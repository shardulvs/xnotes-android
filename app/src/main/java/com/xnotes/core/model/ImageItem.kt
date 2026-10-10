package com.xnotes.core.model

import com.xnotes.core.geometry.Affine
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.pal.Renderer
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * A pasted or inserted bitmap (spec 02 §5.2). Holds the encoded [image] source (decoded on demand
 * by the renderer) and a quarter-turn [orientation]; resize is aspect-locked.
 *
 * A turn is stored, never baked: ink and shapes rotate by moving their own points, but pixels
 * cannot be moved without resampling them, so the rotate handle only advances [angle] and the
 * renderer places the bitmap turned. [rect] therefore stays the image's own upright box, and
 * [bounds] is the box that box sweeps out once turned.
 */
class ImageItem(
    var image: ImageData,
    var rect: Rect,
    var orientation: Int = 0,
    /** Free rotation about [rect]'s centre, radians clockwise. */
    var angle: Double = 0.0,
) : CanvasItem, Resizable {

    override val kind = KIND
    override val resizable = true
    override var locked = false
    override var link: String? = null

    override fun paint(r: Renderer) = r.drawImage(image, rect, orientation, angle)

    override fun bounds(): Rect = if (angle == 0.0) rect else Rect.bounding(corners())

    /** The turned rect's four corners, in the item's own space. */
    fun corners(): List<Pt> {
        val cs = cos(angle)
        val sn = sin(angle)
        val c = rect.center
        return listOf(
            Pt(rect.left, rect.top), Pt(rect.right, rect.top),
            Pt(rect.right, rect.bottom), Pt(rect.left, rect.bottom),
        ).map {
            val dx = it.x - c.x
            val dy = it.y - c.y
            Pt(c.x + dx * cs - dy * sn, c.y + dx * sn + dy * cs)
        }
    }

    /** [p] brought back into the upright rect's frame, so hit tests stay plain rectangle maths. */
    fun unturn(p: Pt): Pt {
        if (angle == 0.0) return p
        val cs = cos(angle)
        val sn = sin(angle)
        val c = rect.center
        val dx = p.x - c.x
        val dy = p.y - c.y
        return Pt(c.x + dx * cs + dy * sn, c.y - dx * sn + dy * cs)
    }

    /** The inverse of [unturn]: an upright-frame point turned back to where it is drawn. */
    fun turn(p: Pt): Pt {
        if (angle == 0.0) return p
        val cs = cos(angle)
        val sn = sin(angle)
        val c = rect.center
        val dx = p.x - c.x
        val dy = p.y - c.y
        return Pt(c.x + dx * cs - dy * sn, c.y + dx * sn + dy * cs)
    }

    override fun translate(dx: Double, dy: Double) {
        rect = rect.translate(dx, dy)
    }

    override fun contains(p: Pt): Boolean = rect.contains(unturn(p))

    override fun centroid(): Pt = rect.center

    override fun outlinePoints(): List<Pt> = corners()

    override fun intersectsCircle(cx: Double, cy: Double, radius: Double): Boolean =
        rect.distanceTo(unturn(Pt(cx, cy))) <= radius

    override fun geometry(): GeoHandle = RectHandle(rect)

    override fun setGeometry(handle: GeoHandle) {
        if (handle is RectHandle) rect = handle.rect
    }

    override fun snapshotGeometry(): GeometrySnapshot = ImageSnapshot(rect, angle)

    override fun restoreGeometry(snap: GeometrySnapshot) {
        if (snap is ImageSnapshot) {
            rect = snap.rect
            angle = snap.angle
        }
    }

    /**
     * Scale the upright box about its mapped centre and add the transform's own turn to [angle].
     * The scale is measured along the image's OWN axes, not the world axes: a single-axis resize
     * of a turned box is a world-space shear, whose column norms (t.scaleX/scaleY) would grow both
     * sides near-uniformly and let the bitmap drift out of its transform box. Projecting the linear
     * part onto the image's current width/height directions recovers the true per-side factors, and
     * reduces to t.scaleX/scaleY at angle 0. A bitmap has no shear to be drawn with.
     */
    override fun applyTransform(t: Affine) {
        val c = t.apply(rect.center)
        val ca = cos(angle)
        val sa = sin(angle)
        val wScale = hypot(t.a * ca + t.c * sa, t.b * ca + t.d * sa)
        val hScale = hypot(-t.a * sa + t.c * ca, -t.b * sa + t.d * ca)
        val w = rect.w * wScale
        val h = rect.h * hScale
        rect = Rect(c.x - w / 2.0, c.y - h / 2.0, w, h)
        angle += t.rotationAngle
    }

    companion object {
        const val KIND = "image"
    }
}

/** Snapshot of an image's transformable geometry. */
private data class ImageSnapshot(val rect: Rect, val angle: Double) : GeometrySnapshot
