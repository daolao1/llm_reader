package com.zzy.llmreader.desktop

import com.zzy.llmreader.LlmReaderCore
import com.zzy.llmreader.data.LlmSettings
import com.zzy.llmreader.data.ReaderPreferences
import com.zzy.llmreader.data.RecentBook
import com.zzy.llmreader.data.RecentBookSource
import com.zzy.llmreader.data.TranslationMode
import com.zzy.llmreader.llm.ChapterTranslator
import com.zzy.llmreader.model.BookContent
import com.zzy.llmreader.platform.AppDirs
import com.zzy.llmreader.web.toBookContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** 桌面端默认数据目录：~/.llm_reader（可用 LLM_READER_HOME 覆盖）。 */
fun defaultDesktopDirs(): AppDirs {
    val base = System.getenv("LLM_READER_HOME")?.takeIf { it.isNotBlank() }?.let { File(it) }
        ?: File(System.getProperty("user.home"), ".llm_reader")
    return AppDirs(dataDir = base, cacheDir = File(base, "cache"))
}

data class DesktopUiState(
    val book: BookContent.Text? = null,
    val currentChapter: Int = 0,
    val fontScale: Float = 1f,
    val loading: Boolean = false,
    val loadingText: String = "",
    val error: String? = null,
    /** 正在抓取正文的章节（网页小说懒加载）。 */
    val loadingChapters: Set<Int> = emptySet(),
    val translatingChapters: Set<Int> = emptySet(),
    val translationProgress: String? = null,
    val translationMode: TranslationMode = TranslationMode.ORIGINAL,
    val settings: LlmSettings = LlmSettings("", "", ""),
    val prefs: ReaderPreferences = ReaderPreferences(),
    val recents: List<RecentBook> = emptyList(),
    val webCanonicalUrl: String? = null,
    val webSourceId: String? = null,
) {
    val isWebBook: Boolean get() = webCanonicalUrl != null
    val chapterCount: Int get() = book?.chapters?.size ?: 0
    val current: com.zzy.llmreader.model.Chapter? get() = book?.chapters?.getOrNull(currentChapter)
}

/**
 * 桌面端的状态持有者。刻意保持精简：只覆盖"打开本地文件 / 打开网页小说 / 阅读 /
 * 翻译"这条主链路，抓取、翻译、持久化全部复用 shared 里的同一份实现。
 */
class DesktopAppState(
    private val core: LlmReaderCore,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(DesktopUiState())
    val state: StateFlow<DesktopUiState> = _state.asStateFlow()

    private val webRegistry = core.createWebRegistry { _state.value.prefs }
    private val translationJobs = mutableMapOf<Int, Job>()
    private val cacheMutex = Mutex()

    init {
        scope.launch {
            core.settingsRepo.settings.collect { s -> _state.update { it.copy(settings = s) } }
        }
        scope.launch {
            core.readerPrefsRepo.preferences.collect { p ->
                _state.update { it.copy(prefs = p, translationMode = p.translationMode) }
            }
        }
        scope.launch {
            core.recentRepo.recents.collect { r -> _state.update { it.copy(recents = r) } }
        }
    }

    // ---------------------------------------------------------------- 打开

    fun openLocalFile(file: File) {
        scope.launch {
            _state.update { it.copy(loading = true, loadingText = "解析中…", error = null) }
            val result = withContext(Dispatchers.IO) {
                runCatching { core.documentParser.parse(FileBookSource(file)) }
            }
            result.onSuccess { book ->
                when (book) {
                    is BookContent.Text -> {
                        val saved = withContext(Dispatchers.IO) { core.progressRepo.get(file.absolutePath) }
                        show(book, canonical = null, sourceId = null, startChapter = saved?.chapterIndex ?: 0)
                        remember(file.absolutePath, book.title, RecentBookSource.FILE)
                    }
                    is BookContent.Pdf -> _state.update {
                        it.copy(loading = false, error = "桌面端暂不支持 PDF 渲染（Android 端用系统 PdfRenderer）")
                    }
                }
            }.onFailure { e ->
                _state.update { it.copy(loading = false, error = e.message ?: "打开失败") }
            }
        }
    }

    fun openWebBook(input: String, forceRefresh: Boolean = false) {
        val url = input.trim()
        if (url.isBlank()) return
        val source = webRegistry.find(url)
        if (source == null) {
            _state.update {
                it.copy(error = "无法识别该链接。支持的站点：${webRegistry.sources.joinToString("、") { s -> s.displayName }}")
            }
            return
        }
        scope.launch {
            _state.update { it.copy(loading = true, loadingText = "抓取目录…", error = null) }
            runCatching {
                val canonical = source.canonicalize(url)
                val cached = if (forceRefresh) null else withContext(Dispatchers.IO) {
                    core.webCache.read(canonical)
                }
                val book = cached ?: source.fetchIndex(canonical) { current, total, label ->
                    _state.update {
                        it.copy(
                            loadingText = if (total > 0) "$label $current/$total" else label,
                        )
                    }
                }
                if (cached == null) withContext(Dispatchers.IO) { core.webCache.write(book) }
                val saved = withContext(Dispatchers.IO) { core.progressRepo.get(book.url) }
                book to (saved?.chapterIndex ?: 0)
            }.onSuccess { (book, startChapter) ->
                show(
                    book.toBookContent(),
                    canonical = book.url,
                    sourceId = book.source,
                    startChapter = startChapter,
                )
                remember(book.url, book.title, RecentBookSource.WEB)
                // 打开后先把当前章正文拉下来
                ensureChapterLoaded(_state.value.currentChapter)
            }.onFailure { e ->
                _state.update { it.copy(loading = false, error = e.message ?: "抓取失败") }
            }
        }
    }

    fun openRecent(book: RecentBook) {
        if (book.source == RecentBookSource.WEB || book.uri.startsWith("http", true)) {
            openWebBook(book.uri)
        } else {
            val file = File(book.uri)
            if (file.exists()) openLocalFile(file)
            else _state.update { it.copy(error = "文件已不存在：${book.uri}") }
        }
    }

    private suspend fun remember(key: String, title: String, source: RecentBookSource) {
        runCatching {
            core.recentRepo.add(
                RecentBook(uri = key, title = title, openedAt = System.currentTimeMillis(), source = source),
            )
        }
    }

    private fun show(
        book: BookContent.Text,
        canonical: String?,
        sourceId: String?,
        startChapter: Int,
    ) {
        cancelTranslationJobs()
        val idx = startChapter.coerceIn(0, (book.chapters.size - 1).coerceAtLeast(0))
        _state.update {
            it.copy(
                book = book,
                currentChapter = idx,
                loading = false,
                error = null,
                webCanonicalUrl = canonical,
                webSourceId = sourceId,
                loadingChapters = emptySet(),
                translatingChapters = emptySet(),
                translationProgress = null,
            )
        }
        saveProgress()
    }

    fun closeBook() {
        cancelTranslationJobs()
        _state.update {
            it.copy(
                book = null,
                currentChapter = 0,
                webCanonicalUrl = null,
                webSourceId = null,
                error = null,
                translationProgress = null,
                translatingChapters = emptySet(),
            )
        }
    }

    fun removeRecent(book: RecentBook) {
        scope.launch { runCatching { core.recentRepo.remove(book.uri) } }
    }

    // ---------------------------------------------------------------- 阅读

    fun selectChapter(index: Int) {
        val st = _state.value
        if (index !in 0 until st.chapterCount) return
        _state.update { it.copy(currentChapter = index) }
        saveProgress()
        ensureChapterLoaded(index)
        prefetchAround(index)
    }

    fun nextChapter() = selectChapter(_state.value.currentChapter + 1)
    fun prevChapter() = selectChapter(_state.value.currentChapter - 1)

    fun setFontScale(scale: Float) = _state.update { it.copy(fontScale = scale.coerceIn(0.6f, 2.2f)) }

    fun cycleTranslationMode() {
        val next = when (_state.value.translationMode) {
            TranslationMode.ORIGINAL -> TranslationMode.TRANSLATED
            TranslationMode.TRANSLATED -> TranslationMode.BILINGUAL
            TranslationMode.BILINGUAL -> TranslationMode.ORIGINAL
        }
        // 同时写回偏好，否则下一次 prefs 流推送会把模式覆盖回去
        savePrefs(_state.value.prefs.copy(translationMode = next))
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    private fun saveProgress() {
        val st = _state.value
        val book = st.book ?: return
        val key = st.webCanonicalUrl ?: keyForLocal(book.title)
        scope.launch {
            runCatching { core.progressRepo.save(key, st.currentChapter, 0f) }
        }
    }

    /** 本地书的进度 key 用最近记录里的 uri（打开时写入的绝对路径）。 */
    private fun keyForLocal(title: String): String =
        _state.value.recents.firstOrNull { it.title == title && it.source == RecentBookSource.FILE }?.uri
            ?: title

    /** 网页小说：按需抓取单章正文。 */
    fun ensureChapterLoaded(index: Int) {
        val st = _state.value
        val canonical = st.webCanonicalUrl ?: return
        val sourceId = st.webSourceId ?: return
        val book = st.book ?: return
        if (index !in book.chapters.indices) return
        if (book.chapters[index].content != null) return
        if (index in st.loadingChapters) return
        val source = webRegistry.findById(sourceId) ?: return

        _state.update { it.copy(loadingChapters = it.loadingChapters + index) }
        scope.launch {
            runCatching {
                val placeholder = withContext(Dispatchers.IO) { core.webCache.read(canonical) }
                    ?: error("缓存丢失，请重新打开该书")
                val chapter = placeholder.chapters.getOrNull(index)
                    ?: error("章节不存在")
                val fetched = source.fetchChapter(canonical, index, chapter)
                cacheMutex.withLock {
                    val cached = withContext(Dispatchers.IO) { core.webCache.read(canonical) }
                    if (cached != null) {
                        val updated = cached.copy(
                            chapters = cached.chapters.toMutableList().also { list ->
                                if (index in list.indices) list[index] = fetched
                            },
                        )
                        withContext(Dispatchers.IO) { core.webCache.write(updated) }
                        val newBook = updated.toBookContent()
                        _state.update { s -> s.copy(book = newBook) }
                    }
                }
            }.onFailure { e ->
                _state.update { it.copy(error = "第 ${index + 1} 章抓取失败：${e.message}") }
            }
            _state.update { it.copy(loadingChapters = it.loadingChapters - index) }
        }
    }

    /** 后台预取后续章节。 */
    private fun prefetchAround(currentIndex: Int) {
        val ahead = _state.value.prefs.webPrefetchAhead
        if (ahead <= 0 || _state.value.webCanonicalUrl == null) return
        // 只预取紧接着的 1 章，避免对站点压力过大；后续章节在翻页时再取。
        ensureChapterLoaded(currentIndex + 1)
    }

    // ---------------------------------------------------------------- 翻译

    fun translateChapter(index: Int) {
        val st = _state.value
        val book = st.book ?: return
        if (index !in book.chapters.indices) return
        if (index in st.translatingChapters) return
        if (st.settings.apiKey.isBlank()) {
            _state.update { it.copy(error = "尚未配置 API Key，请在设置里填写") }
            return
        }
        val chapter = book.chapters[index]
        val html = chapter.content
        if (html.isNullOrBlank()) {
            _state.update { it.copy(error = "本章正文尚未加载") }
            ensureChapterLoaded(index)
            return
        }

        _state.update {
            it.copy(
                translatingChapters = it.translatingChapters + index,
                translationProgress = "翻译中…",
                error = null,
            )
        }
        val targetLang = st.prefs.translationLanguage
        val canonical = st.webCanonicalUrl

        translationJobs[index] = scope.launch {
            val glossaryProvider: suspend () -> String = {
                val entries = if (canonical != null) {
                    withContext(Dispatchers.IO) { core.webCache.read(canonical) }?.glossary ?: emptyList()
                } else {
                    val key = _state.value.book?.title.orEmpty()
                    if (key.isBlank()) emptyList()
                    else withContext(Dispatchers.IO) { core.localGlossary.read(key) }
                }
                entries.joinToString("\n") { "${it.original} → ${it.translated}" }
            }

            val result = runCatching {
                core.translator.translate(
                    settings = _state.value.settings,
                    targetLanguage = targetLang,
                    chapterTitle = chapter.title,
                    chapterHtml = html,
                    styleHint = st.prefs.translationStyle,
                    glossaryProvider = glossaryProvider,
                    batchChars = st.prefs.translationBatchChars,
                    onProgress = { current, total ->
                        _state.update { it.copy(translationProgress = "翻译中 $current/$total") }
                    },
                    onPartial = { partialTitle, partialHtml ->
                        applyTranslation(index, partialTitle, partialHtml)
                    },
                )
            }.getOrElse { e ->
                ChapterTranslator.TranslateResult.Failure(e.message ?: "翻译失败")
            }

            when (result) {
                is ChapterTranslator.TranslateResult.Success -> {
                    applyTranslation(index, result.translatedTitle, result.html)
                    persistTranslation(canonical, index, result.translatedTitle, result.html, targetLang)
                }
                is ChapterTranslator.TranslateResult.Failure -> {
                    _state.update { it.copy(error = "第 ${index + 1} 章${result.message}") }
                }
            }
            _state.update {
                it.copy(
                    translatingChapters = it.translatingChapters - index,
                    translationProgress = null,
                )
            }
        }
    }

    private fun applyTranslation(index: Int, title: String?, html: String) {
        _state.update { st ->
            val book = st.book ?: return@update st
            if (index !in book.chapters.indices) return@update st
            val chapters = book.chapters.toMutableList()
            val cur = chapters[index]
            chapters[index] = cur.copy(
                translatedTitle = title?.takeIf { it.isNotBlank() } ?: cur.translatedTitle,
                translatedContent = html.takeIf { it.isNotBlank() } ?: cur.translatedContent,
            )
            st.copy(book = book.copy(chapters = chapters))
        }
    }

    private suspend fun persistTranslation(
        canonical: String?,
        index: Int,
        title: String,
        html: String,
        lang: String,
    ) {
        if (canonical == null) return
        runCatching {
            cacheMutex.withLock {
                val cached = withContext(Dispatchers.IO) { core.webCache.read(canonical) } ?: return@withLock
                val updated = cached.copy(
                    chapters = cached.chapters.toMutableList().also { list ->
                        val ch = list.getOrNull(index) ?: return@also
                        list[index] = ch.copy(
                            translatedHtml = html,
                            translatedTitle = title,
                            translationLanguage = lang,
                        )
                    },
                )
                withContext(Dispatchers.IO) { core.webCache.write(updated) }
            }
        }
    }

    private fun cancelTranslationJobs() {
        translationJobs.values.forEach { it.cancel() }
        translationJobs.clear()
    }

    // ---------------------------------------------------------------- 设置

    fun saveSettings(settings: LlmSettings) {
        scope.launch { runCatching { core.settingsRepo.update(settings) } }
    }

    fun savePrefs(prefs: ReaderPreferences) {
        scope.launch {
            runCatching { core.readerPrefsRepo.update(prefs) }
            _state.update { it.copy(translationMode = prefs.translationMode) }
        }
    }

    fun fetchModels(settings: LlmSettings, onResult: (Result<List<String>>) -> Unit) {
        scope.launch { onResult(core.llmClient.listModels(settings)) }
    }
}
