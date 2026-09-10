package com.zzy.llmreader

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.zzy.llmreader.image.CharacterPresetRepository
import com.zzy.llmreader.data.DiscoveryTranslationCacheRepository
import com.zzy.llmreader.data.ImageGenSettingsRepository
import com.zzy.llmreader.data.LocalGlossaryRepository
import com.zzy.llmreader.data.ReaderPreferences
import com.zzy.llmreader.data.ReaderPreferencesRepository
import com.zzy.llmreader.data.ReadingProgressRepository
import com.zzy.llmreader.data.RecentBooksRepository
import com.zzy.llmreader.data.SettingsRepository
import com.zzy.llmreader.data.createAppDataStore
import com.zzy.llmreader.format.DocumentParser
import com.zzy.llmreader.image.ComfyUiClient
import com.zzy.llmreader.image.ImageCache
import com.zzy.llmreader.image.NovelAiClient
import com.zzy.llmreader.llm.ChapterTranslator
import com.zzy.llmreader.llm.LlmClient
import com.zzy.llmreader.platform.AppDirs
import com.zzy.llmreader.web.WebBookCacheRepository
import com.zzy.llmreader.web.WebSourceRegistry

/**
 * shared 模块的**组合根**：Android 与桌面端各自的入口各构造一个实例，
 * 之后 UI 层只依赖它，不再自己 new 仓储、也不再需要 `Context`。
 *
 * 平台差异被压缩成构造参数里的 [AppDirs] 一项：
 *  - Android：`AppDirs(context.filesDir, context.cacheDir)`
 *  - 桌面端：`AppDirs(~/.llm_reader, ~/.llm_reader/cache)`
 */
class LlmReaderCore(dirs: AppDirs) {

    /** 与 Android 旧版 `preferencesDataStore("settings")` 同路径，升级不丢配置。 */
    val preferences: DataStore<Preferences> = createAppDataStore(dirs)

    val settingsRepo = SettingsRepository(preferences)
    val readerPrefsRepo = ReaderPreferencesRepository(preferences)
    val recentRepo = RecentBooksRepository(preferences)
    val progressRepo = ReadingProgressRepository(preferences)
    val discoveryTxRepo = DiscoveryTranslationCacheRepository(preferences)
    val imageGenRepo = ImageGenSettingsRepository(preferences)
    val characterRepo = CharacterPresetRepository(preferences)

    val localGlossary = LocalGlossaryRepository(dirs)
    val imageCache = ImageCache(dirs)
    val webCache = WebBookCacheRepository(dirs)

    val llmClient = LlmClient()
    val translator = ChapterTranslator(llmClient)
    val documentParser = DocumentParser()
    val novelAi = NovelAiClient()
    val comfyUi = ComfyUiClient()

    /**
     * 网页源注册表。cookie / UA 由调用方按需提供（Android 从 ViewModel 状态里取，
     * 桌面端从设置里取），shared 不关心它们存在哪。
     */
    fun createWebRegistry(prefsProvider: () -> ReaderPreferences): WebSourceRegistry =
        WebSourceRegistry(
            pixivCookieProvider = { prefsProvider().pixivPhpSessId.takeIf { it.isNotBlank() } },
            hamelnCookieProvider = { prefsProvider().hamelnCookie.takeIf { it.isNotBlank() } },
            hamelnUserAgentProvider = { prefsProvider().hamelnUserAgent.takeIf { it.isNotBlank() } },
            scribblehubCookieProvider = { prefsProvider().scribblehubCookie.takeIf { it.isNotBlank() } },
            scribblehubUserAgentProvider = { prefsProvider().scribblehubUserAgent.takeIf { it.isNotBlank() } },
            ao3CookieProvider = { prefsProvider().ao3Cookie.takeIf { it.isNotBlank() } },
            ao3UserAgentProvider = { prefsProvider().ao3UserAgent.takeIf { it.isNotBlank() } },
        )
}
