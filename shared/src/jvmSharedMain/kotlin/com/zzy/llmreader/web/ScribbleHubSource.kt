package com.zzy.llmreader.web

import okhttp3.HttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * ScribbleHub ([https://www.scribblehub.com](https://www.scribblehub.com)) 小说支持。
 *
 * - 入口：`https://www.scribblehub.com/series/<seriesId>/<slug>/`
 * - 章节：`https://www.scribblehub.com/read/<readId>-<slug>/`
 * - 目录：内嵌在入口页 `<ol class="toc_ol">` 的 `<a class="toc_a">` 链接；
 *   长篇会有分页（`.simple-pagination`），通过 `?pg=N` 拉后续页。
 * - 章节正文容器：`div#chp_raw` 或 `.chp_raw`。
 * - ScribbleHub 挂在 Cloudflare 后面，必要时在设置里通过 WebView 抓 `cf_clearance`。
 */
class ScribbleHubSource(
    private val cookieProvider: () -> String? = { null },
    private val userAgentProvider: () -> String? = { null },
) : HtmlWebSource() {

    override val id: String = "scribblehub"
    override val displayName: String = "ScribbleHub"
    override val sampleUrl: String =
        "https://www.scribblehub.com/series/1048265/cultivation-struggles-of-a-lowly-fox/"

    override val userAgent: String
        get() = userAgentProvider()?.takeIf { it.isNotBlank() } ?: super.userAgent

    override val extraHeaders: Map<String, String>
        get() {
            val extra = cookieProvider()?.trim()
            val map = mutableMapOf("Referer" to "https://www.scribblehub.com/")
            if (!extra.isNullOrBlank()) map["Cookie"] = extra.trim('"')
            return map
        }

    override fun matches(url: HttpUrl): Boolean {
        if (!url.host.endsWith("scribblehub.com")) return false
        val segs = url.pathSegments
        return segs.size >= 2 && segs[0] == "series" && segs[1].all(Char::isDigit)
    }

    override fun canonicalize(input: String): String {
        val parsed = parseInputUrl(input)
        require(matches(parsed)) { "仅支持 scribblehub.com /series/<id>/<slug>/ 的页面" }
        val sid = parsed.pathSegments[1]
        val slug = parsed.pathSegments.getOrNull(2)?.takeIf { it.isNotBlank() }
        return if (slug != null) {
            "https://www.scribblehub.com/series/$sid/$slug/"
        } else {
            "https://www.scribblehub.com/series/$sid/"
        }
    }

    override suspend fun fetchIndex(canonicalUrl: String, onProgress: FetchProgress): CachedWebBook {
        onProgress(0, -1, "读取目录…")
        val firstDoc = fetchDocument(canonicalUrl)
        val title = extractTitle(firstDoc)

        val collected = LinkedHashMap<String, String>() // url -> title
        addChapters(firstDoc, collected)

        // 检测分页：.simple-pagination 里最大页码。
        val totalPages = firstDoc.select(
            ".simple-pagination li a, .lice-bk a, .pagination a, ul.pagination a",
        ).mapNotNull { a ->
            a.text().trim().toIntOrNull()
                ?: a.attr("data-page").trim().toIntOrNull()
        }.maxOrNull() ?: 1

        if (totalPages > 1) {
            for (page in 2..totalPages) {
                onProgress(0, totalPages, "读取目录第 $page / $totalPages 页…")
                val pageDoc = runCatching {
                    fetchDocument("$canonicalUrl?pg=$page")
                }.getOrNull() ?: continue
                addChapters(pageDoc, collected)
            }
        }

        require(collected.isNotEmpty()) {
            "未找到章节列表（如被 Cloudflare 拦截，请在设置里点「网页过 Cloudflare」抓 cookie）"
        }

        val chapters = collected.entries.map { (url, t) ->
            CachedWebChapter(title = t.ifBlank { "Chapter" }, html = null, chapterUrl = url)
        }
        return CachedWebBook(
            source = id,
            url = canonicalUrl,
            title = title,
            fetchedAt = System.currentTimeMillis(),
            chapters = chapters,
        )
    }

    override suspend fun fetchChapter(
        canonicalUrl: String,
        chapterIndex: Int,
        chapter: CachedWebChapter,
    ): CachedWebChapter {
        val url = chapter.chapterUrl ?: error("缺少章节 URL")
        val doc = fetchDocument(url)
        val refinedTitle = doc.selectFirst(".chapter-title, h1.chapter-title, h1")
            ?.text()?.trim()?.ifBlank { null }
            ?: chapter.title
        val raw = doc.selectFirst("div#chp_raw, div.chp_raw, #chp_contents")
            ?: error("未找到 ScribbleHub 正文（页面结构变化或被 Cloudflare 拦截）")
        raw.select(
            "script, style, noscript, iframe, svg, form, button, input, select, textarea, " +
                "[class*=ad], [class*=banner], [id*=banner]",
        ).remove()
        val paragraphs = raw.select("p, h1, h2, h3, h4, blockquote, pre")
            .filter { it.text().isNotBlank() || it.html().contains("<br", ignoreCase = true) }
        val html = if (paragraphs.isNotEmpty()) {
            paragraphs.joinToString("\n") { it.outerHtml() }
        } else {
            raw.html()
        }
        require(Jsoup.parse(html).text().isNotBlank()) { "ScribbleHub 正文为空" }
        return chapter.copy(title = refinedTitle, html = html, chapterUrl = url)
    }

    private fun addChapters(doc: Document, sink: MutableMap<String, String>) {
        // <ol class="toc_ol"> <li class="toc_w"> <a class="toc_a" href="...">
        val anchors = doc.select(
            "ol.toc_ol li.toc_w a.toc_a, " +
                "ol.toc_ol li a, " +
                "a.toc_a, " +
                "a[href*='/read/']",
        )
        for (a in anchors) {
            val href = a.absUrl("href").ifBlank { a.attr("href") }
            if (!href.contains("/read/")) continue
            val abs = if (href.startsWith("http")) href else "https://www.scribblehub.com$href"
            val t = a.text().trim()
                .ifBlank { a.attr("title").trim() }
                .ifBlank { "Chapter" }
            if (!sink.containsKey(abs)) sink[abs] = t
        }
    }

    private fun extractTitle(doc: Document): String {
        return doc.selectFirst(".fic_title, .fic-title, h1.fic_title")
            ?.text()?.trim()?.ifBlank { null }
            ?: doc.selectFirst("meta[property=og:title]")
                ?.attr("content")?.trim()?.ifBlank { null }
            ?: doc.title().substringBefore(" |").trim().ifBlank { null }
            ?: "ScribbleHub 作品"
    }
}
