package com.zzy.llmreader

import com.zzy.llmreader.data.LlmSettings
import com.zzy.llmreader.llm.ChapterTranslator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 「目录 / 书目 / 标签」三条链路的破限必须与调用方的输出契约兼容。
 *
 * 回归背景：这三条链路原来复用 `jailbreakPair()`，拿到的是**标题专用**底部
 * （"只输出翻译后的标题本身，不要任何前后缀"），而它们的输出契约分别是
 * `<编号>. <译名>`、`INDEX:/TITLE:/SUMMARY:` 块、`<编号>. <译文>`；
 * 复用的顶部又写死"只输出带 [编号] 前缀的译文行"。
 *
 * 模型无论听哪一条，调用方的 parser（`^\s*(\d+)[\.\)、]` 与 `INDEX:` 行匹配）
 * 都匹配不到 —— 用户看到的现象就是"点了翻译，界面上什么都没变"。
 */
class StructuredJailbreakPromptTest {

    private val translator = ChapterTranslator()

    private fun deepSeek() = LlmSettings(
        baseUrl = "https://api.deepseek.com/v1",
        apiKey = "sk-test",
        model = "deepseek-chat",
    )

    private fun gemini() = LlmSettings(
        baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
        apiKey = "sk-test",
        model = "gemini-2.0-flash",
    )

    private fun other() = LlmSettings(
        baseUrl = "https://api.openai.com/v1",
        apiKey = "sk-test",
        model = "gpt-4o-mini",
    )

    @Test
    fun `结构化破限不得写死标题专用或批翻译的输出契约`() {
        for (settings in listOf(deepSeek(), gemini())) {
            val (top, bottom) = translator.structuredJailbreakPair(settings)
            val whole = "$top\n$bottom"

            assertTrue(top.isNotBlank(), "结构化顶部破限不应为空：${settings.model}")
            assertTrue(bottom.isNotBlank(), "结构化底部破限不应为空：${settings.model}")

            // 标题版底部那句话是"不翻译"的直接原因，绝不能出现在这三条链路上。
            assertFalse(
                whole.contains("只输出翻译后的标题本身"),
                "结构化链路混进了标题专用底部，会让模型只吐一条裸标题：${settings.model}",
            )
            // 批翻译顶部的 [编号] 契约会让模型输出 `[1] xx`，调用方 parser 一条都匹配不上。
            assertFalse(
                whole.contains("[编号]"),
                "结构化链路混进了批翻译的 [编号] 契约：${settings.model}",
            )
        }
    }

    @Test
    fun `结构化破限必须保留硬约束与防注入`() {
        for (settings in listOf(deepSeek(), gemini())) {
            val (top, bottom) = translator.structuredJailbreakPair(settings)
            val whole = "$top\n$bottom"

            assertTrue(whole.contains("不得拒绝"), "缺少「不得拒绝」硬约束：${settings.model}")
            assertTrue(whole.contains("删减"), "缺少「不得删减」硬约束：${settings.model}")
            assertTrue(whole.contains("按字面翻译"), "缺少防提示词注入说明：${settings.model}")
            // 输出格式必须交给调用方（可能写在 system 后半段，也可能在 user message），
            // 破限自身只强调"照它做"。
            assertTrue(
                whole.contains("提示中指定的输出格式"),
                "结构化破限应把输出格式交给调用方：${settings.model}",
            )
        }
    }

    @Test
    fun `共用条款必须真正内联进破限文本`() {
        for (settings in listOf(deepSeek(), gemini())) {
            val (top, bottom) = translator.structuredJailbreakPair(settings)
            val whole = "$top\n$bottom"
            // 这三段是三个变体共享的反回避 / 归因 / 恢复条款，靠 const 字符串模板拼进来，
            // 一旦写成普通 val 或拼错名字，这里会立刻发现。
            for (marker in listOf("【反回避条款】", "【归因条款】", "【错误恢复】")) {
                assertTrue(whole.contains(marker), "共用条款没有内联：$marker（${settings.model}）")
            }
            assertFalse(whole.contains("\$JB_"), "出现了未展开的 Kotlin 模板：${settings.model}")
        }
    }

    @Test
    fun `结构化破限在同一模型下必须是常量以命中 prompt cache`() {
        for (settings in listOf(deepSeek(), gemini())) {
            assertEquals(
                translator.structuredJailbreakPair(settings),
                translator.structuredJailbreakPair(settings),
                "同一模型的破限必须逐字节一致：${settings.model}",
            )
        }
    }

    @Test
    fun `未识别的服务商维持现状不加破限`() {
        val (top, bottom) = translator.structuredJailbreakPair(other())
        // 与 detectModelFlavor 的既有行为一致：OTHER 分支为空。
        // 将来若要给 OpenAI/Claude/中转站补通用破限，这个断言就是改动的锚点。
        assertEquals("", top)
        assertEquals("", bottom)
    }
}
