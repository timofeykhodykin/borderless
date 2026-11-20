package app.borderless.service

import app.borderless.data.Errors
import android.app.Notification
import app.borderless.Res
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationCompat
import app.borderless.R
import app.borderless.core.Phase
import app.borderless.core.TunnelState
import app.borderless.core.TunnelStatus
import app.borderless.data.Countries
import app.borderless.data.NotifyMode
import app.borderless.data.Repo
import app.borderless.ui.MainActivity

/**
 * The tunnel service's notification ([AppSettings.notify]): always (a notification is the only way to put an
 * icon — heron / ring / arrow — into the status bar), only while traffic does not go through a server, or never
 * (the system's key icon shows the tunnel). Kept minimal: no buttons, just the state, silent.
 *
 * Always: it can't be swiped away (ongoing; Android 14+ lets people swipe even that, so it is posted again at once).
 * Only while not through a server: it can be swiped away, and stays away while the state is the same (or the search
 * between retries); another such state brings it back, a connection removes it.
 */
object Notifications {
    const val ID = 1
    private const val CHANNEL_ICON = "status_icon"
    private const val CHANNEL_QUIET = "status_quiet"

    /** The title of the state the user swiped away in the "only while not through a server" mode (null: none). */
    @Volatile
    private var dismissed: String? = null

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        // The first versions used a noisier channel; its importance can't be lowered, so it goes.
        runCatching { nm.deleteNotificationChannel("status") }
        if (nm.getNotificationChannel(CHANNEL_ICON) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ICON, Res.s(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW).apply {
                    description = Res.s(R.string.notif_channel_desc)
                    setShowBadge(false)
                }
            )
        }
        runCatching { nm.deleteNotificationChannel(CHANNEL_QUIET) }
    }

    /** Status bar icon: heron when connected, open ring while searching, arrow when direct, ⦸ when paused. */
    fun icon(phase: Phase): Int = when (phase) {
        Phase.CONNECTED -> R.drawable.ic_stat_connected
        Phase.DIRECT -> R.drawable.ic_stat_direct
        Phase.PAUSED -> R.drawable.ic_stat_paused
        Phase.LIMITED -> if (Repo.settings.value.noServerAction == app.borderless.data.NoServerAction.PAUSE) R.drawable.ic_stat_paused else R.drawable.ic_stat_direct
        Phase.SEARCHING, Phase.NO_NETWORK, Phase.OFF -> R.drawable.ic_stat_searching
    }

    fun title(status: TunnelStatus): String = when (status.phase) {
        Phase.CONNECTED if status.unanswered -> Res.s(R.string.status_unanswered)
        Phase.CONNECTED -> Repo.server(status.serverId)?.let { Res.s(R.string.notif_connected, Countries.name(it.country)) } ?: Res.s(R.string.notif_connected_plain)
        Phase.SEARCHING -> Res.s(R.string.notif_searching)
        Phase.DIRECT -> Res.s(if (status.noServers) R.string.notif_no_servers else R.string.notif_direct)
        Phase.PAUSED -> Res.s(if (status.noServers) R.string.notif_no_servers_paused else R.string.notif_paused)
        Phase.NO_NETWORK -> Res.s(R.string.status_no_network)
        Phase.LIMITED -> Res.s(if (status.paused) R.string.notif_limited_paused else R.string.notif_limited)
        Phase.OFF -> Res.s(R.string.st_off)
    }

    /** [sticky]: can't be swiped away (the "always" mode and the foreground start). */
    fun build(context: Context, status: TunnelStatus, sticky: Boolean = true): Notification {
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_ICON)
            .setSmallIcon(icon(status.phase))
            .setColor(app.borderless.ui.theme.Palette.accent.toArgb())
            .setContentTitle(title(status))
            .setContentIntent(open)
            .apply {
                // Manual strategy on a server that doesn't answer: one tap hands the choice back to the app.
                if (status.unanswered) addAction(
                    0, Res.s(R.string.action_use_best),
                    PendingIntent.getService(
                        context, 2, Intent(context, TunnelService::class.java).setAction(TunnelService.ACTION_USE_BEST),
                        PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
            }
            .setDeleteIntent(
                PendingIntent.getBroadcast(context, 1, Intent(context, Dismissed::class.java), PendingIntent.FLAG_IMMUTABLE),
            )
            .setOngoing(sticky)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            // On the lock screen only the app's name: the state and the country are for the phone's owner (SECURITY.md S18).
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, CHANNEL_ICON)
                    .setSmallIcon(icon(status.phase))
                    .setContentTitle(Res.s(R.string.app_name))
                    .setSilent(true)
                    .setShowWhen(false)
                    .build()
            )
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    /** Whether [mode] shows a notification for [status]: "unclear" = not through a server, or a server not answering. */
    fun shown(mode: NotifyMode, status: TunnelStatus): Boolean = when (mode) {
        NotifyMode.OFF -> false
        NotifyMode.UNCLEAR -> status.phase != Phase.OFF && (status.phase != Phase.CONNECTED || status.unanswered)
        NotifyMode.ALWAYS -> true
    }

    fun update(context: Context, status: TunnelStatus) {
        if (status.phase == Phase.OFF) return
        val nm = context.getSystemService(NotificationManager::class.java)
        val mode = Repo.settings.value.notify
        if (!shown(mode, status)) {
            dismissed = null
            nm.cancel(ID)
            return
        }
        if (mode == NotifyMode.UNCLEAR && dismissed != null) {
            // Swiped away: not back for the same state, nor for the search between retries.
            if (status.phase == Phase.SEARCHING || title(status) == dismissed) return
            dismissed = null
        }
        Errors.guard("showing the notification", Unit, show = false) { nm.notify(ID, build(context, status, sticky = mode == NotifyMode.ALWAYS)) }
    }

    /** The user swiped the notification away. */
    private fun onDismissed(context: Context) {
        val status = TunnelState.status.value
        if (status.phase == Phase.OFF || TunnelService.instance == null) return
        when (Repo.settings.value.notify) {
            NotifyMode.ALWAYS -> Errors.guard("showing the notification", Unit, show = false) {
                context.getSystemService(NotificationManager::class.java).notify(ID, build(context, status))
            }
            NotifyMode.UNCLEAR -> dismissed = title(status)
            NotifyMode.OFF -> {}
        }
    }

    /** Told by the system when the notification is swiped away (or cleared). */
    class Dismissed : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = onDismissed(context)
    }
}
