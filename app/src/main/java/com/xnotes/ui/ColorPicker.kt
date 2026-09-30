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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.xnotes.R
import com.xnotes.core.model.Rgba
import com.xnotes.ui.theme.ColorMath
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.toComposeColor
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

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

private const val CELL = 18 // swatch cell size, dp
private const val GRID_W = 282 // popup width: 13 cells + 12 gaps + horizontal padding, dp

/**
 * The shared colour picker (spec 10 §4). Two tabs over one live colour:
 *  - **Swatches**: recent colours plus the [SWATCH_HUES]×[SWATCH_SHADES] matrix and a greyscale row.
 *  - **Spectrum**: a hue ring with an inner saturation/value square, drag to pick.
 * A footer under both tabs always shows the current colour with editable HEX and R/G/B fields.
 *
 * Colours are opaque (the picker never sets alpha; tools that need translucency, e.g. the
 * highlighter, own their own opacity control). [onPick] fires on every *commit* — a swatch tap, a
 * spectrum release, or a field edit — never on each drag sample, so it won't flood undo history or
 * re-rasterise a page background mid-drag. [recents] may be empty (the page/pattern picker keeps no
 * recents). The popup stays open across picks and closes only via [onDismiss] (a tap outside).
 */
@Composable
internal fun ColorPickerPopup(
    initial: Rgba?,
    recents: List<Rgba>,
    onDismiss: () -> Unit,
    onPick: (Rgba) -> Unit,
) {
    val palette = LocalPalette.current
    var current by remember { mutableStateOf(initial?.copy(a = 255) ?: Rgba(255, 255, 255)) }
    // Hue and saturation are kept explicitly, because a colour does not always carry them: black has
    // no defined hue *or* saturation, and any grey has no hue. Re-deriving them from [current] would
    // send the markers home the moment a drag reached the square's bottom edge or the ring a grey.
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

    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        Column(Modifier.width(GRID_W.dp).padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                TabChip(stringResource(R.string.swatches), tab == 0) { tab = 0 }
                TabChip(stringResource(R.string.spectrum), tab == 1) { tab = 1 }
                Spacer(Modifier.weight(1f))
                // Close the picker and pick the colour off the screen instead; it lands like a pick here.
                Icon(
                    XnotesIcons.eyedropper,
                    contentDescription = stringResource(R.string.eyedropper),
                    tint = palette.text.toComposeColor(),
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                            Eyedropper.target = onPick
                            onDismiss()
                        }
                        .padding(4.dp)
                        .size(20.dp),
                )
            }
            Spacer(Modifier.size(12.dp))
            when (tab) {
                0 -> SwatchesTab(recents, current) { commit(it) }
                else -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    SpectrumWheel(
                        hue = hue,
                        sat = sat,
                        value = value,
                        onPreview = { h, s, v ->
                            hue = h
                            sat = s
                            value = v
                            current = ColorMath.hsvToRgb(h, s, v)
                        },
                        onCommit = { onPick(current) },
                        modifier = Modifier.size(210.dp),
                    )
                }
            }
            Spacer(Modifier.size(12.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(palette.border.toComposeColor()))
            Spacer(Modifier.size(10.dp))
            ColorFooter(current) { commit(it) }
        }
    }
}

@Composable
private fun SwatchesTab(recents: List<Rgba>, current: Rgba, onPick: (Rgba) -> Unit) {
    // The full-width rows give every cell an equal `weight` so 13 columns divide the row exactly —
    // a fixed cell size would accumulate dp→px rounding and clip the rightmost column.
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (recents.isNotEmpty()) {
            Caption(stringResource(R.string.caption_recent))
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                recents.take(13).forEach { c -> SwatchCell(c, c == current, Modifier.size(CELL.dp)) { onPick(c) } }
            }
            Spacer(Modifier.size(6.dp))
        }
        SWATCH_SHADES.forEach { (s, v) ->
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                SWATCH_HUES.forEach { h ->
                    val c = ColorMath.hsvToRgb(h, s, v)
                    SwatchCell(c, c == current, Modifier.weight(1f).aspectRatio(1f)) { onPick(c) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            (0..12).forEach { i ->
                val g = ((1.0 - i / 12.0) * 255).roundToInt()
                val c = Rgba(g, g, g)
                SwatchCell(c, c == current, Modifier.weight(1f).aspectRatio(1f)) { onPick(c) }
            }
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

// --- Spectrum: hue ring + inner saturation/value square ----------------------------------------

private const val RING_FRAC = 0.13f // ring thickness as a fraction of the wheel diameter
private const val SQUARE_FRAC = 0.94f // inset of the SV square inside the ring's inner circle

/**
 * The wheel reports edits through [onPreview] (live, applied on every touch sample) and [onCommit]
 * (once, on release) so the caller can paint a smooth preview without committing each sample. It is
 * driven by [hue]/[sat]/[value] from above; touching the ring changes hue, touching the square
 * changes saturation/value.
 */
@Composable
private fun SpectrumWheel(
    hue: Double,
    sat: Double,
    value: Double,
    onPreview: (Double, Double, Double) -> Unit,
    onCommit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val border = LocalPalette.current.border.toComposeColor()
    // The gesture coroutine outlives recompositions, so read the latest inputs/callbacks via refs.
    val hsv = rememberUpdatedState(Triple(hue, sat, value))
    val preview = rememberUpdatedState(onPreview)
    val commit = rememberUpdatedState(onCommit)
    Canvas(
        modifier.pointerInput(Unit) {
            fun emit(pos: Offset, onRing: Boolean) {
                val (h, s, v) = hsv.value
                val r = pickFromTouch(pos.x, pos.y, size.width.toFloat(), onRing, h, s, v)
                preview.value(r[0], r[1], r[2])
            }
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                // The target is latched at touch-down so a ring drag that wanders inward keeps
                // editing hue instead of jumping to the square.
                val onRing = hitsRing(down.position.x, down.position.y, size.width.toFloat())
                emit(down.position, onRing)
                down.consume()
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) { change.consume(); break }
                    emit(change.position, onRing)
                    change.consume()
                }
                commit.value()
            }
        },
    ) {
        val (h, s, v) = hsv.value
        drawWheel(h, s, v, border)
    }
}

/** True when a touch belongs to the hue ring. Only the ring band itself counts; the corner gaps
 *  between the square and the ring's inner circle fall through to the square. */
private fun hitsRing(x: Float, y: Float, s: Float): Boolean {
    val c = s / 2f
    val innerR = s / 2f - s * RING_FRAC
    val dx = x - c
    val dy = y - c
    return dx * dx + dy * dy >= innerR * innerR
}

/** Map a touch point to (hue, saturation, value). [onRing] picks hue from the angle, otherwise the
 *  point is clamped into the S/V square. Coordinates that don't change keep [hue]/[sat]/[value]. */
private fun pickFromTouch(x: Float, y: Float, s: Float, onRing: Boolean, hue: Double, sat: Double, value: Double): DoubleArray {
    val c = s / 2f
    val innerR = s / 2f - s * RING_FRAC
    val side = innerR * 1.41421f * SQUARE_FRAC
    val left = c - side / 2f
    val top = c - side / 2f
    return if (onRing) {
        var deg = atan2((y - c).toDouble(), (x - c).toDouble()) * 180.0 / Math.PI
        if (deg < 0) deg += 360.0
        doubleArrayOf(deg, sat, value)
    } else {
        val ss = ((x - left) / side).coerceIn(0f, 1f).toDouble()
        val vv = (1f - (y - top) / side).coerceIn(0f, 1f).toDouble()
        doubleArrayOf(hue, ss, vv)
    }
}

private fun DrawScope.drawWheel(hue: Double, sat: Double, value: Double, border: Color) {
    val s = size.minDimension
    val c = s / 2f
    val center = Offset(c, c)
    val ring = s * RING_FRAC
    val innerR = s / 2f - ring
    val rMid = innerR + ring / 2f
    val side = innerR * 1.41421f * SQUARE_FRAC
    val left = c - side / 2f
    val top = c - side / 2f

    // Hue ring (a thick sweep-gradient circle starting at red on the +x axis, clockwise).
    val wheelHues = (0..12).map { ColorMath.hsvToRgb(it * 30.0, 1.0, 1.0).toComposeColor() }
    drawCircle(Brush.sweepGradient(wheelHues, center), radius = rMid, center = center, style = Stroke(width = ring))

    // SV square: white → pure hue across, transparent → black down.
    val hueColor = ColorMath.hsvToRgb(hue, 1.0, 1.0).toComposeColor()
    val tl = Offset(left, top)
    val sz = Size(side, side)
    drawRect(Brush.horizontalGradient(listOf(Color.White, hueColor), startX = left, endX = left + side), topLeft = tl, size = sz)
    drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black), startY = top, endY = top + side), topLeft = tl, size = sz)
    drawRect(border, topLeft = tl, size = sz, style = Stroke(1.dp.toPx()))

    // Hue marker (a ring riding the colour band).
    val hr = hue * Math.PI / 180.0
    val hm = Offset(c + (rMid * cos(hr)).toFloat(), c + (rMid * sin(hr)).toFloat())
    drawCircle(Color.Black, radius = ring * 0.46f + 1.dp.toPx(), center = hm, style = Stroke(1.dp.toPx()))
    drawCircle(Color.White, radius = ring * 0.46f, center = hm, style = Stroke(2.dp.toPx()))

    // Saturation/value marker.
    val mx = left + sat.toFloat() * side
    val my = top + (1f - value.toFloat()) * side
    val mr = 6.dp.toPx()
    drawCircle(Color.Black, radius = mr + 1.dp.toPx(), center = Offset(mx, my), style = Stroke(1.dp.toPx()))
    drawCircle(Color.White, radius = mr, center = Offset(mx, my), style = Stroke(2.dp.toPx()))
}

// --- Footer: current swatch + HEX / R,G,B fields, shared by both tabs ---------------------------

@Composable
private fun ColorFooter(current: Rgba, onColor: (Rgba) -> Unit) {
    val palette = LocalPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier
                    .size(30.dp)
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(current.toComposeColor())
                    .border(1.dp, palette.border.toComposeColor(), MaterialTheme.shapes.extraSmall),
            )
            HexField(current, onColor, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ChannelField("R", current.r, Modifier.weight(1f)) { onColor(current.copy(r = it)) }
            ChannelField("G", current.g, Modifier.weight(1f)) { onColor(current.copy(g = it)) }
            ChannelField("B", current.b, Modifier.weight(1f)) { onColor(current.copy(b = it)) }
        }
    }
}

@Composable
private fun HexField(current: Rgba, onColor: (Rgba) -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    FieldFrame(modifier) {
        Text("#", color = palette.textDim.toComposeColor(), fontSize = 13.sp)
        NativeField(
            value = Rgba.toHex(current).removePrefix("#").uppercase(),
            onText = { raw -> if (raw.length == 6) Rgba.fromHex("#$raw")?.let { onColor(it.copy(a = 255)) } },
            modifier = Modifier.weight(1f).padding(start = 4.dp),
            maxLen = 6,
            hexOnly = true,
        )
    }
}

@Composable
private fun ChannelField(label: String, value: Int, modifier: Modifier = Modifier, onChange: (Int) -> Unit) {
    val palette = LocalPalette.current
    FieldFrame(modifier) {
        Text(label, color = palette.textDim.toComposeColor(), fontSize = 13.sp)
        NativeField(
            value = value.toString(),
            onText = { raw -> raw.toIntOrNull()?.let { onChange(it.coerceIn(0, 255)) } },
            modifier = Modifier.weight(1f).padding(start = 6.dp),
            numeric = true,
            maxLen = 3,
            endAlign = true,
        )
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
    maxLen: Int = 6,
    hexOnly: Boolean = false,
    endAlign: Boolean = false,
    autoFocus: Boolean = false,
    onDone: (() -> Unit)? = null,
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
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
                inputType = if (numeric) {
                    InputType.TYPE_CLASS_NUMBER
                } else {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                }
                imeOptions = EditorInfo.IME_ACTION_DONE
                gravity = (if (endAlign) Gravity.END else Gravity.START) or Gravity.CENTER_VERTICAL
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
                et.setSelection(value.length)
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

@Composable
private fun TabChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Box(
        Modifier
            .clip(MaterialTheme.shapes.extraSmall)
            .background(if (selected) palette.selectionBackground.toComposeColor() else palette.surface.toComposeColor())
            .border(1.dp, if (selected) palette.accent.toComposeColor() else palette.border.toComposeColor(), MaterialTheme.shapes.extraSmall)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(
            label,
            color = if (selected) palette.selectionForeground.toComposeColor() else palette.text.toComposeColor(),
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun Caption(text: String) {
    Text(
        text,
        color = LocalPalette.current.textDim.toComposeColor(),
        fontSize = 12.sp,
        modifier = Modifier.padding(vertical = 2.dp),
    )
}
