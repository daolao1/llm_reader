package com.zzy.llmreader.format

import java.io.InputStream

/**
 * 一个可解析的文档来源。把 Android 的 `content://` URI（SAF）和桌面端的
 * `File` 统一成同一个入口，[DocumentParser] 因此不再依赖 `Context`。
 */
interface BookSource {
    /** 稳定标识：Android 用 content URI 字符串，桌面端用绝对路径。用于阅读进度 key。 */
    val id: String

    /** 带扩展名的显示名（格式探测优先看扩展名）。 */
    val displayName: String

    /** MIME 类型，未知传 null。 */
    val mimeType: String?

    /** 每次调用都应返回一个全新的、位于起点的流。 */
    fun openStream(): InputStream
}
