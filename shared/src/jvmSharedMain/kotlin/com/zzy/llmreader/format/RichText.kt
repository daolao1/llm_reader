package com.zzy.llmreader.format

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * 平台中立的富文本中间表示。
 *
 * [Chapter.content] 存的是 HTML；Android 端用 Markwon/HtmlCompat 渲染，
 * 桌面端则用本文件把它转成 [RichBlock] 列表，再映射到 Compose 的 `AnnotatedString`。
 * 这样"HTML 怎么解析"只有一份实现，UI 层只负责画。
 */
data class RichRun(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
)

data class RichBlock(
    val runs: List<RichRun>,
    /** 0 = 正文，1..6 = h1..h6 */
    val heading: Int = 0,
    val listItem: Boolean = false,
    val quote: Boolean = false,
)

/** 章节文本里 `[IMG: tags]` 插图标记的匹配规则（与 Android 端保持一致）。 */
val INLINE_IMAGE_REGEX: Regex = Regex(
    pattern = """[\[【]\s*IMG\s*[:：]\s*([^\]】\n]+?)\s*[\]】]""",
    option = RegexOption.IGNORE_CASE,
)

/** 去掉 `[IMG: ...]` 标记，只留正文。 */
fun stripInlineImageMarkers(html: String): String =
    INLINE_IMAGE_REGEX.replace(html) { "" }
        .replace(Regex("[ \\t]+\\n"), "\n")
        .replace(Regex("\\n{3,}"), "\n\n")

/** 抽出章节里所有插图 prompt（按出现顺序）。 */
fun extractInlineImagePrompts(html: String): List<String> =
    INLINE_IMAGE_REGEX.findAll(html).map { it.groupValues[1].trim() }
        .filter { it.isNotBlank() }
        .toList()

/**
 * HTML → [RichBlock] 列表。只处理小说正文会用到的标签，
 * 遇到不认识的标签就继续往下钻（保内容不丢）。
 */
fun htmlToRichBlocks(html: String): List<RichBlock> {
    val doc = runCatching { Jsoup.parse(html) }.getOrNull() ?: return emptyList()
    val body = doc.body() ?: return emptyList()
    val out = mutableListOf<RichBlock>()
    collectBlocks(body, out, style = InlineStyle(), heading = 0, listItem = false, quote = false)
    // 去掉空块
    return out.filter { block -> block.runs.any { it.text.isNotBlank() } }
}

private data class InlineStyle(
    val bold: Boolean = false,
    val italic: Boolean = false,
)

private val BLOCK_TAGS = setOf(
    "p", "div", "section", "article", "br", "hr",
    "h1", "h2", "h3", "h4", "h5", "h6",
    "li", "ul", "ol", "blockquote", "pre", "tr", "td", "th", "table",
)

private fun collectBlocks(
    parent: Element,
    out: MutableList<RichBlock>,
    style: InlineStyle,
    heading: Int,
    listItem: Boolean,
    quote: Boolean,
) {
    var pending = mutableListOf<RichRun>()

    fun flush() {
        if (pending.isNotEmpty()) {
            out += RichBlock(pending, heading = heading, listItem = listItem, quote = quote)
            pending = mutableListOf()
        }
    }

    for (child in parent.childNodes()) {
        when (child) {
            is TextNode -> {
                val text = child.text().replace('\u00A0', ' ')
                if (text.isNotBlank()) {
                    pending += RichRun(text, bold = style.bold, italic = style.italic)
                } else if (text.isNotEmpty() && pending.isNotEmpty()) {
                    pending += RichRun(" ", bold = style.bold, italic = style.italic)
                }
            }

            is Element -> {
                val tag = child.tagName().lowercase()
                when (tag) {
                    "script", "style", "head", "rt", "rp" -> Unit // 跳过
                    "br", "hr" -> flush()
                    "b", "strong" -> {
                        collectInline(child, pending, style.copy(bold = true))
                    }
                    "i", "em", "u", "cite" -> {
                        collectInline(child, pending, style.copy(italic = true))
                    }
                    "span", "a", "small", "font", "code", "ruby", "sup", "sub", "mark", "s", "del" -> {
                        collectInline(child, pending, style)
                    }
                    "img" -> {
                        val alt = child.attr("alt")
                        if (alt.isNotBlank()) pending += RichRun("[$alt]")
                    }
                    else -> {
                        val isBlock = tag in BLOCK_TAGS
                        if (isBlock) flush()
                        val childHeading = when (tag) {
                            "h1" -> 1
                            "h2" -> 2
                            "h3" -> 3
                            "h4" -> 4
                            "h5" -> 5
                            "h6" -> 6
                            else -> heading
                        }
                        collectBlocks(
                            child,
                            out,
                            style,
                            heading = childHeading,
                            listItem = listItem || tag == "li",
                            quote = quote || tag == "blockquote",
                        )
                        if (isBlock) flush()
                    }
                }
            }
        }
    }
    flush()
}

/** 行内元素：把纯文本收进当前块，块级子节点递归下去。 */
private fun collectInline(element: Element, into: MutableList<RichRun>, style: InlineStyle) {
    for (node in element.childNodes()) {
        when (node) {
            is TextNode -> {
                val text = node.text().replace('\u00A0', ' ')
                if (text.isNotEmpty()) into += RichRun(text, bold = style.bold, italic = style.italic)
            }
            is Element -> {
                val tag = node.tagName().lowercase()
                when (tag) {
                    "script", "style", "rt", "rp" -> Unit
                    "br" -> into += RichRun("\n")
                    "b", "strong" -> collectInline(node, into, style.copy(bold = true))
                    "i", "em" -> collectInline(node, into, style.copy(italic = true))
                    "img" -> {
                        val alt = node.attr("alt")
                        if (alt.isNotBlank()) into += RichRun("[$alt]")
                    }
                    else -> collectInline(node, into, style)
                }
            }
            else -> Unit
        }
    }
}

/** 章节 HTML → 纯文本预览（发现页/详情页摘要用）。 */
fun htmlToPlainText(html: String): String =
    runCatching { Jsoup.parse(html).text() }.getOrDefault(html)
