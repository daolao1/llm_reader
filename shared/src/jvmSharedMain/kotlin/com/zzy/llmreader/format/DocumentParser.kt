package com.zzy.llmreader.format

import com.zzy.llmreader.model.BookContent
import com.zzy.llmreader.model.Chapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.intellij.markdown.flavours.commonmark.CommonMarkFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser
import org.jsoup.Jsoup
import java.io.InputStream
import java.net.URLDecoder
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 顶层入口：根据格式分发到对应解析器。
 *
 * 所有格式统一产出 **HTML 字符串**（见 [Chapter.content]），
 * 这样 Android 与桌面端可以各自选择渲染方式，翻译管线也能直接复用。
 *
 * 不依赖任何 Android API —— 输入是 [BookSource]，Android 端用 SAF URI 实现，
 * 桌面端用 `File` 实现。
 */
class DocumentParser {

    suspend fun parse(source: BookSource): BookContent = withContext(Dispatchers.IO) {
        val title = source.displayName.substringBeforeLast('.').ifBlank { "未命名" }
        when (FormatDetector.detect(source.displayName, source.mimeType)) {
            BookFormat.TXT -> parseTxt(source, title)
            BookFormat.MD -> parseMarkdown(source, title)
            BookFormat.HTML -> parseHtml(source, title)
            BookFormat.EPUB -> EpubParser.parse(source, title)
            BookFormat.PDF -> BookContent.Pdf(title = title, sourceUri = source.id)
            // 兜底：当作纯文本读取
            BookFormat.UNKNOWN -> parseTxt(source, title)
        }
    }

    private fun parseTxt(source: BookSource, title: String): BookContent.Text {
        val text = source.openStream().use { it.readBytesDecoded() }
        return BookContent.Text(
            title = title,
            chapters = listOf(Chapter(title, plainTextToHtml(text))),
        )
    }

    private fun parseMarkdown(source: BookSource, title: String): BookContent.Text {
        val text = source.openStream().use { it.readBytesDecoded() }
        return BookContent.Text(title, listOf(Chapter(title, markdownToHtml(text))))
    }

    private fun parseHtml(source: BookSource, title: String): BookContent.Text {
        val text = source.openStream().use { it.readBytesDecoded() }
        val doc = runCatching { Jsoup.parse(text) }.getOrNull()
        val docTitle = doc?.title()?.ifBlank { null } ?: title
        val body = doc?.body()?.html()?.ifBlank { null } ?: text
        return BookContent.Text(docTitle, listOf(Chapter(docTitle, body)))
    }
}

/** Markdown → HTML（JetBrains 官方 KMP markdown 解析器）。 */
internal fun markdownToHtml(markdown: String): String {
    val flavour = CommonMarkFlavourDescriptor()
    val tree = MarkdownParser(flavour).buildMarkdownTreeFromString(markdown)
    return HtmlGenerator(markdown, tree, flavour).generateHtml()
}

/**
 * 纯文本 → HTML：按空行切段，逐段转义后包 `<p>`。
 * 块内若每行都很短，说明是"一行一段"的排版，再按行切一次。
 */
internal fun plainTextToHtml(text: String): String {
    val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
    val blocks = normalized.split(Regex("\n\\s*\n"))
        .flatMap { block ->
            val lines = block.split('\n')
            if (lines.size > 1 && lines.all { it.length < 200 }) lines else listOf(block)
        }
    return blocks
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString("\n") { "<p>${escapeHtml(it)}</p>" }
}

internal fun escapeHtml(value: String): String = buildString(value.length) {
    for (ch in value) {
        when (ch) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            else -> append(ch)
        }
    }
}

internal fun InputStream.readBytesDecoded(): String {
    val bytes = readBytes()
    // 简单 BOM 检测；其它情况尝试 UTF-8，失败则回退 GBK。
    if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
        return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
    }
    if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
        return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
    }
    if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
        return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
    }
    return runCatching {
        val s = String(bytes, Charsets.UTF_8)
        if (s.contains('\uFFFD')) error("non-utf8") else s
    }.getOrElse {
        runCatching { String(bytes, Charset.forName("GBK")) }
            .getOrDefault(String(bytes, Charsets.ISO_8859_1))
    }
}

/**
 * EPUB = ZIP，结构：
 *   META-INF/container.xml -> 找到 .opf 路径
 *   *.opf manifest+spine    -> 章节顺序
 *   每个章节是 (x)html 文件
 */
object EpubParser {

    fun parse(source: BookSource, fallbackTitle: String): BookContent.Text {
        val entries = readZipEntries(source)

        val containerXml = entries["META-INF/container.xml"]
            ?: error("EPUB 缺少 container.xml")
        val opfPath = parseOpfPath(containerXml)
            ?: error("EPUB container.xml 未指定 rootfile")

        val opfBytes = entries[opfPath] ?: error("EPUB 缺少 $opfPath")
        val opfDir = opfPath.substringBeforeLast('/', "")
        val (title, spineHrefs, manifest) = parseOpf(opfBytes)

        val chapters = mutableListOf<Chapter>()
        spineHrefs.forEachIndexed { index, idref ->
            val href = manifest[idref] ?: return@forEachIndexed
            // spine 可能指向章内锚点，且 ZIP 条目名保存的是解码后的路径。
            // 不剥离 fragment、保留 %20 等转义都会让已有章节被静默跳过。
            val hrefPath = href.substringBefore('#').substringBefore('?')
            val decodedHref = runCatching { URLDecoder.decode(hrefPath, Charsets.UTF_8.name()) }
                .getOrDefault(hrefPath)
            val full = if (opfDir.isEmpty()) decodedHref else "$opfDir/$decodedHref"
            val normalized = normalizePath(full)
            val data = entries[normalized] ?: return@forEachIndexed
            val html = String(data, Charsets.UTF_8)
            chapters += htmlToChapter(html, "第 ${index + 1} 节")
        }

        if (chapters.isEmpty()) {
            // 退化：把所有 (x)html 文件都拼起来
            entries.entries
                .filter { (k, _) -> k.endsWith(".html", true) || k.endsWith(".xhtml", true) || k.endsWith(".htm", true) }
                .sortedBy { it.key }
                .forEachIndexed { i, (k, v) ->
                    val html = String(v, Charsets.UTF_8)
                    chapters += htmlToChapter(html, "文件 ${i + 1}: ${k.substringAfterLast('/')}")
                }
        }

        return BookContent.Text(
            title = title?.ifBlank { fallbackTitle } ?: fallbackTitle,
            chapters = chapters,
        )
    }

    private fun htmlToChapter(html: String, fallbackTitle: String): Chapter {
        val doc = runCatching { Jsoup.parse(html) }.getOrNull()
        val chapTitle = doc?.title()?.ifBlank { null }
            ?: doc?.selectFirst("h1, h2, h3")?.text()?.ifBlank { null }
            ?: fallbackTitle
        val body = doc?.body()?.html()?.ifBlank { null } ?: html
        return Chapter(chapTitle, body)
    }

    private fun normalizePath(path: String): String {
        val parts = mutableListOf<String>()
        for (seg in path.split('/')) {
            when (seg) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts += seg
            }
        }
        return parts.joinToString("/")
    }

    private fun readZipEntries(source: BookSource): Map<String, ByteArray> {
        val map = LinkedHashMap<String, ByteArray>()
        source.openStream().use { input ->
            ZipInputStream(input).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        map[entry.name] = zis.readBytes()
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }
        return map
    }

    private fun parseOpfPath(container: ByteArray): String? {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(container.inputStream())
        val nodes = doc.getElementsByTagName("rootfile")
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            val attr = n.attributes.getNamedItem("full-path")
            if (attr != null) return attr.nodeValue
        }
        return null
    }

    private data class OpfMeta(
        val title: String?,
        val spine: List<String>,
        val manifest: Map<String, String>, // id -> href
    )

    private fun parseOpf(data: ByteArray): OpfMeta {
        val doc = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
        }.newDocumentBuilder().parse(data.inputStream())

        val titleNodes = doc.getElementsByTagName("dc:title")
        val title = if (titleNodes.length > 0) titleNodes.item(0).textContent else null

        val manifest = LinkedHashMap<String, String>()
        val items = doc.getElementsByTagName("item")
        for (i in 0 until items.length) {
            val n = items.item(i)
            val id = n.attributes.getNamedItem("id")?.nodeValue ?: continue
            val href = n.attributes.getNamedItem("href")?.nodeValue ?: continue
            manifest[id] = href
        }

        val spine = mutableListOf<String>()
        val refs = doc.getElementsByTagName("itemref")
        for (i in 0 until refs.length) {
            val n = refs.item(i)
            val idref = n.attributes.getNamedItem("idref")?.nodeValue ?: continue
            spine += idref
        }
        return OpfMeta(title, spine, manifest)
    }
}
