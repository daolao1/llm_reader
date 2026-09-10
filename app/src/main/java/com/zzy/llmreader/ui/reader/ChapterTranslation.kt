package com.zzy.llmreader.ui.reader

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.zzy.llmreader.llm.ChapterTranslator
import com.zzy.llmreader.llm.ChatMessage
import com.zzy.llmreader.model.BookContent
import com.zzy.llmreader.ui.ReaderViewModel
import com.zzy.llmreader.ui.ReaderUiState
import com.zzy.llmreader.web.toBookContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 章节翻译：把一章 HTML 交给 LLM 译成目标语言，并把结果写回 state / 缓存。
 *
 * 这是整个 App 最重的一条链路，从 ReaderViewModel 里整体搬出来：
 *  - 分批流式翻译，收到一段就推一段到 UI（onPartial）
 *  - 每批翻完更新术语表（LRU 刷新 + 可选智能去重）
 *  - 网页书把译文落盘缓存，重开不必重翻
 *  - 同语言直接跳过，失败可重试
 * 辅助函数 stripHtml 仍留在 ViewModel（阅读页也在用）。
 */
    /**
     * 翻译指定章节。已翻译且语言匹配则跳过；否则调用 LLM 流式翻译并写回缓存。
     * 支持任意来源的书（网页 / 本地 EPUB / TXT）：网页书额外持久化到磁盘。
     */
    internal fun ReaderViewModel.ensureChapterTranslated(index: Int) {
        val cur = _state.value
        val book = cur.book as? BookContent.Text ?: return
        if (index !in book.chapters.indices) return
        if (index in cur.translatingChapters) return
        if (book.chapters[index].translatedContent != null) return // 已翻译，直接复用

        val bookKey = currentBookKey // 发起翻译时的书身份，回调落地前用于校验书没被切换
        val canonical = cur.webCanonicalUrl // 可能为 null（本地书）
        val targetLang = cur.readerPrefs.translationLanguage
        val chapter = book.chapters[index]
        val original = chapter.content
        if (original == null) {
            // 网页书章节正文还没下载，先下载
            ensureChapterLoaded(index)
            translationJobs[index] = viewModelScope.launch {
                repeat(40) {
                    if (bookKey != currentBookKey) return@launch
                    val updated = (_state.value.book as? BookContent.Text)
                        ?.chapters?.getOrNull(index)?.content
                    if (updated != null) {
                        ensureChapterTranslated(index)
                        return@launch
                    }
                    kotlinx.coroutines.delay(500)
                }
            }
            return
        }

        // 清掉之前的失败记录
        _state.update {
            it.copy(
                translatingChapters = it.translatingChapters + index,
                translationProgress = "翻译准备中…",
                failedTranslations = it.failedTranslations - index,
            )
        }

        translationJobs[index] = viewModelScope.launch {
            val settings = settingsRepo.settings.first()
            if (settings.apiKey.isBlank()) {
                markTranslationFailed(bookKey, index, "尚未配置 API Key，请到设置里填写")
                return@launch
            }

            // 拿到原文 HTML：网页书从缓存读取（保留 <p> 结构）；本地书用 Spanned 转 HTML
            val originalHtml: String
            val originalTitle: String
            if (canonical != null) {
                val placeholder = withContext(Dispatchers.IO) { webCache.read(canonical) }
                    ?.chapters?.getOrNull(index)
                if (placeholder?.html == null) {
                    markTranslationFailed(bookKey, index, "原文 HTML 缺失，请重新打开本章")
                    return@launch
                }
                // 同语言已翻译则跳过
                if (placeholder.translatedHtml != null && placeholder.translationLanguage == targetLang) {
                    refreshBookFromCache(canonical)
                    _state.update {
                        it.copy(
                            translatingChapters = it.translatingChapters - index,
                            translationProgress = null,
                        )
                    }
                    return@launch
                }
                originalHtml = placeholder.html.orEmpty()
                originalTitle = placeholder.title
            } else {
                // 本地书的正文本来就是 HTML 字符串，直接用
                originalHtml = chapter.content.orEmpty()
                originalTitle = chapter.title
            }

            // 原文已经是目标语言（如简体中文 + 中文文章）→ 不发 LLM，直接把原文当译文用。
            if (looksLikeTargetLanguage(originalTitle, targetLang) &&
                looksLikeTargetLanguage(stripHtml(originalHtml), targetLang)
            ) {
                if (canonical != null) {
                    cacheMutex.withLock {
                        val cached = withContext(Dispatchers.IO) { webCache.read(canonical) }
                        if (cached != null) {
                            val updated = cached.copy(
                                chapters = cached.chapters.toMutableList().apply {
                                    val ch = this.getOrNull(index) ?: return@apply
                                    this[index] = ch.copy(
                                        translatedHtml = ch.html,
                                        translatedTitle = ch.title,
                                        translationLanguage = targetLang,
                                    )
                                },
                            )
                            withContext(Dispatchers.IO) { webCache.write(updated) }
                        }
                    }
                    refreshBookFromCache(canonical)
                } else {
                    // 本地书：直接把原文塞到 translatedContent
                    _state.update { st ->
                        val b = st.book as? BookContent.Text ?: return@update st
                        val newChapters = b.chapters.toMutableList().also {
                            val ch = it[index]
                            it[index] = ch.copy(
                                translatedTitle = ch.title,
                                translatedContent = ch.content,
                            )
                        }
                        st.copy(book = b.copy(chapters = newChapters))
                    }
                }
                _state.update {
                    it.copy(
                        translatingChapters = it.translatingChapters - index,
                        translationProgress = null,
                    )
                }
                android.util.Log.i("ReaderVM", "chapter $index already in target language, skip translate")
                return@launch
            }

            // 流式更新节流：避免每个 token 都触发整章重排
            val partialState = PartialBuffer()

            val styleHint = _state.value.readerPrefs.translationStyle
            val dedupeInterval = _state.value.readerPrefs.glossaryDedupeInterval
            val glossaryMaxSize = _state.value.readerPrefs.glossaryMaxSize
            var batchCounter = 0

            // 跨章节上下文：取上一章末尾几段译文（仅当上一章已翻译且语言匹配）
            val initialContext: String? = if (canonical != null && index > 0) {
                val prev = withContext(Dispatchers.IO) { webCache.read(canonical) }
                    ?.chapters?.getOrNull(index - 1)
                val prevTranslated = prev?.translatedHtml
                if (prevTranslated != null && prev.translationLanguage == targetLang) {
                    translator.extractTailContext(prevTranslated).takeIf { it.isNotBlank() }
                } else null
            } else null

            val result = translator.translate(
                settings = settings,
                targetLanguage = targetLang,
                chapterTitle = originalTitle,
                chapterHtml = originalHtml,
                styleHint = styleHint,
                initialPreviousContext = initialContext,
                inlineImagesPerChapter = if (_state.value.imageGenSettings.enableInlineImages) {
                    _state.value.imageGenSettings.inlineImagesPerChapter
                } else 0,
                batchChars = _state.value.readerPrefs.translationBatchChars,
                glossaryProvider = {
                    // 每批翻译前动态读取最新术语表（网页书 → cache，本地书 → 本地存储）
                    val entries = if (canonical != null) {
                        withContext(Dispatchers.IO) { webCache.read(canonical) }?.glossary ?: emptyList()
                    } else {
                        val key = _state.value.book?.title.orEmpty()
                        if (key.isBlank()) emptyList()
                        else withContext(Dispatchers.IO) { localGlossary.read(key) }
                    }
                    entries.joinToString("\n") { "${it.original} → ${it.translated}" }
                },
                onProgress = { current, total ->
                    _state.update { it.copy(translationProgress = "翻译中 $current/$total") }
                },
                onPartial = { partialTitle, partialHtml ->
                    if (partialState.shouldFlush()) {
                        applyPartialTranslation(bookKey, index, partialTitle, partialHtml)
                    }
                },
                onBatchTranslated = { batchOriginal, batchTranslated ->
                    batchCounter++
                    val localKey: String? = if (canonical == null) _state.value.book?.title?.takeIf { it.isNotBlank() } else null
                    if (canonical != null) {
                        val now = System.currentTimeMillis()
                        val batchText = batchOriginal.joinToString(" ")

                        // 1) LRU：刷新出现在本批原文中的术语的 lastUsed
                        runCatching {
                            cacheMutex.withLock {
                                val cached = withContext(Dispatchers.IO) { webCache.read(canonical) }
                                    ?: return@withLock
                                var touched = false
                                val refreshed = cached.glossary.map { entry ->
                                    if (batchText.contains(entry.original)) {
                                        touched = true
                                        entry.copy(lastUsed = now)
                                    } else entry
                                }
                                if (touched) {
                                    val updated = cached.copy(glossary = refreshed)
                                    withContext(Dispatchers.IO) { webCache.write(updated) }
                                }
                            }
                        }

                        // 2) 提取新术语并合并
                        runCatching {
                            val extracted = translator.extractGlossary(
                                settings, targetLang,
                                batchOriginal.joinToString("\n") { "<p>$it</p>" },
                                batchTranslated.joinToString("\n") { "<p>$it</p>" },
                            )
                            if (extracted.isNotBlank()) {
                                val newEntries = parseGlossaryText(extracted)
                                if (newEntries.isNotEmpty()) {
                                    cacheMutex.withLock {
                                        val cached = withContext(Dispatchers.IO) { webCache.read(canonical) }
                                            ?: return@withLock
                                        val existing = cached.glossary
                                        val existingKeys = existing.map { it.original }.toSet()
                                        val merged = existing + newEntries.filter { it.original !in existingKeys }
                                        // LRU 淘汰：超过上限时按 lastUsed 从旧到新排序，保留最新的
                                        val trimmed = if (merged.size > glossaryMaxSize) {
                                            merged.sortedByDescending { it.lastUsed }.take(glossaryMaxSize)
                                        } else merged
                                        if (trimmed != existing) {
                                            val updated = cached.copy(glossary = trimmed)
                                            withContext(Dispatchers.IO) { webCache.write(updated) }
                                        }
                                    }
                                }
                            }
                        }

                        // 3) 每 N 批做一次 LLM 智能去重
                        if (dedupeInterval > 0 && batchCounter % dedupeInterval == 0) {
                            runCatching {
                                val current = withContext(Dispatchers.IO) { webCache.read(canonical) }?.glossary ?: emptyList()
                                if (current.size > 3) {
                                    val glossaryText = current.joinToString("\n") { "${it.original} → ${it.translated}" }
                                    val deduped = translator.deduplicateGlossary(settings, glossaryText)
                                    if (deduped.isNotBlank()) {
                                        val dedupedEntries = parseGlossaryText(deduped)
                                        if (dedupedEntries.isNotEmpty()) {
                                            cacheMutex.withLock {
                                                val cached = withContext(Dispatchers.IO) { webCache.read(canonical) }
                                                    ?: return@withLock
                                                val updated = cached.copy(glossary = dedupedEntries)
                                                withContext(Dispatchers.IO) { webCache.write(updated) }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else if (localKey != null) {
                        // 本地书：抽取新术语并合并到本地存储
                        runCatching {
                            val extracted = translator.extractGlossary(
                                settings, targetLang,
                                batchOriginal.joinToString("\n") { "<p>$it</p>" },
                                batchTranslated.joinToString("\n") { "<p>$it</p>" },
                            )
                            if (extracted.isNotBlank()) {
                                val newEntries = parseGlossaryText(extracted)
                                if (newEntries.isNotEmpty()) {
                                    val existing = withContext(Dispatchers.IO) { localGlossary.read(localKey) }
                                    val existingKeys = existing.map { it.original }.toSet()
                                    val merged = existing + newEntries.filter { it.original !in existingKeys }
                                    val trimmed = if (merged.size > glossaryMaxSize) {
                                        merged.sortedByDescending { it.lastUsed }.take(glossaryMaxSize)
                                    } else merged
                                    if (trimmed != existing) {
                                        withContext(Dispatchers.IO) { localGlossary.write(localKey, trimmed) }
                                    }
                                }
                            }
                        }
                    }
                },
            )

            when (result) {
                is ChapterTranslator.TranslateResult.Success -> {
                    // 写回内存
                    applyPartialTranslation(bookKey, index, result.translatedTitle, result.html)
                    // 网页书 → 同时持久化到磁盘
                    if (canonical != null) {
                        cacheMutex.withLock {
                            val cached = withContext(Dispatchers.IO) { webCache.read(canonical) }
                                ?: return@withLock
                            val chapters = cached.chapters.toMutableList()
                            chapters[index] = chapters[index].copy(
                                translatedHtml = result.html,
                                translatedTitle = result.translatedTitle,
                                translationLanguage = targetLang,
                            )
                            val updated = cached.copy(chapters = chapters)
                            withContext(Dispatchers.IO) { webCache.write(updated) }
                        }
                    }
                    _state.update {
                        it.copy(
                            translatingChapters = it.translatingChapters - index,
                            translationProgress = null,
                            failedTranslations = it.failedTranslations - index,
                        )
                    }
                }
                is ChapterTranslator.TranslateResult.Failure -> {
                    markTranslationFailed(bookKey, index, result.message)
                }
            }
        }
    }

    /** 用户在 UI 上点"重试"时调用：清掉失败记录 + 已翻译标记，重新触发翻译。 */
    internal fun ReaderViewModel.retryChapterTranslation(index: Int) {
        val cur = _state.value
        val book = cur.book as? BookContent.Text ?: return
        if (index !in book.chapters.indices) return
        // 清掉内存中已写入的部分译文，让 ensureChapterTranslated 重新跑
        val chapters = book.chapters.toMutableList()
        chapters[index] = chapters[index].copy(
            translatedTitle = null,
            translatedContent = null,
        )
        _state.update {
            it.copy(
                book = BookContent.Text(book.title, chapters),
                failedTranslations = it.failedTranslations - index,
                translatingChapters = it.translatingChapters - index,
            )
        }
        ensureChapterTranslated(index)
    }

    /** [bookKey] 是发起翻译时的 currentBookKey；书已切换/关闭则丢弃这次回调，避免串书。 */
    internal fun ReaderViewModel.markTranslationFailed(bookKey: String?, index: Int, message: String) {
        if (bookKey != currentBookKey) return
        _state.update {
            it.copy(
                translatingChapters = it.translatingChapters - index,
                translationProgress = null,
                failedTranslations = it.failedTranslations + (index to message),
                error = message,
            )
        }
    }

    /** [bookKey] 是发起翻译时的 currentBookKey；书已切换/关闭则丢弃这次回调，避免串书。 */
    internal fun ReaderViewModel.applyPartialTranslation(bookKey: String?, index: Int, title: String?, html: String) {
        if (bookKey != currentBookKey) return
        _state.update { state ->
            val book = state.book as? BookContent.Text ?: return@update state
            if (index !in book.chapters.indices) return@update state
            val chapters = book.chapters.toMutableList()
            val cur = chapters[index]
            chapters[index] = cur.copy(
                translatedTitle = title?.takeIf { it.isNotBlank() } ?: cur.translatedTitle,
                translatedContent = if (html.isNotBlank()) html else cur.translatedContent,
            )
            state.copy(book = BookContent.Text(book.title, chapters))
        }
    }

    internal suspend fun ReaderViewModel.refreshBookFromCache(canonical: String) {
        val cached = withContext(Dispatchers.IO) { webCache.read(canonical) } ?: return
        _state.update { state ->
            var next = state
            if (state.webCanonicalUrl == canonical) {
                next = next.copy(book = cached.toBookContent())
            }
            // 详情页同样持有目录的译名快照——目录翻译落盘后也要同步给它，
            // 否则 BookDetailScreen 的章节列表不会显示译名。
            val d = next.bookDetail
            if (d != null && (d.key == canonical || d.detailUrl == canonical)) {
                next = next.copy(
                    bookDetail = d.copy(
                        chapters = cached.chapters.map { it.title },
                        translatedChapters = cached.chapters.map { it.translatedTitle },
                    ),
                )
            }
            next
        }
    }

private class PartialBuffer {
        private var lastFlushAt = 0L
        fun shouldFlush(): Boolean {
            val now = System.currentTimeMillis()
            return if (now - lastFlushAt > 250) {
                lastFlushAt = now
                true
            } else false
        }
    }
