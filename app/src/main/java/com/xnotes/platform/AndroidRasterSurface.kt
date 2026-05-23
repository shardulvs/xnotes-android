package com.xnotes.platform

import android.graphics.Bitmap
import android.graphics.Canvas
import com.xnotes.core.model.Rgba
import com.xnotes.core.pal.RasterSurface
import com.xnotes.core.pal.Renderer
import com.xnotes.core.pal.SurfaceFactory

/** A [RasterSurface] backed by an ARGB_8888 [Bitmap]. */
class AndroidRasterSurface(
    val bitmap: Bitmap,
    override val devicePixelRatio: Double = 1.0,
) : RasterSurface {

    override val width: Int get() = bitmap.width
    override val height: Int get() = bitmap.height

    override fun fill(color: Rgba) {
        bitmap.eraseColor(color.toArgb())
    }

    override fun renderer(): Renderer = AndroidRenderer(Canvas(bitmap))

    override fun recycle() {
        if (!bitmap.isRecycled) bitmap.recycle()
    }

    companion object {
        fun create(widthPx: Int, heightPx: Int, devicePixelRatio: Double = 1.0): AndroidRasterSurface {
            val bmp = Bitmap.createBitmap(widthPx.coerceAtLeast(1), heightPx.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            return AndroidRasterSurface(bmp, devicePixelRatio)
        }
    }
}

/** Allocates [AndroidRasterSurface]s for the page cache, thumbnails and frames. */
class AndroidSurfaceFactory : SurfaceFactory {
    override fun create(widthPx: Int, heightPx: Int, devicePixelRatio: Double): RasterSurface =
        AndroidRasterSurface.create(widthPx, heightPx, devicePixelRatio)
}
