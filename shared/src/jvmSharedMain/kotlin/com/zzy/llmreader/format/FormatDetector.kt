package com.zzy.llmreader.format

enum class BookFormat(val displayName: String, val mime: List<String>, val extensions: List<String>) {
    TXT("纯文本", listOf("text/plain"), listOf("txt", "log")),
    MD("Markdown", listOf("text/markdown", "text/x-markdown"), listOf("md", "markdown")),
    HTML("HTML", listOf("text/html", "application/xhtml+xml"), listOf("html", "htm", "xhtml")),
    EPUB("EPUB", listOf("application/epub+zip"), listOf("epub")),
    PDF("PDF", listOf("application/pdf"), listOf("pdf")),
    UNKNOWN("未知", emptyList(), emptyList()),
}

object FormatDetector {

    /** 优先用扩展名判断（更可靠），其次再退到 mime。 */
    fun detect(displayName: String?, mimeType: String?): BookFormat {
        val ext = displayName?.substringAfterLast('.', "")?.lowercase().orEmpty()
        BookFormat.entries.forEach { fmt ->
            if (ext.isNotEmpty() && ext in fmt.extensions) return fmt
        }
        val mt = mimeType?.lowercase().orEmpty()
        BookFormat.entries.forEach { fmt ->
            if (mt.isNotEmpty() && mt in fmt.mime) return fmt
        }
        return BookFormat.UNKNOWN
    }

    /**
     * 系统文件选择器接受的 MIME 列表。
     * 只列出被本应用支持的格式，避免用户误选无法处理的文件。
     * 注意：Markdown 在 SAF 里通常以 text/plain 出现；EPUB 偶尔会被识别为
     * application/zip，因此一并放行 zip。
     */
    val openableMimeTypes: Array<String> = arrayOf(
        "text/plain",
        "text/markdown",
        "text/x-markdown",
        "text/html",
        "application/xhtml+xml",
        "application/epub+zip",
        "application/zip",
        "application/pdf",
    )
}
