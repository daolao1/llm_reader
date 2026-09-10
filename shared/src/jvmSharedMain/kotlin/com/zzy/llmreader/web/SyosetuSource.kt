package com.zzy.llmreader.web

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Syosetu（小説家になろう）系站点，包括成人站 novel18.syosetu.com。
 *
 * - 入口：`https://<host>/<ncode>/`
 * - 章节：`https://<host>/<ncode>/<n>/`，目录页用 `a.p-eplist__subtitle` 列出
 * - 短編：没有目录，正文直接在入口页 `.js-novel-text`
 * - novel18 需要 `over18=yes` cookie
 */
class SyosetuSource : HtmlWebSource() {

    override val id: String = "syosetu"
    override val displayName: String = "Syosetu / Novel18"
    override val sampleUrl: String = "https://novel18.syosetu.com/n7457mc/"

    override val extraHeaders: Map<String, String> = mapOf(
        "Cookie" to "over18=yes; ks=1",
    )

    override val discovery: WebDiscovery? = SyosetuNovel18Discovery(this)

    /** 给 [SyosetuNovel18Discovery] 调 R18 API 用。 */
    internal suspend fun fetchListingJson(url: String): String =
        fetchString(url, accept = "application/json,*/*;q=0.8")

    /** 给 [SyosetuNovel18Discovery] 抓作者主页 HTML 用。 */
    internal suspend fun fetchListingDocument(url: String): Document = fetchDocument(url)

    override fun matches(url: HttpUrl): Boolean {
        if (!url.host.endsWith("syosetu.com")) return false
        val first = url.pathSegments.firstOrNull() ?: return false
        return NCODE.matches(first)
    }

    override fun canonicalize(input: String): String {
        val parsed = parseInputUrl(input)
        require(matches(parsed)) { "仅支持 Syosetu / Novel18 的 ncode 页面" }
        val ncode = parsed.pathSegments[0]
        return parsed.newBuilder()
            .scheme("https")
            .host(parsed.host)
            .encodedPath("/$ncode/")
            .query(null)
            .fragment(null)
            .build()
            .toString()
    }

    override suspend fun fetchIndex(canonicalUrl: String, onProgress: FetchProgress): CachedWebBook {
        onProgress(0, -1, "读取目录…")
        val indexDoc = fetchDocument(canonicalUrl)
        val title = extractNovelTitle(indexDoc)

        if (indexDoc.select("a.p-eplist__subtitle").isEmpty()) {
            // 短編：正文直接在入口页
            val html = extractChapterHtml(indexDoc)
                ?: error("未找到正文（可能需要登录或被屏蔽）")
            return CachedWebBook(
                source = id,
                url = canonicalUrl,
                title = title,
                fetchedAt = System.currentTimeMillis(),
                chapters = listOf(
                    CachedWebChapter(title = title, html = html, chapterUrl = canonicalUrl),
                ),
            )
        }

        // 目录按 100 章/页分页（`?p=N`），从 c-pager 里挖最后一页页码。
        val totalPages = indexDoc.select("a.c-pager__item--last, a.c-pager__item")
            .mapNotNull { a ->
                a.absUrl("href").toHttpUrlOrNull()?.queryParameter("p")?.toIntOrNull()
            }.maxOrNull() ?: 1

        var currentGroup: String? = null
        val chapters = mutableListOf<CachedWebChapter>()

        fun addFromDoc(doc: Document) {
            val listRoot = doc.selectFirst(".p-eplist") ?: doc
            for (el in listRoot.select(".p-eplist__chapter-title, a.p-eplist__subtitle")) {
                if (el.hasClass("p-eplist__chapter-title")) {
                    currentGroup = el.text().trim().ifBlank { null }
                } else {
                    val abs = el.absUrl("href")
                    val text = el.text().trim().ifBlank { "第 ${chapters.size + 1} 章" }
                    chapters += CachedWebChapter(
                        title = text,
                        html = null,
                        chapterUrl = abs,
                        groupTitle = currentGroup,
                    )
                }
            }
        }

        addFromDoc(indexDoc)
        if (totalPages > 1) {
            val base = canonicalUrl.trimEnd('/')
            for (page in 2..totalPages) {
                onProgress(0, totalPages, "读取目录第 $page / $totalPages 页…")
                val pageDoc = fetchDocument("$base/?p=$page")
                addFromDoc(pageDoc)
            }
        }

        val total = chapters.size
        require(total <= MAX_CHAPTERS) { "章节过多（$total 章），暂不支持" }

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
        val url = chapter.chapterUrl
            ?: error("缺少章节 URL，请尝试刷新目录")
        val doc = fetchDocument(url)
        // 注意：`.p-novel__title` 是整本书名，会出现在每章页眉里，不能用它当章节标题。
        // 章节标题在 `.p-novel__subtitle`（连载）或 h1.p-novel__title--rensai 等子类里。
        val refinedTitle = doc.selectFirst(".p-novel__subtitle, h1.p-novel__title--rensai, .p-novel__title--rensai")
            ?.text()
            ?.trim()
            ?.ifBlank { null }
            ?: chapter.title
        val html = extractChapterHtml(doc)
            ?: error("未找到第 ${chapterIndex + 1} 章正文")
        return chapter.copy(title = refinedTitle, html = html, chapterUrl = url)
    }

    private fun extractNovelTitle(doc: Document): String {
        val raw = doc.selectFirst(".p-novel__title")
            ?.text()
            ?.trim()
            ?.ifBlank { null }
            ?: doc.selectFirst("meta[property=og:title]")
                ?.attr("content")
                ?.trim()
                ?.ifBlank { null }
            ?: doc.title().substringBefore(" - ").trim().ifBlank { null }
            ?: "Syosetu 小说"
        return raw
    }

    private fun extractChapterHtml(doc: Document): String? {
        val parts = doc.select("div.js-novel-text, .p-novel__text")
            .distinctBy(Element::elementSiblingIndex)
        if (parts.isEmpty()) return null
        parts.forEach { node ->
            node.select(
                "script, style, noscript, iframe, svg, form, button, input, select, textarea, " +
                    "[class*=novelpoint], [class*=ad]",
            ).remove()
        }
        val combined = parts.joinToString(separator = "\n<hr/>\n") { it.outerHtml() }
        if (Jsoup.parse(combined).text().isBlank()) return null
        return combined
    }

    private companion object {
        val NCODE = Regex("^[A-Za-z0-9]+$")
        const val MAX_CHAPTERS = 1000
    }
}

/**
 * Syosetu R18 发现页：novel18.syosetu.com 官方 API。
 *
 *   GET https://api.syosetu.com/novel18api/api/?out=json&lim=20&st=1&order=hyoka&nocgenre=1&word=...
 *
 * 返回 JSON 数组：[0]={allcount: N}，[1..n]=novels。
 * 详细字段参考 https://dev.syosetu.com/man/api18/
 *
 * 字段映射：
 *   id            = ncode (小写)
 *   detailUrl     = https://novel18.syosetu.com/<ncode>/
 *   title         = title
 *   author        = writer
 *   summary       = story (可能包含换行)
 *   tags          = nocgenre 名称 + keyword 拆分
 *   wordCount     = length
 *   chapterCount  = general_all_no（连载；novel_type=2 表示短篇，为 1）
 *   status        = end == 1 ? "完结" : "连载中"
 *   updatedAt     = parse(novelupdated_at, "yyyy-MM-dd HH:mm:ss")
 *   metrics["评分"]    = global_point
 *   metrics["收藏"]    = fav_novel_cnt
 *   metrics["收看者"]  = impression_cnt
 */
internal class SyosetuNovel18Discovery(private val source: SyosetuSource) : WebDiscovery {

    override val capabilities = DiscoveryCapabilities(
        supportsKeyword = true,
        sorts = listOf(
            DiscoverySort(id = "new", label = "最新"),
            DiscoverySort(id = "hyoka", label = "高评分"),
            DiscoverySort(id = "favnovelcnt", label = "高收藏"),
            DiscoverySort(id = "weekly", label = "周榜"),
            DiscoverySort(id = "monthlypoint", label = "月榜"),
            DiscoverySort(id = "yearlypoint", label = "年榜"),
            DiscoverySort(id = "lengthdesc", label = "长篇优先"),
        ),
        filters = listOf(
            DiscoveryFilterSpec(
                id = "nocgenre",
                label = "分区",
                options = listOf(
                    DiscoveryFilterOption(id = "_all", label = "全部", value = ""),
                    DiscoveryFilterOption(id = "1", label = "ノクターン（男性向け）"),
                    DiscoveryFilterOption(id = "2", label = "ムーンライト（女性向け）"),
                    DiscoveryFilterOption(id = "3", label = "ムーンライト（BL）"),
                    DiscoveryFilterOption(id = "4", label = "ミッドナイトノベル"),
                ),
                multiSelect = false,
            ),
            DiscoveryFilterSpec(
                id = "type",
                label = "类型",
                options = listOf(
                    DiscoveryFilterOption(id = "_all", label = "全部", value = ""),
                    DiscoveryFilterOption(id = "t", label = "短篇"),
                    DiscoveryFilterOption(id = "r", label = "已完结"),
                    DiscoveryFilterOption(id = "er", label = "连载中"),
                ),
                multiSelect = false,
            ),
        ),
        defaultPageSize = 20,
        firstPage = 1,
    )

    override suspend fun discover(query: DiscoveryQuery): DiscoveryPage {
        val lim = 20
        val page = query.page.coerceAtLeast(1)
        val st = (page - 1) * lim + 1
        // 官方 API 限制 st 最大 2000
        require(st <= 2000) { "超出发现 API 的最大范围（1-2000）" }
        val sb = StringBuilder("https://api.syosetu.com/novel18api/api/?out=json&lim=")
        sb.append(lim)
        sb.append("&st=").append(st)
        val sortId = query.sortId
        if (!sortId.isNullOrBlank()) sb.append("&order=").append(sortId)
        val nocgenre = query.filters["nocgenre"]?.firstOrNull()
        if (!nocgenre.isNullOrBlank() && nocgenre != "_all") {
            sb.append("&nocgenre=").append(nocgenre)
        }
        val type = query.filters["type"]?.firstOrNull()
        if (!type.isNullOrBlank() && type != "_all") {
            sb.append("&type=").append(type)
        }
        val keyword = query.keyword.trim()
            .split(Regex("[\\s　]+"))
            .filter { it.isNotBlank() }
            .joinToString(" ")
        if (keyword.isNotEmpty()) {
            sb.append("&word=").append(URLEncoder.encode(keyword, "UTF-8"))
        }

        val raw = source.fetchListingJson(sb.toString())
        val arr = JSONArray(raw)
        if (arr.length() == 0) {
            return DiscoveryPage(query = query, items = emptyList(), hasMore = false, totalCount = 0L)
        }
        val total = arr.optJSONObject(0)?.optLong("allcount", -1L)?.takeIf { it >= 0 }
        val items = (1 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let(::parseItem)
        }
        val hasMore = items.size >= lim &&
            (total == null || st + items.size - 1 < total) &&
            st + lim <= 2000
        return DiscoveryPage(query = query, items = items, hasMore = hasMore, totalCount = total)
    }

    private fun parseItem(o: JSONObject): DiscoveryItem? {
        val ncode = o.optString("ncode").trim().lowercase(Locale.US).ifBlank { return null }
        val title = o.optString("title").trim().ifBlank { return null }
        val writer = o.optString("writer").trim().ifBlank { null }
        val story = o.optString("story").trim().replace("\r\n", "\n").ifBlank { null }
        val keywords = o.optString("keyword").trim()
            .split(Regex("[\\s　]+"))
            .filter { it.isNotBlank() }
            .take(12)
        val nocgenre = o.optInt("nocgenre", -1)
        val nocLabel = when (nocgenre) {
            1 -> "ノクターン"
            2 -> "ムーンライト"
            3 -> "ムーンライトBL"
            4 -> "ミッドナイト"
            else -> null
        }
        // ミッドナイト (nocgenre=4) 走 mid.syosetu.com，其它 18+ 分区在 novel18.syosetu.com
        val host = if (nocgenre == 4) "mid.syosetu.com" else "novel18.syosetu.com"
        val tags = (listOfNotNull(nocLabel) + keywords).distinct()
        val novelType = o.optInt("novel_type", 1)
        val end = o.optInt("end", 0)
        val status = when {
            novelType == 2 -> "短篇"
            end == 1 -> "完结"
            else -> "连载中"
        }
        val length = o.optLong("length", -1L).takeIf { it > 0 }
        val chapterCount = o.optInt("general_all_no", -1).takeIf { it > 0 }
        val userid = o.optLong("userid", -1L).takeIf { it > 0 }
        val authorUrl = userid?.let { "https://mypage.syosetu.com/$it/" }
        val updatedAt = parseDate(o.optString("novelupdated_at"))
            ?: parseDate(o.optString("general_lastup"))
        val metrics = buildMap<String, String> {
            o.optLong("global_point", -1L).takeIf { it > 0 }?.let { put("评分", it.toString()) }
            o.optLong("fav_novel_cnt", -1L).takeIf { it > 0 }?.let { put("收藏", formatCount(it)) }
            o.optLong("impression_cnt", -1L).takeIf { it > 0 }?.let { put("感想", formatCount(it)) }
        }
        return DiscoveryItem(
            id = ncode,
            detailUrl = "https://$host/$ncode/",
            title = title,
            author = writer,
            authorUrl = authorUrl,
            coverUrl = null,
            summary = story,
            tags = tags,
            chapterCount = chapterCount,
            status = status,
            wordCount = length,
            updatedAt = updatedAt,
            metrics = metrics,
        )
    }

    override suspend fun resolveDetailUrl(item: DiscoveryItem): String = item.detailUrl

    /** 从作品页（ncode/novel18/mid 入口页）里挖到作者主页链接。 */
    override suspend fun resolveAuthorUrl(item: DiscoveryItem): String? {
        val doc = runCatching { source.fetchListingDocument(item.detailUrl) }.getOrNull() ?: return null
        // R18 用 xmypage.syosetu.com，全年龄用 mypage.syosetu.com，UID 可能是数字也可能是 x1014cr 这种字母数字 slug
        val link = doc.selectFirst("a[href*=mypage.syosetu.com/]")
            ?: doc.selectFirst("a[href*=xmypage.syosetu.com/]")
            ?: return null
        val href = link.absUrl("href").ifBlank { link.attr("href") }
        val m = Regex("(x?mypage)\\.syosetu\\.com/([A-Za-z0-9]+)").find(href) ?: return null
        val sub = m.groupValues[1]  // mypage 或 xmypage
        val uid = m.groupValues[2]
        // 作者主页就是 https://mypage.syosetu.com/<uid>/（R18 为 xmypage 子域），没有额外路径段
        return "https://$sub.syosetu.com/$uid/"
    }

    /**
     * Syosetu 作者主页 `https://mypage.syosetu.com/<userid>/`，列出该作者全部作品。
     * 页面以 a[href*=novel18.syosetu.com/<ncode>/] / `ncode.syosetu.com/<ncode>/` 等链接给出。
     */
    override suspend fun fetchAuthorWorks(authorUrl: String, excludeId: String?, limit: Int): List<DiscoveryItem>? {
        val doc = runCatching { source.fetchListingDocument(authorUrl) }.getOrNull() ?: return null
        // 每本书在作者页会渲染 3 个链接：系列封面（可能是 `/0/` 占位或 `s<code>/` 系列页，不是书本身）、
        // 书名标题（`.c-novel-list__title`，唯一可靠的书本身链接）、以及重复指向同一 ncode 的
        // "作品情报" 按钮（`.c-novel-list__novel-info-button`，锚文本被截断成"作"，标题不可用）。
        // 只取标题链接，否则系列占位 / info 按钮会被误当成独立的书，混进结果里。
        val anchors = doc.select("a.c-novel-list__title")
        val seen = LinkedHashSet<String>()
        val items = mutableListOf<DiscoveryItem>()
        for (a in anchors) {
            val href = a.absUrl("href").ifBlank { a.attr("href") }
            val m = Regex("//(novel18|ncode|mid)\\.syosetu\\.com/([A-Za-z0-9]+)/?$").find(href) ?: continue
            val host = "${m.groupValues[1]}.syosetu.com"
            val ncode = m.groupValues[2].lowercase(Locale.US)
            if (excludeId != null && ncode == excludeId) continue
            if (!seen.add(ncode)) continue
            val title = a.text().trim()
            if (title.isBlank()) continue
            items += DiscoveryItem(
                id = ncode,
                detailUrl = "https://$host/$ncode/",
                title = title,
                author = null,
                authorUrl = authorUrl,
                coverUrl = null,
                summary = null,
                tags = emptyList(),
                chapterCount = null,
                status = null,
                wordCount = null,
                updatedAt = null,
                metrics = emptyMap(),
            )
            if (items.size >= limit) break
        }
        return items.ifEmpty { null }
    }

    private fun parseDate(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        return runCatching {
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            fmt.timeZone = TimeZone.getTimeZone("Asia/Tokyo")
            fmt.parse(s)?.time
        }.getOrNull()
    }

    private fun formatCount(n: Long): String = when {
        n < 1000 -> n.toString()
        n < 1_000_000 -> String.format(Locale.US, "%.1fk", n / 1000.0)
        else -> String.format(Locale.US, "%.1fM", n / 1_000_000.0)
    }
}
