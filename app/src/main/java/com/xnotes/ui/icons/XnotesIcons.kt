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
 *
 * [icon] joins its path strings into one path before parsing, so every string after
 * the first must open with an ABSOLUTE moveto. A relative "m" there starts from the
 * previous subpath's current point, not the origin, and the stroke lands off-canvas.
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

    private fun roundRect(x: Double, y: Double, w: Double, h: Double, r: Double): String =
        "M ${x + r} $y h ${w - 2 * r} a $r $r 0 0 1 $r $r v ${h - 2 * r} " +
            "a $r $r 0 0 1 ${-r} $r h ${-(w - 2 * r)} a $r $r 0 0 1 ${-r} ${-r} " +
            "v ${-(h - 2 * r)} a $r $r 0 0 1 $r ${-r} Z"

    private fun ellipse(cx: Double, cy: Double, rx: Double, ry: Double): String =
        "M ${cx - rx} $cy a $rx $ry 0 1 0 ${2 * rx} 0 a $rx $ry 0 1 0 ${-2 * rx} 0"

    // Pen, calligraphy, speed, taper and highlighter use the designed vector drawables
    // in res/drawable/ic_stroke_* (referenced from the toolbar), not built-in line glyphs.
    // The block and the paper it rests on, without Lucide's crease across the body. The crease
    // spent its life off-canvas behind the relative-moveto bug, and the plain block turned out
    // to read better at 22dp, so it stays gone on purpose.
    /** Lucide "merge": several notes joined into one. */
    val merge = icon("m8 6 4-4 4 4", "M12 2v10.3a4 4 0 0 1-1.172 2.872L4 22", "m20 22-5-5")

    /** Lucide "camera". */
    val camera = icon(
        "M14.5 4h-5L7 7H4a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V9a2 2 0 0 0-2-2h-3l-2.5-3z",
        circle(12.0, 13.0, 3.0),
    )

    /** Lucide "pipette": the colour picker's eyedropper. */
    val eyedropper = icon(
        "m2 22 1-1h3l9-9",
        "M3 21v-3l9-9",
        "m15 6 3.4-3.4a2.1 2.1 0 1 1 3 3L18 9l.4.4a2.1 2.1 0 1 1-3 3l-3.8-3.8a2.1 2.1 0 1 1 3-3l.4.4Z",
    )

    val eraser = icon(
        "m7 21-4.3-4.3a1.7 1.7 0 0 1 0-2.4l9.6-9.6a1.7 1.7 0 0 1 2.4 0l5.6 5.6a1.7 1.7 0 0 1 0 2.4L13 21Z",
        "M22 21H7",
    )
    val pan = icon("M5 9 2 12l3 3", "M9 5l3-3 3 3", "M15 19l-3 3-3-3", "M19 9l3 3-3 3", "M2 12h20", "M12 2v20")
    val select = icon("M3 3l7.07 16.97 2.51-7.39 7.39-2.51Z", "M13 13l6 6")
    // Free selection, drawn as the marquee it leaves behind: a freehand loop in marching ants.
    val lasso = icon(
        "M21.4 8.3Q22.9 10.4 21.7 12.8", "M19.7 14.9Q17.6 16.1 15.5 17.3",
        "M13 18.8Q10.8 19.9 8.4 19.6", "M5.7 18.5Q3.7 16.9 3.6 14.4",
        "M3.6 11.4Q3.7 9 4.9 6.9", "M7.1 4.9Q9.3 3.9 11.7 4.3",
        "M14.6 4.8Q17 5.4 19.1 6.4",
    )
    val shape = icon(
        "M8.3 10a.7.7 0 0 1-.626-1.079L11.4 3a.7.7 0 0 1 1.198-.043L16.3 8.9a.7.7 0 0 1-.572 1.1Z",
        rect(3.0, 14.0, 7.0, 7.0),
        circle(17.5, 17.5, 3.5),
    )
    // The inline text tool: a caret (I-beam, Lucide "text-cursor"), not the old text-box "T".
    val text = icon(
        "M17 21h-1a4 4 0 0 1-4-4V7a4 4 0 0 1 4-4h1",
        "M7 21h1a4 4 0 0 0 4-4v-1",
        "M7 3h1a4 4 0 0 1 4 4v1",
    )
    // The text box tool: a serif "T" held by corner handles, the box as it looks under edit.
    // A full square would be the fourth identical frame in the bar (page, sidebar, split).
    val textBox = icon(
        "M3 8V4.5A1.5 1.5 0 0 1 4.5 3H8", "M16 3h3.5A1.5 1.5 0 0 1 21 4.5V8",
        "M21 16v3.5a1.5 1.5 0 0 1-1.5 1.5H16", "M8 21H4.5A1.5 1.5 0 0 1 3 19.5V16",
        "M7.5 9.5v-2h9v2", "M12 7.5v9", "M9 16.5h6",
    )

    // The flow format bar's glyphs (Lucide: bold/italic/underline/strikethrough, lists,
    // square-check, aligns, indents).
    val bold = icon("M6 12h9a4 4 0 0 1 0 8H7a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1h7a4 4 0 0 1 0 8")
    val italic = icon("M19 4h-9", "M14 20H5", "M15 4 9 20")
    val underline = icon("M6 4v6a6 6 0 0 0 12 0V4", "M4 20h16")
    val strikethrough = icon("M16 4H9a3 3 0 0 0-2.83 4", "M14 12a4 4 0 0 1 0 8H6", "M4 12h16")
    val listBullet = icon("M3 12h.01", "M3 18h.01", "M3 6h.01", "M8 12h13", "M8 18h13", "M8 6h13")
    val listOrdered = icon("M10 12h11", "M10 18h11", "M10 6h11", "M4 10h2", "M4 6h1v4", "M6 18H4c0-1 2-2 2-3s-1-1.5-2-1")
    val checkboxItem = icon(rect(4.0, 4.0, 16.0, 16.0), "M9 12 11 14 15 10")
    val alignLeft = icon("M15 12H3", "M17 18H3", "M21 6H3")
    val alignCenter = icon("M17 12H7", "M19 18H5", "M21 6H3")
    val alignRight = icon("M21 12H9", "M21 18H7", "M21 6H3")
    val alignJustify = icon("M3 12h18", "M3 18h18", "M3 6h18")
    val indentIncrease = icon("M21 12H11", "M21 18H11", "M21 6H11", "M3 8 7 12 3 16")
    val indentDecrease = icon("M21 12H11", "M21 18H11", "M21 6H11", "M7 8 3 12 7 16")
    val image = icon(rect(3.0, 3.0, 18.0, 18.0), circle(8.5, 8.5, 1.5), "M21 15l-5-5L5 21")
    // The screenshot tool: plain scissors (Feather "scissors"), the same glyph as Cut.
    val scissors = icon(circle(6.0, 6.0, 3.0), circle(6.0, 18.0, 3.0), "M20 4 8.12 15.88", "M14.47 14.48 20 20", "M8.12 8.12 12 12")

    // Shape-kind glyphs for the shape tool's kind picker (line / arrow / rect / ellipse / circle / triangle).
    val shapeLine = icon("M5 19 19 5")
    val shapeArrow = icon("M5 19 19 5", "M9 5h10v10")
    val shapeRect = icon(rect(4.0, 6.0, 16.0, 12.0))
    val shapeEllipse = icon(ellipse(12.0, 12.0, 9.0, 6.0))
    val shapeCircle = icon(circle(12.0, 12.0, 8.0))
    val shapeTriangle = icon("M12 4 21 19 3 19 Z")
    val shapeAxes = icon("M12 3 V21", "M3 12 H21", "M9 6 12 3 15 6", "M18 9 21 12 18 15")
    val shapeNumberLine = icon("M3 12 H21", "M18 9 21 12 18 15", "M7 10 V14", "M11 10 V14", "M15 10 V14")
    val shapeSpline = icon("M4 18 C8 4 16 20 20 6", circle(12.0, 12.0, 1.5))
    val table = icon(rect(3.0, 4.0, 18.0, 16.0), "M3 12 H21", "M9 4 V20", "M15 4 V20")
    val undo = icon("M9 14 4 9l5-5", "M20 20v-7a4 4 0 0 0-4-4H4")
    val redo = icon("M15 14l5-5-5-5", "M4 20v-7a4 4 0 0 1 4-4h12")
    val zoomIn = icon(circle(11.0, 11.0, 8.0), "M21 21l-4.35-4.35", "M11 8v6", "M8 11h6")
    val zoomOut = icon(circle(11.0, 11.0, 8.0), "M21 21l-4.35-4.35", "M8 11h6")
    val fit = icon("M8 3H5a2 2 0 0 0-2 2v3", "M21 8V5a2 2 0 0 0-2-2h-3", "M3 16v3a2 2 0 0 0 2 2h3", "M16 21h3a2 2 0 0 0 2-2v-3")
    val page = icon(rect(3.0, 3.0, 18.0, 18.0), "M12 8v8", "M8 12h8")
    // The infinite canvas: a ruled field running past its own edges.
    val canvas = icon(rect(3.0, 3.0, 18.0, 18.0), "M3 9h18", "M3 15h18", "M9 3v18", "M15 3v18")
    // The overview map: the canvas frame opening at its corner for the panel that rests there,
    // which is where the minimap itself sits. Two bare squares one inside the other read as a
    // stacking order instead, and the inner one had no room to breathe.
    val map = icon(
        "M21 9V6a2 2 0 0 0-2-2H4a2 2 0 0 0-2 2v10a2 2 0 0 0 2 2h4",
        roundRect(12.0, 13.0, 10.0, 7.0, 2.0),
    )
    val prev = icon("M15 18l-6-6 6-6")
    val next = icon("M9 18l6-6-6-6")
    val chevronDown = icon("M6 9l6 6 6-6")
    val file = icon("M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8Z", "M14 2v6h6")
    val edit = icon("M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7", "M18.5 2.5a2.12 2.12 0 0 1 3 3L12 15l-4 1 1-4Z")
    val sidebar = icon(rect(3.0, 3.0, 18.0, 18.0), "M9 3v18")
    val fullscreen = icon("M15 3h6v6", "M9 21H3v-6", "M21 3l-7 7", "M3 21l7-7")
    val plus = icon("M12 5v14", "M5 12h14")
    val minus = icon("M5 12h14")
    // The flow bar's font-face button (Lucide "type").
    val fontFace = icon("M12 4v16", "M4 7V5a1 1 0 0 1 1-1h14a1 1 0 0 1 1 1v2", "M9 20h6")
    val trash = icon("M3 6h18", "M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6", "M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2")
    val close = icon("M18 6 6 18", "M6 6l12 12")
    val bookmark = icon("M19 21l-7-5-7 5V5a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2Z")
    val contents = icon("M8 6h13", "M8 12h13", "M8 18h13", "M3 6h.01", "M3 12h.01", "M3 18h.01")
    val thumbnails = icon(rect(3.0, 3.0, 7.0, 7.0), rect(14.0, 3.0, 7.0, 7.0), rect(3.0, 14.0, 7.0, 7.0), rect(14.0, 14.0, 7.0, 7.0))
    val lock = icon(rect(3.0, 11.0, 18.0, 11.0), "M7 11V7a5 5 0 0 1 10 0v4")
    val unlock = icon(rect(3.0, 11.0, 18.0, 11.0), "M7 11V7a5 5 0 0 1 9.9-1")
    // The straightedge. The body is wider and the graduations hang from one edge so they read at
    // the 22dp the toolbar draws; they are absolute movetos because icon() concatenates the
    // strings into one path, where a relative "m" would start from the previous subpath.
    val ruler = icon(
        "M20.6 14.2a2.6 2.6 0 0 1 0 3.7l-2.7 2.7a2.6 2.6 0 0 1-3.7 0L3.4 9.8a2.6 2.6 0 0 1 0-3.7l2.7-2.7a2.6 2.6 0 0 1 3.7 0Z",
        "M12 5.6 9.6 8", "M15.2 8.8 12.8 11.2", "M18.4 12 16 14.4",
    )
    val magicWand = icon(
        "m21.64 3.64-1.28-1.28a1.21 1.21 0 0 0-1.72 0L2.36 18.64a1.21 1.21 0 0 0 0 1.72l1.28 1.28a1.2 1.2 0 0 0 1.72 0L21.64 5.36a1.2 1.2 0 0 0 0-1.72Z",
        "M14 7 17 10", "M5 6v4", "M19 14v4", "M10 2v2", "M7 8H3", "M21 16h-4", "M11 3H9",
    )
    // Editing a table's structure, drawn as the mode itself looks: a grid with the width rail
    // under it and the height rail beside it, each running the whole side it resizes.
    val tableEdit = icon(rect(4.0, 4.0, 12.0, 12.0), "M4 10h12", "M10 4v12", "M20 4v12", "M4 20h12")

    // Two sheets, the front one rounded and clear of the viewBox edge. Duplicate is the same
    // picture plus a "+", so the pair reads as one family; bring-to-front is deliberately not a
    // third stack of squares, since three near-identical square glyphs in one bar tell you nothing.
    private const val COPY_BACK_SHEET = "M4 16a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2"
    val copy = icon(roundRect(8.0, 8.0, 14.0, 14.0, 2.0), COPY_BACK_SHEET)
    val cut = icon(circle(6.0, 6.0, 3.0), circle(6.0, 18.0, 3.0), "M20 4 8.12 15.88", "M14.47 14.48 20 20", "M8.12 8.12 12 12")
    val duplicate = icon(roundRect(8.0, 8.0, 14.0, 14.0, 2.0), COPY_BACK_SHEET, "M15 12v6", "M12 15h6")
    /** Bring to front: a stack of plates seen in three-quarter view. The stack is the z-order
     *  itself and the top plate is where the selection is going, with no arrow over it. */
    val front = icon(
        "M12 2.5 22 7.5 12 12.5 2 7.5Z",
        "M22 12.5 12 17.5 2 12.5",
        "M22 17.5 12 22.5 2 17.5",
    )
    /** Two panes with a divider down the middle: opening a pair of files side by side. */
    val split = icon(rect(3.0, 3.0, 18.0, 18.0), "M12 3v18")

    // Backstage (File area) commands.
    val folder = icon("M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2Z")
    val save = icon("M19 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11l5 5v11a2 2 0 0 1-2 2Z", "M17 21v-8H7v8", "M7 3v5h8")
    val database = icon(ellipse(12.0, 5.0, 9.0, 3.0), "M3 5V19A9 3 0 0 0 21 19V5", "M3 12A9 3 0 0 0 21 12")
    val importDoc = icon("M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4", "M7 10l5 5 5-5", "M12 15V3")
    val exportDoc = icon("M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4", "M17 8l-5-5-5 5", "M12 3v12")
    // "Save as" / save-to-device uses the download glyph (a tray with a down arrow) so it reads
    // distinctly from Share's up-and-out arrow; same picture as importDoc, named for the action.
    val download = importDoc
    val sliders = icon("M4 21v-7", "M4 10V3", "M12 21v-9", "M12 8V3", "M20 21v-5", "M20 12V3", "M1 14h6", "M9 8h6", "M17 16h6")
    // Page margins: the sheet, with L brackets marking where the content area starts on each edge.
    val margins = icon(
        roundRect(3.0, 2.0, 18.0, 20.0, 2.0),
        "M7 9.5V6h3.5", "M17 9.5V6h-3.5", "M7 14.5V18h3.5", "M17 14.5V18h-3.5",
    )
    val view = icon("M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8Z", circle(12.0, 12.0, 3.0))
    val home = icon("M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2Z", "M9 22V12h6v10")
    val share = icon("M4 12v8a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-8", "M16 6l-4-4-4 4", "M12 2v13")
    val check = icon("M20 6 9 17l-5-5")
    val arrowUp = icon("M12 19V5", "M5 12l7-7 7 7")
    val arrowDown = icon("M12 5v14", "M19 12l-7 7-7-7")
    val newFolder = icon("M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2Z", "M12 11v6", "M9 14h6")
    val pin = icon(
        "M12 17v5",
        "M9 10.76a2 2 0 0 1-1.11 1.79l-1.78.9A2 2 0 0 0 5 15.24V16a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-.76a2 2 0 0 0-1.11-1.79l-1.78-.9A2 2 0 0 1 15 10.76V7a1 1 0 0 1 1-1 2 2 0 0 0 0-4H8a2 2 0 0 0 0 4 1 1 0 0 1 1 1z",
    )
    val paste = icon("M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2", "M9 2h6a1 1 0 0 1 1 1v1a1 1 0 0 1-1 1H9a1 1 0 0 1-1-1V3a1 1 0 0 1 1-1Z")
    val more = icon(circle(12.0, 5.0, 1.0), circle(12.0, 12.0, 1.0), circle(12.0, 19.0, 1.0))
    // Filter/sort: three horizontal lines that narrow top to bottom (Lucide "list-filter").
    val filter = icon("M3 6h18", "M7 12h10", "M10 18h4")
    val menu = icon("M4 6h16", "M4 12h16", "M4 18h16")
    val search = icon(circle(11.0, 11.0, 8.0), "M21 21l-4.35-4.35")

    // Explorer layouts and view options.
    val layoutGrid = icon(
        roundRect(3.0, 3.0, 7.0, 7.0, 1.0), roundRect(14.0, 3.0, 7.0, 7.0, 1.0),
        roundRect(14.0, 14.0, 7.0, 7.0, 1.0), roundRect(3.0, 14.0, 7.0, 7.0, 1.0),
    )
    /** Two whole pages standing on a shelf. */
    val gallery = icon(roundRect(3.0, 3.0, 8.0, 14.0, 1.0), roundRect(13.0, 3.0, 8.0, 14.0, 1.0), "M2 21h20")
    val list = icon("M8 6h13", "M8 12h13", "M8 18h13", "M3 6h.01", "M3 12h.01", "M3 18h.01")
    val columns = icon(roundRect(3.0, 3.0, 18.0, 18.0, 2.0), "M9 3v18", "M15 3v18")
    val timeline = icon(
        roundRect(3.0, 4.0, 18.0, 18.0, 2.0), "M16 2v4", "M8 2v4", "M3 10h18",
        "M8 14h.01", "M12 14h.01", "M16 14h.01", "M8 18h.01", "M12 18h.01",
    )
    val sort = icon("M21 16l-4 4-4-4", "M17 20V4", "M3 8l4-4 4 4", "M7 4v16")
    val layers = icon(
        "M12.83 2.18a2 2 0 0 0-1.66 0L2.6 6.08a1 1 0 0 0 0 1.83l8.58 3.91a2 2 0 0 0 1.66 0l8.58-3.9a1 1 0 0 0 0-1.83Z",
        "M2 12a1 1 0 0 0 .58.91l8.6 3.91a2 2 0 0 0 1.65 0l8.58-3.9A1 1 0 0 0 22 12",
        "M2 17a1 1 0 0 0 .58.91l8.6 3.91a2 2 0 0 0 1.65 0l8.58-3.9A1 1 0 0 0 22 17",
    )
    val clock = icon(circle(12.0, 12.0, 10.0), "M12 6v6l4 2")
    val restore = icon("M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8", "M3 3v5h5")
    val moveToFolder = icon(
        "M2 9V5a2 2 0 0 1 2-2h3.9a2 2 0 0 1 1.69.9l.81 1.2a2 2 0 0 0 1.67.9H20a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2v-1",
        "M2 13h10", "M9 16l3-3-3-3",
    )
    val palette = icon(
        "M12 2C6.5 2 2 6.5 2 12s4.5 10 10 10c.93 0 1.65-.75 1.65-1.69 0-.44-.18-.83-.44-1.12-.29-.29-.44-.65-.44-1.13a1.64 1.64 0 0 1 1.67-1.67h2c3.05 0 5.55-2.5 5.55-5.55C21.97 6.01 17.46 2 12 2Z",
        circle(13.5, 6.5, 0.5), circle(17.5, 10.5, 0.5), circle(8.5, 7.5, 0.5), circle(6.5, 12.5, 0.5),
    )
    /** A stack of pages, for a note's page count. */
    val pages = icon(
        "M20 7h-3a2 2 0 0 1-2-2V2",
        "M9 18a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h7l4 4v10a2 2 0 0 1-2 2Z",
        "M3 7.6v12.8A1.6 1.6 0 0 0 4.6 22h9.8",
    )
    /** A document with lines of text: a note that annotates a PDF. */
    val pdf = icon(
        "M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z", "M14 2v4a2 2 0 0 0 2 2h4",
        "M10 9H8", "M16 13H8", "M16 17H8",
    )
    /** The top of a page with "PDF" lettered beneath it: exporting to PDF. */
    val pdfFile = icon(
        "M4 11V4a2 2 0 0 1 2-2h9l5 5v4", "M14 2v4a2 2 0 0 0 2 2h4",
        "M4.5 21v-6h1.5a1.5 1.5 0 0 1 0 3h-1.5",
        "M10.5 15v6h1a2 2 0 0 0 2-2v-2a2 2 0 0 0-2-2Z",
        "M19.5 15h-3v6", "M16.5 18h2",
    )

    // About / feedback links.
    val info = icon(circle(12.0, 12.0, 10.0), "M12 16v-4", "M12 8h.01")
    val bug = icon(
        "M8 2l1.88 1.88", "M14.12 3.88 16 2",
        "M9 7.13v-1a3.003 3.003 0 1 1 6 0v1",
        "M12 20c-3.3 0-6-2.7-6-6v-3a4 4 0 0 1 4-4h4a4 4 0 0 1 4 4v3c0 3.3-2.7 6-6 6",
        "M12 20v-9", "M6.53 9C4.6 8.8 3 7.1 3 5", "M6 13H2",
        "M3 21c0-2.1 1.7-3.9 3.8-4", "M20.97 5c0 2.1-1.6 3.8-3.5 4",
        "M22 13h-4", "M17.2 17c2.1.1 3.8 1.9 3.8 4",
    )
    val idea = icon(
        "M15 14c.2-1 .7-1.7 1.5-2.5 1-.9 1.5-2.2 1.5-3.5A6 6 0 0 0 6 8c0 1 .2 2.2 1.5 3.5.7.7 1.3 1.5 1.5 2.5",
        "M9 18h6", "M10 22h4",
    )
    val github = icon("M9 19c-5 1.5-5-2.5-7-3m14 6v-3.87a3.37 3.37 0 0 0-.94-2.61c3.14-.35 6.44-1.54 6.44-7A5.44 5.44 0 0 0 20 4.77 5.07 5.07 0 0 0 19.91 1S18.73.65 16 2.48a13.38 13.38 0 0 0-7 0C6.27.65 5.09 1 5.09 1A5.07 5.07 0 0 0 5 4.77a5.44 5.44 0 0 0-1.5 3.78c0 5.42 3.3 6.61 6.44 7A3.37 3.37 0 0 0 9 18.13V22")
    val heart = icon("M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 0 0 0-7.78Z")
}
