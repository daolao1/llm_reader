package com.zzy.llmreader.llm

import com.zzy.llmreader.data.LlmSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit

class LlmClient {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        // 请求体中的 null 字段不输出（避免向不支持 reasoning_effort 的代理发送 null）。
        explicitNulls = false
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        })
        .build()

    suspend fun chat(
        settings: LlmSettings,
        messages: List<ChatMessage>,
    ): Result<String> = chat(settings, messages, forceMinimalReasoning = false)

    suspend fun chat(
        settings: LlmSettings,
        messages: List<ChatMessage>,
        forceMinimalReasoning: Boolean,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(settings.apiKey.isNotBlank()) { "API Key 未配置" }
            val url = settings.baseUrl.trimEnd('/') + "/chat/completions"
            val payload = ChatRequest(
                model = settings.model,
                messages = messages,
                // forceMinimalReasoning 只对"会思考"的模型生效；普通 chat 模型（如 deepseek-chat、
                // GLM-4 普通版、qwen-plus 等）不带这个字段，避免被网关 400/422 拒绝。
                reasoningEffort = if (forceMinimalReasoning && isReasonerModel(settings)) "minimal"
                else reasoningEffortFor(settings),
            )
            val body = json.encodeToString(ChatRequest.serializer(), payload)
                .toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer ${settings.apiKey}")
                .addHeader("Accept", "application/json")
                .post(body)
                .build()
            client.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    error("HTTP ${resp.code}: ${text.take(500)}")
                }
                val parsed = json.decodeFromString(ChatResponse.serializer(), text)
                parsed.choices.firstOrNull()?.message?.content
                    ?: error("响应缺少 choices.message.content: ${text.take(500)}")
            }
        }
    }

    /**
     * 流式聊天：通过 SSE 逐 token 拿增量内容，每收到一段就回调 [onDelta]，
     * 最终返回拼接完成的完整文本。兼容 OpenAI / GLM / 智谱等 `data: {...}` 风格。
     */
    suspend fun chatStream(
        settings: LlmSettings,
        messages: List<ChatMessage>,
        onDelta: (String) -> Unit,
    ): Result<String> = chatStream(settings, messages, forceMinimalReasoning = false, onDelta)

    suspend fun chatStream(
        settings: LlmSettings,
        messages: List<ChatMessage>,
        forceMinimalReasoning: Boolean,
        onDelta: (String) -> Unit,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(settings.apiKey.isNotBlank()) { "API Key 未配置" }
            val url = settings.baseUrl.trimEnd('/') + "/chat/completions"
            val payload = ChatRequest(
                model = settings.model,
                messages = messages,
                stream = true,
                reasoningEffort = if (forceMinimalReasoning && isReasonerModel(settings)) "minimal"
                else reasoningEffortFor(settings),
            )
            val body = json.encodeToString(ChatRequest.serializer(), payload)
                .toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer ${settings.apiKey}")
                .addHeader("Accept", "text/event-stream")
                .post(body)
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val text = resp.body?.string().orEmpty()
                    error("HTTP ${resp.code}: ${text.take(500)}")
                }
                val source = resp.body?.source() ?: error("空响应")
                val full = StringBuilder()
                // 统计「看起来像 SSE 但都解析失败」的行，用于失败时给出更准的原因
                var dataLines = 0
                var unparsableLines = 0
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (line.isBlank()) continue
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    dataLines++
                    val parsedDelta = runCatching {
                        json.decodeFromString(StreamChunk.serializer(), data)
                            .choices.firstOrNull()?.delta
                    }.getOrNull()
                    if (parsedDelta == null) {
                        unparsableLines++
                        continue
                    }
                    // 忽略 reasoning_content / reasoning 思维链字段（DeepSeek-R1 / OpenAI o1 / 部分 Gemini 代理会输出）。
                    val delta = parsedDelta.content.orEmpty()
                    if (delta.isNotEmpty()) {
                        full.append(delta)
                        onDelta(delta)
                    }
                }
                val text = full.toString()
                // ⚠️ 必须在这里判失败。
                // 旧实现无条件 `return full.toString()`：网关忽略 stream=true 直接返回
                // 普通 JSON 时，整个响应体没有一行以 `data:` 开头，于是这里返回**空串且
                // 判定为成功**。上层 ChapterTranslator 收到空串后逐段回退成原文、仍然返回
                // Success，最终把「日文原文」当作「简体中文译文」永久写进缓存 ——
                // 章节被标记为已翻译，此后再也不会重试，界面上也没有任何报错。
                if (text.isEmpty()) {
                    error(
                        when {
                            dataLines == 0 ->
                                "服务端没有返回 SSE 数据流（可能忽略了 stream=true，" +
                                    "或该地址不是 OpenAI 兼容的 /chat/completions 接口）"
                            unparsableLines == dataLines ->
                                "SSE 数据流全部无法解析（服务端可能在流内返回了错误，或返回了非标准格式）"
                            else ->
                                "模型返回了空内容（delta 里没有 content，可能只有思维链或触发了内容过滤）"
                        },
                    )
                }
                text
            }
        }
    }

    /**
     * 获取可用模型列表（GET /models）。返回模型 ID 列表。
     */
    suspend fun listModels(settings: LlmSettings): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            require(settings.apiKey.isNotBlank()) { "API Key 未配置" }
            val url = settings.baseUrl.trimEnd('/') + "/models"
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer ${settings.apiKey}")
                .addHeader("Accept", "application/json")
                .get()
                .build()
            client.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    error("HTTP ${resp.code}: ${text.take(500)}")
                }
                val parsed = json.decodeFromString(ModelsResponse.serializer(), text)
                parsed.data.map { it.id }.sorted()
            }
        }
    }

    @Serializable
    private data class ModelsResponse(val data: List<ModelItem> = emptyList())

    @Serializable
    private data class ModelItem(val id: String)

    @Serializable
    private data class StreamChunk(val choices: List<StreamChoice> = emptyList())

    @Serializable
    private data class StreamChoice(val delta: StreamDelta? = null)

    @Serializable
    private data class StreamDelta(
        val content: String? = null,
        @SerialName("reasoning_content") val reasoningContent: String? = null,
        val reasoning: String? = null,
    )

    /**
     * 判断当前模型是否需要发送 reasoning_effort="minimal" 以关闭思维链。
     * Gemini reasoning preview / o1 / DeepSeek-R1 正常请求需要这个参数。
     */
    private fun reasoningEffortFor(settings: LlmSettings): String? =
        if (isReasonerModel(settings)) "minimal" else null

    private fun isReasonerModel(settings: LlmSettings): Boolean {
        val tag = (settings.baseUrl + " " + settings.model).lowercase()
        return listOf(
            "preview", "thinking", "reasoner", "-r1", "-o1", "-o3", "-o4", "3.1-pro"
        ).any { it in tag }
    }
}
