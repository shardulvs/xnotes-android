package com.xnotes.sync

import android.content.Context
import com.xnotes.R
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.workDataOf
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.xnotes.platform.AppStorageDocumentsProvider
import com.xnotes.settings.LiveSettings
import com.xnotes.settings.Preferences
import com.xnotes.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Entry point the UI and the background worker share. Only the service picked in preferences
 * ([com.xnotes.settings.Preferences.cloudService]) syncs: this resolves it, runs the engine under a
 * lock, and (re)schedules the one periodic WorkManager job. Other services stay signed in but idle.
 */
object SyncManager {
    private const val WORK_NAME = "cloud_periodic_sync"
    /** The one-off job note close and app open start; one at a time, kept alive when the app goes to the background. */
    private const val NOW_WORK_NAME = "cloud_sync_now"
    /** App close waits a little: the pause save is still being written, and a picker or share sheet comes back within it. */
    private const val CLOSE_WORK_NAME = "cloud_sync_close"
    private const val CLOSE_DELAY_S = 10L
    private const val CHECKPOINT_MS = 3_000L
    /** The job name from when Filen was the only service; its worker class no longer exists. */
    private const val LEGACY_WORK_NAME = "filen_periodic_sync"
    private val syncMutex = Mutex()
    // One at a time, so an app open always sees the app close that came before it.
    private val exitSyncScope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO.limitedParallelism(1) + kotlinx.coroutines.SupervisorJob())
    /** A trigger came while a sync ran: it may have passed the just-saved note, so one more pass follows it. */
    private val rerun = AtomicBoolean(false)
    /** Just-closed notes (document uris) to sync ahead of the full pass. */
    private val priority = java.util.concurrent.ConcurrentLinkedQueue<String>()
    /** Syncs started and not yet finished, counting one waiting for another to end. */
    private val active = java.util.concurrent.atomic.AtomicInteger(0)
    private const val APP_OPEN_MIN_GAP_MS = 60_000L

    data class Status(
        val running: Boolean = false,
        val lastSyncMs: Long = 0,
        val fileCount: Int = 0,
        val message: String = "",
        /** Notes whose two devices diverged on the last sync, for the explorer's heads-up banner. */
        val conflicts: List<String> = emptyList(),
        /** Files reconciled so far out of [total] while [running]. */
        val done: Int = 0,
        val total: Int = 0,
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    /** The service [status] belongs to, so switching services shows that service's own last sync. */
    @Volatile private var statusFor: String? = null

    fun activeService(context: Context): CloudService? = CloudServices.byId(settings(context).prefs.cloudService)

    /** Seed the status flow from the active service's last persisted sync (for the sidebar on a fresh launch). */
    fun primeStatus(context: Context) {
        val id = settings(context).prefs.cloudService
        if (statusFor == id) return
        statusFor = id
        _status.value = runCatching {
            val f = statusFile(context, id)
            if (!f.exists()) return@runCatching Status()
            val o = org.json.JSONObject(f.readText())
            val arr = o.optJSONArray("conflicts")
            val conflicts = if (arr != null) (0 until arr.length()).map { arr.getString(it) } else emptyList()
            Status(lastSyncMs = o.optLong("lastSyncMs"), fileCount = o.optInt("fileCount"), conflicts = conflicts)
        }.getOrDefault(Status())
    }

    /** Dismiss the conflict banner (the notes were reviewed). */
    fun clearConflicts(context: Context) {
        _status.value = _status.value.copy(conflicts = emptyList())
        statusFor?.let { persistStatus(context, it, _status.value) }
    }

    private fun statusFile(context: Context, serviceId: String) =
        java.io.File(java.io.File(context.filesDir, "config").apply { mkdirs() }, "${serviceId}_status.json")

    private fun persistStatus(context: Context, serviceId: String, s: Status) {
        runCatching {
            statusFile(context, serviceId).writeText(
                org.json.JSONObject()
                    .put("lastSyncMs", s.lastSyncMs)
                    .put("fileCount", s.fileCount)
                    .put("conflicts", org.json.JSONArray(s.conflicts))
                    .toString(),
            )
        }
    }

    fun isConfigured(context: Context): Boolean {
        val service = activeService(context) ?: return false
        return service.account(context) != null && folderId(context, service).isNotEmpty()
    }

    fun signOut(context: Context, service: CloudService) {
        service.signOut(context)
        if (activeService(context) == service) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            _status.value = Status()
        }
    }

    suspend fun listFolders(context: Context, service: CloudService, parentId: String): Result<List<CloudStorage.RemoteFolder>> =
        withContext(Dispatchers.IO) {
            runCatching { service.storage(context).list(parentId).folders }
        }

    suspend fun createFolder(context: Context, service: CloudService, parentId: String, name: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching { service.storage(context).createFolder(parentId, name) }
        }

    suspend fun syncNow(context: Context, trigger: String = "manual"): Result<SyncEngine.Summary> = withContext(Dispatchers.IO) {
        active.incrementAndGet()
        try {
            var result = syncPass(context, trigger)
            // A trigger muted during the pass is answered here, by the same job, which stays alive while it runs.
            while (rerun.getAndSet(false) || priority.isNotEmpty()) result = syncPass(context, "rerun")
            result
        } finally {
            active.decrementAndGet()
        }
    }

    private suspend fun syncPass(context: Context, trigger: String): Result<SyncEngine.Summary> =
        syncMutex.withLock {
            rerun.set(false) // this pass covers any trigger that came before it started
            SyncLog.trim(context)
            primeStatus(context)
            val service = activeService(context)
            _status.value = _status.value.copy(running = true, message = context.getString(R.string.sync_syncing), done = 0, total = 0)
            SyncNotifications.showProgress(context, 0, 0)
            var lastNotify = 0L
            val started = System.currentTimeMillis()
            val log = mutableListOf("${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(started))}  Started ($trigger)")
            fun flushLog() = synchronized(log) { SyncLog.append(context, log.toList()); log.clear() }
            val result = runCatching {
                val prefs = settings(context).prefs
                require(service != null) { context.getString(R.string.sync_err_no_service) }
                require(prefs.syncDirection != "off") { context.getString(R.string.sync_err_direction_off) }
                val folderId = folderId(context, service)
                require(folderId.isNotEmpty()) { context.getString(R.string.sync_err_no_folder, service.displayName) }
                val treeUri = effectiveTreeUri(context)
                val storage = service.storage(context)
                val state = SyncState.load(context, service.id)
                val up = prefs.syncDirection == "both" || prefs.syncDirection == "up"
                val down = prefs.syncDirection == "both" || prefs.syncDirection == "down"
                var lastSave = System.currentTimeMillis()
                fun checkpoint() {
                    state.save(context, service.id)
                    flushLog()
                    lastSave = System.currentTimeMillis()
                }
                val engine = SyncEngine(
                    context, treeUri, storage, folderId, up, down,
                    onProgress = { done, total ->
                        _status.value = _status.value.copy(done = done, total = total)
                        // Android drops notification updates that come faster than a few per second.
                        if (System.currentTimeMillis() - lastNotify > 1_000) {
                            lastNotify = System.currentTimeMillis()
                            SyncNotifications.showProgress(context, done, total)
                        }
                        // Saved as it goes, so a run cut short (app killed, network gone) does not redo or conflict with its own uploads.
                        if (System.currentTimeMillis() - lastSave > CHECKPOINT_MS) checkpoint()
                    },
                    log = { line -> synchronized(log) { log.add("  $line") } },
                    takePriority = { generateSequence { priority.poll() }.firstNotNullOfOrNull { LocalNoteStore.relativePathOf(treeUri, it) } },
                    checkpoint = ::checkpoint,
                )
                val summary = engine.sync(state)
                runCatching { SettingsSync.sync(context, storage, folderId, state, engine.localUris) }
                    .onFailure { synchronized(log) { log.add("  Error settings: ${it.message}") } }
                state.save(context, service.id)
                summary to state.filePaths().count { !LocalNoteStore.isHiddenPath(it) }
            }
            val newConflicts = result.getOrNull()?.first?.conflictNotes
                ?.map { "${it.path.substringAfterLast('/')}: ${it.message}" } ?: emptyList()
            val status = Status(
                running = false,
                lastSyncMs = System.currentTimeMillis(),
                fileCount = result.getOrNull()?.second ?: _status.value.fileCount,
                message = result.fold(
                    { (s, _) ->
                        context.getString(R.string.sync_status_summary, s.uploaded, s.downloaded) +
                            (if (s.deleted > 0) context.getString(R.string.sync_status_deleted, s.deleted) else "") +
                            (if (s.conflicts > 0) context.getString(R.string.sync_status_conflicts, s.conflicts) else "") +
                            (if (s.errors.isNotEmpty()) context.getString(R.string.sync_status_errors, s.errors.size) else "")
                    },
                    { e -> context.getString(R.string.failed_reason, e.message) },
                ),
                // Accumulate conflicts until the user dismisses the banner, so a background sync's
                // heads-up is not lost before it is seen.
                conflicts = (_status.value.conflicts + newConflicts).distinct().takeLast(50),
            )
            _status.value = status
            if (result.isSuccess && service != null) persistStatus(context, service.id, status)
            val seconds = "%.1f s".format(Locale.US, (System.currentTimeMillis() - started) / 1000.0)
            synchronized(log) {
                log.add(result.fold(
                    { (s, _) -> if (s.errors.isEmpty()) "  OK in $seconds: ${status.message}" else "  FAILED in $seconds: ${status.message}" },
                    { e -> "  FAILED in $seconds: ${e.message}" },
                ))
            }
            flushLog()
            val failed = result.fold({ (s, _) -> s.errors.isNotEmpty() }, { true })
            SyncNotifications.finish(context, if (failed) status.message else null)
            result.map { it.first }
        }

    /** Fire-and-forget sync when the note at [uri] is closed, if sync on note close is on; that note goes first. */
    fun syncOnNoteExit(context: Context, uri: String?) {
        val appCtx = context.applicationContext
        if (!settings(appCtx).prefs.syncOnNoteExit) return
        val first = uri != null && LocalNoteStore.relativePathOf(effectiveTreeUri(appCtx), uri) != null
        if (first) priority.add(uri)
        requestSync(appCtx, "note close", rerunIfBusy = !first)
    }

    /** Fire-and-forget sync when the app comes to the foreground, if sync on app open is on. */
    fun syncOnAppOpen(context: Context) = syncOnAppSwitch(context, closing = false) { it.syncOnAppOpen }

    /** Fire-and-forget sync shortly after the app goes to the background, if sync on app close is on. */
    fun syncOnAppClose(context: Context) = syncOnAppSwitch(context, closing = true) { it.syncOnAppClose }

    private fun syncOnAppSwitch(context: Context, closing: Boolean, enabled: (Preferences) -> Boolean) {
        val appCtx = context.applicationContext
        // Off the main thread: on a cold start this is the first settings load and session decrypt.
        exitSyncScope.launch {
            // Back within the close delay (a picker, a share sheet): that close sync is not needed.
            if (!closing) cancelPendingClose(appCtx)
            if (!enabled(settings(appCtx).prefs)) return@launch
            primeStatus(appCtx)
            if (closing) { requestSync(appCtx, "app close", delaySeconds = CLOSE_DELAY_S); return@launch }
            // A file picker or share sheet also stops and starts the activity; skip if we just synced.
            if (System.currentTimeMillis() - _status.value.lastSyncMs < APP_OPEN_MIN_GAP_MS) return@launch
            // A sync already running started moments ago and covers what this one would do, so this one is dropped.
            requestSync(appCtx, "app open", rerunIfBusy = false)
        }
    }

    private fun cancelPendingClose(context: Context) {
        val wm = WorkManager.getInstance(context)
        // Only one still waiting: a close sync already running is left to finish.
        if (wm.getWorkInfosForUniqueWork(CLOSE_WORK_NAME).get().any { it.state == WorkInfo.State.ENQUEUED }) {
            wm.cancelUniqueWork(CLOSE_WORK_NAME)
        }
    }

    /**
     * Starts a sync as an expedited background job, which carries on after the app is left (an in-app
     * coroutine is frozen with the app). Muted while a sync runs: that one does a single pass more instead.
     */
    private fun requestSync(context: Context, trigger: String, rerunIfBusy: Boolean = true, delaySeconds: Long = 0) {
        val appCtx = context.applicationContext
        val prefs = settings(appCtx).prefs
        if (prefs.syncDirection == "off" || !isConfigured(appCtx)) return
        // A queued priority note is picked up by the running sync itself, so only other triggers need a whole pass more.
        if (active.get() > 0) { if (rerunIfBusy) rerun.set(true); return }
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(if (prefs.syncWifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
            .setInputData(workDataOf(SyncWorker.TRIGGER to trigger))
            // Expedited work cannot be delayed.
            .apply { if (delaySeconds > 0) setInitialDelay(delaySeconds, TimeUnit.SECONDS) else setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST) }
            .build()
        val wm = WorkManager.getInstance(appCtx)
        if (delaySeconds > 0) wm.enqueueUniqueWork(CLOSE_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        else wm.enqueueUniqueWork(NOW_WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    /** Whether a sync is running now, so the periodic job can skip its turn. */
    val isRunning: Boolean get() = active.get() > 0

    /** The notification an expedited sync shows on Android 11 and older, where it runs as a foreground service. */
    fun foregroundInfo(context: Context): androidx.work.ForegroundInfo =
        androidx.work.ForegroundInfo(SyncNotifications.PROGRESS_ID, SyncNotifications.progress(context, 0, 0))

    /** (Re)schedule or cancel the periodic job to match current preferences and the selected service. */
    fun reschedule(context: Context) {
        val prefs = settings(context).prefs
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(LEGACY_WORK_NAME)
        if (prefs.syncDirection == "off" || prefs.syncIntervalMinutes == 0 || !isConfigured(context)) {
            wm.cancelUniqueWork(WORK_NAME)
            return
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (prefs.syncWifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()
        val interval = prefs.syncIntervalMinutes.toLong().coerceAtLeast(15)
        val request = PeriodicWorkRequestBuilder<SyncWorker>(interval, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()
        wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun folderId(context: Context, service: CloudService): String =
        settings(context).prefs.cloudFolders[service.id]?.id.orEmpty()

    private fun settings(context: Context) = LiveSettings.get(SettingsRepository(context))

    private fun effectiveTreeUri(context: Context): String =
        settings(context).browseRoot ?: AppStorageDocumentsProvider.treeUri(context).toString()
}
