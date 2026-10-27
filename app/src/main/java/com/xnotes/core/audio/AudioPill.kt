package com.xnotes.core.audio

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.AudioItem
import com.xnotes.core.model.AudioLook
import com.xnotes.core.model.Rgba
import com.xnotes.core.pal.FontFace
import com.xnotes.core.pal.FontSpec
import com.xnotes.core.pal.Renderer
import com.xnotes.core.pal.TextMeasurer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * How an [AudioItem] looks: a capsule holding a play disc on the left and, beside it, the name and
 * the time over the waveform. [layout] is the single source of its geometry, so the paged renderer
 * ([paint]) and the canvas's triangle mesher draw exactly the same pill. Every length is a fraction
 * of the pill's height, so it scales with the box.
 */
object AudioPill {

    val FACE: FontFace = FontFace.SANS

    private const val PAD_LEFT = 0.1875
    private const val DISC = 0.625
    private const val DISC_GAP = 0.1875
    private const val PAD_RIGHT = 0.34
    private const val ROW_TOP = 0.17
    private const val NAME_LINE = 0.235
    private const val TIME_LINE = 0.215
    private const val NAME_TIME_GAP = 0.12
    private const val WAVE_Y = 0.665
    private const val WAVE_H = 0.28
    private const val BAR_W = 0.047
    private const val BAR_GAP = 0.031
    private const val BAR_MIN = 0.047

    /** The pill's parts in content px. [glyph] is the play triangle, or the pause glyph's two bars. */
    class Layout(
        val body: List<Pt>,
        val discCenter: Pt,
        val discRadius: Double,
        val glyph: List<List<Pt>>,
        val name: String,
        val nameFont: FontSpec,
        val nameAt: Pt,
        val time: String,
        val timeFont: FontSpec,
        val timeAt: Pt,
        val bars: List<List<Pt>>,
        /** How many bars from the left playback has passed. */
        val played: Int,
        /** The waveform is not read yet, so the bars are flat placeholders. */
        val pending: Boolean,
        /** A player is open on this pill, so the time shows where it stands. */
        val active: Boolean,
    )

    fun layout(item: AudioItem): Layout = layout(item.rect, item.name, item.audio.durationMs, item.audio.peaks, item.playhead, item.playing, item.measurer)

    fun layout(
        rect: Rect,
        name: String,
        durationMs: Int,
        peaks: ByteArray,
        playhead: Int,
        playing: Boolean,
        measurer: TextMeasurer,
    ): Layout {
        val h = rect.h
        val cy = rect.centerY
        val discR = DISC * h / 2.0
        val disc = Pt(rect.left + PAD_LEFT * h + discR, cy)
        val glyph = if (playing) pauseGlyph(disc, discR) else listOf(playGlyph(disc, discR))

        val x0 = rect.left + (PAD_LEFT + DISC + DISC_GAP) * h
        val x1 = rect.right - PAD_RIGHT * h
        val active = playhead >= 0
        val long = durationMs >= 3_600_000
        val time = AudioTime.format(if (active) playhead else durationMs, long)
        val unit = measurer.lineHeight(FontSpec(10.0, FACE)) / 10.0
        val nameFont = FontSpec(NAME_LINE * h / unit, FACE)
        val timeFont = FontSpec(TIME_LINE * h / unit, FACE)
        val rowTop = rect.top + ROW_TOP * h
        val timeW = measurer.advances(time, timeFont).sum()
        val timeAt = Pt(x1 - timeW, rowTop + measurer.metrics(nameFont).ascent)
        val nameAt = Pt(x0, timeAt.y)
        val shown = fit(name, nameFont, timeAt.x - NAME_TIME_GAP * h - x0, measurer)

        val bw = BAR_W * h
        val step = bw + BAR_GAP * h
        val n = floor((x1 - x0 + BAR_GAP * h) / step).toInt().coerceAtLeast(1)
        val waveY = rect.top + WAVE_Y * h
        val pending = peaks.isEmpty()
        val bars = ArrayList<List<Pt>>(n)
        for (i in 0 until n) {
            val level = if (pending) 0.0 else peakAt(peaks, i, n) / 255.0
            val bh = maxOf(BAR_MIN, level * WAVE_H) * h
            bars.add(capsule(Rect(x0 + i * step, waveY - bh / 2.0, bw, bh), 4))
        }
        val played = if (!active || durationMs <= 0) 0 else (n.toDouble() * playhead / durationMs).toInt().coerceIn(0, n)
        return Layout(
            capsule(rect, 16), disc, discR, glyph,
            shown, nameFont, nameAt, time, timeFont, timeAt,
            bars, played, pending, active,
        )
    }

    fun paint(r: Renderer, item: AudioItem, look: AudioLook) {
        val l = layout(item)
        r.fillPolygon(l.body, look.body)
        r.fillCircle(l.discCenter, l.discRadius, look.disc)
        for (g in l.glyph) r.fillPolygon(g, look.glyph)
        for ((i, bar) in l.bars.withIndex()) r.fillPolygon(bar, barColor(look, l, i))
        if (l.name.isNotEmpty()) r.drawTextRun(l.name, l.nameAt.x, l.nameAt.y, l.nameFont, look.name)
        r.drawTextRun(l.time, l.timeAt.x, l.timeAt.y, l.timeFont, if (l.active) look.timeActive else look.time)
    }

    fun barColor(look: AudioLook, l: Layout, i: Int): Rgba = when {
        l.pending -> look.barPending
        i < l.played -> look.barPlayed
        else -> look.bar
    }

    /** Where in the recording a point on the pill's waveform stands, in ms; null off the waveform. */
    fun seekAt(item: AudioItem, p: Pt): Int? {
        val h = item.rect.h
        val x0 = item.rect.left + (PAD_LEFT + DISC + DISC_GAP) * h
        val x1 = item.rect.right - PAD_RIGHT * h
        if (p.x < x0 || p.x > x1 || item.audio.durationMs <= 0) return null
        return ((p.x - x0) / (x1 - x0) * item.audio.durationMs).toInt().coerceIn(0, item.audio.durationMs)
    }

    /** Whether [p] is on the pill's play disc (or close enough to it to mean it). */
    fun onDisc(item: AudioItem, p: Pt): Boolean {
        val h = item.rect.h
        return p.x <= item.rect.left + (PAD_LEFT + DISC + DISC_GAP / 2.0) * h && item.rect.contains(p)
    }

    /** The loudest of [peaks] under bar [i] of [n]. */
    fun peakAt(peaks: ByteArray, i: Int, n: Int): Int {
        val from = (i.toLong() * peaks.size / n).toInt()
        val to = maxOf(from + 1, ((i + 1).toLong() * peaks.size / n).toInt()).coerceAtMost(peaks.size)
        var m = 0
        for (k in from until to) m = maxOf(m, peaks[k].toInt() and 0xFF)
        return m
    }

    /** [text] cut with an ellipsis to fit [width]. */
    private fun fit(text: String, font: FontSpec, width: Double, measurer: TextMeasurer): String {
        if (text.isEmpty() || width <= 0.0) return ""
        val adv = measurer.advances(text, font)
        if (adv.sum() <= width) return text
        val ellipsis = measurer.advances("…", font).sum()
        var w = 0.0
        var keep = 0
        while (keep < text.length && w + adv[keep] + ellipsis <= width) w += adv[keep++]
        return if (keep == 0) "" else text.substring(0, keep).trimEnd() + "…"
    }

    private fun playGlyph(c: Pt, r: Double): List<Pt> =
        listOf(Pt(c.x - 0.3 * r, c.y - 0.45 * r), Pt(c.x + 0.5 * r, c.y), Pt(c.x - 0.3 * r, c.y + 0.45 * r))

    private fun pauseGlyph(c: Pt, r: Double): List<List<Pt>> {
        val w = 0.22 * r
        val h = 0.78 * r
        val gap = 0.22 * r
        val top = c.y - h / 2.0
        return listOf(
            rect(c.x - gap / 2.0 - w, top, w, h),
            rect(c.x + gap / 2.0, top, w, h),
        )
    }

    private fun rect(x: Double, y: Double, w: Double, h: Double) = listOf(Pt(x, y), Pt(x + w, y), Pt(x + w, y + h), Pt(x, y + h))

    /** A rectangle with fully round ends along its longer side, as a polygon; [segments] per end. */
    fun capsule(b: Rect, segments: Int): List<Pt> {
        val out = ArrayList<Pt>(2 * segments + 2)
        if (b.w >= b.h) {
            val r = b.h / 2.0
            val left = b.left + r
            val right = b.right - r
            for (k in 0..segments) {
                val a = -PI / 2.0 + PI * k / segments
                out.add(Pt(right + r * cos(a), b.centerY + r * sin(a)))
            }
            for (k in 0..segments) {
                val a = PI / 2.0 + PI * k / segments
                out.add(Pt(left + r * cos(a), b.centerY + r * sin(a)))
            }
        } else {
            val r = b.w / 2.0
            val top = b.top + r
            val bottom = b.bottom - r
            for (k in 0..segments) {
                val a = PI + PI * k / segments
                out.add(Pt(b.centerX + r * cos(a), top + r * sin(a)))
            }
            for (k in 0..segments) {
                val a = PI * k / segments
                out.add(Pt(b.centerX + r * cos(a), bottom + r * sin(a)))
            }
        }
        return out
    }
}
