package com.xnotes.ui

import android.content.Context
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.xnotes.R
import com.xnotes.core.model.Rgba
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.ColorMath
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import kotlin.math.roundToInt

// --- The 13×13 swatch matrix --------------------------------------------------------------------
// 13 hues across; 12 hue×shade rows that ramp from a pale tint at the top, through the pure hue,
// down to near-black at the bottom; then a 13th greyscale row (white → black). The deep bottom
// rows are the ones the old 7-row grid lacked (it stopped at value 0.42).

private val SWATCH_HUES: List<Double> = (0 until 13).map { it * 360.0 / 13.0 }

/** (saturation, value) per row, top → bottom. */
private val SWATCH_SHADES: List<Pair<Double, Double>> = listOf(
    0.18 to 1.00, // pale tint
    0.34 to 1.00,
    0.52 to 1.00,
    0.72 to 1.00,
    0.88 to 1.00,
    1.00 to 1.00, // pure hue
    1.00 to 0.86,
    1.00 to 0.72,
    1.00 to 0.58,
    1.00 to 0.44,
    1.00 to 0.30,
    1.00 to 0.18, // near-black
)

private const val CELL = 18 // swatch cell size outside the picker, dp
private const val COLUMNS = 13

/** The matrix's colours row by row: the twelve shades of each hue, then the greys. */
private val SWATCHES: List<List<Rgba>> =
    SWATCH_SHADES.map { (s, v) -> SWATCH_HUES.map { ColorMath.hsvToRgb(it, s, v) } } +
        listOf((0 until COLUMNS).map { val g = ((1.0 - it / 12.0) * 255).roundToInt(); Rgba(g, g, g) })

private val DIALOG_WIDTH = 300.dp

/** How many recent colours the row over the matrix holds, each a circle this wide. */
private const val RECENTS = 8
private val RECENT_SIZE = 26.dp

/** The gap between the recents and the matrix. */
private val RECENTS_GAP = 10.dp

/** [recents] filled out with the [toolbar]'s colours it lacks, so a new user's row is not empty. */
internal fun recentsWith(recents: List<Rgba>, toolbar: List<Rgba>): List<Rgba> = (recents + toolbar).distinct()

/**
 * The shared colour picker (spec 10 §4). Two tabs over one live colour:
 *  - **Swatches**: recent colours plus the [SWATCH_HUES]×[SWATCH_SHADES] matrix and a greyscale row.
 *  - **Spectrum**: a saturation/value field over a hue bar, drag to pick.
 * A footer under both tabs shows the colour the picker opened with beside the current one, with
 * editable HEX and R/G/B fields.
 *
 * Colours are opaque (the picker never sets alpha; tools that need translucency, e.g. the
 * highlighter, own their own opacity control). [onPick] fires on every *commit* — a swatch tap, a
 * spectrum release, or a field edit — never on each drag sample, so it won't flood undo history or
 * re-rasterise a page background mid-drag. [recents] may be empty (the page/pattern picker keeps no
 * recents); the row shows its first [RECENTS]. The popup stays open across picks and closes only via [onDismiss] (a tap outside).
 */
@Composable
internal fun ColorPickerPopup(
    initial: Rgba?,
    recents: List<Rgba>,
    onDismiss: () -> Unit,
    onPick: (Rgba) -> Unit,
) {
    val was = remember { initial?.copy(a = 255) }
    var current by remember { mutableStateOf(was ?: Rgba(255, 255, 255)) }
    // Hue and saturation are kept explicitly, because a colour does not always carry them: black has
    // no defined hue *or* saturation, and any grey has no hue. Re-deriving them from [current] would
    // send the markers home the moment a drag reached the field's bottom edge or its grey side.
    val startHsv = remember { ColorMath.rgbToHsv(current) }
    var hue by remember { mutableStateOf(startHsv[0]) }
    var sat by remember { mutableStateOf(startHsv[1]) }
    var value by remember { mutableStateOf(startHsv[2]) }
    var tab by remember { mutableStateOf(0) }

    fun applyLive(c: Rgba) {
        current = c
        val hsv = ColorMath.rgbToHsv(c)
        if (hsv[1] > 1e-4 && hsv[2] > 1e-4) hue = hsv[0]
        if (hsv[2] > 1e-4) sat = hsv[1]
        value = hsv[2]
    }
    fun commit(c: Rgba) { applyLive(c); onPick(c) }

    DropdownMenu(expanded = true, onDismissRequest = onDismiss, shape = MaterialTheme.shapes.large, containerColor = panelColors().sheet) {
        // Sized text keeps material's 24sp line otherwise, which the fixed rows clip.
        ProvideTextStyle(LocalTextStyle.current.copy(lineHeight = 1.3.em)) {
            Column(Modifier.width(DIALOG_WIDTH).padding(horizontal = 10.dp, vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) {
                        PanelSegmented(listOf(stringResource(R.string.swatches), stringResource(R.string.spectrum)), tab, { tab = it }, height = 30.dp, sunken = false)
                    }
                    if (LocalEyedropper.current) EyedropperButton { Eyedropper.arm { commit(it) } }
                }
                // The matrix is a square of the card's width, and both tabs take its height, so
                // switching tabs never resizes the dialog.
                val body = DIALOG_WIDTH - 40.dp + if (recents.isEmpty()) 0.dp else RECENT_SIZE + RECENTS_GAP
                PanelCard(padding = PaddingValues(10.dp)) {
                    when (tab) {
                        0 -> SwatchesTab(recents, current, Modifier.height(body)) { commit(it) }
                        else -> SpectrumTab(
                            hue, sat, value, current,
                            onShade = { s, v ->
                                sat = s
                                value = v
                                current = ColorMath.hsvToRgb(hue, s, v)
                            },
                            onHue = { h ->
                                hue = h
                                current = ColorMath.hsvToRgb(h, sat, value)
                            },
                            onCommit = { onPick(current) },
                            modifier = Modifier.height(body),
                        )
                    }
                }
                PanelCard(padding = PaddingValues(10.dp)) { ColorFooter(was, current) { commit(it) } }
            }
        }
    }
}

@Composable
private fun EyedropperButton(onClick: () -> Unit) {
    val label = stringResource(R.string.eyedropper)
    Tooltip(label) {
        Box(
            Modifier
                .size(36.dp)
                .clip(MaterialTheme.shapes.small)
                .background(panelColors().card)
                .clickable(role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(XnotesIcons.eyedropper, label, tint = LocalPalette.current.textDim.toComposeColor(), modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun SwatchesTab(recents: List<Rgba>, current: Rgba, modifier: Modifier = Modifier, onPick: (Rgba) -> Unit) {
    Column(modifier) {
        if (recents.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                // A short row keeps its circles on the full row's slots, from the start.
                for (i in 0 until RECENTS) {
                    Box(Modifier.size(RECENT_SIZE)) { recents.getOrNull(i)?.let { c -> RecentDot(c, c == current) { onPick(c) } } }
                }
            }
            Spacer(Modifier.height(RECENTS_GAP))
        }
        SwatchMatrix(current, onPick, Modifier.fillMaxWidth().aspectRatio(1f))
    }
}

/** A recent colour; the one in use shrinks inside a ring. */
@Composable
private fun RecentDot(color: Rgba, selected: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val base = Modifier.size(RECENT_SIZE).clip(CircleShape).clickable(onClick = onClick)
    Box(
        if (selected) base.border(2.dp, palette.text.toComposeColor(), CircleShape).padding(5.dp).background(color.toComposeColor(), CircleShape)
        else base.background(color.toComposeColor()).border(1.dp, palette.border.toComposeColor(), CircleShape),
    )
}

/** The whole matrix as one drawing: flush cells, rounded outer corners, the chosen cell raised in a ring. */
@Composable
private fun SwatchMatrix(current: Rgba, onPick: (Rgba) -> Unit, modifier: Modifier = Modifier) {
    val pick = rememberUpdatedState(onPick)
    Canvas(
        modifier.pointerInput(Unit) {
            detectTapGestures { at ->
                val col = (at.x * COLUMNS / size.width).toInt().coerceIn(0, COLUMNS - 1)
                val row = (at.y * SWATCHES.size / size.height).toInt().coerceIn(0, SWATCHES.size - 1)
                pick.value(SWATCHES[row][col])
            }
        },
    ) {
        // Whole-px cell edges, so no seam shows between two cells.
        fun edge(i: Int, count: Int, span: Float) = (i * span / count).roundToInt().toFloat()
        val rows = SWATCHES.size
        var chosen: Rect? = null
        val outline = Path().apply { addRoundRect(RoundRect(Rect(Offset.Zero, size), CornerRadius(10.dp.toPx()))) }
        clipPath(outline) {
            for (row in 0 until rows) {
                for (col in 0 until COLUMNS) {
                    val c = SWATCHES[row][col]
                    val cell = Rect(edge(col, COLUMNS, size.width), edge(row, rows, size.height), edge(col + 1, COLUMNS, size.width), edge(row + 1, rows, size.height))
                    drawRect(c.toComposeColor(), cell.topLeft, cell.size)
                    if (c == current) chosen = cell
                }
            }
        }
        chosen?.let { cell ->
            val raised = cell.inflate(cell.width * 0.07f)
            fun plate(color: Color, grow: Float) {
                val r = raised.inflate(grow)
                drawRoundRect(color, r.topLeft, r.size, CornerRadius(5.dp.toPx() + grow))
            }
            plate(Color.Black.copy(alpha = 0.6f), 3.5.dp.toPx())
            plate(Color.White, 2.dp.toPx())
            plate(current.toComposeColor(), 0f)
        }
    }
}

/** The full hue×shade matrix plus the greyscale row at fixed cell size, for menus outside the picker. */
@Composable
internal fun FullSwatchGrid(onPick: (Rgba) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SWATCH_SHADES.forEach { (s, v) ->
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                SWATCH_HUES.forEach { h ->
                    val c = ColorMath.hsvToRgb(h, s, v)
                    SwatchCell(c, false, Modifier.size(CELL.dp)) { onPick(c) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            (0..12).forEach { i ->
                val g = ((1.0 - i / 12.0) * 255).roundToInt()
                val c = Rgba(g, g, g)
                SwatchCell(c, false, Modifier.size(CELL.dp)) { onPick(c) }
            }
        }
    }
}

@Composable
private fun SwatchCell(color: Rgba, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Box(
        modifier
            .clip(RoundedCornerShape(2.dp))
            .background(color.toComposeColor())
            .border(
                if (selected) 1.5.dp else 0.5.dp,
                if (selected) palette.accent.toComposeColor() else palette.border.toComposeColor(),
                RoundedCornerShape(2.dp),
            )
            .clickable(onClick = onClick),
    )
}

// --- Spectrum: a saturation/value field over a hue bar ------------------------------------------

private val KNOB = 11.dp // the field knob's radius
private val HUE_KNOB = 12.dp // the hue knob's radius, and how far the bar's ends sit in from its edges

/**
 * The spectrum reports edits live through [onShade] and [onHue] (every touch sample) and once
 * through [onCommit] (on release), so the caller can paint a smooth preview without committing
 * each sample. The field fills whatever height the bar leaves.
 */
@Composable
private fun SpectrumTab(
    hue: Double,
    sat: Double,
    value: Double,
    current: Rgba,
    onShade: (Double, Double) -> Unit,
    onHue: (Double) -> Unit,
    onCommit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pure = ColorMath.hsvToRgb(hue, 1.0, 1.0).toComposeColor()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Canvas(
            Modifier.fillMaxWidth().weight(1f).pickByDrag(onCommit) { at, size ->
                onShade((at.x / size.width).coerceIn(0f, 1f).toDouble(), (1f - at.y / size.height).coerceIn(0f, 1f).toDouble())
            },
        ) {
            // White to the pure hue across, clear to black down.
            val corner = CornerRadius(10.dp.toPx())
            drawRoundRect(Brush.horizontalGradient(listOf(Color.White, pure)), cornerRadius = corner)
            drawRoundRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)), cornerRadius = corner)
            // Held just inside the field at its edges, so the card never cuts into the knob.
            val inset = 3.dp.toPx()
            val at = Offset(
                (sat.toFloat() * size.width).coerceIn(inset, size.width - inset),
                ((1f - value.toFloat()) * size.height).coerceIn(inset, size.height - inset),
            )
            drawKnob(at, KNOB.toPx(), 2.5.dp.toPx(), current.toComposeColor())
        }
        val ends = with(LocalDensity.current) { HUE_KNOB.toPx() }
        Canvas(
            Modifier.fillMaxWidth().height(28.dp).pickByDrag(onCommit) { at, size ->
                onHue(((at.x - ends) / (size.width - 2 * ends)).coerceIn(0f, 1f) * 360.0)
            },
        ) {
            // The rainbow runs between the knob's two end stops, so the knob always sits on its own hue.
            val hues = (0..12).map { ColorMath.hsvToRgb(it * 30.0, 1.0, 1.0).toComposeColor() }
            val thick = 14.dp.toPx()
            drawRoundRect(
                Brush.horizontalGradient(hues, startX = ends, endX = size.width - ends),
                topLeft = Offset(0f, (size.height - thick) / 2),
                size = Size(size.width, thick),
                cornerRadius = CornerRadius(thick / 2),
            )
            val x = ends + (hue / 360.0).toFloat().coerceIn(0f, 1f) * (size.width - 2 * ends)
            drawKnob(Offset(x, size.height / 2), HUE_KNOB.toPx(), 3.dp.toPx(), pure)
        }
    }
}

/** A round knob filled with the colour it stands on: a white rim, and a dark hairline to hold it on pale colours. */
private fun DrawScope.drawKnob(at: Offset, radius: Float, rim: Float, fill: Color) {
    drawCircle(Color.Black.copy(alpha = 0.5f), radius + 1.dp.toPx(), at)
    drawCircle(Color.White, radius, at)
    drawCircle(fill, radius - rim, at)
}

/** Every touch sample of a press or drag goes to [onMove] with the target's size; [onEnd] fires once at release. */
@Composable
private fun Modifier.pickByDrag(onEnd: () -> Unit, onMove: (Offset, IntSize) -> Unit): Modifier {
    // The gesture coroutine outlives recompositions, so read the latest callbacks via refs.
    val move = rememberUpdatedState(onMove)
    val end = rememberUpdatedState(onEnd)
    return pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            move.value(down.position, size)
            down.consume()
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) { change.consume(); break }
                move.value(change.position, size)
                change.consume()
            }
            end.value()
        }
    }
}

// --- Footer: the colour before and now + HEX / R,G,B fields, shared by both tabs ----------------

@Composable
private fun ColorFooter(was: Rgba?, current: Rgba, onColor: (Rgba) -> Unit) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        WasNow(was, current) { was?.let(onColor) }
        LabelledField(stringResource(R.string.hex), Modifier.weight(1f)) {
            NativeField(
                value = Rgba.toHex(current).removePrefix("#").uppercase(),
                onText = { raw -> if (raw.length == 6) Rgba.fromHex("#$raw")?.let { onColor(it.copy(a = 255)) } },
                modifier = Modifier.fillMaxWidth(),
                maxLen = 6,
                hexOnly = true,
                centered = true,
            )
        }
        ChannelField("R", current.r) { onColor(current.copy(r = it)) }
        ChannelField("G", current.g) { onColor(current.copy(g = it)) }
        ChannelField("B", current.b) { onColor(current.copy(b = it)) }
    }
}

/** The colour the picker opened with beside the one it holds now; a tap on the first goes back to it. */
@Composable
private fun WasNow(was: Rgba?, current: Rgba, onRevert: () -> Unit) {
    val shape = MaterialTheme.shapes.small
    Row(Modifier.size(52.dp, 36.dp).clip(shape).border(1.dp, LocalPalette.current.border.toComposeColor(), shape)) {
        if (was != null) {
            Tooltip(stringResource(R.string.previous_colour), Modifier.weight(1f).fillMaxHeight()) {
                Box(Modifier.fillMaxSize().background(was.toComposeColor()).clickable(role = Role.Button, onClick = onRevert))
            }
        }
        Box(Modifier.weight(1f).fillMaxHeight().background(current.toComposeColor()))
    }
}

@Composable
private fun ChannelField(label: String, value: Int, onChange: (Int) -> Unit) {
    LabelledField(label, Modifier.width(38.dp)) {
        NativeField(
            value = value.toString(),
            onText = { raw -> raw.toIntOrNull()?.let { onChange(it.coerceIn(0, 255)) } },
            modifier = Modifier.fillMaxWidth(),
            numeric = true,
            maxLen = 3,
            centered = true,
        )
    }
}

/** A sunken field under its [label]. */
@Composable
private fun LabelledField(label: String, modifier: Modifier = Modifier, field: @Composable () -> Unit) {
    val palette = LocalPalette.current
    val shape = MaterialTheme.shapes.small
    Column(modifier) {
        Text(label, color = palette.textDim.toComposeColor(), fontSize = 11.sp, maxLines = 1, modifier = Modifier.padding(start = 2.dp, bottom = 4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(36.dp)
                .clip(shape)
                .background(palette.bg.toComposeColor())
                .border(1.dp, palette.border.toComposeColor(), shape),
            contentAlignment = Alignment.Center,
        ) { field() }
    }
}

/**
 * A platform [EditText] (not a Compose `BasicTextField`) for the footer fields. Compose text fields
 * inside a popup window fight the soft keyboard — the keyboard can flash shut and the menu is left
 * shoved out of place. A native view owns a stable input connection and, via [onKeyPreIme], lets us
 * blur it (clearing the caret) when the keyboard is dismissed by the back gesture, which Compose has
 * no clean hook for. [value] drives the field when it isn't focused; edits flow out through [onText].
 */
@Composable
internal fun NativeField(
    value: String,
    onText: (String) -> Unit,
    modifier: Modifier = Modifier,
    numeric: Boolean = false,
    decimal: Boolean = false,
    maxLen: Int = 6,
    hexOnly: Boolean = false,
    endAlign: Boolean = false,
    centered: Boolean = false,
    autoFocus: Boolean = false,
    textSp: Float = 13f,
    onDone: (() -> Unit)? = null,
    onBlur: (() -> Unit)? = null,
) {
    val textColor = LocalPalette.current.text.toArgb()
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PickerEditText(ctx).apply {
                background = null
                setPadding(0, 0, 0, 0)
                minHeight = 0
                minimumHeight = 0
                includeFontPadding = false
                isSingleLine = true
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, textSp)
                inputType = when {
                    decimal -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                    numeric -> InputType.TYPE_CLASS_NUMBER
                    else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                }
                imeOptions = EditorInfo.IME_ACTION_DONE
                gravity = when {
                    centered -> Gravity.CENTER
                    endAlign -> Gravity.END or Gravity.CENTER_VERTICAL
                    else -> Gravity.START or Gravity.CENTER_VERTICAL
                }
                val filterList = mutableListOf<InputFilter>(InputFilter.LengthFilter(maxLen))
                if (hexOnly) {
                    filterList.add(InputFilter { src, start, end, _, _, _ ->
                        val kept = StringBuilder()
                        for (i in start until end) {
                            val c = src[i]
                            if (c.isDigit() || c.lowercaseChar() in 'a'..'f') kept.append(c.uppercaseChar())
                        }
                        if (kept.toString() == src.subSequence(start, end).toString()) null else kept.toString()
                    })
                }
                filters = filterList.toTypedArray()
                onImeBack = { clearFocus() }
                if (onBlur != null) setOnFocusChangeListener { _, has -> if (!has) onBlur() }
                setOnEditorActionListener { _, actionId, _ ->
                    if (actionId == EditorInfo.IME_ACTION_DONE) { onDone?.invoke(); clearFocus(); true } else false
                }
                addTextChangedListener(object : TextWatcher {
                    override fun afterTextChanged(s: Editable?) { if (hasFocus()) onText(s?.toString().orEmpty()) }
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                })
                if (autoFocus) post {
                    requestFocus()
                    selectAll()
                    val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.showSoftInput(this, 0)
                }
            }
        },
        update = { et ->
            et.setTextColor(textColor)
            // Mirror the live colour only while the user isn't typing, so an edit isn't clobbered.
            if (!et.hasFocus() && et.text?.toString() != value) {
                et.setText(value)
                et.setSelection(et.text.length)
            }
        },
    )
}

/** [EditText] that reports a keyboard-dismissing BACK press so the caller can blur it (the platform
 *  otherwise keeps the field focused — and its caret blinking — after the keyboard slides away). */
private class PickerEditText(context: Context) : EditText(context) {
    var onImeBack: (() -> Unit)? = null

    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) onImeBack?.invoke()
        return super.onKeyPreIme(keyCode, event)
    }
}

@Composable
internal fun FieldFrame(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    val palette = LocalPalette.current
    Row(
        modifier
            .clip(MaterialTheme.shapes.extraSmall)
            .background(palette.surface.toComposeColor())
            .border(1.dp, palette.border.toComposeColor(), MaterialTheme.shapes.extraSmall)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}
