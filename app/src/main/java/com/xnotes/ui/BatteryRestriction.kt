package com.xnotes.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect

/** Whether Android lets the app run in the background without battery limits, which background sync needs. */
object BatteryRestriction {
    fun lifted(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    /** Asks with the system dialog, or opens the battery settings list where a device has no such dialog. */
    fun requestLift(context: Context) {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")))
        }.onFailure {
            runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }
}

@Composable
fun rememberBatteryUnrestricted(): Boolean {
    val context = LocalContext.current
    return rememberOnResume { BatteryRestriction.lifted(context) }
}

/** [check] run again on every resume, so the answer follows a change made in the system settings. */
@Composable
fun <T> rememberOnResume(check: () -> T): T {
    var value by remember { mutableStateOf(check()) }
    LifecycleResumeEffect(Unit) {
        value = check()
        onPauseOrDispose {}
    }
    return value
}
