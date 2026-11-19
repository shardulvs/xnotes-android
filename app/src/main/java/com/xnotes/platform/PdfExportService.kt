package com.xnotes.platform

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat

/**
 * Keeps the process alive while [PdfExports] works, which Android only does for an app that is
 * off screen when a foreground service runs, and that service must show itself: the Saving PDF
 * notification. The work is [PdfExports]'s; this holds the process and nothing else.
 */
class PdfExportService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        PdfExports.init(this)
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        // Once asked to start in the foreground it has to, even when there turns out to be nothing left to do.
        val shown = runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, PdfExports.notification(this), type) }.isSuccess
        if (shown) PdfExports.attached(this) else finish()
        return START_NOT_STICKY
    }

    /** Android's daily allowance for this kind of service has run out: whatever is left waits for the next launch. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        finish()
    }

    override fun onDestroy() {
        PdfExports.detached(this)
        super.onDestroy()
    }

    internal fun finish() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private companion object {
        const val NOTIFICATION_ID = 42
    }
}
