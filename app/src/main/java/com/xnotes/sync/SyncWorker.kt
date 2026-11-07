package com.xnotes.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters

/**
 * Background sync with the selected service: the periodic job, and the one-off job note close and app open
 * start. The periodic one retries (with backoff) on failure so a flaky network recovers.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!SyncManager.isConfigured(applicationContext)) return Result.success()
        val trigger = inputData.getString(TRIGGER)
        if (trigger == null && SyncManager.isRunning) return Result.success() // the periodic turn is muted while one runs
        return SyncManager.syncNow(applicationContext, trigger ?: "automatic").fold(
            { Result.success() },
            // Only the periodic job backs off and retries: a one-off one waiting to retry would mute every later trigger.
            { if (trigger == null) Result.retry() else Result.success() },
        )
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = SyncManager.foregroundInfo(applicationContext)

    companion object {
        const val TRIGGER = "trigger"
    }
}
