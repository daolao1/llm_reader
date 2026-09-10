package com.zzy.llmreader.image

import com.zzy.llmreader.data.ImageGenSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlin.random.Random

/**
 * NovelAI 文生图客户端。
 *
 * 端点：POST https://image.novelai.net/ai/generate-image
 * 头：Authorization: Bearer <api_key>, Content-Type: application/json
 * 响应：application/zip 内含若干 .png；取第一个返回。
 *
 * 协议参考 st-chatu8 反混淆后的 utils/novelai.js（generateNovelAIImage）。
 */
class NovelAiClient {

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * 文生图。返回第一张 PNG 的字节。失败时抛异常。
     */
    suspend fun generate(
        settings: ImageGenSettings,
        positivePrompt: String,
        negativePrompt: String? = null,
        widthOverride: Int? = null,
        heightOverride: Int? = null,
    ): ByteArray = withContext(Dispatchers.IO) {
        require(settings.novelaiApiKey.isNotBlank()) { "NovelAI API Key 未配置" }
        val width = (widthOverride ?: settings.novelaiWidth).coerceIn(64, 4096)
        val height = (heightOverride ?: settings.novelaiHeight).coerceIn(64, 4096)
        val seed = if (settings.novelaiSeed <= 0) Random.nextLong(1, 4_294_967_295L) else settings.novelaiSeed
        val finalPositive = listOf(settings.novelaiPositivePrefix, positivePrompt)
            .filter { it.isNotBlank() }
            .joinToString(", ")
        val finalNegative = negativePrompt ?: settings.novelaiNegativePrompt

        val isV4 = settings.novelaiModel.contains("nai-diffusion-4")
        val parameters = buildParameters(settings, width, height, seed, finalNegative, finalPositive, isV4)
        val payload = buildJsonObject {
            put("input", finalPositive)
            put("model", settings.novelaiModel)
            put("action", "generate")
            put("parameters", parameters)
        }

        val url = when (settings.novelaiSite) {
            "custom" -> {
                val u = settings.novelaiCustomUrl.trim().ifBlank {
                    "https://image.novelai.net/ai/generate-image"
                }
                if (u.contains("generate-image")) u else u.trimEnd('/') + "/ai/generate-image"
            }
            else -> "https://image.novelai.net/ai/generate-image"
        }

        val body = payload.toString()
            .toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${settings.novelaiApiKey}")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "*/*")
            .addHeader(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            )
            .post(body)
            .build()

        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val text = resp.body?.string().orEmpty().take(800)
                error("NovelAI HTTP ${resp.code}: $text")
            }
            val bytes = resp.body?.bytes() ?: error("NovelAI 空响应")
            extractFirstPngFromZip(bytes)
                ?: error("NovelAI 响应不是 zip/png（前 4 字节: ${bytes.take(4)}）")
        }
    }

    private fun buildParameters(
        s: ImageGenSettings,
        width: Int,
        height: Int,
        seed: Long,
        negative: String,
        positive: String,
        isV4: Boolean,
    ): JsonObject = buildJsonObject {
        put("params_version", 3)
        put("width", width)
        put("height", height)
        put("scale", s.novelaiScale)
        put("sampler", s.novelaiSampler)
        put("steps", s.novelaiSteps)
        put("n_samples", 1)
        put("ucPreset", 3)
        put("qualityToggle", true)
        put("sm", s.novelaiSm && !isV4)
        put("sm_dyn", s.novelaiSm && s.novelaiSmDyn && !isV4)
        put("dynamic_thresholding", s.novelaiDecrisper && !isV4)
        put("controlnet_strength", 1)
        put("legacy", false)
        put("legacy_uc", false)
        put("add_original_image", true)
        put("cfg_rescale", s.novelaiCfgRescale)
        put("noise_schedule", s.novelaiSchedule)
        if (s.novelaiVarietyBoost) {
            // 与 st-chatu8 一致的近似阈值（不同模型不同基线）
            val sigma = when {
                s.novelaiModel == "nai-diffusion-4-full" -> 19.0
                s.novelaiModel.startsWith("nai-diffusion-4-5") -> 59.04722600415217
                else -> 19.0
            }
            put("skip_cfg_above_sigma", sigma)
        } else {
            put("skip_cfg_above_sigma", JsonNull)
        }
        put("legacy_v3_extend", false)
        put("seed", seed)
        put("negative_prompt", negative)
        // Reference / vibe 数组为空，避免被服务端挑剔。
        put("reference_image_multiple", JsonArray(emptyList()))
        put("reference_information_extracted_multiple", JsonArray(emptyList()))
        put("reference_strength_multiple", JsonArray(emptyList()))
        put("reference_image_multiple_cached", JsonArray(emptyList()))
        put("normalize_reference_strength_multiple", false)
        put("use_coords", false)

        if (isV4) {
            // V4/V4.5 必须的字段
            put("autoSmea", false)
            put("inpaintImg2ImgStrength", 1)
            putJsonObject("v4_prompt") {
                putJsonObject("caption") {
                    put("base_caption", positive)
                    putJsonArray("char_captions") {}
                }
                put("use_coords", false)
                put("use_order", true)
            }
            putJsonObject("v4_negative_prompt") {
                putJsonObject("caption") {
                    put("base_caption", negative)
                    putJsonArray("char_captions") {}
                }
                put("legacy_uc", false)
            }
            putJsonArray("characterPrompts") {}
        }
    }

    /** 从 NovelAI 返回的 zip 字节流里取第一个 png 的内容。 */
    private fun extractFirstPngFromZip(zipBytes: ByteArray): ByteArray? {
        // 先验证 zip magic（PK\x03\x04）
        if (zipBytes.size < 4) return null
        if (!(zipBytes[0] == 0x50.toByte() && zipBytes[1] == 0x4B.toByte() &&
                zipBytes[2] == 0x03.toByte() && zipBytes[3] == 0x04.toByte())
        ) return null
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zin ->
            var entry = zin.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.endsWith(".png", ignoreCase = true)) {
                    return zin.readBytes()
                }
                entry = zin.nextEntry
            }
        }
        return null
    }
}
