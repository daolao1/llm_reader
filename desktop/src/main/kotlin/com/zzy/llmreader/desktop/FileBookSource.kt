package com.zzy.llmreader.desktop

import com.zzy.llmreader.format.BookSource
import java.io.File
import java.io.InputStream

/** 桌面端把本地文件适配成 shared 的 [BookSource]（对应 Android 的 AndroidBookSource）。 */
class FileBookSource(private val file: File) : BookSource {
    override val id: String = file.absolutePath
    override val displayName: String = file.name
    override val mimeType: String? = null
    override fun openStream(): InputStream = file.inputStream()
}
