package com.zzy.llmreader.data

/**
 * 大模型服务商预设。
 *
 * 之前 Base URL 是个纯空输入框：新用户既不知道该填什么，也很容易把
 * 「文档里那条完整地址」粘进来 —— 而 `LlmClient` 会在后面再拼一次
 * `/chat/completions`，于是拼成 `.../chat/completions/chat/completions` 报 404。
 *
 * 这里给出常见服务商的地址与常用模型，UI 只要让用户选一个即可，
 * 选完自动填 Base URL（模型栏为空时顺带填个常用的），用户仍可改成自定义。
 *
 * 放在 shared 里是为了 Android 和桌面端共用同一份清单。
 */
data class LlmProviderPreset(
    /** 稳定 ID，用于勾选状态。 */
    val id: String,
    val displayName: String,
    /** OpenAI 兼容端点，**填到 /v1 为止**，不带 /chat/completions。 */
    val baseUrl: String,
    /** 该服务商常用模型，用于占位提示与「一键填入」。 */
    val suggestedModels: List<String> = emptyList(),
    /** 去哪里申请 API Key。 */
    val keyHint: String = "",
    /** 「自定义」项：不自动填地址。 */
    val custom: Boolean = false,
)

object LlmPresets {

    val CUSTOM = LlmProviderPreset(
        id = "custom",
        displayName = "自定义",
        baseUrl = "",
        keyHint = "填任何 OpenAI 兼容服务的地址，到 /v1 为止",
        custom = true,
    )

    /**
     * 顺序即 UI 展示顺序：先国内常用，再国外，最后本地部署。
     * 都是各服务商公开的 OpenAI 兼容端点。
     */
    val all: List<LlmProviderPreset> = listOf(
        LlmProviderPreset(
            id = "zhipu",
            displayName = "智谱 GLM",
            baseUrl = "https://open.bigmodel.cn/api/paas/v4",
            suggestedModels = listOf("glm-4-flash", "glm-4-plus", "glm-4-long"),
            keyHint = "在 bigmodel.cn 控制台 → API Keys 创建",
        ),
        LlmProviderPreset(
            id = "deepseek",
            displayName = "DeepSeek",
            baseUrl = "https://api.deepseek.com/v1",
            suggestedModels = listOf("deepseek-chat", "deepseek-reasoner"),
            keyHint = "在 platform.deepseek.com → API keys 创建",
        ),
        LlmProviderPreset(
            id = "dashscope",
            displayName = "通义千问",
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            suggestedModels = listOf("qwen-plus", "qwen-turbo", "qwen-max"),
            keyHint = "在阿里云百炼控制台创建 API-KEY",
        ),
        LlmProviderPreset(
            id = "moonshot",
            displayName = "Kimi (月之暗面)",
            baseUrl = "https://api.moonshot.cn/v1",
            suggestedModels = listOf("moonshot-v1-8k", "moonshot-v1-32k"),
            keyHint = "在 platform.moonshot.cn → API Key 管理创建",
        ),
        LlmProviderPreset(
            id = "siliconflow",
            displayName = "硅基流动",
            baseUrl = "https://api.siliconflow.cn/v1",
            suggestedModels = listOf(
                "deepseek-ai/DeepSeek-V3",
                "Qwen/Qwen2.5-7B-Instruct",
            ),
            keyHint = "在 siliconflow.cn → API 密钥创建",
        ),
        LlmProviderPreset(
            id = "hunyuan",
            displayName = "腾讯混元",
            baseUrl = "https://api.hunyuan.cloud.tencent.com/v1",
            suggestedModels = listOf("hunyuan-lite", "hunyuan-standard"),
            keyHint = "在腾讯云控制台 → 混元大模型 → API Key",
        ),
        LlmProviderPreset(
            id = "openai",
            displayName = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            suggestedModels = listOf("gpt-4o-mini", "gpt-4o"),
            keyHint = "在 platform.openai.com → API keys 创建",
        ),
        LlmProviderPreset(
            id = "openrouter",
            displayName = "OpenRouter",
            baseUrl = "https://openrouter.ai/api/v1",
            suggestedModels = listOf("google/gemini-2.0-flash-001", "anthropic/claude-3.5-sonnet"),
            keyHint = "在 openrouter.ai → Keys 创建（一个 key 可用多家模型）",
        ),
        LlmProviderPreset(
            id = "ollama",
            displayName = "本地 Ollama",
            baseUrl = "http://127.0.0.1:11434/v1",
            suggestedModels = listOf("qwen2.5:7b", "llama3.1:8b"),
            keyHint = "本地服务，API Key 随便填一个非空值即可",
        ),
        LlmProviderPreset(
            id = "lmstudio",
            displayName = "本地 LM Studio",
            baseUrl = "http://127.0.0.1:1234/v1",
            suggestedModels = listOf("local-model"),
            keyHint = "本地服务，API Key 随便填一个非空值即可",
        ),
    )

    /** 带「自定义」的完整列表，供 UI 直接使用。 */
    val allWithCustom: List<LlmProviderPreset> = all + CUSTOM

    /**
     * 按 Base URL 反查服务商；匹配不到返回 [CUSTOM]（说明用户填的是自己的地址）。
     * 比较时忽略末尾斜杠与大小写。
     */
    fun match(baseUrl: String): LlmProviderPreset {
        val normalized = baseUrl.trim().trimEnd('/').lowercase()
        if (normalized.isBlank()) return CUSTOM
        return all.firstOrNull { it.baseUrl.trimEnd('/').lowercase() == normalized } ?: CUSTOM
    }
}
