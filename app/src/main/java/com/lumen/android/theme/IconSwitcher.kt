package com.lumen.android.theme

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * Swaps the launcher icon. Each icon is an activity-alias in the manifest, and
 * exactly one is enabled. Some launchers drop the home screen shortcut when
 * that changes (the app drawer always has it), and some close the app.
 */
object IconSwitcher {

    fun apply(context: Context, id: String) {
        val chosen = APP_ICONS.firstOrNull { it.id == id } ?: return
        val pm = context.packageManager
        // Turn the new one on before the old one off, so there's never no icon.
        val order = listOf(chosen) + APP_ICONS.filter { it != chosen }
        for (icon in order) {
            val component = ComponentName(context.packageName, context.packageName + icon.alias)
            val want = if (icon == chosen) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            if (enabled(pm, component, icon) != (icon == chosen)) {
                pm.setComponentEnabledSetting(component, want, PackageManager.DONT_KILL_APP)
            }
        }
    }

    private fun enabled(pm: PackageManager, component: ComponentName, icon: AppIcon): Boolean =
        when (pm.getComponentEnabledSetting(component)) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> icon.id == DEFAULT_ICON
            else -> false
        }

    /** The icon whose bars are closest in colour to [argb]. */
    fun nearest(argb: Int): String = APP_ICONS.minBy { distance(it.color, argb) }.id

    private fun distance(a: Int, b: Int): Int {
        val dr = (a shr 16 and 0xff) - (b shr 16 and 0xff)
        val dg = (a shr 8 and 0xff) - (b shr 8 and 0xff)
        val db = (a and 0xff) - (b and 0xff)
        return dr * dr + dg * dg + db * db
    }

    /** The one the manifest enables. */
    const val DEFAULT_ICON = "neon"
}
