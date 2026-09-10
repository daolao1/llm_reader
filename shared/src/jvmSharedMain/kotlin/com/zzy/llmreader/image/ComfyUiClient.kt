package com.zzy.llmreader.image

import com.zzy.llmreader.data.ImageGenSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * ComfyUI 文生图客户端。
 *
 * 流程（参考 st-chatu8 utils/comfyui.js.deob.js 第 240-330 行）：
 *   1. POST {url}/prompt  body={"client_id":"<uuid>","prompt": <workflow_json>}
 *   2. 轮询 GET {url}/history/<prompt_id> 直到有结果
 *   3. 取 outputs.<node>.images[0].filename / subfolder
 *   4. GET {url}/view?filename=...&subfolder=...&type=output → 图片字节
 *
 * 工作流 JSON 占位符（与 st-chatu8 一致）：
 *   %POSITIVE% / %NEGATIVE% / %MODEL_NAME% / %WIDTH% / %HEIGHT%
 *   %SEED% / %STEPS% / %CFG% / %SAMPLER% / %SCHEDULER%
 */
class ComfyUiClient {

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun generate(
        settings: ImageGenSettings,
        positivePrompt: String,
        negativePrompt: String? = null,
        widthOverride: Int? = null,
        heightOverride: Int? = null,
    ): ByteArray = withContext(Dispatchers.IO) {
        require(settings.comfyuiUrl.isNotBlank()) { "ComfyUI URL 未配置" }
        require(settings.comfyuiWorkflowJson.isNotBlank()) {
            "ComfyUI 工作流未配置。请在设置中导入 SillyTavern 的工作流 JSON。"
        }
        val baseUrl = settings.comfyuiUrl.trimEnd('/')
        val width = widthOverride ?: settings.comfyuiWidth
        val height = heightOverride ?: settings.comfyuiHeight
        val seed = if (settings.comfyuiSeed <= 0) Random.nextLong(1, Long.MAX_VALUE) else settings.comfyuiSeed
        val finalPositive = listOf(settings.comfyuiPositivePrefix, positivePrompt)
            .filter { it.isNotBlank() }.joinToString(", ")
        val finalNegative = negativePrompt ?: settings.comfyuiNegativePrompt
        val workflowJson = applyPlaceholders(
            settings.comfyuiWorkflowJson,
            mapOf(
                "POSITIVE" to escapeJson(finalPositive),
                "NEGATIVE" to escapeJson(finalNegative),
                "MODEL_NAME" to escapeJson(settings.comfyuiCheckpoint),
                "WIDTH" to width.toString(),
                "HEIGHT" to height.toString(),
                "SEED" to seed.toString(),
                "STEPS" to settings.comfyuiSteps.toString(),
                "CFG" to settings.comfyuiCfg.toString(),
                "SAMPLER" to escapeJson(settings.comfyuiSampler),
                "SCHEDULER" to escapeJson(settings.comfyuiScheduler),
            ),
        )

        val clientId = UUID.randomUUID().toString()
        val payload = """{"client_id":"$clientId","prompt":$workflowJson}"""

        // 1) POST /prompt
        val promptResp = http.newCall(
            Request.Builder()
                .url("$baseUrl/prompt")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build(),
        ).execute()
        val promptId = promptResp.use {
            if (!it.isSuccessful) {
                error("ComfyUI /prompt HTTP ${it.code}: ${it.body?.string()?.take(500)}")
            }
            val body = it.body?.string().orEmpty()
            json.parseToJsonElement(body).jsonObject["prompt_id"]?.jsonPrimitive?.content
                ?: error("ComfyUI /prompt 响应缺少 prompt_id：$body")
        }

        // 2) 轮询 /history
        val maxWaitMs = 5 * 60 * 1000L
        val started = System.currentTimeMillis()
        var fileEntry: ImageRef? = null
        while (true) {
            val elapsed = System.currentTimeMillis() - started
            if (elapsed > maxWaitMs) error("ComfyUI 等待超时 (${maxWaitMs / 1000}s)")
            delay(1000)
            val histResp = http.newCall(
                Request.Builder().url("$baseUrl/history/$promptId").build(),
            ).execute()
            val body = histResp.use {
                if (!it.isSuccessful) {
                    if (it.code == 404) return@use ""
                    error("ComfyUI /history HTTP ${it.code}")
                }
                it.body?.string().orEmpty()
            }
            if (body.isBlank() || body == "{}") continue
            val histRoot = json.parseToJsonElement(body).jsonObject
            val task = histRoot[promptId]?.jsonObject ?: continue

            val statusStr = task["status"]?.jsonObject
                ?.get("status_str")?.jsonPrimitive?.content
            if (statusStr == "error") {
                val msgs = task["status"]?.jsonObject?.get("messages")?.toString().orEmpty()
                error("ComfyUI 执行失败：$msgs")
            }

            val outputs = task["outputs"]?.jsonObject ?: continue
            for ((_, nodeOut) in outputs) {
                val obj = nodeOut.jsonObject
                val images = obj["images"]?.jsonArray
                if (images != null && images.isNotEmpty()) {
                    val img = images.first().jsonObject
                    val type = img["type"]?.jsonPrimitive?.content
                    if (type == "output") {
                        fileEntry = ImageRef(
                            filename = img["filename"]?.jsonPrimitive?.content.orEmpty(),
                            subfolder = img["subfolder"]?.jsonPrimitive?.content.orEmpty(),
                        )
                        break
                    }
                }
            }
            if (fileEntry != null) break
        }
        val ref = fileEntry ?: error("ComfyUI 没有找到输出图片")

        // 3) GET /view
        val viewUrl = buildString {
            append("$baseUrl/view?filename=")
            append(URLEncoder.encode(ref.filename, "UTF-8"))
            append("&subfolder=")
            append(URLEncoder.encode(ref.subfolder, "UTF-8"))
            append("&type=output")
        }
        http.newCall(Request.Builder().url(viewUrl).build()).execute().use { r ->
            if (!r.isSuccessful) error("ComfyUI /view HTTP ${r.code}")
            return@withContext r.body?.bytes() ?: error("ComfyUI 空响应")
        }
    }

    /** 测试连通：列出可用模型（ckpt）。 */
    suspend fun listCheckpoints(baseUrl: String): List<String> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(baseUrl.trimEnd('/') + "/object_info/CheckpointLoaderSimple").build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return@withContext emptyList()
            val body = r.body?.string().orEmpty()
            // CheckpointLoaderSimple.input.required.ckpt_name = [ [list...], {} ]
            runCatching {
                val root = Json.parseToJsonElement(body).jsonObject
                val req2 = root["CheckpointLoaderSimple"]?.jsonObject
                    ?.get("input")?.jsonObject
                    ?.get("required")?.jsonObject
                    ?.get("ckpt_name")?.jsonArray
                req2?.firstOrNull()?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            }.getOrDefault(emptyList())
        }
    }

    private data class ImageRef(val filename: String, val subfolder: String)

    /** 把工作流 JSON 中的 %FOO% 占位符替换为实际值。 */
    private fun applyPlaceholders(template: String, vars: Map<String, String>): String {
        var out = template
        for ((k, v) in vars) {
            out = out.replace("%$k%", v)
        }
        return out
    }

    /** 转义字符串以安全嵌入 JSON 字符串字面量内（用于占位符替换）。 */
    private fun escapeJson(s: String): String =
        s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
}
