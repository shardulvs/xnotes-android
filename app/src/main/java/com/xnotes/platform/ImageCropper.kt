package com.xnotes.platform

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.os.Build
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.ImageData
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * Writes a picture again as just part of itself, in a new file, keeping its kind: a JPEG stays a
 * JPEG, a PNG or WebP stays lossless, and an SVG keeps its drawing behind a narrower viewBox.
 */
object ImageCropper {

    /** A crop's longest side and pixel count at most; a bigger part is read at a power-of-two reduction. */
    private const val MAX_SIDE = 8192
    private const val MAX_PIXELS = 48_000_000L

    /** The JPEG quality a cropped photo is saved at. */
    private const val JPEG_QUALITY = 95

    /** The part [part] (whole pixels of its [w] by [h]) of the picture in [file], as a new file in [dir]; null when it failed. IO. */
    fun crop(file: File, w: Int, h: Int, part: Rect, dir: File): ImageData? = runCatching {
        if (part.w < 1.0 || part.h < 1.0) return@runCatching null
        if (ImageDecoder.isVector(file.path)) cropSvg(file, w, h, part, dir) else cropRaster(file, part, dir)
    }.getOrNull()

    private fun cropRaster(file: File, part: Rect, dir: File): ImageData? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val region = android.graphics.Rect(part.left.toInt(), part.top.toInt(), part.right.toInt(), part.bottom.toInt())
        var sample = 1
        while (maxOf(region.width(), region.height()) / sample > MAX_SIDE ||
            region.width().toLong() * region.height() / (sample.toLong() * sample) > MAX_PIXELS
        ) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = runCatching {
            val decoder = if (Build.VERSION.SDK_INT >= 31) {
                BitmapRegionDecoder.newInstance(file.path)
            } else {
                @Suppress("DEPRECATION") BitmapRegionDecoder.newInstance(file.path, false)
            }
            try { decoder.decodeRegion(region, opts) } finally { decoder.recycle() }
        }.getOrNull() ?: wholeThenCut(file, region, sample) ?: return null
        val (format, quality) = when (bounds.outMimeType) {
            "image/jpeg", "image/heif", "image/heic" -> Bitmap.CompressFormat.JPEG to JPEG_QUALITY
            "image/webp" -> (if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSLESS else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP) to 100
            else -> Bitmap.CompressFormat.PNG to 100
        }
        val out = File.createTempFile("img", null, dir)
        try {
            FileOutputStream(out).use { if (!bmp.compress(format, quality, it)) error("not written") }
            return ImageData(out, bmp.width, bmp.height)
        } catch (e: Exception) {
            out.delete()
            throw e
        } finally {
            bmp.recycle()
        }
    }

    /** For kinds the region decoder cannot read: the whole picture at [sample], then the part cut out of it. */
    private fun wholeThenCut(file: File, region: android.graphics.Rect, sample: Int): Bitmap? {
        val full = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val l = (region.left / sample).coerceIn(0, full.width - 1)
        val t = (region.top / sample).coerceIn(0, full.height - 1)
        val w = (region.width() / sample).coerceIn(1, full.width - l)
        val h = (region.height() / sample).coerceIn(1, full.height - t)
        return Bitmap.createBitmap(full, l, t, w, h).also { if (it !== full) full.recycle() }
    }

    /** An SVG keeps all its drawing; its viewBox, and its width and height with it, close in on [part]. */
    private fun cropSvg(file: File, w: Int, h: Int, part: Rect, dir: File): ImageData? {
        val text = file.readText()
        val start = Regex("<svg[\\s>]").find(text)?.range?.first ?: return null
        val end = text.indexOf('>', start).takeIf { it > start } ?: return null
        var tag = text.substring(start, end)
        val fx = part.left / w
        val fy = part.top / h
        val fw = part.w / w
        val fh = part.h / h
        val viewBox = Regex("(?<=\\s)viewBox\\s*=\\s*([\"'])([^\"']*)\\1").find(tag)
        val box = viewBox?.groupValues?.get(2)?.trim()?.split(Regex("[\\s,]+"))?.mapNotNull { it.toDoubleOrNull() }?.takeIf { it.size == 4 }
            ?: listOf(0.0, 0.0, w.toDouble(), h.toDouble())
        val next = listOf(box[0] + box[2] * fx, box[1] + box[3] * fy, box[2] * fw, box[3] * fh).joinToString(" ") { num(it) }
        tag = if (viewBox != null) tag.replaceRange(viewBox.range, "viewBox=\"$next\"") else "$tag viewBox=\"$next\""
        tag = scaled(tag, "width", fw)
        tag = scaled(tag, "height", fh)
        val out = File.createTempFile("img", null, dir)
        return try {
            out.writeText(text.substring(0, start) + tag + text.substring(end))
            val size = ImageDecoder.probeFile(out.path) ?: error("not an svg")
            ImageData(out, size.width, size.height)
        } catch (e: Exception) {
            out.delete()
            throw e
        }
    }

    /** [tag] with its [attr] length scaled by [k], its unit kept; left as it is when it has none or it is a percentage. */
    private fun scaled(tag: String, attr: String, k: Double): String {
        val m = Regex("(?<=\\s)$attr\\s*=\\s*([\"'])\\s*([0-9.+\\-eE]+)([a-z]*)\\s*\\1").find(tag) ?: return tag
        val v = m.groupValues[2].toDoubleOrNull() ?: return tag
        return tag.replaceRange(m.range, "$attr=\"${num(v * k)}${m.groupValues[3]}\"")
    }

    private fun num(v: Double): String = String.format(Locale.ROOT, "%.4f", v).trimEnd('0').trimEnd('.')
}
