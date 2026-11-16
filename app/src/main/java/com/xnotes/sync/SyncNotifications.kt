package com.xnotes.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import com.xnotes.R

/** The ongoing notification while a sync runs, and the one left behind when a sync fails. */
object SyncNotifications {
    private const val PROGRESS_CHANNEL = "cloud_sync"
    private const val ERROR_CHANNEL = "cloud_sync_errors"
    /** Shared with the foreground service an expedited sync runs as on Android 11 and older, so that one is updated in place. */
    const val PROGRESS_ID = 4711
    private const val ERROR_ID = 4712

    /** [total] 0 means the files are still being listed, so the bar has no end yet. */
    fun progress(context: Context, done: Int, total: Int): Notification {
        ensureChannels(context)
        return NotificationCompat.Builder(context, PROGRESS_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(context.getString(R.string.sync_notification_title))
            .apply { if (total > 0) setContentText("$done/$total") }
            .setProgress(total, done, total == 0)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(context))
            .build()
    }

    fun showProgress(context: Context, done: Int, total: Int) = notify(context, PROGRESS_ID, progress(context, done, total))

    /** Ends the progress notification and posts [error] in its place, or clears an earlier failure once a sync goes through. */
    fun finish(context: Context, error: String?) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.cancel(PROGRESS_ID)
        if (error == null) {
            nm.cancel(ERROR_ID)
            return
        }
        ensureChannels(context)
        notify(
            context, ERROR_ID,
            NotificationCompat.Builder(context, ERROR_CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle(context.getString(R.string.sync_error_notification_title))
                .setContentText(error)
                .setStyle(NotificationCompat.BigTextStyle().bigText(error))
                .setAutoCancel(true)
                .setContentIntent(openApp(context))
                .build(),
        )
    }

    // Without the notification permission Android drops the post, so there is nothing to handle beyond that.
    private fun notify(context: Context, id: Int, n: Notification) {
        runCatching { context.getSystemService(NotificationManager::class.java)?.notify(id, n) }
    }

    private fun openApp(context: Context) = context.packageManager.getLaunchIntentForPackage(context.packageName)
        ?.let { PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) }

    private fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(PROGRESS_CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(PROGRESS_CHANNEL, context.getString(R.string.pref_cloud_sync), NotificationManager.IMPORTANCE_LOW))
        }
        if (nm.getNotificationChannel(ERROR_CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(ERROR_CHANNEL, context.getString(R.string.sync_error_channel), NotificationManager.IMPORTANCE_DEFAULT))
        }
    }
}
