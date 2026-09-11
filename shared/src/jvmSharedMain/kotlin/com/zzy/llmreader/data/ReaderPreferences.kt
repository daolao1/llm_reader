package com.zzy.llmreader.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 译文显示模式。 */
enum class TranslationMode { ORIGINAL, TRANSLATED, BILINGUAL }

/** 阅读主题（页面色彩）。 */
enum class ReadingTheme { DAY, SEPIA, NIGHT }

/** 全局应用主题，决定整个 App（书库/发现/设置/阅读外壳）走亮色还是暗色。 */
enum class AppTheme {
    /** 跟随系统。 */
    SYSTEM,
    /** 强制亮色。 */
    LIGHT,
    /** 强制暗色。 */
    DARK,
}

/** 阅读正文使用的字体。基于系统自带 family，避免内置字体资源。 */
enum class ReaderFontFamily(val displayName: String) {
    SYSTEM("默认"),
    SERIF("衬线 / Serif"),
    SANS_SERIF("无衬线 / Sans"),
    MONOSPACE("等宽 / Mono"),
}

/** 阅读相关本地偏好（与 LLM 配置分开，避免互相影响）。 */
data class ReaderPreferences(
    /** 网页小说翻到当前章节后，后台预取的章节数（0 表示关闭预取）。 */
    val webPrefetchAhead: Int = DEFAULT_PREFETCH_AHEAD,
    /** 翻译目标语言（人类可读，作为提示词中的"翻译成 X"）。 */
    val translationLanguage: String = DEFAULT_TRANSLATION_LANGUAGE,
    /** 翻译风格说明（如"口语化/轻小说风格/正式文学"），追加到翻译 system prompt。 */
    val translationStyle: String = "",
    /** 阅读时默认的展示模式。 */
    val translationMode: TranslationMode = TranslationMode.ORIGINAL,
    /** 阅读页面主题。 */
    val readingTheme: ReadingTheme = ReadingTheme.DAY,
    /** 全局 App 主题（白天/夜间/跟随系统）。 */
    val appTheme: AppTheme = AppTheme.SYSTEM,
    /** 阅读正文字体。 */
    val readerFontFamily: ReaderFontFamily = ReaderFontFamily.SYSTEM,
    /** 每翻译多少批后对术语表做一次 LLM 智能去重（0 表示关闭）。 */
    val glossaryDedupeInterval: Int = DEFAULT_GLOSSARY_DEDUPE_INTERVAL,
    /** 术语表最大条目数，超过时按 LRU 淘汰最旧的。 */
    val glossaryMaxSize: Int = DEFAULT_GLOSSARY_MAX_SIZE,
    /** 翻译时每批最大字符数（按段落整段聚合，不切句）。越大整章请求次数越少，但单次输出越长越易截断。 */
    val translationBatchChars: Int = DEFAULT_TRANSLATION_BATCH_CHARS,
    /**
     * 同时有多少个批次请求在途（1 = 完全串行）。
     *
     * 默认 1：一个 API Key 是否允许并发、以及限流额度，只有用户自己知道，所以不替用户
     * 做主。>1 时第 k 批只需等第 (k - concurrency + 1) 批翻完，用"前文译文取自更早一批"
     * 换取真实并行（实测 4 批 / 3 路并发端到端 852ms → 422ms）。
     */
    val translationConcurrency: Int = DEFAULT_TRANSLATION_CONCURRENCY,
    /** Pixiv 登录 cookie（PHPSESSID），为空时只能拉取全年龄小说。 */
    val pixivPhpSessId: String = "",
    /** Hameln (syosetu.org) 可选 cookie：如被 Cloudflare 拦截时粘贴 cf_clearance 等。 */
    val hamelnCookie: String = "",
    /** Hameln 过 CF 时用的 User-Agent；cf_clearance 与 UA 绑定。 */
    val hamelnUserAgent: String = "",
    /** ScribbleHub 可选 cookie（cf_clearance 等）。 */
    val scribblehubCookie: String = "",
    /** ScribbleHub 过 CF 时用的 User-Agent；cf_clearance 与 UA 绑定。 */
    val scribblehubUserAgent: String = "",
    /** AO3 可选 cookie：被 Cloudflare 拦截时粘贴 cf_clearance；模块内部已自动带 view_adult=true。 */
    val ao3Cookie: String = "",
    /** AO3 过 CF 时用的 User-Agent；cf_clearance 与 UA 绑定。 */
    val ao3UserAgent: String = "",
    /** 发现页「翻译标题/简介」开关是否默认打开。 */
    val discoveryTranslateOn: Boolean = false,
) {
    companion object {
        const val DEFAULT_PREFETCH_AHEAD = 3
        const val MAX_PREFETCH_AHEAD = 20
        const val DEFAULT_TRANSLATION_LANGUAGE = "简体中文"
        const val DEFAULT_GLOSSARY_DEDUPE_INTERVAL = 0
        const val DEFAULT_GLOSSARY_MAX_SIZE = 80
        const val DEFAULT_TRANSLATION_BATCH_CHARS = 4000
        const val MIN_TRANSLATION_BATCH_CHARS = 500
        const val MAX_TRANSLATION_BATCH_CHARS = 12000
        /** 保守默认：串行。并发会同时向同一个 API 地址发多个请求。 */
        const val DEFAULT_TRANSLATION_CONCURRENCY = 1
        /** 与 ChapterTranslator.MAX_TRANSLATION_CONCURRENCY 保持一致。 */
        const val MAX_TRANSLATION_CONCURRENCY = 4
    }
}

class ReaderPreferencesRepository(private val store: DataStore<Preferences>) {

    private val keyPrefetch = intPreferencesKey("web_prefetch_ahead")
    private val keyTransLang = stringPreferencesKey("translation_language")
    private val keyTransStyle = stringPreferencesKey("translation_style")
    private val keyTransMode = stringPreferencesKey("translation_mode")
    private val keyReadingTheme = stringPreferencesKey("reading_theme")
    private val keyAppTheme = stringPreferencesKey("app_theme")
    private val keyReaderFontFamily = stringPreferencesKey("reader_font_family")
    private val keyGlossaryDedupe = intPreferencesKey("glossary_dedupe_interval")
    private val keyGlossaryMaxSize = intPreferencesKey("glossary_max_size")
    private val keyBatchChars = intPreferencesKey("translation_batch_chars")
    private val keyConcurrency = intPreferencesKey("translation_concurrency")
    private val keyPixivCookie = stringPreferencesKey("pixiv_phpsessid")
    private val keyHamelnCookie = stringPreferencesKey("hameln_cookie")
    private val keyHamelnUserAgent = stringPreferencesKey("hameln_user_agent")
    private val keyScribblehubCookie = stringPreferencesKey("scribblehub_cookie")
    private val keyScribblehubUserAgent = stringPreferencesKey("scribblehub_user_agent")
    private val keyAo3Cookie = stringPreferencesKey("ao3_cookie")
    private val keyAo3UserAgent = stringPreferencesKey("ao3_user_agent")
    private val keyDiscoveryTranslateOn = androidx.datastore.preferences.core.booleanPreferencesKey("discovery_translate_on")

    val preferences: Flow<ReaderPreferences> = store.data.map { prefs ->
        ReaderPreferences(
            webPrefetchAhead = (prefs[keyPrefetch] ?: ReaderPreferences.DEFAULT_PREFETCH_AHEAD)
                .coerceIn(0, ReaderPreferences.MAX_PREFETCH_AHEAD),
            translationLanguage = prefs[keyTransLang]
                ?.takeIf { it.isNotBlank() }
                ?: ReaderPreferences.DEFAULT_TRANSLATION_LANGUAGE,
            translationStyle = prefs[keyTransStyle] ?: "",
            translationMode = prefs[keyTransMode]
                ?.let { runCatching { TranslationMode.valueOf(it) }.getOrNull() }
                ?: TranslationMode.ORIGINAL,
            readingTheme = prefs[keyReadingTheme]
                ?.let { runCatching { ReadingTheme.valueOf(it) }.getOrNull() }
                ?: ReadingTheme.DAY,
            appTheme = prefs[keyAppTheme]
                ?.let { runCatching { AppTheme.valueOf(it) }.getOrNull() }
                ?: AppTheme.SYSTEM,
            readerFontFamily = prefs[keyReaderFontFamily]
                ?.let { runCatching { ReaderFontFamily.valueOf(it) }.getOrNull() }
                ?: ReaderFontFamily.SYSTEM,
            glossaryDedupeInterval = prefs[keyGlossaryDedupe]
                ?: ReaderPreferences.DEFAULT_GLOSSARY_DEDUPE_INTERVAL,
            glossaryMaxSize = prefs[keyGlossaryMaxSize]
                ?: ReaderPreferences.DEFAULT_GLOSSARY_MAX_SIZE,
            translationBatchChars = (prefs[keyBatchChars]
                ?: ReaderPreferences.DEFAULT_TRANSLATION_BATCH_CHARS)
                .coerceIn(
                    ReaderPreferences.MIN_TRANSLATION_BATCH_CHARS,
                    ReaderPreferences.MAX_TRANSLATION_BATCH_CHARS,
                ),
            translationConcurrency = (prefs[keyConcurrency]
                ?: ReaderPreferences.DEFAULT_TRANSLATION_CONCURRENCY)
                .coerceIn(1, ReaderPreferences.MAX_TRANSLATION_CONCURRENCY),
            pixivPhpSessId = prefs[keyPixivCookie].orEmpty(),
            hamelnCookie = prefs[keyHamelnCookie].orEmpty(),
            hamelnUserAgent = prefs[keyHamelnUserAgent].orEmpty(),
            scribblehubCookie = prefs[keyScribblehubCookie].orEmpty(),
            scribblehubUserAgent = prefs[keyScribblehubUserAgent].orEmpty(),
            ao3Cookie = prefs[keyAo3Cookie].orEmpty(),
            ao3UserAgent = prefs[keyAo3UserAgent].orEmpty(),
            discoveryTranslateOn = prefs[keyDiscoveryTranslateOn] ?: false,
        )
    }

    suspend fun update(prefs: ReaderPreferences) {
        store.edit { p ->
            p[keyPrefetch] = prefs.webPrefetchAhead
                .coerceIn(0, ReaderPreferences.MAX_PREFETCH_AHEAD)
            p[keyTransLang] = prefs.translationLanguage.ifBlank {
                ReaderPreferences.DEFAULT_TRANSLATION_LANGUAGE
            }
            p[keyTransStyle] = prefs.translationStyle
            p[keyTransMode] = prefs.translationMode.name
            p[keyReadingTheme] = prefs.readingTheme.name
            p[keyAppTheme] = prefs.appTheme.name
            p[keyReaderFontFamily] = prefs.readerFontFamily.name
            p[keyGlossaryDedupe] = prefs.glossaryDedupeInterval
            p[keyGlossaryMaxSize] = prefs.glossaryMaxSize
            p[keyBatchChars] = prefs.translationBatchChars
                .coerceIn(
                    ReaderPreferences.MIN_TRANSLATION_BATCH_CHARS,
                    ReaderPreferences.MAX_TRANSLATION_BATCH_CHARS,
                )
            p[keyConcurrency] = prefs.translationConcurrency
                .coerceIn(1, ReaderPreferences.MAX_TRANSLATION_CONCURRENCY)
            p[keyPixivCookie] = prefs.pixivPhpSessId.trim()
            p[keyHamelnCookie] = prefs.hamelnCookie.trim()
            p[keyHamelnUserAgent] = prefs.hamelnUserAgent.trim()
            p[keyScribblehubCookie] = prefs.scribblehubCookie.trim()
            p[keyScribblehubUserAgent] = prefs.scribblehubUserAgent.trim()
            p[keyAo3Cookie] = prefs.ao3Cookie.trim()
            p[keyAo3UserAgent] = prefs.ao3UserAgent.trim()
            p[keyDiscoveryTranslateOn] = prefs.discoveryTranslateOn
        }
    }
}
