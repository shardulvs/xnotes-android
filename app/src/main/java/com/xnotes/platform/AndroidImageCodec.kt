package com.xnotes.platform

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.xnotes.core.pal.ImageCodec
import com.xnotes.core.pal.RasterSurface
import java.io.ByteArrayOutputStream

/** Decodes/encodes bitmaps via the Android framework (spec 01 §3). */
class AndroidImageCodec : ImageCodec {

    override fun decode(bytes: ByteArray): RasterSurface? =
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { wrap(it) }

    override fun decodePath(path: String): RasterSurface? =
        BitmapFactory.decodeFile(path)?.let { wrap(it) }

    override fun encodePng(surface: RasterSurface): ByteArray =
        compress(surface, Bitmap.CompressFormat.PNG, 100)

    override fun encodeJpeg(surface: RasterSurface, quality: Double): ByteArray =
        compress(surface, Bitmap.CompressFormat.JPEG, (quality * 100).toInt().coerceIn(1, 100))

    override fun encodeWebp(surface: RasterSurface, quality: Double): ByteArray? {
        val q = (quality * 100).toInt().coerceIn(1, 100)
        @Suppress("DEPRECATION")
        val format = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            Bitmap.CompressFormat.WEBP
        }
        return compress(surface, format, q)
    }

    private fun wrap(bitmap: Bitmap): RasterSurface {
        // Ensure a software ARGB_8888 surface we can draw and re-encode.
        val argb = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap
        else bitmap.copy(Bitmap.Config.ARGB_8888, false)
        return AndroidRasterSurface(argb)
    }

    private fun compress(surface: RasterSurface, format: Bitmap.CompressFormat, quality: Int): ByteArray {
        val bmp = (surface as AndroidRasterSurface).bitmap
        return ByteArrayOutputStream().use { out ->
            bmp.compress(format, quality, out)
            out.toByteArray()
        }
    }
}
