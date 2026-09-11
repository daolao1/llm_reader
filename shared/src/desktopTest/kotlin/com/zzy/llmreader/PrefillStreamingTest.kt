package com.zzy.llmreader

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.zzy.llmreader.data.LlmSettings
import com.zzy.llmreader.llm.ChapterTranslator
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 「助手预填 / 对话前缀续写」的回归测试。
 *
 * 背景：ST 系预设里最硬的一条破限手段不是措辞，而是**预填**（prefill）——
 * 替模型写好开头，它就只能顺着续写，没有机会以「抱歉 / 作为 AI / 我不能」起笔。
 * 翻译场景同理：把 `[1] ` 放进 assistant 回合，既堵死拒绝开场，又顺手锁死
 * `[编号]` 输出契约（调用方的 parser 依赖它）。
 *
 * 协议细节：DeepSeek 的「对话前缀续写（Beta）」要求最后一条消息是 assistant 且带
 * `"prefix": true`，响应**只返回续写部分**。因此客户端必须自己把前缀拼回去，
 * 否则译文会丢掉第一段 —— 下面第一条用例盯的就是这个。
 */
class PrefillStreamingTest {

    private val sourceHtml = "<p>こんにちは世界。</p><p>今日はいい天気ですね。</p>"

    private class Stub(val bodies: MutableList<String>, val server: HttpServer)

    private fun stub(respond: (body: String, index: Int) -> Pair<Int, String>): Stub {
        val bodies = Collections.synchronizedList(mutableListOf<String>())
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/chat/completions") { ex: HttpExchange ->
            val body = ex.requestBody.readBytes().decodeToString()
            bodies.add(body)
            val (code, resp) = respond(body, bodies.size - 1)
            val bytes = resp.toByteArray()
            ex.responseHeaders.add("Content-Type", "text/event-stream")
            ex.sendResponseHeaders(code, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        return Stub(bodies, server)
    }

    private fun esc(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    private fun sse(vararg chunks: String): String =
        chunks.joinToString("") { "data: {\"choices\":[{\"delta\":{\"content\":${esc(it)}}}]}\n\n" } +
            "data: [DONE]\n\n"

    /** 批翻译请求回 [batchSse]，其余（标题翻译）回一句标题。 */
    private fun respondWith(batchSse: String): (String, Int) -> Pair<Int, String> = { body, _ ->
        if (body.contains("【需要翻译的段落】")) 200 to batchSse else 200 to sse("第一章")
    }

    private suspend fun translate(settings: LlmSettings) = ChapterTranslator().translateStreaming(
        settings = settings,
        targetLanguage = "简体中文",
        chapterTitle = "第一章",
        chapterHtml = sourceHtml,
        onFinish = {},
    )

    private fun settingsFor(stub: Stub, model: String) =
        LlmSettings("http://127.0.0.1:${stub.server.address.port}", "sk-test", model)

    @Test
    fun `DeepSeek 前缀续写只返回续写部分时也要拼回第一段`() = runBlocking {
        // 关键：响应里没有 `[1] ` 标记 —— 真实的前缀续写就是这么返回的。
        val stub = stub(respondWith(sse("你好，世界。", "\n\n[2] 今天天气真好。")))
        try {
            val result = translate(settingsFor(stub, "deepseek-chat"))

            assertTrue(result is ChapterTranslator.TranslateResult.Success, "应翻译成功：$result")
            val html = (result as ChapterTranslator.TranslateResult.Success).html
            assertContains(html, "你好，世界。")
            assertContains(html, "今天天气真好。")

            val batch = stub.bodies.firstOrNull { it.contains("【需要翻译的段落】") }
            assertNotNull(batch, "没有捕获到批翻译请求")
            assertContains(batch, "\"prefix\":true")
            assertContains(batch, "\"role\":\"assistant\"")
            assertContains(batch, "[1] ")
            // 批翻译变体里也必须带上共用条款（反回避 / 恢复），否则等于只改了结构化链路。
            assertContains(batch, "【反回避条款】")
            assertContains(batch, "【错误恢复】")
        } finally {
            stub.server.stop(0)
        }
    }

    @Test
    fun `非 DeepSeek 服务商不发前缀续写字段`() = runBlocking {
        val stub = stub(respondWith(sse("[1] 你好，世界。", "\n\n[2] 今天天气真好。")))
        try {
            val result = translate(settingsFor(stub, "gpt-4o-mini"))

            assertTrue(result is ChapterTranslator.TranslateResult.Success, "应翻译成功：$result")
            assertTrue(
                stub.bodies.none { it.contains("\"prefix\"") },
                "prefix 是 DeepSeek Beta 字段，发给其他网关会 400",
            )
        } finally {
            stub.server.stop(0)
        }
    }

    @Test
    fun `网关拒绝前缀续写时自动回退且译文不受影响`() = runBlocking {
        val stub = stub { body, _ ->
            when {
                body.contains("\"prefix\":true") -> 400 to "{\"error\":\"prefix not supported\"}"
                body.contains("【需要翻译的段落】") ->
                    200 to sse("[1] 你好，世界。", "\n\n[2] 今天天气真好。")
                else -> 200 to sse("第一章")
            }
        }
        try {
            val result = translate(settingsFor(stub, "deepseek-chat"))

            assertTrue(result is ChapterTranslator.TranslateResult.Success, "回退后应翻译成功：$result")
            val batchBodies = stub.bodies.filter { it.contains("【需要翻译的段落】") }
            assertEquals(2, batchBodies.size, "应先带前缀失败、再去掉前缀重试一次")
            assertTrue(batchBodies[0].contains("\"prefix\":true"), "第一次应带前缀")
            assertFalse(batchBodies[1].contains("\"prefix\""), "回退请求不应再带前缀")
        } finally {
            stub.server.stop(0)
        }
    }

    @Test
    fun `前缀续写返回零内容时也要回退`() = runBlocking {
        // 有些网关收下了 prefix:true 却什么都不返回（或只有 [DONE]）。
        // 这时一个字译文都没发出去，必须当成"前缀模式不可用"回退，而不是整章失败。
        val stub = stub { body, _ ->
            when {
                body.contains("\"prefix\":true") -> 200 to "data: [DONE]\n\n"
                body.contains("【需要翻译的段落】") ->
                    200 to sse("[1] 你好，世界。", "\n\n[2] 今天天气真好。")
                else -> 200 to sse("第一章")
            }
        }
        try {
            val result = translate(settingsFor(stub, "deepseek-chat"))

            assertTrue(result is ChapterTranslator.TranslateResult.Success, "回退后应翻译成功：$result")
            val html = (result as ChapterTranslator.TranslateResult.Success).html
            assertContains(html, "你好，世界。")
            assertContains(html, "今天天气真好。")
            assertEquals(
                2,
                stub.bodies.count { it.contains("【需要翻译的段落】") },
                "应先带前缀拿到空流、再去掉前缀重试一次",
            )
        } finally {
            stub.server.stop(0)
        }
    }
}
