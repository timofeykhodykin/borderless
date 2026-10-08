package app.borderless.ui

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import app.borderless.data.Errors

/**
 * The launcher icon follows the colour palette: each palette has an activity-alias with its own icon
 * (AndroidManifest), and exactly one of them is enabled. Applied only when the app goes to the
 * background: Android closes an open app whose launcher alias is switched. The launcher may re-add
 * the home-screen icon.
 */
object AppIcon {
    private val aliases = mapOf("coral" to ".ui.LauncherCoral", "mono" to ".ui.LauncherMono")

    fun apply(context: Context, palette: String) = Errors.guard("app icon", Unit, show = false) { switch(context, palette) }

    /**
     * At process start: switches only if no task of ours runs through an alias that would be disabled
     * (else it waits for [apply] in onStop). Catches up when the app was killed while open (update,
     * crash) and onStop never came.
     */
    fun applyIfIdle(context: Context, palette: String) = Errors.guard("app icon", Unit, show = false) {
        val am = context.getSystemService(ActivityManager::class.java) ?: return@guard
        val wanted = aliases[palette] ?: aliases.getValue("coral")
        val others = aliases.values.filter { it != wanted }.map { context.packageName + it }.toSet()
        val busy = am.appTasks.any { t ->
            val info = runCatching { t.taskInfo }.getOrNull() ?: return@any false
            listOfNotNull(info.baseIntent.component, info.origActivity, info.baseActivity, info.topActivity).any { it.className in others }
        }
        if (!busy) switch(context, palette)
    }

    private fun switch(context: Context, palette: String) {
        val wanted = aliases[palette] ?: aliases.getValue("coral")
        val pm = context.packageManager
        aliases.values.forEach { name ->
            val component = ComponentName(context.packageName, context.packageName + name)
            val state = if (name == wanted) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            val current = pm.getComponentEnabledSetting(component)
            val effective = if (current == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) {
                // Manifest default: only the coral alias is enabled.
                if (name == aliases["coral"]) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            } else current
            if (effective != state) pm.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
        }
    }
}
