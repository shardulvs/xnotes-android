package com.xnotes.platform

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.xnotes.R

/**
 * Keeps the microphone live while xnotes is off screen. Android silences any app that is not in
 * front unless a foreground service of type microphone runs, and that service must show itself:
 * the Recording notification, with Pause or Resume and Stop. The recording itself is
 * [com.xnotes.ui.AudioRecording]'s; this only holds the process in front and relays the buttons.
 */
class RecordingService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> com.xnotes.ui.AudioRecording.pause()
            ACTION_RESUME -> com.xnotes.ui.AudioRecording.resume()
            ACTION_STOP -> com.xnotes.ui.AudioRecording.stop()
            else -> {
                val state = com.xnotes.ui.AudioRecording.notice()
                if (state == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
                ServiceCompat.startForeground(this, NOTIFICATION_ID, build(this, state), type)
            }
        }
        return START_NOT_STICKY
    }

    /** Swiped away from Recents: the recording is closed as it stands, and placed the next time its note opens. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        com.xnotes.ui.AudioRecording.abandon()
        stopSelf()
    }

    /** What the notification shows. [elapsedMs] is recorded time; [paused] freezes it. */
    class Notice(val title: String, val elapsedMs: Long, val paused: Boolean)

    companion object {
        private const val CHANNEL = "recording"
        private const val NOTIFICATION_ID = 41
        private const val ACTION_PAUSE = "com.xnotes.recording.PAUSE"
        private const val ACTION_RESUME = "com.xnotes.recording.RESUME"
        private const val ACTION_STOP = "com.xnotes.recording.STOP"

        fun start(context: Context) {
            ensureChannel(context)
            ContextCompat.startForegroundService(context, Intent(context, RecordingService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RecordingService::class.java))
        }

        /** Show [state] in the notification the service already put up. */
        fun update(context: Context, state: Notice) {
            runCatching { context.getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, build(context, state)) }
        }

        private fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL) != null) return
            val channel = NotificationChannel(CHANNEL, context.getString(R.string.recording_channel), NotificationManager.IMPORTANCE_LOW)
            channel.setShowBadge(false)
            nm.createNotificationChannel(channel)
        }

        private fun build(context: Context, state: Notice): Notification {
            val open = context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?.let { PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) }
            fun action(name: String) = PendingIntent.getService(
                context, name.hashCode(), Intent(context, RecordingService::class.java).setAction(name),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val b = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification_mic)
                .setContentTitle(context.getString(if (state.paused) R.string.recording_paused else R.string.recording))
                .setContentText(state.title)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setContentIntent(open)
            if (state.paused) {
                b.setShowWhen(false)
                b.setSubText(com.xnotes.core.audio.AudioTime.format(state.elapsedMs.toInt()))
                b.addAction(0, context.getString(R.string.recording_resume), action(ACTION_RESUME))
            } else {
                // A chronometer counts on by itself, so the notification need not be posted every second.
                b.setUsesChronometer(true)
                b.setShowWhen(true)
                b.setWhen(System.currentTimeMillis() - state.elapsedMs)
                b.addAction(0, context.getString(R.string.recording_pause), action(ACTION_PAUSE))
            }
            b.addAction(0, context.getString(R.string.recording_stop), action(ACTION_STOP))
            return b.build()
        }
    }
}
