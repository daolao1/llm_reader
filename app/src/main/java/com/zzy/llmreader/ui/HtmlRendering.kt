package com.zzy.llmreader.ui

import android.text.Spanned
import androidx.core.text.HtmlCompat

/**
 * HTML → Spanned。
 *
 * 章节正文在 shared 里统一以 **HTML 字符串**存放（跨平台中立的表现形式），
 * Android 端在这一层把它转成 `TextView` 能渲染的 Spanned；
 * 桌面端则用 `com.zzy.llmreader.format.htmlToRichBlocks()` 转成 Compose 的 AnnotatedString。
 *
 * 用 `remember(html)` 包一层调用即可，避免每次重组都重新解析。
 */
fun htmlToSpanned(html: String?): Spanned? {
    if (html.isNullOrBlank()) return null
    return HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_COMPACT)
}
