package com.zzy.llmreader

import com.zzy.llmreader.data.LlmSettings
import com.zzy.llmreader.data.RecentBook
import com.zzy.llmreader.data.RecentBookSource
import com.zzy.llmreader.format.BookSource
import com.zzy.llmreader.format.extractInlineImagePrompts
import com.zzy.llmreader.format.htmlToRichBlocks
import com.zzy.llmreader.format.markdownToHtml
import com.zzy.llmreader.format.plainTextToHtml
import com.zzy.llmreader.format.stripInlineImageMarkers
import com.zzy.llmreader.model.BookContent
import com.zzy.llmreader.platform.AppDirs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * shared 模块的冒烟测试：在纯 JVM（= 桌面端）路径上跑通
 * "解析本地文档 → 存配置 → 读配置 → 渲染富文本" 这条主链路。
 *
 * 跑法：`./gradlew :shared:desktopTest`
 */
class SharedCoreSmokeTest {

    private fun tempDirs(): AppDirs {
        val base = Files.createTempDirectory("llm-reader-test").toFile()
        return AppDirs(dataDir = File(base, "data"), cacheDir = File(base, "cache"))
    }

    private class BytesBookSource(
        override val id: String,
        override val displayName: String,
        override val mimeType: String?,
        private val bytes: ByteArray,
    ) : BookSource {
        override fun openStream(): InputStream = bytes.inputStream()
    }

    // ------------------------------------------------------------ 文档解析

    @Test
    fun `纯文本按段落转成 HTML`() = runBlocking {
        val core = LlmReaderCore(tempDirs())
        val text = "第一段\n\n第二段\n第三段\n\n\n第四段"
        val book = core.documentParser.parse(
            BytesBookSource("t1", "测试小说.txt", "text/plain", text.toByteArray()),
        ) as BookContent.Text

        assertEquals("测试小说", book.title)
        assertEquals(1, book.chapters.size)
        val html = assertNotNull(book.chapters[0].content)
        assertContains(html, "<p>第一段</p>")
        assertContains(html, "<p>第二段</p>")
        assertContains(html, "<p>第四段</p>")
        // 不产生空段落
        assertTrue(!html.contains("<p></p>"), "不应生成空段落：$html")
    }

    @Test
    fun `纯文本会转义 HTML 特殊字符`() {
        val html = plainTextToHtml("a < b & c > d")
        assertContains(html, "&lt;")
        assertContains(html, "&amp;")
        assertTrue(!html.contains("a < b"), "尖括号必须被转义：$html")
    }

    @Test
    fun `GBK 编码的 txt 能正确解码`() = runBlocking {
        val core = LlmReaderCore(tempDirs())
        val gbk = "中文测试内容".toByteArray(charset("GBK"))
        val book = core.documentParser.parse(
            BytesBookSource("t2", "gbk.txt", "text/plain", gbk),
        ) as BookContent.Text
        assertContains(assertNotNull(book.chapters[0].content), "中文测试内容")
    }

    @Test
    fun `markdown 转成 HTML`() {
        val html = markdownToHtml("# 标题\n\n正文 **加粗** 与 *斜体*\n\n- 项目一\n- 项目二")
        assertContains(html, "<h1")
        assertContains(html, "<strong>")
        assertContains(html, "<em>")
        assertContains(html, "<li>")
    }

    @Test
    fun `EPUB 按 spine 顺序解析出多章`() = runBlocking {
        val epub = buildEpub(
            title = "我的书",
            chapters = listOf("第一章" to "<p>甲</p>", "第二章" to "<p>乙</p>"),
        )
        val core = LlmReaderCore(tempDirs())
        val book = core.documentParser.parse(
            BytesBookSource("e1", "book.epub", "application/epub+zip", epub),
        ) as BookContent.Text

        assertEquals("我的书", book.title)
        assertEquals(2, book.chapters.size)
        assertEquals("第一章", book.chapters[0].title)
        assertContains(assertNotNull(book.chapters[0].content), "甲")
        assertContains(assertNotNull(book.chapters[1].content), "乙")
    }

    @Test
    fun `EPUB spine 的锚点和 URL 转义路径不会丢章`() = runBlocking {
        val epub = buildEpub(
            title = "带链接的书",
            chapters = listOf("第一章" to "<p>正文保留</p>"),
            href = "chapter%201.xhtml#section-2",
            chapterPath = "OEBPS/chapter 1.xhtml",
        )
        val core = LlmReaderCore(tempDirs())
        val book = core.documentParser.parse(
            BytesBookSource("e2", "linked.epub", "application/epub+zip", epub),
        ) as BookContent.Text

        assertEquals(1, book.chapters.size, "带 fragment 的 spine 项也必须解析")
        assertContains(assertNotNull(book.chapters[0].content), "正文保留")
    }

    @Test
    fun `PDF 走到 Pdf 分支而不是当成文本`() = runBlocking {
        val core = LlmReaderCore(tempDirs())
        val book = core.documentParser.parse(
            BytesBookSource("p1", "doc.pdf", "application/pdf", "%PDF-1.4 fake".toByteArray()),
        )
        assertTrue(book is BookContent.Pdf, "PDF 应识别为 Pdf 分支")
        assertEquals("p1", (book as BookContent.Pdf).sourceUri)
    }

    // ------------------------------------------------------------ 配置持久化

    @Test
    fun `设置的写入与读回`() = runBlocking {
        val dirs = tempDirs()
        val core = LlmReaderCore(dirs)

        core.settingsRepo.update(LlmSettings("https://example.com/v1", "sk-test", "glm-4-flash"))
        val loaded = core.settingsRepo.settings.first()
        assertEquals("https://example.com/v1", loaded.baseUrl)
        assertEquals("sk-test", loaded.apiKey)
        assertEquals("glm-4-flash", loaded.model)

        // 真的落到了磁盘上（路径与 Android 旧版一致，升级不丢配置）
        val storeFile = File(File(dirs.dataDir, "datastore"), "settings.preferences_pb")
        assertTrue(storeFile.exists(), "DataStore 文件应存在：${storeFile.absolutePath}")
        assertTrue(storeFile.length() > 0, "DataStore 文件不应为空")
        assertContains(
            storeFile.readBytes().toString(Charsets.ISO_8859_1),
            "sk-test",
            message = "配置值应写入磁盘",
        )

        // 同一目录重复构造 Core 不应崩（DataStore 同文件只允许一个活跃实例）
        val reopened = LlmReaderCore(dirs)
        assertEquals("sk-test", reopened.settingsRepo.settings.first().apiKey)
    }

    @Test
    fun `阅读偏好与最近阅读记录`() = runBlocking {
        val core = LlmReaderCore(tempDirs())

        core.readerPrefsRepo.update(
            core.readerPrefsRepo.preferences.first().copy(
                translationLanguage = "繁體中文",
                translationBatchChars = 3000,
                webPrefetchAhead = 5,
            ),
        )
        val prefs = core.readerPrefsRepo.preferences.first()
        assertEquals("繁體中文", prefs.translationLanguage)
        assertEquals(3000, prefs.translationBatchChars)
        assertEquals(5, prefs.webPrefetchAhead)

        core.recentRepo.add(RecentBook("/tmp/a.txt", "甲书", 1L, RecentBookSource.FILE))
        core.recentRepo.add(RecentBook("https://x/s/b", "乙书", 2L, RecentBookSource.WEB))
        val recents = core.recentRepo.recents.first()
        assertEquals(listOf("乙书", "甲书"), recents.map { it.title })

        core.recentRepo.remove("https://x/s/b")
        assertEquals(listOf("甲书"), core.recentRepo.recents.first().map { it.title })
    }

    @Test
    fun `阅读进度按 key 存取`() = runBlocking {
        val core = LlmReaderCore(tempDirs())
        core.progressRepo.save("book-key", chapterIndex = 7, scrollFraction = 0.42f)
        val p = assertNotNull(core.progressRepo.get("book-key"))
        assertEquals(7, p.chapterIndex)
        assertEquals(0.42f, p.chapterScrollFraction)
    }

    // ------------------------------------------------------------ 富文本

    @Test
    fun `HTML 转成富文本块并保留粗斜体与标题`() {
        val blocks = htmlToRichBlocks(
            "<h2>小标题</h2><p>普通<b>粗</b>与<i>斜</i>文字</p><blockquote>引用</blockquote>",
        )
        assertEquals(3, blocks.size)
        assertEquals(2, blocks[0].heading)
        assertEquals("小标题", blocks[0].runs.joinToString("") { it.text }.trim())
        assertTrue(blocks[1].runs.any { it.bold && it.text.contains("粗") })
        assertTrue(blocks[1].runs.any { it.italic && it.text.contains("斜") })
        assertTrue(blocks[2].quote)
    }

    @Test
    fun `插图标记的提取与剔除`() {
        val html = "<p>她抬头。</p><p>[IMG: 1girl, moonlight]</p><p>风起了。</p>"
        assertEquals(listOf("1girl, moonlight"), extractInlineImagePrompts(html))
        val stripped = stripInlineImageMarkers(html)
        assertTrue(!stripped.contains("IMG"), "标记应被剔除：$stripped")
        assertContains(stripped, "她抬头。")
        assertContains(stripped, "风起了。")
    }

    @Test
    fun `全角方括号的插图标记也能识别`() {
        assertEquals(listOf("2boys"), extractInlineImagePrompts("<p>【IMG: 2boys】</p>"))
    }

    // ------------------------------------------------------------ 站点派发

    @Test
    fun `网页源注册表能按 URL 派发`() {
        val core = LlmReaderCore(tempDirs())
        val registry = core.createWebRegistry { com.zzy.llmreader.data.ReaderPreferences() }

        assertEquals(8, registry.sources.size)
        assertEquals("literotica", registry.find("https://www.literotica.com/s/some-story")?.id)
        assertEquals("syosetu", registry.find("https://ncode.syosetu.com/n1234ab/")?.id)
        assertEquals("ao3", registry.find("https://archiveofourown.org/works/123")?.id)
        assertEquals("royalroad", registry.find("https://www.royalroad.com/fiction/123/x")?.id)
        assertEquals(null, registry.find("https://example.com/not-supported"))
        // 无 scheme 的输入也应能识别
        assertEquals("literotica", registry.find("literotica.com/s/abc")?.id)
    }

    /** 现造一个最小可用的 EPUB（ZIP + container.xml + opf + 章节 xhtml）。 */
    private fun buildEpub(
        title: String,
        chapters: List<Pair<String, String>>,
        href: String? = null,
        chapterPath: String? = null,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun entry(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }

            entry(
                "META-INF/container.xml",
                """<?xml version="1.0"?>
                   <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                     <rootfiles><rootfile full-path="OEBPS/content.opf"
                       media-type="application/oebps-package+xml"/></rootfiles>
                   </container>""",
            )

            val manifest = chapters.indices.joinToString("") {
                val chapterHref = if (it == 0) href ?: "c$it.xhtml" else "c$it.xhtml"
                """<item id="c$it" href="$chapterHref" media-type="application/xhtml+xml"/>"""
            }
            val spine = chapters.indices.joinToString("") { """<itemref idref="c$it"/>""" }
            entry(
                "OEBPS/content.opf",
                """<?xml version="1.0"?>
                   <package xmlns="http://www.idpf.org/2007/opf" version="2.0">
                     <metadata><dc:title xmlns:dc="http://purl.org/dc/elements/1.1/">$title</dc:title></metadata>
                     <manifest>$manifest</manifest>
                     <spine>$spine</spine>
                   </package>""",
            )

            chapters.forEachIndexed { i, (chapterTitle, body) ->
                entry(
                    if (i == 0) chapterPath ?: "OEBPS/c$i.xhtml" else "OEBPS/c$i.xhtml",
                    """<?xml version="1.0" encoding="utf-8"?>
                       <html><head><title>$chapterTitle</title></head><body>$body</body></html>""",
                )
            }
        }
        return out.toByteArray()
    }
}
