package com.zzy.llmreader

import com.sun.net.httpserver.HttpServer
import com.zzy.llmreader.data.LlmSettings
import com.zzy.llmreader.llm.ChapterTranslator
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * 「翻译失败必须被判定为失败」的回归测试。
 *
 * 背景：旧实现有三处叠加，会让**完全没翻译**的章节被当成翻译成功并永久写进缓存：
 *  1. `LlmClient.chatStream` 无条件 `return full.toString()`：网关忽略 `stream=true`
 *     返回普通 JSON 时，没有一行以 `data:` 开头，于是返回**空串且判定为成功**；
 *  2. `ChapterTranslator` 对解析不出的段落逐段回退成**原文**（`?: batch[i]`）；
 *  3. 收尾仍然 `return TranslateResult.Success(...)`。
 *
 * 调用方随后把这份「原文」当作译文落盘并打上 `translationLanguage`，
 * 该章此后被判定为已翻译、再也不会重试，界面上没有任何报错 —— 无声的数据损坏。
 *
 * 这三条现在都被堵住了，同时下面还断言**正常翻译不会被误伤**。
 */
class TranslationFailureDetectionTest {

    private val sourceHtml = "<p>こんにちは世界。</p><p>今日はいい天気ですね。</p>"

    private fun withStubServer(body: String, contentType: String, block: suspend (LlmSettings) -> Unit) =
        runBlocking {
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/chat/completions") { ex ->
                val bytes = body.toByteArray()
                ex.responseHeaders.add("Content-Type", contentType)
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            server.start()
            try {
                block(LlmSettings("http://127.0.0.1:${server.address.port}", "sk-test", "test-model"))
            } finally {
                server.stop(0)
            }
        }

    /**
     * 走**生产实际使用的那条路径**：`translateStreaming`（App 两端都改用它，
     * 因为只有它接受 concurrency、并把结果从 onFinish 交还）。
     *
     * 另有 [translate 便捷重载] 一条用例覆盖 `translate()` 包装器本身。
     */
    private suspend fun translate(settings: LlmSettings) = ChapterTranslator().translateStreaming(
        settings = settings,
        targetLanguage = "简体中文",
        chapterTitle = "第一章",
        chapterHtml = sourceHtml,
        onFinish = {},
    )

    @Test
    fun `网关忽略 stream 返回普通 JSON 时必须判失败`() = withStubServer(
        """{"choices":[{"message":{"role":"assistant","content":"Sure!"}}]}""",
        "application/json",
    ) { settings ->
        val result = translate(settings)
        assertTrue(
            result is ChapterTranslator.TranslateResult.Failure,
            "非 SSE 的 200 响应曾被当作成功并返回空串，必须判失败。实际：$result",
        )
        assertContains(result.message, "SSE")
    }

    @Test
    fun `模型拒答只输出散文时必须判失败`() = withStubServer(
        "data: {\"choices\":[{\"delta\":{\"content\":\"抱歉，我不能翻译这段内容。\"}}]}\n\n" +
            "data: [DONE]\n\n",
        "text/event-stream",
    ) { settings ->
        val result = translate(settings)
        assertTrue(
            result is ChapterTranslator.TranslateResult.Failure,
            "模型拒答时逐段回退成原文，曾被当作成功。实际：$result",
        )
        assertContains(result.message, "原样返回")
    }

    @Test
    fun `空流必须判失败`() = withStubServer("data: [DONE]\n\n", "text/event-stream") { settings ->
        val result = translate(settings)
        assertTrue(result is ChapterTranslator.TranslateResult.Failure, "空流必须判失败。实际：$result")
    }

    @Test
    fun `正常翻译不能被误伤`() = withStubServer(
        "data: {\"choices\":[{\"delta\":{\"content\":\"[1] 你好世界。\"}}]}\n\n" +
            "data: {\"choices\":[{\"delta\":{\"content\":\"\\n[2] 今天天气真好。\"}}]}\n\n" +
            "data: [DONE]\n\n",
        "text/event-stream",
    ) { settings ->
        val result = translate(settings)
        assertTrue(result is ChapterTranslator.TranslateResult.Success, "正常翻译被误判为失败：$result")
        assertContains(result.html, "你好世界")
        assertContains(result.html, "今天天气真好")
        assertTrue(!result.html.contains("こんにちは"), "译文里不应残留日文原文")
    }

    /** `translate()` 是给"只要结果、不关心流式"的调用方留的便捷重载，别让它悄悄失效。 */
    @Test
    fun `便捷重载 translate 仍返回同一份结果`() = withStubServer(
        "data: {\"choices\":[{\"delta\":{\"content\":\"[1] 你好世界。\"}}]}\n\n" +
            "data: {\"choices\":[{\"delta\":{\"content\":\"\\n[2] 今天天气真好。\"}}]}\n\n" +
            "data: [DONE]\n\n",
        "text/event-stream",
    ) { settings ->
        val result = ChapterTranslator().translate(
            settings = settings,
            targetLanguage = "简体中文",
            chapterTitle = "第一章",
            chapterHtml = sourceHtml,
        )
        assertTrue(result is ChapterTranslator.TranslateResult.Success, "便捷重载失败：$result")
        assertContains(result.html, "你好世界")
        assertContains(result.html, "今天天气真好")
    }
}
