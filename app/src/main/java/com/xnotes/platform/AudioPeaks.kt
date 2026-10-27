package com.xnotes.platform

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.xnotes.core.model.AudioData
import java.io.File
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Reads an attached file's waveform with the system decoder. Each of [AudioData.PEAK_BINS] slices
 * decodes a short window from its start rather than the whole slice, so an hour-long file reads in
 * seconds; the loudest sample there stands for the slice.
 */
object AudioPeaks {

    private const val WINDOW_US = 400_000L
    private const val DEQUEUE_US = 5_000L

    /** Empty polls after a window's last input before it is given up on. */
    private const val MAX_WAITS = 40

    /** One background thread for every read, so a batch of attachments never competes with drawing. */
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "audio-peaks").apply { priority = Thread.MIN_PRIORITY } }

    /** Read [file]'s waveform on the worker and hand it to [done] there; null when it cannot be decoded. */
    fun readLater(file: File, durationMs: Int, done: (ByteArray?) -> Unit) {
        worker.execute { done(runCatching { read(file, durationMs) }.getOrNull()) }
    }

    fun read(file: File, durationMs: Int): ByteArray? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.path)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val totalUs = (if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else durationMs * 1000L)
                .coerceAtLeast(1L)
            val dec = MediaCodec.createDecoderByType(mime)
            codec = dec
            dec.configure(format, null, null, 0)
            dec.start()
            val bins = AudioData.PEAK_BINS
            val levels = FloatArray(bins)
            val binUs = totalUs / bins
            val info = MediaCodec.BufferInfo()
            var float = false
            for (b in 0 until bins) {
                val from = b * binUs
                val until = from + min(binUs, WINDOW_US).coerceAtLeast(1L)
                extractor.seekTo(from, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                if (b > 0) dec.flush()
                var fed = false
                var waits = 0
                var peak = 0f
                // Each window ends in an end of stream, which the next window's flush clears.
                while (waits < MAX_WAITS) {
                    if (!fed) {
                        val inIdx = dec.dequeueInputBuffer(DEQUEUE_US)
                        if (inIdx >= 0) {
                            val buf = dec.getInputBuffer(inIdx)
                            val t = extractor.sampleTime
                            val n = if (buf == null || t < 0 || t >= until) -1 else extractor.readSampleData(buf, 0)
                            if (n < 0) {
                                dec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                fed = true
                            } else {
                                dec.queueInputBuffer(inIdx, 0, n, t, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val outIdx = dec.dequeueOutputBuffer(info, DEQUEUE_US)
                    if (outIdx >= 0) {
                        val out = dec.getOutputBuffer(outIdx)
                        if (out != null && info.size > 0 && info.presentationTimeUs + 1 >= from) {
                            out.position(info.offset)
                            out.limit(info.offset + info.size)
                            val ordered = out.order(ByteOrder.LITTLE_ENDIAN)
                            if (float) {
                                val fb = ordered.asFloatBuffer()
                                while (fb.hasRemaining()) peak = maxOf(peak, abs(fb.get()))
                            } else {
                                val sb = ordered.asShortBuffer()
                                while (sb.hasRemaining()) peak = maxOf(peak, abs(sb.get().toInt()) / 32768f)
                            }
                        }
                        dec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val of = dec.outputFormat
                        float = of.containsKey(MediaFormat.KEY_PCM_ENCODING) && of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    } else if (fed) {
                        waits++
                    }
                }
                levels[b] = peak
            }
            // Loudness as the eye reads it: the loudest slice fills the bar, quiet speech still shows.
            val top = levels.maxOrNull() ?: 0f
            if (top > 0f) for (i in levels.indices) levels[i] = sqrt(levels[i] / top)
            return AudioData.peaksOf(levels, bins, bins)
        } catch (_: Exception) {
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }
}
