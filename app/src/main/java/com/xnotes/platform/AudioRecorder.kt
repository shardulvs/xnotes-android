package com.xnotes.platform

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File
import java.io.FileOutputStream

/**
 * The microphone into [file] as AAC in ADTS: every frame stands alone, so a recording cut short by
 * a crash still plays up to its last frame, and a later run can append to the same file and simply
 * carry on. Voice quality, 64 kbps mono. Pausing uses the recorder's own pause where it has one,
 * else ends the run and the next [resume] appends a new one.
 */
class AudioRecorder(private val context: Context, val file: File) {

    private var recorder: MediaRecorder? = null
    private var out: FileOutputStream? = null

    /** Start, or start again after a stop, appending to [file]. False when the microphone cannot be had. */
    fun start(): Boolean {
        release()
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        return try {
            val stream = FileOutputStream(file, true)
            out = stream
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(SAMPLE_RATE)
            r.setAudioEncodingBitRate(BIT_RATE)
            r.setOutputFile(stream.fd)
            r.prepare()
            r.start()
            recorder = r
            true
        } catch (_: Exception) {
            runCatching { r.release() }
            runCatching { out?.close() }
            out = null
            false
        }
    }

    /** Hold the recording. True when it can [resume] in place; false when the run ended and resuming appends. */
    fun pause(): Boolean {
        val r = recorder ?: return false
        return try {
            r.pause()
            true
        } catch (_: Exception) {
            release()
            false
        }
    }

    /** Carry on after [pause]: in place when the run held, else as a new run on the end of the file. */
    fun resume(): Boolean {
        val r = recorder ?: return start()
        return try {
            r.resume()
            true
        } catch (_: Exception) {
            start()
        }
    }

    /** The loudest the microphone heard since the last call, 0 to 1. */
    fun level(): Float = runCatching { (recorder?.maxAmplitude ?: 0) / 32767f }.getOrDefault(0f).coerceIn(0f, 1f)

    /** End the current run, writing out what it holds. */
    fun stop() = release()

    private fun release() {
        recorder?.let { r ->
            runCatching { r.stop() }
            runCatching { r.release() }
        }
        recorder = null
        runCatching { out?.close() }
        out = null
    }

    companion object {
        const val SAMPLE_RATE = 44_100
        const val BIT_RATE = 64_000
    }
}
