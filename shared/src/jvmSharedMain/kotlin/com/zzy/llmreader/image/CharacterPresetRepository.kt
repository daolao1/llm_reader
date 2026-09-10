package com.zzy.llmreader.image

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 角色预设（用于 RP / 文生图场景）。
 *
 * 设计：
 *  - `aliases` 用来在章节正文里识别角色（中文名、外号、英文名等）
 *  - `appearancePrompt` 是角色的固定外观 tag（脸型/发色等）
 *  - `defaultOutfitId` 指向一个 [OutfitPreset]
 *  - 兼容 st-chatu8 的"角色管理"块；导入时会做一次映射。
 */
@Serializable
data class CharacterPreset(
    val id: String,
    val name: String,
    val englishName: String = "",
    val aliases: List<String> = emptyList(),
    val appearancePrompt: String = "",
    val defaultOutfitId: String? = null,
    val notes: String = "",
)

@Serializable
data class OutfitPreset(
    val id: String,
    val name: String,
    val prompt: String,
    val notes: String = "",
)

@Serializable
data class CharacterLibrary(
    val characters: List<CharacterPreset> = emptyList(),
    val outfits: List<OutfitPreset> = emptyList(),
)

class CharacterPresetRepository(private val store: DataStore<Preferences>) {

    private val key = stringPreferencesKey("character_library_json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    val library: Flow<CharacterLibrary> = store.data.map { p ->
        val raw = p[key] ?: return@map CharacterLibrary()
        runCatching { json.decodeFromString(CharacterLibrary.serializer(), raw) }
            .getOrDefault(CharacterLibrary())
    }

    suspend fun update(lib: CharacterLibrary) {
        store.edit { p ->
            p[key] = json.encodeToString(CharacterLibrary.serializer(), lib)
        }
    }

    /** 根据章节文本中出现的角色名（中文优先），合成补充 prompt。 */
    fun buildAutoPromptAddon(lib: CharacterLibrary, sceneText: String): String {
        if (lib.characters.isEmpty()) return ""
        val matched = mutableListOf<CharacterPreset>()
        for (c in lib.characters) {
            val keys = (listOf(c.name, c.englishName) + c.aliases).filter { it.isNotBlank() }
            if (keys.any { it in sceneText }) matched += c
        }
        if (matched.isEmpty()) return ""
        val outfitMap = lib.outfits.associateBy { it.id }
        return matched.joinToString(", ") { c ->
            val parts = mutableListOf<String>()
            if (c.englishName.isNotBlank()) parts += c.englishName
            if (c.appearancePrompt.isNotBlank()) parts += c.appearancePrompt
            c.defaultOutfitId?.let { outfitMap[it]?.prompt?.takeIf { p -> p.isNotBlank() }?.let(parts::add) }
            parts.joinToString(", ")
        }
    }
}
