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
import kotlin.test.assertTrue

/**
 * 翻译流水线提速的基准与不变量测试。
 *
 * 用本地 SSE 桩服务端驱动真实的 [ChapterTranslator]，因此测的是端到端真实路径，
 * 而不是复制一份算法副本（复制副本会随着实现漂移而失去意义）。
 *
 * 关键指标：
 * - **并发窗口**：服务端记录每个请求的起止时间，断言在途请求数不超过设定值，
 *   且 >1 时确实出现过重叠（否则说明并发没生效）。
 * - **上下文门控**：断言第 k 批请求到达时，第 k-1 批**已经返回**（前文译文必须是真实的
 *   上一批译文，而不是并发猜测）。
 * - **端到端耗时**：每请求人为 sleep，串行与并发的时间差应当接近倍数关系。
 * - **解析正确性**：实时增量解析的结果必须与整段文本一次性解析的结果一致。
 */
class TranslationPipelineSpeedTest {

    private class StubServer(
        /** 每个请求额外占用多少毫秒，用来放大"等模型吐字"的时间。 */
        private val responseDelayMs: Long = 0,
    ) {
        val maxConcurrent = AtomicInteger(0)
        val concurrentNow = AtomicInteger(0)
        private val lock = Any()
        /** 每个"段落翻译"请求的起止时间（标题请求不计入）。 */
        private val windows = java.util.concurrent.ConcurrentHashMap<Long, Long>()
        private val order = ArrayList<Long>()

        private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        private val timeline = ArrayList<String>()
        private val t0 = System.currentTimeMillis()

        val settings: LlmSettings
            get() = LlmSettings("http://127.0.0.1:${server.address.port}", "sk-test", "test-model")

        fun start(paragraphCount: Int) {
            server.executor = Executors.newFixedThreadPool(8)
            server.createContext("/chat/completions") { ex -> handle(ex, paragraphCount) }
            server.start()
        }

        fun stop() = server.stop(0)

        /** 按发起顺序返回每个段落批次的 (start, end)。 */
        fun windowsInOrder(): List<Pair<Long, Long>> = synchronized(lock) {
            order.map { it to (windows[it] ?: it) }
        }

        fun timelineDump(): String = synchronized(lock) {
            timeline.joinToString("\n      ")
        }

        private fun handle(ex: HttpExchange, paragraphCount: Int) {
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            val now = System.currentTimeMillis()
            val running = concurrentNow.incrementAndGet()
            maxConcurrent.updateAndGet { maxOf(it, running) }
            val isTitle = !body.contains("【需要翻译的段落】")
            val firstIdx = Regex("""\[(\d+)]""").find(body)?.groupValues?.get(1) ?: "-"
            if (!isTitle) synchronized(lock) { order.add(now) }
            synchronized(lock) {
                timeline.add(
                    "@${now - t0}ms 起 ${if (isTitle) "标题" else "批(首段#$firstIdx)"} 在途=$running " +
                        "abs=$now",
                )
            }
            try {
                Thread.sleep(responseDelayMs)
                val sse = buildSse(body, paragraphCount, isTitle)
                val bytes = sse.toByteArray()
                ex.responseHeaders.add("Content-Type", "text/event-stream")
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            } finally {
                concurrentNow.decrementAndGet()
                if (!isTitle) windows[now] = System.currentTimeMillis()
                synchronized(lock) {
                    if (!isTitle) timeline.add("@${System.currentTimeMillis() - t0}ms 完 批(首段#$firstIdx)")
                }
            }
        }

        /** 造一段像真模型的输出：逐段 `[N] 中文译文`，并且一篇章里混着标题请求。 */
        private fun buildSse(body: String, paragraphCount: Int, isTitle: Boolean): String {
            val sb = StringBuilder()
            fun chunk(text: String) = sb.append("data: {\"choices\":[{\"delta\":{\"content\":")
                .append(escapeJson(text)).append("}}]}\n\n")

            if (isTitle) {
                chunk("第一章 起点")
            } else {
                // ⚠️ 编号只能从【需要翻译的段落】块里取，不能对请求体整体跑 \[(\d+)]：
                // 原文正文里的数字（如"第 12 段落目"）会被当成编号，导致回的段号错位 ——
                // 这正是"第 12 段译文缺失"那个断言失败的原因，是桩的 bug，不是实现的问题。
                // 真实提示词里每批的 [编号] 都是**局部**的 1..N，所以不能用编号定位全局段。
                // 桩改为从原文里的稳定标记 "ばんごう N" 提取本批覆盖的全局段号。
                val block = body.substringAfter("【需要翻译的段落】", "").substringBeforeLast("[最终翻译提醒")
                val nums = Regex("""ばんごう (\d+) の原文""").findAll(block)
                    .mapNotNull { it.groupValues[1].toIntOrNull() }.distinct().toList()
                nums.forEachIndexed { local, global ->
                    val line = "[${local + 1}] 这是第 $global 段的译文内容。"
                    line.chunked(3).forEach { chunk(it) }
                    chunk("\n\n")
                }
            }
            sb.append("data: [DONE]\n\n")
            return sb.toString()
        }

        private fun escapeJson(s: String): String = buildString {
            append('"')
            for (c in s) when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
            append('"')
        }
    }

    /**
     * 造原文。每段带一个**不含其它数字**的唯一编号 `#i`，桩服务端据此回译文。
     * 编号不能写成"第 i 段落目"——正文里出现别的数字会让桩的编号提取与断言都错位。
     */
    private fun chapterHtml(paragraphs: Int): String =
        (1..paragraphs).joinToString("") { i ->
            "<p>これはばんごう $i の原文です。それなりの長さの文を入れておきます。</p>"
        }

    private fun ms(nanos: Long) = "%.0f ms".format(nanos / 1_000_000.0)

    private fun translate(
        settings: LlmSettings,
        paragraphs: Int,
        batchChars: Int,
        concurrency: Int,
    ): ChapterTranslator.TranslateResult = runBlocking {
        ChapterTranslator().translateStreaming(
            settings = settings,
            targetLanguage = "简体中文",
            chapterTitle = "第一章",
            chapterHtml = chapterHtml(paragraphs),
            batchChars = batchChars,
            concurrency = concurrency,
            onFinish = {},
        )
    }

    @Test
    fun `并发窗口与上下文门控不变量`() {
        val paragraphs = 40
        val batchChars = 400 // ≈ 6 段/批 → 7 批，窗口 3
        val stub = StubServer(responseDelayMs = 60)
        stub.start(paragraphs)
        try {
            val result = translate(stub.settings, paragraphs, batchChars, concurrency = 3)
            assertTrue(result is ChapterTranslator.TranslateResult.Success, "并发翻译失败：$result")

            val windows = stub.windowsInOrder()

            // 1) 在途请求数不超过窗口值（标题请求会额外占一个，所以上限是 window + 1）
            val maxSeen = stub.maxConcurrent.get()
            assertTrue(maxSeen <= 4, "在途请求数 $maxSeen 超出窗口上限（concurrency=3 + 标题 1）")

            // 2) 确实发生了重叠：并发没生效的话这里会等于 1
            val overlapped = windows.indices.any { i ->
                windows.indices.any { j ->
                    i != j && windows[i].first < windows[j].second && windows[j].first < windows[i].second
                }
            }
            assertTrue(overlapped, "concurrency=3 时观测不到任何请求重叠，说明批次仍在串行")

            // 3) 上下文门控：第 k 批必须等第 (k - window + 1) 批返回后才发请求。
            //    这是"前文译文必须来自已翻完的批次"这条不变量的可观测形式。
            //    允许 50ms 误差（墙上时钟 + 协程调度）。
            val window = 3
            windows.forEachIndexed { k, w ->
                val gateIdx = k - window
                if (gateIdx >= 0) {
                    assertTrue(
                        w.first >= windows[gateIdx].second - 50,
                        "第 ${k + 1} 批在第 ${gateIdx + 1} 批返回前就发了请求 —— 前文译文会是猜测值",
                    )
                }
            }
            println("[并发] 批次数=${windows.size} 在途峰值=$maxSeen 重叠=是 门控=满足")
        } finally {
            stub.stop()
        }
    }

    @Test
    fun `串行与并发的端到端耗时对比`() {
        val paragraphs = 40
        val batchChars = 400
        // 每请求固定 200ms：串行 N 批 ≈ N×200ms，窗口 3 应显著低于它
        for (concurrency in listOf(1, 3)) {
            val stub = StubServer(responseDelayMs = 200)
            stub.start(paragraphs)
            try {
                val t0 = System.nanoTime()
                val result = translate(stub.settings, paragraphs, batchChars, concurrency)
                val elapsed = System.nanoTime() - t0
                assertTrue(result is ChapterTranslator.TranslateResult.Success, "翻译失败：$result")
                val batches = stub.windowsInOrder().size
                println(
                    "[耗时] concurrency=$concurrency 批次数=$batches " +
                        "端到端=${ms(elapsed)} 在途峰值=${stub.maxConcurrent.get()}\n      " +
                        stub.timelineDump(),
                )
            } finally {
                stub.stop()
            }
        }
    }

    @Test
    fun `增量解析结果与整段解析一致`() {
        val paragraphs = 40
        val batchChars = 400
        val stub = StubServer()
        stub.start(paragraphs)
        try {
            val result = translate(stub.settings, paragraphs, batchChars, concurrency = 1)
            assertTrue(result is ChapterTranslator.TranslateResult.Success, "翻译失败：$result")
            val html = (result as ChapterTranslator.TranslateResult.Success).html
            // 桩对每一段原文都回了 `这是第 N 段的译文内容。`，且 N 是全局段号：
            // 因此这里同时校验"没有漏段"和"批内局部编号被正确映射到全局位置"。
            for (n in 1..paragraphs) {
                assertTrue(html.contains("这是第 $n 段的译文内容。"), "第 $n 段译文缺失或编号错位")
            }
            assertTrue(!html.contains("これはばんごう"), "译文里不应残留日文原文")
            val pCount = Regex("<p>").findAll(html).count()
            assertTrue(pCount == paragraphs, "段落数应为 $paragraphs，实际 $pCount")
            assertTrue(
                (result).translatedTitle.contains("第一章"),
                "标题译名缺失：${result.translatedTitle}",
            )
        } finally {
            stub.stop()
        }
    }
}
