package com.xnotes.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.xnotes.R
import com.xnotes.core.model.AudioData
import com.xnotes.core.model.AudioItem
import com.xnotes.platform.AudioFiles
import com.xnotes.platform.AudioRecorder
import com.xnotes.platform.AudioRemux
import com.xnotes.platform.RecordingService
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.sqrt

/** What a recording needs from the editor it was started in. */
interface RecordOwner {
    /** A recording id nothing in this editor's document uses yet. */
    fun freshRecordingId(): Int

    /** Place the finished recording where the view is now, as recording [rid], selected; null when nothing can take it. */
    fun placeRecording(audio: AudioData, name: String, rid: Int): AudioItem?

    /** A placed recording's .m4a is ready: [item] plays from [audio] from now on, and the note keeps it. */
    fun recordingMoved(item: AudioItem, audio: AudioData)

    /** Show [text], with [action] as a button on it. */
    fun recordingMessage(text: String, action: Pair<String, () -> Unit>? = null)

    /** Where a placed recording's files live, beside the document's other assets. */
    val recordingDir: File

    /** The note's name, which the notification shows. */
    val recordingTitle: String

    /** What a recording started here is found again by after a crash: the document it belongs to. */
    val recordingKey: String
}

/** An editor that plays and records: both canvases are. */
interface AudioHost : AudioOwner, RecordOwner

/**
 * The one recording in the app, for one editor. Recorded time counts only while it runs, so a
 * pause leaves no gap in the moments ink is given. It carries on with xnotes off screen, under
 * [RecordingService]. Stop, or closing the note, places it; only Discard throws it away, and
 * even that can be undone for a few seconds.
 */
object AudioRecording {

    class Session(val owner: RecordOwner, val rid: Int, val name: String, val file: File) {
        /** Recorded seconds, for the chip; it changes once a second. */
        var seconds by mutableIntStateOf(0)
            internal set
        var paused by mutableStateOf(false)
            internal set

        /** How loud the microphone is now, 0 to 1, for the menu's meter. */
        var level by mutableFloatStateOf(0f)
            internal set

        internal var accumulated = 0L
        internal var runningSince = SystemClock.elapsedRealtime()
        internal var levels = FloatArray(1024)
        internal var levelCount = 0

        /** Recorded time so far, in ms, pauses left out. */
        fun elapsedMs(): Long = accumulated + if (paused) 0L else SystemClock.elapsedRealtime() - runningSince

        internal fun addLevel(l: Float) {
            if (levelCount == levels.size) levels = levels.copyOf(levels.size * 2)
            levels[levelCount++] = l
        }
    }

    var session by mutableStateOf<Session?>(null)
        private set

    private const val TICK_MS = 50L

    /** Below this much free space a recording stops and is placed. */
    private const val MIN_FREE_BYTES = 100L shl 20

    /** How long a discarded recording can still be had back. */
    private const val UNDO_MS = 15_000L

    /** A recording left behind whose note never opened again is let go after this long. */
    private const val KEEP_LEFT_MS = 30L * 24 * 60 * 60 * 1000

    private var app: Context? = null
    private var recorder: AudioRecorder? = null
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "audio-remux") }
    private var ticks = 0
    private var discarded: Session? = null
    private val forget = Runnable { dropDiscarded() }

    private val tick = object : Runnable {
        override fun run() {
            val s = session ?: return
            if (s.paused) return
            val l = recorder?.level() ?: 0f
            s.level = l
            s.addLevel(l)
            val sec = (s.elapsedMs() / 1000).toInt()
            if (sec != s.seconds) s.seconds = sec
            if (++ticks % 100 == 0 && lowOnSpace()) {
                stop()
                s.owner.recordingMessage(app?.getString(R.string.err_storage_low) ?: return)
                return
            }
            main.postDelayed(this, TICK_MS)
        }
    }

    fun init(context: Context) {
        app = context.applicationContext
        val cutoff = System.currentTimeMillis() - KEEP_LEFT_MS
        for (f in dir(context).listFiles().orEmpty()) if (f.lastModified() < cutoff) f.delete()
    }

    /** Where recordings in progress are written; nothing else lives there. */
    fun dir(context: Context): File = File(context.filesDir, "recordings").apply { mkdirs() }

    /** Start recording for [owner]. False, with a message, when the microphone cannot be had. */
    fun start(owner: RecordOwner): Boolean {
        if (session != null) return false
        val ctx = app ?: return false
        AudioPlayback.close()
        dropDiscarded()
        val now = System.currentTimeMillis()
        val file = File(dir(ctx), "rec-$now.aac")
        val r = AudioRecorder(ctx, file)
        if (!r.start()) {
            file.delete()
            owner.recordingMessage(ctx.getString(R.string.err_record_failed))
            return false
        }
        recorder = r
        val s = Session(owner, owner.freshRecordingId(), defaultName(ctx, now), file)
        session = s
        writeSidecar(s, owner.recordingKey)
        ticks = 0
        RecordingService.start(ctx)
        main.removeCallbacks(tick)
        main.post(tick)
        return true
    }

    fun pause() {
        val s = session ?: return
        if (s.paused) return
        recorder?.pause()
        s.accumulated = s.elapsedMs()
        s.paused = true
        s.level = 0f
        main.removeCallbacks(tick)
        notifyChange()
    }

    fun resume() {
        val s = session ?: return
        if (!s.paused) return
        val r = recorder ?: AudioRecorder(app ?: return, s.file).also { recorder = it }
        if (!r.resume()) {
            s.owner.recordingMessage(app?.getString(R.string.err_record_failed) ?: return)
            return
        }
        s.runningSince = SystemClock.elapsedRealtime()
        s.paused = false
        main.removeCallbacks(tick)
        main.post(tick)
        notifyChange()
    }

    fun toggle() {
        if (session?.paused == true) resume() else pause()
    }

    /** Stop and place the recording where its editor's view is now. */
    fun stop() {
        val s = end() ?: return
        place(s)
    }

    /** Stop and place it if it is [owner]'s, as when that editor's note closes. */
    fun stopFor(owner: RecordOwner) {
        if (session?.owner === owner) stop()
    }

    /** Throw the recording away, though Undo on the message brings it back, paused, for a few seconds. */
    fun discard() {
        val s = end() ?: return
        dropDiscarded()
        // Not found again after a crash: it was thrown away, the file only waits for Undo.
        sidecarOf(s.file).delete()
        discarded = s
        main.postDelayed(forget, UNDO_MS)
        val ctx = app ?: return
        s.owner.recordingMessage(ctx.getString(R.string.recording_discarded), ctx.getString(R.string.undo) to { restore() })
    }

    /** The app is being closed under the recording: close the file as it stands, for the next open of its note. */
    fun abandon() {
        end()
    }

    /** The moment an item [owner] adds now is given: while it records there and is not paused. */
    fun markFor(owner: RecordOwner): com.xnotes.core.model.SyncMark? {
        val s = session ?: return null
        if (s.owner !== owner || s.paused) return null
        return com.xnotes.core.model.SyncMark(s.rid, s.elapsedMs().coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    }

    /** Give [items], just added by [owner], the moment of the recording running there; a pill never syncs. */
    fun stamp(owner: RecordOwner, items: List<com.xnotes.core.model.CanvasItem>) {
        if (items.isEmpty()) return
        val mark = markFor(owner) ?: return
        for (item in items) if (item !is AudioItem && item.sync == null) item.sync = mark
    }

    /** What the notification shows, or null with nothing recording. */
    fun notice(): RecordingService.Notice? {
        val s = session ?: return null
        return RecordingService.Notice(s.owner.recordingTitle, s.elapsedMs(), s.paused)
    }

    private fun restore() {
        val s = discarded ?: return
        main.removeCallbacks(forget)
        discarded = null
        if (session != null) return dropFile(s)
        val ctx = app ?: return
        recorder = null
        s.paused = true
        session = s
        writeSidecar(s, s.owner.recordingKey)
        RecordingService.start(ctx)
    }

    /** Close the recorder and the session, leaving the file whole. */
    private fun end(): Session? {
        val s = session ?: return null
        main.removeCallbacks(tick)
        if (!s.paused) s.accumulated = s.elapsedMs()
        s.paused = true
        recorder?.stop()
        recorder = null
        session = null
        app?.let { RecordingService.stop(it) }
        return s
    }

    private fun place(s: Session) {
        val duration = s.accumulated.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        if (!s.file.exists() || s.file.length() == 0L || duration <= 0) return dropFile(s)
        val adts = moveOut(s.file, s.owner.recordingDir) ?: return
        sidecarOf(s.file).delete()
        val item = s.owner.placeRecording(AudioData(adts, "aac", duration, peaksOf(s)), s.name, s.rid) ?: return
        toM4a(s.owner, item, adts)
    }

    /** Moved beside the note's other assets, so the recordings folder only ever holds ones in progress. */
    private fun moveOut(file: File, dir: File): File? {
        val adts = runCatching { File.createTempFile("aud", ".aac", dir) }.getOrNull() ?: return null
        if (!file.renameTo(adts)) {
            if (runCatching { file.copyTo(adts, overwrite = true) }.isFailure) {
                adts.delete()
                return null
            }
            file.delete()
        }
        return adts
    }

    /** Copy [adts] into an .m4a off the main thread, and hand it to [item] if it still plays from [adts]. */
    private fun toM4a(owner: RecordOwner, item: AudioItem, adts: File) {
        worker.execute {
            val m4a = File.createTempFile("aud", null, adts.parentFile)
            val ok = AudioRemux.toM4a(adts, m4a)
            val exact = if (ok) AudioFiles.durationOf(m4a) else null
            main.post {
                // The .aac left behind goes with the launch purge.
                if (!ok || exact == null || item.audio.file !== adts) {
                    m4a.delete()
                    return@post
                }
                owner.recordingMoved(item, AudioData(m4a, "m4a", exact, item.audio.peaks))
            }
        }
    }

    /**
     * Place what a crash or a swipe left unplaced for the document [owner] now shows, as if Stop had
     * been pressed when it ended: where the view is, with the ink written during it still tied to it.
     * [keys] are the names that document goes by; a recording still going is never touched.
     */
    fun recover(owner: RecordOwner, keys: List<String>) {
        val ctx = app ?: return
        val live = session?.file
        val dir = dir(ctx)
        for (side in dir.listFiles { f -> f.name.endsWith(SIDECAR) }.orEmpty()) {
            val o = runCatching { org.json.JSONObject(side.readText()) }.getOrNull()
            if (o == null) {
                side.delete()
                continue
            }
            if (o.optString("key") !in keys) continue
            val file = File(dir, o.optString("file"))
            if (file == live) continue
            side.delete()
            if (!file.exists() || file.length() == 0L) {
                file.delete()
                continue
            }
            val rid = o.optInt("rid", 0)
            val name = o.optString("name").ifBlank { ctx.getString(R.string.recording) }
            val adts = moveOut(file, owner.recordingDir) ?: continue
            worker.execute {
                val duration = AudioFiles.durationOf(adts)
                main.post {
                    if (duration == null || duration <= 0) {
                        adts.delete()
                        return@post
                    }
                    val audio = AudioData(adts, "aac", duration, ByteArray(0))
                    val item = owner.placeRecording(audio, name, rid) ?: return@post
                    com.xnotes.platform.AudioPeaks.readLater(adts, duration) { peaks ->
                        main.post {
                            if (item.audio.file === adts || item.audio.peaks.isEmpty()) {
                                owner.recordingMoved(item, item.audio.withPeaks(peaks ?: ByteArray(AudioData.PEAK_BINS) { 40 }))
                            }
                        }
                    }
                    toM4a(owner, item, adts)
                }
            }
        }
    }

    private const val SIDECAR = ".json"

    private fun sidecarOf(file: File) = File(file.parentFile, file.nameWithoutExtension + SIDECAR)

    /** What finds the recording again if the app dies under it: its note, its id, its name. */
    private fun writeSidecar(s: Session, key: String) {
        val o = org.json.JSONObject()
            .put("key", key)
            .put("file", s.file.name)
            .put("rid", s.rid)
            .put("name", s.name)
        runCatching { sidecarOf(s.file).writeText(o.toString()) }
    }

    private fun peaksOf(s: Session): ByteArray {
        val n = s.levelCount
        if (n == 0) return ByteArray(AudioData.PEAK_BINS)
        val top = (0 until n).maxOf { s.levels[it] }
        val shaped = FloatArray(n) { if (top > 0f) sqrt(s.levels[it] / top) else 0f }
        return AudioData.peaksOf(shaped, n)
    }

    private fun dropDiscarded() {
        main.removeCallbacks(forget)
        discarded?.let { dropFile(it) }
        discarded = null
    }

    private fun dropFile(s: Session) {
        s.file.delete()
        sidecarOf(s.file).delete()
    }

    private fun notifyChange() {
        val ctx = app ?: return
        notice()?.let { RecordingService.update(ctx, it) }
    }

    private fun lowOnSpace(): Boolean = runCatching {
        StatFs((app ?: return false).filesDir.path).availableBytes < MIN_FREE_BYTES
    }.getOrDefault(false)

    private fun defaultName(context: Context, at: Long): String {
        val pattern = android.text.format.DateFormat.getBestDateTimePattern(java.util.Locale.getDefault(), "dMMMjjmm")
        return android.text.format.DateFormat.format(pattern, at).toString()
            .ifBlank { context.getString(R.string.recording) }
    }
}
