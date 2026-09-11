package com.zzy.llmreader.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zzy.llmreader.LlmReaderApp
import com.zzy.llmreader.data.LlmSettings
import com.zzy.llmreader.data.SettingsRepository
import com.zzy.llmreader.data.ReaderPreferences
import com.zzy.llmreader.data.RecentBook
import com.zzy.llmreader.data.RecentBookSource
import com.zzy.llmreader.data.ReadingTheme
import com.zzy.llmreader.data.TranslationMode
import com.zzy.llmreader.format.AndroidBookSource
import com.zzy.llmreader.ui.reader.BookJob
import com.zzy.llmreader.ui.reader.runDiscovery
import com.zzy.llmreader.ui.reader.refreshBookFromCache
import com.zzy.llmreader.ui.reader.ensureChapterTranslated
import com.zzy.llmreader.ui.reader.refreshChapterImages
import com.zzy.llmreader.ui.reader.applyTagsForItem
import com.zzy.llmreader.ui.reader.translateBookInfosOnce
import com.zzy.llmreader.ui.reader.translateDiscoveryBatch
import com.zzy.llmreader.ui.reader.translateTagsOnce
import com.zzy.llmreader.llm.ChapterTranslator
import com.zzy.llmreader.llm.ChatMessage
import com.zzy.llmreader.model.BookContent
import com.zzy.llmreader.web.CloudflareBlockedException
import com.zzy.llmreader.web.WebSource
import com.zzy.llmreader.web.toBookContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext


class ReaderViewModel(app: Application) : AndroidViewModel(app) {

    /** 所有仓储 / 客户端都由 shared 的组合根提供（与桌面端是同一份实现）。 */
    private val core = (app as LlmReaderApp).core

    internal val settingsRepo = core.settingsRepo
    private val readerPrefsRepo = core.readerPrefsRepo
    private val recentRepo = core.recentRepo
    private val progressRepo = core.progressRepo

    /** 当前已打开书的进度键（web 用 canonical url；本地用文件 uri）。 */
    internal var currentBookKey: String? = null

    /** 每章翻译协程的 Job，切书/关书时用于取消旧书的翻译，避免结果串到新书上。 */
    internal val translationJobs = mutableMapOf<Int, kotlinx.coroutines.Job>()

    private fun cancelAllTranslationJobs() {
        translationJobs.values.forEach { it.cancel() }
        translationJobs.clear()
    }
    internal val client = core.llmClient
    internal val translator = core.translator
    private val parser = core.documentParser
    internal val webCache = core.webCache

    /** Tag 翻译缓存：key = "lang|tag-原文" → 译文。启动时从 DataStore 加载。 */
    internal val tagTranslationCache: LinkedHashMap<String, String> = LinkedHashMap()

    /** 书籍信息（标题+简介）翻译缓存：key = "lang|title|||summary" → (译标题, 译简介)。 */
    internal val bookInfoTranslationCache: LinkedHashMap<String, Pair<String, String>> = LinkedHashMap()

    internal val discoveryTxRepo = core.discoveryTxRepo
    internal val localGlossary = core.localGlossary
    internal val webRegistry = core.createWebRegistry { _state.value.readerPrefs }
    internal val imageGenRepo = core.imageGenRepo
    internal val characterRepo = core.characterRepo
    internal val imageCache = core.imageCache
    internal val novelAi = core.novelAi
    internal val comfyUi = core.comfyUi
    /** 序列化"读缓存→改→写缓存"，避免懒加载和预取互相覆盖。 */
    internal val cacheMutex = Mutex()

    /** CF 拦截后保留的待重试上下文：拿到 cookie 后自动重做这步动作。 */
    private var pendingWebBookOpen: String? = null
    private var pendingChapterReload: Int? = null
    internal var pendingDiscoveryReload: Boolean = false
    /** 详情页选中的章节：openBook/openWebBook 完成后跳到该章。 */
    private var pendingInitialChapter: Int? = null

    internal val _state = MutableStateFlow(ReaderUiState())
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    val recents: StateFlow<List<RecentBook>> = recentRepo.recents.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    /** \u542f\u52a8\u65f6\u4e00\u6b21\u6027\u52a0\u8f7d\u53d1\u73b0\u9875\u7ffb\u8bd1\u7f13\u5b58\u7684 Job\uff1b\u7ffb\u8bd1\u524d\u5148 join \u7b49\u5b83\u5c31\u7eea\u3002 */
    internal var discoveryCacheLoad: kotlinx.coroutines.Job? = null

    init {
        viewModelScope.launch {
            settingsRepo.settings.collect { s -> _state.update { it.copy(settings = s) } }
        }
        viewModelScope.launch {
            readerPrefsRepo.preferences.collect { p ->
                _state.update {
                    it.copy(
                        readerPrefs = p,
                        // 第一次拿到 prefs 时同步发现页翻译开关；之后用户手动切换不会被覆盖
                        // 因为 setDiscoveryTranslate 会写回到 prefs。
                        discovery = it.discovery.copy(translateListings = p.discoveryTranslateOn),
                    )
                }
            }
        }
        viewModelScope.launch {
            imageGenRepo.settings.collect { s -> _state.update { it.copy(imageGenSettings = s) } }
        }
        viewModelScope.launch {
            characterRepo.library.collect { lib -> _state.update { it.copy(characterLibrary = lib) } }
        }
        discoveryCacheLoad = viewModelScope.launch {
            tagTranslationCache.putAll(discoveryTxRepo.loadTags())
            bookInfoTranslationCache.putAll(discoveryTxRepo.loadBooks())
        }
    }

    fun openBook(uri: Uri, takePersistableFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, loadingText = "解析中…", error = null) }
            runCatching {
                getApplication<Application>().contentResolver
                    .takePersistableUriPermission(uri, takePersistableFlags)
            }
            val parsed = withContext(Dispatchers.IO) {
                runCatching { parser.parse(AndroidBookSource(getApplication(), uri)) }
            }
            parsed.onSuccess { book ->
                val saved = progressRepo.get(uri.toString())
                val totalChapters = ((book as? BookContent.Text)?.chapters?.size ?: 1)
                val override = pendingInitialChapter
                pendingInitialChapter = null
                val startIdx = override?.coerceIn(0, (totalChapters - 1).coerceAtLeast(0))
                    ?: saved?.chapterIndex
                        ?.coerceIn(0, (totalChapters - 1).coerceAtLeast(0))
                    ?: 0
                val initialScrollFraction = if (override == null && saved != null && saved.chapterIndex == startIdx) {
                    saved.chapterScrollFraction.coerceIn(0f, 1f)
                } else {
                    0f
                }
                cancelAllTranslationJobs()
                currentBookKey = uri.toString()
                lastSavedScrollFraction = initialScrollFraction
                _state.update {
                    it.copy(
                        book = book,
                        currentChapter = startIdx,
                        loading = false,
                        error = null,
                        initialChapterScrollFraction = initialScrollFraction,
                    )
                }
                recentRepo.add(
                    RecentBook(
                        uri = uri.toString(),
                        title = book.title,
                        openedAt = System.currentTimeMillis(),
                        source = RecentBookSource.FILE,
                    )
                )
                // 如果当前已在翻译模式，自动触发翻译
                if (_state.value.readerPrefs.translationMode != TranslationMode.ORIGINAL) {
                    ensureChapterTranslated(startIdx)
                }
            }.onFailure { err ->
                Log.e(TAG, "parse failed", err)
                _state.update { it.copy(loading = false, error = err.message ?: "解析失败") }
            }
        }
    }

    fun openWebBook(inputUrl: String, forceRefresh: Boolean = false) {
        viewModelScope.launch {
            val source = webRegistry.find(inputUrl)
            if (source == null) {
                _state.update { it.copy(error = "暂不支持该站点") }
                return@launch
            }
            val canonicalUrl = runCatching { source.canonicalize(inputUrl) }
                .getOrElse { err ->
                    _state.update { it.copy(error = err.message ?: "URL 无效") }
                    return@launch
                }
            _state.update {
                it.copy(
                    loading = true,
                    loadingText = if (forceRefresh) "刷新目录中…" else "打开 ${source.displayName} 中…",
                    error = null,
                )
            }

            val loaded = try {
                val cached = if (forceRefresh) null
                else withContext(Dispatchers.IO) { webCache.read(canonicalUrl) }
                val book = cached ?: source.fetchIndex(canonicalUrl) { current, total, label ->
                    val text = when {
                        total > 0 -> "$label · $current / $total"
                        else -> label
                    }
                    _state.update { it.copy(loadingText = text) }
                }.also { fetched ->
                    withContext(Dispatchers.IO) { webCache.write(fetched) }
                }
                Result.success(book)
            } catch (err: Throwable) {
                Result.failure(err)
            }

            loaded.onSuccess { book ->
                val saved = progressRepo.get(book.url)
                val override = pendingInitialChapter
                pendingInitialChapter = null
                val startIdx = override?.coerceIn(0, (book.chapters.size - 1).coerceAtLeast(0))
                    ?: saved?.chapterIndex
                        ?.coerceIn(0, (book.chapters.size - 1).coerceAtLeast(0))
                    ?: 0
                val initialScrollFraction = if (override == null && saved != null && saved.chapterIndex == startIdx) {
                    saved.chapterScrollFraction.coerceIn(0f, 1f)
                } else {
                    0f
                }
                cancelAllTranslationJobs()
                currentBookKey = book.url
                lastSavedScrollFraction = initialScrollFraction
                _state.update {
                    it.copy(
                        book = book.toBookContent(),
                        currentChapter = startIdx,
                        loading = false,
                        error = null,
                        loadingChapters = emptySet(),
                        webCanonicalUrl = book.url,
                        webSourceId = book.source,
                        initialChapterScrollFraction = initialScrollFraction,
                    )
                }
                recentRepo.add(
                    RecentBook(
                        uri = book.url,
                        title = book.title,
                        openedAt = System.currentTimeMillis(),
                        source = RecentBookSource.WEB,
                    )
                )
                // 首次打开默认预拉记忆中的章节（或第 0 章），让用户立刻能看
                ensureChapterLoaded(startIdx)
                prefetchAround(startIdx)
                // 如果当前已在翻译模式，自动触发翻译
                if (_state.value.readerPrefs.translationMode != TranslationMode.ORIGINAL) {
                    ensureChapterTranslated(startIdx)
                }
                // 后台批量翻译目录章节标题（命中缓存的不会再请求）。
                ensureTocTranslated(book.url)
                // 旧缓存可能缺少新加入字段（如 kakuyomu 的 groupTitle）。
                // 只要任何章没有 groupTitle，就在后台拉一次最新目录把元数据合并进来，
                // 已下载的 html / translatedHtml / translatedTitle 全部保留。
                if (!forceRefresh && book.chapters.any { it.groupTitle.isNullOrBlank() }) {
                    refreshTocMetadataInBackground(book.url, source)
                }
            }.onFailure { err ->
                Log.e(TAG, "web book failed", err)
                if (err is CloudflareBlockedException) {
                    _state.update {
                        it.copy(
                            loading = false,
                            error = "需要重新通过 Cloudflare 验证。",
                            pendingCloudflareUrl = err.targetUrl,
                        )
                    }
                    pendingWebBookOpen = inputUrl
                } else {
                    _state.update { it.copy(loading = false, error = err.message ?: "网页小说打开失败") }
                }
            }
        }
    }

    /**
     * 按需抓取并填充指定章节正文。已经加载或正在加载时直接返回。
     */
    fun ensureChapterLoaded(index: Int) {
        val cur = _state.value
        val canonical = cur.webCanonicalUrl ?: return
        val sourceId = cur.webSourceId ?: return
        val book = cur.book as? BookContent.Text ?: return
        if (index !in book.chapters.indices) return
        if (book.chapters[index].content != null) return
        if (index in cur.loadingChapters) return

        val source = webRegistry.findById(sourceId) ?: return
        _state.update { it.copy(loadingChapters = it.loadingChapters + index) }

        viewModelScope.launch {
            val result = runCatching {
                // 先在锁外抓正文（耗时），再在锁内做读-改-写，避免长时间持锁阻塞预取。
                val placeholder = withContext(Dispatchers.IO) { webCache.read(canonical) }
                    ?.chapters?.getOrNull(index)
                    ?: error("缓存丢失，请刷新目录")
                if (placeholder.html != null) {
                    return@runCatching withContext(Dispatchers.IO) { webCache.read(canonical) }!!
                }
                val fetched = source.fetchChapter(canonical, index, placeholder)
                cacheMutex.withLock {
                    val cached = withContext(Dispatchers.IO) { webCache.read(canonical) }
                        ?: error("缓存丢失，请刷新目录")
                    val updated = cached.copy(
                        chapters = cached.chapters.toMutableList().apply {
                            // 防御：如果同时另一个写者也填了这一章，保留 fetched
                            this[index] = fetched
                        },
                    )
                    withContext(Dispatchers.IO) { webCache.write(updated) }
                    updated
                }
            }

            result.onSuccess { updated ->
                _state.update { state ->
                    val newBook = updated.toBookContent()
                    state.copy(
                        book = newBook,
                        loadingChapters = state.loadingChapters - index,
                    )
                }
            }.onFailure { err ->
                Log.e(TAG, "chapter $index fetch failed", err)
                if (err is CloudflareBlockedException) {
                    _state.update {
                        it.copy(
                            loadingChapters = it.loadingChapters - index,
                            error = "需要重新通过 Cloudflare 验证。",
                            pendingCloudflareUrl = err.targetUrl,
                        )
                    }
                    pendingChapterReload = index
                } else {
                    _state.update {
                        it.copy(
                            loadingChapters = it.loadingChapters - index,
                            error = err.message ?: "章节加载失败",
                        )
                    }
                }
            }
        }
    }

    /**
     * 重新下载**当前这一章**的正文。
     *
     * 旧实现走的是 `openWebBook(forceRefresh = true)` —— 那会跳过缓存重新抓整份目录，
     * 而新目录里每一章的 `html` / `translatedHtml` 都是 null，落盘后
     * 「已下载的全部章节 + 全部 LLM 译文 + 术语表」一起消失。菜单上写「本章」，
     * 行为却是整本，属于无声的数据丢失。
     *
     * 这里改成只抓单章 + 锁内读-改-写合并，其余章节原样保留。
     * 译文刻意**不**清空：重抓的是原文，译文是花钱换来的；想更新译文用户会自己点
     * 「重新翻译本章」。
     */
    fun reDownloadChapter(index: Int) {
        val cur = _state.value
        val canonical = cur.webCanonicalUrl ?: return
        val sourceId = cur.webSourceId ?: return
        val book = cur.book as? BookContent.Text ?: return
        if (index !in book.chapters.indices) return
        if (index in cur.loadingChapters) return
        val source = webRegistry.findById(sourceId) ?: return

        _state.update { it.copy(loadingChapters = it.loadingChapters + index) }
        viewModelScope.launch {
            val result = runCatching {
                val placeholder = withContext(Dispatchers.IO) { webCache.read(canonical) }
                    ?.chapters?.getOrNull(index)
                    ?: error("缓存丢失，请刷新目录")
                val fetched = source.fetchChapter(canonical, index, placeholder)
                cacheMutex.withLock {
                    val cached = withContext(Dispatchers.IO) { webCache.read(canonical) }
                        ?: error("缓存丢失，请刷新目录")
                    val updated = cached.copy(
                        chapters = cached.chapters.toMutableList().apply {
                            val old = this[index]
                            // 只替换正文与章节信息，译文/译名/语言标记全部保留
                            this[index] = fetched.copy(
                                translatedHtml = old.translatedHtml,
                                translatedTitle = old.translatedTitle,
                                translationLanguage = old.translationLanguage,
                            )
                        },
                    )
                    withContext(Dispatchers.IO) { webCache.write(updated) }
                    updated
                }
            }
            result.onSuccess { updated ->
                _state.update { state ->
                    // 身份校验：期间切了书就不要把结果写回去
                    if (state.webCanonicalUrl != canonical) state
                    else state.copy(book = updated.toBookContent())
                }
            }.onFailure { e ->
                _state.update { it.copy(error = "重新下载失败：${e.message}") }
            }
            _state.update { it.copy(loadingChapters = it.loadingChapters - index) }
        }
    }

    /**
     * 重新抓目录（连载更新后用）。
     *
     * 与 [reDownloadChapter] 同样是安全合并：已有章节按 `chapterUrl` 对齐后只更新
     * 标题/分组，**已下载的正文、LLM 译文和术语表全部保留**；站点新增的章节会追加，
     * 站点删掉的章节会移除。
     */
    fun refreshToc() {
        val cur = _state.value
        val canonical = cur.webCanonicalUrl ?: return
        val sourceId = cur.webSourceId ?: return
        if (cur.tocRefreshing) return
        val source = webRegistry.findById(sourceId) ?: return

        _state.update { it.copy(tocRefreshing = true) }
        viewModelScope.launch {
            val result = runCatching {
                val fresh = source.fetchIndex(canonical) { _, _, _ -> }
                cacheMutex.withLock {
                    val cached = withContext(Dispatchers.IO) { webCache.read(canonical) }
                        ?: error("缓存丢失")
                    val oldByUrl = cached.chapters
                        .mapNotNull { c -> c.chapterUrl?.let { it to c } }
                        .toMap()
                    val merged = fresh.chapters.mapIndexed { idx, f ->
                        // 优先按 URL 对齐；没有 chapterUrl 的退化实现按下标对齐
                        val old = f.chapterUrl?.let { oldByUrl[it] }
                            ?: cached.chapters.getOrNull(idx)?.takeIf { it.chapterUrl == null }
                        if (old == null) {
                            f
                        } else {
                            old.copy(
                                title = f.title.ifBlank { old.title },
                                groupTitle = f.groupTitle ?: old.groupTitle,
                            )
                        }
                    }
                    val updated = cached.copy(
                        title = fresh.title.ifBlank { cached.title },
                        chapters = merged,
                    )
                    withContext(Dispatchers.IO) { webCache.write(updated) }
                    updated
                }
            }
            result.onSuccess { updated ->
                _state.update { state ->
                    if (state.webCanonicalUrl != canonical) {
                        state
                    } else {
                        state.copy(
                            book = updated.toBookContent(),
                            // 目录可能增删，把当前章夹回合法范围
                            currentChapter = state.currentChapter
                                .coerceIn(0, (updated.chapters.size - 1).coerceAtLeast(0)),
                            error = null,
                        )
                    }
                }
            }.onFailure { e ->
                _state.update { it.copy(error = "刷新目录失败：${e.message}") }
            }
            _state.update { it.copy(tocRefreshing = false) }
        }
    }

    /**
     * 后台静默拉一次最新目录，按 chapterUrl 把元数据（groupTitle / title）合并到现有缓存里，
     * 已下载的 html / translatedHtml / translatedTitle / translationLanguage 一律保留。
     * 用来给旧缓存补上后期新增的字段（例如 kakuyomu 的 groupTitle）。
     */
    private fun refreshTocMetadataInBackground(canonical: String, source: WebSource) {
        viewModelScope.launch {
            val fresh = runCatching {
                withContext(Dispatchers.IO) { source.fetchIndex(canonical) { _, _, _ -> } }
            }.getOrNull() ?: return@launch
            cacheMutex.withLock {
                val cached = withContext(Dispatchers.IO) { webCache.read(canonical) } ?: return@withLock
                // 用 chapterUrl 建索引；没有 chapterUrl 的退化按下标对齐。
                val byUrl: Map<String, com.zzy.llmreader.web.CachedWebChapter> =
                    fresh.chapters.mapNotNull { c -> c.chapterUrl?.let { it to c } }.toMap()
                val merged = cached.chapters.mapIndexed { idx, old ->
                    val match = old.chapterUrl?.let { byUrl[it] }
                        ?: fresh.chapters.getOrNull(idx)
                        ?: return@mapIndexed old
                    old.copy(
                        title = if (old.translatedTitle.isNullOrBlank() && match.title.isNotBlank()) match.title
                                else if (old.title.isBlank() && match.title.isNotBlank()) match.title
                                else old.title,
                        groupTitle = old.groupTitle ?: match.groupTitle,
                    )
                }
                val changed = merged != cached.chapters || cached.title != fresh.title
                if (!changed) return@withLock
                val updated = cached.copy(
                    title = fresh.title.ifBlank { cached.title },
                    chapters = merged,
                )
                withContext(Dispatchers.IO) { webCache.write(updated) }
                _state.update { state ->
                    if (state.webCanonicalUrl != canonical) state
                    else state.copy(book = updated.toBookContent())
                }
            }
        }
    }

    fun openRecent(book: RecentBook) {
        if (book.source == RecentBookSource.WEB || book.uri.startsWith("http", ignoreCase = true)) {
            openWebBook(book.uri)
        } else {
            openBook(Uri.parse(book.uri))
        }
    }

    fun supportedSites(): List<WebSource> = webRegistry.sources

    /** 仅返回支持"发现页"浏览的站点。 */
    fun discoverableSites(): List<WebSource> =
        webRegistry.sources.filter { it.discovery != null }

    /** 切换发现页站点；自动复位 query 并触发首页加载。 */
    fun selectDiscoverySite(siteId: String) {
        val site = webRegistry.findById(siteId) ?: return
        val caps = site.discovery?.capabilities ?: return
        val resetQuery = com.zzy.llmreader.web.DiscoveryQuery(
            page = caps.firstPage,
            pageSize = caps.defaultPageSize,
            sortId = caps.sorts.firstOrNull()?.id,
        )
        _state.update {
            it.copy(
                discovery = DiscoveryUiState(
                    siteId = siteId,
                    query = resetQuery,
                    // 沿用 prefs 里的"默认翻译开关"——不要因为切站点就把它重置
                    translateListings = it.readerPrefs.discoveryTranslateOn,
                ),
            )
        }
        runDiscovery(resetPage = true)
    }

    /** 改 keyword / filter / sort 后重跑第一页。 */
    fun updateDiscoveryQuery(transform: (com.zzy.llmreader.web.DiscoveryQuery) -> com.zzy.llmreader.web.DiscoveryQuery) {
        val cur = _state.value.discovery
        val site = cur.siteId?.let { webRegistry.findById(it) } ?: return
        val caps = site.discovery?.capabilities ?: return
        val newQuery = transform(cur.query).copy(
            page = caps.firstPage,
            pageSize = caps.defaultPageSize,
        )
        _state.update {
            it.copy(discovery = it.discovery.copy(query = newQuery, items = emptyList(), scrollIndex = 0, scrollOffset = 0))
        }
        runDiscovery(resetPage = true)
    }

    /** 加载下一页并追加到结果列表。 */
    fun loadMoreDiscovery() {
        val cur = _state.value.discovery
        if (cur.loading || !cur.hasMore || cur.siteId == null) return
        runDiscovery(resetPage = false)
    }

    /** 清空发现页，回到 site picker。 */
    fun clearDiscovery() {
        _state.update {
            it.copy(
                discovery = DiscoveryUiState(
                    translateListings = it.readerPrefs.discoveryTranslateOn,
                ),
            )
        }
    }

    /** 由 UI 在滚动时调用，记录 Discovery 列表当前可见首项 + 偏移。 */
    fun setDiscoveryScroll(index: Int, offset: Int) {
        val cur = _state.value.discovery
        if (cur.scrollIndex == index && cur.scrollOffset == offset) return
        _state.update { it.copy(discovery = it.discovery.copy(scrollIndex = index, scrollOffset = offset)) }
    }

    /** 在卡片上点击 → 打开详情弹窗（不直接打开书）。 */
    fun showDiscoveryDetail(itemId: String) {
        _state.update {
            it.copy(
                discovery = it.discovery.copy(
                    detailItemId = itemId,
                    detailSimilarGroups = null,
                    detailSimilarLoading = true,
                ),
            )
        }
        // 异步拉站点原生相似作品；优先用分组接口，失败/不支持 → 单组退化 → 空列表让 UI 回退本地。
        viewModelScope.launch {
            val cur = _state.value.discovery
            val site = cur.siteId?.let { webRegistry.findById(it) }
            val discovery = site?.discovery
            val item = cur.items.firstOrNull { it.id == itemId }
            val groups: List<com.zzy.llmreader.web.SimilarGroup> = when {
                discovery == null || item == null -> emptyList()
                else -> {
                    val grouped = runCatching { discovery.fetchSimilarGroups(item, 12) }.getOrNull()
                    if (!grouped.isNullOrEmpty()) grouped
                    else {
                        val flat = runCatching { discovery.fetchSimilar(item, 12) }.getOrNull()
                        if (!flat.isNullOrEmpty()) listOf(com.zzy.llmreader.web.SimilarGroup("相似作品", flat))
                        else emptyList()
                    }
                }
            }
            _state.update {
                val d = it.discovery
                if (d.detailItemId != itemId) it
                else it.copy(
                    discovery = d.copy(
                        detailSimilarGroups = groups,
                        detailSimilarLoading = false,
                    ),
                )
            }
        }
    }

    /** 关闭详情弹窗。 */
    fun dismissDiscoveryDetail() {
        _state.update {
            it.copy(
                discovery = it.discovery.copy(
                    detailItemId = null,
                    detailSimilarGroups = null,
                    detailSimilarLoading = false,
                ),
            )
        }
    }

    // ---------- 作品详情页 ----------

    /** 在 Discovery 卡片或相似作品上点 → 打开作品详情独立界面。 */
    fun openBookDetail(itemId: String, sourceId: String? = null) {
        val cur = _state.value.discovery
        val sId = sourceId ?: cur.siteId
        val site = sId?.let { webRegistry.findById(it) }
        val item = cur.items.firstOrNull { it.id == itemId }
            ?: cur.detailSimilarGroups
                ?.asSequence()
                ?.flatMap { it.items.asSequence() }
                ?.firstOrNull { it.id == itemId }
            ?: _state.value.bookDetail?.similarGroups
                ?.asSequence()
                ?.flatMap { it.items.asSequence() }
                ?.firstOrNull { it.id == itemId }
            ?: return
        openBookDetailForItem(item, site)
    }

    /** 用一个完整的 DiscoveryItem 打开详情（用于跨详情页跳转）。 */
    fun openBookDetailForItem(
        item: com.zzy.llmreader.web.DiscoveryItem,
        site: com.zzy.llmreader.web.WebSource?,
    ) {
        val translate = _state.value.discovery.translateListings
        val initial = BookDetailUiState(
            key = item.detailUrl,
            title = item.title,
            isWeb = true,
            sourceId = site?.id,
            sourceName = site?.displayName,
            detailUrl = item.detailUrl,
            translatedTitle = item.translatedTitle,
            author = item.author,
            coverUrl = item.coverUrl,
            summary = item.summary,
            translatedSummary = item.translatedSummary,
            tags = item.tags,
            translatedTags = item.translatedTags,
            status = item.status,
            chapterCount = item.chapterCount,
            wordCount = item.wordCount,
            updatedAt = item.updatedAt,
            metrics = item.metrics,
            tocLoading = true,
            similarLoading = site?.discovery != null,
            translateListings = translate,
        )
        // 关闭旧的详情弹窗（若有）
        _state.update {
            it.copy(
                bookDetail = initial,
                discovery = it.discovery.copy(
                    detailItemId = null,
                    detailSimilarGroups = null,
                    detailSimilarLoading = false,
                ),
            )
        }
        viewModelScope.launch {
            // 1) 加载目录：优先缓存；缓存未命中则尝试 fetchIndex（仅取标题，不下章节正文）
            val canonical = runCatching { site?.canonicalize(item.detailUrl) }.getOrNull()
                ?: item.detailUrl
            val cached = withContext(Dispatchers.IO) { webCache.read(canonical) }
            if (cached != null) {
                applyTocToDetail(canonical, cached, fromCache = true)
                ensureTocTranslated(canonical)
            } else if (site != null) {
                runCatching {
                    site.fetchIndex(canonical) { _, _, _ -> }
                }.onSuccess { fetched ->
                    withContext(Dispatchers.IO) { webCache.write(fetched) }
                    applyTocToDetail(canonical, fetched, fromCache = false)
                    // 后台批量翻译目录章节标题（如果开启了译文）。
                    ensureTocTranslated(canonical)
                }.onFailure { err ->
                    _state.update {
                        val d = it.bookDetail ?: return@update it
                        if (d.key != item.detailUrl) it
                        else it.copy(
                            bookDetail = d.copy(
                                tocLoading = false,
                                tocError = err.message ?: err.javaClass.simpleName,
                            ),
                        )
                    }
                }
            } else {
                _state.update {
                    val d = it.bookDetail ?: return@update it
                    if (d.key != item.detailUrl) it
                    else it.copy(bookDetail = d.copy(tocLoading = false))
                }
            }
        }
        if (site?.discovery != null) {
            loadDetailSimilarGroups(item, site)
        }
    }

    private fun applyTocToDetail(canonical: String, book: com.zzy.llmreader.web.CachedWebBook, fromCache: Boolean) {
        _state.update {
            val d = it.bookDetail ?: return@update it
            if (d.key != canonical && d.detailUrl != canonical) it
            else it.copy(
                bookDetail = d.copy(
                    key = canonical,
                    title = book.title.ifBlank { d.title },
                    chapters = book.chapters.map { ch -> ch.title },
                    translatedChapters = book.chapters.map { ch -> ch.translatedTitle },
                    chapterCount = book.chapters.size,
                    tocFromCache = fromCache,
                    tocLoading = false,
                    tocError = null,
                ),
            )
        }
    }

    private fun loadDetailSimilarGroups(
        item: com.zzy.llmreader.web.DiscoveryItem,
        site: com.zzy.llmreader.web.WebSource,
    ) {
        viewModelScope.launch {
            val discovery = site.discovery ?: return@launch
            // 1) 站点原生分组接口；若没有分组结果，兜底用扁平的"相似作品"列表
            val grouped = runCatching { discovery.fetchSimilarGroups(item, 12) }.getOrNull()
            val groups: List<com.zzy.llmreader.web.SimilarGroup> = if (!grouped.isNullOrEmpty()) {
                grouped
            } else {
                val flat = runCatching { discovery.fetchSimilar(item, 12) }.getOrNull()
                if (!flat.isNullOrEmpty()) listOf(com.zzy.llmreader.web.SimilarGroup("相似作品", flat)) else emptyList()
            }
            // 先把缓存里已有的标题/摘要译文塞进来（无需走网络），让首屏就能显示译名。
            val translate = _state.value.bookDetail?.translateListings
                ?: _state.value.readerPrefs.discoveryTranslateOn
            val groupsWithCachedTx = if (translate) groups.map { g ->
                g.copy(items = g.items.map { applyCachedListingsTx(it) })
            } else groups
            _state.update {
                val d = it.bookDetail ?: return@update it
                if (d.detailUrl != item.detailUrl) it
                else it.copy(
                    bookDetail = d.copy(
                        similarGroups = groupsWithCachedTx,
                        similarLoading = false,
                    ),
                )
            }
            // 2) 后台批量翻译"作者同作品"分组里的标题/摘要/标签
            if (translate && groupsWithCachedTx.isNotEmpty()) {
                runCatching { translateBookDetailGroups(item.detailUrl) }
                    .onFailure { android.util.Log.w("ReaderVM", "translate similar groups failed", it) }
            }
        }
    }

    /** 用已缓存的译文给单条 DiscoveryItem 补 translatedTitle/Summary/Tags。 */
    private fun applyCachedListingsTx(item: com.zzy.llmreader.web.DiscoveryItem): com.zzy.llmreader.web.DiscoveryItem {
        val target = _state.value.readerPrefs.translationLanguage
        val cachedBook = bookInfoTranslationCache[bookInfoCacheKey(target, item.title, item.summary.orEmpty())]
        val txTags = applyTagsForItem(item, target)
        return item.copy(
            translatedTitle = cachedBook?.first?.takeIf { it.isNotBlank() } ?: item.translatedTitle,
            translatedSummary = cachedBook?.second?.takeIf { it.isNotBlank() } ?: item.translatedSummary,
            translatedTags = txTags ?: item.translatedTags,
        )
    }

    /** 给详情页的 similarGroups 做一次完整翻译（标题+摘要+标签），完成后把译文写回 state。 */
    private suspend fun translateBookDetailGroups(detailUrl: String) {
        val settings = _state.value.settings
        if (settings.apiKey.isBlank()) return
        val target = _state.value.readerPrefs.translationLanguage
        val style = _state.value.readerPrefs.translationStyle
        val groups = _state.value.bookDetail?.similarGroups ?: return
        if (groups.isEmpty()) return
        val flat = groups.flatMap { it.items }
        // 待译书目（标题/摘要）
        val pendingBooks = flat.mapNotNull { i ->
            if (!i.translatedTitle.isNullOrBlank() &&
                (i.summary.isNullOrBlank() || !i.translatedSummary.isNullOrBlank())
            ) return@mapNotNull null
            val summary = i.summary
            if (looksLikeTargetLanguage(i.title, target) &&
                (summary.isNullOrBlank() || looksLikeTargetLanguage(summary, target))
            ) return@mapNotNull null
            val key = bookInfoCacheKey(target, i.title, i.summary.orEmpty())
            if (bookInfoTranslationCache[key] != null) return@mapNotNull null
            BookJob(i.id, i.title, i.summary?.take(800).orEmpty())
        }
        val allTags = flat.flatMap { it.tags }.distinct().filter { it.isNotBlank() }
        val pendingTags = allTags.filter {
            tagTranslationCache["$target|$it"] == null && !looksLikeTargetLanguage(it, target)
        }
        if (pendingBooks.isEmpty() && pendingTags.isEmpty()) {
            // 缓存全命中，直接把 cache 应用一遍即可
            applyDetailGroupsTx(detailUrl)
            return
        }
        kotlinx.coroutines.coroutineScope {
            val bookJob: Deferred<Map<String, Pair<String, String>>>? = if (pendingBooks.isNotEmpty()) async {
                runCatching { translateBookInfosOnce(settings, target, style, pendingBooks) }
                    .getOrDefault(emptyMap())
            } else null
            val tagJob: Deferred<Map<String, String>>? = if (pendingTags.isNotEmpty()) async {
                runCatching { translateTagsOnce(settings, target, pendingTags) }
                    .getOrDefault(emptyMap())
            } else null
            val bookResults = bookJob?.await().orEmpty()
            val tagResults = tagJob?.await().orEmpty()
            for ((id, pair) in bookResults) {
                val orig = flat.firstOrNull { it.id == id } ?: continue
                bookInfoTranslationCache[bookInfoCacheKey(target, orig.title, orig.summary.orEmpty())] = pair
            }
            for ((tag, tx) in tagResults) {
                if (tx.isNotBlank()) tagTranslationCache["$target|$tag"] = tx
            }
            if (bookResults.isNotEmpty()) {
                runCatching { discoveryTxRepo.saveBooks(bookInfoTranslationCache) }
            }
            if (tagResults.isNotEmpty()) {
                runCatching { discoveryTxRepo.saveTags(tagTranslationCache) }
            }
            applyDetailGroupsTx(detailUrl)
        }
    }

    /** 把当前缓存里的译文应用到 bookDetail.similarGroups（按 detailUrl 自检过期）。 */
    private fun applyDetailGroupsTx(detailUrl: String) {
        _state.update { st ->
            val d = st.bookDetail ?: return@update st
            if (d.detailUrl != detailUrl) return@update st
            val groups = d.similarGroups ?: return@update st
            val newGroups = groups.map { g ->
                g.copy(items = g.items.map { applyCachedListingsTx(it) })
            }
            st.copy(bookDetail = d.copy(similarGroups = newGroups))
        }
    }

    /** 从书库点 i 信息按钮 → 打开 Recent 的详情页。 */
    fun openBookDetailFromRecent(book: com.zzy.llmreader.data.RecentBook) {
        val isWeb = book.source == com.zzy.llmreader.data.RecentBookSource.WEB ||
            book.uri.startsWith("http", ignoreCase = true)
        val translate = _state.value.discovery.translateListings
        if (isWeb) {
            val site = webRegistry.find(book.uri)
            val canonical = runCatching { site?.canonicalize(book.uri) }.getOrNull() ?: book.uri
            val initial = BookDetailUiState(
                key = canonical,
                title = book.title,
                isWeb = true,
                sourceId = site?.id,
                sourceName = site?.displayName,
                detailUrl = book.uri,
                tocLoading = true,
                similarLoading = site?.discovery != null,
                translateListings = translate,
            )
            _state.update { it.copy(bookDetail = initial) }
            viewModelScope.launch {
                val cached = withContext(Dispatchers.IO) { webCache.read(canonical) }
                if (cached != null) {
                    applyTocToDetail(canonical, cached, fromCache = true)
                    ensureTocTranslated(canonical)
                    // 用缓存的 url 重建一个 DiscoveryItem 占位，去站点拉相似作品
                    if (site != null) {
                        val placeholder = com.zzy.llmreader.web.DiscoveryItem(
                            id = canonical,
                            detailUrl = canonical,
                            title = cached.title,
                        )
                        loadDetailSimilarGroups(placeholder, site)
                    }
                } else if (site != null) {
                    runCatching { site.fetchIndex(canonical) { _, _, _ -> } }
                        .onSuccess { fetched ->
                            withContext(Dispatchers.IO) { webCache.write(fetched) }
                            applyTocToDetail(canonical, fetched, fromCache = false)
                            ensureTocTranslated(canonical)
                            val placeholder = com.zzy.llmreader.web.DiscoveryItem(
                                id = canonical,
                                detailUrl = canonical,
                                title = fetched.title,
                            )
                            loadDetailSimilarGroups(placeholder, site)
                        }
                        .onFailure { err ->
                            _state.update {
                                val d = it.bookDetail ?: return@update it
                                if (d.key != canonical) it
                                else it.copy(
                                    bookDetail = d.copy(
                                        tocLoading = false,
                                        tocError = err.message ?: err.javaClass.simpleName,
                                    ),
                                )
                            }
                        }
                }
            }
        } else {
            // 本地文件：尝试解析当前打开的 BookContent.Text 章节列表（同 Reader 一样）。
            val initial = BookDetailUiState(
                key = book.uri,
                title = book.title,
                isWeb = false,
                detailUrl = book.uri,
                tocLoading = true,
                translateListings = translate,
            )
            _state.update { it.copy(bookDetail = initial) }
            viewModelScope.launch {
                val parsed = runCatching {
                    parser.parse(
                        AndroidBookSource(getApplication(), android.net.Uri.parse(book.uri)),
                    )
                }.getOrNull()
                _state.update {
                    val d = it.bookDetail ?: return@update it
                    if (d.key != book.uri) it
                    else if (parsed is com.zzy.llmreader.model.BookContent.Text) {
                        it.copy(
                            bookDetail = d.copy(
                                title = parsed.title.ifBlank { d.title },
                                chapters = parsed.chapters.map { ch -> ch.title },
                                chapterCount = parsed.chapters.size,
                                tocLoading = false,
                                tocFromCache = true,
                            ),
                        )
                    } else {
                        it.copy(
                            bookDetail = d.copy(
                                tocLoading = false,
                                tocError = "本地文件不是文本类小说，无目录可显示。",
                            ),
                        )
                    }
                }
            }
        }
    }

    /** 在阅读时点「查看详情」→ 打开当前书的详情页。 */
    fun openBookDetailForCurrent() {
        val state = _state.value
        val book = state.book ?: return
        val translate = state.discovery.translateListings
        val canonical = state.webCanonicalUrl
        if (canonical != null) {
            // 网页书：复用缓存的目录数据
            val site = state.webSourceId?.let { webRegistry.findById(it) }
            val initial = BookDetailUiState(
                key = canonical,
                title = book.title,
                isWeb = true,
                sourceId = site?.id,
                sourceName = site?.displayName,
                detailUrl = canonical,
                tocLoading = true,
                similarLoading = site?.discovery != null,
                translateListings = translate,
            )
            _state.update { it.copy(bookDetail = initial) }
            viewModelScope.launch {
                val cached = withContext(Dispatchers.IO) { webCache.read(canonical) }
                if (cached != null) {
                    applyTocToDetail(canonical, cached, fromCache = true)
                    ensureTocTranslated(canonical)
                    if (site != null) {
                        val placeholder = com.zzy.llmreader.web.DiscoveryItem(
                            id = canonical,
                            detailUrl = canonical,
                            title = cached.title,
                        )
                        loadDetailSimilarGroups(placeholder, site)
                    }
                }
            }
        } else if (book is com.zzy.llmreader.model.BookContent.Text) {
            val initial = BookDetailUiState(
                key = currentBookKey ?: book.title,
                title = book.title,
                isWeb = false,
                tocLoading = false,
                tocFromCache = true,
                chapters = book.chapters.map { it.title },
                chapterCount = book.chapters.size,
                translateListings = translate,
            )
            _state.update { it.copy(bookDetail = initial) }
        }
    }

    /** 关闭作品详情独立界面。 */
    fun dismissBookDetail() {
        _state.update { it.copy(bookDetail = null) }
    }

    /** 在详情页点章节 → 打开此书并跳到该章。 */
    fun openBookDetailChapter(chapterIndex: Int) {
        val d = _state.value.bookDetail ?: return
        if (d.isWeb) {
            val target = d.detailUrl ?: d.key
            // 关闭详情，然后走正常的 openWebBook 流程并设置初始章节
            pendingInitialChapter = chapterIndex
            _state.update { it.copy(bookDetail = null) }
            openWebBook(target)
        } else {
            // 本地：如果当前已经是这本书，直接 selectChapter；否则先打开。
            val currentKey = currentBookKey
            if (currentKey == d.key && _state.value.book != null) {
                _state.update { it.copy(bookDetail = null) }
                selectChapter(chapterIndex)
            } else {
                pendingInitialChapter = chapterIndex
                _state.update { it.copy(bookDetail = null) }
                openBook(android.net.Uri.parse(d.key))
            }
        }
    }

    /** 切换"翻译标题/简介"开关。开启时统一对当前列表批量翻译；关闭时不清空已译数据，UI 自动切回原文。 */
    fun setDiscoveryTranslate(enabled: Boolean) {
        _state.update {
            it.copy(discovery = it.discovery.copy(translateListings = enabled))
        }
        // 持久化到 prefs，下次启动自动恢复
        viewModelScope.launch {
            runCatching {
                readerPrefsRepo.update(_state.value.readerPrefs.copy(discoveryTranslateOn = enabled))
            }
        }
        if (enabled) {
            translateDiscoveryBatch(_state.value.discovery.items)
        }
    }

    /**
     * 强制重新翻译当前发现页可见的全部 item：
     * - 清掉这些 item 涉及的 tag/书目缓存项；
     * - 重置 item 上的 translatedTitle/Summary/Tags；
     * - 自动开启翻译开关并触发批量翻译。
     */
    fun retranslateDiscoveryAll() {
        val target = _state.value.readerPrefs.translationLanguage
        val items = _state.value.discovery.items
        if (items.isEmpty()) return
        // 清缓存
        items.forEach { item ->
            bookInfoTranslationCache.remove(bookInfoCacheKey(target, item.title, item.summary.orEmpty()))
        }
        items.flatMap { it.tags }.distinct().forEach { tag ->
            tagTranslationCache.remove("$target|$tag")
        }
        // 落盘（删完后整体回写）
        viewModelScope.launch {
            runCatching { discoveryTxRepo.saveBooks(bookInfoTranslationCache) }
            runCatching { discoveryTxRepo.saveTags(tagTranslationCache) }
        }
        // 重置 item 译文字段
        _state.update { st ->
            val d = st.discovery
            val cleared = d.items.map {
                it.copy(translatedTitle = null, translatedSummary = null, translatedTags = null)
            }
            st.copy(discovery = d.copy(items = cleared, translateListings = true))
        }
        translateDiscoveryBatch(_state.value.discovery.items)
    }



    internal fun bookInfoCacheKey(lang: String, title: String, summary: String): String =
        "$lang|$title|||${summary.take(800)}"

    /**
     * 检测一段文字是否已经是目标语言（粗略判断），用于在发现页跳过"中文 → 中文"这种无意义翻译。
     *
     * 仅当 target 显示为中文（"中文" / "简体中文" / "繁体" / "汉语" / 包含 "Chinese"）时启用判定，
     * 并要求文本满足两个条件：
     *  1) 含有 ≥2 个 CJK 汉字（U+4E00..U+9FFF / 扩展区）；
     *  2) 不含日文假名（平假名 U+3040..U+309F、片假名 U+30A0..U+30FF）。
     * 这能正确把 "[中華] 章节标题 译/原" 之类标题判为已是中文，同时把日文 / 韩文 / 英文判为非中文。
     */
    private fun isJapaneseSourceUrl(url: String): Boolean {
        val u = url.lowercase()
        return u.contains("syosetu.com") || u.contains("syosetu.org") ||
            u.contains("kakuyomu.jp") || u.contains("hameln") ||
            u.contains("pixiv.net") || u.contains("alphapolis.co.jp")
    }

    internal fun looksLikeTargetLanguage(text: String, target: String): Boolean {
        if (text.isBlank()) return true
        val tgt = target.lowercase()
        val isChineseTarget = tgt.contains("中文") || tgt.contains("简体") ||
            tgt.contains("繁体") || tgt.contains("汉语") || tgt.contains("漢語") ||
            tgt.contains("chinese") || tgt.contains("zh")
        if (!isChineseTarget) return false
        var cjk = 0
        for (c in text) {
            val cp = c.code
            // 日文假名（平假名/片假名/半宽片假名）→ 立即否决
            if (cp in 0x3040..0x309F || cp in 0x30A0..0x30FF || cp in 0xFF66..0xFF9D) return false
            // 韩文 Hangul 也否决（怕被误判成中文目标）
            if (cp in 0xAC00..0xD7A3) return false
            if (cp in 0x4E00..0x9FFF || cp in 0x3400..0x4DBF) cjk++
        }
        return cjk >= 2
    }



















    /** UI 触发 CF 验证 Activity 后调用，把 cookie 落盘并自动重试上次失败的操作。 */
    fun onCloudflareCookieResolved(cookie: String, userAgent: String) {
        viewModelScope.launch {
            // 根据 pendingCloudflareUrl 的 host 决定写到哪个站点的 cookie 字段。
            val pendingUrl = _state.value.pendingCloudflareUrl.orEmpty()
            val host = runCatching {
                android.net.Uri.parse(pendingUrl).host?.lowercase().orEmpty()
            }.getOrDefault("")
            val current = _state.value.readerPrefs
            val updated = when {
                host.endsWith("scribblehub.com") -> current.copy(
                    scribblehubCookie = cookie.trim(),
                    scribblehubUserAgent = userAgent.trim(),
                )
                host.endsWith("archiveofourown.org") -> current.copy(
                    ao3Cookie = cookie.trim(),
                    ao3UserAgent = userAgent.trim(),
                )
                else -> current.copy(
                    hamelnCookie = cookie.trim(),
                    hamelnUserAgent = userAgent.trim(),
                )
            }
            readerPrefsRepo.update(updated)
            _state.update { it.copy(pendingCloudflareUrl = null, error = null) }
            val pendingOpen = pendingWebBookOpen
            val pendingChap = pendingChapterReload
            val pendingDisc = pendingDiscoveryReload
            pendingWebBookOpen = null
            pendingChapterReload = null
            pendingDiscoveryReload = false
            if (pendingOpen != null) {
                openWebBook(pendingOpen, forceRefresh = true)
            } else if (pendingChap != null) {
                ensureChapterLoaded(pendingChap)
            } else if (pendingDisc) {
                runDiscovery(resetPage = true)
            }
        }
    }

    /** 用户取消 CF 验证 dialog/activity。 */
    fun dismissCloudflareChallenge() {
        pendingWebBookOpen = null
        pendingChapterReload = null
        pendingDiscoveryReload = false
        _state.update { it.copy(pendingCloudflareUrl = null) }
    }

    fun dismissError() {
        if (_state.value.error != null) {
            _state.update { it.copy(error = null) }
        }
    }

    fun closeBook() {
        currentBookKey = null
        cancelAllTranslationJobs()
        _state.update {
            it.copy(
                book = null,
                ask = AskState(),
                loadingChapters = emptySet(),
                webCanonicalUrl = null,
                webSourceId = null,
                translatingChapters = emptySet(),
                translationProgress = null,
                failedTranslations = emptyMap(),
            )
        }
    }

    fun selectChapter(index: Int) = selectChapter(index, startAtEnd = false)

    /** 跳到指定章节并定位到章末（翻页翻到章首再往前时用）。 */
    fun selectChapterAtEnd(index: Int) = selectChapter(index, startAtEnd = true)

    private fun selectChapter(index: Int, startAtEnd: Boolean) {
        // 切章节时把初始滚动定位到头部或末尾；本地也同步记录 lastSavedScrollFraction。
        val frac = if (startAtEnd) 1f else 0f
        lastSavedScrollFraction = frac
        _state.update { it.copy(currentChapter = index, initialChapterScrollFraction = frac) }
        ensureChapterLoaded(index)
        prefetchAround(index)
        if (_state.value.readerPrefs.translationMode != TranslationMode.ORIGINAL) {
            ensureChapterTranslated(index)
        }
        refreshChapterImages()
        // 持久化阅读进度（章节）。
        val key = currentBookKey
        if (!key.isNullOrBlank()) {
            viewModelScope.launch { progressRepo.save(key, index, frac) }
        }
    }

    /** 上次保存的滚动百分比，用于节流避免频繁写 DataStore。 */
    private var lastSavedScrollFraction: Float = 0f

    /** UI 在当前章滚动时调用；变化 ≥ 0.01 才写入。 */
    fun setChapterScrollFraction(index: Int, fraction: Float) {
        val key = currentBookKey ?: return
        val cur = _state.value
        if (cur.currentChapter != index) return
        val f = fraction.coerceIn(0f, 1f)
        if (kotlin.math.abs(f - lastSavedScrollFraction) < 0.01f) return
        lastSavedScrollFraction = f
        viewModelScope.launch { progressRepo.save(key, index, f) }
    }

    /**
     * 后台预取从 `currentIndex` 之后的若干章（依据用户设置 webPrefetchAhead）。
     * 不会阻塞 UI；不会触发 loadingChapters 状态（避免 UI 闪烁）。
     */
    private fun prefetchAround(currentIndex: Int) {
        val cur = _state.value
        val ahead = cur.readerPrefs.webPrefetchAhead.coerceAtLeast(0)
        if (ahead == 0) return
        val canonical = cur.webCanonicalUrl ?: return
        val sourceId = cur.webSourceId ?: return
        val source = webRegistry.findById(sourceId) ?: return
        val book = cur.book as? BookContent.Text ?: return

        val targets = (1..ahead)
            .map { currentIndex + it }
            .filter { it in book.chapters.indices }
            .filter { book.chapters[it].content == null }
            .filter { it !in cur.loadingChapters }

        if (targets.isEmpty()) return

        viewModelScope.launch {
            for (idx in targets) {
                // 期间用户可能切书/换章，需要重新检查。
                val s = _state.value
                if (s.webCanonicalUrl != canonical) return@launch

                // 先快速检查这一章是否已在磁盘上（可能其他协程已经写好）。
                val placeholder = withContext(Dispatchers.IO) { webCache.read(canonical) }
                    ?.chapters?.getOrNull(idx) ?: continue
                if (placeholder.html != null) continue

                val fetched = runCatching { source.fetchChapter(canonical, idx, placeholder) }
                    .getOrNull()
                if (fetched == null) {
                    Log.w(TAG, "prefetch chapter $idx failed")
                    continue
                }

                val updated = cacheMutex.withLock {
                    val cached = withContext(Dispatchers.IO) { webCache.read(canonical) }
                        ?: return@launch
                    val u = cached.copy(
                        chapters = cached.chapters.toMutableList().apply { this[idx] = fetched },
                    )
                    withContext(Dispatchers.IO) { webCache.write(u) }
                    u
                }

                // 静默更新 UI 中对应章节的 content（不动当前章节、不显示 loading）。
                _state.update { state ->
                    if (state.webCanonicalUrl != canonical) state
                    else state.copy(book = updated.toBookContent())
                }
            }
        }
    }

    fun saveReaderPrefs(prefs: ReaderPreferences) {
        viewModelScope.launch { readerPrefsRepo.update(prefs) }
    }

    /** 直接切到指定翻译模式（下拉菜单选择）。 */
    fun setTranslationMode(mode: TranslationMode) {
        val cur = _state.value
        if (cur.readerPrefs.translationMode == mode) return
        saveReaderPrefs(cur.readerPrefs.copy(translationMode = mode))
        if (mode != TranslationMode.ORIGINAL) {
            ensureChapterTranslated(cur.currentChapter)
        }
    }

    /** 切换 白天 → 护眼 → 夜间 三种主题。 */
    fun cycleReadingTheme() {
        val cur = _state.value
        val next = when (cur.readerPrefs.readingTheme) {
            ReadingTheme.DAY -> ReadingTheme.SEPIA
            ReadingTheme.SEPIA -> ReadingTheme.NIGHT
            ReadingTheme.NIGHT -> ReadingTheme.DAY
        }
        saveReaderPrefs(cur.readerPrefs.copy(readingTheme = next))
    }





    /** 粗略剥离 HTML 标签，仅供语言检测使用，最长截 4000 字符够判断。 */
    internal fun stripHtml(html: String): String =
        html.replace(Regex("<[^>]+>"), " ").take(4000)

    /** 节流：每 250ms 至多 flush 一次部分译文到 UI。 */


    private val tocTranslateInFlight = mutableSetOf<String>()

    /**
     * 在阅读时后台批量翻译网页书的章节目录标题。
     *
     * - 命中缓存的（cache 里 translatedTitle 已是当前目标语言）跳过；
     * - 标题已是目标语言（如中文）的跳过；
     * - 用 30 个标题一组、最多 2 路并发，分块流式不必要——目录标题短，直接整批 chat 即可；
     * - 单组失败/被过滤 → 二分降级；翻好的立刻写回 cache，并刷新 UI 让目录显示译名。
     */
    fun ensureTocTranslated(canonical: String) {
        if (canonical.isBlank()) return
        if (!tocTranslateInFlight.add(canonical)) return
        viewModelScope.launch {
            try {
                val settings = _state.value.settings
                if (settings.apiKey.isBlank()) return@launch
                val target = _state.value.readerPrefs.translationLanguage
                val style = _state.value.readerPrefs.translationStyle
                val cached0 = withContext(Dispatchers.IO) { webCache.read(canonical) } ?: return@launch
                // 收集需要翻译的章节：translatedTitle 缺失或语言不匹配，且原标题非目标语言。
                val isJapaneseSource = isJapaneseSourceUrl(canonical)
                val pending = cached0.chapters.mapIndexedNotNull { idx, ch ->
                    val raw = ch.title.trim()
                    if (raw.isBlank()) return@mapIndexedNotNull null
                    val txOk = !ch.translatedTitle.isNullOrBlank() && ch.translationLanguage == target
                    if (txOk) return@mapIndexedNotNull null
                    if (!isJapaneseSource && looksLikeTargetLanguage(raw, target)) return@mapIndexedNotNull null
                    idx to raw
                }
                if (pending.isEmpty()) return@launch
                android.util.Log.i("ReaderVM", "translate TOC: ${pending.size} pending titles")

                val chunks = pending.chunked(30)
                val sem = Semaphore(2)
                kotlinx.coroutines.coroutineScope {
                    chunks.forEach { chunk ->
                        launch {
                            sem.withPermit {
                                runCatching { translateTocChunkSafe(settings, target, style, canonical, chunk) }
                                    .onFailure { android.util.Log.w("ReaderVM", "TOC chunk failed", it) }
                            }
                        }
                    }
                }
            } finally {
                tocTranslateInFlight.remove(canonical)
            }
        }
    }

    /** 单组目录标题翻译；命中过滤则二分降级。 */
    private suspend fun translateTocChunkSafe(
        settings: com.zzy.llmreader.data.LlmSettings,
        target: String,
        style: String,
        canonical: String,
        chunk: List<Pair<Int, String>>,
    ) {
        if (chunk.isEmpty()) return
        val result = runCatching { translateTocChunk(settings, target, style, chunk) }
        result.exceptionOrNull()?.let { e ->
            val msg = e.message.orEmpty()
            val filtered = msg.contains("filtered", ignoreCase = true) ||
                msg.contains("HTTP 500") ||
                msg.contains("content_policy", ignoreCase = true) ||
                msg.contains("safety", ignoreCase = true)
            if (!filtered || chunk.size <= 1) {
                if (chunk.size > 1) throw e
                return
            }
            val mid = chunk.size / 2
            translateTocChunkSafe(settings, target, style, canonical, chunk.subList(0, mid))
            translateTocChunkSafe(settings, target, style, canonical, chunk.subList(mid, chunk.size))
            return
        }
        val tx = result.getOrDefault(emptyMap())
        if (tx.isEmpty()) {
            // 没抛异常却一条都没解析出来：多半是模型没按 `<编号>. <译名>` 回。
            // 以前这里直接 return，用户的现象是"目录点了翻译没反应"，日志里也无迹可寻。
            android.util.Log.w("ReaderVM", "TOC chunk empty parse, chunk=${chunk.size}")
            return
        }
        cacheMutex.withLock {
            val cached = withContext(Dispatchers.IO) { webCache.read(canonical) } ?: return@withLock
            val newChapters = cached.chapters.toMutableList()
            for ((idx, name) in tx) {
                val ch = newChapters.getOrNull(idx) ?: continue
                newChapters[idx] = ch.copy(translatedTitle = name, translationLanguage = target)
            }
            val updated = cached.copy(chapters = newChapters)
            withContext(Dispatchers.IO) { webCache.write(updated) }
        }
        refreshBookFromCache(canonical)
    }

    /** 一次性把若干章节标题发给 LLM。返回 chapterIndex → 译名。 */
    private suspend fun translateTocChunk(
        settings: com.zzy.llmreader.data.LlmSettings,
        target: String,
        style: String,
        chunk: List<Pair<Int, String>>,
    ): Map<Int, String> {
        // 结构化链路专用破限：不写死输出格式，避免与下面的 `<编号>. <译名>` 契约打架。
        val (jbTop, jbBottom) = translator.structuredJailbreakPair(settings)
        val sys = buildString {
            if (jbTop.isNotEmpty()) append(jbTop).append("\n\n")
            append("你是小说阅读 app 的目录翻译引擎，把以下编号的章节标题逐条翻译为").append(target).append("。")
            append("保留原意与语气，长度尽量与原文相当；标题里若已是目标语言可原样输出。")
            if (style.isNotBlank()) append(" 风格：").append(style).append("。")
            append("\n严格按 `<编号>. <译名>` 一行一条输出，不要 markdown / 说明 / 思考过程。")
        }
        val userBody = buildString {
            chunk.forEachIndexed { i, (_, t) ->
                if (i > 0) append('\n')
                append(i + 1).append(". ").append(t)
            }
            if (jbBottom.isNotEmpty()) append("\n\n").append(jbBottom)
        }
        val res = client.chat(
            settings,
            listOf(
                com.zzy.llmreader.llm.ChatMessage(role = "system", content = sys),
                com.zzy.llmreader.llm.ChatMessage(role = "user", content = userBody),
            ),
            forceMinimalReasoning = true,
            // 助手预填：锁定 `<编号>. <译名>` 的开头，模型无法以解释/拒绝起笔。
            prefill = "1. ",
        ).getOrElse { throw it }
        val out = mutableMapOf<Int, String>()
        // 兼容两种编号形式：调用方契约的 `1. 译名`，以及模型惯性输出的 `[1] 译名`。
        val numRe = Regex("^\\s*\\[?(\\d+)[\\]\\.\\)、]\\s*(.+)$")
        for (line in res.lineSequence()) {
            val m = numRe.find(line) ?: continue
            val one = m.groupValues[1].toIntOrNull() ?: continue
            val tx = m.groupValues[2].trim().trim('"', '\'')
            val pair = chunk.getOrNull(one - 1) ?: continue
            if (tx.isNotBlank()) out[pair.first] = tx
        }
        return out
    }

    fun setFontScale(scale: Float) =
        _state.update { it.copy(fontScale = scale.coerceIn(0.6f, 2.5f)) }

    /** 获取当前书的术语表（网页书 → cache，本地书 → LocalGlossaryRepository）。 */
    fun getGlossary(): List<com.zzy.llmreader.web.GlossaryEntry> {
        val canonical = _state.value.webCanonicalUrl
        if (canonical != null) {
            val cached = webCache.readSync(canonical) ?: return emptyList()
            return cached.glossary
        }
        val key = _state.value.book?.title ?: return emptyList()
        return localGlossary.read(key)
    }

    /** 保存用户手动编辑后的术语表。 */
    /**
     * 保存术语表。
     *
     * 会过滤掉明显不是术语的条目（空、含句末标点的整句、超长文本），
     * 但**不再静默丢弃** —— 丢弃条数通过 [onDropped] 回传，由 UI 告诉用户。
     * 长度上限从 40 放宽到 80，与 `ChapterTranslator.filterGlossaryLines`
     * 注入时用的上限对齐：旧版一边允许 80 字的一行存在、一边在保存时删掉它，
     * 用户会发现条目「自己消失了」。
     */
    fun saveGlossary(
        entries: List<com.zzy.llmreader.web.GlossaryEntry>,
        onDropped: (Int) -> Unit = {},
    ) {
        val cleaned = entries.filter { e ->
            e.original.isNotBlank() && e.translated.isNotBlank() &&
                e.original.length <= 80 && e.translated.length <= 80 &&
                e.original.none { it in ".。;；?？!！" } &&
                e.translated.none { it in ".。;；?？!！" }
        }
        val dropped = entries.size - cleaned.size
        if (dropped > 0) onDropped(dropped)
        val canonical = _state.value.webCanonicalUrl
        if (canonical != null) {
            viewModelScope.launch {
                cacheMutex.withLock {
                    val cached = withContext(Dispatchers.IO) { webCache.read(canonical) } ?: return@withLock
                    val updated = cached.copy(glossary = cleaned)
                    withContext(Dispatchers.IO) { webCache.write(updated) }
                }
            }
            return
        }
        val key = _state.value.book?.title ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { localGlossary.write(key, cleaned) }
        }
    }

    /** 手动触发术语表 LLM 智能去重，返回 callback 通知 UI 更新。 */
    fun deduplicateGlossaryManual(callback: (Result<List<com.zzy.llmreader.web.GlossaryEntry>>) -> Unit) {
        val canonical = _state.value.webCanonicalUrl
        val localKey = if (canonical == null) _state.value.book?.title else null
        if (canonical == null && localKey.isNullOrBlank()) {
            callback(Result.failure(Exception("没有打开的书")))
            return
        }
        viewModelScope.launch {
            val settings = settingsRepo.settings.first()
            val current = if (canonical != null) {
                withContext(Dispatchers.IO) { webCache.read(canonical) }?.glossary ?: emptyList()
            } else {
                withContext(Dispatchers.IO) { localGlossary.read(localKey!!) }
            }
            if (current.size <= 1) {
                callback(Result.success(current))
                return@launch
            }
            val glossaryText = current.joinToString("\n") { "${it.original} → ${it.translated}" }
            val result = runCatching {
                val deduped = translator.deduplicateGlossary(settings, glossaryText)
                val dedupedEntries = if (deduped.isNotBlank()) parseGlossaryText(deduped) else emptyList()
                // ⚠️ 只回传结果，**不落盘**。
                // 旧实现把合并结果直接写进缓存，于是用户在对话框里点「取消」
                // 也照样生效 —— 取消按钮形同虚设。现在由用户点「保存」才写入。
                if (dedupedEntries.isNotEmpty()) dedupedEntries else current
            }
            callback(result)
        }
    }

    /** 重新翻译当前章节：清掉缓存中的译文，重新触发翻译。 */
    fun retranslateCurrentChapter() {
        val cur = _state.value
        val index = cur.currentChapter
        val book = cur.book as? BookContent.Text ?: return
        if (index !in book.chapters.indices) return
        if (index in cur.translatingChapters) return

        // 清掉内存中的译文
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
        // 清掉磁盘缓存中的译文（网页书）
        val canonical = cur.webCanonicalUrl
        if (canonical != null) {
            viewModelScope.launch {
                cacheMutex.withLock {
                    val cached = withContext(Dispatchers.IO) { webCache.read(canonical) } ?: return@withLock
                    val chs = cached.chapters.toMutableList()
                    if (index in chs.indices) {
                        chs[index] = chs[index].copy(
                            translatedHtml = null,
                            translatedTitle = null,
                            translationLanguage = null,
                        )
                        val updated = cached.copy(chapters = chs)
                        withContext(Dispatchers.IO) { webCache.write(updated) }
                    }
                }
                // 重新翻译
                ensureChapterTranslated(index)
            }
        } else {
            ensureChapterTranslated(index)
        }
    }

    /** 解析 LLM 返回的"原文 → 译文"文本为结构化列表。 */
    internal fun parseGlossaryText(text: String): List<com.zzy.llmreader.web.GlossaryEntry> {
        return text.lines()
            .map { it.trim() }
            .filter { it.contains("→") || it.contains("->") || it.contains("=>") }
            .mapNotNull { line ->
                val sep = when {
                    "→" in line -> "→"
                    "=>" in line -> "=>"
                    else -> "->"
                }
                val parts = line.split(sep, limit = 2)
                if (parts.size == 2) {
                    val orig = parts[0].trim().trimStart('-', '·', '*', '•', ' ', '\t').trim()
                    val trans = parts[1].trim()
                    // 拒绝明显是 reasoning/解释文本的条目：
                    // - 任一侧空白
                    // - 任一侧过长（专有名词通常 ≤ 30 字符）
                    // - 任一侧含句号/分号等长句标点
                    if (orig.isBlank() || trans.isBlank()) return@mapNotNull null
                    if (orig.length > 40 || trans.length > 40) return@mapNotNull null
                    if (orig.any { it in ".。;；?？!！" } || trans.any { it in ".。;；?？!！" }) return@mapNotNull null
                    com.zzy.llmreader.web.GlossaryEntry(orig, trans)
                } else null
            }
            .distinctBy { it.original }
    }

    fun saveLlmSettings(s: LlmSettings) {
        viewModelScope.launch { settingsRepo.update(s) }
    }

    fun fetchModels(settings: LlmSettings, callback: (Result<List<String>>) -> Unit) {
        viewModelScope.launch {
            val result = client.listModels(settings)
            callback(result)
        }
    }

    fun removeRecent(book: RecentBook) {
        viewModelScope.launch {
            if (book.source == RecentBookSource.WEB || book.uri.startsWith("http", ignoreCase = true)) {
                withContext(Dispatchers.IO) {
                    runCatching {
                        val canonical = webRegistry.find(book.uri)?.canonicalize(book.uri) ?: book.uri
                        webCache.delete(canonical)
                    }
                }
            }
            recentRepo.remove(book.uri)
        }
    }

    fun showAsk() = _state.update { it.copy(ask = it.ask.copy(visible = true)) }
    fun hideAsk() = _state.update { it.copy(ask = AskState()) }
    fun setQuestion(q: String) = _state.update { it.copy(ask = it.ask.copy(question = q)) }

    fun ask() {
        val cur = _state.value
        if (cur.ask.loading) return
        val ctx = currentChapterText(cur).orEmpty()
        if (ctx.isBlank()) {
            _state.update { it.copy(ask = it.ask.copy(error = "当前章节为空")) }
            return
        }
        _state.update { it.copy(ask = it.ask.copy(loading = true, error = null, answer = "")) }
        viewModelScope.launch {
            val settings = settingsRepo.settings.first()
            val q = cur.ask.question.ifBlank { "请用中文总结本章要点。" }
            val messages = listOf(
                ChatMessage(
                    role = "system",
                    content = "你是一个中文阅读助手，根据用户提供的章节内容作答，简洁准确。",
                ),
                ChatMessage(
                    role = "user",
                    content = "章节内容（截取前 6000 字）：\n${ctx.take(6000)}\n\n问题：$q",
                ),
            )
            client.chat(settings, messages)
                .onSuccess { ans ->
                    _state.update { it.copy(ask = it.ask.copy(loading = false, answer = ans)) }
                }
                .onFailure { err ->
                    _state.update {
                        it.copy(ask = it.ask.copy(loading = false, error = err.message ?: "请求失败"))
                    }
                }
        }
    }

    internal fun currentChapterText(state: ReaderUiState): String? {
        val book = state.book as? BookContent.Text ?: return null
        return book.chapters.getOrNull(state.currentChapter)?.content?.let { stripHtml(it) }
    }

    // ===================== 图片生成 (M2-M6) =====================

    /** 当前书的稳定 ID（优先使用 web canonical url）。 */
    internal fun currentBookId(): String? {
        val s = _state.value
        val title = s.book?.title ?: return null
        return s.webCanonicalUrl ?: "file:$title"
    }

    /**
     * 从 SillyTavern st-chatu8 的 settings JSON 导入图片生成配置与角色库。
     *
     * 解析逻辑放在 shared 的 [com.zzy.llmreader.image.StChatu8Importer]（纯函数，
     * 有单元测试覆盖）；这里只负责 JSON 解析的线程切换、持久化和回调。
     */
    fun importStChatu8Settings(jsonText: String, callback: (Result<String>) -> Unit) {
        viewModelScope.launch {
            val result = runCatching {
                val imported = withContext(Dispatchers.IO) {
                    com.zzy.llmreader.image.StChatu8Importer.importFromText(
                        jsonText,
                        _state.value.imageGenSettings,
                    )
                }
                imageGenRepo.update(imported.settings)
                if (imported.library.characters.isNotEmpty() || imported.library.outfits.isNotEmpty()) {
                    characterRepo.update(imported.library)
                }
                imported.summary
            }
            result.onSuccess { callback(Result.success(it)) }
                .onFailure { callback(Result.failure(it)) }
        }
    }

    companion object {
        internal const val TAG = "ReaderVM"
    }
}
