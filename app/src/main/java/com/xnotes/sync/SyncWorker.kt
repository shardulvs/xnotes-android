package com.xnotes.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/** Periodic background sync with the selected service. Retries (with backoff) on failure so a flaky network recovers. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!SyncManager.isConfigured(applicationContext)) return Result.success()
        return SyncManager.syncNow(applicationContext).fold(
            { Result.success() },
            { Result.retry() },
        )
    }
}
