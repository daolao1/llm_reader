package com.zzy.llmreader.ui

import com.zzy.llmreader.data.ImageGenSettings
import com.zzy.llmreader.data.LlmSettings
import com.zzy.llmreader.data.ReaderPreferences
import com.zzy.llmreader.data.SettingsRepository
import com.zzy.llmreader.image.CharacterLibrary
import com.zzy.llmreader.image.ImageCache
import com.zzy.llmreader.web.DiscoveryItem
import com.zzy.llmreader.web.DiscoveryQuery
import com.zzy.llmreader.web.SimilarGroup
import com.zzy.llmreader.model.BookContent

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
    val imageGenSettings: ImageGenSettings =
        ImageGenSettings(),
    val characterLibrary: CharacterLibrary =
        CharacterLibrary(),
    /** 当前章节已生成的图片列表。 */
    val chapterImages: List<ImageCache.GeneratedImage> = emptyList(),
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
    /** 正在重新抓取网页小说的目录（连载更新后刷新用）。 */
    val tocRefreshing: Boolean = false,
)

/** 发现页 UI 状态：站点 / 当前查询 / 结果 / 加载与错误。 */
data class DiscoveryUiState(
    /** 当前选中的站点 ID；null 表示尚未选择。 */
    val siteId: String? = null,
    val query: DiscoveryQuery = DiscoveryQuery(),
    val items: List<DiscoveryItem> = emptyList(),
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
    val detailSimilarGroups: List<SimilarGroup>? = null,
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
    val similarGroups: List<SimilarGroup>? = null,
    val similarLoading: Boolean = false,
    /** 是否启用译文显示（与发现页共用同一开关）。 */
    val translateListings: Boolean = false,
)
