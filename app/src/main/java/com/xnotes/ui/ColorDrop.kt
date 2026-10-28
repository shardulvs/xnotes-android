package com.xnotes.ui

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.drawscope.Stroke as RingStroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.dp
import com.xnotes.core.fill.ColorDrop
import com.xnotes.core.fill.DropAction
import com.xnotes.core.fill.FillGrid
import com.xnotes.core.fill.FillMap
import com.xnotes.core.fill.Probe
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.DrawStyle
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.TextItem
import com.xnotes.core.model.deepCopy
import com.xnotes.core.pal.Renderer
import com.xnotes.core.pal.TextMeasurer
import com.xnotes.platform.AndroidRenderer
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** One drag of a toolbar swatch over the page: where the pointer is, and what a drop there would do. */
@Stable
class ColorDrag internal constructor(val color: Rgba) {
    /** The pointer, in window pixels. */
    var pointer by mutableStateOf(Offset.Unspecified)
        internal set

    /** What a drop at the pointer would do, drawn under the disc; null for nothing. */
    var preview by mutableStateOf<DropPreview?>(null)
        internal set
}

/** What a drop at the pointer would do, ready to draw over the page (or canvas) [frame] it lands on. */
sealed interface DropPreview {
    val frame: Int

    /** [item] in the dropped colour, drawn over the line it recolours. */
    class Line(override val frame: Int, val item: CanvasItem) : DropPreview

    /** The area that would fill, as an alpha [mask] laid over [rect] in the frame's own space. */
    class Area(override val frame: Int, val mask: Bitmap, val rect: Rect) : DropPreview
}

/** The editor end of a swatch's colour drag; both editors are one, so both toolbars drag alike. */
interface ColorDropHost {
    /** The drag in flight, or null. */
    val colorDrag: ColorDrag?

    fun beginColorDrag(color: Rgba, window: Offset)
    fun moveColorDrag(window: Offset)
    fun endColorDrag(window: Offset)
    fun cancelColorDrag()

    /** The canvas view's bounds, in window pixels. */
    fun canvasInWindow(): androidx.compose.ui.geometry.Rect

    /** Move [r], standing at the canvas view's top left in its pixels, into frame [frame]'s own space. */
    fun enterDropFrame(r: Renderer, frame: Int)
}

/** A point a colour drag is over: the frame it is in, where in it, and what decides a drop there. */
internal class DropSpot(
    val frame: Int,
    /** The map built for this frame serves every spot with an equal key. */
    val key: Any,
    val at: Pt,
    /** The frame's items, in z-order. */
    val items: List<CanvasItem>,
    val grid: FillGrid,
    /** How near a line a drop counts as on it, in content px. */
    val reach: Double,
    /** Closed bits up to this many cells across fill along with the area round them. */
    val smallHole: Int,
)

/** Where a colour drag lands on one editor's canvas, and what dropping there does. */
internal interface DropSurface {
    /** Get ready for a drag: nothing selected, nothing being typed. */
    fun beginDrop()

    /** The spot under view point [view] (canvas view pixels), or null when off every page. */
    fun spotAt(view: Pt): DropSpot?

    /** Carry out [action] at [spot] in [color], as one undo step, or say why nothing happened. */
    fun applyDrop(spot: DropSpot, action: DropAction, color: Rgba)

    /** The canvas view's top left, in window pixels. */
    fun viewInWindow(): Offset

    /** Copies text boxes for a preview. */
    val measurer: TextMeasurer
}

/**
 * Runs a swatch's colour drag for one editor. It keeps the drag's state for the overlay, works out
 * what a drop would do on a worker as the pointer moves (only ever the newest point), and carries
 * out the drop. The line map of a page or canvas window is built once a drag and reused for every
 * point over it; the area under the pointer is reused for as long as the pointer stays in it.
 */
internal class ColorDropper(private val surface: DropSurface) {

    var drag by mutableStateOf<ColorDrag?>(null)
        private set

    private val worker by lazy { Executors.newSingleThreadExecutor { r -> Thread(r, "xnotes-colour-drop").apply { isDaemon = true } } }
    private val main = Handler(Looper.getMainLooper())
    private val pending = AtomicReference<Ask?>(null)

    @Volatile
    private var generation = 0

    // The worker's own, never touched elsewhere.
    private var maps = HashMap<Any, FillMap>()
    private var mapsFor = -1
    private var lastArea: Probe.Enclosed? = null
    private var lastKey: Any? = null
    private var lastPreview: DropPreview? = null

    private class Ask(val spot: DropSpot?, val drop: Boolean, val generation: Int, val color: Rgba)

    fun begin(color: Rgba, window: Offset) {
        surface.beginDrop()
        generation++
        shownLine = null
        shownCopy = null
        drag = ColorDrag(color).also { it.pointer = window }
        ask(window, color, drop = false)
    }

    fun move(window: Offset) {
        val d = drag ?: return
        d.pointer = window
        ask(window, d.color, drop = false)
    }

    fun end(window: Offset) {
        val d = drag ?: return
        // The disc goes at once; the drop lands once the worker has worked it out.
        drag = null
        ask(window, d.color, drop = true)
    }

    fun cancel() {
        drag = null
        generation++
    }

    private fun ask(window: Offset, color: Rgba, drop: Boolean) {
        val origin = surface.viewInWindow()
        val spot = surface.spotAt(Pt((window.x - origin.x).toDouble(), (window.y - origin.y).toDouble()))
        pending.set(Ask(spot, drop, generation, color))
        worker.execute(::work)
    }

    private fun work() {
        val ask = pending.getAndSet(null) ?: return
        if (ask.generation != generation) return
        if (mapsFor != ask.generation) {
            maps = HashMap()
            mapsFor = ask.generation
            lastArea = null
            lastPreview = null
        }
        val spot = ask.spot
        if (spot == null) {
            if (!ask.drop) post(ask.generation) { drag?.preview = null }
            return
        }
        val map = maps[spot.key]
            ?: ColorDrop.map(spot.items, spot.grid) { generation != ask.generation }?.also { maps[spot.key] = it }
            ?: return
        if (ask.drop) {
            val action = ColorDrop.decide(spot.items, map, spot.at, ask.color, spot.reach, spot.smallHole)
            post(ask.generation) { surface.applyDrop(spot, action, ask.color) }
            return
        }
        val preview = previewAt(spot, map)
        post(ask.generation) { show(preview, ask.color) }
    }

    // The main thread's own: the line last shown recoloured, and its recoloured copy.
    private var shownLine: CanvasItem? = null
    private var shownCopy: DropPreview.Line? = null

    /** Show [preview], a line's in a recoloured copy made here, on the main thread, and kept while the pointer stays on it. */
    private fun show(preview: DropPreview?, color: Rgba) {
        val d = drag ?: return
        if (preview !is DropPreview.Line) {
            d.preview = preview
            return
        }
        if (shownLine !== preview.item || shownCopy?.frame != preview.frame) {
            shownLine = preview.item
            shownCopy = DropPreview.Line(preview.frame, preview.item.deepCopy(surface.measurer).also { recolour(it, color) })
        }
        d.preview = shownCopy
    }

    /** What a drop at [spot] would do: recolour the line it lands on, fill the area round it, or nothing. */
    private fun previewAt(spot: DropSpot, map: FillMap): DropPreview? {
        ColorDrop.lineAt(spot.items, spot.at, spot.reach)?.let { return DropPreview.Line(spot.frame, it) }
        val last = lastArea
        if (last != null && lastKey == spot.key && last.holds(map.cellX(spot.at), map.cellY(spot.at))) return lastPreview
        val area = map.probe(spot.at) as? Probe.Enclosed
        val preview = area?.let { mask(spot.frame, map.grid, it) }
        lastArea = area
        lastKey = spot.key
        lastPreview = preview
        return preview
    }

    /** [area]'s cells as an alpha mask, thinned out on a big area so a preview never costs much. */
    private fun mask(frame: Int, grid: FillGrid, area: Probe.Enclosed): DropPreview.Area {
        val step = max(1, ceil(sqrt(area.w.toDouble() * area.h / PREVIEW_CELLS)).toInt())
        val bw = (area.w + step - 1) / step
        val bh = (area.h + step - 1) / step
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ALPHA_8)
        val stride = bmp.rowBytes
        val px = ByteArray(stride * bh)
        for (y in 0 until bh) {
            val cy = min(area.h - 1, y * step + step / 2)
            for (x in 0 until bw) {
                val cx = min(area.w - 1, x * step + step / 2)
                if (area.cells[cy * area.w + cx].toInt() and FillMap.MARK != 0) px[y * stride + x] = 0xFF.toByte()
            }
        }
        bmp.copyPixelsFromBuffer(ByteBuffer.wrap(px))
        val tl = grid.toContent(area.x0.toDouble(), area.y0.toDouble())
        val br = grid.toContent((area.x0 + bw * step).toDouble(), (area.y0 + bh * step).toDouble())
        return DropPreview.Area(frame, bmp, Rect.fromPoints(tl, br))
    }

    private fun post(forGeneration: Int, block: () -> Unit) {
        main.post { if (forGeneration == generation) block() }
    }

    private companion object {
        /** Most cells an area preview's mask holds. */
        const val PREVIEW_CELLS = 600_000.0

        fun recolour(item: CanvasItem, color: Rgba) {
            if (item is TextItem) item.rgba = color else DrawStyle.of(item)?.let { DrawStyle(color, it.width).applyTo(item) }
        }
    }
}

/** The disc a swatch drag carries and, under it, what a drop there would do; drawn over [host]'s pane. */
@Composable
fun ColorDropOverlay(host: ColorDropHost) {
    val drag = host.colorDrag ?: return
    val ring = LocalPalette.current.text
    val origin = remember { arrayOf(Offset.Zero) }
    val tint = remember { Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG) }
    Canvas(Modifier.fillMaxSize().onGloballyPositioned { origin[0] = it.positionInWindow() }) {
        val canvas = host.canvasInWindow().translate(-origin[0])
        drag.preview?.let { p ->
            clipRect(canvas.left, canvas.top, canvas.right, canvas.bottom) {
                drawIntoCanvas { c ->
                    val r = AndroidRenderer(c.nativeCanvas)
                    r.save()
                    r.translate(canvas.left.toDouble(), canvas.top.toDouble())
                    host.enterDropFrame(r, p.frame)
                    when (p) {
                        is DropPreview.Line -> p.item.paint(r)
                        is DropPreview.Area -> {
                            tint.color = drag.color.withAlpha(PREVIEW_ALPHA).toArgb()
                            val d = p.rect
                            c.nativeCanvas.drawBitmap(p.mask, null, RectF(d.left.toFloat(), d.top.toFloat(), d.right.toFloat(), d.bottom.toFloat()), tint)
                        }
                    }
                    r.restore()
                }
            }
        }
        val at = drag.pointer
        if (at.isSpecified) {
            val local = at - origin[0]
            val radius = DISC_RADIUS.dp.toPx()
            drawCircle(drag.color.toComposeColor(), radius, local)
            drawCircle(ring.toComposeColor(), radius, local, style = RingStroke(1.5.dp.toPx()))
        }
    }
}

/**
 * Lets a toolbar swatch be pulled off the bar as a colour drag for [host]: a pull across the bar
 * (down off a top bar, sideways off a rail) starts one, while one along it is left to scroll the
 * bar and a tap still picks the colour.
 */
internal fun Modifier.colorDragSource(host: ColorDropHost, color: Rgba, vertical: Boolean): Modifier {
    val where = arrayOfNulls<LayoutCoordinates>(1)
    return this
        .onGloballyPositioned { where[0] = it }
        .pointerInput(host, color, vertical) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val slop = viewConfiguration.touchSlop
                var moved = Offset.Zero
                var dragging = false
                var dropped = false
                fun window(p: Offset): Offset = where[0]?.takeIf { it.isAttached }?.localToWindow(p) ?: p
                try {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            if (dragging) {
                                change.consume()
                                host.endColorDrag(window(change.position))
                                dropped = true
                            }
                            break
                        }
                        if (!dragging) {
                            moved += change.positionChange()
                            val across = abs(if (vertical) moved.x else moved.y)
                            val along = abs(if (vertical) moved.y else moved.x)
                            if (along > slop && along >= across) break
                            if (across <= slop) continue
                            dragging = true
                            host.beginColorDrag(color, window(change.position))
                        } else {
                            host.moveColorDrag(window(change.position))
                        }
                        change.consume()
                    }
                } finally {
                    if (dragging && !dropped) host.cancelColorDrag()
                }
            }
        }
}

/** Cells to a content px that a note page's areas are found at. */
internal const val DROP_CELLS_PER_PX = 2.0

/** Most cells a drop's window may hold, which bounds what finding an area costs. */
internal const val DROP_CELL_BUDGET = 9_000_000L

/** How near a line, in dp on screen, a drop counts as landing on it. */
internal const val DROP_REACH_DP = 4.0

/** A closed bit up to this many mm across on screen fills along with the area round it. */
internal const val DROP_SMALL_HOLE_MM = 6.0

/** How far past the screen, as a share of its longer side, a canvas drop looks for an area's edge. */
internal const val DROP_MARGIN = 0.25

/** Radius of the disc a colour drag carries, in dp. */
private const val DISC_RADIUS = 14

/** How strongly the area a drop would fill is tinted in the preview, out of 255. */
private const val PREVIEW_ALPHA = 140
