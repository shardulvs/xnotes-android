package com.xnotes.platform

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream

/**
 * Attaching an audio file: it is copied to disk as it is, byte for byte, never into the heap, and
 * kept only if Android can play it. Off the main thread throughout.
 */
object AudioFiles {

    /** Larger attachments are refused. */
    const val MAX_BYTES = 1L shl 30

    sealed class Result {
        class Ok(val file: File, val ext: String, val durationMs: Int, val name: String) : Result()
        object TooBig : Result()
        object Unplayable : Result()
        object Unreadable : Result()
    }

    /** Copy the audio at [uri] into [dir] and check it plays. */
    fun attach(context: Context, uri: Uri, dir: File): Result {
        val resolver = context.contentResolver
        val display = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (!c.moveToFirst()) null else Pair(c.getString(0), if (c.isNull(1)) -1L else c.getLong(1))
            }
        }.getOrNull()
        if ((display?.second ?: -1L) > MAX_BYTES) return Result.TooBig
        val file = runCatching { File.createTempFile("aud", null, dir) }.getOrNull() ?: return Result.Unreadable
        val copied = runCatching {
            resolver.openInputStream(uri)?.use { input ->
                FileOutputStream(file).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) return@runCatching false
                        out.write(buf, 0, n)
                    }
                }
                true
            } ?: false
        }.getOrDefault(false)
        if (!copied) {
            val tooBig = file.length() > MAX_BYTES - 64 * 1024
            file.delete()
            return if (tooBig) Result.TooBig else Result.Unreadable
        }
        val duration = durationOf(file)
        if (duration == null || duration <= 0) {
            file.delete()
            return Result.Unplayable
        }
        val name = display?.first.orEmpty()
        val stem = name.substringBeforeLast('.', name).trim()
        return Result.Ok(file, extOf(name, resolver.getType(uri)), duration, stem)
    }

    /** How long [file] plays, in ms, or null when it holds no audio Android can read. */
    fun durationOf(file: File): Int? {
        val retriever = MediaMetadataRetriever()
        val fromMeta = try {
            retriever.setDataSource(file.path)
            val hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?.takeIf { hasAudio == null || hasAudio == "yes" }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
        if (fromMeta != null && fromMeta > 0) return fromMeta.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.path)
            (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
                .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true && it.containsKey(MediaFormat.KEY_DURATION) }
                ?.getLong(MediaFormat.KEY_DURATION)?.let { (it / 1000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { extractor.release() }
        }
    }

    /** The extension a file keeps in the bundle: its name's, else one its type implies. */
    private fun extOf(name: String, mime: String?): String {
        val fromName = name.substringAfterLast('.', "").lowercase()
        if (Regex("[a-z0-9]{1,5}").matches(fromName)) return fromName
        return when (mime?.lowercase()) {
            "audio/mpeg", "audio/mp3" -> "mp3"
            "audio/ogg", "application/ogg" -> "ogg"
            "audio/opus" -> "opus"
            "audio/flac", "audio/x-flac" -> "flac"
            "audio/wav", "audio/x-wav", "audio/wave" -> "wav"
            "audio/amr" -> "amr"
            "audio/aac", "audio/aacp" -> "aac"
            "audio/webm" -> "webm"
            "audio/3gpp" -> "3gp"
            else -> "m4a"
        }
    }
}
