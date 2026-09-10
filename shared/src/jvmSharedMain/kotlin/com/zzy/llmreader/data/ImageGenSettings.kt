package com.zzy.llmreader.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 文生图后端类型。 */
enum class ImageBackend { NONE, NOVELAI, COMFYUI }

/**
 * 文生图配置。字段命名尽量与 SillyTavern st-chatu8 的
 * extension_settings["st-chatu8"] 对齐，便于后续做 JSON 配置导入。
 */
data class ImageGenSettings(
    /** 主后端选择。NONE 表示禁用。 */
    val backend: ImageBackend = ImageBackend.NONE,

    // ---------- NovelAI ----------
    /** Persistent token，pst-... */
    val novelaiApiKey: String = "",
    /** 站点：official | custom */
    val novelaiSite: String = "official",
    /** 自定义站点（含完整 URL，如 http://localhost:9696/get-new-token 或 .../ai/generate-image） */
    val novelaiCustomUrl: String = "",
    /** 模型，如 nai-diffusion-4-5-full / nai-diffusion-4-full / nai-diffusion-3 */
    val novelaiModel: String = "nai-diffusion-4-5-full",
    val novelaiSampler: String = "k_euler_ancestral",
    val novelaiSchedule: String = "karras",
    val novelaiSteps: Int = 28,
    val novelaiWidth: Int = 832,
    val novelaiHeight: Int = 1216,
    val novelaiScale: Double = 7.0,
    val novelaiCfgRescale: Double = 0.18,
    val novelaiSeed: Long = 0L, // 0 / -1 = 随机
    val novelaiSm: Boolean = true,
    val novelaiSmDyn: Boolean = true,
    val novelaiDecrisper: Boolean = true,
    val novelaiVarietyBoost: Boolean = false,
    val novelaiPositivePrefix: String = "",
    val novelaiNegativePrompt: String =
        "lowres, bad anatomy, bad hands, text, error, missing fingers, " +
            "extra digit, fewer digits, cropped, worst quality, low quality, " +
            "normal quality, jpeg artifacts, signature, watermark, username, blurry",

    // ---------- ComfyUI ----------
    val comfyuiUrl: String = "http://127.0.0.1:8188",
    val comfyuiSampler: String = "euler",
    val comfyuiScheduler: String = "normal",
    val comfyuiCfg: Double = 5.0,
    val comfyuiSteps: Int = 30,
    val comfyuiWidth: Int = 832,
    val comfyuiHeight: Int = 1216,
    val comfyuiSeed: Long = 0L,
    val comfyuiCheckpoint: String = "",
    /** 当前选中的工作流名称（来自 workers map 的 key）。 */
    val comfyuiWorkflowName: String = "",
    /** 当前选中工作流的 JSON 字符串。占位符：%MODEL_NAME% / %POSITIVE% / %NEGATIVE% / %WIDTH% / %HEIGHT% / %SEED% / %STEPS% / %CFG% / %SAMPLER% / %SCHEDULER% */
    val comfyuiWorkflowJson: String = "",
    val comfyuiPositivePrefix: String = "",
    val comfyuiNegativePrompt: String =
        "lowres, bad anatomy, bad hands, worst quality, low quality, blurry",

    // ---------- 通用 ----------
    /** 由 LLM 抽取场景 prompt（true）还是用户手输（false）。 */
    val autoSceneExtract: Boolean = true,

    /**
     * 在翻译时让 LLM 自动在段落末尾插入 `[IMG: tags]` 图像标记，
     * 阅读时这些标记会被渲染成可点击的"生成图片"卡片，生成后图片直接展示在原位。
     * 类似 SillyTavern st-chatu8 的"插入正文"模式。
     */
    val enableInlineImages: Boolean = false,
    /** 每章建议生成的插图数量（仅作为给 LLM 的提示）。 */
    val inlineImagesPerChapter: Int = 3,
)

class ImageGenSettingsRepository(private val store: DataStore<Preferences>) {

    private object K {
        val backend = stringPreferencesKey("img_backend")

        val naiKey = stringPreferencesKey("img_nai_key")
        val naiSite = stringPreferencesKey("img_nai_site")
        val naiCustom = stringPreferencesKey("img_nai_custom")
        val naiModel = stringPreferencesKey("img_nai_model")
        val naiSampler = stringPreferencesKey("img_nai_sampler")
        val naiSchedule = stringPreferencesKey("img_nai_schedule")
        val naiSteps = intPreferencesKey("img_nai_steps")
        val naiW = intPreferencesKey("img_nai_w")
        val naiH = intPreferencesKey("img_nai_h")
        val naiScale = stringPreferencesKey("img_nai_scale")
        val naiCfgRescale = stringPreferencesKey("img_nai_cfg_rescale")
        val naiSeed = stringPreferencesKey("img_nai_seed")
        val naiSm = booleanPreferencesKey("img_nai_sm")
        val naiSmDyn = booleanPreferencesKey("img_nai_sm_dyn")
        val naiDecrisper = booleanPreferencesKey("img_nai_decrisper")
        val naiVariety = booleanPreferencesKey("img_nai_variety")
        val naiPositivePre = stringPreferencesKey("img_nai_pos_pre")
        val naiNegative = stringPreferencesKey("img_nai_neg")

        val cuiUrl = stringPreferencesKey("img_cui_url")
        val cuiSampler = stringPreferencesKey("img_cui_sampler")
        val cuiScheduler = stringPreferencesKey("img_cui_scheduler")
        val cuiCfg = stringPreferencesKey("img_cui_cfg")
        val cuiSteps = intPreferencesKey("img_cui_steps")
        val cuiW = intPreferencesKey("img_cui_w")
        val cuiH = intPreferencesKey("img_cui_h")
        val cuiSeed = stringPreferencesKey("img_cui_seed")
        val cuiCkpt = stringPreferencesKey("img_cui_ckpt")
        val cuiWfName = stringPreferencesKey("img_cui_wf_name")
        val cuiWfJson = stringPreferencesKey("img_cui_wf_json")
        val cuiPositivePre = stringPreferencesKey("img_cui_pos_pre")
        val cuiNegative = stringPreferencesKey("img_cui_neg")

        val autoExtract = booleanPreferencesKey("img_auto_extract")
        val inlineEnabled = booleanPreferencesKey("img_inline_enabled")
        val inlinePerChapter = intPreferencesKey("img_inline_per_chapter")
    }

    val settings: Flow<ImageGenSettings> = store.data.map { p ->
        val def = ImageGenSettings()
        ImageGenSettings(
            backend = p[K.backend]?.let { runCatching { ImageBackend.valueOf(it) }.getOrNull() }
                ?: ImageBackend.NONE,
            novelaiApiKey = p[K.naiKey] ?: "",
            novelaiSite = p[K.naiSite] ?: def.novelaiSite,
            novelaiCustomUrl = p[K.naiCustom] ?: "",
            novelaiModel = p[K.naiModel] ?: def.novelaiModel,
            novelaiSampler = p[K.naiSampler] ?: def.novelaiSampler,
            novelaiSchedule = p[K.naiSchedule] ?: def.novelaiSchedule,
            novelaiSteps = p[K.naiSteps] ?: def.novelaiSteps,
            novelaiWidth = p[K.naiW] ?: def.novelaiWidth,
            novelaiHeight = p[K.naiH] ?: def.novelaiHeight,
            novelaiScale = p[K.naiScale]?.toDoubleOrNull() ?: def.novelaiScale,
            novelaiCfgRescale = p[K.naiCfgRescale]?.toDoubleOrNull() ?: def.novelaiCfgRescale,
            novelaiSeed = p[K.naiSeed]?.toLongOrNull() ?: def.novelaiSeed,
            novelaiSm = p[K.naiSm] ?: def.novelaiSm,
            novelaiSmDyn = p[K.naiSmDyn] ?: def.novelaiSmDyn,
            novelaiDecrisper = p[K.naiDecrisper] ?: def.novelaiDecrisper,
            novelaiVarietyBoost = p[K.naiVariety] ?: def.novelaiVarietyBoost,
            novelaiPositivePrefix = p[K.naiPositivePre] ?: "",
            novelaiNegativePrompt = p[K.naiNegative] ?: def.novelaiNegativePrompt,
            comfyuiUrl = p[K.cuiUrl] ?: def.comfyuiUrl,
            comfyuiSampler = p[K.cuiSampler] ?: def.comfyuiSampler,
            comfyuiScheduler = p[K.cuiScheduler] ?: def.comfyuiScheduler,
            comfyuiCfg = p[K.cuiCfg]?.toDoubleOrNull() ?: def.comfyuiCfg,
            comfyuiSteps = p[K.cuiSteps] ?: def.comfyuiSteps,
            comfyuiWidth = p[K.cuiW] ?: def.comfyuiWidth,
            comfyuiHeight = p[K.cuiH] ?: def.comfyuiHeight,
            comfyuiSeed = p[K.cuiSeed]?.toLongOrNull() ?: def.comfyuiSeed,
            comfyuiCheckpoint = p[K.cuiCkpt] ?: "",
            comfyuiWorkflowName = p[K.cuiWfName] ?: "",
            comfyuiWorkflowJson = p[K.cuiWfJson] ?: "",
            comfyuiPositivePrefix = p[K.cuiPositivePre] ?: "",
            comfyuiNegativePrompt = p[K.cuiNegative] ?: def.comfyuiNegativePrompt,
            autoSceneExtract = p[K.autoExtract] ?: def.autoSceneExtract,
            enableInlineImages = p[K.inlineEnabled] ?: def.enableInlineImages,
            inlineImagesPerChapter = p[K.inlinePerChapter] ?: def.inlineImagesPerChapter,
        )
    }

    suspend fun update(s: ImageGenSettings) {
        store.edit { p ->
            p[K.backend] = s.backend.name
            p[K.naiKey] = s.novelaiApiKey
            p[K.naiSite] = s.novelaiSite
            p[K.naiCustom] = s.novelaiCustomUrl
            p[K.naiModel] = s.novelaiModel
            p[K.naiSampler] = s.novelaiSampler
            p[K.naiSchedule] = s.novelaiSchedule
            p[K.naiSteps] = s.novelaiSteps
            p[K.naiW] = s.novelaiWidth
            p[K.naiH] = s.novelaiHeight
            p[K.naiScale] = s.novelaiScale.toString()
            p[K.naiCfgRescale] = s.novelaiCfgRescale.toString()
            p[K.naiSeed] = s.novelaiSeed.toString()
            p[K.naiSm] = s.novelaiSm
            p[K.naiSmDyn] = s.novelaiSmDyn
            p[K.naiDecrisper] = s.novelaiDecrisper
            p[K.naiVariety] = s.novelaiVarietyBoost
            p[K.naiPositivePre] = s.novelaiPositivePrefix
            p[K.naiNegative] = s.novelaiNegativePrompt
            p[K.cuiUrl] = s.comfyuiUrl
            p[K.cuiSampler] = s.comfyuiSampler
            p[K.cuiScheduler] = s.comfyuiScheduler
            p[K.cuiCfg] = s.comfyuiCfg.toString()
            p[K.cuiSteps] = s.comfyuiSteps
            p[K.cuiW] = s.comfyuiWidth
            p[K.cuiH] = s.comfyuiHeight
            p[K.cuiSeed] = s.comfyuiSeed.toString()
            p[K.cuiCkpt] = s.comfyuiCheckpoint
            p[K.cuiWfName] = s.comfyuiWorkflowName
            p[K.cuiWfJson] = s.comfyuiWorkflowJson
            p[K.cuiPositivePre] = s.comfyuiPositivePrefix
            p[K.cuiNegative] = s.comfyuiNegativePrompt
            p[K.autoExtract] = s.autoSceneExtract
            p[K.inlineEnabled] = s.enableInlineImages
            p[K.inlinePerChapter] = s.inlineImagesPerChapter
        }
    }
}
