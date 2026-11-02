package com.zzy.llmreader

import com.zzy.llmreader.data.ImageBackend
import com.zzy.llmreader.image.ImageCache
import com.zzy.llmreader.platform.AppDirs
import com.zzy.llmreader.web.topLevelContentBlocks
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BugRegressionTest {
    @Test
    fun `嵌套引用只保留顶层块且普通段落不丢`() {
        val root = Jsoup.parse(
            "<article><p>前文</p><blockquote><p>唯一引用</p>" +
                "<blockquote><p>内层引用</p></blockquote></blockquote><p>后文</p></article>",
        ).selectFirst("article")!!

        val blocks = topLevelContentBlocks(root)
        val html = blocks.joinToString("\n") { it.outerHtml() }

        assertEquals(listOf("p", "blockquote", "p"), blocks.map { it.tagName() })
        assertEquals(1, Regex("唯一引用").findAll(html).count())
        assertEquals(1, Regex("内层引用").findAll(html).count())
        assertTrue(html.contains("前文") && html.contains("后文"))
    }

    @Test
    fun `并发保存同章图片不会互相覆盖索引`() = runBlocking {
        val base = Files.createTempDirectory("image-cache-test").toFile()
        val cache = ImageCache(AppDirs(base, base))
        val images = coroutineScope {
            (1..24).map { n ->
                async {
                    cache.save(
                        bookId = "book",
                        chapterIndex = 0,
                        bytes = byteArrayOf(n.toByte()),
                        prompt = "prompt-$n",
                        negative = "",
                        backend = ImageBackend.COMFYUI,
                        width = 64,
                        height = 64,
                    )
                }
            }.awaitAll()
        }

        val stored = cache.list("book", 0)
        assertEquals(24, stored.size, "并发写入的索引项必须全部保留")
        assertEquals(images.map { it.id }.toSet(), stored.map { it.id }.toSet())
        assertTrue(stored.all { cache.fileFor("book", 0, it.filename).isFile })
    }

    @Test
    fun `删除与新增并发时不会复活已删除的图片`() = runBlocking {
        val base = Files.createTempDirectory("image-cache-test").toFile()
        val cache = ImageCache(AppDirs(base, base))
        val old = cache.save("book", 0, byteArrayOf(1), "old", "", ImageBackend.COMFYUI, 64, 64)
        val delete = async { cache.delete("book", 0, old.id) }
        val save = async {
            cache.save("book", 0, byteArrayOf(2), "new", "", ImageBackend.COMFYUI, 64, 64)
        }
        delete.await()
        save.await()

        val stored = cache.list("book", 0)
        assertEquals(1, stored.size)
        assertEquals("new", stored.single().prompt)
        assertTrue(!cache.fileFor("book", 0, old.filename).exists())
    }
}
