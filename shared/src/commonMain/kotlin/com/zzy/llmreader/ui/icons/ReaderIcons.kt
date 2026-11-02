package com.zzy.llmreader.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Lucide 图标的精简 Compose 版本，Android / Desktop 共用。
 * 原始 SVG：https://github.com/lucide-icons/lucide/tree/main/icons
 * 许可证：见同目录 LICENSE-LUCIDE.txt。
 */
object ReaderIcons {
    val BookOpen by lazy { icon {
        path("M12 5v16")
        path("M20.001 19A2 2 0 0022 17V5a2 2 0 00-1.999-2L16 3.002A5 5 0 0012 5a5 5 0 00-4-2H4a2 2 0 00-2 2v12a2 2 0 001.999 2H8a5 5 0 014 2 5 5 0 014-2z")
    } }
    val Library by lazy { icon {
        rect(3f, 3f, 8f, 18f, 1f)
        path("M7 3v18")
        path("M20.4 18.9c.2.5-.1 1.1-.6 1.3l-1.9.7c-.5.2-1.1-.1-1.3-.6L11.1 5.1c-.2-.5.1-1.1.6-1.3l1.9-.7c.5-.2 1.1.1 1.3.6Z")
    } }
    val Tune by lazy { icon {
        path("M10 5H3", "M12 19H3", "M14 3v4", "M16 17v4", "M21 12h-9", "M21 19h-5", "M21 5h-7", "M8 10v4", "M8 12H3")
    } }
    val Languages by lazy { icon {
        path("m5 8 6 6", "m4 14 6-6 2-3", "M2 5h12", "M7 2h1", "m22 22-5-10-5 10", "M14 18h6")
    } }
    val SunMoon by lazy { icon {
        path("M12 2v2", "M14.837 16.385a6 6 0 1 1-7.223-7.222c.624-.147.97.66.715 1.248a4 4 0 0 0 5.26 5.259c.589-.255 1.396.09 1.248.715", "M16 12a4 4 0 0 0-4-4", "m19 5-1.256 1.256", "M20 12h2")
    } }
    val ArrowLeft by lazy { icon { path("m12 19-7-7 7-7", "M19 12H5") } }
    val ArrowRight by lazy { icon { path("M5 12h14", "m12 5 7 7-7 7") } }
    val ChevronLeft by lazy { icon { path("m15 18-6-6 6-6") } }
    val ChevronRight by lazy { icon { path("m9 18 6-6-6-6") } }
    val ChevronDown by lazy { icon { path("m6 9 6 6 6-6") } }
    val X by lazy { icon { path("M18 6 6 18", "m6 6 12 12") } }
    val Plus by lazy { icon { path("M5 12h14", "M12 5v14") } }
    val FolderOpen by lazy { icon {
        path("m6 14 1.5-2.9A2 2 0 0 1 9.24 10H20a2 2 0 0 1 1.94 2.5l-1.54 6a2 2 0 0 1-1.95 1.5H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h3.93a2 2 0 0 1 1.66.9l.82 1.2a2 2 0 0 0 1.66.9H18a2 2 0 0 1 2 2v2")
    } }
    val Link by lazy { icon {
        path("M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71")
        path("M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71")
    } }
    val Search by lazy { icon { circle(11f, 11f, 8f); path("m21 21-4.3-4.3") } }
    val Settings by lazy { icon {
        path("M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z")
        circle(12f, 12f, 3f)
    } }
    val More by lazy { icon { circle(12f, 12f, 1f); circle(19f, 12f, 1f); circle(5f, 12f, 1f) } }
    val Trash by lazy { icon { path("M3 6h18", "M19 6v14c0 1-1 2-2 2H7c-1 0-2-1-2-2V6", "M8 6V4c0-1 1-2 2-2h4c1 0 2 1 2 2v2", "M10 11v6", "M14 11v6") } }
    val Info by lazy { icon { circle(12f, 12f, 10f); path("M12 16v-4", "M12 8h.01") } }
    val Image by lazy { icon { rect(3f, 3f, 18f, 18f, 2f); circle(9f, 9f, 2f); path("m21 15-3.086-3.086a2 2 0 0 0-2.828 0L6 21") } }
    val MessageQuestion by lazy { icon { path("M7.9 20A9 9 0 1 0 4 16.1L2 22Z", "M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3", "M12 17h.01") } }
    val Refresh by lazy { icon { path("M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8", "M21 3v5h-5", "M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16", "M8 16H3v5") } }
    val Download by lazy { icon { path("M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4", "m7 10 5 5 5-5", "M12 15V3") } }
    val Check by lazy { icon { path("M20 6 9 17l-5-5") } }
    val FileText by lazy { icon { path("M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z", "M14 2v4a2 2 0 0 0 2 2h4", "M10 9H8", "M16 13H8", "M16 17H8") } }
    val EyeOff by lazy { icon { path("M10.733 5.076a10.744 10.744 0 0 1 11.205 6.575 1 1 0 0 1 0 .696 10.747 10.747 0 0 1-1.444 2.49", "M14.084 14.158a3 3 0 0 1-4.242-4.242", "M17.479 17.499a10.75 10.75 0 0 1-15.417-5.151 1 1 0 0 1 0-.696 10.75 10.75 0 0 1 4.446-5.143", "m2 2 20 20") } }
}

private class IconBuilder {
    val paths = mutableListOf<PathData>()
    fun path(vararg data: String) { data.forEach { paths += PathData(it) } }
    fun rect(x: Float, y: Float, w: Float, h: Float, radius: Float) {
        path("M${x + radius} ${y}H${x + w - radius}A$radius $radius 0 0 1 ${x + w} ${y + radius}V${y + h - radius}A$radius $radius 0 0 1 ${x + w - radius} ${y + h}H${x + radius}A$radius $radius 0 0 1 $x ${y + h - radius}V${y + radius}A$radius $radius 0 0 1 ${x + radius} ${y}Z")
    }
    fun circle(cx: Float, cy: Float, r: Float) {
        path("M${cx + r} $cy A$r $r 0 1 0 ${cx - r} $cy A$r $r 0 1 0 ${cx + r} ${cy}Z")
    }
}

private data class PathData(val data: String)

private fun icon(block: IconBuilder.() -> Unit): ImageVector {
    val paths = IconBuilder().apply(block).paths
    return ImageVector.Builder(
        name = "Lucide", defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f,
    ).apply {
        paths.forEach { data ->
            path(
                fill = SolidColor(Color.Transparent),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                pathFillType = PathFillType.NonZero,
            ) { addPathNodes(data.data) }
        }
    }.build()
}
