package app.borderless.ui

import app.borderless.ui.stats.SubscriptionStatsScreen
import androidx.compose.ui.unit.IntOffset
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.AnimatedContent
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import app.borderless.ui.stats.StatsPeriod
import app.borderless.ui.AppIcon
import app.borderless.data.Activity
import app.borderless.core.Power
import kotlinx.coroutines.withContext
import app.borderless.data.launchSafe
import app.borderless.data.Errors
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.borderless.core.Engine
import app.borderless.core.Scanner
import app.borderless.core.TunnelState
import app.borderless.data.Importer
import app.borderless.data.Repo
import app.borderless.data.lastProbe
import app.borderless.data.Strategy
import app.borderless.data.Server
import app.borderless.service.TunnelService
import app.borderless.ui.main.AddSheet
import app.borderless.ui.main.MainActions
import app.borderless.ui.main.MainScreen
import app.borderless.ui.main.MainState
import app.borderless.ui.main.ServerSheet
import app.borderless.ui.settings.AppsScreen
import app.borderless.ui.stats.ServerStatsScreen
import app.borderless.ui.stats.StatsScreen
import app.borderless.ui.settings.GroupsScreen
import app.borderless.ui.settings.LogScreen
import app.borderless.ui.settings.SettingsPage
import app.borderless.ui.settings.SettingsScreen
import app.borderless.ui.theme.BorderlessTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /** Server to connect to once the user grants the tunnel permission. */
    private var pendingServer: String? = null

    private val tunnelPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) Errors.guard("turning the connection on", Unit) { TunnelService.start(this, pendingServer) }
        pendingServer = null
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    /** Text shared to the app or a vless://… link opened with it; shown in the add dialog. */
    private var incoming by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleIntent(intent)

        setContent {
            BorderlessTheme {
                // Unexpected errors from anywhere in the app, one dialog at a time.
                ErrorDialog()
                // Screens open on top of each other: "main|stats|subStats:<id>|serverStats:<id>". Back closes the
                // top one, so every screen returns to the one it was opened from, whatever the path.
                var stack by rememberSaveable { mutableStateOf("main") }
                val screen = top(stack)
                // Each open screen keeps its state (scroll, tab) while another one is on top of it.
                val saved = rememberSaveableStateHolder()
                var adding by rememberSaveable { mutableStateOf(false) }
                var details by rememberSaveable { mutableStateOf<String?>(null) }
                // Open settings page, kept while visiting apps / subscriptions / log from it.
                var settingsPage by rememberSaveable { mutableStateOf<SettingsPage?>(null) }
                fun open(next: String) {
                    // Statistics share one period; it starts afresh when they are entered from elsewhere.
                    if (isStats(next) && !isStats(screen)) StatsPeriod.reset()
                    stack = "$stack|$next"
                }
                fun back() {
                    if ('|' !in stack) return
                    saved.removeState(stack)
                    stack = stack.substringBeforeLast('|')
                }
                BackHandler(enabled = screen != "main") {
                    if (screen == "settings" && settingsPage != null) settingsPage = null else back()
                }
                // Screens slide in from the side of their button (settings / statistics: left, groups:
                // right); deeper screens come from the right; going back reverses the motion.
                AnimatedContent(
                    targetState = stack,
                    transitionSpec = { screenTransition(initialState, targetState) },
                    label = "screens",
                ) { current ->
                    saved.SaveableStateProvider(current) {
                    val entry = top(current)
                    val arg = entry.substringAfter(':', "")
                    when (entry.substringBefore(':')) {
                        "stats" -> StatsScreen(
                            onBack = ::back,
                            onServer = { open("serverStats:$it") },
                            onSubscription = { open("subStats:$it") },
                        )
                        "serverStats" -> ServerStatsScreen(arg, onBack = ::back)
                        "subStats" -> SubscriptionStatsScreen(arg, onBack = ::back, onServer = { open("serverStats:$it") })
                        "groups" -> GroupsScreen(
                            onBack = ::back,
                            onSubStats = { open("subStats:$it") },
                            onServerStats = { open("serverStats:$it") },
                        )
                        "settings" -> SettingsScreen(
                            page = settingsPage,
                            onPage = { settingsPage = it },
                            onBack = { back(); settingsPage = null },
                            onApps = { open("apps") },
                            onLog = { open("log") },
                        )
                        "log" -> LogScreen(onBack = ::back)
                        "apps" -> AppsScreen(onBack = ::back)
                        else -> {
                        val visible by Repo.visible.collectAsStateWithLifecycle()
                        // Servers the last scan stopped waiting for, with no result yet, stay out of sight.
                        val skipped by Scanner.skipped.collectAsStateWithLifecycle()
                        val servers = remember(visible, skipped) { if (skipped.isEmpty()) visible else visible.filter { it.id !in skipped } }
                        val allServers by Repo.servers.collectAsStateWithLifecycle()
                        val pings by Repo.pings.collectAsStateWithLifecycle()
                        val groups by Repo.groups.collectAsStateWithLifecycle()
                        val subs by Repo.subscriptions.collectAsStateWithLifecycle()
                        val status by TunnelState.status.collectAsStateWithLifecycle()
                        val settings by Repo.settings.collectAsStateWithLifecycle()
                        val scan by Scanner.progress.collectAsStateWithLifecycle()
                        val pending by Scanner.pending.collectAsStateWithLifecycle()
                        val plan by Scanner.plan.collectAsStateWithLifecycle()
                        val probing by Scanner.probing.collectAsStateWithLifecycle()
                        val ecoEstimate by app.borderless.core.Eco.estimate.collectAsStateWithLifecycle()
                        MainScreen(
                            MainState(servers, groups, pings, subs, status, settings.routingEnabled, settings.strategy, scan, pending,
                                probing, plan.takeIf { settings.autoSort }, settings.ecoOn, ecoEstimate?.percent),
                            MainActions(
                                onToggle = ::toggle,
                                onServerClick = ::connectTo,
                                onServerLongClick = { details = it.id },
                                onRoutingToggle = { v ->
                                    Repo.updateSettings { it.copy(routingEnabled = v) }
                                    TunnelService.reconfigure()
                                },
                                onStrategy = { v -> Repo.updateSettings { it.copy(strategy = v) } },
                                onPingAll = { Scanner.start() },
                                onSettings = { settingsPage = null; open("settings") },
                                onGroups = { open("groups") },
                                onStats = { open("stats") },
                                onAdd = { adding = true },
                                onEco = { app.borderless.core.Eco.toggle() },
                                onEcoSettings = { settingsPage = SettingsPage.ECO; open("settings") },
                            ),
                        )
                        if (adding || incoming != null) {
                            AddSheet(initial = incoming.orEmpty(), onDismiss = { adding = false; incoming = null })
                        }
                        details?.let { id ->
                            // Any server, so the sheet stays open if auto-visibility hides it meanwhile.
                            val server = allServers.firstOrNull { it.id == id }
                            if (server == null) details = null
                            else ServerSheet(
                                server, pings[id],
                                onConnect = { connectTo(server) },
                                onStats = { open("serverStats:${server.id}") },
                                onDismiss = { details = null },
                            )
                        }
                        }
                    }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val text = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        }
        if (!text.isNullOrBlank()) incoming = text
    }

    override fun onStart() {
        super.onStart()
        lifecycleScope.launchSafe("updating subscriptions") {
            withContext(Dispatchers.IO) {
                Importer.updateDue()
                Importer.resolveCountries()
                app.borderless.core.GeoLists.updateIfDue()
            }
        }
        // Opening the app refreshes the map unless it is fresh enough: with the tunnel on the engine keeps
        // it up to date on its own schedule, so only a map older than that schedule is rescanned.
        // The map's age is its last check of servers (the server in use is pinged on its own, that doesn't refresh it).
        val newest = Repo.pings.value.values.lastProbe() ?: 0
        val s = Repo.settings.value
        val maxAge = if (TunnelState.status.value.active) s.policy.rescanMin * 60_000L
        else 5 * 60_000L * (if (s.ecoOn) app.borderless.data.EcoMath.OPEN_SCAN_FACTOR else 1)
        if (Repo.activeServers.isNotEmpty() && System.currentTimeMillis() - newest > maxAge * Power.stretch()) Scanner.start(reason = Activity.Kind.SCAN_OPEN)
        Activity.samplePower()
        Activity.uiShown(true)
        app.borderless.core.Eco.refreshEstimate()
    }

    override fun onStop() {
        super.onStop()
        // Not while visible: changing the launcher alias would close the app.
        AppIcon.apply(this, Repo.settings.value.palette)
        Activity.uiShown(false)
        Activity.samplePower()
        Activity.flush()
    }

    private fun toggle() {
        if (TunnelState.status.value.active) TunnelService.stop() else connect(null)
    }

    private fun connectTo(server: Server) {
        // A server picked by hand means the Manual strategy (an automatic one can be chosen again later).
        Repo.updateSettings { it.copy(strategy = Strategy.MANUAL, manualServerId = server.id) }
        if (TunnelState.status.value.active) TunnelService.send(Engine.Event.Select(server.id))
        else connect(server.id)
    }

    private fun connect(serverId: String?) = Errors.guard("turning the connection on", Unit) { connectUnsafe(serverId) }

    private fun connectUnsafe(serverId: String?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val prepare = VpnService.prepare(this)
        if (prepare == null) {
            TunnelService.start(this, serverId)
        } else {
            pendingServer = serverId
            tunnelPermission.launch(prepare)
        }
    }
}

/** The screen on top of a navigation stack ("main|stats|serverStats:<id>"). */
private fun top(stack: String) = stack.substringAfterLast('|')

/** How many screens are open over the main one. */
private fun depth(stack: String) = stack.count { it == '|' }

private fun isStats(entry: String) = entry.substringBefore(':') in setOf("stats", "subStats", "serverStats")

/** Which side a screen opened from the main one comes from: settings and statistics (left-hand buttons) on the left. */
private fun side(entry: String) = if (entry.substringBefore(':') in setOf("settings", "stats")) -1 else 1

private fun screenTransition(from: String, to: String): ContentTransform {
    val spec = tween<IntOffset>(320, easing = FastOutSlowInEasing)
    val fade = tween<Float>(320)
    // Direction the new screen comes from: -1 = left, +1 = right. Deeper = forward, shallower = back.
    val dir = when {
        depth(to) > depth(from) -> if (depth(from) == 0) side(top(to)) else 1
        depth(to) < depth(from) -> if (depth(to) == 0) -side(top(from)) else -1
        else -> 1
    }
    return (slideInHorizontally(spec) { full -> dir * full } + fadeIn(fade)) togetherWith
        (slideOutHorizontally(spec) { full -> -dir * full } + fadeOut(fade))
}

