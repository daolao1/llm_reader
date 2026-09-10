package com.zzy.llmreader.model

/**
 * 一本书的章节单元；纯文本格式只有一个章节，EPUB 等多章节格式有多条。
 *
 * `content` / `translatedContent` 统一存 **HTML 字符串**（跨平台中立的表现形式）：
 *  - Android 渲染时用 Markwon/HtmlCompat 转成 `Spanned`（在 UI 边界转换一次）
 *  - 桌面端用 shared 的 `htmlToRichParagraphs()` 转成 Compose `AnnotatedString`
 * 翻译管线本来就以 HTML 为单位（`ChapterTranslator` 直接吃 HTML），
 * 所以这个类型同时简化了本地书的翻译路径（原先需要 Spanned → HTML 的反向转换）。
 */
data class Chapter(
    val title: String,
    /**
     * 章节正文 HTML。网页小说在懒加载模式下未抓取的章节为 `null`，UI 应展示加载占位符。
     */
    val content: String?,
    /** 翻译后的标题；未翻译为 `null`。 */
    val translatedTitle: String? = null,
    /** 翻译后的正文 HTML；未翻译为 `null`。 */
    val translatedContent: String? = null,
    /** 上层分组标题（如 kakuyomu 的「X章」），用于目录里展示分节。 */
    val groupTitle: String? = null,
)

/** 解析后的内容：文本类（多章节）或 PDF（各平台自行渲染）。 */
sealed class BookContent {
    abstract val title: String

    data class Text(
        override val title: String,
        val chapters: List<Chapter>,
    ) : BookContent()

    data class Pdf(
        override val title: String,
        /** 平台相关的文档标识：Android 是 content URI，桌面端是绝对路径。 */
        val sourceUri: String,
    ) : BookContent()
}
