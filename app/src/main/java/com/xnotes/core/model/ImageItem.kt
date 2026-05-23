package com.xnotes.core.model

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.pal.RasterSurface
import com.xnotes.core.pal.Renderer

/** A pasted or inserted bitmap (spec 02 §5.2). Resize is aspect-locked. */
class ImageItem(
    var raster: RasterSurface,
    var rect: Rect,
) : CanvasItem, Resizable {

    override val kind = KIND
    override val resizable = true

    override fun paint(r: Renderer) = r.drawRaster(raster, rect)

    override fun bounds(): Rect = rect

    override fun translate(dx: Double, dy: Double) {
        rect = rect.translate(dx, dy)
    }

    override fun contains(p: Pt): Boolean = rect.contains(p)

    override fun centroid(): Pt = rect.center

    override fun intersectsCircle(cx: Double, cy: Double, radius: Double): Boolean =
        rect.distanceTo(Pt(cx, cy)) <= radius

    override fun geometry(): GeoHandle = RectHandle(rect)

    override fun setGeometry(handle: GeoHandle) {
        if (handle is RectHandle) rect = handle.rect
    }

    companion object {
        const val KIND = "image"
    }
}
