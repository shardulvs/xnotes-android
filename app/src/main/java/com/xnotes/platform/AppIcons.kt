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
    INK_TILE("com.xnotes.IconInkTile", R.mipmap.ic_launcher_ink_tile, R.string.icon_ink_tile),
    GRAPHITE("com.xnotes.IconGraphite", R.mipmap.ic_launcher_graphite, R.string.icon_graphite),
    SUMI("com.xnotes.IconSumi", R.mipmap.ic_launcher_sumi, R.string.icon_sumi),
    POW("com.xnotes.IconPow", R.mipmap.ic_launcher_pow, R.string.icon_pow),
    MARBLING("com.xnotes.IconMarbling", R.mipmap.ic_launcher_marbling, R.string.icon_marbling),
    CLASSIC("com.xnotes.IconClassic", R.mipmap.ic_launcher_classic, R.string.icon_classic),
}

/** Which launcher icon shows. Exactly one alias is enabled; the system keeps that across updates. */
object AppIcons {
    // One at a time, so quick taps cannot interleave and leave every alias off.
    private val worker = Executors.newSingleThreadExecutor()

    // Aliases of icons no longer offered, kept so a launcher entry using one stays; each draws as the icon it maps to.
    private val retired = mapOf(
        "com.xnotes.IconLime" to AppIcon.BUBBLEGUM,
        "com.xnotes.IconAqua" to AppIcon.BUBBLEGUM,
        "com.xnotes.IconCreamTile" to AppIcon.INK_TILE,
    )

    fun current(context: Context): AppIcon =
        AppIcon.entries.firstOrNull { isOn(context, it.alias) }
            ?: retired.entries.firstOrNull { isOn(context, it.key) }?.value
            ?: AppIcon.BUBBLEGUM

    /** Show [icon], off the main thread: its alias goes on before the others go off, so the launcher always has one. */
    fun apply(context: Context, icon: AppIcon) {
        val app = context.applicationContext
        worker.execute {
            set(app, icon.alias, PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
            (AppIcon.entries.map { it.alias } + retired.keys).filter { it != icon.alias && isOn(app, it) }
                .forEach { set(app, it, PackageManager.COMPONENT_ENABLED_STATE_DISABLED) }
        }
    }

    private fun isOn(context: Context, alias: String): Boolean =
        when (runCatching { context.packageManager.getComponentEnabledSetting(ComponentName(context, alias)) }.getOrNull()) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> alias == AppIcon.BUBBLEGUM.alias // the manifest enables it alone
            else -> false
        }

    private fun set(context: Context, alias: String, state: Int) {
        runCatching { context.packageManager.setComponentEnabledSetting(ComponentName(context, alias), state, PackageManager.DONT_KILL_APP) }
    }
}
