package com.xnotes.platform

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * A small on-disk cache of note thumbnails so the explorer grid paints instantly across
 * launches instead of re-rendering every note.
 *
 * Each note URI maps to two files keyed by a hash of the URI: `<key>.png` (the thumbnail)
 * and `<key>.txt` (the source file's last-modified time it was rendered from). The caller
 * compares that stored mtime against the file's current mtime to detect a stale thumbnail
 * (an edit bumps the mtime), so the cache self-heals without explicit invalidation.
 *
 * Bounded by [trimToCap]: unlike the old recents cache (capped at 10), the explorer can
 * touch many files across folders, so after each [store] the oldest entries beyond
 * [maxFiles] are dropped (LRU by file mtime). Best-effort — failures are swallowed and
 * just cause a re-render.
 */
class NoteThumbnailCache(private val dir: File, private val maxFiles: Int = 256) {

    /** The cached thumbnail + the source mtime it was rendered from, or null when not cached. */
    fun load(uri: String): Pair<Bitmap, Long>? {
        val key = key(uri)
        val png = File(dir, "$key.png")
        if (!png.exists()) return null
        val bitmap = runCatching { BitmapFactory.decodeFile(png.path) }.getOrNull() ?: return null
        val modified = runCatching { File(dir, "$key.txt").readText().trim().toLong() }.getOrDefault(0L)
        return bitmap to modified
    }

    /** Cache [bitmap] for [uri], tagged with the source file's [modified] mtime. */
    fun store(uri: String, bitmap: Bitmap, modified: Long) {
        runCatching {
            dir.mkdirs()
            val key = key(uri)
            FileOutputStream(File(dir, "$key.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            File(dir, "$key.txt").writeText(modified.toString())
            trimToCap()
        }
    }

    fun remove(uri: String) {
        val key = key(uri)
        runCatching { File(dir, "$key.png").delete() }
        runCatching { File(dir, "$key.txt").delete() }
    }

    /** Delete cached files for any URI not in [keep]. */
    fun prune(keep: Set<String>) {
        runCatching {
            val keepKeys = keep.mapTo(HashSet()) { key(it) }
            dir.listFiles()?.forEach { f ->
                if (f.name.substringBeforeLast('.') !in keepKeys) f.delete()
            }
        }
    }

    /** Keep only the newest [maxFiles] thumbnails (by file mtime); drop the rest with their sidecars. */
    private fun trimToCap() {
        runCatching {
            val pngs = dir.listFiles { f -> f.name.endsWith(".png") } ?: return
            if (pngs.size <= maxFiles) return
            pngs.sortedByDescending { it.lastModified() }.drop(maxFiles).forEach { f ->
                f.delete()
                File(dir, "${f.name.substringBeforeLast('.')}.txt").delete()
            }
        }
    }

    private fun key(uri: String): String =
        MessageDigest.getInstance("SHA-256").digest(uri.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
}
