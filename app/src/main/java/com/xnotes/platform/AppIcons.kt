package com.xnotes.platform

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.xnotes.R
import java.util.concurrent.Executors

/** A launcher icon xnotes ships, in the order the picker shows them: its activity alias and its adaptive icon. */
enum class AppIcon(val alias: String, @param:DrawableRes val icon: Int, @param:StringRes val label: Int) {
    BUBBLEGUM("com.xnotes.MainActivity", R.mipmap.ic_launcher, R.string.icon_bubblegum),
    CREAM_ROSE("com.xnotes.IconCreamRose", R.mipmap.ic_launcher_cream_rose, R.string.icon_cream_rose),
    LIME("com.xnotes.IconLime", R.mipmap.ic_launcher_lime, R.string.icon_lime),
    AQUA("com.xnotes.IconAqua", R.mipmap.ic_launcher_aqua, R.string.icon_aqua),
    INK_TILE("com.xnotes.IconInkTile", R.mipmap.ic_launcher_ink_tile, R.string.icon_ink_tile),
    CLASSIC("com.xnotes.IconClassic", R.mipmap.ic_launcher_classic, R.string.icon_classic),
    GRAPHITE("com.xnotes.IconGraphite", R.mipmap.ic_launcher_graphite, R.string.icon_graphite),
    CREAM_TILE("com.xnotes.IconCreamTile", R.mipmap.ic_launcher_cream_tile, R.string.icon_cream_tile),
}

/** Which launcher icon shows. Exactly one [AppIcon] alias is enabled; the system keeps that across updates. */
object AppIcons {
    // One at a time, so quick taps cannot interleave and leave every alias off.
    private val worker = Executors.newSingleThreadExecutor()

    fun current(context: Context): AppIcon =
        AppIcon.entries.firstOrNull { isOn(context, it) } ?: AppIcon.BUBBLEGUM

    /** Show [icon], off the main thread: its alias goes on before the others go off, so the launcher always has one. */
    fun apply(context: Context, icon: AppIcon) {
        val app = context.applicationContext
        worker.execute {
            set(app, icon, PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
            AppIcon.entries.filter { it != icon && isOn(app, it) }
                .forEach { set(app, it, PackageManager.COMPONENT_ENABLED_STATE_DISABLED) }
        }
    }

    private fun isOn(context: Context, icon: AppIcon): Boolean =
        when (runCatching { context.packageManager.getComponentEnabledSetting(ComponentName(context, icon.alias)) }.getOrNull()) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> icon == AppIcon.BUBBLEGUM // the manifest enables it alone
            else -> false
        }

    private fun set(context: Context, icon: AppIcon, state: Int) {
        runCatching { context.packageManager.setComponentEnabledSetting(ComponentName(context, icon.alias), state, PackageManager.DONT_KILL_APP) }
    }
}
