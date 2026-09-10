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

data class AskState(
    val visible: Boolean = false,
    val question: String = "",
    val answer: String = "",
    val loading: Boolean = false,
    val error: String? = null,
)

data class ReaderUiState(
    val book: BookContent? = null,
    val currentChapter: Int = 0,
    val fontScale: Float = 1f,
    val loading: Boolean = false,
    val loadingText: String = "解析中…",
    val error: String? = null,
    val ask: AskState = AskState(),
    /** 当前正在加载正文的章节下标集合（懒加载用）。 */
    val loadingChapters: Set<Int> = emptySet(),
    /** 当前打开的网页小说的归一化 URL；非网页小说为 null。 */
    val webCanonicalUrl: String? = null,
    val webSourceId: String? = null,
    val settings: LlmSettings = LlmSettings(
        baseUrl = SettingsRepository.DEFAULT_BASE_URL,
        apiKey = "",
        model = SettingsRepository.DEFAULT_MODEL,
    ),
    val readerPrefs: ReaderPreferences = ReaderPreferences(),
    /** 正在翻译的章节下标。 */
    val translatingChapters: Set<Int> = emptySet(),
    /** 当前章节翻译进度文本（如"翻译中 2/5"）；非翻译期间为 null。 */
    val translationProgress: String? = null,
    /** 翻译失败的章节及失败原因；UI 据此显示"重试"按钮。 */
    val failedTranslations: Map<Int, String> = emptyMap(),
    /** 检测到 Cloudflare 拦截，UI 弹 CF 验证 Activity 后清空。 */
    val pendingCloudflareUrl: String? = null,
    val imageGenSettings: com.zzy.llmreader.data.ImageGenSettings =
        com.zzy.llmreader.data.ImageGenSettings(),
    val characterLibrary: com.zzy.llmreader.image.CharacterLibrary =
        com.zzy.llmreader.image.CharacterLibrary(),
    /** 当前章节已生成的图片列表。 */
    val chapterImages: List<com.zzy.llmreader.image.ImageCache.GeneratedImage> = emptyList(),
    /** 是否正在生成图片。 */
    val generatingImage: Boolean = false,
    /** 图片生成的最近一次错误。 */
    val imageGenError: String? = null,
    /** 发现页（图书馆式浏览）状态。 */
    val discovery: DiscoveryUiState = DiscoveryUiState(),
    /** 作品详情页状态；null 表示当前未在展示详情页。 */
    val bookDetail: BookDetailUiState? = null,
    /** 当前章节首次显示时使用的初始滚动百分比（0..1），从持久化进度恢复。 */
    val initialChapterScrollFraction: Float = 0f,
)

/** 发现页 UI 状态：站点 / 当前查询 / 结果 / 加载与错误。 */
data class DiscoveryUiState(
    /** 当前选中的站点 ID；null 表示尚未选择。 */
    val siteId: String? = null,
    val query: com.zzy.llmreader.web.DiscoveryQuery = com.zzy.llmreader.web.DiscoveryQuery(),
    val items: List<com.zzy.llmreader.web.DiscoveryItem> = emptyList(),
    val hasMore: Boolean = false,
    val totalCount: Long? = null,
    val loading: Boolean = false,
    val error: String? = null,
    /** 是否对结果做翻译（前端开关 + 触发批量翻译；UI 显示原文还是译文也由它决定）。 */
    val translateListings: Boolean = false,
    /** 正在翻译的 item.id 集合（批量翻译时是当前批次中的全部 id）。 */
    val translating: Set<String> = emptySet(),
    /** 列表滚动位置（首个可见 item 的下标 / 像素偏移），用于离开后再回到 Discovery 时恢复。 */
    val scrollIndex: Int = 0,
    val scrollOffset: Int = 0,
    /** 当前打开的作品详情 item.id；null 表示未打开详情弹窗。 */
    val detailItemId: String? = null,
    /**
     * 站点原生分组的相似作品（如「相似作品」+「作者同作品」）；
     * null 表示尚未拉取，emptyList 表示已尝试但无结果。
     */
    val detailSimilarGroups: List<com.zzy.llmreader.web.SimilarGroup>? = null,
    /** 正在拉取站点原生相似作品。 */
    val detailSimilarLoading: Boolean = false,
)

/**
 * 作品详情页 UI 状态。同时支持网页小说与本地导入。
 * - 网页：[isWeb]=true，使用 [key] 作为 canonicalUrl；可拉取站点原生相似/作者作品。
 * - 本地：[isWeb]=false，使用 [key] 作为 content URI 字符串；只展示已解析的目录。
 */
data class BookDetailUiState(
    /** 入口标识：web 用 canonicalUrl，本地用 content URI 字符串。 */
    val key: String,
    val title: String,
    val isWeb: Boolean,
    /** WebSource.id；本地为 null。 */
    val sourceId: String? = null,
    /** 站点显示名（web 用，UI 友好）。 */
    val sourceName: String? = null,
    /** 入口 URL，用于「打开站点链接」。 */
    val detailUrl: String? = null,
    val translatedTitle: String? = null,
    val author: String? = null,
    val coverUrl: String? = null,
    val summary: String? = null,
    val translatedSummary: String? = null,
    val tags: List<String> = emptyList(),
    val translatedTags: List<String>? = null,
    val status: String? = null,
    val chapterCount: Int? = null,
    val wordCount: Long? = null,
    val updatedAt: Long? = null,
    val metrics: Map<String, String> = emptyMap(),
    /** 章节标题列表（cached/已解析时填充）。 */
    val chapters: List<String> = emptyList(),
    val translatedChapters: List<String?>? = null,
    /** 章节目录是否已是站点最新版本（缓存命中时为 true）。 */
    val tocFromCache: Boolean = false,
    /** 正在加载/抓取目录。 */
    val tocLoading: Boolean = false,
    val tocError: String? = null,
    /** 站点原生分组的相似作品 + 作者同作品；null 表示尚未拉取。 */
    val similarGroups: List<com.zzy.llmreader.web.SimilarGroup>? = null,
    val similarLoading: Boolean = false,
    /** 是否启用译文显示（与发现页共用同一开关）。 */
    val translateListings: Boolean = false,
)

class ReaderViewModel(app: Application) : AndroidViewModel(app) {

    /** 所有仓储 / 客户端都由 shared 的组合根提供（与桌面端是同一份实现）。 */
    private val core = (app as LlmReaderApp).core

    private val settingsRepo = core.settingsRepo
    private val readerPrefsRepo = core.readerPrefsRepo
    private val recentRepo = core.recentRepo
    private val progressRepo = core.progressRepo

    /** 当前已打开书的进度键（web 用 canonical url；本地用文件 uri）。 */
    private var currentBookKey: String? = null

    /** 每章翻译协程的 Job，切书/关书时用于取消旧书的翻译，避免结果串到新书上。 */
    private val translationJobs = mutableMapOf<Int, kotlinx.coroutines.Job>()

    private fun cancelAllTranslationJobs() {
        translationJobs.values.forEach { it.cancel() }
        translationJobs.clear()
    }
    private val client = core.llmClient
    private val translator = core.translator
    private val parser = core.documentParser
    private val webCache = core.webCache

    /** Tag 翻译缓存：key = "lang|tag-原文" → 译文。启动时从 DataStore 加载。 */
    private val tagTranslationCache: LinkedHashMap<String, String> = LinkedHashMap()

    /** 书籍信息（标题+简介）翻译缓存：key = "lang|title|||summary" → (译标题, 译简介)。 */
    private val bookInfoTranslationCache: LinkedHashMap<String, Pair<String, String>> = LinkedHashMap()

    private val discoveryTxRepo = core.discoveryTxRepo
    private val localGlossary = core.localGlossary
    private val webRegistry = core.createWebRegistry { _state.value.readerPrefs }
    private val imageGenRepo = core.imageGenRepo
    private val characterRepo = core.characterRepo
    private val imageCache = core.imageCache
    private val novelAi = core.novelAi
    private val comfyUi = core.comfyUi
    /** 序列化"读缓存→改→写缓存"，避免懒加载和预取互相覆盖。 */
    private val cacheMutex = Mutex()

    /** CF 拦截后保留的待重试上下文：拿到 cookie 后自动重做这步动作。 */
    private var pendingWebBookOpen: String? = null
    private var pendingChapterReload: Int? = null
    private var pendingDiscoveryReload: Boolean = false
    /** 详情页选中的章节：openBook/openWebBook 完成后跳到该章。 */
    private var pendingInitialChapter: Int? = null

    private val _state = MutableStateFlow(ReaderUiState())
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    val recents: StateFlow<List<RecentBook>> = recentRepo.recents.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    /** \u542f\u52a8\u65f6\u4e00\u6b21\u6027\u52a0\u8f7d\u53d1\u73b0\u9875\u7ffb\u8bd1\u7f13\u5b58\u7684 Job\uff1b\u7ffb\u8bd1\u524d\u5148 join \u7b49\u5b83\u5c31\u7eea\u3002 */
    private var discoveryCacheLoad: kotlinx.coroutines.Job? = null

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

    fun refreshCurrentWebBook() {
        val book = _state.value.book ?: return
        val recent = recents.value.firstOrNull { it.title == book.title && it.source == RecentBookSource.WEB }
            ?: return
        openWebBook(recent.uri, forceRefresh = true)
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

    /**
     * 批量翻译当前结果（标题+简介一次、tag 一次，两个接口并发）。
     * 命中缓存的不会重复请求；未命中的拼成一份提示一并发出去。
     */
    private fun translateDiscoveryBatch(items: List<com.zzy.llmreader.web.DiscoveryItem>) {
        if (items.isEmpty()) return
        // 启动后第一次走到这儿时缓存可能还没加载完——先等它，再读缓存。
        viewModelScope.launch {
            runCatching { discoveryCacheLoad?.join() }
            translateDiscoveryBatchInternal(items)
        }
    }

    private fun translateDiscoveryBatchInternal(items: List<com.zzy.llmreader.web.DiscoveryItem>) {
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
        }
    }

    private data class BookJob(val id: String, val title: String, val summary: String)

    private fun bookInfoCacheKey(lang: String, title: String, summary: String): String =
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

    private fun looksLikeTargetLanguage(text: String, target: String): Boolean {
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

    /** 把已缓存的 tag 翻译写到当前列表里（无网络请求）。 */
    private fun applyCachedTagTranslations(target: String) {
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
    private fun applyTagsForItem(
        i: com.zzy.llmreader.web.DiscoveryItem,
        target: String,
    ): List<String>? {
        if (i.tags.isEmpty()) return null
        val translated = i.tags.map { tag -> tagTranslationCache["$target|$tag"].orEmpty() }
        return if (translated.any { it.isNotBlank() }) translated else null
    }

    private fun applyBookInfoTranslation(itemId: String, title: String, summary: String) {
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
    private suspend fun translateBookInfosOnce(
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
    private suspend fun translateBookInfosChunkSafe(
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
    private suspend fun translateBookInfosChunkStream(
        settings: com.zzy.llmreader.data.LlmSettings,
        target: String,
        style: String,
        jobs: List<BookJob>,
    ): Map<String, Pair<String, String>> {
        val (jbTop, jbBottom) = translator.jailbreakPair(settings)
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

    private suspend fun translateBookInfosBatch(
        settings: com.zzy.llmreader.data.LlmSettings,
        target: String,
        style: String,
        jobs: List<BookJob>,
    ): Map<String, Pair<String, String>> = translateBookInfosChunkStream(settings, target, style, jobs)

    /** 翻译一批 tag。按 12 个一组切块，最多 3 个并发。整组被过滤则二分降级。 */
    private suspend fun translateTagsOnce(
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
    private suspend fun translateTagsChunkSafe(
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

    private suspend fun translateTagsBatch(
        settings: com.zzy.llmreader.data.LlmSettings,
        target: String,
        tags: List<String>,
    ): Map<String, String> {
        val (jbTop, jbBottom) = translator.jailbreakPair(settings)
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
        ).getOrElse {
            android.util.Log.w("ReaderVM", "translateTagsOnce chat error", it)
            throw it
        }
        val out = mutableMapOf<String, String>()
        val numRe = Regex("^\\s*(\\d+)[\\.|、)]\\s*(.+)$")
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

    private fun parseBatchTitleSummary(text: String): Map<Int, Pair<String, String>> {
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

    private fun runDiscovery(resetPage: Boolean) {
        val cur = _state.value.discovery
        val site = cur.siteId?.let { webRegistry.findById(it) } ?: return
        val discovery = site.discovery ?: return
        val caps = discovery.capabilities
        val query = if (resetPage) {
            cur.query.copy(page = caps.firstPage)
        } else {
            cur.query.copy(page = cur.query.page + 1)
        }
        // 关键字必填的站点（如 Pixiv）：没有 keyword 也没有 tag 时才给空态；
        // 只有 tag 时用第一个 tag 临时作为站点 keyword，再做本地 tag 过滤。
        if (caps.keywordRequired && query.keyword.trim().isEmpty() && query.tags.none { it.isNotBlank() }) {
            _state.update {
                it.copy(
                    discovery = it.discovery.copy(
                        query = query,
                        loading = false,
                        error = null,
                        items = emptyList(),
                        hasMore = false,
                        totalCount = null,
                    ),
                )
            }
            return
        }
        _state.update {
            it.copy(
                discovery = it.discovery.copy(
                    query = query,
                    loading = true,
                    error = null,
                    items = if (resetPage) emptyList() else it.discovery.items,
                ),
            )
        }
        viewModelScope.launch {
            val result = runCatching {
                if (query.tags.any { it.isNotBlank() }) {
                    val fetchQuery = query.withKeywordsIncludingTags()
                    discoverWithLocalTagFill(discovery, fetchQuery, query)
                } else {
                    discovery.discover(query)
                }
            }
            result.fold(
                onSuccess = { page ->
                    _state.update {
                        val merged = if (resetPage) page.items else it.discovery.items + page.items
                        it.copy(
                            discovery = it.discovery.copy(
                                query = page.query,
                                items = merged,
                                hasMore = page.hasMore,
                                totalCount = page.totalCount,
                                loading = false,
                            ),
                        )
                    }
                    if (_state.value.discovery.translateListings) {
                        translateDiscoveryBatch(page.items)
                    }
                },
                onFailure = { t ->
                    if (t is CloudflareBlockedException) {
                        pendingDiscoveryReload = true
                        _state.update {
                            it.copy(
                                discovery = it.discovery.copy(
                                    loading = false,
                                    error = "需要重新通过 Cloudflare 验证。",
                                ),
                                error = "需要重新通过 Cloudflare 验证。",
                                pendingCloudflareUrl = t.targetUrl,
                            )
                        }
                    } else {
                        _state.update {
                            it.copy(
                                discovery = it.discovery.copy(
                                    loading = false,
                                    error = t.message ?: t.javaClass.simpleName,
                                ),
                            )
                        }
                    }
                },
            )
        }
    }

    private suspend fun discoverWithLocalTagFill(
        discovery: com.zzy.llmreader.web.WebDiscovery,
        fetchQuery: com.zzy.llmreader.web.DiscoveryQuery,
        displayQuery: com.zzy.llmreader.web.DiscoveryQuery,
    ): com.zzy.llmreader.web.DiscoveryPage {
        val targetSize = displayQuery.pageSize.coerceAtLeast(1)
        val matched = mutableListOf<com.zzy.llmreader.web.DiscoveryItem>()
        var nextQuery = fetchQuery
        var lastQuery = fetchQuery
        var hasMore = true
        var totalCount: Long? = null
        var guard = 0

        while (matched.size < targetSize && hasMore && guard < 20) {
            guard += 1
            val page = discovery.discover(nextQuery)
            lastQuery = page.query
            totalCount = page.totalCount
            matched += page.items.filter { it.matchesAllLocalTags(displayQuery.tags) }
            hasMore = page.hasMore
            if (matched.size < targetSize && hasMore) {
                nextQuery = page.query.copy(page = page.query.page + 1)
            }
        }

        return com.zzy.llmreader.web.DiscoveryPage(
            query = displayQuery.copy(page = lastQuery.page),
            items = matched,
            hasMore = hasMore,
            totalCount = totalCount,
        )
    }

    private fun com.zzy.llmreader.web.DiscoveryQuery.withKeywordsIncludingTags():
        com.zzy.llmreader.web.DiscoveryQuery {
        val terms = (keyword.split(Regex("[\\s　]+")) + tags)
            .map { it.trim().trimStart('#').trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase(java.util.Locale.ROOT) }
        return copy(keyword = terms.joinToString(" "))
    }

    private fun com.zzy.llmreader.web.DiscoveryItem.matchesAllLocalTags(tags: List<String>): Boolean {
        val wanted = tags.mapNotNull { it.normalizedDiscoveryTag().takeIf(String::isNotBlank) }
        if (wanted.isEmpty()) return true
        val haystack = this.tags.map { it.normalizedDiscoveryTag() }
        return wanted.all { needle ->
            haystack.any { candidate -> candidate == needle || candidate.contains(needle) }
        }
    }

    private fun String.normalizedDiscoveryTag(): String =
        trim().trimStart('#').trim().lowercase(java.util.Locale.ROOT)

    /** 选中某条 item → 走正常的"网页书"打开流程。 */
    fun openDiscoveryItem(itemId: String) {
        val cur = _state.value.discovery
        val site = cur.siteId?.let { webRegistry.findById(it) } ?: return
        val discovery = site.discovery ?: return
        // 列表里没有时回退到详情弹窗当前的相似作品分组（站点原生推荐结果）。
        val item = cur.items.firstOrNull { it.id == itemId }
            ?: cur.detailSimilarGroups?.asSequence()
                ?.flatMap { it.items.asSequence() }
                ?.firstOrNull { it.id == itemId }
            ?: return
        // 关闭详情弹窗（如果是从详情按"开始阅读"进来的）
        _state.update {
            it.copy(
                discovery = it.discovery.copy(
                    detailItemId = null,
                    detailSimilarGroups = null,
                    detailSimilarLoading = false,
                ),
            )
        }
        viewModelScope.launch {
            val resolved = runCatching { discovery.resolveDetailUrl(item) }.getOrNull()
                ?: item.detailUrl
            openWebBook(resolved)
        }
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

    /**
     * 翻译指定章节。已翻译且语言匹配则跳过；否则调用 LLM 流式翻译并写回缓存。
     * 支持任意来源的书（网页 / 本地 EPUB / TXT）：网页书额外持久化到磁盘。
     */
    fun ensureChapterTranslated(index: Int) {
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
    fun retryChapterTranslation(index: Int) {
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
    private fun markTranslationFailed(bookKey: String?, index: Int, message: String) {
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
    private fun applyPartialTranslation(bookKey: String?, index: Int, title: String?, html: String) {
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

    /** 粗略剥离 HTML 标签，仅供语言检测使用，最长截 4000 字符够判断。 */
    private fun stripHtml(html: String): String =
        html.replace(Regex("<[^>]+>"), " ").take(4000)

    /** 节流：每 250ms 至多 flush 一次部分译文到 UI。 */
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

    private suspend fun refreshBookFromCache(canonical: String) {
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
        if (tx.isEmpty()) return
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
        val (jbTop, jbBottom) = translator.jailbreakPair(settings)
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
        ).getOrElse { throw it }
        val out = mutableMapOf<Int, String>()
        val numRe = Regex("^\\s*(\\d+)[\\.\\)、]\\s*(.+)$")
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
    fun saveGlossary(entries: List<com.zzy.llmreader.web.GlossaryEntry>) {
        val cleaned = entries.filter { e ->
            e.original.isNotBlank() && e.translated.isNotBlank() &&
                e.original.length <= 40 && e.translated.length <= 40 &&
                e.original.none { it in ".。;；?？!！" } &&
                e.translated.none { it in ".。;；?？!！" }
        }
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
                if (deduped.isNotBlank()) {
                    val dedupedEntries = parseGlossaryText(deduped)
                    if (dedupedEntries.isNotEmpty()) {
                        if (canonical != null) {
                            cacheMutex.withLock {
                                val cached = withContext(Dispatchers.IO) { webCache.read(canonical) }
                                    ?: return@withLock
                                val updated = cached.copy(glossary = dedupedEntries)
                                withContext(Dispatchers.IO) { webCache.write(updated) }
                            }
                        } else {
                            withContext(Dispatchers.IO) { localGlossary.write(localKey!!, dedupedEntries) }
                        }
                        dedupedEntries
                    } else current
                } else current
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
    private fun parseGlossaryText(text: String): List<com.zzy.llmreader.web.GlossaryEntry> {
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

    private fun currentChapterText(state: ReaderUiState): String? {
        val book = state.book as? BookContent.Text ?: return null
        return book.chapters.getOrNull(state.currentChapter)?.content?.let { stripHtml(it) }
    }

    // ===================== 图片生成 (M2-M6) =====================

    /** 当前书的稳定 ID（优先使用 web canonical url）。 */
    private fun currentBookId(): String? {
        val s = _state.value
        val title = s.book?.title ?: return null
        return s.webCanonicalUrl ?: "file:$title"
    }

    /** 加载当前章节已生成的图片列表，刷新到 state。 */
    fun refreshChapterImages() {
        val bookId = currentBookId() ?: return
        val idx = _state.value.currentChapter
        viewModelScope.launch {
            val list = imageCache.list(bookId, idx)
            _state.update { it.copy(chapterImages = list) }
        }
    }

    fun saveImageGenSettings(s: com.zzy.llmreader.data.ImageGenSettings) {
        viewModelScope.launch { imageGenRepo.update(s) }
    }

    fun saveCharacterLibrary(lib: com.zzy.llmreader.image.CharacterLibrary) {
        viewModelScope.launch { characterRepo.update(lib) }
    }

    fun clearImageGenError() {
        if (_state.value.imageGenError != null) {
            _state.update { it.copy(imageGenError = null) }
        }
    }

    /**
     * 用 LLM 从当前章节抽取场景，输出 Danbooru 风格的英文 tag。
     */
    fun extractScenePrompt(callback: (Result<String>) -> Unit) {
        val cur = _state.value
        val ctx = currentChapterText(cur).orEmpty()
        if (ctx.isBlank()) {
            callback(Result.failure(IllegalStateException("当前章节为空")))
            return
        }
        viewModelScope.launch {
            val settings = settingsRepo.settings.first()
            val excerpt = ctx.take(4000)
            val msgs = listOf(
                ChatMessage(
                    role = "system",
                    content = "你是 Danbooru 风格的图像生成 prompt 工程师。" +
                        "用户给你一段小说章节正文，你要选出最具画面感的一个场景（人物、动作、环境、氛围），" +
                        "输出英文逗号分隔的 tag 序列；不要任何中文，不要句子，不要解释，不要 NSFW 触发词；" +
                        "20-40 个 tag 之间。",
                ),
                ChatMessage(role = "user", content = "章节正文：\n$excerpt\n\n请输出 tag 序列。"),
            )
            client.chat(settings, msgs)
                .map { raw ->
                    raw.lineSequence()
                        .map { it.trim() }
                        .filter { it.isNotBlank() && !it.startsWith("#") }
                        .joinToString(", ")
                        .replace(Regex("\\s*,\\s*"), ", ")
                        .trim(',', ' ')
                }
                .let(callback)
        }
    }

    /** 触发一次图片生成，结果（成功时）已经保存进 ImageCache，会自动刷新 chapterImages。 */
    fun generateImage(positivePrompt: String, callback: (Result<com.zzy.llmreader.image.ImageCache.GeneratedImage>) -> Unit) {
        val cur = _state.value
        val bookId = currentBookId()
        if (bookId == null) {
            callback(Result.failure(IllegalStateException("请先打开一本书")))
            return
        }
        val backend = cur.imageGenSettings.backend
        if (backend == com.zzy.llmreader.data.ImageBackend.NONE) {
            callback(Result.failure(IllegalStateException("请先在「图片生成 → 设置」选择后端 (NovelAI / ComfyUI)")))
            return
        }
        val idx = cur.currentChapter
        val sceneText = currentChapterText(cur).orEmpty()
        // 自动追加角色相关的外观/穿着
        val charAddon = characterRepo.buildAutoPromptAddon(cur.characterLibrary, sceneText)
        val finalPrompt = listOf(positivePrompt, charAddon).filter { it.isNotBlank() }.joinToString(", ")

        _state.update { it.copy(generatingImage = true, imageGenError = null) }
        viewModelScope.launch {
            val result = runCatching {
                val s = cur.imageGenSettings
                when (backend) {
                    com.zzy.llmreader.data.ImageBackend.NOVELAI ->
                        novelAi.generate(s, finalPrompt) to (s.novelaiWidth to s.novelaiHeight)
                    com.zzy.llmreader.data.ImageBackend.COMFYUI ->
                        comfyUi.generate(s, finalPrompt) to (s.comfyuiWidth to s.comfyuiHeight)
                    com.zzy.llmreader.data.ImageBackend.NONE -> error("unreachable")
                }
            }.mapCatching { (bytes, size) ->
                imageCache.save(
                    bookId = bookId,
                    chapterIndex = idx,
                    bytes = bytes,
                    prompt = finalPrompt,
                    negative = when (backend) {
                        com.zzy.llmreader.data.ImageBackend.NOVELAI -> cur.imageGenSettings.novelaiNegativePrompt
                        com.zzy.llmreader.data.ImageBackend.COMFYUI -> cur.imageGenSettings.comfyuiNegativePrompt
                        else -> ""
                    },
                    backend = backend,
                    width = size.first,
                    height = size.second,
                )
            }
            _state.update { it.copy(generatingImage = false) }
            result.onSuccess { entry ->
                val list = imageCache.list(bookId, idx)
                _state.update { it.copy(chapterImages = list) }
                callback(Result.success(entry))
            }.onFailure { err ->
                Log.e(TAG, "image generation failed", err)
                _state.update { it.copy(imageGenError = err.message) }
                callback(Result.failure(err))
            }
        }
    }

    fun deleteGeneratedImage(id: String) {
        val bookId = currentBookId() ?: return
        val idx = _state.value.currentChapter
        viewModelScope.launch {
            imageCache.delete(bookId, idx, id)
            val list = imageCache.list(bookId, idx)
            _state.update { it.copy(chapterImages = list) }
        }
    }

    /** 给定一个 prompt，查找该 prompt 在当前书内是否已生成过图片，返回本地文件。 */
    suspend fun findCachedImageByPrompt(prompt: String): java.io.File? {
        val bookId = currentBookId() ?: return null
        return imageCache.fileForPrompt(bookId, prompt)
    }

    /**
     * 用于"在文中插入插图"：根据 prompt 直接生成一张，不依赖当前章节已生成列表。
     * 完成后会刷新当前章节的图片列表（如果生成的是同一章），方便面板/按 prompt 查找。
     */
    fun generateInlineImage(prompt: String, callback: (Result<com.zzy.llmreader.image.ImageCache.GeneratedImage>) -> Unit) {
        // 直接复用 generateImage 即可：它已经把图片写到当前章节目录下，
        // findCachedImageByPrompt 跨章节查找时也能命中。
        generateImage(prompt, callback)
    }

    /**
     * 批量生成本章所有 [IMG: ...] 标记对应的插图。
     * - 已缓存（按 prompt 命中）的会跳过
     * - 串行执行，避免后端被打爆
     * - 通过 [onProgress] 上报 (current, total, currentPrompt)
     * - 通过 [onDone] 上报 (success, failed)
     */
    fun generateAllInlinePrompts(
        prompts: List<String>,
        onProgress: (current: Int, total: Int, prompt: String) -> Unit = { _, _, _ -> },
        onDone: (success: Int, failed: Int) -> Unit = { _, _ -> },
    ) {
        val bookId = currentBookId()
        if (bookId == null) {
            onDone(0, prompts.size)
            return
        }
        val backend = _state.value.imageGenSettings.backend
        if (backend == com.zzy.llmreader.data.ImageBackend.NONE) {
            _state.update { it.copy(imageGenError = "请先在「图片生成 → 设置」选择后端") }
            onDone(0, prompts.size)
            return
        }
        viewModelScope.launch {
            val unique = prompts.distinct()
            var ok = 0
            var fail = 0
            for ((i, p) in unique.withIndex()) {
                onProgress(i + 1, unique.size, p)
                // 已缓存则跳过
                val existing = imageCache.fileForPrompt(bookId, p)
                if (existing != null) {
                    ok++
                    continue
                }
                val result = kotlinx.coroutines.suspendCancellableCoroutine<Result<com.zzy.llmreader.image.ImageCache.GeneratedImage>> { cont ->
                    generateImage(p) { r -> if (cont.isActive) cont.resumeWith(Result.success(r)) }
                }
                if (result.isSuccess) ok++ else fail++
            }
            onDone(ok, fail)
        }
    }

    /** 获取一张已保存图片的本地文件路径。 */
    fun imageFilePath(filename: String): String? {
        val bookId = currentBookId() ?: return null
        val idx = _state.value.currentChapter
        return imageCache.fileFor(bookId, idx, filename).absolutePath
    }

    /**
     * 从 SillyTavern st-chatu8 的 settings JSON 中导入图片生成相关配置。
     * 仅取我们当前支持的字段；其余忽略。
     */
    fun importStChatu8Settings(jsonText: String, callback: (Result<String>) -> Unit) {
        viewModelScope.launch {
            val result = runCatching {
                val root = withContext(Dispatchers.IO) {
                    kotlinx.serialization.json.Json.parseToJsonElement(jsonText)
                } as? kotlinx.serialization.json.JsonObject
                    ?: error("不是合法的 JSON 对象")
                // 兼容两种格式：
                //  1. 顶层就是 st-chatu8 的 settings（用户从插件页面"导出当前配置"得到的格式）
                //  2. 整个 SillyTavern settings.json，包在 extension_settings['st-chatu8'] 里
                val sc = (root["extension_settings"] as? kotlinx.serialization.json.JsonObject)
                    ?.get("st-chatu8") as? kotlinx.serialization.json.JsonObject
                    ?: root // 直接用顶层
                applyStChatu8(sc)
            }
            result.onSuccess { msg ->
                callback(Result.success(msg))
            }.onFailure { callback(Result.failure(it)) }
        }
    }

    private suspend fun applyStChatu8(sc: kotlinx.serialization.json.JsonObject): String {
        val cur = _state.value.imageGenSettings
        fun str(key: String) = (sc[key] as? kotlinx.serialization.json.JsonPrimitive)?.content
        fun num(key: String) = str(key)?.toDoubleOrNull()
        fun int(key: String) = str(key)?.toIntOrNull()
        fun bool(key: String) = str(key)?.let {
            when (it.lowercase()) {
                "true", "1", "yes" -> true
                "false", "0", "no", "" -> false
                else -> null
            }
        }

        // 后端选择：client=jiuguan 视为 ComfyUI（jiuguan 是九馆代理 ComfyUI 的方式），
        // client=novelai → NovelAI；其他保持原值。
        val client = str("client") ?: ""
        val backend = when (client) {
            "novelai" -> com.zzy.llmreader.data.ImageBackend.NOVELAI
            "comfy", "comfyui", "jiuguan" -> com.zzy.llmreader.data.ImageBackend.COMFYUI
            else -> if (cur.backend == com.zzy.llmreader.data.ImageBackend.NONE) {
                com.zzy.llmreader.data.ImageBackend.NOVELAI
            } else cur.backend
        }

        val site = when (str("novelaisite")) {
            "官网", "official", "main" -> "official"
            null -> cur.novelaiSite
            else -> "custom"
        }

        var newSettings = cur.copy(
            backend = backend,
            novelaiApiKey = str("novelaiApi") ?: cur.novelaiApiKey,
            novelaiSite = site,
            novelaiCustomUrl = str("novelaiOtherSite") ?: cur.novelaiCustomUrl,
            novelaiModel = str("novelaimode") ?: cur.novelaiModel,
            novelaiSampler = str("novelai_sampler") ?: cur.novelaiSampler,
            novelaiSteps = int("novelai_steps") ?: cur.novelaiSteps,
            novelaiWidth = int("novelai_width") ?: cur.novelaiWidth,
            novelaiHeight = int("novelai_height") ?: cur.novelaiHeight,
            novelaiSeed = str("novelai_seed")?.toLongOrNull() ?: cur.novelaiSeed,
            novelaiScale = num("nai3Scale") ?: cur.novelaiScale,
            novelaiDecrisper = bool("nai3Deceisp") ?: cur.novelaiDecrisper,
            novelaiVarietyBoost = bool("nai3Variety") ?: cur.novelaiVarietyBoost,
            comfyuiUrl = str("comfyuiUrl") ?: cur.comfyuiUrl,
            comfyuiSampler = str("comfyuisamplerName") ?: cur.comfyuiSampler,
            comfyuiScheduler = str("comfyui_scheduler") ?: cur.comfyuiScheduler,
            comfyuiCfg = num("cfg_comfyui") ?: cur.comfyuiCfg,
            comfyuiSteps = int("comfyui_steps") ?: cur.comfyuiSteps,
            comfyuiWidth = int("comfyui_width") ?: cur.comfyuiWidth,
            comfyuiHeight = int("comfyui_height") ?: cur.comfyuiHeight,
            comfyuiSeed = str("comfyui_seed")?.toLongOrNull() ?: cur.comfyuiSeed,
        )

        // ComfyUI 工作流：优先用当前激活的 workerid，否则取 workers map 第一个。
        // workers 里有些条目可能不是工作流（例如 "links"/"last_link_id"），跳过。
        val workersMap = sc["workers"] as? kotlinx.serialization.json.JsonObject
        var wfCount = 0
        if (workersMap != null) {
            val activeName = str("workerid")
            val candidates = listOfNotNull(activeName) + workersMap.keys
            val pick = candidates.firstOrNull { name ->
                val v = workersMap[name]
                val text = (v as? kotlinx.serialization.json.JsonPrimitive)?.content
                    ?: v?.toString()
                text != null && text.contains("class_type")
            }
            wfCount = workersMap.values.count {
                val t = (it as? kotlinx.serialization.json.JsonPrimitive)?.content ?: it.toString()
                t.contains("class_type")
            }
            if (pick != null) {
                val raw = workersMap[pick]
                val wfJson = (raw as? kotlinx.serialization.json.JsonPrimitive)?.content
                    ?: raw?.toString()
                if (wfJson != null) {
                    newSettings = newSettings.copy(
                        comfyuiWorkflowName = pick,
                        comfyuiWorkflowJson = wfJson,
                    )
                }
            }
        }

        imageGenRepo.update(newSettings)

        val charLib = parseStChatu8Characters(sc)
        if (charLib.characters.isNotEmpty() || charLib.outfits.isNotEmpty()) {
            characterRepo.update(charLib)
        }

        return buildString {
            append("已导入：后端=${backend.name}")
            if (charLib.characters.isNotEmpty()) append(", 角色 ${charLib.characters.size} 个")
            if (charLib.outfits.isNotEmpty()) append(", 装束 ${charLib.outfits.size} 个")
            if (wfCount > 0) append(", ComfyUI 工作流 $wfCount 个（已选 \"${newSettings.comfyuiWorkflowName}\"）")
        }
    }

    private fun parseStChatu8Characters(sc: kotlinx.serialization.json.JsonObject): com.zzy.llmreader.image.CharacterLibrary {
        // st-chatu8 格式：characterPresets / outfitPresets 是 dict<name, dict>。
        // 也兼容 jueselist / outfitslist / characters / outfits 的列表写法。
        fun extractDict(key: String): List<Pair<String, kotlinx.serialization.json.JsonObject>> {
            val node = sc[key] ?: return emptyList()
            return when (node) {
                is kotlinx.serialization.json.JsonObject -> node.entries.mapNotNull { (k, v) ->
                    (v as? kotlinx.serialization.json.JsonObject)?.let { k to it }
                }
                is kotlinx.serialization.json.JsonArray -> node.mapIndexedNotNull { i, v ->
                    (v as? kotlinx.serialization.json.JsonObject)?.let { o ->
                        val name = (o["name"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                            ?: i.toString()
                        name to o
                    }
                }
                else -> emptyList()
            }
        }
        fun JsonElementGet(o: kotlinx.serialization.json.JsonObject, vararg keys: String): String? {
            for (k in keys) {
                val v = o[k]
                val s = (v as? kotlinx.serialization.json.JsonPrimitive)?.content
                if (!s.isNullOrBlank()) return s
            }
            return null
        }

        val outfitsRaw = extractDict("outfitPresets") + extractDict("outfitslist") + extractDict("outfits")
        val outfits = outfitsRaw.distinctBy { it.first }.map { (name, o) ->
            com.zzy.llmreader.image.OutfitPreset(
                id = JsonElementGet(o, "id") ?: "o_$name",
                name = JsonElementGet(o, "name") ?: name,
                prompt = JsonElementGet(o, "prompt", "tags", "positive", "content") ?: "",
                notes = JsonElementGet(o, "notes", "description") ?: "",
            )
        }.filter { it.name.isNotBlank() }

        val charsRaw = extractDict("characterPresets") + extractDict("jueselist") + extractDict("characters")
        val chars = charsRaw.distinctBy { it.first }.map { (name, c) ->
            val aliasArr = c["aliases"] as? kotlinx.serialization.json.JsonArray
            com.zzy.llmreader.image.CharacterPreset(
                id = JsonElementGet(c, "id") ?: "c_$name",
                name = JsonElementGet(c, "name") ?: name,
                englishName = JsonElementGet(c, "english", "en", "englishName") ?: "",
                aliases = aliasArr?.mapNotNull {
                    (it as? kotlinx.serialization.json.JsonPrimitive)?.content
                } ?: emptyList(),
                appearancePrompt = JsonElementGet(c, "appearance", "prompt", "tags", "positive", "content") ?: "",
                defaultOutfitId = JsonElementGet(c, "defaultOutfit", "outfit", "outfitId"),
                notes = JsonElementGet(c, "notes", "description") ?: "",
            )
        }.filter { it.name.isNotBlank() }

        return com.zzy.llmreader.image.CharacterLibrary(characters = chars, outfits = outfits)
    }

    companion object {
        private const val TAG = "ReaderVM"
    }
}

