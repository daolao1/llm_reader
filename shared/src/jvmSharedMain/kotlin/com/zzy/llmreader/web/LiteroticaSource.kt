package com.zzy.llmreader.web

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class LiteroticaSource : HtmlWebSource() {

    override val id: String = "literotica"
    override val displayName: String = "Literotica"
    override val sampleUrl: String = "https://www.literotica.com/s/your-story-slug"

    override val discovery: WebDiscovery? = LiteroticaDiscovery(this)

    override fun matches(url: HttpUrl): Boolean =
        url.host.endsWith("literotica.com") && url.encodedPath.startsWith("/s/")

    override fun canonicalize(input: String): String {
        val parsed = parseInputUrl(input)
        require(matches(parsed)) { "仅支持 Literotica 的 /s/ 小说页面" }
        return parsed.newBuilder()
            .scheme("https")
            .host(if (parsed.host == "literotica.com") "www.literotica.com" else parsed.host)
            .query(null)
            .fragment(null)
            .build()
            .toString()
    }

    override suspend fun fetchIndex(canonicalUrl: String, onProgress: FetchProgress): CachedWebBook {
        onProgress(0, -1, "读取首页…")
        val firstDoc = fetchDocument(canonicalUrl)
        val title = extractTitle(firstDoc)
        val pageCount = discoverPageCount(firstDoc, canonicalUrl.toHttpUrl())
        require(pageCount <= MAX_PAGES) { "分页过多：$pageCount 页" }

        // 第一页正文已在手边，顺便填上，避免用户进首页还要再发一次请求。
        val firstHtml = runCatching { extractArticleHtml(firstDoc) }.getOrNull()
        val chapters = (1..pageCount).map { page ->
            CachedWebChapter(
                title = if (pageCount == 1) title else "第 $page 页",
                html = if (page == 1) firstHtml else null,
                chapterUrl = pageUrl(canonicalUrl, page),
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
        val url = chapter.chapterUrl ?: pageUrl(canonicalUrl, chapterIndex + 1)
        val doc = fetchDocument(url)
        return chapter.copy(html = extractArticleHtml(doc), chapterUrl = url)
    }

    private fun extractTitle(doc: Document): String {
        val title = doc.selectFirst("[itemprop=headline], h1")
            ?.text()
            ?.ifBlank { null }
            ?: doc.selectFirst("meta[property=og:title]")
                ?.attr("content")
                ?.ifBlank { null }
            ?: doc.title().ifBlank { null }
            ?: "Literotica 小说"
        return title
            .removeSuffix(" - Literotica.com")
            .substringBeforeLast(" - ", title)
            .trim()
            .ifBlank { "Literotica 小说" }
    }

    private fun discoverPageCount(doc: Document, canonicalUrl: HttpUrl): Int {
        val hrefPages = doc.select("a[href]").mapNotNull { link ->
            val href = link.absUrl("href").toHttpUrlOrNull() ?: return@mapNotNull null
            if (!isSameStory(canonicalUrl, href)) return@mapNotNull null
            href.queryParameter("page")?.toIntOrNull()
        }
        val inputMax = doc.selectFirst("input[aria-label=Page Number], input[class*=pagination__input]")
            ?.attr("max")
            ?.toIntOrNull()
        return (hrefPages + listOfNotNull(inputMax, 1)).maxOrNull()?.coerceAtLeast(1) ?: 1
    }

    private fun extractArticleHtml(doc: Document): String {
        val article = doc.selectFirst("[itemprop=articleBody]")
            ?: doc.selectFirst("div[class*=article__content]")
            ?: error("未找到小说正文")
        article.select(
            "script, style, noscript, iframe, svg, form, button, input, select, textarea, " +
                "[class*=report], [class*=feedback], [class*=pagination]",
        ).remove()
        val paragraphs = article.select("p")
            .filter { paragraph -> paragraph.text().isNotBlank() || paragraph.html().contains("<br", ignoreCase = true) }
        val html = if (paragraphs.isNotEmpty()) {
            paragraphs.joinToString(separator = "\n") { paragraph -> paragraph.outerHtml() }
        } else {
            article.html()
        }
        require(Jsoup.parse(html).text().isNotBlank()) { "小说正文为空" }
        return html
    }

    private fun pageUrl(canonicalUrl: String, page: Int): String {
        val base = canonicalUrl.toHttpUrl()
        if (page <= 1) return base.toString()
        return base.newBuilder()
            .addQueryParameter("page", page.toString())
            .build()
            .toString()
    }

    private fun isSameStory(canonicalUrl: HttpUrl, other: HttpUrl): Boolean =
        other.host.endsWith("literotica.com") && other.encodedPath == canonicalUrl.encodedPath

    /** 给同包 [LiteroticaDiscovery] 用。 */
    internal suspend fun fetchListingDocument(url: String): Document = fetchDocument(url)

    /** 给 [LiteroticaDiscovery] 调 JSON 搜索 API 用。 */
    internal suspend fun fetchListingJson(url: String): String =
        fetchString(url, accept = "application/json,*/*;q=0.8")

    private companion object {
        const val MAX_PAGES = 200
    }
}

/**
 * Literotica 发现页：用站点新版 JSON API（与官方 search.literotica.com 同源）。
 *
 *   GET https://literotica.com/api/3/search/stories?params=<urlencoded JSON>
 *
 *   JSON: { "q": "<keyword>", "page": <1-based>, "categories": [<numeric-id>],
 *           "sort": "<field> <order>" }
 *
 * 字段映射：
 *   id            = stories 的 url slug（详情页路径稳定，比数字 id 直观）
 *   detailUrl     = https://www.literotica.com/s/<url>
 *   title         = title
 *   author        = authorname
 *   coverUrl      = author.userpic（站点本身没有故事封面，用作者头像兜底）
 *   summary       = description
 *   tags          = category_info.pageUrl + 前 6 个 story tag
 *   wordCount     = words_count
 *   chapterCount  = series.items.length（系列；单篇为空）
 *   updatedAt     = parse(date_approve, "MM/dd/yyyy")
 *   metrics["评分"] = rate_all（保留 1 位小数）
 *   metrics["热度"] = view_count（k/M 在 UI 层格式化，这里直接给原值字符串）
 */
internal class LiteroticaDiscovery(private val source: LiteroticaSource) : WebDiscovery {

    override val capabilities = DiscoveryCapabilities(
        supportsKeyword = true,
        sorts = listOf(
            DiscoverySort(id = "relevance", label = "综合"),
            DiscoverySort(id = "date desc", label = "最新"),
            DiscoverySort(id = "rate desc", label = "高评分"),
        ),
        filters = listOf(
            DiscoveryFilterSpec(
                id = "category",
                label = "分类",
                options = listOf(DiscoveryFilterOption(id = "_all", label = "全部", value = "")) +
                    CATEGORIES.map { (id, slug, label) ->
                        DiscoveryFilterOption(id = slug, label = label, value = id.toString())
                    },
                multiSelect = false,
            ),
        ),
        defaultPageSize = 50,
        firstPage = 1,
    )

    override suspend fun discover(query: DiscoveryQuery): DiscoveryPage {
        val params = JSONObject()
        params.put("q", query.keyword)
        params.put("page", query.page.coerceAtLeast(1))
        val catSlug = query.filters["category"]?.firstOrNull()
        val catId = CATEGORIES.firstOrNull { it.second == catSlug }?.first
        if (catId != null) {
            params.put("categories", JSONArray().put(catId))
        }
        val sortId = query.sortId
        if (!sortId.isNullOrBlank() && sortId != "relevance") {
            params.put("sort", sortId)
        }

        val url = "$API_BASE/search/stories?params=" +
            URLEncoder.encode(params.toString(), "UTF-8")
        val raw = source.fetchListingJson(url)
        val root = JSONObject(raw)
        val data = root.optJSONArray("data") ?: JSONArray()
        val meta = root.optJSONObject("meta")
        val total = meta?.optLong("total", -1L)?.takeIf { it >= 0 }
        val pageSize = meta?.optInt("pageSize", 50)?.takeIf { it > 0 } ?: 50

        val items = (0 until data.length()).mapNotNull { i ->
            data.optJSONObject(i)?.let(::parseItem)
        }
        val hasMore = items.size >= pageSize &&
            (total == null || query.page * pageSize < total) &&
            query.page < MAX_PAGE
        return DiscoveryPage(
            query = query,
            items = items,
            hasMore = hasMore,
            totalCount = total,
        )
    }

    private fun parseItem(o: JSONObject): DiscoveryItem? {
        val slug = o.optString("url").trim().ifBlank { return null }
        val title = o.optString("title").trim().ifBlank { return null }
        val author = o.optJSONObject("author")
        val authorName = o.optString("authorname").trim()
            .ifBlank { author?.optString("username")?.trim().orEmpty() }
        val authorId = author?.optLong("userid", -1L)?.takeIf { it > 0 }
        val authorUsername = author?.optString("username")?.trim()?.ifBlank { null }
        // Literotica 没有可直接 GET 的稳定"作者主页 JSON"，但 /api/3/users/<uid>/stories
        // 列出该作者全部作品（与搜索 API 用一样的 item 结构）。authorUrl 用作者 id 编进去。
        val authorUrl = when {
            authorId != null -> "https://literotica.com/api/3/users/$authorId/stories"
            authorUsername != null -> "https://literotica.com/api/3/users/u/$authorUsername/stories"
            else -> null
        }
        val cover = author?.optString("userpic")?.trim()
            ?.takeIf { it.isNotBlank() && it.startsWith("http") && !it.contains("da_default") }
        val description = o.optString("description").trim().ifBlank { null }
        val rating = o.optDouble("rate_all").takeIf { !it.isNaN() && it > 0.0 }
            ?.let { String.format(Locale.US, "%.1f", it) }
        val views = o.optLong("view_count", -1L).takeIf { it >= 0 }
        val comments = o.optLong("comment_count", -1L).takeIf { it >= 0 }
        val words = o.optLong("words_count", -1L).takeIf { it > 0 }
        val cat = o.optJSONObject("category_info")?.optString("pageUrl")?.trim()
            ?.takeIf { it.isNotBlank() }
        val storyTagsArr = o.optJSONArray("tags")
        val storyTags = mutableListOf<String>()
        if (storyTagsArr != null) {
            for (i in 0 until storyTagsArr.length().coerceAtMost(6)) {
                storyTagsArr.optJSONObject(i)?.optString("tag")?.trim()
                    ?.takeIf { it.isNotBlank() }?.let(storyTags::add)
            }
        }
        val tags = (listOfNotNull(cat) + storyTags).distinct()
        val series = o.optJSONObject("series")?.optJSONArray("items")
        val chapterCount = series?.length()?.takeIf { it > 0 }
        val updatedAt = parseDate(o.optString("date_approve"))

        val metrics = buildMap<String, String> {
            if (rating != null) put("评分", rating)
            views?.let { put("热度", formatCount(it)) }
            comments?.let { put("评论", formatCount(it)) }
        }

        return DiscoveryItem(
            id = slug,
            detailUrl = "https://www.literotica.com/s/$slug",
            title = title,
            author = authorName.ifBlank { null },
            authorUrl = authorUrl,
            coverUrl = cover,
            summary = description,
            tags = tags,
            chapterCount = chapterCount,
            wordCount = words,
            updatedAt = updatedAt,
            metrics = metrics,
        )
    }

    override suspend fun resolveDetailUrl(item: DiscoveryItem): String = item.detailUrl

    /** 从故事详情页里挖出作者数字 ID（埋在 a[href*=memberpage.php?uid=] 里）。 */
    override suspend fun resolveAuthorUrl(item: DiscoveryItem): String? {
        val doc = runCatching { source.fetchListingDocument(item.detailUrl) }.getOrNull() ?: return null
        // 1) memberpage.php?uid=12345
        Regex("memberpage\\.php\\?uid=(\\d+)").find(doc.html())?.groupValues?.get(1)?.toLongOrNull()?.let {
            return "https://literotica.com/api/3/users/$it/stories"
        }
        // 2) 兑底用作者 username slug
        doc.selectFirst("a[href*=/authors/]")?.let { a ->
            val href = a.absUrl("href").ifBlank { a.attr("href") }
            val slug = Regex("/authors/([^/]+)").find(href)?.groupValues?.get(1)
                ?.takeIf { it.isNotBlank() }
            if (slug != null) return "https://literotica.com/api/3/users/u/$slug/stories"
        }
        return null
    }

    /**
     * Literotica `/api/3/users/<uid>/stories`：返回该作者全部作品（含分页 meta），
     * item 字段结构与搜索 API 完全一致。
     */
    override suspend fun fetchAuthorWorks(authorUrl: String, excludeId: String?, limit: Int): List<DiscoveryItem>? {
        val uid = Regex("/users/(\\d+)/stories").find(authorUrl)?.groupValues?.get(1)?.toLongOrNull()
            ?: return null
        val url = "$API_BASE/users/$uid/stories?page=1&pageSize=${limit.coerceAtLeast(20)}"
        val raw = runCatching { source.fetchListingJson(url) }.getOrNull() ?: return null
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val data = root.optJSONArray("data") ?: return null
        val items = (0 until data.length()).mapNotNull { i ->
            data.optJSONObject(i)?.let(::parseItem)
        }.filter { excludeId == null || it.id != excludeId }.take(limit)
        return items.ifEmpty { null }
    }

    private fun parseDate(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        return runCatching {
            val fmt = SimpleDateFormat("MM/dd/yyyy", Locale.US)
            fmt.timeZone = TimeZone.getTimeZone("UTC")
            fmt.parse(s)?.time
        }.getOrNull()
    }

    private fun formatCount(n: Long): String = when {
        n < 1000 -> n.toString()
        n < 1_000_000 -> String.format(Locale.US, "%.1fk", n / 1000.0)
        else -> String.format(Locale.US, "%.1fM", n / 1_000_000.0)
    }

    private companion object {
        const val API_BASE = "https://literotica.com/api/3"
        const val MAX_PAGE = 50

        // (numericId, urlSlug, displayName) — 来自 /api/3/tagsportal/categories
        // 仅保留 type=story 且 language=1 的项，按字母序。
        val CATEGORIES: List<Triple<Int, String, String>> = listOf(
            Triple(37, "anal-sex-stories", "Anal"),
            Triple(39, "audio-sex-stories", "Audio"),
            Triple(31, "bdsm-stories", "BDSM"),
            Triple(28, "chain-stories", "Chain Stories"),
            Triple(58, "crossdressing", "Crossdressing"),
            Triple(2, "erotic-couplings", "Erotic Couplings"),
            Triple(51, "erotic-horror", "Erotic Horror"),
            Triple(33, "erotic-novels", "Novels & Novellas"),
            Triple(4, "exhibitionist-voyeur", "Exhibitionist & Voyeur"),
            Triple(27, "celebrity-stories", "Fan Fiction & Celebrities"),
            Triple(5, "fetish-stories", "Fetish"),
            Triple(40, "first-time-sex-stories", "First Time"),
            Triple(6, "gay-sex-stories", "Gay Male"),
            Triple(7, "group-sex-stories", "Group Sex"),
            Triple(34, "adult-humor", "Humor & Satire"),
            Triple(45, "illustrated-erotic-fiction", "Illustrated"),
            Triple(10, "interracial-erotic-stories", "Interracial Love"),
            Triple(11, "lesbian-sex-stories", "Lesbian Sex"),
            Triple(53, "erotic-letters", "Letters & Transcripts"),
            Triple(12, "loving-wives", "Loving Wives"),
            Triple(26, "mature-sex", "Mature"),
            Triple(29, "mind-control", "Mind Control"),
            Triple(35, "non-erotic-stories", "Non-Erotic"),
            Triple(14, "non-human-stories", "NonHuman"),
            Triple(13, "non-consent-stories", "Reluctance / NonConsent"),
            Triple(15, "adult-romance", "Romance"),
            Triple(38, "science-fiction-fantasy", "Sci-Fi & Fantasy"),
            Triple(9, "taboo-sex-stories", "Taboo / Incest"),
            Triple(16, "masturbation-stories", "Toys & Masturbation"),
            Triple(48, "transgender", "Transgender"),
            Triple(8, "adult-how-to", "How To"),
            Triple(3, "reviews-and-essays", "Reviews & Essays"),
        )
    }
}
