package com.zzy.llmreader.web

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl

/**
 * Pixiv 小说（[https://www.pixiv.net/novel/show.php?id=<id>](https://www.pixiv.net/novel/show.php?id=<id>)）。
 *
 * - 全年龄 (xRestrict=0) 匿名可拉。
 * - R-18 / 多页 / 系列章节抓取需要 PHPSESSID（在「设置」里粘贴登录 cookie）。
 * - 数据来源是 ajax JSON 接口，不抓 HTML 页面。
 */
class PixivSource(
    /** 返回 PHPSESSID 字符串；未设置时返回 null。Cookie 仅本地保存。 */
    private val cookieProvider: () -> String? = { null },
) : HtmlWebSource() {

    override val id: String = "pixiv"
    override val displayName: String = "Pixiv 小说"
    override val sampleUrl: String = "https://www.pixiv.net/novel/show.php?id=27974042"

    override val discovery: WebDiscovery? = PixivDiscovery(this)

    /** 供 [PixivDiscovery] 拉 ajax 搜索接口，复用 cookie/UA。 */
    internal suspend fun fetchSearchJson(url: String): String =
        fetchString(url = url, accept = "application/json")

    override val extraHeaders: Map<String, String>
        get() {
            val cookie = cookieProvider()?.trim()?.takeIf { it.isNotBlank() }
            val headers = mutableMapOf(
                "Referer" to "https://www.pixiv.net/",
                "Accept" to "application/json",
                "x-user-id" to (cookie?.substringBefore('_').orEmpty()),
            )
            if (cookie != null) {
                headers["Cookie"] = "PHPSESSID=$cookie"
            }
            return headers
        }

    override fun matches(url: HttpUrl): Boolean {
        val host = url.host.lowercase()
        if (host != "www.pixiv.net" && host != "pixiv.net") return false
        return url.encodedPath.startsWith("/novel/show.php") ||
            url.encodedPath.startsWith("/novel/series/")
    }

    override fun canonicalize(input: String): String {
        val parsed = parseInputUrl(input)
        require(matches(parsed)) { "仅支持 Pixiv 小说 URL" }
        if (parsed.encodedPath.startsWith("/novel/series/")) {
            val seriesId = parsed.pathSegments.lastOrNull { it.isNotBlank() }
                ?: error("URL 中找不到 series ID")
            require(seriesId.all { it.isDigit() }) { "series ID 不合法：$seriesId" }
            return "https://www.pixiv.net/novel/series/$seriesId"
        }
        val id = parsed.queryParameter("id") ?: error("URL 中缺少 ?id= 参数")
        require(id.all { it.isDigit() }) { "novel ID 不合法：$id" }
        return "https://www.pixiv.net/novel/show.php?id=$id"
    }

    override suspend fun fetchIndex(canonicalUrl: String, onProgress: FetchProgress): CachedWebBook {
        onProgress(0, -1, "读取 Pixiv 小说…")

        // 系列 URL：直接抓系列目录。
        if (canonicalUrl.contains("/novel/series/")) {
            val seriesId = canonicalUrl.substringAfterLast("/")
            return fetchSeriesIndex(seriesId, canonicalUrl, onProgress)
        }

        // 单篇 URL：先拉一次正文，看是不是系列里的一篇，是的话再升级成系列目录。
        val novelId = canonicalUrl.substringAfter("?id=").substringBefore("&")
        val body = fetchNovelBody(novelId)
        // ⚠️ seriesId 不在顶层，而在 body.seriesNavData.seriesId。
        // 旧实现读 `body["seriesId"]` 永远拿到 null，于是「单篇自动升级成系列」
        // 这段是死代码 —— 粘贴一个属于 30+ 话系列的作品，只会得到 1 章的书，
        // 没有任何入口能读到其余话。
        val seriesId = body["seriesNavData"]
            ?.let { it as? kotlinx.serialization.json.JsonObject }
            ?.get("seriesId")
            ?.jsonPrimOrNull()?.contentOrNull
            ?.takeIf { it.isNotBlank() && it != "0" }
        if (seriesId != null) {
            // 升级到系列模式
            val seriesUrl = "https://www.pixiv.net/novel/series/$seriesId"
            return fetchSeriesIndex(seriesId, seriesUrl, onProgress)
        }

        val title = body["title"]?.jsonPrimOrNull()?.contentOrNull?.ifBlank { null }
            ?: "Pixiv 小说 $novelId"
        val html = pixivContentToHtml(body["content"]?.jsonPrimOrNull()?.contentOrNull.orEmpty())
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

    private suspend fun fetchSeriesIndex(
        seriesId: String,
        canonicalUrl: String,
        onProgress: FetchProgress,
    ): CachedWebBook {
        // 系列元数据（标题）
        val seriesMeta = runCatching {
            val raw = fetchString(
                url = "https://www.pixiv.net/ajax/novel/series/$seriesId",
                accept = "application/json",
            )
            val obj = parseEnvelope(raw)
            obj["title"]?.jsonPrimOrNull()?.contentOrNull?.ifBlank { null }
        }.getOrNull()
        val seriesTitle = seriesMeta ?: "Pixiv 系列 $seriesId"

        // 分页拉章节列表
        val all = mutableListOf<Pair<String, String>>() // novelId -> title
        var lastOrder = 0
        val pageSize = 30
        while (true) {
            val raw = fetchString(
                url = "https://www.pixiv.net/ajax/novel/series_content/$seriesId" +
                    "?limit=$pageSize&last_order=$lastOrder&order_by=asc",
                accept = "application/json",
            )
            val body = parseEnvelope(raw)
            val list = body["page"]?.jsonObject?.get("seriesContents")?.jsonArray
                ?: body["seriesContents"]?.jsonArray
                ?: break
            if (list.isEmpty()) break
            for (entry in list) {
                val obj = entry.jsonObject
                val nid = obj["id"]?.jsonPrimOrNull()?.contentOrNull ?: continue
                val t = obj["title"]?.jsonPrimOrNull()?.contentOrNull?.ifBlank { null }
                    ?: "第 ${all.size + 1} 章"
                all += nid to t
            }
            if (list.size < pageSize) break
            lastOrder += list.size
            onProgress(all.size, -1, "目录：已读 ${all.size} 章")
            if (all.size >= MAX_CHAPTERS) break
        }
        require(all.isNotEmpty()) { "系列目录为空（可能需要 Cookie 或 ID 错误）" }

        val chapters = all.map { (nid, t) ->
            CachedWebChapter(
                title = t,
                html = null,
                chapterUrl = "https://www.pixiv.net/novel/show.php?id=$nid",
            )
        }
        return CachedWebBook(
            source = id,
            url = canonicalUrl,
            title = seriesTitle,
            fetchedAt = System.currentTimeMillis(),
            chapters = chapters,
        )
    }

    override suspend fun fetchChapter(
        canonicalUrl: String,
        chapterIndex: Int,
        chapter: CachedWebChapter,
    ): CachedWebChapter {
        val url = chapter.chapterUrl ?: error("章节 URL 缺失")
        val novelId = url.substringAfter("?id=").substringBefore("&")
        val body = fetchNovelBody(novelId)
        val title = body["title"]?.jsonPrimOrNull()?.contentOrNull?.ifBlank { null } ?: chapter.title
        val html = pixivContentToHtml(body["content"]?.jsonPrimOrNull()?.contentOrNull.orEmpty())
        return chapter.copy(title = title, html = html, chapterUrl = url)
    }

    private suspend fun fetchNovelBody(novelId: String): JsonObject {
        val raw = fetchString(
            url = "https://www.pixiv.net/ajax/novel/$novelId",
            accept = "application/json",
        )
        return parseEnvelope(raw)
    }

    /** Pixiv ajax 统一信封：`{error, message, body}`。返回 body。 */
    private fun parseEnvelope(raw: String): JsonObject {
        val root = JSON.parseToJsonElement(raw).jsonObject
        val isErr = root["error"]?.jsonPrimOrNull()?.let {
            it.contentOrNull == "true" || it.intOrNull == 1
        } ?: false
        if (isErr) {
            val msg = root["message"]?.jsonPrimOrNull()?.contentOrNull?.ifBlank { null }
                ?: "Pixiv 接口返回错误"
            error(msg)
        }
        return root["body"]?.jsonObject ?: error("Pixiv 接口返回缺少 body")
    }

    /** 把 Pixiv 自有标记语言转成简单 HTML。 */
    private fun pixivContentToHtml(raw: String): String {
        if (raw.isBlank()) return ""
        val sb = StringBuilder()
        // 按 [newpage] 切页，每页内部按空行切段。
        val pages = raw.split(NEWPAGE_RE)
        pages.forEachIndexed { idx, page ->
            if (idx > 0) sb.append("<hr/>\n")
            val paragraphs = page.split(BLANK_LINE_RE)
            for (p in paragraphs) {
                val trimmed = p.trim('\r', '\n', ' ', '\t')
                if (trimmed.isBlank()) continue
                if (CHAPTER_RE.containsMatchIn(trimmed)) {
                    val title = CHAPTER_RE.find(trimmed)?.groupValues?.get(1)?.trim().orEmpty()
                    if (title.isNotBlank()) {
                        sb.append("<h3>").append(escapeHtml(title)).append("</h3>\n")
                        continue
                    }
                }
                sb.append("<p>")
                sb.append(formatInline(trimmed))
                sb.append("</p>\n")
            }
        }
        return sb.toString()
    }

    private fun formatInline(text: String): String {
        // 行内：先处理 ruby [[rb:漢字 > かんじ]]、跳过 [jump:N]/[pixivimage:..]/[uploadedimage:..]
        var s = text
        s = RUBY_RE.replace(s) { m ->
            val base = m.groupValues[1].trim()
            val rt = m.groupValues[2].trim()
            "<ruby>${escapeHtml(base)}<rt>${escapeHtml(rt)}</rt></ruby>"
        }
        s = JUMP_RE.replace(s, "")
        s = PIXIVIMAGE_RE.replace(s, "")
        s = UPLOADIMG_RE.replace(s, "")
        // 把剩余文本 escape，再把单换行变 <br/>
        val parts = StringBuilder()
        // 因为已经插入了 <ruby> 等标签，不能整体 escape，要按 token 处理。
        val tagSplit = TAG_RE.split(s)
        val tags = TAG_RE.findAll(s).toList()
        for (i in tagSplit.indices) {
            parts.append(escapeHtml(tagSplit[i]).replace("\n", "<br/>"))
            if (i < tags.size) parts.append(tags[i].value)
        }
        return parts.toString()
    }

    private fun escapeHtml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

    private fun kotlinx.serialization.json.JsonElement.jsonPrimOrNull(): JsonPrimitive? =
        this as? JsonPrimitive

    companion object {
        private const val MAX_CHAPTERS = 1000
        private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }
        private val NEWPAGE_RE = Regex("\\[newpage\\]")
        private val BLANK_LINE_RE = Regex("\\n\\s*\\n+")
        private val CHAPTER_RE = Regex("^\\[chapter:(.+?)\\]$")
        private val RUBY_RE = Regex("\\[\\[rb:(.+?)\\s*>\\s*(.+?)\\]\\]")
        private val JUMP_RE = Regex("\\[jump:[^\\]]*\\]")
        private val PIXIVIMAGE_RE = Regex("\\[pixivimage:[^\\]]*\\]")
        private val UPLOADIMG_RE = Regex("\\[uploadedimage:[^\\]]*\\]")
        private val TAG_RE = Regex("<[^>]+>")
    }
}

/**
 * Pixiv 发现页：基于 `/ajax/search/novels/<word>` 关键字搜索。
 *
 * - 关键字必填（Pixiv 不支持空关键字搜索）。
 * - mode：all / safe / r18（r18 需要登录 Cookie）。
 * - order：date_d 最新 / date 最早 / popular_d 热门（需 Premium，否则会回退）。
 * - 每页 ajax 默认 30 条，pagination 通过 `&p=N`。
 * - body.novel.data[] 字段：id / title / userName / userId / tags[] / textCount /
 *   xRestrict / createDate / updateDate / description(HTML) / seriesId / seriesTitle。
 */
internal class PixivDiscovery(private val source: PixivSource) : WebDiscovery {

    override val capabilities = DiscoveryCapabilities(
        supportsKeyword = true,
        keywordRequired = true,
        sorts = listOf(
            DiscoverySort(id = "date_d", label = "最新"),
            DiscoverySort(id = "date", label = "最早"),
            DiscoverySort(id = "popular_d", label = "热门(Premium)"),
        ),
        filters = listOf(
            DiscoveryFilterSpec(
                id = "mode",
                label = "分级",
                options = listOf(
                    DiscoveryFilterOption(id = "all", label = "全部"),
                    DiscoveryFilterOption(id = "safe", label = "全年龄"),
                    DiscoveryFilterOption(id = "r18", label = "R-18(需Cookie)"),
                ),
                multiSelect = false,
            ),
            DiscoveryFilterSpec(
                id = "s_mode",
                label = "匹配",
                options = listOf(
                    DiscoveryFilterOption(id = "s_tag", label = "tag(部分一致)"),
                    DiscoveryFilterOption(id = "s_tag_full", label = "tag(完全一致)"),
                    DiscoveryFilterOption(id = "s_tc", label = "标题/正文"),
                ),
                multiSelect = false,
            ),
        ),
        defaultPageSize = 30,
        firstPage = 1,
    )

    override suspend fun discover(query: DiscoveryQuery): DiscoveryPage {
        val word = query.keyword.trim()
        require(word.isNotEmpty()) { "Pixiv 搜索需要关键字" }
        val mode = query.filters["mode"]?.firstOrNull()?.takeIf { it.isNotBlank() } ?: "all"
        val sMode = query.filters["s_mode"]?.firstOrNull()?.takeIf { it.isNotBlank() } ?: "s_tag"
        val order = query.sortId?.takeIf { it.isNotBlank() } ?: "date_d"
        val page = query.page.coerceAtLeast(1)

        val encoded = java.net.URLEncoder.encode(word, "UTF-8")
        val url = "https://www.pixiv.net/ajax/search/novels/$encoded" +
            "?word=$encoded&order=$order&mode=$mode&s_mode=$sMode&p=$page"

        val raw = source.fetchSearchJson(url)
        val root = org.json.JSONObject(raw)
        val isErr = root.opt("error")?.toString()?.equals("true", ignoreCase = true) == true ||
            root.optInt("error", 0) == 1
        if (isErr) {
            val msg = root.optString("message").ifBlank { "Pixiv 搜索失败" }
            error(msg)
        }
        val body = root.optJSONObject("body") ?: error("Pixiv 搜索返回缺少 body")
        val novel = body.optJSONObject("novel") ?: error("Pixiv 搜索返回缺少 novel")
        val data = novel.optJSONArray("data") ?: org.json.JSONArray()
        val total = novel.optLong("total", -1L).takeIf { it >= 0 }

        val items = mutableListOf<DiscoveryItem>()
        for (i in 0 until data.length()) {
            val o = data.optJSONObject(i) ?: continue
            val id = o.optString("id").trim()
            if (id.isBlank()) continue
            val title = o.optString("title").trim()
            if (title.isBlank()) continue
            val author = o.optString("userName").trim().takeIf { it.isNotBlank() }
            val descRaw = o.optString("description").trim()
            val summary = descRaw
                .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
                .replace(Regex("<[^>]+>"), "")
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'")
                .trim().ifBlank { null }
            val tagsArr = o.optJSONArray("tags")
            val tags = mutableListOf<String>()
            if (tagsArr != null) {
                for (j in 0 until tagsArr.length().coerceAtMost(12)) {
                    tagsArr.optString(j).trim().takeIf { it.isNotBlank() }?.let { tags += it }
                }
            }
            val xRestrict = o.optInt("xRestrict", 0)
            if (xRestrict == 1) tags.add(0, "R-18")
            else if (xRestrict == 2) tags.add(0, "R-18G")
            val wordCount = o.optLong("textCount", -1L).takeIf { it > 0 }
            val updatedAt = parseIso(o.optString("updateDate"))
            val seriesId = o.optString("seriesId").takeIf { it.isNotBlank() && it != "0" }
            val metrics = buildMap<String, String> {
                if (seriesId != null) {
                    val seriesTitle = o.optString("seriesTitle").trim()
                    put("系列", seriesTitle.ifBlank { "#$seriesId" })
                }
            }
            val detailUrl = if (seriesId != null) {
                "https://www.pixiv.net/novel/series/$seriesId"
            } else {
                "https://www.pixiv.net/novel/show.php?id=$id"
            }
            items += DiscoveryItem(
                id = if (seriesId != null) "s_$seriesId" else id,
                detailUrl = detailUrl,
                title = title,
                author = author,
                coverUrl = null,
                summary = summary,
                tags = tags.distinct().take(12),
                chapterCount = null,
                status = null,
                wordCount = wordCount,
                updatedAt = updatedAt,
                metrics = metrics,
            )
        }
        // 系列结果会重复（同一系列多篇会各自出现），按 id 去重保留首次顺序。
        val deduped = LinkedHashMap<String, DiscoveryItem>()
        for (it in items) {
            if (it.id !in deduped) deduped[it.id] = it
        }
        val result = deduped.values.toList()
        val hasMore = result.size >= 20 && (total == null || (page * 30L) < total)
        return DiscoveryPage(query = query, items = result, hasMore = hasMore, totalCount = total)
    }

    override suspend fun resolveDetailUrl(item: DiscoveryItem): String = item.detailUrl

    /**
     * Pixiv 的"分组相似作品"：仅返回「作者同作品」一块。
     * - 通过 `/ajax/novel/<id>` 拿 `userId`，再打 `/ajax/user/<userId>/profile/top`
     *
     * 系列项不支持，返回 null。空也返回 null。
     */
    override suspend fun fetchSimilarGroups(item: DiscoveryItem, limit: Int): List<SimilarGroup>? {
        if (item.id.startsWith("s_")) return null
        val novelId = item.id.takeIf { it.all(Char::isDigit) } ?: return null

        val sameAuthor = runCatching { fetchAuthorOtherNovels(novelId, limit) }
            .getOrDefault(emptyList())
            .filter { it.id != novelId }

        if (sameAuthor.isEmpty()) return null
        return listOf(SimilarGroup("作者同作品", sameAuthor))
    }

    /** 拉作者其他作品（profile/top）。 */
    private suspend fun fetchAuthorOtherNovels(novelId: String, limit: Int): List<DiscoveryItem> {
        // 先拿 userId
        val rawNovel = source.fetchSearchJson("https://www.pixiv.net/ajax/novel/$novelId")
        val rootNovel = org.json.JSONObject(rawNovel)
        val isErr0 = rootNovel.opt("error")?.toString()?.equals("true", ignoreCase = true) == true ||
            rootNovel.optInt("error", 0) == 1
        if (isErr0) return emptyList()
        val userId = rootNovel.optJSONObject("body")?.optString("userId")
            ?.takeIf { it.isNotBlank() && it != "0" }
            ?: return emptyList()

        val raw = source.fetchSearchJson(
            "https://www.pixiv.net/ajax/user/$userId/profile/top",
        )
        val root = org.json.JSONObject(raw)
        val isErr = root.opt("error")?.toString()?.equals("true", ignoreCase = true) == true ||
            root.optInt("error", 0) == 1
        if (isErr) return emptyList()
        val novels = root.optJSONObject("body")?.optJSONObject("novels") ?: return emptyList()
        val keys = novels.keys().asSequence().toMutableList()
        keys.sortByDescending { it.toLongOrNull() ?: 0L }
        val out = LinkedHashMap<String, DiscoveryItem>()
        for (key in keys) {
            if (key == novelId) continue
            val o = novels.optJSONObject(key) ?: continue
            val parsed = parseRecommendNovel(o) ?: continue
            out[parsed.id] = parsed
            if (out.size >= limit) break
        }
        return out.values.toList()
    }

    /** 解析 Pixiv ajax 中常见的 mini novel 节点（recommend / profile/top 共用同一结构）。 */
    private fun parseRecommendNovel(o: org.json.JSONObject): DiscoveryItem? {
        val recId = o.optString("id").trim()
        if (recId.isBlank() || recId == "0") return null
        val title = o.optString("title").trim()
        if (title.isBlank()) return null
        val author = o.optString("userName").trim().takeIf { it.isNotBlank() }
        val descRaw = o.optString("description").trim()
        val summary = descRaw
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'")
            .trim().ifBlank { null }
        val tagsArr = o.optJSONArray("tags")
        val tags = mutableListOf<String>()
        if (tagsArr != null) {
            for (j in 0 until tagsArr.length().coerceAtMost(12)) {
                tagsArr.optString(j).trim().takeIf { it.isNotBlank() }?.let { tags += it }
            }
        }
        val xRestrict = o.optInt("xRestrict", 0)
        if (xRestrict == 1) tags.add(0, "R-18")
        else if (xRestrict == 2) tags.add(0, "R-18G")
        val wordCount = o.optLong("textCount", -1L).takeIf { it > 0 }
        val updatedAt = parseIso(o.optString("updateDate"))
        return DiscoveryItem(
            id = recId,
            detailUrl = "https://www.pixiv.net/novel/show.php?id=$recId",
            title = title,
            author = author,
            summary = summary,
            tags = tags.distinct().take(12),
            wordCount = wordCount,
            updatedAt = updatedAt,
        )
    }

    private fun parseIso(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        return runCatching {
            // Pixiv 返回 ISO8601 带时区，例如 "2023-04-01T12:34:56+09:00"
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.US)
            fmt.parse(s)?.time
        }.getOrNull()
    }
}
