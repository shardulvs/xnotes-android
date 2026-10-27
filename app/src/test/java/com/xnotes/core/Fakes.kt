package com.xnotes.core

import com.xnotes.core.geometry.Flip
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.ImageData
import com.xnotes.core.model.Rgba
import com.xnotes.core.pal.FillRule
import com.xnotes.core.pal.FontSpec
import com.xnotes.core.pal.ImageCodec
import com.xnotes.core.pal.ImageSize
import com.xnotes.core.pal.LineMetrics
import com.xnotes.core.pal.MathBox
import com.xnotes.core.pal.MathSize
import com.xnotes.core.pal.MathTypesetter
import com.xnotes.core.pal.Pen
import com.xnotes.core.pal.RasterSurface
import com.xnotes.core.pal.Renderer
import com.xnotes.core.pal.SurfaceFactory
import com.xnotes.core.pal.TextFlags
import com.xnotes.core.pal.TextMeasurer
import com.xnotes.core.vector.VectorContour
import com.xnotes.core.vector.VectorSeg

/** A Renderer that records the primitive calls it receives (for assertions). */
class FakeRenderer : Renderer {
    val ops = mutableListOf<String>()

    /** Ribbon runs as `from to count`, so a test can see which points a pass actually painted. */
    val ribbonRuns = mutableListOf<Pair<Int, Int>>()

    /** Where each [drawImage] landed, and each [clipRect], in call order. */
    val images = mutableListOf<Rect>()
    val clips = mutableListOf<Rect>()

    /** Dashed runs as `from to count` paired with the phase they started at. */
    val dashRuns = mutableListOf<Triple<Int, Int, Double>>()

    /** Page-space rects a raster was blitted into. */
    val rasterDests = mutableListOf<Rect>()

    /** Stroked polyline segments, as start/end pairs (the page ruling draws through these). */
    val segments = mutableListOf<Pair<Pt, Pt>>()

    /** Centres of filled circles (the dotted ruling). */
    val circles = mutableListOf<Pt>()

    /** Colours [fillPath] filled with and pens [strokePath] stroked with, in call order. */
    val pathFills = mutableListOf<Rgba>()
    val pathPens = mutableListOf<Pen>()

    override fun fillDiskRibbon(centers: FloatArray, radii: FloatArray, from: Int, count: Int, color: Rgba) {
        ribbonRuns += from to count
        super.fillDiskRibbon(centers, radii, from, count, color)
    }

    override fun strokePolyline(pts: FloatArray, from: Int, count: Int, pen: Pen) {
        dashRuns += Triple(from, count, pen.dashPhase)
        super.strokePolyline(pts, from, count, pen)
    }

    override fun save() { ops += "save" }
    override fun restore() { ops += "restore" }
    override fun saveLayerAlpha(bounds: Rect, alpha: Double) { ops += "saveLayerAlpha" }
    override fun translate(dx: Double, dy: Double) { ops += "translate" }
    override fun scale(sx: Double, sy: Double) { ops += "scale" }
    override fun clipRect(rect: Rect) { ops += "clipRect"; clips += rect }
    override fun clear() { ops += "clear" }
    override fun fillBackground(rect: Rect, color: Rgba) { ops += "fillBackground" }
    override fun fillRect(rect: Rect, color: Rgba) { ops += "fillRect" }
    override fun fillPolygon(points: List<Pt>, color: Rgba, rule: FillRule) { ops += "fillPolygon" }
    override fun fillCircle(center: Pt, radius: Double, color: Rgba) {
        ops += "fillCircle"
        circles += center
    }
    override fun fillEllipse(center: Pt, rx: Double, ry: Double, color: Rgba) { ops += "fillEllipse" }
    override fun strokeRect(rect: Rect, pen: Pen) { ops += "strokeRect" }
    override fun strokePolyline(points: List<Pt>, pen: Pen) {
        ops += "strokePolyline"
        for (i in 0 until points.size - 1) segments += points[i] to points[i + 1]
    }
    override fun strokePolygon(points: List<Pt>, pen: Pen) { ops += "strokePolygon" }
    override fun strokeEllipse(center: Pt, rx: Double, ry: Double, pen: Pen) { ops += "strokeEllipse" }
    override fun fillPath(contours: List<VectorContour>, color: Rgba, rule: FillRule) {
        ops += "fillPath"
        pathFills += color
    }
    override fun strokePath(contours: List<VectorContour>, pen: Pen) {
        ops += "strokePath"
        pathPens += pen
    }
    override fun drawRaster(raster: RasterSurface, dest: Rect, src: Rect?) {
        ops += "drawRaster"
        rasterDests += dest
    }
    override fun drawImage(image: ImageData, dest: Rect, orientation: Int, angle: Double, flip: Flip) { ops += "drawImage"; images += dest }
    override fun drawText(text: String, rect: Rect, font: FontSpec, color: Rgba, flags: TextFlags) { ops += "drawText" }
    override fun drawTextTurned(text: String, rect: Rect, font: FontSpec, color: Rgba, flags: TextFlags, angle: Double) {
        ops += "drawTextTurned:$text@$angle"
        textRects += rect
    }

    /** Where every turned text was set, centred on the point its angle turns it about. */
    val textRects = mutableListOf<Rect>()
    override fun drawTextRun(text: String, x: Double, baseline: Double, font: FontSpec, color: Rgba) {
        ops += "drawTextRun:$text@$x,$baseline"
    }
    override fun drawMath(
        latex: String,
        x: Double,
        baseline: Double,
        sizePt: Double,
        color: Rgba,
        display: Boolean,
    ): Boolean {
        ops += "drawMath${if (display) "Display" else ""}:$latex@$x,$baseline"
        return mathReady
    }

    /** What [drawMath] answers: false plays a backend still setting the formula. */
    var mathReady = true

    override fun drawMathPending(latex: String, sizePt: Double, display: Boolean, box: Rect, color: Rgba) {
        ops += "mathPending:$latex@${box.left},${box.top},${box.w},${box.h}"
    }
}

/** A bitmap-less raster surface for tests. */
class FakeRasterSurface(
    override val width: Int,
    override val height: Int,
    override val devicePixelRatio: Double = 1.0,
) : RasterSurface {
    /** One painter for the surface's life, so a test can read everything ever drawn into it. */
    val painter = FakeRenderer()
    var fills = 0
    var recycled = false

    override fun fill(color: Rgba) { fills++ }
    override fun renderer(): Renderer = painter
    override fun recycle() { recycled = true }
}

class FakeSurfaceFactory : SurfaceFactory {
    val created = mutableListOf<FakeRasterSurface>()

    override fun create(widthPx: Int, heightPx: Int, devicePixelRatio: Double) =
        FakeRasterSurface(widthPx, heightPx, devicePixelRatio).also { created += it }
}

/**
 * A measurer where each newline-separated line is one [lineHeight] tall and every
 * character advances a fixed 0.6pt, so layout tests are exact: ascent (1.0pt) +
 * descent (0.3pt) always equals [lineHeight] (1.3pt), matching the Android impl's
 * invariant.
 */
class FakeTextMeasurer(private val perPointLineHeight: Double = 1.3) : TextMeasurer {
    override fun measure(text: String, font: FontSpec, wrapWidth: Double, flags: TextFlags): Rect {
        val lines = maxOf(1, text.split('\n').size)
        return Rect(0.0, 0.0, wrapWidth, lines * lineHeight(font))
    }

    override fun lineHeight(font: FontSpec): Double = font.pointSize * perPointLineHeight

    override fun metrics(font: FontSpec): LineMetrics =
        LineMetrics(ascent = font.pointSize * (perPointLineHeight - 0.3), descent = font.pointSize * 0.3)

    override fun advances(text: String, font: FontSpec): DoubleArray =
        DoubleArray(text.length) { font.pointSize * ADVANCE_PER_POINT }

    /** Each visible character a square one advance wide, in rows of [lineHeight], from the block's corner. */
    override fun outline(text: String, font: FontSpec, wrapWidth: Double, flags: TextFlags): List<VectorContour> {
        val adv = font.pointSize * ADVANCE_PER_POINT
        val out = ArrayList<VectorContour>()
        for ((row, line) in text.split('\n').withIndex()) {
            val top = row * lineHeight(font)
            for ((col, ch) in line.withIndex()) {
                if (ch.isWhitespace()) continue
                val x = col * adv
                out += VectorContour(
                    Pt(x, top),
                    listOf(VectorSeg.Line(Pt(x + adv, top)), VectorSeg.Line(Pt(x + adv, top + adv)), VectorSeg.Line(Pt(x, top + adv))),
                    closed = true,
                )
            }
        }
        return out
    }

    companion object {
        const val ADVANCE_PER_POINT = 0.6
    }
}

/**
 * A typesetter whose formulas set [scale] times as wide as the same characters
 * would as text and [tall] times as high, so a test can tell a drawn box from
 * the source behind it. [rejects] refuses those strings, the way a real one
 * refuses LaTeX it cannot parse.
 */
class FakeMathTypesetter(
    private val scale: Double = 2.0,
    private val tall: Double = 3.0,
    private val rejects: Set<String> = emptySet(),
    private val ready: Boolean = true,
) : MathTypesetter {
    override fun ready(): Boolean = ready

    override fun measure(latex: String, sizePt: Double, display: Boolean): MathBox? {
        if (latex.isEmpty() || latex in rejects) return null
        // Display form sets half again as large, so a test can see which it got.
        val grow = if (display) 1.5 else 1.0
        return MathBox(
            width = latex.length * sizePt * FakeTextMeasurer.ADVANCE_PER_POINT * scale * grow,
            ascent = sizePt * tall * grow,
            descent = sizePt * 0.3,
        )
    }
}

/**
 * A typesetter that sets off the caller's thread: every formula is pending until [land] hands
 * its box in, null for one it refuses. It counts the layout passes and what each one asked.
 */
class FakeAsyncTypesetter : MathTypesetter {
    private val landed = HashMap<String, MathBox?>()
    val asked = ArrayList<String>()
    var passes = 0

    fun land(latex: String, box: MathBox?) {
        landed[latex] = box
    }

    override fun measure(latex: String, sizePt: Double, display: Boolean): MathBox? = landed[latex]

    override fun size(latex: String, sizePt: Double, display: Boolean): MathSize {
        asked += latex
        if (!landed.containsKey(latex)) return MathSize.Pending
        return landed[latex] ?: MathSize.Refused
    }

    override fun beginLayout() {
        passes++
    }
}

/** A codec that reports a fixed probe size. */
class FakeImageCodec : ImageCodec {
    var probeWidth = 64
    var probeHeight = 48
    override fun probeFile(path: String): ImageSize = ImageSize(probeWidth, probeHeight)
}
