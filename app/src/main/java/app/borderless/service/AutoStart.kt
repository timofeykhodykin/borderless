package app.borderless.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import app.borderless.R
import app.borderless.data.AppLog
import app.borderless.data.Errors
import app.borderless.data.LText

/**
 * The tunnel comes back by itself after the phone restarts or the app is updated (the update kills the process):
 * if it was on then — turned on and not turned off by the user ([wanted]) — it starts again. Android's own
 * always-on setting does this and more (Settings → Connection explains it); this costs nothing next to it.
 */
object AutoStart {
    private const val PREFS = "tunnel"
    private const val KEY_WANTED = "wanted"

    /** The tunnel was turned on ([on]) or off on purpose. */
    fun setWanted(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_WANTED, on).apply()
    }

    fun wanted(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_WANTED, false)

    /** Phone started, or this app was updated. */
    class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
            if (!wanted(context) || TunnelService.instance != null) return
            // The permission to run the tunnel was taken back meanwhile: only the user can give it again.
            if (VpnService.prepare(context) != null) return
            AppLog.event(LText.of(R.string.ev_autostart))
            Errors.guard("starting the tunnel by itself", Unit, show = false) { TunnelService.startAuto(context) }
        }
    }
}
