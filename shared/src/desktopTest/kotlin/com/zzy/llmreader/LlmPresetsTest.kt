package com.zzy.llmreader

import com.zzy.llmreader.data.LlmPresets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 服务商预设的回归测试。
 *
 * 重点锁住一个具体坑：`LlmClient` 会做 `baseUrl.trimEnd('/') + "/chat/completions"`，
 * 所以预设里**绝不能**给出带 `/chat/completions` 的完整地址，
 * 否则请求会拼成 `.../chat/completions/chat/completions` 直接 404。
 */
class LlmPresetsTest {

    @Test
    fun `按地址反查服务商`() {
        assertEquals("zhipu", LlmPresets.match("https://open.bigmodel.cn/api/paas/v4").id)
        assertEquals("deepseek", LlmPresets.match("https://api.deepseek.com/v1").id)
        assertEquals("openai", LlmPresets.match("https://api.openai.com/v1").id)
    }

    @Test
    fun `末尾斜杠与大小写不影响匹配`() {
        assertEquals("openai", LlmPresets.match("https://api.openai.com/v1/").id)
        assertEquals("openai", LlmPresets.match("HTTPS://API.OPENAI.COM/V1").id)
        assertEquals("openai", LlmPresets.match("  https://api.openai.com/v1  ").id)
    }

    @Test
    fun `未知地址与空地址都归为自定义`() {
        assertEquals(LlmPresets.CUSTOM, LlmPresets.match(""))
        assertEquals(LlmPresets.CUSTOM, LlmPresets.match("   "))
        assertEquals(LlmPresets.CUSTOM, LlmPresets.match("https://my-own-proxy.internal/v1"))
        assertTrue(LlmPresets.CUSTOM.custom)
    }

    @Test
    fun `预设地址不能带 chat-completions 后缀`() {
        LlmPresets.all.forEach { p ->
            assertTrue(
                !p.baseUrl.trimEnd('/').endsWith("/chat/completions"),
                "${p.displayName} 的地址带了 /chat/completions，会让请求拼成两遍：" +
                    "${p.baseUrl}。请只填到 /v1。",
            )
        }
    }

    @Test
    fun `每个预设都要有可用信息`() {
        val ids = LlmPresets.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "预设 id 不能重复")
        LlmPresets.all.forEach { p ->
            assertTrue(
                p.baseUrl.startsWith("https://") || p.baseUrl.startsWith("http://"),
                "${p.displayName} 的地址缺少 scheme：${p.baseUrl}",
            )
            assertTrue(p.displayName.isNotBlank(), "预设 ${p.id} 缺少显示名")
            assertTrue(p.suggestedModels.isNotEmpty(), "${p.displayName} 没给推荐模型")
            assertTrue(p.keyHint.isNotBlank(), "${p.displayName} 没给申请密钥的提示")
        }
    }

    @Test
    fun `自定义项排在最末且不自动填地址`() {
        assertEquals(LlmPresets.CUSTOM, LlmPresets.allWithCustom.last())
        assertEquals("", LlmPresets.CUSTOM.baseUrl)
        assertEquals(LlmPresets.all.size + 1, LlmPresets.allWithCustom.size)
    }
}
