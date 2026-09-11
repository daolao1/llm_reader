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

    /** 当前这本书的进度 key：本地书 = 文件绝对路径，网页书 = canonical url。 */
    private var currentBookKey: String? = null

    /** 流式部分译文的节流时间戳（见 [partialFlushDue]）。 */
    private var lastPartialFlushAt = 0L

    /**
     * 部分译文是否该推送。250ms 与 Android 侧 `PartialBuffer` 保持一致 ——
     * 每次推送都会让阅读区整章重新布局，太频繁只会在低端机上掉帧。
     */
    private fun partialFlushDue(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastPartialFlushAt < 250) return false
        lastPartialFlushAt = now
        return true
    }

    /**
     * 章节抓取任务。必须持有引用，否则 `closeBook` 取消不掉，
     * 抓取结果会在用户已经离开这本书之后把书塞回界面。
     */
    private val chapterFetchJobs = mutableMapOf<Int, Job>()
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
                        show(
                            book,
                            canonical = null,
                            sourceId = null,
                            startChapter = saved?.chapterIndex ?: 0,
                            localKey = file.absolutePath,
                        )
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
                // 打开后先把当前章正文拉下来，并按设置预取后续章节
                ensureChapterLoaded(_state.value.currentChapter)
                prefetchAround(_state.value.currentChapter)
            }.onFailure { e ->
                _state.update {
                    it.copy(loading = false, error = friendlyFetchError(e, source.displayName))
                }
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
        /** 本地书的进度 key（绝对路径）。网页书传 null，用 canonical。 */
        localKey: String? = null,
    ) {
        cancelTranslationJobs()
        currentBookKey = canonical ?: localKey
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
        chapterFetchJobs.values.forEach { it.cancel() }
        chapterFetchJobs.clear()
        currentBookKey = null
        _state.update {
            it.copy(
                book = null,
                currentChapter = 0,
                webCanonicalUrl = null,
                webSourceId = null,
                error = null,
                translationProgress = null,
                translatingChapters = emptySet(),
                loadingChapters = emptySet(),
            )
        }
    }

    fun removeRecent(book: RecentBook) {
        scope.launch {
            // Android 端会连缓存一起删，桌面端旧实现只删列表项 ——
            // 于是「删除」看起来生效了，web_books/<sha>.json 却留在磁盘上无限增长。
            if (book.source == RecentBookSource.WEB || book.uri.startsWith("http", ignoreCase = true)) {
                withContext(Dispatchers.IO) {
                    runCatching {
                        val canonical = webRegistry.find(book.uri)?.canonicalize(book.uri) ?: book.uri
                        core.webCache.delete(canonical)
                    }
                }
            }
            runCatching { core.recentRepo.remove(book.uri) }
        }
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

        // 切到译文/双语时自动开翻。
        // 旧实现只改了一个枚举值：用户点「译文」后页面纹丝不动，
        // 而「译文本章」按钮在工具栏另一头，看起来就像功能坏了。
        // Android 端一直是有这个联动的。
        if (next != TranslationMode.ORIGINAL) {
            val st = _state.value
            val index = st.currentChapter
            val already = st.current?.translatedContent != null
            if (!already && index !in st.translatingChapters) {
                translateChapter(index)
            }
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    private fun saveProgress() {
        val st = _state.value
        if (st.book == null) return
        // 用打开时记下的稳定 key。
        // 旧实现是 `keyForLocal(book.title)` —— 反查 recents 拿 uri，但
        // recents 是异步填充的，`show()` 里第一次 save 时它还没有这本书，
        // 于是 key 落到书名上；而读取用的是 `file.absolutePath`。
        // 对 TXT/MD/HTML 这种只有 1 章、`selectChapter` 永不触发的书来说，
        // 这是**唯一**一次保存 —— 结果是本机 txt 的进度从来没存对过，
        // 每次重开都回到开头。
        val key = currentBookKey ?: return
        scope.launch {
            runCatching { core.progressRepo.save(key, st.currentChapter, 0f) }
        }
    }

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
        chapterFetchJobs[index] = scope.launch {
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
                        // ⚠️ 身份校验：抓取期间用户可能已经返回书库、或换了一本书。
                        // 旧实现无条件 `copy(book = newBook)` —— 抓取落地时会把书
                        // 重新塞回界面，而 webCanonicalUrl / webSourceId 已被 closeBook
                        // 清空，于是 ensureChapterLoaded 直接 return、所有章节永久卡在
                        // 「本章暂无正文」，进度与翻译还会把网页书当本地书处理。
                        _state.update { s ->
                            if (s.webCanonicalUrl != canonical) s
                            else s.copy(book = newBook, loadingChapters = s.loadingChapters - index)
                        }
                        return@launch
                    }
                }
            }.onFailure { e ->
                _state.update { s ->
                    if (s.webCanonicalUrl != canonical) s
                    else s.copy(
                        error = "第 ${index + 1} 章抓取失败：${e.message}",
                        loadingChapters = s.loadingChapters - index,
                    )
                }
                return@launch
            }
        }
    }

    /** 后台预取后续章节。 */
    private fun prefetchAround(currentIndex: Int) {
        val ahead = _state.value.prefs.webPrefetchAhead
        if (ahead <= 0 || _state.value.webCanonicalUrl == null) return
        val last = _state.value.chapterCount - 1
        // 旧实现读了 webPrefetchAhead 却只当开关用，永远只抓 currentIndex + 1，
        // 设置成 20 和设置成 1 没有任何区别。
        for (offset in 1..ahead) {
            val target = currentIndex + offset
            if (target > last) break
            ensureChapterLoaded(target)
        }
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
                    // 必须与 mergeGlossary 的写入 key 一致（currentBookKey = 文件绝对路径）。
                    // 旧实现读写都用书名，除了同名书会串，也会因为『先读后写』顺序问题丢数据。
                    val key = currentBookKey
                    if (key.isNullOrBlank()) emptyList()
                    else withContext(Dispatchers.IO) { core.localGlossary.read(key) }
                }
                entries.joinToString("\n") { "${it.original} → ${it.translated}" }
            }

            // 用 translateStreaming 而不是 translate：只有它接受 concurrency，
            // 并且把最终结果从 onFinish 交还（外层不再需要 runCatching 包一层）。
            core.translator.translateStreaming(
                settings = _state.value.settings,
                targetLanguage = targetLang,
                chapterTitle = chapter.title,
                chapterHtml = html,
                styleHint = st.prefs.translationStyle,
                glossaryProvider = glossaryProvider,
                batchChars = st.prefs.translationBatchChars,
                concurrency = st.prefs.translationConcurrency,
                onProgress = { done, total ->
                    _state.update { it.copy(translationProgress = "翻译中 $done/$total") }
                },
                onPartial = { partialTitle, partialHtml ->
                    // 桌面端过去完全没有节流：每个 delta 都走一次 _state.update，
                    // 而 ReaderScreen 会据此重新把整章转成 AnnotatedString 并重新布局。
                    // Android 侧有 PartialBuffer，这里补上同样的 250ms 节流。
                    // 批末那次完整译文由 ChapterTranslator 强制推送，不会被这里吞掉。
                    if (partialFlushDue()) {
                        applyTranslation(index, partialTitle, partialHtml)
                    }
                },
                // 每批翻完抽取人名/专有名词并落库。
                // 桌面端旧实现只**读**术语表（glossaryProvider），从不写入，
                // 也没有传 onBatchTranslated（其默认实现是空操作）——
                // 于是 glossaryProvider 永远返回空串，跨章译名一致性在桌面端
                // 根本不存在，glossaryDedupeInterval / glossaryMaxSize 是死设置。
                onBatchTranslated = { batchOriginal, batchTranslated ->
                    runCatching {
                        val extracted = core.translator.extractGlossary(
                            _state.value.settings,
                            targetLang,
                            batchOriginal.joinToString("\n") { "<p>$it</p>" },
                            batchTranslated.joinToString("\n") { "<p>$it</p>" },
                        )
                        if (extracted.isNotBlank()) {
                            mergeGlossary(canonical, parseGlossaryLines(extracted))
                        }
                    }
                },
                onFinish = { result ->
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
                },
            )
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

    /**
     * 把新抽到的术语合并进当前书的术语表（网页书进缓存，本地书进本地存储）。
     * 按 `original` 去重、刷新 `lastUsed`、按 `glossaryMaxSize` 做 LRU 淘汰。
     */
    private suspend fun mergeGlossary(
        canonical: String?,
        entries: List<com.zzy.llmreader.web.GlossaryEntry>,
    ) {
        if (entries.isEmpty()) return
        val maxSize = _state.value.prefs.glossaryMaxSize.coerceAtLeast(1)
        val now = System.currentTimeMillis()

        if (canonical != null) {
            cacheMutex.withLock {
                val cached = withContext(Dispatchers.IO) { core.webCache.read(canonical) } ?: return@withLock
                val byKey = cached.glossary.associateBy { it.original }.toMutableMap()
                entries.forEach { e -> byKey[e.original] = e.copy(lastUsed = now) }
                val trimmed = byKey.values.sortedByDescending { it.lastUsed }.take(maxSize)
                withContext(Dispatchers.IO) {
                    core.webCache.write(cached.copy(glossary = trimmed))
                }
            }
            return
        }

        // 本地书：用文件名（含目录）做 key，避免同书名的两本书共用术语表
        val key = currentBookKey ?: return
        val existing = withContext(Dispatchers.IO) { core.localGlossary.read(key) }
        val byKey = existing.associateBy { it.original }.toMutableMap()
        entries.forEach { e -> byKey[e.original] = e.copy(lastUsed = now) }
        val trimmed = byKey.values.sortedByDescending { it.lastUsed }.take(maxSize)
        withContext(Dispatchers.IO) { core.localGlossary.write(key, trimmed) }
    }

    /** 解析 LLM 输出的术语行，兼容 `A → B`、`A -> B`、`- A → B` 等写法。 */
    private fun parseGlossaryLines(text: String): List<com.zzy.llmreader.web.GlossaryEntry> =
        text.lineSequence().mapNotNull { raw ->
            val line = raw.trim().removePrefix("-").removePrefix("*").trim()
            if (line.isBlank()) return@mapNotNull null
            val arrow = line.indexOf('→').takeIf { it >= 0 }
                ?: line.indexOf("->").takeIf { it >= 0 }
                ?: return@mapNotNull null
            val width = if (line[arrow] == '→') 1 else 2
            val orig = line.substring(0, arrow).trim().trim('[', ']', '【', '】', '"')
            val trans = line.substring(arrow + width).trim().trim('[', ']', '【', '】', '"')
            if (orig.isBlank() || trans.isBlank() || orig.length > 80 || trans.length > 80) null
            else com.zzy.llmreader.web.GlossaryEntry(original = orig, translated = trans)
        }.toList()

    /**
     * 把抓取异常翻译成用户能照做的提示。
     *
     * 桌面端**没有内嵌浏览器**，所以不能像 Android 那样直接弹 WebView 过验证 ——
     * 旧实现把原始异常文本（「Cloudflare 拦截（HTTP 403）：<url>」）直接甩给用户，
     * 他既不知道该怎么办，也不知道设置里其实可以粘 cookie。
     */
    private fun friendlyFetchError(e: Throwable, siteName: String): String =
        when (e) {
            is com.zzy.llmreader.web.CloudflareBlockedException ->
                "$siteName 被 Cloudflare 拦截了（HTTP ${e.httpStatus}）。\n" +
                    "桌面端没有内嵌浏览器，请在浏览器里打开该站点完成验证，" +
                    "然后到「设置 → 站点登录」把 cf_clearance 粘贴进来。"
            else -> e.message ?: "抓取失败"
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
