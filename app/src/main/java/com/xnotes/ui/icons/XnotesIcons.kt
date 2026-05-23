package com.xnotes.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * The line-icon set (spec 11 §3), built as Compose [ImageVector]s straight from
 * the reference SVG path bodies (Feather/Lucide). Each is a 24x24 viewBox, 2px
 * round-capped stroke, no fill; the stroke colour is a placeholder recoloured by
 * the `Icon` tint at use.
 */
object XnotesIcons {

    private fun icon(vararg pathData: String): ImageVector {
        val nodes = PathParser().parsePathString(pathData.joinToString(" ")).toNodes()
        return ImageVector.Builder(
            name = "xn",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).addPath(
            pathData = nodes,
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ).build()
    }

    private fun circle(cx: Double, cy: Double, r: Double): String =
        "M ${cx - r} $cy a $r $r 0 1 0 ${2 * r} 0 a $r $r 0 1 0 ${-2 * r} 0"

    private fun rect(x: Double, y: Double, w: Double, h: Double): String =
        "M $x $y h $w v $h h ${-w} Z"

    val pen = icon("M12 20h9", "M16.5 3.5a2.12 2.12 0 0 1 3 3L7 19l-4 1 1-4Z")
    val calligraphy = icon("M17 3a2.83 2.83 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5Z", "M15 5l4 4")
    val highlighter = icon("m9 11-6 6v3h9l3-3", "m22 12-4.6 4.6a2 2 0 0 1-2.8 0l-5.2-5.2a2 2 0 0 1 0-2.8L16 4l6 8Z")
    val eraser = icon(
        "m7 21-4.3-4.3a1.7 1.7 0 0 1 0-2.4l9.6-9.6a1.7 1.7 0 0 1 2.4 0l5.6 5.6a1.7 1.7 0 0 1 0 2.4L13 21Z",
        "M22 21H7", "m5 11 9 9",
    )
    val pan = icon("M5 9 2 12l3 3", "M9 5l3-3 3 3", "M15 19l-3 3-3-3", "M19 9l3 3-3 3", "M2 12h20", "M12 2v20")
    val select = icon("M3 3l7.07 16.97 2.51-7.39 7.39-2.51Z", "M13 13l6 6")
    val lasso = icon(
        "M7 22a5 5 0 0 1-2-4",
        "M3.3 14A6.8 6.8 0 0 1 2 10c0-4.4 4.5-8 10-8s10 3.6 10 8-4.5 8-10 8a12 12 0 0 1-5-1",
        circle(5.0, 16.0, 2.0),
    )
    val shape = icon(
        "M8.3 10a.7.7 0 0 1-.626-1.079L11.4 3a.7.7 0 0 1 1.198-.043L16.3 8.9a.7.7 0 0 1-.572 1.1Z",
        rect(3.0, 14.0, 7.0, 7.0),
        circle(17.5, 17.5, 3.5),
    )
    val text = icon("M4 7V4h16v3", "M9 20h6", "M12 4v16")
    val image = icon(rect(3.0, 3.0, 18.0, 18.0), circle(8.5, 8.5, 1.5), "M21 15l-5-5L5 21")
    val undo = icon("M9 14 4 9l5-5", "M20 20v-7a4 4 0 0 0-4-4H4")
    val redo = icon("M15 14l5-5-5-5", "M4 20v-7a4 4 0 0 1 4-4h12")
    val zoomIn = icon(circle(11.0, 11.0, 8.0), "M21 21l-4.35-4.35", "M11 8v6", "M8 11h6")
    val zoomOut = icon(circle(11.0, 11.0, 8.0), "M21 21l-4.35-4.35", "M8 11h6")
    val fit = icon("M8 3H5a2 2 0 0 0-2 2v3", "M21 8V5a2 2 0 0 0-2-2h-3", "M3 16v3a2 2 0 0 0 2 2h3", "M16 21h3a2 2 0 0 0 2-2v-3")
    val page = icon(rect(3.0, 3.0, 18.0, 18.0), "M12 8v8", "M8 12h8")
    val prev = icon("M15 18l-6-6 6-6")
    val next = icon("M9 18l6-6-6-6")
    val file = icon("M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8Z", "M14 2v6h6")
    val edit = icon("M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7", "M18.5 2.5a2.12 2.12 0 0 1 3 3L12 15l-4 1 1-4Z")
    val sidebar = icon(rect(3.0, 3.0, 18.0, 18.0), "M9 3v18")
    val fullscreen = icon("M15 3h6v6", "M9 21H3v-6", "M21 3l-7 7", "M3 21l7-7")
    val present = icon("M2 8V6a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-6", "M2 12a9 9 0 0 1 8 8", "M2 16a5 5 0 0 1 4 4", "M2 20h.01")
    val plus = icon("M12 5v14", "M5 12h14")
    val trash = icon("M3 6h18", "M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6", "M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2")
    val close = icon("M18 6 6 18", "M6 6l12 12")
    val bookmark = icon("M19 21l-7-5-7 5V5a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2Z")
    val contents = icon("M8 6h13", "M8 12h13", "M8 18h13", "M3 6h.01", "M3 12h.01", "M3 18h.01")
    val thumbnails = icon(rect(3.0, 3.0, 7.0, 7.0), rect(14.0, 3.0, 7.0, 7.0), rect(3.0, 14.0, 7.0, 7.0), rect(14.0, 14.0, 7.0, 7.0))
    val lock = icon(rect(3.0, 11.0, 18.0, 11.0), "M7 11V7a5 5 0 0 1 10 0v4")
    val unlock = icon(rect(3.0, 11.0, 18.0, 11.0), "M7 11V7a5 5 0 0 1 9.9-1")
    val copy = icon(rect(9.0, 9.0, 13.0, 13.0), "M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1")
    val cut = icon(circle(6.0, 6.0, 3.0), circle(6.0, 18.0, 3.0), "M20 4 8.12 15.88", "M14.47 14.48 20 20", "M8.12 8.12 12 12")
    val duplicate = icon(rect(8.0, 8.0, 12.0, 12.0), "M16 8V6a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v8a2 2 0 0 0 2 2h2")
}
