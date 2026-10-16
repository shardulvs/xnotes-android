package com.xnotes.sync.filen

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
 * Entry point the UI and the background worker share. Owns the session (via
 * [FilenSecureStore]), reads sync config from preferences, runs the engine under a
 * lock, and (re)schedules the periodic WorkManager job.
 */
object FilenSyncManager {
    private const val WORK_NAME = "filen_periodic_sync"
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
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    /** Seed the status flow from the last persisted sync (for the sidebar on a fresh launch). */
    fun primeStatus(context: Context) {
        if (_status.value.lastSyncMs != 0L || _status.value.running) return
        runCatching {
            val f = statusFile(context)
            if (f.exists()) {
                val o = org.json.JSONObject(f.readText())
                val arr = o.optJSONArray("conflicts")
                val conflicts = if (arr != null) (0 until arr.length()).map { arr.getString(it) } else emptyList()
                _status.value = Status(lastSyncMs = o.optLong("lastSyncMs"), fileCount = o.optInt("fileCount"), conflicts = conflicts)
            }
        }
    }

    /** Dismiss the conflict banner (the notes were reviewed). */
    fun clearConflicts(context: Context) {
        _status.value = _status.value.copy(conflicts = emptyList())
        persistStatus(context, _status.value)
    }

    private fun statusFile(context: Context) =
        java.io.File(java.io.File(context.filesDir, "config").apply { mkdirs() }, "filen_status.json")

    private fun persistStatus(context: Context, s: Status) {
        runCatching {
            statusFile(context).writeText(
                org.json.JSONObject()
                    .put("lastSyncMs", s.lastSyncMs)
                    .put("fileCount", s.fileCount)
                    .put("conflicts", org.json.JSONArray(s.conflicts))
                    .toString(),
            )
        }
    }

    fun session(context: Context): FilenSession? = FilenSecureStore(context).load()

    fun isConfigured(context: Context): Boolean {
        val prefs = settings(context).prefs
        return session(context) != null && prefs.filenFolderUuid.isNotEmpty()
    }

    suspend fun login(context: Context, email: String, password: String, twoFactorCode: String): Result<FilenSession> =
        withContext(Dispatchers.IO) {
            runCatching {
                val session = FilenClient.login(FilenApi(), email.trim(), password, twoFactorCode)
                FilenSecureStore(context).save(session)
                session
            }
        }

    fun logout(context: Context) {
        FilenSecureStore(context).clear()
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        _status.value = Status()
    }

    suspend fun listFolders(context: Context, parentUuid: String): Result<List<Pair<String, FilenApi.RemoteFolder>>> =
        withContext(Dispatchers.IO) {
            runCatching { clientOrThrow(context).listFolders(parentUuid) }
        }

    suspend fun createFolder(context: Context, parentUuid: String, name: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching { clientOrThrow(context).createFolder(parentUuid, name) }
        }

    suspend fun syncNow(context: Context): Result<FilenSyncEngine.Summary> = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            backgroundQueued.set(false) // this pass covers anything queued before it started
            _status.value = _status.value.copy(running = true, message = "Syncing")
            val result = runCatching {
                val prefs = settings(context).prefs
                require(prefs.filenSyncDirection != "off") { "Sync direction is off" }
                val folderUuid = prefs.filenFolderUuid
                require(folderUuid.isNotEmpty()) { "No Filen folder selected" }
                val treeUri = effectiveTreeUri(context)
                val client = clientOrThrow(context)
                val state = FilenSyncState.load(context)
                val up = prefs.filenSyncDirection == "both" || prefs.filenSyncDirection == "up"
                val down = prefs.filenSyncDirection == "both" || prefs.filenSyncDirection == "down"
                val summary = FilenSyncEngine(context, treeUri, client, folderUuid, up, down).sync(state)
                runCatching { FilenSettingsSync.sync(context, client, folderUuid, state) }
                state.save(context)
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
            if (result.isSuccess) persistStatus(context, status)
            result.map { it.first }
        }
    }

    /** Fire-and-forget sync when a note is closed, if sync on note close is on. */
    fun syncOnNoteExit(context: Context) {
        if (settings(context.applicationContext).prefs.filenSyncOnNoteExit) syncInBackground(context)
    }

    /** Fire-and-forget sync when the app comes to the foreground, if sync on app open is on. */
    fun syncOnAppOpen(context: Context) {
        val appCtx = context.applicationContext
        // Off the main thread: on a cold start this is the first settings load and session decrypt.
        exitSyncScope.launch {
            if (!settings(appCtx).prefs.filenSyncOnAppOpen) return@launch
            primeStatus(appCtx)
            // Coming back from a file picker or share sheet also starts the activity; skip if we just synced.
            if (System.currentTimeMillis() - _status.value.lastSyncMs < APP_OPEN_MIN_GAP_MS) return@launch
            syncInBackground(appCtx)
        }
    }

    private fun syncInBackground(context: Context) {
        val appCtx = context.applicationContext
        val prefs = settings(appCtx).prefs
        if (!prefs.filenSyncEnabled || prefs.filenSyncDirection == "off" || !isConfigured(appCtx)) return
        if (prefs.filenWifiOnly && !isUnmetered(appCtx)) return
        // A sync already running may have passed the just-saved note, so queue one pass after it (at most one waits).
        if (!backgroundQueued.compareAndSet(false, true)) return
        exitSyncScope.launch { runCatching { syncNow(appCtx) } }
    }

    private fun isUnmetered(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /** (Re)schedule or cancel the periodic job to match current preferences. */
    fun reschedule(context: Context) {
        val prefs = settings(context).prefs
        val wm = WorkManager.getInstance(context)
        if (!prefs.filenSyncEnabled || prefs.filenSyncDirection == "off" || !prefs.filenAutoSync || !isConfigured(context)) {
            wm.cancelUniqueWork(WORK_NAME)
            return
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (prefs.filenWifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()
        val interval = prefs.filenSyncIntervalMinutes.toLong().coerceAtLeast(15)
        val request = PeriodicWorkRequestBuilder<FilenSyncWorker>(interval, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()
        wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    private fun clientOrThrow(context: Context): FilenClient {
        val session = session(context) ?: throw IllegalStateException("Not signed in to Filen")
        return FilenClient(FilenApi(session.apiKey), session)
    }

    private fun settings(context: Context) = LiveSettings.get(SettingsRepository(context))

    private fun effectiveTreeUri(context: Context): String =
        settings(context).browseRoot ?: AppStorageDocumentsProvider.treeUri(context).toString()
}
