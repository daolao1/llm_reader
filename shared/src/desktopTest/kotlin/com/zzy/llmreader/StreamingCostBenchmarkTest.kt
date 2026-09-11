package com.zzy.llmreader

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.zzy.llmreader.data.LlmSettings
import com.zzy.llmreader.llm.ChapterTranslator
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 流式翻译路径的**规模压力测试**。
 *
 * 这里测的不是"算法副本"，而是真实的 [ChapterTranslator]：本地 SSE 桩服务端逐字吐出
 * 一整章译文，统计回调次数与耗时。它守住的是一条曾经被违反的性质：
 *
 * > 每个 SSE delta 的处理代价必须与**新增字符数**成正比，不能与**整章长度**成正比。
 *
 * 旧实现每个 delta 都重扫整个累积 buffer 并重建整章 HTML（O(n²)）。实测一章 20k 字要吃掉
 * 1.6 秒、累计构造 341 MB 字符串（40k 字时 5.4 秒 / 1.37 GB）；这些回调是**同步跑在 SSE
 * 读取循环里**的，等于直接拖慢收字速度，而且在 Android 上就是 GC 抖动与掉帧。
 *
 * 因此断言不能只是"能跑完"，还要卡住两个可观测的量：
 * - 部分译文的下发次数必须被节流（否则组合期会反复重建整章 Spanned/布局）；
 * - 端到端耗时必须有量级上的余量（O(n²) 回归会让它爆掉）。
 */
class StreamingCostBenchmarkTest {

    private class StreamStub(private val paragraphs: Int) {
        val maxConcurrent = AtomicInteger(0)
        private val concurrent = AtomicInteger(0)
        private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

        val settings: LlmSettings
            get() = LlmSettings("http://127.0.0.1:${server.address.port}", "sk-test", "test-model")

        fun start() {
            server.executor = Executors.newFixedThreadPool(8)
            server.createContext("/chat/completions") { ex -> handle(ex) }
            server.start()
        }

        fun stop() = server.stop(0)

        private fun handle(ex: HttpExchange) {
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            maxConcurrent.updateAndGet { maxOf(it, concurrent.incrementAndGet()) }
            try {
                val sse = if (!body.contains("【需要翻译的段落】")) {
                    sseOf(listOf("第一章"))
                } else {
                    // 真实提示词里每批的编号都是局部的 1..N，桩按"本批包含哪些原文段"回译文
                    val block = body.substringAfter("【需要翻译的段落】", "")
                        .substringBeforeLast("[最终翻译提醒")
                    val nums = Regex("""ばんごう (\d+) の原文""").findAll(block)
                        .mapNotNull { it.groupValues[1].toIntOrNull() }.distinct().toList()
                    sseOf(nums.mapIndexed { local, global -> "[${local + 1}] 这是第 $global 段的译文内容。" })
                }
                val bytes = sse.toByteArray()
                ex.responseHeaders.add("Content-Type", "text/event-stream")
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            } finally {
                concurrent.decrementAndGet()
            }
        }

        /** 逐字（3 字符一个 delta）流式吐出，模拟真实 token 流。 */
        private fun sseOf(lines: List<String>): String {
            val sb = StringBuilder()
            for (line in lines) {
                line.chunked(3).forEach { piece ->
                    sb.append("data: {\"choices\":[{\"delta\":{\"content\":\"")
                        .append(piece.replace("\"", "\\\"").replace("\n", "\\n"))
                        .append("\"}}]}\n\n")
                }
                sb.append("data: {\"choices\":[{\"delta\":{\"content\":\"\\n\\n\"}}]}\n\n")
            }
            sb.append("data: [DONE]\n\n")
            return sb.toString()
        }
    }

    private fun translateLargeChapter(
        paragraphs: Int,
    ): Pair<Int, Pair<ChapterTranslator.TranslateResult, Long>> {
        val html = (1..paragraphs).joinToString("") { i ->
            "<p>これはばんごう $i の原文です。それなりの長さの文を入れておきます。</p>"
        }
        val stub = StreamStub(paragraphs)
        stub.start()
        return try {
            val partials = AtomicInteger(0)
            val t0 = System.nanoTime()
            val result = runBlocking {
                ChapterTranslator().translateStreaming(
                    settings = stub.settings,
                    targetLanguage = "简体中文",
                    chapterTitle = "第一章",
                    chapterHtml = html,
                    batchChars = 4000,
                    concurrency = 1,
                    onPartial = { _, _ -> partials.incrementAndGet() },
                    onFinish = {},
                )
            }
            val elapsedMs = (System.nanoTime() - t0) / 1_000_000
            partials.get() to (result to elapsedMs)
        } finally {
            stub.stop()
        }
    }

    @Test
    fun `大章节的流式回调必须线性而不是二次`() {
        // 约 6 万字的输出量，delta 数在万级 —— 旧实现这里要吃掉数秒并构造 GB 级字符串
        val paragraphs = 300
        val (partialCount, pair) = translateLargeChapter(paragraphs)
        val (result, elapsedMs) = pair

        assertTrue(result is ChapterTranslator.TranslateResult.Success, "翻译失败：$result")
        val html = (result as ChapterTranslator.TranslateResult.Success).html
        for (n in 1..paragraphs) {
            assertTrue(html.contains("这是第 $n 段的译文内容。"), "第 $n 段译文缺失")
        }
        println("[规模] 段数=$paragraphs 端到端=${elapsedMs}ms 部分译文下发=$partialCount 次")

        // 下发必须被节流：300 段若每个 delta 都推一次，会是上万次
        assertTrue(
            partialCount < paragraphs * 4,
            "部分译文下发 $partialCount 次，节流失效（每段应当远少于 4 次）",
        )
        // O(n²) 回归会让这一条直接爆掉（旧实现相同规模约数秒）
        assertTrue(elapsedMs < 60_000, "端到端 ${elapsedMs}ms 过慢，疑似退回 O(n²) 回调")
    }

    @Test
    fun `推送给调用方的部分译文始终是内容单调增长的完整章节`() {
        val paragraphs = 60
        val html = (1..paragraphs).joinToString("") { i ->
            "<p>これはばんごう $i の原文です。</p>"
        }
        val stub = StreamStub(paragraphs)
        stub.start()
        try {
            val seen = ArrayList<String>()
            val result = runBlocking {
                ChapterTranslator().translateStreaming(
                    settings = stub.settings,
                    targetLanguage = "简体中文",
                    chapterTitle = "第一章",
                    chapterHtml = html,
                    batchChars = 1000,
                    concurrency = 1,
                    onPartial = { _, h -> if (h.isNotBlank()) seen.add(h) },
                    onFinish = {},
                )
            }
            assertTrue(result is ChapterTranslator.TranslateResult.Success, "翻译失败：$result")
            assertTrue(seen.isNotEmpty(), "从未下发部分译文 —— 边翻边看会完全失效")

            // 每次下发的段落数不得减少（否则界面上会看到已翻译的内容又变回原文）
            val counts = seen.map { Regex("<p>").findAll(it).count() }
            assertEquals(counts.sorted(), counts, "部分译文的段落数出现回退：$counts")
            // 最后一次应当是完整的一章
            assertEquals(paragraphs, counts.last(), "最后一次下发的段落数不是完整章节")
        } finally {
            stub.stop()
        }
    }
}
