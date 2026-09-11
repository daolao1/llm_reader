package com.zzy.llmreader

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.zzy.llmreader.data.LlmSettings
import com.zzy.llmreader.llm.ChapterTranslator
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 「边翻边看」的回归测试：**部分译文必须在生成过程中反复到达，而不是只在批末到一次。**
 *
 * 这条曾经被违反过，而且不会以"译文错误"的形式暴露 ——
 * 增量解析器把标记扫描起点设在了「本轮新增 delta 的起点」，而 SSE 会把 `[12]` 拆成
 * `[1` + `2] ` 两个 delta。于是 `[` 落在上一轮、`]` 落在这一轮，标记永远匹配不到，
 * 流式回调一次都不发；而收尾走的是全量重扫，**最终译文完全正确**。
 * 表现出来就是"流式生成没有了"，整章只在结尾一次性出现。
 *
 * 关键点：**桩服务端必须真的慢**。若服务端瞬时返回，250ms 节流会把中间推送全部吞掉、
 * 只剩批末那一次 —— 那样即使解析器坏了也测不出来（最初的测试版本正是因此漏掉了它）。
 */
class StreamingTimelineTest {

    private class Stub(private val perDeltaDelayMs: Long) {
        private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val settings: LlmSettings
            get() = LlmSettings("http://127.0.0.1:${server.address.port}", "sk-test", "test-model")

        fun start() {
            server.executor = Executors.newFixedThreadPool(8)
            server.createContext("/chat/completions") { ex -> handle(ex) }
            server.start()
        }

        fun stop() = server.stop(0)

        /** 逐段流式吐字，delta 之间 sleep，模拟真实模型生成速度。 */
        private fun handle(ex: HttpExchange) {
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            ex.responseHeaders.add("Content-Type", "text/event-stream")
            ex.sendResponseHeaders(200, 0)
            ex.responseBody.use { out ->
                fun send(text: String) {
                    val line = "data: {\"choices\":[{\"delta\":{\"content\":\"" +
                        text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") +
                        "\"}}]}\n\n"
                    out.write(line.toByteArray())
                    out.flush()
                    if (perDeltaDelayMs > 0) Thread.sleep(perDeltaDelayMs)
                }
                if (!body.contains("【需要翻译的段落】")) {
                    send("第一章")
                } else {
                    val block = body.substringAfter("【需要翻译的段落】", "")
                        .substringBeforeLast("[最终翻译提醒")
                    val nums = Regex("""ばんごう (\d+) の原文""").findAll(block)
                        .mapNotNull { it.groupValues[1].toIntOrNull() }.distinct().toList()
                    nums.forEachIndexed { local, global ->
                        // 关键：每 2 字符一个 delta —— `[N] ` 标记必然被拆到两个 delta 里
                        "[${local + 1}] 这是第 $global 段的译文内容。".chunked(2).forEach { send(it) }
                        send("\n\n")
                    }
                }
                out.write("data: [DONE]\n\n".toByteArray())
                out.flush()
            }
        }
    }

    /** 返回每次 onPartial 到达时的「已翻译段数」序列。 */
    private fun partialTimeline(label: String, paragraphs: Int, perDeltaDelayMs: Long): List<Int> {
        val html = (1..paragraphs).joinToString("") { i ->
            "<p>これはばんごう $i の原文です。それなりの長さの文を入れておきます。</p>"
        }
        val stub = Stub(perDeltaDelayMs)
        stub.start()
        return try {
            val paragraphCounts = ArrayList<Int>()
            val result = runBlocking {
                ChapterTranslator().translateStreaming(
                    settings = stub.settings,
                    targetLanguage = "简体中文",
                    chapterTitle = "第一章",
                    chapterHtml = html,
                    batchChars = 700,
                    onPartial = { _, h ->
                        paragraphCounts.add(
                            h.split("<p>").count { it.isNotBlank() && !it.contains("これはばんごう") },
                        )
                    },
                    onFinish = {},
                )
            }
            assertTrue(result is ChapterTranslator.TranslateResult.Success, "[$label] 翻译失败：$result")
            paragraphCounts
        } finally {
            stub.stop()
        }
    }

    @Test
    fun `部分译文必须在生成过程中反复到达而不是只在批末`() {
        val counts = partialTimeline("慢速", paragraphs = 10, perDeltaDelayMs = 40)

        assertTrue(
            counts.size >= 3,
            "流式回调只到达 ${counts.size} 次 —— 「边翻边看」失效。实际段数序列：$counts",
        )
        assertTrue(
            counts.first() < 10,
            "第一次部分译文就已是 ${counts.first()} 段（整章 10 段）—— 说明只推了批末结果",
        )
        // 段数必须单调不回退，否则界面上会看到已翻译的内容又变回原文
        assertTrue(counts == counts.sorted(), "部分译文的段数出现回退：$counts")
        assertTrue(counts.last() == 10, "最后一次部分译文只有 ${counts.last()} 段，应为 10 段")
    }

    @Test
    fun `标记被拆进两个 delta 时仍能识别`() {
        // 桩固定每 2 字符一个 delta：`[1] ` 与 `[10] ` 都必然跨 delta
        val counts = partialTimeline("跨delta标记", paragraphs = 12, perDeltaDelayMs = 20)
        assertTrue(
            counts.last() == 12,
            "最终只渲染出 ${counts.last()} 段，应为 12 段（标记识别失败会丢段）",
        )
        assertTrue(counts.size >= 2, "只推送了 ${counts.size} 次，流式未生效")
    }
}
