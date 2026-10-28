package com.xnotes.core.template

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.ImageData
import com.xnotes.core.model.Rgba
import com.xnotes.core.pal.FillRule
import com.xnotes.core.pal.Pen
import com.xnotes.core.pal.Renderer
import kotlin.math.max

/**
 * Paints a [TemplateOutput] through the PAL, in page-local content space (the page's top-left at
 * the origin, [scale] content px per mm). Each colour layer is drawn opaque inside one alpha layer,
 * so crossings never darken and PDF export (which honours a layer's alpha but not a fill's) matches
 * the screen. [region] is the page-local rect being painted; shapes outside it are skipped.
 * [minLine] is the thinnest line in px, so a thumbnail's ruling never fades below a pixel.
 * [picture] finds an image layer's picture by its `src`; without one, images draw nothing.
 */
object TemplatePainter {

    fun paint(r: Renderer, out: TemplateOutput, scale: Double, region: Rect, minLine: Double = 0.0, picture: (String) -> ImageData? = { null }) {
        if (scale <= 0.0) return
        val area = Rect(region.x / scale, region.y / scale, region.w / scale, region.h / scale)
        for (layer in out.layers) {
            layer.image?.let { img ->
                if (img.clip.intersects(area)) drawImage(r, img, layer.color.a, scale, region, picture)
                continue
            }
            var bounds: Rect? = null
            val visible = layer.prims.filter { p ->
                p.bounds.intersects(area).also { if (it) bounds = bounds?.union(p.bounds) ?: p.bounds }
            }
            val b = bounds ?: continue
            val translucent = layer.color.a < 255
            val ink = if (translucent) layer.color.copy(a = 255) else layer.color
            if (translucent) {
                val box = TemplateClip.intersect(Rect(b.x * scale, b.y * scale, b.w * scale, b.h * scale).outset(1.0), region) ?: continue
                r.saveLayerAlpha(box, layer.color.a / 255.0)
            }
            for (p in visible) draw(r, p, ink, scale, minLine)
            if (translucent) r.restore()
        }
    }

    private fun drawImage(r: Renderer, img: TImage, alpha: Int, s: Double, region: Rect, picture: (String) -> ImageData?) {
        val data = picture(img.src) ?: return
        val shown = TemplateClip.intersect(Rect(img.clip.x * s, img.clip.y * s, img.clip.w * s, img.clip.h * s), region) ?: return
        val d = img.dest(data.width.toDouble(), data.height.toDouble())
        r.save()
        r.clipRect(shown)
        if (alpha < 255) r.saveLayerAlpha(shown, alpha / 255.0)
        r.drawImage(data, Rect(d.x * s, d.y * s, d.w * s, d.h * s))
        if (alpha < 255) r.restore()
        r.restore()
    }

    private fun draw(r: Renderer, p: TPrim, ink: Rgba, s: Double, minLine: Double) {
        when (p) {
            is TPrim.Stroke -> {
                val pen = pen(ink, p.width, p.dashOn, p.dashOff, s, minLine)
                val pts = points(p.pts, s)
                if (p.closed) r.strokePolygon(pts, pen) else r.strokePolyline(pts, pen)
            }
            is TPrim.Fill -> r.fillPolygon(points(p.pts, s), ink, FillRule.NONZERO)
            is TPrim.FillRect -> r.fillRect(Rect(p.rect.x * s, p.rect.y * s, p.rect.w * s, p.rect.h * s), ink)
            is TPrim.Ellipse -> {
                val c = Pt(p.cx * s, p.cy * s)
                // A dot thinner than the thinnest line would vanish, so it grows to match it.
                val least = if (p.fill) minLine * 0.75 else 0.0
                val rx = max(p.rx * s, least)
                val ry = max(p.ry * s, least)
                when {
                    !p.fill -> r.strokeEllipse(c, rx, ry, pen(ink, p.width, p.dashOn, p.dashOff, s, minLine))
                    rx == ry -> r.fillCircle(c, rx, ink)
                    else -> r.fillEllipse(c, rx, ry, ink)
                }
            }
        }
    }

    private fun pen(ink: Rgba, width: Double, on: Double, off: Double, s: Double, minLine: Double) = Pen(
        ink, width = if (width > 0.0) max(width * s, minLine) else 0.0, cosmetic = false, dashed = off > 0.0, dashOn = on * s, dashGap = off * s,
    )

    private fun points(pts: DoubleArray, s: Double): List<Pt> {
        val out = ArrayList<Pt>(pts.size / 2)
        var i = 0
        while (i + 1 < pts.size) {
            out.add(Pt(pts[i] * s, pts[i + 1] * s))
            i += 2
        }
        return out
    }
}
