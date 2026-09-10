package com.zzy.llmreader.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 单本书的阅读进度。 */
@Serializable
data class ReadingProgress(
    val chapterIndex: Int = 0,
    /** 章节内滚动百分比（0..1）。当前未在 UI 中使用，仅作前向兼容。 */
    val chapterScrollFraction: Float = 0f,
    val updatedAt: Long = System.currentTimeMillis(),
)

/** 跨进程持久化书 → 阅读进度。Key 为 book uri / canonical url。 */
class ReadingProgressRepository(private val store: DataStore<Preferences>) {

    private val key = stringPreferencesKey("reading_progress_json_v1")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }

    private suspend fun readAll(): MutableMap<String, ReadingProgress> {
        val raw = store.data.first()[key].orEmpty()
        if (raw.isBlank()) return mutableMapOf()
        return runCatching { json.decodeFromString<Map<String, ReadingProgress>>(raw).toMutableMap() }
            .getOrElse { mutableMapOf() }
    }

    suspend fun get(uri: String): ReadingProgress? = readAll()[uri]

    suspend fun save(uri: String, chapterIndex: Int, scrollFraction: Float = 0f) {
        if (uri.isBlank()) return
        store.edit { p ->
            val cur = p[key].orEmpty().let {
                if (it.isBlank()) mutableMapOf()
                else runCatching { json.decodeFromString<Map<String, ReadingProgress>>(it).toMutableMap() }
                    .getOrElse { mutableMapOf() }
            }
            cur[uri] = ReadingProgress(chapterIndex, scrollFraction, System.currentTimeMillis())
            // 限制条目，避免无限增长。
            val trimmed = if (cur.size > 200) {
                cur.entries.sortedByDescending { it.value.updatedAt }.take(200).associate { it.key to it.value }
            } else cur
            p[key] = json.encodeToString(trimmed)
        }
    }
}
