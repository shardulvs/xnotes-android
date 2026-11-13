package com.xnotes.platform

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.icu.lang.UCharacter
import android.icu.lang.UProperty
import android.os.Build
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.ImageData
import com.xnotes.core.model.ImageItem
import com.xnotes.core.model.TextItem
import java.io.File
import kotlin.math.ceil
import kotlin.math.sqrt

/** The standard GL outline route cannot paint color glyph bitmaps (including emoji). */
internal object CanvasTextRaster {
    data class Result(val image: ImageItem, val bitmap: Bitmap)

    fun needsColorGlyphs(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val codePoint = Character.codePointAt(text, i)
            // Detect emoji-presentation characters, flags and explicitly selected emoji style.
            // ICU's property is available from API 28; the range covers older devices.
            if (codePoint == 0xFE0F || codePoint == 0x20E3 ||
                codePoint in 0x1F000..0x1FAFF ||
                (codePoint > 0x7F && Build.VERSION.SDK_INT >= 28 &&
                    UCharacter.hasBinaryProperty(codePoint, UProperty.EMOJI_PRESENTATION))) return true
            i += Character.charCount(codePoint)
        }
        return false
    }

    /** Renders native shaping, emoji colors and alpha into a bounded GL-ready bitmap. */
    fun render(item: TextItem, directory: File): Result? {
        val paint = AndroidText.textPaint(
            item.font, Color.argb(item.rgba.a, item.rgba.r, item.rgba.g, item.rgba.b),
        )
        val layout = AndroidText.layout(item.text, item.width.toInt(), paint, TextItem.FLAGS)
        val width = layout.width.coerceAtLeast(1)
        val height = layout.height.coerceAtLeast(1)
        val scale = minOf(2.0, 4096.0 / maxOf(width, height), sqrt(4_194_304.0 / width / height))
        if (scale <= 0.0 || !scale.isFinite()) return null
        val bitmap = Bitmap.createBitmap(
            ceil(width * scale).toInt().coerceAtLeast(1),
            ceil(height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(bitmap)
        canvas.scale(scale.toFloat(), scale.toFloat())
        layout.draw(canvas)
        var file: File? = null
        try {
            directory.mkdirs()
            file = File.createTempFile("canvas-text-", ".png", directory)
            file.outputStream().use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) error("PNG encode failed")
            }
        } catch (e: Exception) {
            file?.delete()
            bitmap.recycle()
            throw e
        }
        val source = ImageData(file, bitmap.width, bitmap.height)
        return Result(ImageItem(source, Rect(item.pos.x, item.pos.y, width.toDouble(), height.toDouble())), bitmap)
    }
}
