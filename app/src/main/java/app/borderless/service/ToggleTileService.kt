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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Quick Settings tile: one tap connects or disconnects. While the panel is open the tile follows the
 * connection live (the system asks for a redraw only when the panel opens), and a tap shows its outcome at
 * once ("Connecting…" / off) instead of waiting for the tunnel.
 */
class ToggleTileService : TileService() {

    /** Lives while the panel shows the tile. */
    private var listening: CoroutineScope? = null

    /** What a tap asked for, shown until the tunnel's state catches up: true = on, false = off. */
    private var requested: Boolean? = null

    override fun onStartListening() {
        super.onStartListening()
        Errors.guard("quick settings tile", Unit, show = false) {
            Repo.init(this)
            render()
            listening?.cancel()
            listening = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + Errors.handler).also { scope ->
                scope.launch {
                    TunnelState.status
                        .distinctUntilChanged { a, b -> a.phase == b.phase && a.serverId == b.serverId && a.ping == b.ping }
                        .collect { status ->
                            if (requested != null && requested == status.active) requested = null
                            Errors.guard("quick settings tile", Unit, show = false) { render() }
                        }
                }
            }
        }
    }

    override fun onStopListening() {
        listening?.cancel()
        listening = null
        requested = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        Errors.guard("quick settings tile", Unit) { toggle() }
    }

    private fun toggle() {
        if (TunnelState.status.value.active) {
            request(false)
            TunnelService.stop()
        } else if (VpnService.prepare(this) == null) {
            request(true)
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

    /** Shows the outcome of a tap at once; the tunnel's own state takes over when it matches, or after a while. */
    private fun request(on: Boolean) {
        requested = on
        render()
        listening?.launch {
            delay(REQUEST_SHOWN_MS)
            if (requested == on) { requested = null; render() }
        }
    }

    private fun render() {
        val tile = qsTile ?: return
        val status = TunnelState.status.value
        val on = requested ?: status.active
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.app_name)
        // Always the heron; on/off is the tile's own active look, the state is in the subtitle.
        tile.icon = Icon.createWithResource(this, R.drawable.ic_stat_connected)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when {
                requested == true -> getString(R.string.st_connecting)
                requested == false -> getString(R.string.st_off)
                // Looking for a server: before the first one, or after the current one failed.
                status.phase == Phase.SEARCHING -> getString(R.string.st_connecting)
                else -> subtitle(status)
            }
        }
        tile.updateTile()
    }

    private fun subtitle(status: app.borderless.core.TunnelStatus): String =
        when (status.phase) {
            Phase.OFF -> getString(R.string.st_off)
            Phase.CONNECTED if status.unanswered -> getString(R.string.status_unanswered)
            Phase.CONNECTED -> status.ping?.let { "${Notifications.title(status).substringBefore(" ·")} · ${getString(R.string.ms, it)}" }
                ?: Notifications.title(status)
            Phase.DIRECT -> getString(R.string.st_direct)
            Phase.PAUSED -> getString(R.string.st_paused)
            Phase.SEARCHING -> getString(R.string.st_searching)
            Phase.NO_NETWORK -> getString(R.string.status_no_network)
            Phase.LIMITED -> getString(R.string.st_limited)
        }

    companion object {
        private const val REQUEST_SHOWN_MS = 10_000L

        fun refresh(context: Context) {
            runCatching { requestListeningState(context, ComponentName(context, ToggleTileService::class.java)) }
        }
    }
}
