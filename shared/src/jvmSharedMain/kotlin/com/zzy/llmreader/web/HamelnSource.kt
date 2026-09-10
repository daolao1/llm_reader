package com.zzy.llmreader.web

import okhttp3.HttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Hameln（[https://syosetu.org](https://syosetu.org)）小说支持。
 *
 * - 入口：`https://syosetu.org/novel/<id>/`
 * - 章节：`https://syosetu.org/novel/<id>/<n>.html`
 * - 短編：没有章节列表，正文直接在入口页 `<div id="honbun">`
 * - 18+：自动带 `over18=off` cookie；如被 Cloudflare 拦截，可在设置里粘贴 cf_clearance。
 */
class HamelnSource(
    /** 用户在设置里粘贴的额外 cookie 字符串（如 `cf_clearance=...; ...`）。 */
    private val cookieProvider: () -> String? = { null },
    /** WebView 过 CF 后的 UA；cf_clearance 与 UA 绑定，必须保持一致。 */
    private val userAgentProvider: () -> String? = { null },
) : HtmlWebSource() {

    override val id: String = "hameln"
    override val displayName: String = "Hameln (syosetu.org)"
    override val sampleUrl: String = "https://syosetu.org/novel/410483/"

    override val discovery: WebDiscovery? = HamelnDiscovery(this)

    /** 供 [HamelnDiscovery] 拉搜索页，复用 cookie/UA。 */
    internal suspend fun fetchListingDocument(url: String): Document = fetchDocument(url)

    override val userAgent: String
        get() = userAgentProvider()?.takeIf { it.isNotBlank() } ?: super.userAgent

    override val extraHeaders: Map<String, String>
        get() {
            val extra = cookieProvider()?.trim()
            val cookie = buildString {
                append("over18=off; over18-confirm=on")
                if (!extra.isNullOrBlank()) append("; ").append(extra.trim('"'))
            }
            return mapOf(
                "Cookie" to cookie,
                "Referer" to "https://syosetu.org/",
            )
        }

    override fun matches(url: HttpUrl): Boolean {
        if (!url.host.endsWith("syosetu.org")) return false
        val segs = url.pathSegments
        return segs.size >= 2 && segs[0] == "novel" && segs[1].all(Char::isDigit)
    }

    override fun canonicalize(input: String): String {
        val parsed = parseInputUrl(input)
        require(matches(parsed)) { "仅支持 Hameln (syosetu.org) /novel/<id>/ 的页面" }
        val nid = parsed.pathSegments[1]
        return "https://syosetu.org/novel/$nid/"
    }

    override suspend fun fetchIndex(canonicalUrl: String, onProgress: FetchProgress): CachedWebBook {
        onProgress(0, -1, "读取目录…")
        val indexDoc = fetchDocument(canonicalUrl)
        val title = extractNovelTitle(indexDoc)

        // Hameln 的目录链接：站内 `/novel/<id>/<n>.html` 形式，<n> 必须是纯数字。
        val nid = canonicalUrl.trimEnd('/').substringAfterLast('/')
        val chapterAnchorRe = Regex("^/novel/$nid/(\\d+)\\.html\\b")
        val seen = LinkedHashMap<Int, Pair<String, String>>() // n -> (absUrl, title)
        for (a in indexDoc.select("a[href]")) {
            val href = a.attr("href")
            val abs = a.absUrl("href")
            val match = chapterAnchorRe.find(href)
                ?: chapterAnchorRe.find(absUrl(abs))
                ?: continue
            val n = match.groupValues[1].toIntOrNull() ?: continue
            val text = a.text().trim()
                .ifBlank { a.attr("title").trim() }
                .ifBlank { "第 $n 章" }
            // 同 n 多次出现（比如目录 + "最新章节"），保留首个文字最长的
            val prev = seen[n]
            if (prev == null || text.length > prev.second.length) {
                seen[n] = abs to text
            }
        }

        if (seen.isEmpty()) {
            // 短編：正文直接在入口页
            val html = extractChapterHtml(indexDoc)
                ?: error("未找到正文（可能被 Cloudflare 拦截，请在设置里粘贴 cf_clearance cookie）")
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

        val sorted = seen.entries.sortedBy { it.key }
        require(sorted.size <= MAX_CHAPTERS) { "章节过多（${sorted.size} 章），暂不支持" }
        val chapters = sorted.map { (_, pair) ->
            CachedWebChapter(title = pair.second, html = null, chapterUrl = pair.first)
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
        val url = chapter.chapterUrl
            ?: error("缺少章节 URL，请尝试刷新目录")
        val doc = fetchDocument(url)
        val refinedTitle = doc.selectFirst("span[itemprop=name], .ss span, .novel_subtitle")
            ?.text()
            ?.trim()
            ?.ifBlank { null }
            ?: chapter.title
        val html = extractChapterHtml(doc)
            ?: error("未找到第 ${chapterIndex + 1} 章正文")
        return chapter.copy(title = refinedTitle, html = html, chapterUrl = url)
    }

    private fun extractNovelTitle(doc: Document): String {
        return doc.selectFirst(".ss span[itemprop=name], span[itemprop=name], h1, .topic_title")
            ?.text()
            ?.trim()
            ?.ifBlank { null }
            ?: doc.selectFirst("meta[property=og:title]")
                ?.attr("content")
                ?.trim()
                ?.ifBlank { null }
            ?: doc.title().substringBefore(" - ").trim().ifBlank { null }
            ?: "Hameln 小说"
    }

    private fun extractChapterHtml(doc: Document): String? {
        // Hameln 章节正文最常见容器：div#honbun；前书き/后书き分别为 #maegaki / #atogaki。
        val parts = doc.select("div#honbun, div#maegaki, div#atogaki, div.section3, div.ss")
            .distinctBy(Element::elementSiblingIndex)
            .filter { node -> node.text().trim().length >= 10 }
        if (parts.isEmpty()) return null
        parts.forEach { node ->
            node.select(
                "script, style, noscript, iframe, svg, form, button, input, select, textarea, " +
                    "[class*=novelpoint], [class*=ad], [class*=banner]",
            ).remove()
        }
        val combined = parts.joinToString(separator = "\n<hr/>\n") { it.outerHtml() }
        if (Jsoup.parse(combined).text().isBlank()) return null
        return combined
    }

    private fun absUrl(s: String): String = if (s.startsWith("http")) {
        s.substringAfter("://").substringAfter("/").let { "/$it" }
    } else s

    private companion object {
        const val MAX_CHAPTERS = 2000
    }
}

/**
 * Hameln 发现页：抓 `https://syosetu.org/?mode=search&word=...&genre=...&order=...&p=N`
 *
 * 解析策略：每个搜索结果是一个 `.section3 > .ss > .section4` 块，
 * 标题 a：`a[href^=//syosetu.org/novel/]` 或 `a[href^=/novel/]`，
 * summary：紧跟的简介段落（`.ss .ss` 或 `.section3 ... <p>`）。
 *
 * Hameln 经常被 Cloudflare 拦截，发现页能否打开取决于用户在设置里粘贴的
 * cf_clearance / UA 是否有效。
 */
internal class HamelnDiscovery(private val source: HamelnSource) : WebDiscovery {

    override val capabilities = DiscoveryCapabilities(
        supportsKeyword = true,
        sorts = listOf(
            DiscoverySort(id = "hyoka", label = "高评分"),
            DiscoverySort(id = "hyoka_d", label = "高评分(降序)"),
            DiscoverySort(id = "new", label = "最新"),
            DiscoverySort(id = "old", label = "最早"),
            DiscoverySort(id = "ranking", label = "总榜"),
            DiscoverySort(id = "weekly", label = "周榜"),
            DiscoverySort(id = "monthly", label = "月榜"),
            DiscoverySort(id = "yearly", label = "年榜"),
        ),
        filters = listOf(
            DiscoveryFilterSpec(
                id = "genre",
                label = "分区",
                options = listOf(
                    DiscoveryFilterOption(id = "_all", label = "全部", value = ""),
                    DiscoveryFilterOption(id = "1", label = "ハイファンタジー〔ファンタジー〕"),
                    DiscoveryFilterOption(id = "2", label = "ローファンタジー〔ファンタジー〕"),
                    DiscoveryFilterOption(id = "101", label = "純文学〔文芸〕"),
                    DiscoveryFilterOption(id = "102", label = "ヒューマンドラマ〔文芸〕"),
                    DiscoveryFilterOption(id = "103", label = "歴史〔文芸〕"),
                    DiscoveryFilterOption(id = "104", label = "推理〔文芸〕"),
                    DiscoveryFilterOption(id = "105", label = "ホラー〔文芸〕"),
                    DiscoveryFilterOption(id = "106", label = "アクション〔文芸〕"),
                    DiscoveryFilterOption(id = "107", label = "コメディー〔文芸〕"),
                    DiscoveryFilterOption(id = "201", label = "VRゲーム〔SF〕"),
                    DiscoveryFilterOption(id = "202", label = "宇宙〔SF〕"),
                    DiscoveryFilterOption(id = "301", label = "現実世界〔恋愛〕"),
                    DiscoveryFilterOption(id = "302", label = "異世界〔恋愛〕"),
                ),
                multiSelect = false,
            ),
            DiscoveryFilterSpec(
                id = "type",
                label = "类型",
                options = listOf(
                    DiscoveryFilterOption(id = "_all", label = "全部", value = ""),
                    DiscoveryFilterOption(id = "tanpen", label = "短编"),
                    DiscoveryFilterOption(id = "rensai", label = "连载中"),
                    DiscoveryFilterOption(id = "kanketsu", label = "已完结"),
                ),
                multiSelect = false,
            ),
        ),
        defaultPageSize = 20,
        firstPage = 1,
    )

    override suspend fun discover(query: DiscoveryQuery): DiscoveryPage {
        val page = query.page.coerceAtLeast(1)
        val sb = StringBuilder("https://syosetu.org/?mode=search")
        val q = query.keyword.trim()
        if (q.isNotEmpty()) sb.append("&word=").append(java.net.URLEncoder.encode(q, "UTF-8"))
        val sortId = query.sortId?.takeIf { it.isNotBlank() } ?: "hyoka"
        sb.append("&order=").append(sortId)
        query.filters["genre"]?.firstOrNull()?.takeIf { it != "_all" && it.isNotBlank() }?.let {
            sb.append("&genre=").append(it)
        }
        query.filters["type"]?.firstOrNull()?.takeIf { it != "_all" && it.isNotBlank() }?.let {
            sb.append("&type=").append(it)
        }
        sb.append("&p=").append(page)

        val doc = source.fetchListingDocument(sb.toString())
        // Hameln 搜索结果块：div.section3 > div.search_box / div.section4 等结构变化较大，
        // 兜底用「包含 /novel/<id>/ 链接的容器」识别。
        val anchors = doc.select("a[href*=/novel/]").filter { a ->
            Regex("^/?(?:https?://syosetu\\.org)?/?novel/\\d+/?$").containsMatchIn(a.attr("href"))
        }
        val seen = LinkedHashSet<String>()
        val items = mutableListOf<DiscoveryItem>()
        for (a in anchors) {
            val href = a.absUrl("href").ifBlank { a.attr("href") }
            val nid = Regex("/novel/(\\d+)").find(href)?.groupValues?.get(1) ?: continue
            if (!seen.add(nid)) continue
            val title = a.text().trim()
            if (title.isBlank()) continue
            // 找该 anchor 所在的"块"，往上找最近的 div/li
            val block = a.parents().firstOrNull { it.tagName() in setOf("div", "li", "section") }
            val summary = block?.let { extractSummary(it) }
            val authorAnchor = block?.selectFirst("a[href*=/user/]")
            val author = authorAnchor?.text()?.trim()?.ifBlank { null }
            val authorUrl = authorAnchor?.absUrl("href")?.ifBlank { authorAnchor.attr("href") }
                ?.takeIf { it.isNotBlank() }
                ?.let { raw ->
                    if (raw.startsWith("http")) raw else "https://syosetu.org$raw"
                }
            val tags = block?.select("a.alert_color, a[href*=tag=], a[href*=keyword=]")
                ?.mapNotNull { it.text().trim().ifBlank { null } }
                ?.distinct()
                ?.take(8)
                .orEmpty()

            items += DiscoveryItem(
                id = nid,
                detailUrl = "https://syosetu.org/novel/$nid/",
                title = title,
                author = author,
                authorUrl = authorUrl,
                coverUrl = null,
                summary = summary,
                tags = tags,
                chapterCount = null,
                status = null,
                wordCount = null,
                updatedAt = null,
                metrics = emptyMap(),
            )
            if (items.size >= 20) break
        }
        // 翻页：找 a[href*=p=N+1]
        val hasMore = doc.select("a[href*=p=${page + 1}]").isNotEmpty()
        return DiscoveryPage(query = query, items = items, hasMore = hasMore, totalCount = null)
    }

    private fun extractSummary(block: Element): String? {
        // 优先 .ss / .text-comp / 直接的 <p>
        val candidates = block.select(".ss, .text-comp, p").map { it.text().trim() }
            .filter { it.length in 10..400 }
        return candidates.firstOrNull()
    }

    override suspend fun resolveDetailUrl(item: DiscoveryItem): String = item.detailUrl

    /** 从作品页里挖出 `?mode=user&uid=<uid>` 的作者主页链接。 */
    override suspend fun resolveAuthorUrl(item: DiscoveryItem): String? {
        val doc = runCatching { source.fetchListingDocument(item.detailUrl) }.getOrNull() ?: return null
        // hameln 兼容多种形式：?mode=user&uid=N 或 /user/N/
        val link = doc.selectFirst(
            "a[href*=mode=user&uid=], a[href*=/user/]",
        ) ?: return null
        val href = link.absUrl("href").ifBlank { link.attr("href") }
        val abs = if (href.startsWith("http")) href else "https://syosetu.org/${href.removePrefix("/")}"
        Regex("[?&]uid=(\\d+)").find(abs)?.groupValues?.get(1)?.let {
            return "https://syosetu.org/?mode=user&uid=$it"
        }
        Regex("/user/(\\d+)/").find(abs)?.groupValues?.get(1)?.let {
            return "https://syosetu.org/user/$it/"
        }
        return null
    }

    /** Hameln 作者主页：`/user/<uid>/` 列出作者全部作品。 */
    override suspend fun fetchAuthorWorks(authorUrl: String, excludeId: String?, limit: Int): List<DiscoveryItem>? {
        val doc = runCatching { source.fetchListingDocument(authorUrl) }.getOrNull() ?: return null
        // 用户页同样以 a[href*=/novel/<id>/] 列出作品
        val anchors = doc.select("a[href*=/novel/]").filter { a ->
            Regex("^/?(?:https?://syosetu\\.org)?/?novel/\\d+/?$").containsMatchIn(a.attr("href"))
        }
        val seen = LinkedHashSet<String>()
        val items = mutableListOf<DiscoveryItem>()
        for (a in anchors) {
            val href = a.absUrl("href").ifBlank { a.attr("href") }
            val nid = Regex("/novel/(\\d+)").find(href)?.groupValues?.get(1) ?: continue
            if (excludeId != null && nid == excludeId) continue
            if (!seen.add(nid)) continue
            val title = a.text().trim()
            if (title.isBlank()) continue
            val block = a.parents().firstOrNull { it.tagName() in setOf("div", "li", "section") }
            val summary = block?.let { extractSummary(it) }
            items += DiscoveryItem(
                id = nid,
                detailUrl = "https://syosetu.org/novel/$nid/",
                title = title,
                author = null,
                authorUrl = authorUrl,
                coverUrl = null,
                summary = summary,
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
}
