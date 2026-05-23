package com.xnotes.core.pal

/**
 * Moves bitmaps between files/bytes and [RasterSurface]s (spec 01 §3). Decoders
 * report failure as `null`. PNG encoding embeds images in the `.xnote` bundle;
 * JPEG encoding produces presentation frames.
 */
interface ImageCodec {
    fun decode(bytes: ByteArray): RasterSurface?
    fun decodePath(path: String): RasterSurface?

    fun encodePng(surface: RasterSurface): ByteArray
    fun encodeJpeg(surface: RasterSurface, quality: Double): ByteArray
    fun encodeWebp(surface: RasterSurface, quality: Double): ByteArray? = null
}
