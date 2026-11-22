package com.xnotes.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.R
import com.xnotes.core.geometry.Rect
import com.xnotes.core.text.FlowTable
import com.xnotes.core.text.TableBorders
import com.xnotes.core.text.TableDefaults
import com.xnotes.core.text.TableFrag
import com.xnotes.core.text.TableSnapshot
import com.xnotes.core.text.TableStyle
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import kotlin.math.abs
import kotlin.math.roundToInt

/** Table chrome over the canvas: the edit-mode handles and the restyle dialog. */
@Composable
fun TableChrome(editor: Editor) {
    editor.editingTable?.let { TableEditOverlay(editor, it) }
    editor.tableStyling?.let { TableDialog(editor, it) { editor.closeTableStyle() } }
}

/**
 * The insert-table dialog ([table] null, from the format bar) and the restyle
 * dialog (from a table's long-press menu). Values start from the saved new-table
 * defaults (restyling: from the table) and stay local to the dialog, like the
 * View menu: "Default for new tables" saves them and Reset returns to factory
 * values. Restyling shows live and lands as one undo step when the dialog closes.
 */
@Composable
fun TableDialog(editor: Editor, table: FlowTable?, onDismiss: () -> Unit) {
    val palette = LocalPalette.current
    val saved = editor.newTableDefaults
    val before = remember(table) { table?.snapshot() }
    var rows by remember { mutableStateOf(saved.rows) }
    var cols by remember { mutableStateOf(saved.cols) }
    var style by remember { mutableStateOf(table?.style ?: saved.style) }
    fun current() = if (table == null) TableDefaults(rows, cols, style) else saved.copy(style = style)
    // Same session-sticky rule as the View menu's default row.
    var showDefaultRow by remember { mutableStateOf(current() != saved) }

    fun update(r: Int = rows, c: Int = cols, s: TableStyle = style) {
        rows = r.coerceIn(1, TableDefaults.MAX_ROWS)
        cols = c.coerceIn(1, TableDefaults.MAX_COLS)
        style = s.clamped()
        if (table != null) editor.tablePreview(table, table.snapshot().copy(style = style))
        if (current() != saved) showDefaultRow = true
    }

    fun finish(insert: Boolean) {
        if (table != null && before != null) editor.tableCommitPreview(table, before)
        if (insert) editor.flowInsertTable(rows, cols, style) else if (table == null) editor.flowReshowIme()
        onDismiss()
    }

    SheetDialog(
        title = stringResource(if (table == null) R.string.insert_table else R.string.table_style),
        onDismiss = { finish(insert = false) },
        width = 360.dp,
        gap = 8.dp,
        action = {
            PanelChip(stringResource(R.string.reset)) {
                val factory = TableDefaults()
                update(factory.rows, factory.cols, factory.style)
            }
        },
        foot = {
            if (table == null) ConfirmButtons(stringResource(R.string.insert), { finish(insert = false) }) { finish(insert = true) }
            else SheetButton(stringResource(R.string.done), Modifier.fillMaxWidth(), ButtonKind.CONFIRM) { finish(insert = false) }
        },
    ) {
        PanelCard(padding = PaddingValues(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TablePreview(
                    if (table == null) rows else 4, if (table == null) cols else 3, style, editor.tableRuleColor(),
                    Modifier.weight(1f).height(if (table == null) 120.dp else 104.dp),
                )
                if (table == null) {
                    Column(Modifier.width(128.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Stepper(stringResource(R.string.table_rows), "$rows", onMinus = { update(r = rows - 1) }, onPlus = { update(r = rows + 1) })
                        Stepper(stringResource(R.string.table_columns), "$cols", onMinus = { update(c = cols - 1) }, onPlus = { update(c = cols + 1) })
                    }
                }
            }
        }
        MenuCard(buildList<MenuGroup> {
            add {
                MenuSegmented(BORDER_CHOICES.map { stringResource(it.labelRes) }, BORDER_CHOICES.indexOf(style.borders)) {
                    update(s = style.copy(borders = BORDER_CHOICES[it]))
                }
                Spacer(Modifier.height(10.dp))
            }
            add {
                Row(Modifier.padding(start = 14.dp, top = 10.dp, end = 10.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Stepper(
                        stringResource(R.string.table_padding), stringResource(R.string.table_pt_value, "%.0f".format(style.paddingPt)), Modifier.weight(1f),
                        onMinus = { update(s = style.copy(paddingPt = style.paddingPt - 1.0)) },
                        onPlus = { update(s = style.copy(paddingPt = style.paddingPt + 1.0)) },
                    )
                    Stepper(
                        stringResource(R.string.table_lines), stringResource(R.string.table_pt_value, "%.2f".format(style.lineWidthPt)), Modifier.weight(1f),
                        onMinus = { update(s = style.copy(lineWidthPt = style.lineWidthPt - LINE_STEP)) },
                        onPlus = { update(s = style.copy(lineWidthPt = style.lineWidthPt + LINE_STEP)) },
                    )
                }
            }
            add {
                MenuRow(stringResource(R.string.table_line_colour)) {
                    TableColor(style.lineColor, editor.tableRuleColor(), editor) { update(s = style.copy(lineColor = it)) }
                }
            }
        })
        MenuCard(buildList<MenuGroup> {
            add { MenuSwitch(stringResource(R.string.table_header_row), style.headerRow) { update(s = style.copy(headerRow = it)) } }
            add { MenuSwitch(stringResource(R.string.table_banded_rows), style.banded) { update(s = style.copy(banded = it)) } }
            if (style.headerRow || style.banded) {
                add { MenuRow(stringResource(R.string.table_tint)) { TableColor(style.tint, palette.accent, editor) { update(s = style.copy(tint = it)) } } }
            }
        })
        if (showDefaultRow && !current().isFactory) {
            PanelCheck(stringResource(R.string.default_for_new_tables), !saved.isFactory && current() == saved) { on ->
                editor.saveNewTableDefaults(if (on) current() else TableDefaults())
            }
        }
    }
}

/** A small live sketch of the table's look: fills and rules. */
@Composable
private fun TablePreview(rows: Int, cols: Int, style: TableStyle, autoRule: com.xnotes.core.model.Rgba, modifier: Modifier) {
    val palette = LocalPalette.current
    val tint = (style.tint ?: palette.accent)
    val line = (style.lineColor ?: autoRule).toComposeColor()
    val header = tint.withAlpha(com.xnotes.core.text.FlowLayout.HEADER_ALPHA).toComposeColor()
    val band = tint.withAlpha(com.xnotes.core.text.FlowLayout.BAND_ALPHA).toComposeColor()
    Canvas(modifier.clip(MaterialTheme.shapes.small).background(palette.bg.toComposeColor())) {
        val r = rows.coerceAtMost(PREVIEW_MAX)
        val c = cols.coerceAtMost(PREVIEW_MAX)
        val margin = 10.dp.toPx()
        val w = size.width - 2 * margin
        val h = size.height - 2 * margin
        val cw = w / c
        val rh = h / r
        val lw = (style.lineWidthPt.toFloat() * 0.8f).dp.toPx().coerceAtLeast(1f)
        for (i in 0 until r) {
            val body = i - if (style.headerRow) 1 else 0
            val fill = when {
                style.headerRow && i == 0 -> header
                style.banded && body >= 0 && body % 2 == 1 -> band
                else -> null
            } ?: continue
            drawRect(fill, Offset(margin, margin + i * rh), Size(w, rh))
        }
        fun hLine(y: Float) = drawLine(line, Offset(margin, y), Offset(margin + w, y), lw)
        fun vLine(x: Float) = drawLine(line, Offset(x, margin), Offset(x, margin + h), lw)
        when (style.borders) {
            TableBorders.NONE -> {}
            TableBorders.OUTER -> drawRect(line, Offset(margin, margin), Size(w, h), style = Stroke(lw))
            TableBorders.HORIZONTAL -> for (i in 0..r) hLine(margin + i * rh)
            TableBorders.ALL -> {
                for (i in 0..r) hLine(margin + i * rh)
                for (j in 0..c) vLine(margin + j * cw)
            }
        }
    }
}

/** A number of the table under its [label], stepped one at a time. */
@Composable
private fun Stepper(label: String, value: String, modifier: Modifier = Modifier, onMinus: () -> Unit, onPlus: () -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = LocalPalette.current.textDim.toComposeColor(), fontSize = 12.sp, maxLines = 1)
        MenuStepper(value, stringResource(R.string.decrease), stringResource(R.string.increase), Modifier.fillMaxWidth(), onLess = onMinus, onMore = onPlus)
    }
}

/** Auto (null = follow the theme) or a colour of its own. */
@Composable
private fun TableColor(value: com.xnotes.core.model.Rgba?, auto: com.xnotes.core.model.Rgba, editor: Editor, onChange: (com.xnotes.core.model.Rgba?) -> Unit) {
    ColorOrNone(stringResource(R.string.auto), value, auto, onChange) { d, p -> ColorPickerPopup(value ?: auto, editor.recentColors, d, p) }
}

// --- edit mode ---

private enum class Axis { ROW, COL }

/** A row or column being dragged to a new place: its [from] index and the finger in viewport px. */
private data class Reorder(val axis: Axis, val frag: Int, val from: Int, val pos: Offset)

/** A table slice with its viewport rect; maps page-local coordinates onto the screen. */
private class Placed(val frag: TableFrag, val vp: Rect) {
    private val sx = vp.w / (frag.right - frag.left)
    private val sy = vp.h / (frag.bottom - frag.top)

    fun x(cx: Double): Float = (vp.left + (cx - frag.left) * sx).toFloat()
    fun y(cy: Double): Float = (vp.top + (cy - frag.top) * sy).toFloat()

    /** Viewport px per content px, vertically. */
    val scaleY: Double get() = sy
}

/**
 * Structure-edit chrome over [table] (the long-press menu's first button): an
 * accent outline, + buttons at every row and column boundary, grips that drag a
 * row or column to a new place with an x that deletes it, handles on the bottom
 * edge for the column widths and the table's own width, and on the right edge
 * for row heights. Width and height drags preview live and land as one undo
 * step. A tap anywhere else on the canvas ends the mode (the flow controller's
 * gate).
 */
@Composable
private fun TableEditOverlay(editor: Editor, table: FlowTable) {
    editor.tableChromeTick
    val palette = LocalPalette.current
    val density = LocalDensity.current
    val placed = editor.tableFrags(table).mapNotNull { (pi, f) -> editor.pageRectToViewport(pi, f.rect)?.let { Placed(f, it) } }
    if (placed.isEmpty()) return
    val current by rememberUpdatedState(placed)
    var reorder by remember { mutableStateOf<Reorder?>(null) }
    val accent = palette.accent.toComposeColor()
    fun px(d: Dp) = with(density) { d.toPx() }

    fun finishReorder() {
        val r = reorder ?: return
        reorder = null
        val p = current.getOrNull(r.frag) ?: return
        if (r.axis == Axis.COL) {
            val xs = p.frag.colXs.map { p.x(it) }
            val b = xs.indices.minByOrNull { abs(xs[it] - r.pos.x) } ?: return
            val to = if (b > r.from) b - 1 else b
            if (to != r.from) editor.tableMoveCol(table, r.from, to)
        } else {
            val (rows, ys) = rowBoundaries(p)
            val k = ys.indices.minByOrNull { abs(ys[it] - r.pos.y) } ?: return
            val b = rows[k]
            val to = if (b > r.from) b - 1 else b
            if (to != r.from) editor.tableMoveRow(table, r.from, to)
        }
    }

    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            for (p in placed) {
                drawRect(
                    accent,
                    Offset(p.vp.left.toFloat(), p.vp.top.toFloat()),
                    Size(p.vp.w.toFloat(), p.vp.h.toFloat()),
                    style = Stroke(2.dp.toPx()),
                )
            }
            val r = reorder ?: return@Canvas
            val p = placed.getOrNull(r.frag) ?: return@Canvas
            val bar = 3.dp.toPx()
            if (r.axis == Axis.COL) {
                val xs = p.frag.colXs.map { p.x(it) }
                val b = xs.indices.minByOrNull { abs(xs[it] - r.pos.x) } ?: return@Canvas
                drawRect(accent.copy(alpha = 0.18f), Offset(xs[r.from], p.vp.top.toFloat()), Size(xs[r.from + 1] - xs[r.from], p.vp.h.toFloat()))
                drawLine(accent, Offset(xs[b], p.vp.top.toFloat()), Offset(xs[b], p.vp.bottom.toFloat()), bar)
            } else {
                val ys = rowBoundaries(p).second
                val k = ys.indices.minByOrNull { abs(ys[it] - r.pos.y) } ?: return@Canvas
                p.frag.rows.firstOrNull { it.row == r.from }?.let { row ->
                    drawRect(accent.copy(alpha = 0.18f), Offset(p.vp.left.toFloat(), p.y(row.top)), Size(p.vp.w.toFloat(), p.y(row.bottom) - p.y(row.top)))
                }
                drawLine(accent, Offset(p.vp.left.toFloat(), ys[k]), Offset(p.vp.right.toFloat(), ys[k]), bar)
            }
        }

        for ((fi, p) in placed.withIndex()) {
            val f = p.frag
            val cols = f.colXs.size - 1
            val gripY = (p.vp.top - px(18.dp)).toFloat()
            val plusY = (p.vp.top - px(44.dp)).toFloat()
            val gripX = (p.vp.left - px(30.dp)).toFloat()
            val plusX = (p.vp.left - px(62.dp)).toFloat()

            // Columns: + at every boundary, a grip (drag to move) and an x over each column.
            for (b in 0..cols) {
                key("cp", fi, b) {
                    PlusButton(p.x(f.colXs[b]), plusY) { editor.tableInsertCol(table, b) }
                }
            }
            for (c in 0 until cols) {
                key("cg", fi, c) {
                    val center by rememberUpdatedState(Offset((p.x(f.colXs[c]) + p.x(f.colXs[c + 1])) / 2f, gripY))
                    Grip(
                        center.x, center.y, vertical = false,
                        onDelete = { editor.tableDeleteCol(table, c) },
                        onDragStart = { reorder = Reorder(Axis.COL, fi, c, center) },
                        onDrag = { d -> reorder = reorder?.let { it.copy(pos = it.pos + d) } },
                        onDragEnd = { finishReorder() },
                        onDragCancel = { reorder = null },
                    )
                }
            }

            // Rows: + where a row starts or ends on this page, a grip and an x beside each row slice.
            val (rowsAt, ysAt) = rowBoundaries(p)
            for (k in rowsAt.indices) {
                key("rp", fi, k) {
                    val at = rowsAt[k]
                    PlusButton(plusX, ysAt[k]) { editor.tableInsertRow(table, at) }
                }
            }
            for (row in f.rows) {
                key("rg", fi, row.row) {
                    val center by rememberUpdatedState(Offset(gripX, (p.y(row.top) + p.y(row.bottom)) / 2f))
                    Grip(
                        center.x, center.y, vertical = true,
                        onDelete = { editor.tableDeleteRow(table, row.row) },
                        onDragStart = { reorder = Reorder(Axis.ROW, fi, row.row, center) },
                        onDrag = { d -> reorder = reorder?.let { it.copy(pos = it.pos + d) } },
                        onDragEnd = { finishReorder() },
                        onDragCancel = { reorder = null },
                    )
                }
            }

            // Column widths: a handle under every inner boundary; the neighbours trade width.
            val widthY = (p.vp.bottom + px(16.dp)).toFloat()
            for (c in 1 until cols) {
                key("cw", fi, c) {
                    val tableW by rememberUpdatedState(p.vp.w.toFloat())
                    DragHandle(p.x(f.colXs[c]), widthY, vertical = true, table, 0.0) { start, _, dx, _, done ->
                        val w = start.widths.toMutableList()
                        val d = (dx / tableW).toDouble().coerceIn(MIN_COL - w[c - 1], w[c] - MIN_COL)
                        w[c - 1] += d
                        w[c] -= d
                        finishDrag(editor, table, start, start.copy(widths = w), done)
                    }
                }
            }

            // The outer boundary moves the table's own right edge, since no column waits beyond
            // it to trade with; the columns keep their shares of whatever width is left. Unlike
            // the boundaries above it this drag moves the table's own width, so it measures
            // against the width at the grab, not the one it is busy changing.
            key("tw", fi) {
                DragHandle(p.x(f.colXs[cols]), widthY, vertical = true, table, p.vp.w) { start, grabbed, dx, _, done ->
                    val next = FlowTable.widthAfterDrag(start.width, grabbed, dx.toDouble())
                    finishDrag(editor, table, start, start.copy(width = next), done)
                }
            }

            // Row heights: a handle beside the bottom of each row that ends on this page.
            val heightX = (p.vp.right + px(16.dp)).toFloat()
            for ((k, row) in f.rows.withIndex()) {
                if (k == f.rows.lastIndex && continuesOnNextPage(placed, fi)) continue
                key("rh", fi, row.row) {
                    val scaleY by rememberUpdatedState(p.scaleY)
                    DragHandle(heightX, p.y(row.bottom), vertical = false, table, row.bottom - row.top) { start, rowH, _, dy, done ->
                        val h = List(maxOf(start.minHeights.size, row.row + 1)) { start.minHeights.getOrElse(it) { 0.0 } }.toMutableList()
                        h[row.row] = ((rowH + dy / scaleY) / TableStyle.PX_PER_PT).coerceAtLeast(0.0)
                        finishDrag(editor, table, start, start.copy(minHeights = h), done)
                    }
                }
            }

            if (fi == 0) {
                key("done") {
                    Centered((p.vp.right + px(30.dp)).toFloat(), plusY, 30.dp, 30.dp, Modifier.clip(CircleShape).background(accent).clickable { editor.endTableEdit() }, tip = stringResource(R.string.done)) {
                        Icon(Icons.Filled.Check, stringResource(R.string.done), tint = palette.bg.toComposeColor(), modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

/** Preview [next] while a drag runs; [done] lands it as one undo step (or reverts a cancel). */
private fun finishDrag(editor: Editor, table: FlowTable, start: TableSnapshot, next: TableSnapshot, done: Boolean?) {
    when (done) {
        null -> editor.tablePreview(table, next)
        true -> editor.tableCommitPreview(table, start)
        false -> editor.tablePreview(table, start)
    }
}

/** True when the last row slice of [placed][i] carries on at the top of the next slice. */
private fun continuesOnNextPage(placed: List<Placed>, i: Int): Boolean {
    val next = placed.getOrNull(i + 1)?.frag?.rows?.firstOrNull() ?: return false
    return placed[i].frag.rows.last().row == next.row
}

/** Insertion points on a slice: the row index a boundary inserts before, and its viewport y. */
private fun rowBoundaries(p: Placed): Pair<List<Int>, List<Float>> {
    val rows = mutableListOf<Int>()
    val ys = mutableListOf<Float>()
    val slices = p.frag.rows
    rows.add(slices.first().row)
    ys.add(p.y(slices.first().top))
    for ((k, s) in slices.withIndex()) {
        val nextRow = slices.getOrNull(k + 1)?.row
        if (nextRow == s.row) continue
        rows.add(s.row + 1)
        ys.add(p.y(s.bottom))
    }
    return rows to ys
}

/** A box of [w] x [h] centred on viewport ([x], [y]), named by [tip] when the pen hovers it. */
@Composable
private fun Centered(
    x: Float,
    y: Float,
    w: Dp,
    h: Dp,
    modifier: Modifier = Modifier,
    tip: String? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val density = LocalDensity.current
    val wp = with(density) { w.toPx() }
    val hp = with(density) { h.toPx() }
    val place = Modifier.offset { IntOffset((x - wp / 2f).roundToInt(), (y - hp / 2f).roundToInt()) }
    if (tip == null) {
        Box(place.size(w, h).then(modifier), contentAlignment = Alignment.Center, content = content)
    } else {
        Tooltip(tip, place) { Box(Modifier.size(w, h).then(modifier), contentAlignment = Alignment.Center, content = content) }
    }
}

@Composable
private fun PlusButton(x: Float, y: Float, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Centered(x, y, 22.dp, 22.dp, Modifier.clip(CircleShape).background(palette.accent.toComposeColor()).clickable(onClick = onClick), tip = stringResource(R.string.insert)) {
        Icon(Icons.Filled.Add, stringResource(R.string.insert), tint = palette.bg.toComposeColor(), modifier = Modifier.size(16.dp))
    }
}

/** A row/column pill: a drag grip to move it and an x to delete it. */
@Composable
private fun Grip(
    x: Float,
    y: Float,
    vertical: Boolean,
    onDelete: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
) {
    val palette = LocalPalette.current
    val shape = MaterialTheme.shapes.medium
    val start by rememberUpdatedState(onDragStart)
    val move by rememberUpdatedState(onDrag)
    val end by rememberUpdatedState(onDragEnd)
    val cancel by rememberUpdatedState(onDragCancel)
    Centered(
        x, y, 52.dp, 22.dp,
        Modifier
            .clip(shape)
            .background(palette.menuBg.toComposeColor())
            .border(1.dp, palette.border.toComposeColor(), shape),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Tooltip(stringResource(R.string.move)) {
                Box(
                    Modifier
                        .size(28.dp, 22.dp)
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { start() },
                                onDragEnd = { end() },
                                onDragCancel = { cancel() },
                            ) { change, amount ->
                                change.consume()
                                move(amount)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.DragIndicator,
                        stringResource(R.string.move),
                        tint = palette.textDim.toComposeColor(),
                        modifier = Modifier.size(16.dp).rotate(if (vertical) 0f else 90f),
                    )
                }
            }
            Tooltip(stringResource(R.string.delete)) {
                Box(Modifier.size(24.dp, 22.dp).clickable(onClick = onDelete), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Close, stringResource(R.string.delete), tint = palette.text.toComposeColor(), modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}

/** One resize drag in flight: the table and the handle's [measure] as it began, and the travel. */
private class HandleDrag {
    var start: TableSnapshot? = null
    var measure = 0.0
    var dx = 0f
    var dy = 0f
}

/**
 * A resize handle at viewport ([x], [y]): a pill across the edge it moves
 * ([vertical] = a column boundary, dragged sideways). [onChange] receives the
 * table and [measure] as the drag began (the layout moves under a live drag),
 * the travel so far, and done = null while moving, true at the end, false on
 * cancel. A handle that leaves the composition mid-drag (its row moved page)
 * lands what it had.
 */
@Composable
private fun DragHandle(
    x: Float,
    y: Float,
    vertical: Boolean,
    table: FlowTable,
    measure: Double,
    onChange: (start: TableSnapshot, measure: Double, dx: Float, dy: Float, done: Boolean?) -> Unit,
) {
    val palette = LocalPalette.current
    val change by rememberUpdatedState(onChange)
    val current by rememberUpdatedState(measure)
    val drag = remember { HandleDrag() }
    fun emit(done: Boolean?) {
        val start = drag.start ?: return
        if (done != null) drag.start = null
        change(start, drag.measure, drag.dx, drag.dy, done)
    }
    DisposableEffect(Unit) { onDispose { emit(true) } }
    Centered(
        x, y, if (vertical) 22.dp else 34.dp, if (vertical) 34.dp else 22.dp,
        Modifier.pointerInput(table) {
            detectDragGestures(
                onDragStart = {
                    drag.start = table.snapshot()
                    drag.measure = current
                    drag.dx = 0f
                    drag.dy = 0f
                },
                onDragEnd = { emit(true) },
                onDragCancel = { emit(false) },
            ) { c, amount ->
                c.consume()
                drag.dx += amount.x
                drag.dy += amount.y
                emit(null)
            }
        },
    ) {
        Box(
            Modifier
                .size(if (vertical) 8.dp else 24.dp, if (vertical) 24.dp else 8.dp)
                .clip(MaterialTheme.shapes.extraSmall)
                .background(palette.accent.toComposeColor()),
        )
    }
}

private val BORDER_CHOICES = listOf(
    TableBorders.ALL,
    TableBorders.OUTER,
    TableBorders.HORIZONTAL,
    TableBorders.NONE,
)

private const val LINE_STEP = 0.25
private const val PREVIEW_MAX = 8

/** Narrowest a column can be dragged, as a fraction of the table. */
private const val MIN_COL = 0.04
