package com.zzy.llmreader.ui.reader

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.zzy.llmreader.data.LlmSettings
import com.zzy.llmreader.llm.ChatMessage
import com.zzy.llmreader.ui.ReaderViewModel
import com.zzy.llmreader.ui.ReaderUiState
import com.zzy.llmreader.web.DiscoveryItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * 发现页的批量翻译：把列表里的标题 / 简介 / 标签译成目标语言并缓存。
 *
 * 从 3144 行的 ReaderViewModel 里按职责拆出来，与阅读、生图链路解耦。
 * 两条独立管线：
 *  - 书籍信息（标题 + 简介）走"编号对齐"的批量请求
 *  - 标签走独立批次（标签短、数量多，混在一起会拖慢主结果）
 * 两者都带本地缓存（tagTranslationCache / bookInfoTranslationCache），
 * 命中即跳过，避免重复烧 token。
 */
    /**
     * 批量翻译当前结果（标题+简介一次、tag 一次，两个接口并发）。
     * 命中缓存的不会重复请求；未命中的拼成一份提示一并发出去。
     */
    internal fun ReaderViewModel.translateDiscoveryBatch(items: List<com.zzy.llmreader.web.DiscoveryItem>) {
        if (items.isEmpty()) return
        // 启动后第一次走到这儿时缓存可能还没加载完——先等它，再读缓存。
        viewModelScope.launch {
            runCatching { discoveryCacheLoad?.join() }
            translateDiscoveryBatchInternal(items)
        }
    }

    internal fun ReaderViewModel.translateDiscoveryBatchInternal(items: List<com.zzy.llmreader.web.DiscoveryItem>) {
        val settings = _state.value.settings
        if (settings.apiKey.isBlank()) {
            _state.update {
                it.copy(discovery = it.discovery.copy(error = "未配置 LLM API Key，无法翻译"))
            }
            return
        }
        val target = _state.value.readerPrefs.translationLanguage
        val style = _state.value.readerPrefs.translationStyle

        // ---- 准备 book info 待译列表 ----
        val pendingBooks = items.mapNotNull { item ->
            if (!item.translatedTitle.isNullOrBlank() &&
                (item.summary.isNullOrBlank() || !item.translatedSummary.isNullOrBlank())
            ) return@mapNotNull null
            // 原文已是目标语言（如中文）时不请求翻译。
            val summary = item.summary
            if (looksLikeTargetLanguage(item.title, target) &&
                (summary.isNullOrBlank() || looksLikeTargetLanguage(summary, target))
            ) return@mapNotNull null
            val key = bookInfoCacheKey(target, item.title, item.summary.orEmpty())
            bookInfoTranslationCache[key]?.let { (t, s) ->
                applyBookInfoTranslation(item.id, t, s)
                return@mapNotNull null
            }
            BookJob(item.id, item.title, item.summary?.take(800).orEmpty())
        }

        // ---- 准备 tag 待译列表 ----
        val allTags = items.flatMap { it.tags }.distinct().filter { it.isNotBlank() }
        val pendingTags = allTags.filter {
            tagTranslationCache["$target|$it"] == null && !looksLikeTargetLanguage(it, target)
        }
        // 命中缓存的 tag 立刻应用一次，UI 立即出译文。
        applyCachedTagTranslations(target)

        if (pendingBooks.isEmpty() && pendingTags.isEmpty()) return

        val markIds = (pendingBooks.map { it.id } +
            items.filter { it.tags.any { tag -> tag in pendingTags } }.map { it.id }).toSet()
        _state.update {
            it.copy(discovery = it.discovery.copy(translating = it.discovery.translating + markIds))
        }

        viewModelScope.launch {
            val bookJob = if (pendingBooks.isNotEmpty()) {
                async {
                    runCatching {
                        translateBookInfosOnce(settings, target, style, pendingBooks)
                    }.onFailure {
                        android.util.Log.w("ReaderVM", "translate book infos failed", it)
                    }.getOrElse { e ->
                        _state.update { st ->
                            st.copy(discovery = st.discovery.copy(
                                error = "书籍信息翻译失败：${e.message ?: e.javaClass.simpleName}",
                            ))
                        }
                        emptyMap()
                    }
                }
            } else null
            val tagJob = if (pendingTags.isNotEmpty()) {
                async {
                    runCatching {
                        translateTagsOnce(settings, target, pendingTags)
                    }.onFailure {
                        android.util.Log.w("ReaderVM", "translate tags failed", it)
                    }.getOrElse { e ->
                        _state.update { st ->
                            st.copy(discovery = st.discovery.copy(
                                error = "标签翻译失败：${e.message ?: e.javaClass.simpleName}",
                            ))
                        }
                        emptyMap()
                    }
                }
            } else null
            val bookResults = bookJob?.await().orEmpty()
            val tagResults = tagJob?.await().orEmpty()
            android.util.Log.i(
                "ReaderVM",
                "discovery batch translate: books=${pendingBooks.size}->${bookResults.size}, " +
                    "tags=${pendingTags.size}->${tagResults.size}",
            )

            // 写缓存
            for ((id, pair) in bookResults) {
                val item = items.firstOrNull { it.id == id } ?: continue
                bookInfoTranslationCache[bookInfoCacheKey(target, item.title, item.summary.orEmpty())] = pair
            }
            for ((tag, tx) in tagResults) {
                if (tx.isNotBlank()) tagTranslationCache["$target|$tag"] = tx
            }
            // 落盘（异常吞掉，不影响 UI）
            if (bookResults.isNotEmpty()) {
                runCatching { discoveryTxRepo.saveBooks(bookInfoTranslationCache) }
                    .onFailure { android.util.Log.w("ReaderVM", "save book tx cache failed", it) }
            }
            if (tagResults.isNotEmpty()) {
                runCatching { discoveryTxRepo.saveTags(tagTranslationCache) }
                    .onFailure { android.util.Log.w("ReaderVM", "save tag tx cache failed", it) }
            }

            // 应用到 state
            _state.update { st ->
                val d = st.discovery
                val newItems = d.items.map { i ->
                    val newTitleSummary = bookResults[i.id]
                    val newTags = applyTagsForItem(i, target)
                    i.copy(
                        translatedTitle = newTitleSummary?.first?.takeIf { it.isNotBlank() }
                            ?: i.translatedTitle,
                        translatedSummary = newTitleSummary?.second?.takeIf { it.isNotBlank() }
                            ?: i.translatedSummary,
                        translatedTags = newTags ?: i.translatedTags,
                    )
                }
                st.copy(
                    discovery = d.copy(
                        items = newItems,
                        translating = d.translating - markIds,
                    ),
                )
            }

            // 请求发了却一条都没解析出来 → 明确报错，不要只是把转圈停掉。
            // 这几条链路曾因破限与输出契约打架而整批解析失败，UI 上表现为
            // "点了翻译什么都没发生"，用户没有任何线索可判断。
            val silent = buildList {
                if (pendingBooks.isNotEmpty() && bookResults.isEmpty()) add("书目")
                if (pendingTags.isNotEmpty() && tagResults.isEmpty()) add("标签")
            }
            if (silent.isNotEmpty()) {
                _state.update {
                    it.copy(
                        discovery = it.discovery.copy(
                            error = "${silent.joinToString("、")}翻译没有结果：模型未按指定格式输出（可重试或换模型）",
                        ),
                    )
                }
            }
        }
    }

    /** 把已缓存的 tag 翻译写到当前列表里（无网络请求）。 */
    internal fun ReaderViewModel.applyCachedTagTranslations(target: String) {
        _state.update { st ->
            val d = st.discovery
            val items = d.items.map { i ->
                val newTags = applyTagsForItem(i, target)
                if (newTags == null) i else i.copy(translatedTags = newTags)
            }
            st.copy(discovery = d.copy(items = items))
        }
    }

    /** 用缓存表给一个 item 拼出 translatedTags，全空则返回 null（保持原值）。 */
    internal fun ReaderViewModel.applyTagsForItem(
        i: com.zzy.llmreader.web.DiscoveryItem,
        target: String,
    ): List<String>? {
        if (i.tags.isEmpty()) return null
        val translated = i.tags.map { tag -> tagTranslationCache["$target|$tag"].orEmpty() }
        return if (translated.any { it.isNotBlank() }) translated else null
    }

    internal fun ReaderViewModel.applyBookInfoTranslation(itemId: String, title: String, summary: String) {
        _state.update { st ->
            val d = st.discovery
            val items = d.items.map { i ->
                if (i.id != itemId) i else i.copy(
                    translatedTitle = title.ifBlank { i.translatedTitle },
                    translatedSummary = summary.ifBlank { i.translatedSummary },
                )
            }
            st.copy(discovery = d.copy(items = items))
        }
    }

    /** 一次性把多本书的标题+简介丢给 LLM。返回 itemId → (译标题, 译简介)。
     *  现在采用：拆成多个小块并发 + 流式返回。每一本在流中解析出来后就立即更新 UI，
     *  不用等一整批都拿回才出译文。什么本都是独立请求，某本失败不会拖累其他本。。
     *  若一整批被服务端过滤（HTTP 500 "Response got filtered" 等），自动二分降级递归，
     *  让"干净"的部分仍能翻成功，单条仍失败就跳过。 */
    internal suspend fun ReaderViewModel.translateBookInfosOnce(
        settings: com.zzy.llmreader.data.LlmSettings,
        target: String,
        style: String,
        jobs: List<BookJob>,
    ): Map<String, Pair<String, String>> {
        if (jobs.isEmpty()) return emptyMap()
        val chunkSize = 3
        val chunks = jobs.chunked(chunkSize)
        // 限制并发：同一时刻最多 2 个 LLM 流式请求，避免被网关限流。
        val sem = Semaphore(2)
        return kotlinx.coroutines.coroutineScope {
            chunks.map { chunk ->
                async {
                    sem.withPermit { translateBookInfosChunkSafe(settings, target, style, chunk) }
                }
            }.awaitAll().fold(LinkedHashMap<String, Pair<String, String>>()) { acc, m -> acc.putAll(m); acc }
        }
    }

    /** 单个块，带过滤重试二分。 */
    internal suspend fun ReaderViewModel.translateBookInfosChunkSafe(
        settings: com.zzy.llmreader.data.LlmSettings,
        target: String,
        style: String,
        jobs: List<BookJob>,
    ): Map<String, Pair<String, String>> {
        if (jobs.isEmpty()) return emptyMap()
        val result = runCatching { translateBookInfosChunkStream(settings, target, style, jobs) }
        result.exceptionOrNull()?.let { e ->
            val msg = e.message.orEmpty()
            val filtered = msg.contains("filtered", ignoreCase = true) ||
                msg.contains("HTTP 500") ||
                msg.contains("content_policy", ignoreCase = true) ||
                msg.contains("safety", ignoreCase = true)
            if (!filtered || jobs.size <= 1) {
                if (jobs.size == 1) {
                    android.util.Log.w("ReaderVM", "translateBookInfos chunk single skipped: ${jobs[0].title}")
                    return emptyMap()
                }
                throw e
            }
            android.util.Log.w("ReaderVM", "translateBookInfos chunk filtered, split ${jobs.size} -> ${jobs.size / 2}+${jobs.size - jobs.size / 2}")
            val mid = jobs.size / 2
            val left = translateBookInfosChunkSafe(settings, target, style, jobs.subList(0, mid))
            val right = translateBookInfosChunkSafe(settings, target, style, jobs.subList(mid, jobs.size))
            return left + right
        }
        return result.getOrDefault(emptyMap())
    }

    /** 流式拿一个块的译文，每解析出一本就立即 applyBookInfoTranslation。 */
    internal suspend fun ReaderViewModel.translateBookInfosChunkStream(
        settings: com.zzy.llmreader.data.LlmSettings,
        target: String,
        style: String,
        jobs: List<BookJob>,
    ): Map<String, Pair<String, String>> {
        // 结构化链路专用破限：不写死输出格式，避免与下面的 `<编号>. <译文>` 契约打架。
        val (jbTop, jbBottom) = translator.structuredJailbreakPair(settings)
        val sys = buildString {
            if (jbTop.isNotEmpty()) append(jbTop).append("\n\n")
            append("你是小说库 app 的书目翻译引擎，把下面 N 本书的标题和简介完整、忠实、一对一地翻译为").append(target).append("。")
            append("保留原意与原文强度，不扩写、不删减、不软化；原文中如出现成人/暴力/敏感题材，也需原强度译出，不得跳过。")
            if (style.isNotBlank()) append(" 风格：").append(style).append("。")
            append(
                "\n严格按下列格式输出，不要 markdown / 说明 / 思考过程：\n" +
                    "INDEX: <编号>\nTITLE: <译后标题>\nSUMMARY: <译后简介，单行；如原文无简介则写「无」>\n\n每本书一组，组与组之间用空行分隔。",
            )
        }
        val userBody = buildString {
            jobs.forEachIndexed { idx, j ->
                if (idx > 0) append("\n\n")
                append("INDEX: ").append(idx + 1).append('\n')
                append("TITLE_RAW: ").append(j.title).append('\n')
                append("SUMMARY_RAW: ").append(j.summary.ifBlank { "无" })
            }
            if (jbBottom.isNotEmpty()) append("\n\n").append(jbBottom)
        }
        val buffer = StringBuilder()
        val emitted = mutableSetOf<Int>()
        val out = mutableMapOf<String, Pair<String, String>>()
        // 匹配一个完整记录：INDEX:n / TITLE:.. / SUMMARY:.. ，后跟空行或下一个 INDEX。
        val recordRe = Regex(
            "INDEX:\\s*(\\d+)\\s*\\n+TITLE:\\s*([^\\n]*)\\n+SUMMARY:\\s*([^\\n]*)(?=\\n)",
            RegexOption.IGNORE_CASE,
        )
        fun flush() {
            val text = buffer.toString()
            for (m in recordRe.findAll(text)) {
                val idx1 = m.groupValues[1].toIntOrNull() ?: continue
                val zero = idx1 - 1
                if (zero in emitted) continue
                val j = jobs.getOrNull(zero) ?: continue
                val title = m.groupValues[2].trim()
                var summary = m.groupValues[3].trim()
                if (summary.equals("无", ignoreCase = true)) summary = ""
                applyBookInfoTranslation(j.id, title, summary)
                out[j.id] = title to summary
                emitted += zero
                // 同时落缓存，避免套中间进程被中断后下次重复请求。
                bookInfoTranslationCache[bookInfoCacheKey(target, j.title, j.summary)] = title to summary
            }
        }
        val full = client.chatStream(
            settings,
            listOf(
                com.zzy.llmreader.llm.ChatMessage(role = "system", content = sys),
                com.zzy.llmreader.llm.ChatMessage(role = "user", content = userBody),
            ),
            forceMinimalReasoning = true,
            // 助手预填：直接以第一条记录的格式开头，模型无法以解释/拒绝起笔。
            prefill = "INDEX: 1\nTITLE: ",
        ) { delta ->
            buffer.append(delta)
            flush()
        }.getOrElse { throw it }
        // 流结束后再走一次传统 parser，兼容最后一本未以 \n 结尾。
        if (emitted.size < jobs.size) {
            val parsed = parseBatchTitleSummary(full)
            for ((idx1, pair) in parsed) {
                val zero = idx1 - 1
                if (zero in emitted) continue
                val j = jobs.getOrNull(zero) ?: continue
                val title = pair.first
                val summary = pair.second
                applyBookInfoTranslation(j.id, title, summary)
                out[j.id] = title to summary
                emitted += zero
                bookInfoTranslationCache[bookInfoCacheKey(target, j.title, j.summary)] = title to summary
            }
        }
        if (out.isEmpty()) {
            android.util.Log.w("ReaderVM", "translateBookInfosChunkStream empty parse, raw=${full.take(500)}")
        }
        return out
    }

    internal suspend fun ReaderViewModel.translateBookInfosBatch(
        settings: com.zzy.llmreader.data.LlmSettings,
        target: String,
        style: String,
        jobs: List<BookJob>,
    ): Map<String, Pair<String, String>> = translateBookInfosChunkStream(settings, target, style, jobs)

    /** 翻译一批 tag。按 12 个一组切块，最多 3 个并发。整组被过滤则二分降级。 */
    internal suspend fun ReaderViewModel.translateTagsOnce(
        settings: com.zzy.llmreader.data.LlmSettings,
        target: String,
        tags: List<String>,
    ): Map<String, String> {
        if (tags.isEmpty()) return emptyMap()
        val chunks = tags.chunked(12)
        val sem = Semaphore(3)
        return kotlinx.coroutines.coroutineScope {
            chunks.map { chunk ->
                async {
                    sem.withPermit { translateTagsChunkSafe(settings, target, chunk) }
                }
            }.awaitAll().fold(LinkedHashMap<String, String>()) { acc, m ->
                // 单组完成立刻把结果写进缓存并刷新 UI，让翻译"流式"出现。
                for ((k, v) in m) {
                    if (v.isNotBlank()) tagTranslationCache["$target|$k"] = v
                }
                applyCachedTagTranslations(target)
                acc.putAll(m); acc
            }
        }
    }

    /** 单组 tag 翻译，遇过滤则二分。 */
    internal suspend fun ReaderViewModel.translateTagsChunkSafe(
        settings: com.zzy.llmreader.data.LlmSettings,
        target: String,
        tags: List<String>,
    ): Map<String, String> {
        if (tags.isEmpty()) return emptyMap()
        val result = runCatching { translateTagsBatch(settings, target, tags) }
        result.exceptionOrNull()?.let { e ->
            val msg = e.message.orEmpty()
            val filtered = msg.contains("filtered", ignoreCase = true) ||
                msg.contains("HTTP 500") ||
                msg.contains("content_policy", ignoreCase = true) ||
                msg.contains("safety", ignoreCase = true)
            if (!filtered || tags.size <= 1) {
                if (tags.size == 1) {
                    android.util.Log.w("ReaderVM", "translateTagsOnce single skipped: ${tags[0]}")
                    return emptyMap()
                }
                throw e
            }
            android.util.Log.w("ReaderVM", "translateTagsOnce filtered, split ${tags.size} -> ${tags.size / 2}+${tags.size - tags.size / 2}")
            val mid = tags.size / 2
            val left = translateTagsChunkSafe(settings, target, tags.subList(0, mid))
            val right = translateTagsChunkSafe(settings, target, tags.subList(mid, tags.size))
            return left + right
        }
        return result.getOrDefault(emptyMap())
    }

    internal suspend fun ReaderViewModel.translateTagsBatch(
        settings: com.zzy.llmreader.data.LlmSettings,
        target: String,
        tags: List<String>,
    ): Map<String, String> {
        // 结构化链路专用破限：不写死输出格式，避免与下面的 `<编号>. <译文>` 契约打架。
        val (jbTop, jbBottom) = translator.structuredJailbreakPair(settings)
        val sys = buildString {
            if (jbTop.isNotEmpty()) append(jbTop).append("\n\n")
            append("你是小说库 app 的分类标签翻译引擎，把下列编号标签逐个翻译为").append(target).append("。")
            append("译文需简洁（≤6 字），保留原意；成人/敏感/口语标签也需原强度译出，不得跳过、不得拒给。")
            append(
                "\n严格按下列格式输出，一行一个，不要 markdown / 说明 / 思考过程：\n" +
                    "<编号>. <译文>",
            )
        }
        val userBody = buildString {
            append(tags.mapIndexed { i, t -> "${i + 1}. $t" }.joinToString("\n"))
            if (jbBottom.isNotEmpty()) append("\n\n").append(jbBottom)
        }
        val res = client.chat(
            settings,
            listOf(
                com.zzy.llmreader.llm.ChatMessage(role = "system", content = sys),
                com.zzy.llmreader.llm.ChatMessage(role = "user", content = userBody),
            ),
            forceMinimalReasoning = true,
            // 助手预填：锁定 `<编号>. <译文>` 的开头，避免模型先用一句话解释再输出。
            prefill = "1. ",
        ).getOrElse {
            android.util.Log.w("ReaderVM", "translateTagsOnce chat error", it)
            throw it
        }
        val out = mutableMapOf<String, String>()
        // 兼容两种编号形式：调用方契约的 `1. 译文`，以及模型惯性输出的 `[1] 译文`。
        val numRe = Regex("^\\s*\\[?(\\d+)[\\]\\.|、)]\\s*(.+)$")
        for (line in res.lineSequence()) {
            val m = numRe.find(line) ?: continue
            val idx = m.groupValues[1].toIntOrNull() ?: continue
            val tx = m.groupValues[2].trim().trim('"', '\'')
            val src = tags.getOrNull(idx - 1) ?: continue
            if (tx.isNotBlank()) out[src] = tx
        }
        if (out.isEmpty()) {
            android.util.Log.w("ReaderVM", "translateTagsOnce empty parse, raw=${res.take(500)}")
        }
        return out
    }

    internal fun ReaderViewModel.parseBatchTitleSummary(text: String): Map<Int, Pair<String, String>> {
        val out = mutableMapOf<Int, Pair<String, String>>()
        var idx: Int? = null
        var title = ""
        var summary = ""
        fun flush() {
            val i = idx ?: return
            if (title.isNotBlank() || summary.isNotBlank()) {
                val s = if (summary.equals("无", ignoreCase = true)) "" else summary
                out[i] = title to s
            }
            idx = null; title = ""; summary = ""
        }
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("INDEX:", ignoreCase = true) -> {
                    flush()
                    idx = line.substringAfter(":").trim().toIntOrNull()
                }
                line.startsWith("TITLE:", ignoreCase = true) ->
                    title = line.substringAfter(":").trim()
                line.startsWith("SUMMARY:", ignoreCase = true) ->
                    summary = line.substringAfter(":").trim()
            }
        }
        flush()
        return out
    }

/** 一次书籍信息（标题+简介）翻译任务的输入。 */
internal data class BookJob(val id: String, val title: String, val summary: String)
