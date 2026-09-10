package com.zzy.llmreader.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class RecentBookSource {
    FILE,
    WEB,
}

@Serializable
data class RecentBook(
    val uri: String,
    val title: String,
    val openedAt: Long,
    val source: RecentBookSource = RecentBookSource.FILE,
)

class RecentBooksRepository(private val store: DataStore<Preferences>) {

    private val key = stringPreferencesKey("recent_books_json")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }

    val recents: Flow<List<RecentBook>> = store.data.map { p ->
        val raw = p[key].orEmpty()
        if (raw.isBlank()) emptyList()
        else runCatching { json.decodeFromString<List<RecentBook>>(raw) }.getOrElse { emptyList() }
    }

    suspend fun add(book: RecentBook, max: Int = 20) {
        store.edit { p ->
            val current = p[key].orEmpty().let {
                if (it.isBlank()) emptyList()
                else runCatching { json.decodeFromString<List<RecentBook>>(it) }
                    .getOrElse { emptyList() }
            }
            val next = (listOf(book) + current.filter { it.uri != book.uri }).take(max)
            p[key] = json.encodeToString(next)
        }
    }

    suspend fun remove(uri: String) {
        store.edit { p ->
            val current = p[key].orEmpty().let {
                if (it.isBlank()) emptyList()
                else runCatching { json.decodeFromString<List<RecentBook>>(it) }
                    .getOrElse { emptyList() }
            }
            p[key] = json.encodeToString(current.filter { it.uri != uri })
        }
    }
}
