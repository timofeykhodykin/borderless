package app.borderless.service

import app.borderless.data.Errors
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.borderless.R
import app.borderless.core.Phase
import app.borderless.core.TunnelState
import app.borderless.data.Repo
import app.borderless.ui.ToggleActivity

/** Quick Settings tile: one tap connects or disconnects. */
class ToggleTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        Errors.guard("quick settings tile", Unit, show = false) {
            Repo.init(this)
            render()
        }
    }

    override fun onClick() {
        super.onClick()
        Errors.guard("quick settings tile", Unit) { toggle() }
    }

    private fun toggle() {
        if (TunnelState.status.value.active) {
            TunnelService.stop()
        } else if (VpnService.prepare(this) == null) {
            TunnelService.start(this)
        } else {
            // Permission dialog needs an activity.
            val intent = Intent(this, ToggleActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }
    }

    private fun render() {
        val tile = qsTile ?: return
        val status = TunnelState.status.value
        tile.state = if (status.active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.app_name)
        // Always the heron; on/off is the tile's own active look, the state is in the subtitle.
        tile.icon = Icon.createWithResource(this, R.drawable.ic_stat_connected)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when (status.phase) {
                Phase.OFF -> getString(R.string.st_off)
                Phase.CONNECTED -> status.ping?.let { "${Notifications.title(status).substringBefore(" ·")} · ${getString(R.string.ms, it)}" }
                    ?: Notifications.title(status)
                Phase.DIRECT -> getString(R.string.st_direct)
                Phase.PAUSED -> getString(R.string.st_paused)
                Phase.SEARCHING -> getString(R.string.st_searching)
                Phase.NO_NETWORK -> getString(R.string.status_no_network)
            }
        }
        tile.updateTile()
    }

    companion object {
        fun refresh(context: Context) {
            runCatching { requestListeningState(context, ComponentName(context, ToggleTileService::class.java)) }
        }
    }
}
