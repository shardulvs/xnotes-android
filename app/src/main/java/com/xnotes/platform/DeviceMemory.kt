package com.xnotes.platform

import android.app.ActivityManager
import android.content.Context

/** How much memory this device has, for sizing what the app keeps cached. */
object DeviceMemory {
    @Volatile private var total = -1L

    /** The device's memory in bytes, 0 when the system will not say. */
    fun total(context: Context): Long {
        if (total >= 0) return total
        val info = ActivityManager.MemoryInfo()
        val am = context.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        am?.getMemoryInfo(info)
        return info.totalMem.coerceAtLeast(0).also { total = it }
    }
}
