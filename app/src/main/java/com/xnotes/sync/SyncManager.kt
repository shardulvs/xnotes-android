package com.xnotes.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.xnotes.platform.AppStorageDocumentsProvider
import com.xnotes.settings.LiveSettings
import com.xnotes.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Entry point the UI and the background worker share. Only the service picked in preferences
 * ([com.xnotes.settings.Preferences.cloudService]) syncs: this resolves it, runs the engine under a
 * lock, and (re)schedules the one periodic WorkManager job. Other services stay signed in but idle.
 */
object SyncManager {
    private const val WORK_NAME = "cloud_periodic_sync"
    /** The job name from when Filen was the only service; its worker class no longer exists. */
    private const val LEGACY_WORK_NAME = "filen_periodic_sync"
    private val syncMutex = Mutex()
    private val exitSyncScope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
    private val backgroundQueued = AtomicBoolean(false)
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

    suspend fun syncNow(context: Context): Result<SyncEngine.Summary> = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            backgroundQueued.set(false) // this pass covers anything queued before it started
            primeStatus(context)
            val service = activeService(context)
            _status.value = _status.value.copy(running = true, message = "Syncing", done = 0, total = 0)
            val result = runCatching {
                val prefs = settings(context).prefs
                require(service != null) { "No cloud service selected" }
                require(prefs.syncDirection != "off") { "Sync direction is off" }
                val folderId = folderId(context, service)
                require(folderId.isNotEmpty()) { "No ${service.displayName} folder selected" }
                val treeUri = effectiveTreeUri(context)
                val storage = service.storage(context)
                val state = SyncState.load(context, service.id)
                val up = prefs.syncDirection == "both" || prefs.syncDirection == "up"
                val down = prefs.syncDirection == "both" || prefs.syncDirection == "down"
                val summary = SyncEngine(context, treeUri, storage, folderId, up, down) { done, total ->
                    _status.value = _status.value.copy(done = done, total = total)
                }.sync(state)
                runCatching { SettingsSync.sync(context, storage, folderId, state) }
                state.save(context, service.id)
                summary to state.filePaths().count { !it.startsWith(".") }
            }
            val newConflicts = result.getOrNull()?.first?.conflictNotes
                ?.map { "${it.path.substringAfterLast('/')}: ${it.message}" } ?: emptyList()
            val status = Status(
                running = false,
                lastSyncMs = System.currentTimeMillis(),
                fileCount = result.getOrNull()?.second ?: _status.value.fileCount,
                message = result.fold(
                    { (s, _) ->
                        "Up ${s.uploaded}, down ${s.downloaded}" +
                            (if (s.deleted > 0) ", ${s.deleted} deleted" else "") +
                            (if (s.conflicts > 0) ", ${s.conflicts} conflicts" else "") +
                            (if (s.errors.isNotEmpty()) ", ${s.errors.size} errors" else "")
                    },
                    { e -> "Failed: ${e.message}" },
                ),
                // Accumulate conflicts until the user dismisses the banner, so a background sync's
                // heads-up is not lost before it is seen.
                conflicts = (_status.value.conflicts + newConflicts).distinct().takeLast(50),
            )
            _status.value = status
            if (result.isSuccess && service != null) persistStatus(context, service.id, status)
            result.map { it.first }
        }
    }

    /** Fire-and-forget sync when a note is closed, if sync on note close is on. */
    fun syncOnNoteExit(context: Context) {
        if (settings(context.applicationContext).prefs.syncOnNoteExit) syncInBackground(context)
    }

    /** Fire-and-forget sync when the app comes to the foreground, if sync on app open is on. */
    fun syncOnAppOpen(context: Context) {
        val appCtx = context.applicationContext
        // Off the main thread: on a cold start this is the first settings load and session decrypt.
        exitSyncScope.launch {
            if (!settings(appCtx).prefs.syncOnAppOpen) return@launch
            primeStatus(appCtx)
            // Coming back from a file picker or share sheet also starts the activity; skip if we just synced.
            if (System.currentTimeMillis() - _status.value.lastSyncMs < APP_OPEN_MIN_GAP_MS) return@launch
            syncInBackground(appCtx)
        }
    }

    private fun syncInBackground(context: Context) {
        val appCtx = context.applicationContext
        val prefs = settings(appCtx).prefs
        if (prefs.syncDirection == "off" || !isConfigured(appCtx)) return
        if (prefs.syncWifiOnly && !isUnmetered(appCtx)) return
        // A sync already running may have passed the just-saved note, so queue one pass after it (at most one waits).
        if (!backgroundQueued.compareAndSet(false, true)) return
        exitSyncScope.launch { runCatching { syncNow(appCtx) } }
    }

    private fun isUnmetered(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /** (Re)schedule or cancel the periodic job to match current preferences and the selected service. */
    fun reschedule(context: Context) {
        val prefs = settings(context).prefs
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(LEGACY_WORK_NAME)
        if (prefs.syncDirection == "off" || !prefs.syncAuto || !isConfigured(context)) {
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
