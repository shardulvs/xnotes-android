package com.xnotes.core

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Rgba
import com.xnotes.core.pal.FillRule
import com.xnotes.core.pal.FontSpec
import com.xnotes.core.pal.ImageCodec
import com.xnotes.core.pal.Pen
import com.xnotes.core.pal.RasterSurface
import com.xnotes.core.pal.Renderer
import com.xnotes.core.pal.SurfaceFactory
import com.xnotes.core.pal.TextFlags
import com.xnotes.core.pal.TextMeasurer

/** A Renderer that records the primitive calls it receives (for assertions). */
class FakeRenderer : Renderer {
    val ops = mutableListOf<String>()

    override fun save() { ops += "save" }
    override fun restore() { ops += "restore" }
    override fun saveLayerAlpha(bounds: Rect, alpha: Double) { ops += "saveLayerAlpha" }
    override fun translate(dx: Double, dy: Double) { ops += "translate" }
    override fun scale(sx: Double, sy: Double) { ops += "scale" }
    override fun clipRect(rect: Rect) { ops += "clipRect" }
    override fun clear() { ops += "clear" }
    override fun fillBackground(rect: Rect, color: Rgba) { ops += "fillBackground" }
    override fun fillRect(rect: Rect, color: Rgba) { ops += "fillRect" }
    override fun fillPolygon(points: List<Pt>, color: Rgba, rule: FillRule) { ops += "fillPolygon" }
    override fun fillCircle(center: Pt, radius: Double, color: Rgba) { ops += "fillCircle" }
    override fun fillEllipse(center: Pt, rx: Double, ry: Double, color: Rgba) { ops += "fillEllipse" }
    override fun strokeRect(rect: Rect, pen: Pen) { ops += "strokeRect" }
    override fun strokePolyline(points: List<Pt>, pen: Pen) { ops += "strokePolyline" }
    override fun strokePolygon(points: List<Pt>, pen: Pen) { ops += "strokePolygon" }
    override fun strokeEllipse(center: Pt, rx: Double, ry: Double, pen: Pen) { ops += "strokeEllipse" }
    override fun drawRaster(raster: RasterSurface, dest: Rect, src: Rect?) { ops += "drawRaster" }
    override fun drawText(text: String, rect: Rect, font: FontSpec, color: Rgba, flags: TextFlags) { ops += "drawText" }
}

/** A bitmap-less raster surface for tests. */
class FakeRasterSurface(
    override val width: Int,
    override val height: Int,
    override val devicePixelRatio: Double = 1.0,
) : RasterSurface {
    override fun fill(color: Rgba) {}
    override fun renderer(): Renderer = FakeRenderer()
}

class FakeSurfaceFactory : SurfaceFactory {
    override fun create(widthPx: Int, heightPx: Int, devicePixelRatio: Double) =
        FakeRasterSurface(widthPx, heightPx, devicePixelRatio)
}

/** A measurer where each newline-separated line is one [lineHeight] tall. */
class FakeTextMeasurer(private val perPointLineHeight: Double = 1.3) : TextMeasurer {
    override fun measure(text: String, font: FontSpec, wrapWidth: Double, flags: TextFlags): Rect {
        val lines = maxOf(1, text.split('\n').size)
        return Rect(0.0, 0.0, wrapWidth, lines * lineHeight(font))
    }

    override fun lineHeight(font: FontSpec): Double = font.pointSize * perPointLineHeight
}

/** A codec that produces fixed-size surfaces and tiny byte stand-ins. */
class FakeImageCodec : ImageCodec {
    var decodeWidth = 64
    var decodeHeight = 48
    override fun decode(bytes: ByteArray): RasterSurface = FakeRasterSurface(decodeWidth, decodeHeight)
    override fun decodePath(path: String): RasterSurface = FakeRasterSurface(decodeWidth, decodeHeight)
    override fun encodePng(surface: RasterSurface): ByteArray = byteArrayOf(0x89.toByte(), 'P'.code.toByte())
    override fun encodeJpeg(surface: RasterSurface, quality: Double): ByteArray = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
}
