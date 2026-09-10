package com.zzy.llmreader.web

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Archive of Our Own (archiveofourown.org) 小说源。
 *
 * 支持的 URL 形式（都会归一化到 `/works/<workId>`）：
 *   - https://archiveofourown.org/works/66804367
 *   - https://archiveofourown.org/works/66804367/chapters/172392544
 *   - https://archiveofourown.org/works/66804367?view_adult=true
 *
 * 抓取策略：
 *   - 目录：GET `/works/<id>/navigate` 解析 `#chapter_index ol li a`，得到章节 (title, url)。
 *     单章作品 `/navigate` 仍会返回 1 条，比正文页更稳。
 *   - 单章：直接抓 `/works/<id>/chapters/<cid>?view_adult=true`，正文在
 *     `#chapters .userstuff[role=article]` 或 `#chapters .userstuff`。
 *   - 单章作品 fallback：用 `?view_full_work=true` 抓全文。
 *
 * 通过 `view_adult=true` cookie 跳过年龄确认页。
 */
class Ao3Source(
    /** 用户在设置里粘贴的额外 cookie 字符串（如 `cf_clearance=...; ...`）。 */
    private val cookieProvider: () -> String? = { null },
    /** WebView 过 CF 后的 UA；cf_clearance 与 UA 绑定，必须保持一致。 */
    private val userAgentProvider: () -> String? = { null },
) : HtmlWebSource() {

    override val id: String = "ao3"
    override val displayName: String = "AO3"
    override val sampleUrl: String = "https://archiveofourown.org/works/66804367"

    override val discovery: WebDiscovery? = Ao3Discovery(this)

    /** 供 [Ao3Discovery] 复用走 cookie/UA 的拉页。 */
    internal suspend fun fetchListingDocument(url: String): Document = fetchDocument(url)

    override val userAgent: String
        get() = userAgentProvider()?.takeIf { it.isNotBlank() } ?: super.userAgent

    override val extraHeaders: Map<String, String>
        get() {
            val extra = cookieProvider()?.trim()
            val cookie = buildString {
                // 跳过 AO3 的 "This work could have adult content" 提示页
                append("view_adult=true")
                if (!extra.isNullOrBlank()) append("; ").append(extra.trim('"'))
            }
            return mapOf(
                "Cookie" to cookie,
                "Referer" to "https://archiveofourown.org/",
            )
        }

    override fun matches(url: HttpUrl): Boolean =
        url.host.endsWith("archiveofourown.org") &&
            WORK_PATH_REGEX.containsMatchIn("/" + url.encodedPathSegments.joinToString("/"))

    override fun canonicalize(input: String): String {
        val parsed = parseInputUrl(input)
        require(matches(parsed)) { "仅支持 AO3 /works/<id> 链接" }
        val workId = extractWorkId(parsed) ?: error("无法解析 AO3 work id")
        return "https://archiveofourown.org/works/$workId"
    }

    override suspend fun fetchIndex(
        canonicalUrl: String,
        onProgress: FetchProgress,
    ): CachedWebBook {
        val workId = extractWorkId(canonicalUrl.toHttpUrl())
            ?: error("无法解析 AO3 work id")
        onProgress(0, -1, "读取目录…")

        // 优先尝试 /navigate；这一步对部分 work 会被 Cloudflare 返回 525。
        val navResult = runCatching {
            fetchDocument("https://archiveofourown.org/works/$workId/navigate")
        }
        val navDoc = navResult.getOrNull()
        val title: String
        val chapterLinks: List<Pair<String, String>> // (title, absoluteUrl)
        if (navDoc != null && navDoc.select("#chapter_index ol li a, ol.chapter.index li a").isNotEmpty()) {
            title = extractWorkTitle(navDoc)
            chapterLinks = navDoc.select("#chapter_index ol li a, ol.chapter.index li a").map { a ->
                val href = a.absUrl("href").ifBlank { a.attr("href") }
                a.text().ifBlank { "Chapter" } to absoluteAo3Url(href)
            }
        } else {
            // /navigate 失败或为空：回退到拉一个章节页，从 <select id=selected_id> 提取目录。
            onProgress(0, -1, "目录页不可用，回退到章节页…")
            val firstChapterUrl = "https://archiveofourown.org/works/$workId/chapters/0?view_adult=true"
            // /chapters/0 会被 AO3 重定向到该 work 的第一章；HtmlWebSource 默认 followRedirects=true
            val chapDoc = runCatching { fetchDocument(firstChapterUrl) }.getOrElse {
                // 再退一步：单章作品（无 selected_id 下拉），整页就是正文
                onProgress(0, -1, "尝试单章全文页…")
                val fullDoc = fetchDocument(
                    "https://archiveofourown.org/works/$workId?view_full_work=true",
                )
                val fullTitle = extractWorkTitle(fullDoc)
                val fullHtml = extractFullWorkHtml(fullDoc)
                return CachedWebBook(
                    source = id,
                    url = canonicalUrl,
                    title = fullTitle,
                    fetchedAt = System.currentTimeMillis(),
                    chapters = listOf(
                        CachedWebChapter(
                            title = fullTitle,
                            html = fullHtml,
                            chapterUrl = "https://archiveofourown.org/works/$workId",
                        ),
                    ),
                )
            }
            title = extractWorkTitle(chapDoc)
            val options = chapDoc.select("select#selected_id option, #selected_id option")
            chapterLinks = if (options.isNotEmpty()) {
                options.map { opt ->
                    val cid = opt.attr("value").trim()
                    val rawTitle = opt.text().trim().ifBlank { "Chapter $cid" }
                    rawTitle to "https://archiveofourown.org/works/$workId/chapters/$cid"
                }
            } else {
                // 没有下拉，说明就是单章；把这页正文塞回去
                val html = extractChapterHtml(chapDoc)
                return CachedWebBook(
                    source = id,
                    url = canonicalUrl,
                    title = title,
                    fetchedAt = System.currentTimeMillis(),
                    chapters = listOf(
                        CachedWebChapter(
                            title = title,
                            html = html,
                            chapterUrl = chapDoc.location().ifBlank { firstChapterUrl },
                        ),
                    ),
                )
            }
        }

        val chapters = chapterLinks.mapIndexed { idx, (t, url) ->
            CachedWebChapter(
                title = t.ifBlank { "Chapter ${idx + 1}" },
                html = null,
                chapterUrl = url,
            )
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
        val withAdult = appendQuery(url, "view_adult", "true")
        val doc = fetchDocument(withAdult)
        val html = extractChapterHtml(doc)
        return chapter.copy(html = html, chapterUrl = url)
    }

    // ---------- 解析工具 ----------

    private fun extractWorkTitle(doc: Document): String {
        val candidates = listOf(
            doc.selectFirst("h2.title.heading"),
            doc.selectFirst("#workskin .title"),
            doc.selectFirst("h1"),
        ).mapNotNull { it?.text()?.trim()?.takeIf { t -> t.isNotBlank() } }
        return (candidates.firstOrNull()
            ?: doc.title().substringBefore(" - ").trim().ifBlank { "AO3 作品" })
    }

    private fun extractChapterHtml(doc: Document): String {
        // 单章页面结构：#chapters > div.chapter > .chapter-title + .summary + .notes + .userstuff[role=article]
        val article = doc.selectFirst("#chapters .userstuff[role=article]")
            ?: doc.selectFirst("#chapters div[role=article].userstuff")
            ?: doc.selectFirst("#chapters .userstuff")
            ?: doc.selectFirst(".userstuff[role=article]")
            ?: doc.selectFirst(".userstuff")
            ?: error("未找到 AO3 正文（可能页面被改版或被遮挡）")
        return cleanUserstuff(article)
    }

    private fun extractFullWorkHtml(doc: Document): String {
        // 全文模式：每章是 #chapter-1, #chapter-2 ... 各自带 .userstuff
        val chapters = doc.select("#chapters > div.chapter")
        val merged = StringBuilder()
        if (chapters.isNotEmpty()) {
            for (ch in chapters) {
                val title = ch.selectFirst(".chapter.preface .title")?.text()?.trim()
                if (!title.isNullOrBlank()) {
                    merged.append("<h2>").append(escapeHtml(title)).append("</h2>\n")
                }
                ch.selectFirst(".userstuff[role=article], .userstuff")?.let {
                    merged.append(cleanUserstuff(it)).append("\n")
                }
            }
            if (merged.isNotBlank()) return merged.toString()
        }
        // 退化到 #workskin .userstuff
        val article = doc.selectFirst("#workskin .userstuff")
            ?: error("未找到 AO3 正文")
        return cleanUserstuff(article)
    }

    private fun cleanUserstuff(article: Element): String {
        article.select(
            "script, style, noscript, iframe, svg, form, button, input, select, textarea, " +
                "[class*=feedback], [class*=kudos]",
        ).remove()
        val paragraphs = article.select("p, h1, h2, h3, h4, blockquote, pre")
            .filter { p ->
                p.text().isNotBlank() || p.html().contains("<br", ignoreCase = true)
            }
        val html = if (paragraphs.isNotEmpty()) {
            paragraphs.joinToString("\n") { it.outerHtml() }
        } else {
            article.html()
        }
        require(Jsoup.parse(html).text().isNotBlank()) { "AO3 正文为空" }
        return html
    }

    private fun extractWorkId(url: HttpUrl): String? {
        val segs = url.encodedPathSegments
        val idx = segs.indexOf("works")
        if (idx >= 0 && idx + 1 < segs.size) {
            return segs[idx + 1].takeIf { it.all(Char::isDigit) }
        }
        return null
    }

    private fun absoluteAo3Url(href: String): String {
        if (href.isBlank()) return href
        if (href.startsWith("http")) return href
        val path = if (href.startsWith("/")) href else "/$href"
        return "https://archiveofourown.org$path"
    }

    private fun appendQuery(url: String, key: String, value: String): String {
        val parsed = url.toHttpUrlOrNull() ?: return url
        val existing = parsed.queryParameter(key)
        if (existing == value) return url
        return parsed.newBuilder()
            .removeAllQueryParameters(key)
            .addQueryParameter(key, value)
            .build()
            .toString()
    }

    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

    private companion object {
        // 只接受 /works/<digits> 或 /works/<digits>/chapters/<digits>
        val WORK_PATH_REGEX = Regex("""/works/\d+(?:/chapters/\d+)?""")
    }
}

/**
 * AO3 发现页：走官方 `/works/search` 页面（HTML）。
 * URL：https://archiveofourown.org/works/search?work_search[query]=...&work_search[sort_column]=kudos_count&page=N
 * 页面结构：ol.work.index.group > li.work.blurb.group。
 *
 * 字段映射：
 *   id          = work_<id> 中的 <id>
 *   detailUrl   = https://archiveofourown.org/works/<id>
 *   title       = h4.heading > a:first
 *   author      = h4.heading > a[rel=author]
 *   summary     = blockquote.userstuff.summary 纯文本
 *   tags        = ul.tags.commas li a.tag 的前 N 个（取前 12 个，避免过长）
 *   chapterCount= dd.chapters 的总数
 *   wordCount   = dd.words
 *   updatedAt   = p.datetime 解析
 *   metrics     = Kudos / Hits / Bookmarks
 */
internal class Ao3Discovery(private val source: Ao3Source) : WebDiscovery {

    override val capabilities = DiscoveryCapabilities(
        supportsKeyword = true,
        sorts = listOf(
            DiscoverySort(id = "revised_at", label = "最新更新"),
            DiscoverySort(id = "kudos_count", label = "高 Kudos"),
            DiscoverySort(id = "hits", label = "高热度"),
            DiscoverySort(id = "bookmarks_count", label = "高收藏"),
            DiscoverySort(id = "comments_count", label = "高评论"),
            DiscoverySort(id = "word_count", label = "长篇优先"),
            DiscoverySort(id = "created_at", label = "最新发布"),
        ),
        filters = listOf(
            DiscoveryFilterSpec(
                id = "complete",
                label = "状态",
                options = listOf(
                    DiscoveryFilterOption(id = "_all", label = "全部", value = ""),
                    DiscoveryFilterOption(id = "T", label = "已完结"),
                    DiscoveryFilterOption(id = "F", label = "连载中"),
                ),
                multiSelect = false,
            ),
            DiscoveryFilterSpec(
                id = "rating",
                label = "分级",
                options = listOf(
                    DiscoveryFilterOption(id = "_all", label = "全部", value = ""),
                    DiscoveryFilterOption(id = "9", label = "Not Rated"),
                    DiscoveryFilterOption(id = "10", label = "General"),
                    DiscoveryFilterOption(id = "11", label = "Teen"),
                    DiscoveryFilterOption(id = "12", label = "Mature"),
                    DiscoveryFilterOption(id = "13", label = "Explicit"),
                ),
                multiSelect = false,
            ),
            DiscoveryFilterSpec(
                id = "language",
                label = "语言",
                options = listOf(
                    DiscoveryFilterOption(id = "_all", label = "全部", value = ""),
                    DiscoveryFilterOption(id = "en", label = "English"),
                    DiscoveryFilterOption(id = "zh", label = "中文"),
                    DiscoveryFilterOption(id = "ja", label = "日本語"),
                    DiscoveryFilterOption(id = "ko", label = "íêµ­ì´"),
                    DiscoveryFilterOption(id = "ru", label = "Русский"),
                    DiscoveryFilterOption(id = "fr", label = "Français"),
                ),
                multiSelect = false,
            ),
        ),
        defaultPageSize = 20,
        firstPage = 1,
    )

    override suspend fun discover(query: DiscoveryQuery): DiscoveryPage {
        val page = query.page.coerceAtLeast(1)
        val sb = StringBuilder("https://archiveofourown.org/works/search?")
        sb.append("work_search%5Bquery%5D=")
            .append(java.net.URLEncoder.encode(query.keyword.trim(), "UTF-8"))
        val sortId = query.sortId?.takeIf { it.isNotBlank() } ?: "revised_at"
        sb.append("&work_search%5Bsort_column%5D=").append(sortId)
        query.filters["complete"]?.firstOrNull()?.takeIf { it != "_all" && it.isNotBlank() }?.let {
            sb.append("&work_search%5Bcomplete%5D=").append(it)
        }
        query.filters["rating"]?.firstOrNull()?.takeIf { it != "_all" && it.isNotBlank() }?.let {
            sb.append("&work_search%5Brating_ids%5D=").append(it)
        }
        query.filters["language"]?.firstOrNull()?.takeIf { it != "_all" && it.isNotBlank() }?.let {
            sb.append("&work_search%5Blanguage_id%5D=").append(it)
        }
        sb.append("&page=").append(page)

        val doc = source.fetchListingDocument(sb.toString())
        val list = doc.select("ol.work.index.group > li.work.blurb.group")
        val items = list.mapNotNull { parseItem(it) }
        // 总数：“<h3 class="heading">N Works” 或 search summary
        val total = doc.selectFirst("h3.heading")?.text()
            ?.let { Regex("([0-9,]+)\\s+Works").find(it)?.groupValues?.get(1) }
            ?.replace(",", "")
            ?.toLongOrNull()
        val hasMore = doc.select("ol.pagination li.next a, ol.pagination li a[rel=next]").isNotEmpty()
        return DiscoveryPage(query = query, items = items, hasMore = hasMore, totalCount = total)
    }

    private fun parseItem(li: org.jsoup.nodes.Element): DiscoveryItem? {
        val titleA = li.selectFirst("h4.heading a:not([rel=author])") ?: return null
        val href = titleA.attr("href").trim().ifBlank { return null }
        val workId = Regex("/works/(\\d+)").find(href)?.groupValues?.get(1)
            ?: return null
        val title = titleA.text().trim().ifBlank { return null }
        val authorAnchors = li.select("h4.heading a[rel=author]")
        val author = authorAnchors.joinToString(", ") { it.text() }.ifBlank { null }
        // 取第一位作者的主页 URL（有 pseud 时用 /users/<name>/works 更稳）
        val authorUrl = authorAnchors.firstOrNull()?.attr("href")?.trim()?.ifBlank { null }
            ?.let { raw ->
                val abs = if (raw.startsWith("http")) raw else "https://archiveofourown.org$raw"
                // /users/<name>/pseuds/<pseud> → /users/<name>/works
                val m = Regex("/users/([^/]+)").find(abs)
                if (m != null) "https://archiveofourown.org/users/${m.groupValues[1]}/works" else abs
            }
        val summary = li.selectFirst("blockquote.userstuff.summary")?.text()?.trim()?.ifBlank { null }

        // 标签：优先 freeforms / characters / relationships，避免 warnings/rating chips
        val tags = mutableListOf<String>()
        // fandom 放在最前
        li.select("h5.fandoms.heading a.tag").take(3).forEach { tags += it.text().trim() }
        // freeforms（自由 tag）优先
        li.select("ul.tags.commas li.freeforms a.tag").take(8).forEach { tags += it.text().trim() }
        if (tags.size < 6) {
            li.select("ul.tags.commas li.relationships a.tag").take(4)
                .forEach { tags += it.text().trim() }
        }
        if (tags.size < 8) {
            li.select("ul.tags.commas li.characters a.tag").take(4)
                .forEach { tags += it.text().trim() }
        }
        val tagsClean = tags.map { it.trim() }.filter { it.isNotBlank() }.distinct().take(12)

        // 状态：看 complete-yes/iswip
        val status = when {
            li.select("span.complete-yes").isNotEmpty() -> "完结"
            li.select("span.complete-no").isNotEmpty() -> "连载中"
            else -> null
        }

        val words = li.selectFirst("dd.words")?.text()?.replace(",", "")?.toLongOrNull()
        val chaptersText = li.selectFirst("dd.chapters")?.text()?.trim().orEmpty()
        val chapterCount = Regex("^([0-9,]+)").find(chaptersText)?.groupValues?.get(1)
            ?.replace(",", "")?.toIntOrNull()

        val updatedAt = li.selectFirst("p.datetime")?.text()?.let(::parseDate)

        val metrics = buildMap<String, String> {
            li.selectFirst("dd.kudos")?.text()?.takeIf { it.isNotBlank() }?.let { put("Kudos", it) }
            li.selectFirst("dd.hits")?.text()?.takeIf { it.isNotBlank() }?.let { put("热度", it) }
            li.selectFirst("dd.bookmarks")?.text()?.takeIf { it.isNotBlank() }?.let { put("收藏", it) }
            li.selectFirst("dd.comments")?.text()?.takeIf { it.isNotBlank() }?.let { put("评论", it) }
        }

        return DiscoveryItem(
            id = workId,
            detailUrl = "https://archiveofourown.org/works/$workId",
            title = title,
            author = author,
            authorUrl = authorUrl,
            coverUrl = null,
            summary = summary,
            tags = tagsClean,
            chapterCount = chapterCount,
            status = status,
            wordCount = words,
            updatedAt = updatedAt,
            metrics = metrics,
        )
    }

    override suspend fun resolveDetailUrl(item: DiscoveryItem): String = item.detailUrl

    /** 从作品页里挖到作者 `/users/<name>/works`（含 adult bypass）。 */
    override suspend fun resolveAuthorUrl(item: DiscoveryItem): String? {
        val url = item.detailUrl + (if (item.detailUrl.contains("?")) "&" else "?") + "view_adult=true"
        val doc = runCatching { source.fetchListingDocument(url) }.getOrNull() ?: return null
        val link = doc.selectFirst("a[rel=author], h3.byline a, .byline a[href*=/users/]") ?: return null
        val href = link.absUrl("href").ifBlank { link.attr("href") }
        val name = Regex("/users/([^/]+)").find(href)?.groupValues?.get(1) ?: return null
        return "https://archiveofourown.org/users/$name/works"
    }

    /** AO3 的「作者全部作品」页：`/users/<name>/works`，HTML 结构与搜索页相同。 */
    override suspend fun fetchAuthorWorks(authorUrl: String, excludeId: String?, limit: Int): List<DiscoveryItem>? {
        val url = if (authorUrl.contains("/works")) authorUrl else "$authorUrl/works"
        val doc = runCatching { source.fetchListingDocument(url) }.getOrNull() ?: return null
        val list = doc.select("ol.work.index.group > li.work.blurb.group")
        val items = list.mapNotNull { parseItem(it) }
            .filter { excludeId == null || it.id != excludeId }
            .take(limit)
        return items.ifEmpty { null }
    }

    private fun parseDate(s: String): Long? {
        // AO3 datetime 格式例："12 Nov 2018"
        return runCatching {
            val fmt = java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.ENGLISH)
            fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
            fmt.parse(s.trim())?.time
        }.getOrNull()
    }
}
