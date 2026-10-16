package com.xnotes.sync.filen

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/** Periodic background sync. Retries (with backoff) on failure so a flaky network recovers. */
class FilenSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!FilenSyncManager.isConfigured(applicationContext)) return Result.success()
        return FilenSyncManager.syncNow(applicationContext).fold(
            { Result.success() },
            { Result.retry() },
        )
    }
}
