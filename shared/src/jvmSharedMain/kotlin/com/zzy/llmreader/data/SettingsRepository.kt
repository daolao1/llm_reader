package com.zzy.llmreader.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class LlmSettings(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
)

class SettingsRepository(private val store: DataStore<Preferences>) {

    private val keyBaseUrl = stringPreferencesKey("base_url")
    private val keyApiKey = stringPreferencesKey("api_key")
    private val keyModel = stringPreferencesKey("model")

    val settings: Flow<LlmSettings> = store.data.map { prefs ->
        LlmSettings(
            baseUrl = prefs[keyBaseUrl] ?: DEFAULT_BASE_URL,
            apiKey = prefs[keyApiKey].orEmpty(),
            model = prefs[keyModel] ?: DEFAULT_MODEL,
        )
    }

    suspend fun update(settings: LlmSettings) {
        store.edit { prefs ->
            prefs[keyBaseUrl] = settings.baseUrl
            prefs[keyApiKey] = settings.apiKey
            prefs[keyModel] = settings.model
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://open.bigmodel.cn/api/paas/v4"
        const val DEFAULT_MODEL = "glm-4-flash"
    }
}
