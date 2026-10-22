package app.borderless.service

import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.collectLatest
import app.borderless.data.NotifyMode
import kotlinx.coroutines.flow.combine
import app.borderless.data.Activity
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import app.borderless.data.Errors
import app.borderless.data.LText
import app.borderless.R
import android.content.Context
import app.borderless.Res
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.borderless.core.CoreEnv
import app.borderless.core.Engine
import app.borderless.core.Phase
import app.borderless.core.TunnelState
import app.borderless.core.TunnelStatus
import app.borderless.data.AppLog
import app.borderless.data.Regions
import app.borderless.data.Repo
import app.borderless.data.StatsDb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class TunnelService : VpnService(), Engine.Host {

    companion object {
        private const val TAG = "Tunnel"
        const val ACTION_START = "app.borderless.START"
        const val ACTION_STOP = "app.borderless.STOP"
        const val ACTION_FIND_BEST = "app.borderless.FIND_BEST"
        private const val ACTION_AUTO = "app.borderless.AUTO"
        /** Leave Manual for the default automatic strategy (its server stopped answering): it finds the best one. */
        const val ACTION_USE_BEST = "app.borderless.USE_BEST"
        const val EXTRA_SERVER = "server"
        private const val EXTRA_FOREGROUND = "foreground"

        @Volatile
        var instance: TunnelService? = null
            private set

        /** The caller must have the tunnel permission already (see [VpnService.prepare]). */
        fun start(context: Context, serverId: String? = null) {
            val i = Intent(context, TunnelService::class.java).setAction(ACTION_START)
            serverId?.let { i.putExtra(EXTRA_SERVER, it) }
            // Without the status icon there is no notification at all: a plain start is enough, the
            // system keeps an established tunnel alive by itself. If Android refuses (app in background),
            // fall back to a foreground start, which must show the notification for a moment.
            if (Repo.settings.value.notify == NotifyMode.ALWAYS) {
                ContextCompat.startForegroundService(context, i.putExtra(EXTRA_FOREGROUND, true))
            } else try {
                context.startService(i)
            } catch (e: IllegalStateException) {
                ContextCompat.startForegroundService(context, i.putExtra(EXTRA_FOREGROUND, true))
            }
        }

        /**
         * Started by itself (phone restart, app update: see [AutoStart]): a foreground start, which those moments allow
         * from the background; handled like a start by the system (the strategy in effect is kept).
         */
        fun startAuto(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, TunnelService::class.java).setAction(ACTION_AUTO))
        }

        fun stop() {
            instance?.stopTunnel()
        }

        /** Whether Android keeps this tunnel always on (null: not known — the tunnel isn't running, or Android < 10). */
        val alwaysOn: Boolean?
            get() = instance?.takeIf { Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q }?.let { runCatching { it.isAlwaysOn }.getOrNull() }

        /** Hands [event] to the running engine; false if there is none (not started yet, or the demo build). */
        fun send(event: Engine.Event): Boolean {
            val engine = instance?.engine ?: return false
            engine.send(event)
            return true
        }

        /** Re-creates the TUN interface (app exclusions) and regenerates the config. */
        fun reconfigure() {
            instance?.rebuild()
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + Errors.handler)
    private var tun: ParcelFileDescriptor? = null
    private var engine: Engine? = null
    private var statusJob: Job? = null
    private var stopping = false

    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    private val power by lazy { getSystemService(PowerManager::class.java) }

    @Volatile
    private var network: Network? = null

    /** Whether the system's own check of [network] passes (null: not known yet). */
    @Volatile
    private var validated: Boolean? = null

    /** The screen turning on: the engine checks at once if no server is in use (someone may be waiting). */
    private val screenReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            engine?.send(if (intent.action == Intent.ACTION_SCREEN_OFF) Engine.Event.ScreenOff else Engine.Event.ScreenOn)
        }
    }
    private var screenWatched = false

    override val tunFd: Int get() = tun?.fd ?: 0
    /**
     * A callback can be missed or come before the network is usable: when none is known, ask the system
     * directly (our app is outside the tunnel, so its active network is the one underneath).
     */
    override fun hasNetwork(): Boolean =
        network != null || Errors.guard("network check", false, show = false) {
            underlying()?.also {
                AppLog.debug(TAG, "network found without a callback: ${describe(it)}")
                network = it
            } != null
        }
    override fun isScreenOn(): Boolean = power.isInteractive
    override fun networkKind(): String? = network?.let { n ->
        val c = connectivity.getNetworkCapabilities(n) ?: return@let "other"
        when {
            c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cell"
            c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
    }

    override fun zoneAppsInside(inside: Boolean) {
        scope.launch {
            if (inside == zoneAppsInside) return@launch
            zoneAppsInside = inside
            AppLog.debug(TAG, if (inside) "zone apps into the tunnel (directly first)" else "zone apps outside the tunnel again")
            // Without routing the zones' apps are inside anyway: nothing to rebuild.
            if (Repo.settings.value.routingEnabled) rebuild(Engine.Event.TunRenewed) else engine?.send(Engine.Event.TunRenewed)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Repo.init(this)
        CoreEnv.init(this)
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            when (intent?.action) {
                ACTION_STOP -> stopTunnel()
                ACTION_FIND_BEST -> engine?.send(Engine.Event.FindBest)
                ACTION_USE_BEST -> Repo.updateSettings { s ->
                    s.copy(strategy = s.defaultStrategy.takeIf { it != app.borderless.data.Strategy.MANUAL } ?: app.borderless.data.Strategy.STABLE)
                }
                // ACTION_START, a restart by the system (null intent) and Always-on all start the tunnel.
                else -> startTunnel(
                intent?.getStringExtra(EXTRA_SERVER), byUser = intent?.action == ACTION_START,
                // Our foreground starts and system starts (restart, Always-on) must call startForeground.
                mustForeground = intent?.getBooleanExtra(EXTRA_FOREGROUND, false) == true || intent?.action != ACTION_START,
            )
            }
        } catch (e: Exception) {
            // E.g. the system refused the TUN interface or the foreground service: report and shut down cleanly.
            Errors.report(e, "tunnel service (${intent?.action ?: "restart"})", getString(R.string.err_tunnel_start))
            if (engine == null) {
                TunnelState.set(TunnelStatus(error = e.message))
                runCatching { tun?.close() }
                tun = null
                runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startTunnel(serverId: String?, byUser: Boolean, mustForeground: Boolean) {
        val e = engine
        if (e != null) {
            serverId?.let { e.send(Engine.Event.Select(it)) }
            return
        }
        // Turned on by the user without picking a server: start with the default strategy from settings.
        // (A picked server comes with MANUAL; a restart by the system keeps whatever was in effect.)
        if (byUser && serverId == null) Repo.updateSettings { app.borderless.data.EcoMath.startStrategy(it) }
        stopping = false
        AppLog.event(LText.of(R.string.ev_tunnel_on))
        AutoStart.setWanted(this, true)
        TunnelState.set(TunnelStatus(phase = Phase.SEARCHING))
        if (Repo.settings.value.notify == NotifyMode.ALWAYS || mustForeground) goForeground()

        tun = establish()
        if (tun == null) {
            AppLog.event(LText.of(R.string.ev_no_permission), AppLog.Kind.ERROR)
            TunnelState.set(TunnelStatus(error = Res.s(R.string.ev_no_permission)))
            stopSelf()
            return
        }
        // Established: the system now holds the tunnel service, so without the status icon the
        // notification (if a start needed it) can go.
        if (Repo.settings.value.notify != NotifyMode.ALWAYS) dropForeground()
        network = underlying()
        watchNetworks()
        if (!screenWatched) {
            screenWatched = true
            ContextCompat.registerReceiver(this, screenReceiver, IntentFilter(Intent.ACTION_SCREEN_ON).apply { addAction(Intent.ACTION_SCREEN_OFF) }, ContextCompat.RECEIVER_NOT_EXPORTED)
        }
        // The notification shows phase and server only: don't redraw it for every new ping.
        statusJob = scope.launch {
            TunnelState.status.distinctUntilChanged { a, b -> a.phase == b.phase && a.serverId == b.serverId && a.error == b.error && a.unanswered == b.unanswered }
                .combine(Repo.settings.map { it.notify to it.palette }.distinctUntilChanged()) { st, s -> st to s.first }
                .collectLatest { (st, mode) ->
                    // The setting can change while connected: show or remove the notification.
                    when (mode) {
                        NotifyMode.ALWAYS -> {
                            if (!foreground) Errors.guard("status icon", Unit, show = false) { goForeground() }
                            Notifications.update(this@TunnelService, st)
                        }
                        NotifyMode.UNCLEAR -> {
                            dropForeground()
                            // At once: when something doesn't load, the reason is already in the notification.
                            Notifications.update(this@TunnelService, st)
                        }
                        NotifyMode.OFF -> {
                            dropForeground()
                            Notifications.update(this@TunnelService, st)
                        }
                    }
                }
        }

        // New routing lists: the core loads them when it starts.
        scope.launch { app.borderless.core.GeoLists.version.drop(1).collect { engine?.send(Engine.Event.RestartCore) } }

        // A server picked by hand comes with the Manual strategy, so start() connects to it directly.
        engine = Engine(this).also { it.start(serverId ?: Repo.settings.value.lastServerId) }
    }

    /**
     * Follows the network underneath the tunnel. Not the app's "default network": for the owner of the tunnel
     * Android reports the tunnel itself there, so changes of Wi-Fi / mobile were never seen (the core kept
     * dead connections until the tunnel was turned off and on). Android 12+: the best non-tunnel network;
     * older versions only have the default-network callback.
     */
    private fun watchNetworks() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val request = android.net.NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            connectivity.registerBestMatchingNetworkCallback(request, networkCallback, android.os.Handler(android.os.Looper.getMainLooper()))
        } else {
            connectivity.registerDefaultNetworkCallback(networkCallback)
        }
    }

    /** Whether the service currently shows its notification (and runs in the foreground). */
    private var foreground = false

    private fun goForeground() {
        val n = Notifications.build(this, TunnelState.status.value)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, Notifications.ID, n, type)
        foreground = true
    }

    private fun dropForeground() {
        if (!foreground) return
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        foreground = false
    }

    private fun establish(): ParcelFileDescriptor? {
        val s = Repo.settings.value
        val b = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(s.mtu)
            .addAddress("172.19.0.1", 30)
            .addRoute("0.0.0.0", 0)
            // Never answered directly: port 53 is intercepted by the core's DNS module.
            .addDnsServer("172.19.0.2")
        // Route IPv6 into the tunnel even when it is "off", otherwise it would silently go around the tunnel.
        b.addAddress("fdfe:dcba:9876::1", 126)
        b.addRoute("::", 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) b.setMetered(false)

        // Our own app is always outside (its checks and downloads go direct). With routing on: the domain zones' apps,
        // plus the user's "without the tunnel" list, minus the user's "through the tunnel" list (the user's lists win).
        b.addDisallowedApplication(packageName)
        if (s.routingEnabled) {
            // On a limited network with "directly first" the zones' apps come inside: their connections are then tried
            // directly first and go through the server if that doesn't work (Engine.syncDirectFirst).
            val zone = if (zoneAppsInside) emptySet() else zoneApps(s.regions)
            val outside = (zone + s.appsDirect) - s.appsTunnel - packageName
            outside.forEach {
                try {
                    b.addDisallowedApplication(it)
                } catch (_: PackageManager.NameNotFoundException) {
                }
            }
        }
        // No fixed underlying network: the system follows its default network by itself. A pinned one goes
        // stale after a switch (Wi-Fi ↔ mobile) and apps then see the tunnel as having no internet.
        return try {
            b.establish()
        } catch (e: Exception) {
            AppLog.debug(TAG, "establish failed", e)
            null
        }
    }

    /** Installed apps of the chosen domain zones (recognised by the rules in regions.json). */
    private fun zoneApps(codes: Set<String>): Set<String> {
        val zones = Regions.active(codes)
        if (zones.isEmpty()) return emptySet()
        return Errors.guard("reading installed apps", emptySet(), show = false) {
            packageManager.getInstalledApplications(0).map { it.packageName }.filter { pkg -> zones.any { it.apps.matches(pkg) } }.toSet()
        }
    }

    /** The zones' apps are inside the tunnel for now (see [Engine.Host.zoneAppsInside]). */
    @Volatile
    private var zoneAppsInside = false

    private fun rebuild(event: Engine.Event = Engine.Event.Reconfigure) {
        if (engine == null) return
        val old = tun
        val fresh = establish() ?: return
        tun = fresh
        engine?.send(event)
        scope.launch {
            delay(3000)
            runCatching { old?.close() }
        }
    }

    /**
     * A new network underneath: a fresh TUN interface as well (what turning the tunnel off and on does,
     * which used to be the only cure when apps kept "waiting for network" after a switch), then the
     * engine restarts the core on it and checks the server.
     */
    private fun renew() {
        if (engine == null || stopping) return
        val old = tun
        establish()?.let { fresh ->
            tun = fresh
            // The old core keeps reading the old interface until the engine restarts it (it may be busy).
            scope.launch {
                delay(15_000)
                runCatching { old?.close() }
            }
        }
        engine?.send(Engine.Event.NetworkChanged)
    }

    fun stopTunnel() {
        if (stopping) return
        stopping = true
        AppLog.event(LText.of(R.string.ev_tunnel_off))
        // Turned off (by the user, or another app took the tunnel over): not to be started again by itself.
        AutoStart.setWanted(this, false)
        scope.launch {
            runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
            if (screenWatched) { screenWatched = false; runCatching { unregisterReceiver(screenReceiver) } }
            Errors.guard("stopping the engine", Unit) { engine?.stop() }
            engine = null
            StatsDb.phase(Phase.OFF, null)
            Activity.samplePower(force = true)
            StatsDb.flush()
            statusJob?.cancel()
            // A notification posted without the foreground ("only while not through a server") goes too.
            runCatching { getSystemService(android.app.NotificationManager::class.java).cancel(Notifications.ID) }
            runCatching { tun?.close() }
            tun = null
            TunnelState.set(TunnelStatus())
            ServiceCompat.stopForeground(this@TunnelService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onRevoke() {
        // Another app took the tunnel over or the user revoked permission.
        AppLog.event(LText.of(R.string.ev_revoked), AppLog.Kind.WARN)
        stopTunnel()
    }

    override fun onDestroy() {
        if (engine != null) {
            runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
            if (screenWatched) { screenWatched = false; runCatching { unregisterReceiver(screenReceiver) } }
            runCatching { tun?.close() }
            TunnelState.set(TunnelStatus())
        }
        instance = null
        scope.cancel()
        super.onDestroy()
    }

    /** A usable network outside the tunnel right now (the active one may be the tunnel itself for us). */
    @Suppress("DEPRECATION")
    private fun underlying(): Network? =
        connectivity.activeNetwork?.takeIf { isUsable(it) } ?: connectivity.allNetworks.firstOrNull { isUsable(it) }

    private fun isUsable(n: Network): Boolean {
        val caps = connectivity.getNetworkCapabilities(n) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    private fun describe(n: Network): String {
        val c = connectivity.getNetworkCapabilities(n) ?: return n.toString()
        return when {
            c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Res.s(R.string.net_mobile)
            c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> n.toString()
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(n: Network) = consider(n)

        // A network can turn usable (internet capability, validation) after it was announced. On the network in use,
        // the system's check passing again is the quickest sign that the internet is back.
        override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) {
            if (n != network) return consider(n)
            val v = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            val was = validated
            validated = v
            if (was != null && was != v) engine?.send(Engine.Event.Validated(v))
        }

        override fun onLost(n: Network) {
            if (n == network) {
                AppLog.debug(TAG, "default network lost: ${describe(n)}")
                network = null
                engine?.send(Engine.Event.NetworkChanged)
            }
        }

        private fun consider(n: Network) {
            if (!isUsable(n)) return
            val changed = n != network
            network = n
            if (changed) {
                validated = connectivity.getNetworkCapabilities(n)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                AppLog.debug(TAG, "default network: ${describe(n)}")
                scope.launch { renew() }
            }
        }
    }
}
