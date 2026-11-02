package com.zzy.llmreader.web

import okhttp3.HttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * Royal Road ([https://www.royalroad.com](https://www.royalroad.com)) 小说支持。
 *
 * - 入口：`https://www.royalroad.com/fiction/<id>/<slug>`
 * - 章节：`https://www.royalroad.com/fiction/<id>/<slug>/chapter/<cid>/<chapter-slug>`
 * - 目录：入口页 `<table id="chapters"> tbody tr[data-url]`，按 DOM 顺序就是发布顺序。
 * - 章节正文容器：`div.chapter-inner.chapter-content`。
 */
class RoyalRoadSource : HtmlWebSource() {

    override val id: String = "royalroad"
    override val displayName: String = "Royal Road"
    override val sampleUrl: String =
        "https://www.royalroad.com/fiction/154956/the-lich-king-reincarnates-as-a-baby"

    override fun matches(url: HttpUrl): Boolean {
        if (!url.host.endsWith("royalroad.com")) return false
        val segs = url.pathSegments
        return segs.size >= 2 && segs[0] == "fiction" && segs[1].all(Char::isDigit)
    }

    override fun canonicalize(input: String): String {
        val parsed = parseInputUrl(input)
        require(matches(parsed)) { "仅支持 royalroad.com /fiction/<id>/<slug> 的页面" }
        val fid = parsed.pathSegments[1]
        val slug = parsed.pathSegments.getOrNull(2)?.takeIf { it.isNotBlank() }
        return if (slug != null) {
            "https://www.royalroad.com/fiction/$fid/$slug"
        } else {
            "https://www.royalroad.com/fiction/$fid"
        }
    }

    override suspend fun fetchIndex(canonicalUrl: String, onProgress: FetchProgress): CachedWebBook {
        onProgress(0, -1, "读取目录…")
        val doc = fetchDocument(canonicalUrl)
        val title = extractTitle(doc)

        val collected = LinkedHashMap<String, String>()
        val rows = doc.select("table#chapters tbody tr[data-url], #chapters tr[data-url]")
        for (row in rows) {
            val href = row.attr("data-url").trim().ifBlank {
                row.selectFirst("a[href]")?.attr("href").orEmpty()
            }
            if (href.isBlank()) continue
            val abs = if (href.startsWith("http")) href else "https://www.royalroad.com$href"
            val t = row.selectFirst("a")?.text()?.trim()
                ?.ifBlank { null }
                ?: row.text().trim().substringBefore('\t').ifBlank { "Chapter" }
            if (!collected.containsKey(abs)) collected[abs] = t
        }
        if (collected.isEmpty()) {
            // Fallback：拿所有 /chapter/ 链接。
            for (a in doc.select("a[href*='/chapter/']")) {
                val href = a.absUrl("href").ifBlank { a.attr("href") }
                if (!href.contains("/chapter/")) continue
                val abs = if (href.startsWith("http")) href else "https://www.royalroad.com$href"
                val t = a.text().trim().ifBlank { "Chapter" }
                if (!collected.containsKey(abs)) collected[abs] = t
            }
        }
        require(collected.isNotEmpty()) { "未找到 Royal Road 章节列表" }

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
        val refinedTitle = doc.selectFirst("h1.font-white, h1.fic-header, h1")
            ?.text()?.trim()?.ifBlank { null }
            ?: chapter.title
        val raw = doc.selectFirst("div.chapter-inner.chapter-content, div.chapter-content")
            ?: error("未找到 Royal Road 正文")
        // RR 用反盗版 CSS 隐藏伪段：display:none 的段落是诱饵，移除它们。
        raw.select("[style*=display:none], [style*=display: none]").remove()
        raw.select(
            "script, style, noscript, iframe, svg, form, button, input, select, textarea, " +
                "[class*=ad], [class*=banner], [id*=banner]",
        ).remove()
        val paragraphs = topLevelContentBlocks(raw)
        val html = if (paragraphs.isNotEmpty()) {
            paragraphs.joinToString("\n") { it.outerHtml() }
        } else {
            raw.html()
        }
        require(Jsoup.parse(html).text().isNotBlank()) { "Royal Road 正文为空" }
        return chapter.copy(title = refinedTitle, html = html, chapterUrl = url)
    }

    private fun extractTitle(doc: Document): String {
        return doc.selectFirst("h1.font-white, h1.fic-title, .fic-header h1")
            ?.text()?.trim()?.ifBlank { null }
            ?: doc.selectFirst("meta[property=og:title]")
                ?.attr("content")?.trim()?.ifBlank { null }
            ?: doc.title().substringBefore(" |").trim().ifBlank { null }
            ?: "Royal Road 作品"
    }
}
