package com.zzy.llmreader.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 发现页翻译结果的本地持久化：
 *   tag      key = "lang|tag-原文"            → 译文
 *   bookInfo key = "lang|title|||summary[800]" → (译标题, 译简介)
 *
 * 用 DataStore 存两段 JSON，启动时一次性 load，更新时整张回写。
 * 容量上限：tag 5000 条、book 2000 条；超出按"先进先出"丢弃最旧。
 */
class DiscoveryTranslationCacheRepository(private val store: DataStore<Preferences>) {

    private val tagKey = stringPreferencesKey("discovery_tag_tx_v1")
    private val bookKey = stringPreferencesKey("discovery_bookinfo_tx_v1")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }

    @Serializable
    data class BookEntry(val title: String, val summary: String)

    suspend fun loadTags(): LinkedHashMap<String, String> {
        val raw = store.data.first()[tagKey].orEmpty()
        if (raw.isBlank()) return LinkedHashMap()
        return runCatching {
            val map = json.decodeFromString<Map<String, String>>(raw)
            LinkedHashMap(map)
        }.getOrElse { LinkedHashMap() }
    }

    suspend fun loadBooks(): LinkedHashMap<String, Pair<String, String>> {
        val raw = store.data.first()[bookKey].orEmpty()
        if (raw.isBlank()) return LinkedHashMap()
        return runCatching {
            val map = json.decodeFromString<Map<String, BookEntry>>(raw)
            LinkedHashMap<String, Pair<String, String>>().apply {
                for ((k, v) in map) put(k, v.title to v.summary)
            }
        }.getOrElse { LinkedHashMap() }
    }

    suspend fun saveTags(map: Map<String, String>, max: Int = 5000) {
        val trimmed = if (map.size > max) {
            map.entries.toList().takeLast(max).associate { it.toPair() }
        } else map
        store.edit { p -> p[tagKey] = json.encodeToString(trimmed) }
    }

    suspend fun saveBooks(map: Map<String, Pair<String, String>>, max: Int = 2000) {
        val trimmed = if (map.size > max) {
            map.entries.toList().takeLast(max).associate { it.toPair() }
        } else map
        val serializable = trimmed.mapValues { (_, v) -> BookEntry(v.first, v.second) }
        store.edit { p -> p[bookKey] = json.encodeToString(serializable) }
    }

    suspend fun clearAll() {
        store.edit { p ->
            p.remove(tagKey)
            p.remove(bookKey)
        }
    }
}
