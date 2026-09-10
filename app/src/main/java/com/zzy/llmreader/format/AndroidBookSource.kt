package com.zzy.llmreader.format

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.InputStream

/**
 * 把 Android SAF 的 `content://` URI 适配成 shared 的 [BookSource]，
 * 让跨平台的 [DocumentParser] 不需要认识 Android 的 Uri / ContentResolver。
 *
 * 桌面端对应的实现是 `FileBookSource`（见 :desktop 模块）。
 */
class AndroidBookSource(
    private val context: Context,
    private val uri: Uri,
) : BookSource {

    private val meta: Pair<String?, String?> by lazy { queryMeta() }

    override val id: String = uri.toString()

    override val displayName: String
        get() = meta.first?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "未命名"

    override val mimeType: String?
        get() = meta.second

    override fun openStream(): InputStream =
        context.contentResolver.openInputStream(uri) ?: error("无法打开 $uri")

    private fun queryMeta(): Pair<String?, String?> {
        var name: String? = null
        val mime = context.contentResolver.getType(uri)
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) name = c.getString(idx)
            }
        }
        return name to mime
    }
}
