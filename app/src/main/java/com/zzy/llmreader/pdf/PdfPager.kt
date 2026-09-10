package com.zzy.llmreader.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.runtime.Stable
import java.io.Closeable

@Stable
class PdfPager(context: Context, uri: Uri) : Closeable {

    private val pfd: ParcelFileDescriptor =
        context.contentResolver.openFileDescriptor(uri, "r")
            ?: error("无法打开 PDF: $uri")

    private val renderer: PdfRenderer = PdfRenderer(pfd)

    val pageCount: Int = renderer.pageCount

    /** 渲染指定页为位图。targetWidth 是 px，按页面宽高比保持比例。 */
    fun render(pageIndex: Int, targetWidth: Int): Bitmap {
        require(pageIndex in 0 until pageCount) { "页码越界: $pageIndex" }
        renderer.openPage(pageIndex).use { page ->
            val w = targetWidth.coerceAtLeast(320)
            val h = (page.height.toFloat() / page.width * w).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            // 白底，避免透明 PDF 在深色主题下不可读
            android.graphics.Canvas(bmp).drawColor(Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bmp
        }
    }

    override fun close() {
        runCatching { renderer.close() }
        runCatching { pfd.close() }
    }
}
