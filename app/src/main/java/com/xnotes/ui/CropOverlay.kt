package com.xnotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.xnotes.R
import com.xnotes.core.geometry.Rect
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** A picture being cropped as it shows on screen: its box's centre and sides in px, and how far it is turned. */
class CropFrame(val centerX: Float, val centerY: Float, val width: Float, val height: Float, val angleDeg: Float)

/** What the crop frame works on: a picture in crop mode, which either canvas can be in. */
interface CropHost {
    /** The picture being cropped, or null outside crop mode. */
    val cropFrame: CropFrame?

    /** End crop mode, keeping the part [part] (fractions of the picture's box) for good, or nothing with null. */
    fun finishCrop(part: Rect?)
}

/** The smallest part a crop keeps, as a fraction of each side. */
private const val MIN_PART = 0.05

/**
 * Crop mode: the whole picture with what will be cut off dimmed, a frame to drag by its edges,
 * corners or middle, turned with the picture, and Done and Cancel under it. A tap off the picture
 * is Done; nothing beneath takes a touch until it ends.
 */
@Composable
fun CropOverlay(host: CropHost) {
    val frame = host.cropFrame ?: return
    val palette = LocalPalette.current
    val density = LocalDensity.current
    var part by remember(frame) { mutableStateOf(Rect.ltrb(0.0, 0.0, 1.0, 1.0)) }
    BoxWithConstraints(
        // One handler for the whole pane, so a side can be taken from just outside the picture too.
        Modifier.fillMaxSize().pointerInput(frame) {
            val grab = 24.dp.toPx()
            awaitEachGesture {
                // Unconsumed only: Done and Cancel take their own touches first.
                val down = awaitFirstDown()
                down.consume()
                val start = frame.toLocal(down.position)
                val from = part
                val sides = grabbedSides(start, from, frame.width, frame.height, grab)
                var moved = false
                while (true) {
                    val e = awaitPointerEvent()
                    e.changes.forEach { it.consume() }
                    val c = e.changes.firstOrNull { it.id == down.id } ?: break
                    if (!c.pressed) break
                    if (!moved && (c.position - down.position).getDistance() < viewConfiguration.touchSlop) continue
                    moved = true
                    val p = frame.toLocal(c.position)
                    if (sides != null) part = dragged(from, sides, (p.x - start.x).toDouble() / frame.width, (p.y - start.y).toDouble() / frame.height)
                }
                val onPicture = start.x in 0f..frame.width && start.y in 0f..frame.height
                if (!moved && !onPicture) host.finishCrop(part)
            }
        },
    ) {
        val roomH = constraints.maxHeight
        Box(
            Modifier
                .offset { IntOffset((frame.centerX - frame.width / 2f).roundToInt(), (frame.centerY - frame.height / 2f).roundToInt()) }
                .size(with(density) { frame.width.toDp() }, with(density) { frame.height.toDp() })
                .graphicsLayer { rotationZ = frame.angleDeg }
                .drawBehind {
                    val l = (part.left * size.width).toFloat()
                    val t = (part.top * size.height).toFloat()
                    val r = (part.right * size.width).toFloat()
                    val b = (part.bottom * size.height).toFloat()
                    val dim = Color.Black.copy(alpha = 0.55f)
                    drawRect(dim, Offset.Zero, Size(size.width, t))
                    drawRect(dim, Offset(0f, b), Size(size.width, size.height - b))
                    drawRect(dim, Offset(0f, t), Size(l, b - t))
                    drawRect(dim, Offset(r, t), Size(size.width - r, b - t))
                    val line = 2.dp.toPx()
                    drawRect(Color.Black.copy(alpha = 0.6f), Offset(l, t), Size(r - l, b - t), style = Stroke(line * 2f))
                    drawRect(Color.White, Offset(l, t), Size(r - l, b - t), style = Stroke(line))
                    val knob = 5.dp.toPx()
                    for ((x, y) in listOf(l to t, r to t, l to b, r to b, (l + r) / 2 to t, (l + r) / 2 to b, l to (t + b) / 2, r to (t + b) / 2)) {
                        drawCircle(Color.Black.copy(alpha = 0.6f), knob + line, Offset(x, y))
                        drawCircle(Color.White, knob, Offset(x, y))
                    }
                },
        )
        // Done and Cancel under the picture's turned box, or over it when there is no room below.
        val rad = Math.toRadians(frame.angleDeg.toDouble())
        val halfH = (abs(frame.width / 2.0 * sin(rad)) + abs(frame.height / 2.0 * cos(rad))).toFloat()
        val gap = with(density) { 12.dp.toPx() }
        var bar by remember { mutableStateOf(IntSize.Zero) }
        val below = frame.centerY + halfH + gap
        val y = if (below + bar.height <= roomH) below else frame.centerY - halfH - gap - bar.height
        Row(
            Modifier
                .offset { IntOffset((frame.centerX - bar.width / 2f).roundToInt(), y.roundToInt().coerceAtLeast(0)) }
                .onSizeChanged { bar = it }
                .clip(MaterialTheme.shapes.medium)
                .background(palette.menuBg.toComposeColor())
                .border(1.dp, palette.border.toComposeColor(), MaterialTheme.shapes.medium),
        ) {
            TextButton(onClick = { host.finishCrop(null) }) { Text(stringResource(R.string.cancel)) }
            TextButton(onClick = { host.finishCrop(part) }) { Text(stringResource(R.string.done)) }
        }
    }
}

/** A point on screen as it lies in the picture's unturned box, in px from that box's top left. */
private fun CropFrame.toLocal(p: Offset): Offset {
    val rad = Math.toRadians(angleDeg.toDouble())
    val c = cos(rad).toFloat()
    val s = sin(rad).toFloat()
    val dx = p.x - centerX
    val dy = p.y - centerY
    return Offset(dx * c + dy * s + width / 2f, -dx * s + dy * c + height / 2f)
}

/**
 * The sides of [part] a touch at [p] in a [w] by [h] picture takes (left, top, right, bottom), all
 * four to move it, or null. A side reaches [grab] outward but at most a third of the part inward,
 * so even a small part keeps a middle to move it by.
 */
private fun grabbedSides(p: Offset, part: Rect, w: Float, h: Float, grab: Float): BooleanArray? {
    val l = (part.left * w).toFloat()
    val t = (part.top * h).toFloat()
    val r = (part.right * w).toFloat()
    val b = (part.bottom * h).toFloat()
    val inX = minOf(grab, (r - l) / 3f)
    val inY = minOf(grab, (b - t) / 3f)
    val alongX = p.x in l - grab..r + grab
    val alongY = p.y in t - grab..b + grab
    val sides = booleanArrayOf(
        alongY && p.x in l - grab..l + inX,
        alongX && p.y in t - grab..t + inY,
        alongY && p.x in r - inX..r + grab,
        alongX && p.y in b - inY..b + grab,
    )
    if (sides.any { it }) return sides
    return if (p.x in l..r && p.y in t..b) booleanArrayOf(true, true, true, true) else null
}

/** [part] with the [sides] a drag holds moved by [dx] and [dy] (fractions of the picture), kept on it and never under [MIN_PART]. */
private fun dragged(part: Rect, sides: BooleanArray, dx: Double, dy: Double): Rect =
    if (sides.all { it }) {
        val mx = dx.coerceIn(-part.left, 1.0 - part.right)
        val my = dy.coerceIn(-part.top, 1.0 - part.bottom)
        Rect.ltrb(part.left + mx, part.top + my, part.right + mx, part.bottom + my)
    } else {
        Rect.ltrb(
            if (sides[0]) (part.left + dx).coerceIn(0.0, part.right - MIN_PART) else part.left,
            if (sides[1]) (part.top + dy).coerceIn(0.0, part.bottom - MIN_PART) else part.top,
            if (sides[2]) (part.right + dx).coerceIn(part.left + MIN_PART, 1.0) else part.right,
            if (sides[3]) (part.bottom + dy).coerceIn(part.top + MIN_PART, 1.0) else part.bottom,
        )
    }
