package app.borderless.service

import android.app.Notification
import app.borderless.Res
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationCompat
import app.borderless.R
import app.borderless.core.Phase
import app.borderless.core.TunnelStatus
import app.borderless.data.Countries
import app.borderless.data.Repo
import app.borderless.ui.MainActivity

/**
 * The tunnel service's notification, only shown with [AppSettings.statusIcon] on: a notification is the
 * only way to put an icon (heron / ring / arrow) into the status bar. Kept minimal: no buttons, just
 * the state, silent. Without it there is no notification at all; the system's key icon shows the tunnel.
 */
object Notifications {
    const val ID = 1
    private const val CHANNEL_ICON = "status_icon"
    private const val CHANNEL_QUIET = "status_quiet"

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
        Phase.SEARCHING, Phase.NO_NETWORK, Phase.OFF -> R.drawable.ic_stat_searching
    }

    fun title(status: TunnelStatus): String = when (status.phase) {
        Phase.CONNECTED -> Repo.server(status.serverId)?.let { Res.s(R.string.notif_connected, Countries.name(it.country)) } ?: Res.s(R.string.notif_connected_plain)
        Phase.SEARCHING -> Res.s(R.string.notif_searching)
        Phase.DIRECT -> Res.s(if (status.noServers) R.string.notif_no_servers else R.string.notif_direct)
        Phase.PAUSED -> Res.s(R.string.notif_paused)
        Phase.NO_NETWORK -> Res.s(R.string.status_no_network)
        Phase.OFF -> Res.s(R.string.st_off)
    }

    fun build(context: Context, status: TunnelStatus): Notification {
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
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    fun update(context: Context, status: TunnelStatus) {
        if (status.phase == Phase.OFF) return
        val nm = context.getSystemService(NotificationManager::class.java)
        if (!Repo.settings.value.statusIcon) {
            nm.cancel(ID)
            return
        }
        runCatching { nm.notify(ID, build(context, status)) }
    }
}
