package com.xnotes.platform

import android.app.UiModeManager
import android.content.Context
import android.os.Build

/** Tells Android 12 and later the app's own light or dark choice, so the next launch screen opens in it. */
object AppNightMode {
    private var told: String? = null

    fun follow(context: Context, appearance: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || appearance == told) return
        told = appearance
        val mode = when (appearance) {
            "light" -> UiModeManager.MODE_NIGHT_NO
            "dark", "oled" -> UiModeManager.MODE_NIGHT_YES
            else -> UiModeManager.MODE_NIGHT_AUTO // "system": no override, the device decides
        }
        context.getSystemService(UiModeManager::class.java)?.setApplicationNightMode(mode)
    }
}
