package com.zzy.llmreader.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.zzy.llmreader.platform.AppDirs
import okio.Path.Companion.toOkioPath
import java.io.File

/**
 * 同一文件的 DataStore 实例缓存。
 *
 * DataStore 明确要求：**同一个文件在同一进程内只能有一个活跃实例**，否则会抛
 * `IllegalStateException: There are multiple DataStores active for the same file`。
 * Android 端每个进程只会构造一个 [com.zzy.llmreader.LlmReaderCore]，本来不会踩到；
 * 但桌面端开第二个窗口、或重复构造 Core 时就会崩。这里按文件路径做一次单例化。
 */
private val activeStores = mutableMapOf<String, DataStore<Preferences>>()

/**
 * 在 shared 里创建 DataStore（multiplatform 版 datastore-preferences-core）。
 *
 * 关键点：文件路径刻意与 Android 端原本的 `preferencesDataStore(name = "settings")`
 * **完全一致** —— `<filesDir>/datastore/settings.preferences_pb`，
 * 所以 Android 用户升级后已有的 API Key / cookie / 阅读进度 / 术语表都不会丢。
 *
 * 桌面端同一个函数会写到 `~/.llm_reader/datastore/settings.preferences_pb`。
 */
@Synchronized
fun createAppDataStore(dirs: AppDirs, name: String = "settings"): DataStore<Preferences> {
    val file = File(File(dirs.dataDir, "datastore"), "$name.preferences_pb")
    file.parentFile?.mkdirs()
    return activeStores.getOrPut(file.absolutePath) {
        PreferenceDataStoreFactory.createWithPath(produceFile = { file.toOkioPath() })
    }
}
