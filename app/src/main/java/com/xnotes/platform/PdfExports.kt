package com.xnotes.platform

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.xnotes.R
import com.xnotes.core.util.DocumentKind
import com.xnotes.core.util.StagedCopy
import com.xnotes.core.util.copyOver
import com.xnotes.format.FileWindow
import com.xnotes.format.XnotesPdf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock

/**
 * The exports that bring an xnotes PDF's pages up to its note, for the whole process.
 *
 * A save only appends the newer note to the PDF ([XnotesPdf]); its pages are drawn again here, once
 * the note is closed or the app is left with it open. One export runs at a time, off the main
 * thread, and a foreground service keeps the process alive for it ([PdfExportService]), which
 * Android only lets start while the app is on screen or just leaving it.
 *
 * The file is the only thing an export reads: the note is taken out of it under the file's lock,
 * exported to private storage, and copied back under that lock, unless the file was saved again
 * meanwhile or its note came back on screen. Only that last copy cannot be stopped, and the
 * finished PDF is kept until the copy is seen whole, so a copy cut short is finished at the next
 * launch ([StagedCopy]).
 */
object PdfExports {

    /** What draws a note's pages: the editor's export, which knows the theme, the fonts and the templates. */
    fun interface Exporter {
        /**
         * Write the bundle [note] as the xnotes PDF named [pdfName] into [to], unpacking what it
         * needs into [temp]. [onProgress] takes how far along it is, from 0 to 1. False when
         * [isCancelled] stopped it.
         */
        fun export(note: File, pdfName: String, to: File, temp: File, onProgress: (Float) -> Unit, isCancelled: () -> Boolean): Boolean
    }

    /** Told when a commit has put a whole new file in place, so what was known of the old one is let go. */
    fun interface Listener {
        /** The file at [uri], last modified at [before], was replaced and is now modified at [after]. The file's lock is held. */
        fun onReplaced(uri: String, name: String, before: Long, after: Long)
    }

    /** How long an export holds off after the app is left with a note open, in case it is only a picker in front. */
    const val LEAVE_HOLD_MS = 5_000L

    private const val CHANNEL = "pdf_export"
    private const val NOTIFICATION_ID = 42
    private const val COPY_BUFFER = 1024 * 1024
    private const val STAGED_MAX_AGE_MS = 14L * 24 * 60 * 60 * 1000

    private lateinit var app: Context
    private lateinit var store: PdfExportStore
    @Volatile private var ready = false

    @Volatile var exporter: Exporter? = null

    private val listeners = CopyOnWriteArraySet<Listener>()
    private val locks = ConcurrentHashMap<String, ReentrantLock>()

    /** The documents open on screen, by key: none of these is exported. */
    private val held: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** When each document's export may start at the earliest, by key, on the elapsed-time clock. */
    private val notBefore = ConcurrentHashMap<String, Long>()

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "pdf-export").apply { isDaemon = true; priority = Thread.MIN_PRIORITY + 1 }
    }
    private val tasks = AtomicInteger(0)

    /** Held for the length of one export: the worker's, or one asked for here and now. */
    private val oneAtATime = ReentrantLock()

    private class Job(val key: String, val name: String) {
        val cancel = AtomicBoolean(false)
    }

    @Volatile private var current: Job? = null

    @Volatile private var service: PdfExportService? = null
    @Volatile private var starting = false

    private val running = MutableStateFlow<Map<String, Float>>(emptyMap())

    /** How far along each running export is, from 0 to 1, by document key: what a tile's ring shows. */
    val progress: StateFlow<Map<String, Float>> = running

    fun init(context: Context) {
        if (ready) return
        synchronized(this) {
            if (ready) return
            app = context.applicationContext
            store = PdfExportStore.of(app)
            ready = true
        }
    }

    fun listen(listener: Listener) { listeners.add(listener) }

    fun forget(listener: Listener) { listeners.remove(listener) }

    /** A document's identity: its provider and document id, so one file reached by two uris is one document. */
    fun keyOf(uri: String): String = runCatching {
        val u = Uri.parse(uri)
        "${u.authority}|${DocumentsContract.getDocumentId(u)}"
    }.getOrDefault(uri)

    /** Run [block] holding the lock of the file at [uri]. Everything that writes a PDF, or reads one whole, does. */
    fun <T> locked(uri: String, block: () -> T): T {
        val lock = locks.getOrPut(keyOf(uri)) { ReentrantLock() }
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    // --- what waits ---

    /** The pages of the PDF at [uri] are behind its note from here on. */
    fun stale(uri: String, name: String) {
        if (ready) store.wait(keyOf(uri), uri, name)
    }

    /** The PDF at [uri] is whole again, or is not one to export. */
    fun fresh(uri: String) {
        if (ready) store.done(keyOf(uri))
    }

    fun isWaiting(uri: String): Boolean = ready && store.get(keyOf(uri))?.waits == true

    /** The file at [uri] is gone. */
    fun gone(uri: String) {
        if (!ready) return
        val key = keyOf(uri)
        hold(uri) // drop an export of it that is running
        held.remove(key)
        store.get(key)?.staged?.let { File(commitDir(), it.file).delete() }
        store.remove(key)
    }

    /** The file at [oldUri] is at [newUri] now, named [newName]. */
    fun moved(oldUri: String, newUri: String, newName: String) {
        if (!ready) return
        val old = keyOf(oldUri)
        val new = keyOf(newUri)
        current?.takeIf { it.key == old }?.cancel?.set(true) // it would copy over a file that is no longer there
        if (held.remove(old)) held.add(new)
        notBefore.remove(old)?.let { notBefore[new] = it }
        store.move(old, new, newUri, newName)
    }

    // --- open notes ---

    /** The note at [uri] is on screen: its export is put off, and one that is running is dropped. */
    fun hold(uri: String) {
        val key = keyOf(uri)
        held.add(key)
        notBefore.remove(key)
        current?.takeIf { it.key == key }?.cancel?.set(true)
    }

    /** The note at [uri] is off the screen. Its export may start, once [afterMs] have passed. */
    fun release(uri: String, afterMs: Long = 0L) {
        val key = keyOf(uri)
        held.remove(key)
        if (afterMs > 0L) notBefore[key] = SystemClock.elapsedRealtime() + afterMs else notBefore.remove(key)
    }

    // --- running ---

    /**
     * Start on whatever waits. To be called while a foreground service may still be started: with
     * the app on screen, or as it leaves. [expecting] starts the service even with nothing waiting
     * yet, for a save still going out that is about to leave pages behind.
     */
    fun kick(expecting: Boolean = false) {
        if (!ready) return
        if (!expecting && store.all().none { it.waits && it.key !in held }) return
        startService()
        submit {
            if (expecting) nap(LEAVE_HOLD_MS)
            drain()
        }
    }

    /** At launch: finish the copies the last run left unfinished, then start on what waits. */
    fun resume() {
        if (!ready) return
        submit { recover() }
        kick()
    }

    private fun submit(task: () -> Unit) {
        tasks.incrementAndGet()
        worker.execute {
            try {
                runCatching(task)
            } finally {
                if (tasks.decrementAndGet() == 0) service?.finish()
            }
        }
    }

    private fun nap(ms: Long) {
        runCatching { Thread.sleep(ms) }
    }

    private fun drain() {
        val tried = HashSet<String>()
        while (true) {
            val now = SystemClock.elapsedRealtime()
            val waiting = store.all().filter { it.waits && it.key !in held && it.key !in tried }
            if (waiting.isEmpty()) return
            val due = waiting.firstOrNull { (notBefore[it.key] ?: 0L) <= now }
            if (due == null) {
                // All that is left was let go a moment ago, with the app: napped through in short
                // turns, so a note that comes back on screen is seen to have.
                nap(250L)
                continue
            }
            tried.add(due.key)
            run(due)
        }
    }

    /** What the file was when its note was taken out for an export. */
    private class Taken(val size: Long, val end: String)

    /**
     * Export the PDF at [uri], named [name], here and now on the caller's thread, for something
     * that needs its pages level with its note before going on: sharing it. An export of it that
     * is already running is waited for. True when the pages are level afterwards.
     */
    fun exportNow(uri: String, name: String, onProgress: (Float) -> Unit = {}, isCancelled: () -> Boolean = { false }): Boolean {
        if (!ready) return false
        val key = keyOf(uri)
        store.wait(key, uri, name)
        oneAtATime.lock()
        try {
            val entry = store.get(key)?.takeIf { it.waits } ?: return true // drawn while this waited its turn
            export(entry, onProgress, isCancelled)
            return store.get(key)?.waits != true
        } finally {
            oneAtATime.unlock()
        }
    }

    private fun run(entry: PdfExportStore.Entry) {
        oneAtATime.lock()
        try {
            if (store.get(entry.key)?.waits == true) export(entry, {}, { false })
        } finally {
            oneAtATime.unlock()
        }
    }

    /** One export, start to finish; [oneAtATime] is held. */
    private fun export(entry: PdfExportStore.Entry, onProgress: (Float) -> Unit, isCancelled: () -> Boolean) {
        val exporter = exporter ?: return
        val job = Job(entry.key, entry.name)
        current = job
        val dir = File(app.filesDir, "pdfexport")
        try {
            dir.deleteRecursively()
            val temp = File(dir, "temp")
            if (!temp.mkdirs()) return
            val note = File(dir, "note.xnote")
            val out = File(dir, "out.pdf")
            val stopped = { job.cancel.get() || entry.key in held || isCancelled() }
            if (stopped()) return
            val taken = locked(entry.uri) { takeNote(entry, note) } ?: return
            val began = SystemClock.elapsedRealtime()
            show(entry.key, 0f)
            val whole = runCatching {
                exporter.export(note, entry.name, out, temp, { show(entry.key, it); onProgress(it) }, stopped)
            }.getOrDefault(false)
            if (!whole || stopped()) {
                android.util.Log.i("xnotes.save", "pdf export of ${entry.name} ${if (whole) "dropped" else "stopped or failed"} after ${SystemClock.elapsedRealtime() - began}ms")
                return
            }
            locked(entry.uri) {
                // Back on screen, or saved again since: these pages are for a note that has moved on.
                val outcome = when {
                    stopped() || !isStill(entry.uri, taken) -> "dropped, the note moved on"
                    replace(entry.uri, entry.name, out) -> "in place".also { store.done(entry.key) }
                    else -> "not copied"
                }
                android.util.Log.i("xnotes.save", "pdf export of ${entry.name} $outcome after ${SystemClock.elapsedRealtime() - began}ms")
            }
        } finally {
            current = null
            hide(entry.key)
            dir.deleteRecursively()
        }
    }

    /**
     * Copy the note inside [entry]'s file to [to]. Null when there is none to export, and when that
     * is because the file is not ours, or its pages are not behind, it stops waiting.
     */
    private fun takeNote(entry: PdfExportStore.Entry, to: File): Taken? {
        val result = runCatching {
            app.contentResolver.openFileDescriptor(Uri.parse(entry.uri), "r")?.use { pfd ->
                val ch = FileInputStream(pfd.fileDescriptor).channel
                val note = when (val found = XnotesPdf.inspect(ch)) {
                    is XnotesPdf.Inspection.Ours -> if (found.stale) XnotesPdf.embedded(ch, found) else null
                    // A save that never finished: the note under it is exported, which drops the rest.
                    is XnotesPdf.Inspection.Torn -> XnotesPdf.embedded(FileWindow(ch, 0L, found.ownLength), found.own)
                    else -> null
                }
                if (note == null) {
                    store.done(entry.key)
                    return@use null
                }
                FileOutputStream(to).use { out ->
                    var at = 0L
                    while (at < note.size()) {
                        val n = note.transferTo(at, note.size() - at, out.channel)
                        if (n <= 0L) throw java.io.IOException("short read")
                        at += n
                    }
                }
                Taken(ch.size(), StagedCopy.endHash(ch))
            }
        }
        // A file that cannot be opened at all is gone or out of reach: it will not get better by waiting.
        if (result.exceptionOrNull() is java.io.FileNotFoundException) store.remove(entry.key)
        return result.getOrNull()
    }

    private fun isStill(uri: String, taken: Taken): Boolean = runCatching {
        app.contentResolver.openFileDescriptor(Uri.parse(uri), "r")?.use { pfd ->
            val ch = FileInputStream(pfd.fileDescriptor).channel
            ch.size() == taken.size && StagedCopy.endHash(ch) == taken.end
        }
    }.getOrNull() == true

    // --- putting a finished PDF in place ---

    private fun commitDir(): File = File(app.filesDir, "pdfcommit").apply { mkdirs() }

    /**
     * Put [finished], a whole xnotes PDF, in place of the file at [uri], named [name]. The caller
     * holds the file's lock. [finished] is moved into app storage first and kept there until the
     * copy is seen whole, so one cut short is finished at the next launch. False when the copy did
     * not land.
     */
    fun replace(uri: String, name: String, finished: File): Boolean {
        val key = keyOf(uri)
        val before = stampOf(uri)
        val oldEnd = runCatching {
            app.contentResolver.openFileDescriptor(Uri.parse(uri), "r")?.use { StagedCopy.endHash(FileInputStream(it.fileDescriptor).channel) }
        }.getOrNull().orEmpty()
        val staged = File(commitDir(), MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }.take(32) + ".pdf")
        staged.delete()
        if (!finished.renameTo(staged)) {
            finished.copyTo(staged, overwrite = true)
            finished.delete()
        }
        store.stage(key, uri, name, PdfExportStore.Staged(staged.name, before.first, oldEnd))
        if (!copyIn(uri, staged)) return false
        store.unstage(key)
        staged.delete()
        replaced(uri, name, before.second)
        return true
    }

    private fun copyIn(uri: String, staged: File): Boolean = runCatching {
        copyOver(staged, COPY_BUFFER) { app.contentResolver.openOutputStream(Uri.parse(uri), "wt") } &&
            app.contentResolver.openFileDescriptor(Uri.parse(uri), "r")?.use { FileInputStream(it.fileDescriptor).channel.size() } == staged.length()
    }.getOrDefault(false)

    private fun replaced(uri: String, name: String, before: Long) {
        val after = stampOf(uri).second
        for (l in listeners) runCatching { l.onReplaced(uri, name, before, after) }
    }

    /** The size and modified time the provider reports for [uri], -1 for either it will not say. */
    private fun stampOf(uri: String): Pair<Long, Long> = runCatching {
        app.contentResolver.query(
            Uri.parse(uri),
            arrayOf(OpenableColumns.SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED),
            null, null, null,
        )?.use { c ->
            if (!c.moveToFirst()) null else (if (c.isNull(0)) -1L else c.getLong(0)) to (if (c.isNull(1)) -1L else c.getLong(1))
        }
    }.getOrNull() ?: (-1L to -1L)

    /** Finish, or let go of, the copies that were under way when the app last stopped. */
    private fun recover() {
        val dir = commitDir()
        for (entry in store.all()) {
            val staged = entry.staged ?: continue
            val file = File(dir, staged.file)
            if (!file.exists()) {
                store.unstage(entry.key)
                continue
            }
            locked(entry.uri) {
                val state = runCatching {
                    app.contentResolver.openFileDescriptor(Uri.parse(entry.uri), "r")?.use { pfd ->
                        FileInputStream(file).use { StagedCopy.of(FileInputStream(pfd.fileDescriptor).channel, it.channel, staged.oldSize, staged.oldEnd) }
                    }
                }.getOrNull()
                val settled = when (state) {
                    StagedCopy.DONE -> true
                    StagedCopy.REDO -> copyIn(entry.uri, file)
                    StagedCopy.LEAVE -> false
                    // The file could not be read just now: the copy is kept for another launch, for a while.
                    null -> if (System.currentTimeMillis() - file.lastModified() < STAGED_MAX_AGE_MS) return@locked else false
                }
                store.unstage(entry.key)
                file.delete()
                if (settled) replaced(entry.uri, entry.name, -1L)
            }
        }
        val kept = store.all().mapNotNull { it.staged?.file }.toSet()
        dir.listFiles()?.forEach { if (it.name !in kept) it.delete() }
    }

    // --- progress and the service ---

    private fun show(key: String, fraction: Float) {
        val f = fraction.coerceIn(0f, 1f)
        val old = running.value[key]
        if (old != null && f < 1f && kotlin.math.abs(f - old) < 0.01f) return
        running.value = running.value + (key to f)
        service?.let { s -> runCatching { app.getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(s)) } }
    }

    private fun hide(key: String) {
        running.value = running.value - key
    }

    private fun startService() {
        if (service != null || starting) return
        starting = true
        // Refused once the app has been off screen for a few seconds. The work goes on without it then.
        runCatching { ContextCompat.startForegroundService(app, Intent(app, PdfExportService::class.java)) }
            .onFailure { starting = false }
    }

    internal fun attached(s: PdfExportService) {
        service = s
        starting = false
        if (tasks.get() == 0) s.finish()
    }

    internal fun detached(s: PdfExportService) {
        if (service === s) service = null
        starting = false
    }

    internal fun notification(context: Context): Notification {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.pdf_export_channel), NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) },
            )
        }
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.let { PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) }
        val job = current
        val fraction = job?.let { running.value[it.key] }
        val b = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification_pdf)
            .setContentTitle(context.getString(R.string.pdf_export_saving))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(open)
        if (job != null) b.setContentText(DocumentKind.stripSuffix(job.name))
        if (fraction != null) b.setProgress(1000, (fraction * 1000).toInt(), false) else b.setProgress(0, 0, true)
        return b.build()
    }
}
