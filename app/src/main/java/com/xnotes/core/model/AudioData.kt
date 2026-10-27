package com.xnotes.core.model

import java.io.File

/**
 * The immutable source of an [AudioItem]: the encoded audio [file] on disk as it was recorded or
 * attached, never decoded into memory. [ext] names its format in the bundle ("m4a", "mp3"…),
 * [durationMs] its length, and [peaks] its loudness in even slices from start to end (0 to 255),
 * which the waveform draws; empty while an attached file is still being read. Shared freely between
 * copies, like [ImageData], because the file is read-only and lives in a temp dir the platform owns.
 */
class AudioData(
    val file: File,
    val ext: String,
    val durationMs: Int,
    val peaks: ByteArray,
) {
    /** The same audio with its waveform read. */
    fun withPeaks(p: ByteArray) = AudioData(file, ext, durationMs, p)

    companion object {
        /** How many slices a waveform is stored at; drawing resamples them to its bars. */
        const val PEAK_BINS = 256

        /** [levels] (0 to 1, oldest first) folded into [bins] slices, each the loudest it covers. */
        fun peaksOf(levels: FloatArray, count: Int = levels.size, bins: Int = PEAK_BINS): ByteArray {
            if (count <= 0) return ByteArray(0)
            val out = ByteArray(bins)
            for (b in 0 until bins) {
                val from = (b.toLong() * count / bins).toInt()
                val to = maxOf(from + 1, ((b + 1).toLong() * count / bins).toInt()).coerceAtMost(count)
                var m = 0f
                for (i in from until to) if (levels[i] > m) m = levels[i]
                out[b] = (m.coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte()
            }
            return out
        }
    }
}
