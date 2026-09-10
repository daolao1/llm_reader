package com.zzy.llmreader.llm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ChatMessage(
    val role: String,
    val content: String,
)

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double = 0.3,
    val stream: Boolean = false,
    /** OpenAI o1 / Gemini-thinking / DeepSeek-R1 兼容：minimal/low/medium/high；null 时不发送。 */
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
)

@Serializable
data class ChatChoice(
    val index: Int = 0,
    val message: ChatMessage? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class ChatResponse(
    val id: String? = null,
    val model: String? = null,
    val choices: List<ChatChoice> = emptyList(),
)
