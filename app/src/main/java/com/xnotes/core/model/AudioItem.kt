package com.xnotes.core.model

import com.xnotes.core.audio.AudioPill
import com.xnotes.core.geometry.Affine
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.pal.Renderer
import com.xnotes.core.pal.TextMeasurer

/**
 * A recording or an attached audio file, placed as a pill that plays it: a disc to play or pause,
 * the [name] and length over a waveform that shows how far playback has got. It scales by its
 * corners and lengthens by its sides, laying itself out again each time; it never turns or mirrors.
 *
 * [rid] identifies the recording within its document. Items added while it was being recorded carry
 * it in their [SyncMark], which is how playback finds the ink it should fade and seek by.
 */
class AudioItem(
    var audio: AudioData,
    var rect: Rect,
    var name: String,
    var rid: Int,
    val measurer: TextMeasurer,
) : CanvasItem {

    override val kind = KIND
    override val resizable = true
    override val mirrorable = false
    override val exported = false
    override var locked = false
    override var group: Int? = null

    /** Where the open player stands on this recording, in ms, or -1 while none is open on it. Never saved. */
    @Volatile
    var playhead: Int = -1

    /** Whether that player is playing rather than paused. */
    @Volatile
    var playing: Boolean = false

    override fun paint(r: Renderer) = AudioPill.paint(r, this, AudioLook.current)

    override fun bounds(): Rect = rect

    override fun translate(dx: Double, dy: Double) {
        rect = rect.translate(dx, dy)
    }

    override fun contains(p: Pt): Boolean = rect.contains(p)

    override fun centroid(): Pt = rect.center

    override fun intersectsCircle(cx: Double, cy: Double, radius: Double): Boolean =
        rect.distanceTo(Pt(cx, cy)) <= radius

    override fun snapshotGeometry(): GeometrySnapshot = AudioSnapshot(rect)

    override fun restoreGeometry(snap: GeometrySnapshot) {
        if (snap is AudioSnapshot) rect = snap.rect
    }

    /**
     * A selection's resize lengthens the pill along x and scales it along y: it lays itself out
     * again from the new box, so a longer one shows more of the waveform and a taller one is bigger
     * all over. It stays upright, and never shrinks past what its disc and clock need, growing back
     * from whichever side held still.
     */
    override fun applyTransform(t: Affine) {
        val a = t.apply(rect.topLeft)
        val b = t.apply(Pt(rect.right, rect.bottom))
        val r = Rect.fromPoints(a, b)
        val h = maxOf(r.h, MIN_HEIGHT)
        val w = maxOf(r.w, h * MIN_ASPECT)
        val leftHeld = kotlin.math.abs(a.x - rect.left) <= kotlin.math.abs(b.x - rect.right)
        val topHeld = kotlin.math.abs(a.y - rect.top) <= kotlin.math.abs(b.y - rect.bottom)
        rect = Rect(if (leftHeld) r.left else r.right - w, if (topHeld) r.top else r.bottom - h, w, h)
    }

    companion object {
        const val KIND = "audio"

        /** The pill's size on paper as it is placed. */
        const val WIDTH_MM = 64.0
        const val HEIGHT_MM = 14.0

        /** The least a pill can be scaled down to, in content px, and how short it can get for its height. */
        const val MIN_HEIGHT = 16.0
        const val MIN_ASPECT = 2.4

        /** The pill's box centred on [center], sized for [dpi] content px per inch seen at [zoom]. */
        fun boxAt(center: Pt, dpi: Int, zoom: Double = 1.0): Rect {
            val w = WIDTH_MM / 25.4 * dpi / zoom
            val h = HEIGHT_MM / 25.4 * dpi / zoom
            return Rect(center.x - w / 2.0, center.y - h / 2.0, w, h)
        }
    }
}

/** Snapshot of a pill's geometry: only where it sits. */
private data class AudioSnapshot(val rect: Rect) : GeometrySnapshot
