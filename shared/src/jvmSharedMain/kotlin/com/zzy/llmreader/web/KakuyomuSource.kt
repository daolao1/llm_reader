package com.zzy.llmreader.web

import okhttp3.HttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * カクヨム ([https://kakuyomu.jp](https://kakuyomu.jp)) 小说支持。
 *
 * - 作品入口：`https://kakuyomu.jp/works/<workId>`
 * - 章节：`https://kakuyomu.jp/works/<workId>/episodes/<episodeId>`
 * - 目录列表内嵌在入口页 `<script id="__NEXT_DATA__">` 的 Apollo 缓存里
 *   （`Work:<workId>` 取标题，`Episode:<id>` 取章节标题，按页面里出现顺序排列）。
 * - 章节正文容器：`div.widget-episodeBody`
 */
class KakuyomuSource : HtmlWebSource() {

    override val id: String = "kakuyomu"
    override val displayName: String = "カクヨム / Kakuyomu"
    override val sampleUrl: String = "https://kakuyomu.jp/works/822139845727270228"

    override val discovery: WebDiscovery? = KakuyomuDiscovery(this)

    /** 供 [KakuyomuDiscovery] 拉搜索页。 */
    internal suspend fun fetchListingDocument(url: String): Document = fetchDocument(url)

    override fun matches(url: HttpUrl): Boolean {
        if (url.host != "kakuyomu.jp" && url.host != "www.kakuyomu.jp") return false
        val segs = url.pathSegments
        return segs.size >= 2 && segs[0] == "works" && segs[1].all(Char::isDigit)
    }

    override fun canonicalize(input: String): String {
        val parsed = parseInputUrl(input)
        require(matches(parsed)) { "仅支持 kakuyomu.jp /works/<id> 的页面" }
        val workId = parsed.pathSegments[1]
        return "https://kakuyomu.jp/works/$workId"
    }

    override suspend fun fetchIndex(canonicalUrl: String, onProgress: FetchProgress): CachedWebBook {
        onProgress(0, -1, "读取目录…")
        val workId = canonicalUrl.trimEnd('/').substringAfterLast('/')
        val doc = fetchDocument(canonicalUrl)
        val title = extractWorkTitle(doc, workId)

        val nextData = doc.selectFirst("script#__NEXT_DATA__")?.data().orEmpty()

        // 优先用 JSON 解析 Apollo state，能拿到「X 章」的分节信息。
        val structured = parseTocStructured(nextData, workId)
        val chapters: List<CachedWebChapter> = if (structured.isNotEmpty()) {
            structured.map { item ->
                CachedWebChapter(
                    title = item.title.ifBlank { "话" },
                    html = null,
                    chapterUrl = "https://kakuyomu.jp/works/$workId/episodes/${item.episodeId}",
                    groupTitle = item.groupTitle,
                )
            }
        } else {
            // 兜底：走老的 __ref 正则扫描 + DOM 兜底。
            val seen = LinkedHashSet<String>()
            for (m in EPISODE_REF_RE.findAll(nextData)) seen += m.groupValues[1]
            if (seen.isEmpty()) {
                for (a in doc.select("a[href*=/episodes/]")) {
                    val href = a.attr("href")
                    val m = EPISODE_RE.find(href) ?: continue
                    if (m.groupValues[1] != workId) continue
                    seen += m.groupValues[2]
                }
            }
            val titleMap = extractEpisodeTitles(nextData)
            seen.mapIndexed { idx, eid ->
                val t = titleMap[eid]?.takeIf { it.isNotBlank() } ?: "第 ${idx + 1} 话"
                CachedWebChapter(
                    title = t,
                    html = null,
                    chapterUrl = "https://kakuyomu.jp/works/$workId/episodes/$eid",
                )
            }
        }

        if (chapters.isEmpty()) error("未在目录页找到任何章节链接")
        require(chapters.size <= MAX_CHAPTERS) { "章节过多（${chapters.size} 章），暂不支持" }

        return CachedWebBook(
            source = id,
            url = canonicalUrl,
            title = title,
            fetchedAt = System.currentTimeMillis(),
            chapters = chapters,
        )
    }

    /** 从 Apollo `__APOLLO_STATE__` 里走 Work → TableOfContentsChapter → EpisodeUnion → Episode 的引用图。 */
    private fun parseTocStructured(nextData: String, workId: String): List<TocEntry> {
        if (nextData.isBlank()) return emptyList()
        val root = runCatching { org.json.JSONObject(nextData) }.getOrNull() ?: return emptyList()
        val state = root.optJSONObject("props")
            ?.optJSONObject("pageProps")
            ?.optJSONObject("__APOLLO_STATE__") ?: return emptyList()

        val work = state.optJSONObject("Work:$workId") ?: return emptyList()
        val tocRefs = mutableListOf<String>()
        // 2026 起 kakuyomu 把目录字段改名为 tableOfContentsV2；保留旧名兜底。
        val tocField = work.opt("tableOfContentsV2") ?: work.opt("tableOfContents")
        when (val toc = tocField) {
            is org.json.JSONArray -> for (i in 0 until toc.length()) {
                toc.optJSONObject(i)?.optString("__ref")
                    ?.takeIf { it.isNotBlank() }?.let { tocRefs += it }
            }
            is org.json.JSONObject -> toc.optString("__ref")
                .takeIf { it.isNotBlank() }?.let { tocRefs += it }
            else -> Unit
        }
        if (tocRefs.isEmpty()) return emptyList()

        val out = mutableListOf<TocEntry>()
        for (ref in tocRefs) {
            val node = state.optJSONObject(ref) ?: continue
            when {
                ref.startsWith("TableOfContentsChapter:") -> {
                    // 分组标题现在挂在 chapter.__ref 指向的 Chapter 节点的 title 上；
                    // 旧结构则直接在本节点的 title 字段。
                    val groupTitle = run {
                        val chapterRef = node.optJSONObject("chapter")?.optString("__ref")
                        val fromChapter = chapterRef
                            ?.let { state.optJSONObject(it) }
                            ?.optString("title")
                            ?.trim()
                            ?.ifBlank { null }
                        fromChapter ?: node.optString("title").trim().ifBlank { null }
                    }
                    val unions = node.optJSONArray("episodeUnions") ?: continue
                    for (i in 0 until unions.length()) {
                        val unionRef = unions.optJSONObject(i)?.optString("__ref") ?: continue
                        appendEpisode(state, unionRef, groupTitle, out)
                    }
                }
                ref.startsWith("EpisodeUnion:") -> appendEpisode(state, ref, null, out)
                ref.startsWith("Episode:") -> appendEpisode(state, ref, null, out)
            }
        }
        return out
    }

    private fun appendEpisode(
        state: org.json.JSONObject,
        ref: String,
        groupTitle: String?,
        out: MutableList<TocEntry>,
    ) {
        val node = state.optJSONObject(ref) ?: return
        val episodeRef: String = when {
            ref.startsWith("Episode:") -> ref
            else -> node.optJSONObject("episode")?.optString("__ref")
                ?.takeIf { it.startsWith("Episode:") } ?: return
        }
        val episode = state.optJSONObject(episodeRef) ?: return
        val eid = episode.optString("id").ifBlank { episodeRef.removePrefix("Episode:") }
        if (eid.isBlank()) return
        val etitle = episode.optString("title").trim()
        out += TocEntry(eid, etitle, groupTitle)
    }

    private data class TocEntry(val episodeId: String, val title: String, val groupTitle: String?)

    override suspend fun fetchChapter(
        canonicalUrl: String,
        chapterIndex: Int,
        chapter: CachedWebChapter,
    ): CachedWebChapter {
        val url = chapter.chapterUrl ?: error("缺少章节 URL，请尝试刷新目录")
        val doc = fetchDocument(url)
        // 注意：章节页 `h1` 是全书名，绝不能拿来当章节标题。
        // 真正的话标题在 `.widget-episodeTitle`（可能存在 ruby/小标签）。
        // 目录阶段已经从 Apollo state 拿到了准确标题，这里仅当无标题时再尝试细化。
        val refinedTitle = doc.selectFirst(".widget-episodeTitle, p.widget-episodeTitle")
            ?.text()
            ?.trim()
            ?.ifBlank { null }
            ?: chapter.title
        val html = extractChapterHtml(doc)
            ?: error("未找到第 ${chapterIndex + 1} 章正文")
        return chapter.copy(title = refinedTitle, html = html, chapterUrl = url)
    }

    private fun extractWorkTitle(doc: Document, workId: String): String {
        // 1) Apollo 缓存
        val nextData = doc.selectFirst("script#__NEXT_DATA__")?.data().orEmpty()
        WORK_TITLE_RE.find(nextData)?.let { m ->
            val key = m.groupValues[1]
            val payload = m.groupValues[2]
            if (key == workId) {
                Regex("\"title\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(payload)?.let {
                    return jsonUnescape(it.groupValues[1])
                }
            }
        }
        // 2) og:title
        doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()?.let {
            // 去掉 " - カクヨム" 后缀
            val t = it.removeSuffix(" - カクヨム").removeSuffix(" - Kakuyomu").trim()
            if (t.isNotBlank()) return t
        }
        return doc.title().substringBefore(" - ").trim().ifBlank { "Kakuyomu 作品" }
    }

    /**
     * 从 __NEXT_DATA__ 文本里抓所有 `"Episode:<id>":{...,"title":"..."}` 的对照。
     * 不做完整 JSON 解析，免得拖一个 Json 库；只用正则就够。
     */
    private fun extractEpisodeTitles(nextData: String): Map<String, String> {
        if (nextData.isBlank()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        for (m in EPISODE_BLOCK_RE.findAll(nextData)) {
            val eid = m.groupValues[1]
            val payload = m.groupValues[2]
            val tm = Regex("\"title\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(payload)
            if (tm != null) {
                result[eid] = jsonUnescape(tm.groupValues[1])
            }
        }
        return result
    }

    private fun extractChapterHtml(doc: Document): String? {
        val body = doc.selectFirst("div.widget-episodeBody, .widget-episodeBody, #contentMain-inner .widget-episodeBody")
            ?: return null
        body.select("script, style, noscript, iframe, button, [class*=ad]").remove()
        // 保留 <p>/<br>/<ruby>/<rt> 等结构。
        val cloned = body.clone() as Element
        cloned.select("p").forEach { p ->
            // 删掉空段（class=blank 仅 <br>）的多余属性
            p.removeAttr("id")
            p.removeAttr("class")
        }
        val html = cloned.html()
        if (Jsoup.parse(html).text().isBlank()) return null
        return html
    }

    private fun jsonUnescape(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    '"', '\\', '/' -> { sb.append(n); i += 2 }
                    'n' -> { sb.append('\n'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    'u' -> {
                        if (i + 5 < s.length) {
                            val code = s.substring(i + 2, i + 6).toIntOrNull(16)
                            if (code != null) {
                                sb.append(code.toChar()); i += 6
                            } else {
                                sb.append(c); i++
                            }
                        } else { sb.append(c); i++ }
                    }
                    else -> { sb.append(n); i += 2 }
                }
            } else {
                sb.append(c); i++
            }
        }
        return sb.toString()
    }

    private companion object {
        const val MAX_CHAPTERS = 2000
        val EPISODE_RE = Regex("/works/(\\d+)/episodes/(\\d+)")
        // Apollo 缓存里章节按 episodeUnions 数组顺序出现的引用：{"__ref":"Episode:<id>"}
        val EPISODE_REF_RE = Regex("\"__ref\"\\s*:\\s*\"Episode:(\\d+)\"")
        // Apollo 缓存条目（容忍嵌套层级，简单按 "Episode:<id>": { ... } 抓首层 JSON 对象段）
        val EPISODE_BLOCK_RE = Regex("\"Episode:(\\d+)\"\\s*:\\s*\\{((?:[^{}]|\\{[^{}]*\\})*)\\}")
        val WORK_TITLE_RE = Regex("\"Work:(\\d+)\"\\s*:\\s*\\{((?:[^{}]|\\{[^{}]*\\})*)\\}")
    }
}

/**
 * Kakuyomu 发现页：抓 `/search` HTML，解析 `__NEXT_DATA__` 里的 Apollo state。
 *
 * 字段映射（来自 Apollo `Work:<id>` 节点）：
 *   id            = workId
 *   detailUrl     = https://kakuyomu.jp/works/<id>
 *   title         = title
 *   author        = UserAccount.activityName / nickname
 *   summary       = catchphrase + introduction
 *   tags          = genre + tagLabels
 *   chapterCount  = publicEpisodeCount
 *   wordCount     = totalCharacterCount
 *   status        = serialStatus（COMPLETED/RUNNING/...）
 *   metrics["评分"]= totalReviewPoint
 */
internal class KakuyomuDiscovery(private val source: KakuyomuSource) : WebDiscovery {

    override val capabilities = DiscoveryCapabilities(
        supportsKeyword = true,
        sorts = listOf(
            DiscoverySort(id = "POPULAR", label = "人气"),
            DiscoverySort(id = "NEW", label = "最新"),
            DiscoverySort(id = "RANK_DAILY", label = "日榜"),
            DiscoverySort(id = "RANK_WEEKLY", label = "周榜"),
            DiscoverySort(id = "RANK_MONTHLY", label = "月榜"),
            DiscoverySort(id = "RANK_ALL_TIME", label = "总榜"),
        ),
        filters = listOf(
            DiscoveryFilterSpec(
                id = "genre",
                label = "分区",
                options = listOf(
                    DiscoveryFilterOption(id = "_all", label = "全部", value = ""),
                    DiscoveryFilterOption(id = "fantasy", label = "异世界Fantasy"),
                    DiscoveryFilterOption(id = "action", label = "现代Fantasy"),
                    DiscoveryFilterOption(id = "sf", label = "SF"),
                    DiscoveryFilterOption(id = "love_story", label = "恋爱"),
                    DiscoveryFilterOption(id = "romance", label = "Romance"),
                    DiscoveryFilterOption(id = "drama", label = "现代Drama"),
                    DiscoveryFilterOption(id = "horror", label = "恐怖"),
                    DiscoveryFilterOption(id = "mystery", label = "悬疑"),
                    DiscoveryFilterOption(id = "essay", label = "随笔"),
                    DiscoveryFilterOption(id = "history", label = "历史"),
                    DiscoveryFilterOption(id = "criticism", label = "评论"),
                    DiscoveryFilterOption(id = "others", label = "其他"),
                ),
                multiSelect = false,
            ),
        ),
        defaultPageSize = 20,
        firstPage = 1,
    )

    override suspend fun discover(query: DiscoveryQuery): DiscoveryPage {
        val page = query.page.coerceAtLeast(1)
        val sb = StringBuilder("https://kakuyomu.jp/search?")
        val q = query.keyword.trim()
        if (q.isNotEmpty()) sb.append("q=").append(java.net.URLEncoder.encode(q, "UTF-8")).append('&')
        val sortId = query.sortId?.takeIf { it.isNotBlank() } ?: "POPULAR"
        sb.append("order=").append(sortId)
        query.filters["genre"]?.firstOrNull()?.takeIf { it != "_all" && it.isNotBlank() }?.let {
            sb.append("&genre=").append(it)
        }
        sb.append("&page=").append(page)

        val doc = source.fetchListingDocument(sb.toString())
        val rawJson = doc.selectFirst("script#__NEXT_DATA__")?.data()
            ?: error("Kakuyomu 搜索页结构变化，未找到 __NEXT_DATA__")
        val root = org.json.JSONObject(rawJson)
        val state = root.optJSONObject("props")?.optJSONObject("pageProps")?.optJSONObject("__APOLLO_STATE__")
            ?: error("Kakuyomu 搜索数据缺失 __APOLLO_STATE__")
        val rootQuery = state.optJSONObject("ROOT_QUERY") ?: error("Kakuyomu ROOT_QUERY 缺失")

        // searchWorks(...) 的 key 包含参数序列化字符串，找出第一条匹配
        var swKey: String? = null
        val keys = rootQuery.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (k.startsWith("searchWorks(")) { swKey = k; break }
        }
        val sw = swKey?.let { rootQuery.optJSONObject(it) }
            ?: return DiscoveryPage(query = query, items = emptyList(), hasMore = false, totalCount = 0L)
        val nodes = sw.optJSONArray("nodes") ?: org.json.JSONArray()
        val items = mutableListOf<DiscoveryItem>()
        for (i in 0 until nodes.length()) {
            val ref = nodes.optJSONObject(i)?.optString("__ref").orEmpty()
            if (!ref.startsWith("Work:")) continue
            val work = state.optJSONObject(ref) ?: continue
            parseWork(state, work)?.let(items::add)
        }
        val total = sw.optLong("totalCount", -1L).takeIf { it >= 0 }
        val hasMore = items.size >= 20
        return DiscoveryPage(query = query, items = items, hasMore = hasMore, totalCount = total)
    }

    private fun parseWork(state: org.json.JSONObject, work: org.json.JSONObject): DiscoveryItem? {
        val id = work.optString("id").trim().ifBlank { return null }
        val title = work.optString("title").trim().ifBlank { return null }
        val authorRef = work.optJSONObject("author")?.optString("__ref")
        val authorState = authorRef?.let { state.optJSONObject(it) }
        val activityName = authorState?.optString("activityName")?.takeIf { it.isNotBlank() }
        val author = authorState?.let { it.optString("activityName").ifBlank { it.optString("nickname") } }
            ?.takeIf { it.isNotBlank() }
        val authorUrl = activityName?.let { "https://kakuyomu.jp/users/$it/works" }
        val catch = work.optString("catchphrase").trim()
        val intro = work.optString("introduction").trim()
        val summary = listOf(catch, intro).filter { it.isNotBlank() }.joinToString("\n").ifBlank { null }
        val tags = mutableListOf<String>()
        work.optString("genre").trim().takeIf { it.isNotBlank() }?.let { tags += it.lowercase() }
        val labels = work.optJSONArray("tagLabels")
        if (labels != null) {
            for (i in 0 until labels.length().coerceAtMost(10)) {
                labels.optString(i).trim().takeIf { it.isNotBlank() }?.let { tags += it }
            }
        }
        val chapterCount = work.optInt("publicEpisodeCount", -1).takeIf { it > 0 }
        val wordCount = work.optLong("totalCharacterCount", -1L).takeIf { it > 0 }
        val status = when (work.optString("serialStatus")) {
            "COMPLETED" -> "完结"
            "RUNNING" -> "连载中"
            "STOPPED" -> "停更"
            else -> null
        }
        val updatedAt = parseIso8601(work.optString("lastEpisodePublishedAt"))
        val metrics = buildMap<String, String> {
            work.optLong("totalReviewPoint", -1L).takeIf { it > 0 }?.let { put("评分", formatCount(it)) }
        }
        return DiscoveryItem(
            id = id,
            detailUrl = "https://kakuyomu.jp/works/$id",
            title = title,
            author = author,
            authorUrl = authorUrl,
            coverUrl = null,
            summary = summary,
            tags = tags.distinct().take(12),
            chapterCount = chapterCount,
            status = status,
            wordCount = wordCount,
            updatedAt = updatedAt,
            metrics = metrics,
        )
    }

    override suspend fun resolveDetailUrl(item: DiscoveryItem): String = item.detailUrl

    /** 从 `/works/<id>` 详情页 Apollo state 里取作者 activityName。 */
    override suspend fun resolveAuthorUrl(item: DiscoveryItem): String? {
        val doc = runCatching { source.fetchListingDocument(item.detailUrl) }.getOrNull() ?: return null
        val rawJson = doc.selectFirst("script#__NEXT_DATA__")?.data() ?: return null
        val root = runCatching { org.json.JSONObject(rawJson) }.getOrNull() ?: return null
        val state = root.optJSONObject("props")?.optJSONObject("pageProps")?.optJSONObject("__APOLLO_STATE__")
            ?: return null
        // 精确定位当前作品对应的 UserAccount，而非抓取页面里第一个遇到的 UserAccount 节点
        // （详情页 Apollo state 里可能因推荐位等原因混入其他作者的节点）
        val work = state.optJSONObject("Work:${item.id}") ?: return null
        val authorRef = work.optJSONObject("author")?.optString("__ref") ?: return null
        val activityName = state.optJSONObject(authorRef)?.optString("activityName")?.trim()
            ?.takeIf { it.isNotBlank() }
        return activityName?.let { "https://kakuyomu.jp/users/$it/works" }
    }

    /**
     * Kakuyomu 作者主页 `https://kakuyomu.jp/users/<activityName>/works`，
     * Apollo state 里 `UserAccount:<id>` 节点持有 `works` 引用列表。
     */
    override suspend fun fetchAuthorWorks(authorUrl: String, excludeId: String?, limit: Int): List<DiscoveryItem>? {
        val doc = runCatching { source.fetchListingDocument(authorUrl) }.getOrNull() ?: return null
        val rawJson = doc.selectFirst("script#__NEXT_DATA__")?.data() ?: return null
        val root = runCatching { org.json.JSONObject(rawJson) }.getOrNull() ?: return null
        val state = root.optJSONObject("props")?.optJSONObject("pageProps")?.optJSONObject("__APOLLO_STATE__")
            ?: return null

        // 1) 收集所有 Work:<id> 节点（用户主页/works 页，Apollo state 里通常只剩这一位作者的作品）
        val items = mutableListOf<DiscoveryItem>()
        val seen = LinkedHashSet<String>()
        val keys = state.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (!k.startsWith("Work:")) continue
            val work = state.optJSONObject(k) ?: continue
            val item = parseWork(state, work) ?: continue
            if (excludeId != null && item.id == excludeId) continue
            if (!seen.add(item.id)) continue
            items += item
        }
        // 按 lastEpisodePublishedAt 倒序（updatedAt 已转毫秒）
        items.sortByDescending { it.updatedAt ?: 0L }
        return items.take(limit).ifEmpty { null }
    }

    private fun parseIso8601(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        return runCatching {
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
            fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
            fmt.parse(s)?.time
        }.getOrNull()
    }

    private fun formatCount(n: Long): String = when {
        n < 1000 -> n.toString()
        n < 1_000_000 -> String.format(java.util.Locale.US, "%.1fk", n / 1000.0)
        else -> String.format(java.util.Locale.US, "%.1fM", n / 1_000_000.0)
    }
}
