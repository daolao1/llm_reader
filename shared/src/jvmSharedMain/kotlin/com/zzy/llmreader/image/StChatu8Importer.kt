package com.zzy.llmreader.image

import com.zzy.llmreader.data.ImageBackend
import com.zzy.llmreader.data.ImageGenSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** [StChatu8Importer.import] 的结果：解析出的配置 + 给用户看的摘要。 */
data class StChatu8ImportResult(
    val settings: ImageGenSettings,
    val library: CharacterLibrary,
    /** workers 里识别出的 ComfyUI 工作流数量。 */
    val workflowCount: Int,
) {
    /** 导入摘要，UI 直接展示。 */
    val summary: String
        get() = buildString {
            append("已导入：后端=${settings.backend.name}")
            if (library.characters.isNotEmpty()) append(", 角色 ${library.characters.size} 个")
            if (library.outfits.isNotEmpty()) append(", 装束 ${library.outfits.size} 个")
            if (workflowCount > 0) {
                append(", ComfyUI 工作流 $workflowCount 个（已选 \"${settings.comfyuiWorkflowName}\"）")
            }
        }
}

/**
 * 解析 SillyTavern `st-chatu8` 插件的配置 JSON。
 *
 * 纯函数：不碰 IO、不持状态，因此可以直接单测（见 shared/src/desktopTest）。
 * 持久化由调用方负责。
 *
 * 兼容两种输入形态：
 *  1. 顶层就是 st-chatu8 的 settings（插件页面「导出当前配置」的格式）
 *  2. 整个 SillyTavern `settings.json`，配置包在 `extension_settings["st-chatu8"]` 里
 */
object StChatu8Importer {

    private val json = Json { ignoreUnknownKeys = true }

    /** 从原始 JSON 文本导入。 */
    fun importFromText(jsonText: String, current: ImageGenSettings): StChatu8ImportResult {
        val root = json.parseToJsonElement(jsonText) as? JsonObject
            ?: error("不是合法的 JSON 对象")
        return import(root, current)
    }

    /** 从已解析的根对象导入。 */
    fun import(root: JsonObject, current: ImageGenSettings): StChatu8ImportResult {
        // 兼容两种格式，见类注释
        val sc = (root["extension_settings"] as? JsonObject)
            ?.get("st-chatu8") as? JsonObject
            ?: root

        fun str(key: String) = (sc[key] as? JsonPrimitive)?.content
        fun num(key: String) = str(key)?.toDoubleOrNull()
        fun int(key: String) = str(key)?.toIntOrNull()
        fun bool(key: String) = str(key)?.let {
            when (it.lowercase()) {
                "true", "1", "yes" -> true
                "false", "0", "no", "" -> false
                else -> null
            }
        }

        // 后端选择：client=jiuguan 视为 ComfyUI（九馆代理 ComfyUI 的方式），
        // client=novelai → NovelAI；未指定时若当前是 NONE 则默认 NovelAI。
        val client = str("client") ?: ""
        val backend = when (client) {
            "novelai" -> ImageBackend.NOVELAI
            "comfy", "comfyui", "jiuguan" -> ImageBackend.COMFYUI
            else -> if (current.backend == ImageBackend.NONE) ImageBackend.NOVELAI else current.backend
        }

        val site = when (str("novelaisite")) {
            "官网", "official", "main" -> "official"
            null -> current.novelaiSite
            else -> "custom"
        }

        var settings = current.copy(
            backend = backend,
            novelaiApiKey = str("novelaiApi") ?: current.novelaiApiKey,
            novelaiSite = site,
            novelaiCustomUrl = str("novelaiOtherSite") ?: current.novelaiCustomUrl,
            novelaiModel = str("novelaimode") ?: current.novelaiModel,
            novelaiSampler = str("novelai_sampler") ?: current.novelaiSampler,
            novelaiSteps = int("novelai_steps") ?: current.novelaiSteps,
            novelaiWidth = int("novelai_width") ?: current.novelaiWidth,
            novelaiHeight = int("novelai_height") ?: current.novelaiHeight,
            novelaiSeed = str("novelai_seed")?.toLongOrNull() ?: current.novelaiSeed,
            novelaiScale = num("nai3Scale") ?: current.novelaiScale,
            novelaiDecrisper = bool("nai3Deceisp") ?: current.novelaiDecrisper,
            novelaiVarietyBoost = bool("nai3Variety") ?: current.novelaiVarietyBoost,
            comfyuiUrl = str("comfyuiUrl") ?: current.comfyuiUrl,
            comfyuiSampler = str("comfyuisamplerName") ?: current.comfyuiSampler,
            comfyuiScheduler = str("comfyui_scheduler") ?: current.comfyuiScheduler,
            comfyuiCfg = num("cfg_comfyui") ?: current.comfyuiCfg,
            comfyuiSteps = int("comfyui_steps") ?: current.comfyuiSteps,
            comfyuiWidth = int("comfyui_width") ?: current.comfyuiWidth,
            comfyuiHeight = int("comfyui_height") ?: current.comfyuiHeight,
            comfyuiSeed = str("comfyui_seed")?.toLongOrNull() ?: current.comfyuiSeed,
        )

        // ComfyUI 工作流：优先用当前激活的 workerid，否则取 workers 里第一个像工作流的。
        // workers 里混有 "links"/"last_link_id" 这类非工作流条目，靠 class_type 字段区分。
        val workersMap = sc["workers"] as? JsonObject
        var workflowCount = 0
        if (workersMap != null) {
            val activeName = str("workerid")
            val candidates = listOfNotNull(activeName) + workersMap.keys
            val pick = candidates.firstOrNull { name -> workersMap[name].asWorkflowText() != null }
            workflowCount = workersMap.values.count { it.asWorkflowText() != null }
            if (pick != null) {
                workersMap[pick].asWorkflowText()?.let { wfJson ->
                    settings = settings.copy(
                        comfyuiWorkflowName = pick,
                        comfyuiWorkflowJson = wfJson,
                    )
                }
            }
        }

        return StChatu8ImportResult(
            settings = settings,
            library = parseCharacters(sc),
            workflowCount = workflowCount,
        )
    }

    /** 把一个 worker 条目转成工作流 JSON 文本；不像工作流则返回 null。 */
    private fun kotlinx.serialization.json.JsonElement?.asWorkflowText(): String? {
        val text = (this as? JsonPrimitive)?.content ?: this?.toString() ?: return null
        return text.takeIf { it.contains("class_type") }
    }

    /**
     * 解析角色 / 装束预设。
     * st-chatu8 用 `characterPresets` / `outfitPresets` 这种 dict<name, dict>，
     * 同时兼容 `jueselist` / `outfitslist` / `characters` / `outfits` 的列表写法。
     */
    fun parseCharacters(sc: JsonObject): CharacterLibrary {
        fun extractDict(key: String): List<Pair<String, JsonObject>> {
            val node = sc[key] ?: return emptyList()
            return when (node) {
                is JsonObject -> node.entries.mapNotNull { (k, v) ->
                    (v as? JsonObject)?.let { k to it }
                }
                is JsonArray -> node.mapIndexedNotNull { i, v ->
                    (v as? JsonObject)?.let { o ->
                        val name = (o["name"] as? JsonPrimitive)?.content ?: i.toString()
                        name to o
                    }
                }
                else -> emptyList()
            }
        }

        val outfits = (
            extractDict("outfitPresets") + extractDict("outfitslist") + extractDict("outfits")
            ).distinctBy { it.first }.map { (name, o) ->
            OutfitPreset(
                id = o.firstString("id") ?: "o_$name",
                name = o.firstString("name") ?: name,
                prompt = o.firstString("prompt", "tags", "positive", "content") ?: "",
                notes = o.firstString("notes", "description") ?: "",
            )
        }.filter { it.name.isNotBlank() }

        val characters = (
            extractDict("characterPresets") + extractDict("jueselist") + extractDict("characters")
            ).distinctBy { it.first }.map { (name, c) ->
            val aliasArr = c["aliases"] as? JsonArray
            CharacterPreset(
                id = c.firstString("id") ?: "c_$name",
                name = c.firstString("name") ?: name,
                englishName = c.firstString("english", "en", "englishName") ?: "",
                aliases = aliasArr?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList(),
                appearancePrompt = c.firstString("appearance", "prompt", "tags", "positive", "content") ?: "",
                defaultOutfitId = c.firstString("defaultOutfit", "outfit", "outfitId"),
                notes = c.firstString("notes", "description") ?: "",
            )
        }.filter { it.name.isNotBlank() }

        return CharacterLibrary(characters = characters, outfits = outfits)
    }

    /** 按顺序取第一个非空字符串字段。 */
    private fun JsonObject.firstString(vararg keys: String): String? {
        for (k in keys) {
            val s = (this[k] as? JsonPrimitive)?.content
            if (!s.isNullOrBlank()) return s
        }
        return null
    }
}
