package com.xnotes.gl

import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLUtils
import com.xnotes.core.model.ImageData
import com.xnotes.platform.ImageDecoder
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * GL textures for placed images, decoded on demand at the size the current zoom needs.
 *
 * Images keep their encoded bytes on disk and are decoded only when drawn, which is what keeps a
 * canvas full of photographs off the heap. That property has to survive the move to GL, so nothing
 * here holds a full-resolution bitmap: a decode produces a texture at the size the screen can
 * actually show, the bitmap is recycled immediately, and the texture is re-made only when the zoom
 * has moved far enough that the one in hand is visibly coarse.
 *
 * Decoding runs off the render thread, because it reads a file and can take tens of milliseconds.
 * The upload has to happen with the context current, so a finished decode is queued and picked up
 * at the start of the next frame.
 */
class TextureCache(private val budgetBytes: Long = DEFAULT_BUDGET_BYTES) {

    private class Entry(
        val texture: Int,
        val width: Int,
        val height: Int,
        /** Longest edge, in pixels, this texture was decoded for. */
        val decodedFor: Int,
        var lastUsedFrame: Long,
    ) {
        val bytes: Long get() = width.toLong() * height * 4
    }

    private class Pending(val image: ImageData, val bitmap: Bitmap, val decodedFor: Int)

    private val entries = IdentityHashMap<ImageData, Entry>()
    private val ready = ConcurrentLinkedQueue<Pending>()
    private val decoding = IdentityHashMap<ImageData, Int>()

    private var contextGen = -1
    private var frame = 0L

    /** Bytes the live textures occupy, for the debug readout. */
    var residentBytes: Long = 0
        private set

    val textureCount: Int get() = entries.size

    /** A new context: every texture name is gone, so drop the lot rather than delete it. */
    fun onContextCreated(gen: Int) {
        contextGen = gen
        entries.clear()
        decoding.clear()
        while (true) (ready.poll() ?: break).bitmap.recycle()
        residentBytes = 0
    }

    /** A newly rasterized text image can upload in the very next frame, with no second decode. */
    fun offerBitmap(image: ImageData, bitmap: Bitmap) {
        ready.add(Pending(image, bitmap, maxEdge(image)))
    }

    /** Take in whatever finished decoding since the last frame. Call with the context current. */
    fun uploadPending() {
        while (true) {
            val next = ready.poll() ?: return
            decoding.remove(next.image)
            if (next.bitmap.isRecycled) continue
            val existing = entries.remove(next.image)
            if (existing != null) {
                residentBytes -= existing.bytes
                GLES30.glDeleteTextures(1, intArrayOf(existing.texture), 0)
            }
            val name = IntArray(1)
            GLES30.glGenTextures(1, name, 0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, name[0])
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, next.bitmap, 0)
            val entry = Entry(name[0], next.bitmap.width, next.bitmap.height, next.decodedFor, frame)
            entries[next.image] = entry
            residentBytes += entry.bytes
            next.bitmap.recycle()
        }
    }

    /**
     * The texture for [image] at a size that suits [wantedLongEdge] device pixels, or 0 when one is
     * not ready yet. Asks for a decode when there is nothing, or when what is held is coarse enough
     * that the difference would show.
     */
    fun textureFor(image: ImageData, wantedLongEdge: Int, decodeOn: (Runnable) -> Unit): Int {
        val target = targetSize(wantedLongEdge)
        val entry = entries[image]
        if (entry != null) {
            entry.lastUsedFrame = frame
            // Re-decode only on a real step up: re-uploading for every small zoom change would
            // spend more time decoding than drawing.
            if (target > entry.decodedFor && entry.decodedFor < maxEdge(image)) request(image, target, decodeOn)
            return entry.texture
        }
        request(image, target, decodeOn)
        return 0
    }

    /** Mark the start of a frame and evict whatever the budget no longer covers. */
    fun beginFrame() {
        frame++
        if (residentBytes <= budgetBytes) return
        // Least recently drawn first: what is off screen goes before what is on it.
        val ordered = entries.entries.sortedBy { it.value.lastUsedFrame }
        for ((image, entry) in ordered) {
            if (residentBytes <= budgetBytes) break
            if (entry.lastUsedFrame == frame) continue // in this very frame, so still needed
            GLES30.glDeleteTextures(1, intArrayOf(entry.texture), 0)
            residentBytes -= entry.bytes
            entries.remove(image)
        }
    }

    /** Drop everything, deleting the textures. Call with the context current. */
    fun release() {
        for (entry in entries.values) GLES30.glDeleteTextures(1, intArrayOf(entry.texture), 0)
        entries.clear()
        residentBytes = 0
    }

    private fun request(image: ImageData, target: Int, decodeOn: (Runnable) -> Unit) {
        val already = decoding[image]
        if (already != null && already >= target) return
        decoding[image] = target
        val gen = contextGen
        decodeOn(
            Runnable {
                val edge = target.coerceAtMost(maxEdge(image))
                val bitmap = runCatching { ImageDecoder.decodeSampledFile(image.file.path, edge, edge) }.getOrNull()
                if (bitmap == null || gen != contextGen) {
                    bitmap?.recycle()
                    return@Runnable
                }
                ready.add(Pending(image, bitmap, target))
            },
        )
    }

    /**
     * The decode size for a wanted edge, rounded up to a power of two. Rounding is what stops a
     * slow pinch from re-decoding on every frame: the target only changes at doublings.
     */
    private fun targetSize(wantedLongEdge: Int): Int {
        var size = MIN_EDGE
        while (size < wantedLongEdge && size < MAX_EDGE) size *= 2
        return size
    }

    private fun maxEdge(image: ImageData): Int = maxOf(image.width, image.height).coerceAtLeast(1)

    companion object {
        /** Texture memory the cache aims to stay under. */
        const val DEFAULT_BUDGET_BYTES = 96L * 1024 * 1024

        /** Smallest and largest decode edge, in pixels. */
        const val MIN_EDGE = 128
        const val MAX_EDGE = 4096
    }
}
