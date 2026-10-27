package com.xnotes.platform

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * A finished recording's ADTS frames moved into an .m4a, the same AAC untouched: the container
 * gives it an exact length and a seek index, and it plays everywhere. A copy, not an encode, so an
 * hour takes a moment.
 */
object AudioRemux {

    /** Write [adts] as an .m4a at [out]; false (and [out] gone) when it cannot. */
    fun toM4a(adts: File, out: File): Boolean {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false
        try {
            extractor.setDataSource(adts.path)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return fail(out)
            extractor.selectTrack(track)
            val m = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = m
            val t = m.addTrack(extractor.getTrackFormat(track))
            m.start()
            started = true
            val buf = ByteBuffer.allocate(256 * 1024)
            val info = MediaCodec.BufferInfo()
            var frames = 0
            while (true) {
                val n = extractor.readSampleData(buf, 0)
                if (n < 0) break
                val sync = extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
                info.set(0, n, extractor.sampleTime, if (sync) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                m.writeSampleData(t, buf, info)
                frames++
                extractor.advance()
            }
            if (frames == 0) return fail(out)
            m.stop()
            started = false
            return true
        } catch (_: Exception) {
            return fail(out)
        } finally {
            if (started) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun fail(out: File): Boolean {
        out.delete()
        return false
    }
}
