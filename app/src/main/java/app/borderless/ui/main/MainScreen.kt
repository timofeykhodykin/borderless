package app.borderless.ui.main

import androidx.compose.runtime.produceState
import app.borderless.data.Errors
import app.borderless.data.Energy
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import kotlin.math.sqrt
import kotlin.math.ln
import androidx.compose.runtime.saveable.rememberSaveable
import app.borderless.ui.FlagIcon
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.util.lerp
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.draw.shadow
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.AltRoute
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.borderless.R
import app.borderless.core.Phase
import app.borderless.core.Scanner
import app.borderless.core.TunnelStatus
import app.borderless.data.Countries
import app.borderless.data.Group
import app.borderless.data.PingRecord
import app.borderless.data.lastProbe
import app.borderless.data.Repo
import app.borderless.data.Server
import app.borderless.data.Strategy
import app.borderless.data.Subscription
import app.borderless.ui.theme.Display
import app.borderless.ui.theme.Body
import app.borderless.ui.theme.Palette
import kotlinx.coroutines.delay

/** Everything the main screen shows, gathered by the caller from the repositories. */
data class MainState(
    val servers: List<Server>,
    val groups: List<Group>,
    val pings: Map<String, PingRecord>,
    val subscriptions: List<Subscription>,
    val status: TunnelStatus,
    val routingEnabled: Boolean,
    val strategy: Strategy,
    val scan: Scanner.Progress?,
    /** Servers being pinged right now. */
    val pending: Set<String> = emptySet(),
    /** Servers being probed at this moment; the rest of [pending] are queued. */
    val probing: Set<String> = emptySet(),
    /** Auto-sort on: the scan order the heatmap follows inside each group. */
    val order: List<String>? = null,
    /** The battery saving mode is on. */
    val eco: Boolean = false,
    /** Its expected saving in percent, if known. */
    val ecoSaving: Int? = null,
)

class MainActions(
    val onToggle: () -> Unit,
    val onServerClick: (Server) -> Unit,
    val onServerLongClick: (Server) -> Unit,
    val onRoutingToggle: (Boolean) -> Unit,
    val onStrategy: (Strategy) -> Unit,
    val onPingAll: () -> Unit,
    val onSettings: () -> Unit,
    val onGroups: () -> Unit,
    val onStats: () -> Unit,
    val onAdd: () -> Unit,
    val onEco: () -> Unit = {},
    val onEcoSettings: () -> Unit = {},
)

@Composable
fun MainScreen(state: MainState, actions: MainActions) {
    val sorted = remember(state.servers, state.pings) { sortByPing(state.servers, state.pings) }
    val grouped = remember(state.servers, state.groups, state.order) {
        Repo.serversByGroup(state.servers, state.groups, state.order).map { it.second }.filter { it.isNotEmpty() }
    }
    val groupNames = remember(state.groups, state.subscriptions) { state.groups.associate { it.id to Repo.groupName(it) } }
    val spin = rememberSpin(state.pending.isNotEmpty())
    // Server shown in the panel under the heatmap after tapping its cell.
    var peek by rememberSaveable { mutableStateOf<String?>(null) }

    Box(Modifier.fillMaxSize().background(Palette.background)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            TopBar(actions)
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                // Room at the bottom for the battery saving button.
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
            ) {
                item { Stats(state) }
                item { ConnectBlock(state, actions) }
                if (state.servers.isNotEmpty()) {
                    item {
                        Heatmap(
                            grouped, state.pings, state.pending, state.probing, spin,
                            currentId = state.status.serverId.takeIf { state.status.active },
                            peekId = peek,
                            onCell = { s -> peek = if (peek == s.id) null else s.id },
                            onCellLongClick = actions.onServerLongClick,
                        ) {
                            state.servers.firstOrNull { it.id == peek }?.let { server ->
                                ServerRow(
                                    server = server,
                                    ping = state.pings[server.id],
                                    group = groupNames[Repo.groupOf(server)],
                                    current = state.status.active && server.id == state.status.serverId,
                                    pending = server.id in state.pending,
                                    probing = server.id in state.probing,
                                    spin = spin,
                                    onClick = { actions.onServerClick(server) },
                                    onLongClick = { actions.onServerLongClick(server) },
                                    panel = true,
                                )
                            }
                        }
                    }
                    item {
                        Text(
                            stringResource(R.string.servers_header, state.servers.size),
                            style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(start = 4.dp, top = 22.dp, bottom = 6.dp),
                        )
                    }
                    items(sorted, key = { it.id }) { server ->
                        ServerRow(
                            server = server,
                            ping = state.pings[server.id],
                            group = groupNames[Repo.groupOf(server)],
                            current = state.status.active && server.id == state.status.serverId,
                            pending = server.id in state.pending,
                            probing = server.id in state.probing,
                            spin = spin,
                            onClick = { actions.onServerClick(server) },
                            onLongClick = { actions.onServerLongClick(server) },
                            // Rows fade in/out and slide when servers appear, disappear or re-sort.
                            modifier = Modifier.animateItem(),
                        )
                    }
                } else {
                    item { EmptyHint(actions.onAdd) }
                }
            }
        }
        EcoButton(
            state.eco, state.ecoSaving, actions.onEco, actions.onEcoSettings,
            Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 18.dp, bottom = 18.dp),
        )
    }
}

/**
 * The battery saving mode: a round button with a battery while off; while on it is filled with the
 * accent and shows the expected saving. Tap switches the mode, long press opens its settings.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EcoButton(on: Boolean, saving: Int?, onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier) {
    val bg by animateColorAsState(if (on) Palette.accent else Palette.card, label = "eco")
    val fg by animateColorAsState(if (on) Color.White else Palette.textSecondary, label = "ecoFg")
    Row(
        modifier
            .height(56.dp)
            .widthIn(min = 56.dp)
            .shadow(if (on) 6.dp else 3.dp, CircleShape)
            .clip(CircleShape)
            .background(bg)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .animateContentSize()
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Rounded.BatterySaver,
            contentDescription = stringResource(if (on) R.string.eco_on_cd else R.string.eco_off_cd),
            tint = fg, modifier = Modifier.size(24.dp),
        )
        if (on && saving != null && saving > 0) {
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.eco_pill, saving), fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = Body, color = fg)
        }
    }
}

/** "Border" bold, "(less)" italic. */
private val AppTitle = buildAnnotatedString {
    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("Border") }
    withStyle(SpanStyle(fontWeight = FontWeight.Normal, fontStyle = FontStyle.Italic)) { append("(less)") }
}

/** Working servers by latency, then untested, then unavailable. */
fun sortByPing(servers: List<Server>, pings: Map<String, PingRecord>): List<Server> =
    servers.sortedWith(compareBy<Server> {
        val p = pings[it.id]
        when {
            p == null -> 1
            p.ok -> 0
            else -> 2
        }
    }.thenBy { pings[it.id]?.ms ?: Int.MAX_VALUE })

@Composable
private fun TopBar(actions: MainActions) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = actions.onSettings) {
            Icon(Icons.Rounded.Settings, contentDescription = stringResource(R.string.settings), tint = Palette.textSecondary)
        }
        IconButton(onClick = actions.onStats) {
            Icon(Icons.Rounded.Insights, contentDescription = stringResource(R.string.stats), tint = Palette.textSecondary)
        }
        Text(
            AppTitle,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center,
            fontSize = 21.sp,
            fontFamily = Display,
            color = Palette.text,
        )
        IconButton(onClick = actions.onGroups) {
            Icon(Icons.Rounded.FolderOpen, contentDescription = stringResource(R.string.groups), tint = Palette.textSecondary)
        }
        IconButton(onClick = actions.onAdd) {
            Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.add), tint = Palette.textSecondary)
        }
    }
}

@Composable
private fun Stats(state: MainState) {
    val shown = state.pings.filterKeys { id -> state.servers.any { it.id == id } }.values
    val available = shown.count { it.ok }
    val tested = shown.size
    val bestRecord = shown.filter { it.ok }.minByOrNull { it.ms!! }
    val best = bestRecord?.ms
    // Estimated battery use of the app right now (last hour, in the mode it is in), refreshed every minute
    // while it runs and at once when the battery saving mode is switched.
    val active = state.status.active
    val saving = state.ecoSaving?.div(100.0)
    val rate by produceState<Energy.Recent?>(null, active, state.eco) {
        value = null
        while (active) {
            value = Errors.guard("battery estimate", null, show = false) { Energy.recentRate(state.eco, saving) }
            delay(60_000)
        }
    }
    // When the shown results were taken. Available: the last check of servers (the server in use is also
    // pinged on its own every few minutes; those pings don't count, or it would always read "just now").
    // Best: that server's own result.
    val agoAvailable = rememberAgo(shown.lastProbe())
    val agoBest = rememberAgo(bestRecord?.at?.takeIf { it > 0 })
    val r = rate?.rate
    val pct = r?.percentPerHour
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatCard(stringResource(R.string.stat_available), if (tested == 0) "—" else "$available / ${state.servers.size}", null, agoAvailable, Modifier.weight(1f))
        StatCard(stringResource(R.string.stat_best), best?.let { stringResource(R.string.ms, it) } ?: "—", best, agoBest, Modifier.weight(1f))
        StatCard(
            stringResource(R.string.stat_battery),
            when {
                !active || r == null -> "—"
                pct != null -> stringResource(R.string.stat_battery_pct, "%.1f".format(pct))
                else -> stringResource(R.string.stat_battery_mah, "%.0f".format(r.mahPerHour))
            },
            null,
            rate?.let { rr ->
                when {
                    // Just switched modes: the other mode's rate scaled by the expected saving.
                    rr.forecast -> stringResource(R.string.stat_forecast)
                    // The error with its unit, then the time the figure covers.
                    pct != null -> stringResource(R.string.stat_pm_over, stringResource(R.string.stat_battery_pct, "%.1f".format(rr.rate.percentPlusMinus ?: 0.0)), minutesText(rr.minutes))
                    else -> stringResource(R.string.stat_pm_over, stringResource(R.string.stat_battery_mah, "%.0f".format(rr.rate.mahPlusMinus)), minutesText(rr.minutes))
                }
            },
            Modifier.weight(1f),
        )
    }
}

/** "1 h" for a full hour, else "25 min": the time a figure covers. */
@Composable
private fun minutesText(min: Int): String =
    if (min >= 55) stringResource(R.string.dur_h_short, ((min + 5) / 60).coerceAtLeast(1)) else stringResource(R.string.dur_m, min)

/** "just now", "5 min ago", "2 h ago", then the date. */
@Composable
private fun agoText(time: Long, now: Long): String {
    val min = ((now - time) / 60_000).coerceAtLeast(0)
    return when {
        min < 1 -> stringResource(R.string.ago_now)
        min < 60 -> stringResource(R.string.ago_min, min.toInt())
        min < 24 * 60 -> stringResource(R.string.ago_h, (min / 60).toInt())
        else -> java.text.SimpleDateFormat("d MMM", app.borderless.Res.locale).format(java.util.Date(time))
    }
}

/** "just now" / "5 min ago" / "2 h ago" for [time], updated while shown. */
@Composable
private fun rememberAgo(time: Long?): String? {
    if (time == null) return null
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }
    val min = ((now - time) / 60_000).coerceAtLeast(0)
    return when {
        min < 1 -> stringResource(R.string.ago_now)
        min < 60 -> stringResource(R.string.ago_min, min.toInt())
        else -> stringResource(R.string.ago_h, (min / 60).toInt())
    }
}

@Composable
private fun StatCard(label: String, value: String, ms: Int?, sub: String?, modifier: Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Palette.card)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(label, fontSize = 12.sp, color = Palette.textSecondary, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (ms != null) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(Palette.ping(ms)))
                Spacer(Modifier.width(6.dp))
            }
            Text(value, fontSize = 19.sp, fontWeight = FontWeight.Bold, fontFamily = Display, color = Palette.text, maxLines = 1)
        }
        // Always a line, so the cards keep the same height.
        Text(sub.orEmpty(), fontSize = 11.5.sp, color = Palette.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConnectBlock(state: MainState, actions: MainActions) {
    val status = state.status
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        ConnectButton(
            status.phase,
            searching = status.phase == Phase.SEARCHING || state.scan != null,
            sats = remember(state.servers, state.pings, state.pending, state.probing, state.scan?.startedAt) { satellites(state) },
            scanId = state.scan?.startedAt,
            eco = state.eco,
            onClick = actions.onToggle,
        )
        Spacer(Modifier.height(6.dp))
        val (title, subtitle) = statusText(state)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (status.phase == Phase.CONNECTED) {
                FlagIcon(state.servers.firstOrNull { it.id == status.serverId }?.country, 18.dp)
                Spacer(Modifier.width(10.dp))
            }
            Text(title, fontSize = 19.sp, fontWeight = FontWeight.Bold, fontFamily = Display, color = Palette.text, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(3.dp))
        Text(
            subtitle, fontSize = 13.sp, color = Palette.textSecondary, textAlign = TextAlign.Center,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 24.dp),
        )
        // The current server's group and protocol, like in the server list.
        val server = state.servers.firstOrNull { it.id == status.serverId }
        if (server != null && (status.phase == Phase.CONNECTED || status.phase == Phase.SEARCHING) && state.scan == null) {
            Text(
                listOfNotNull(Repo.groupName(Repo.group(Repo.groupOf(server))), server.protocol).joinToString(" · "),
                fontSize = 12.sp, color = Palette.textMuted, textAlign = TextAlign.Center,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 24.dp, vertical = 1.dp),
            )
        }
        Spacer(Modifier.height(18.dp))
        // Wraps to a second line on narrow screens.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RoutingChip(state.routingEnabled, actions.onRoutingToggle)
            StrategyChip(state.strategy, actions.onStrategy)
            PingChip(state.scan, actions.onPingAll)
        }
    }
}

@Composable
private fun statusText(state: MainState): Pair<String, String> {
    val status = state.status
    val server = state.servers.firstOrNull { it.id == status.serverId }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(status.phase) {
        // Retry countdown every second; "last connected …" every half minute.
        while (status.phase == Phase.DIRECT || status.phase == Phase.PAUSED || status.phase == Phase.OFF) {
            now = System.currentTimeMillis()
            delay(if (status.phase == Phase.OFF) 30_000 else 1000)
        }
    }
    val retryIn = ((status.nextRetryAt - now) / 1000).coerceAtLeast(0)
    // Off: when it was last connected (the statistics' state history; 0 = never).
    val lastConnected by produceState<Long?>(null, status.phase) {
        if (status.phase == Phase.OFF) value = Errors.guard("last connection", null, show = false) { app.borderless.data.StatsDb.lastConnected() } ?: 0L
    }
    return when (status.phase) {
        Phase.OFF -> stringResource(R.string.status_off) to when (val t = lastConnected) {
            null -> ""
            0L -> stringResource(R.string.status_never_connected)
            else -> stringResource(R.string.status_last_connected, agoText(t, now))
        }
        Phase.CONNECTED -> buildString {
            append(Countries.name(server?.country))
            status.ping?.let { append(" · ").append(stringResource(R.string.ms, it)) }
        } to server?.let(Repo::displayName).orEmpty()
        Phase.SEARCHING -> stringResource(R.string.status_searching) to
            (state.scan?.let { stringResource(R.string.status_checked, it.done, it.total) } ?: server?.let(Repo::displayName) ?: "")
        Phase.DIRECT -> if (status.noServers) stringResource(R.string.status_no_servers) to stringResource(R.string.status_no_servers_sub)
        else stringResource(R.string.status_unavailable) to stringResource(R.string.status_direct_sub, app.borderless.data.Units.durationText(retryIn))
        Phase.PAUSED -> stringResource(R.string.status_unavailable) to stringResource(R.string.status_paused_sub, app.borderless.data.Units.durationText(retryIn))
        Phase.NO_NETWORK -> stringResource(R.string.status_no_network) to stringResource(R.string.status_no_network_sub)
    }
}

/**
 * One satellite on the search orbits: a server of the scan in progress. It appears when its probe
 * starts, turns into a coloured planet at a height given by its ping (faster = closer) when it
 * answers, and fades away if it doesn't.
 */
data class Satellite(val id: String, val probing: Boolean, val ms: Int?, val failed: Boolean)

/** Servers of a scan passed to the orbits (the orbit itself shows fewer, see [MAX_PLANETS]). */
private const val MAX_SATELLITES = 80

/** Most planets on the orbits at once: when more answer, the slowest one flies off to make room. */
private const val MAX_PLANETS = 22

private fun satellites(state: MainState): List<Satellite> {
    val since = state.scan?.startedAt ?: return emptyList()
    return state.servers.mapNotNull { s ->
        val p = state.pings[s.id]
        val fresh = p != null && p.at >= since && s.id !in state.pending
        when {
            s.id in state.probing -> Satellite(s.id, probing = true, ms = null, failed = false)
            fresh -> Satellite(s.id, probing = false, ms = p!!.ms, failed = !p.ok)
            else -> null // queued: not launched yet
        }
    }.take(MAX_SATELLITES)
}

@Composable
private fun ConnectButton(phase: Phase, searching: Boolean, sats: List<Satellite>, scanId: Long?, eco: Boolean, onClick: () -> Unit) {
    val fill by animateColorAsState(
        when (phase) {
            Phase.CONNECTED -> Palette.accent
            Phase.DIRECT, Phase.NO_NETWORK -> Palette.warning
            Phase.PAUSED -> Palette.rose
            Phase.SEARCHING -> Palette.accentSoft
            Phase.OFF -> Palette.background
        }, label = "fill",
    )
    // Double ring: a white gap, then a darker ring. It makes "on" unmistakable.
    val ring by animateColorAsState(
        when (phase) {
            Phase.CONNECTED -> Palette.accentStrong
            Phase.DIRECT, Phase.NO_NETWORK -> Palette.warningStrong
            Phase.PAUSED -> Palette.danger
            Phase.SEARCHING -> Palette.accentContainer
            Phase.OFF -> Color.Transparent
        }, label = "ring",
    )
    val iconColor by animateColorAsState(
        when (phase) {
            Phase.CONNECTED -> Color.White
            Phase.OFF -> Palette.textMuted
            Phase.SEARCHING -> Palette.accent
            Phase.PAUSED -> Palette.danger
            else -> Palette.warningIcon
        }, label = "icon",
    )
    // Orbits slide out from under the button while servers are being pinged and back in after.
    // They stay at least MIN_ORBITS_MS, so a one-second connect still shows the whole motion.
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(searching) {
        if (searching) shown = true
        else if (shown) {
            delay(MIN_ORBITS_MS)
            shown = false
        }
    }
    val appear = animateFloatAsState(
        if (shown || searching) 1f else 0f,
        tween(if (shown || searching) 800 else 550, easing = FastOutSlowInEasing),
        label = "orbits",
    )

    Box(Modifier.size(ORBIT_BOX), contentAlignment = Alignment.Center) {
        if (phase == Phase.CONNECTED) Halo(Palette.accent, still = eco)
        if (searching || shown || appear.value > 0.001f) Orbits({ appear.value }, sats, scanId)
        Box(
            Modifier
                .size(BUTTON + 16.dp)
                .drawBehind {
                    if (eco) {
                        // Battery saving mode: the outer ring turns dashed.
                        val w = 4.dp.toPx()
                        drawCircle(ring, radius = size.minDimension / 2 - w / 2, style = Stroke(w, pathEffect = ecoDash))
                    } else {
                        drawCircle(ring)
                        drawCircle(Palette.background, radius = size.minDimension / 2 - 4.dp.toPx())
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(BUTTON)
                    .clip(CircleShape)
                    .background(fill)
                    .then(if (phase == Phase.OFF) Modifier.border(2.dp, Palette.outline, CircleShape) else Modifier)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = androidx.compose.material3.ripple(), onClick = onClick),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.PowerSettingsNew, contentDescription = stringResource(R.string.connect), tint = iconColor, modifier = Modifier.size(58.dp))
            }
        }
    }
}

private val BUTTON = 148.dp
private val ecoDash = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))
private const val MIN_ORBITS_MS = 1500L

/**
 * Soft breathing glow behind the button. Animated in the draw phase only, without recomposition;
 * [still] (battery saving mode): no animation at all, so no frames are drawn for it.
 */
@Composable
private fun Halo(glow: Color, still: Boolean = false) {
    if (still) {
        Box(Modifier.size(224.dp).drawBehind { drawCircle(Brush.radialGradient(listOf(glow.copy(alpha = 0.22f), glow.copy(alpha = 0f)))) })
        return
    }
    val breath = rememberInfiniteTransition(label = "halo")
        .animateFloat(1f, 1.06f, infiniteRepeatable(tween(2200), RepeatMode.Reverse), label = "breath")
    Box(
        Modifier
            .size(224.dp)
            .graphicsLayer { scaleX = breath.value; scaleY = breath.value }
            .drawBehind { drawCircle(Brush.radialGradient(listOf(glow.copy(alpha = 0.22f), glow.copy(alpha = 0f)))) },
    )
}

/**
 * Two orbits of servers around the button while searching. Grey satellites are still being
 * pinged; they light up with their latency colour when they answer and fade when they do not.
 * Everything is drawn in the draw phase: rotation and slide-out never recompose.
 */
/**
 * Animation state of one satellite, advanced in the draw phase (no recomposition per frame). Each one has a
 * character of its own ([speed], a slow radial [wobble]), seeded by its id: planets with the same ping
 * would otherwise circle in lockstep, in pairs, forever.
 */
private class SatMotion(var angle: Float, val bornNs: Long, seed: Int) {
    private val rnd = kotlin.random.Random(seed)
    val speed = 0.78f + rnd.nextFloat() * 0.5f
    private val wobbleDp = 1.5f + rnd.nextFloat() * 2.5f
    private val wobblePeriodS = 3f + rnd.nextFloat() * 4f
    private val wobblePhase = rnd.nextFloat() * 6.2832f

    /** Radial breathing in dp at time [ns]. */
    fun wobble(ns: Long): Float = wobbleDp * sin(ns / 1e9f / wobblePeriodS * 6.2832f + wobblePhase)

    /** Small random offset of the starting angle, so newcomers don't form a perfect pattern either. */
    val jitter = (rnd.nextFloat() - 0.5f) * 50f

    var lastNs = bornNs
    var answeredNs = 0L
    var ms: Int? = null
    var failedNs = 0L
    /** Pushed out to keep at most [MAX_PLANETS]: flies off like a failed one, but in its colour. */
    var evictedNs = 0L
    var radius = 0f
    val gone get() = failedNs != 0L || evictedNs != 0L
}

private const val GOLDEN_ANGLE = 137.508f

/** Room for the orbits around the button. */
private val ORBIT_BOX = 268.dp

@Composable
private fun Orbits(appear: () -> Float, sats: List<Satellite>, scanId: Long?) {
    // Drives redraws while visible; the motion itself is computed from the clock in draw.
    val tick = rememberInfiniteTransition(label = "orbits").animateFloat(0f, 1f, infiniteRepeatable(tween(1000, easing = LinearEasing)), label = "tick")
    val dash = remember { PathEffect.dashPathEffect(floatArrayOf(6f, 9f)) }
    val motions = remember { LinkedHashMap<String, SatMotion>() }
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 10.sp, color = Palette.textSecondary, fontFamily = Display)
    val msUnit = stringResource(R.string.ms_unit)
    // Each scan starts a fresh sky; after it ends the last one stays until the orbits are drawn in.
    val lastScan = remember { longArrayOf(0) }
    // Fastest and slowest answer of this sky (the rings' labels).
    val scale = remember { IntArray(2) }
    if (scanId != null && scanId != lastScan[0]) {
        lastScan[0] = scanId
        motions.clear()
        scale.fill(0)
    }
    val now = System.nanoTime()
    sats.forEach { sat ->
        // Each newcomer takes roughly the golden angle after the previous one: never on top of another.
        val m = motions.getOrPut(sat.id) { SatMotion(0f, now, sat.id.hashCode()).also { it.angle = (motions.size * GOLDEN_ANGLE + it.jitter + 360f) % 360f } }
        if (sat.ms != null && m.ms == null) { m.ms = sat.ms; m.answeredNs = now }
        if (sat.failed && m.failedNs == 0L) m.failedNs = now
    }
    // A probe that ended without a new result (cut short, scan stopped) drops out of the list: let it fly off
    // instead of circling as an empty ring until the next scan.
    // Also when the scan has ended: the last probes end with it.
    run {
        val listed = sats.mapTo(HashSet()) { it.id }
        motions.forEach { (id, m) -> if (id !in listed && m.ms == null && !m.gone) m.failedNs = now }
    }
    // Keep the sky readable: over the limit, the slowest answered planets leave (probes in flight stay).
    val present = motions.values.filterNot { it.gone }
    if (present.size > MAX_PLANETS) {
        present.filter { it.ms != null }.sortedByDescending { it.ms }.take(present.size - MAX_PLANETS).forEach { it.evictedNs = now }
    }
    Canvas(Modifier.size(ORBIT_BOX)) {
        tick.value
        val k = appear()
        val t = System.nanoTime()
        val base = (BUTTON / 2).toPx()
        val fast = 100.dp.toPx()
        val slow = 122.dp.toPx()
        val waiting = 111.dp.toPx()
        // Hard corridor for planet centres: the 9 dp halo never touches the button's ring (+4 dp gap)
        // nor leaves the orbit box (so it can't reach the text below). Wins over everything else.
        val halo = 9.dp.toPx()
        val minR = (BUTTON / 2 + 8.dp).toPx() + halo + 4.dp.toPx()
        val maxR = (ORBIT_BOX / 2).toPx() - halo
        // The two rings are the scale of this scan: fastest answer on the inner one, slowest on the outer.
        val answered = motions.values.filterNot { it.gone }.mapNotNull { it.ms }
        // The ring labels stay until the orbits close, even if the planets that set them have flown off.
        if (answered.isNotEmpty()) { scale[0] = answered.min(); scale[1] = answered.max() }
        val lo = scale[0].takeIf { it > 0 }
        val hi = scale[1].takeIf { it > 0 }
        drawCircle(Palette.outline.copy(alpha = 0.7f * k), lerp(base, fast, k), style = Stroke(1.dp.toPx(), pathEffect = dash))
        drawCircle(Palette.outline.copy(alpha = 0.7f * k), lerp(base, slow, k), style = Stroke(1.dp.toPx(), pathEffect = dash))
        /**
         * Drawing-style callout for a ring: a dot on the ring at [angleDeg], a slanted line out past the
         * orbits, a horizontal shelf towards the side and the label at its end.
         */
        fun ringLabel(text: String, radius: Float, angleDeg: Double, toLeft: Boolean) {
            val m = measurer.measure(text, labelStyle)
            val a = Math.toRadians(angleDeg)
            val dir = Offset(cos(a).toFloat(), sin(a).toFloat())
            val anchor = center + dir * radius
            val elbow = center + dir * (lerp(base, slow, k) + 10.dp.toPx())
            val shelfEnd = elbow + Offset((if (toLeft) -1 else 1) * 22.dp.toPx(), 0f)
            val line = Palette.textMuted.copy(alpha = 0.8f * k)
            drawCircle(line, 2.dp.toPx(), anchor)
            drawLine(line, anchor, elbow, 1.dp.toPx())
            drawLine(line, elbow, shelfEnd, 1.dp.toPx())
            val gap = 4.dp.toPx()
            val x = if (toLeft) shelfEnd.x - gap - m.size.width else shelfEnd.x + gap
            drawText(m, topLeft = Offset(x, shelfEnd.y - m.size.height / 2f), alpha = k)
        }
        // Inner ring (fastest): bottom left; outer ring (slowest): top right.
        if (lo != null) ringLabel("$lo $msUnit", lerp(base, fast, k), 135.0, toLeft = true)
        if (hi != null && hi != lo) ringLabel("$hi $msUnit", lerp(base, slow, k), -45.0, toLeft = false)
        fun progress(fromNs: Long, durMs: Int) = ((t - fromNs) / 1e6f / durMs).coerceIn(0f, 1f).let { 1 - (1 - it) * (1 - it) }
        // Planets on nearly the same orbit would overlap while one overtakes the other: push close pairs
        // apart radially (a display offset, recomputed every frame, so they settle back after passing).
        val live = motions.values.filter { !it.gone && it.radius > 0f }
        val push = HashMap<SatMotion, Float>()
        val minGap = 19.dp.toPx()
        for (i in live.indices) for (j in i + 1 until live.size) {
            val p = live[i]
            val q = live[j]
            val da = Math.toRadians(((p.angle - q.angle + 540f) % 360f - 180f).toDouble())
            val along = kotlin.math.abs(da).toFloat() * (p.radius + q.radius) / 2
            val across = q.radius - p.radius
            val dist = sqrt(along * along + across * across)
            if (dist < minGap) {
                val f = (minGap - dist) / 2 * (1 - along / minGap).coerceIn(0f, 1f)
                // Crowds just overlap: each planet moves at most a few dp (see the clamp below).
                val sign = if (across >= 0) 1f else -1f
                push[q] = (push[q] ?: 0f) + f * sign
                push[p] = (push[p] ?: 0f) - f * sign
            }
        }
        motions.values.forEach { m ->
            val failedK = when {
                m.failedNs > 0 -> progress(m.failedNs, 900)
                m.evictedNs > 0 -> progress(m.evictedNs, 900)
                else -> 0f
            }
            if (failedK >= 1f) return@forEach
            // Height: launched from the button to the middle orbit, then to its ping's place between the
            // fastest (inner ring) and slowest (outer ring) answer so far, on a log scale.
            val target = m.ms?.let { ms ->
                if (lo == null || hi == null || hi <= lo) (fast + slow) / 2
                else fast + (slow - fast) * ((ln(ms.toFloat()) - ln(lo.toFloat())) / (ln(hi.toFloat()) - ln(lo.toFloat()))).coerceIn(0f, 1f)
            }
            val dtMove = ((t - m.lastNs) / 1e9f).coerceIn(0f, 0.1f)
            m.radius = when {
                target == null -> lerp(base, waiting, progress(m.bornNs, 600))
                // Glide towards the target; it moves when a new fastest / slowest answer arrives.
                else -> m.radius + (target - m.radius) * (1 - kotlin.math.exp(-6f * dtMove))
            }
            val nudge = (push[m] ?: 0f).coerceIn(-6.dp.toPx(), 6.dp.toPx()) + (if (m.ms != null) m.wobble(t).dp.toPx() else 0f)
            // While still launching (under the button) only the outer limit applies.
            val placed = (m.radius + nudge + 14.dp.toPx() * failedK).let { if (m.radius >= minR) it.coerceIn(minR, maxR) else it.coerceAtMost(maxR) }
            val r = lerp(base, placed, k)
            // Inner orbits turn faster (Kepler-like), so the picture keeps moving.
            val dt = ((t - m.lastNs) / 1e9f).coerceIn(0f, 0.1f)
            m.lastNs = t
            m.angle = (m.angle + 24f * m.speed * (waiting / r.coerceAtLeast(1f)).let { it * sqrt(it) } * dt) % 360f
            val a = Math.toRadians(m.angle.toDouble())
            val p = center + Offset(cos(a).toFloat() * r, sin(a).toFloat() * r)
            val alpha = k * (1 - failedK)
            val grow = progress(m.bornNs, 400)
            when {
                m.failedNs > 0 -> drawCircle(Palette.unavailableMark.copy(alpha = 0.7f * alpha), 3.5.dp.toPx() * (1 - failedK * 0.6f), p)
                m.ms != null -> {
                    val c = Palette.ping(m.ms)
                    drawCircle(c.copy(alpha = 0.35f * alpha), 9.dp.toPx(), p)
                    drawCircle(c.copy(alpha = alpha), 6.dp.toPx(), p)
                }
                else -> {
                    drawCircle(Palette.background.copy(alpha = alpha), 5.dp.toPx() * grow, p)
                    drawCircle(Palette.textMuted.copy(alpha = alpha), 5.dp.toPx() * grow, p, style = Stroke(1.5.dp.toPx()))
                }
            }
        }
    }
}

/**
 * Rotation angle shared by all "waiting" spinners. Read it only inside draw lambdas: then the
 * animation redraws those cells without recomposing anything. Idle when nothing is pending.
 */
@Composable
fun rememberSpin(active: Boolean): () -> Float {
    if (!active) return { 0f }
    val a = rememberInfiniteTransition(label = "pending")
        .animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "pending")
    return { a.value }
}

/** Draws a small rotating arc over the content: the server is being pinged right now. */
fun Modifier.pendingSpinner(active: Boolean, spin: () -> Float, color: Color = Palette.accent, scale: Float = 0.5f): Modifier =
    if (!active) this else drawWithContent {
        drawContent()
        val d = size.minDimension * scale
        val w = (size.minDimension * 0.08f).coerceIn(2f, 4.dp.toPx())
        drawArc(
            color = color,
            startAngle = spin(),
            sweepAngle = 270f,
            useCenter = false,
            topLeft = Offset((size.width - d) / 2, (size.height - d) / 2),
            size = Size(d, d),
            style = Stroke(width = w, cap = StrokeCap.Round),
        )
    }

/** Toggle chip: filled coral when routing is on. */
@Composable
private fun RoutingChip(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    val bg by animateColorAsState(if (enabled) Palette.accent else Palette.card, label = "routing")
    val fg = if (enabled) Color.White else Palette.textSecondary
    Row(
        Modifier
            .height(44.dp)
            .clip(RoundedCornerShape(50))
            .background(bg)
            .clickable { onToggle(!enabled) }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.AutoMirrored.Rounded.AltRoute, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.routing), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (enabled) Color.White else Palette.text)
    }
}

private val strategies = listOf(
    Triple(Strategy.STABLE, R.string.strategy_stable, R.string.strategy_stable_desc),
    Triple(Strategy.FASTEST, R.string.strategy_fastest, R.string.strategy_fastest_desc),
    Triple(Strategy.FAILOVER, R.string.strategy_failover, R.string.strategy_failover_desc),
    Triple(Strategy.MANUAL, R.string.strategy_manual, R.string.strategy_manual_desc),
)

/** Dropdown with the server selection strategy. */
@Composable
private fun StrategyChip(strategy: Strategy, onChange: (Strategy) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .height(44.dp)
                .clip(RoundedCornerShape(50))
                .background(Palette.card)
                .clickable { open = true }
                .padding(start = 14.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(strategies.first { it.first == strategy }.second), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Palette.text)
            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null, tint = Palette.textSecondary, modifier = Modifier.size(18.dp))
        }
        app.borderless.ui.AppMenu(open, { open = false }) {
            strategies.forEach { (value, title, desc) ->
                app.borderless.ui.AppMenuItem(
                    stringResource(title), sub = stringResource(desc), selected = value == strategy,
                    trailing = if (value == strategy) Icons.Rounded.Check else null,
                ) { onChange(value); open = false }
            }
        }
    }
}

@Composable
private fun PingChip(scan: Scanner.Progress?, onClick: () -> Unit) {
    Row(
        Modifier
            .height(44.dp)
            .clip(RoundedCornerShape(50))
            .background(Palette.card)
            .clickable(enabled = scan == null, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (scan != null) {
            CircularProgressIndicator(
                progress = { if (scan.total == 0) 0f else scan.done.toFloat() / scan.total },
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = Palette.bar,
                trackColor = Palette.outline,
            )
            Spacer(Modifier.width(8.dp))
            Text("${scan.done}/${scan.total}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Palette.text)
        } else {
            Icon(Icons.Rounded.Refresh, contentDescription = null, tint = Palette.textSecondary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.ping_all), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Palette.text)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ServerRow(
    server: Server,
    ping: PingRecord?,
    group: String?,
    current: Boolean,
    /** Queued or being probed: the old result is shown dimmed. */
    pending: Boolean,
    /** Being probed right now: a spinner instead of the ping. */
    probing: Boolean,
    spin: () -> Float,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    /** Shown as a white card under the heatmap; tapping it connects to the server. */
    panel: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val ms = ping?.ms
    val failed = ping != null && !ping.ok && !pending
    Row(
        modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (current) Palette.accentSoft else if (panel) Palette.background else Color.Transparent)
            .then(if (current) Modifier.border(1.5.dp, Palette.accent, RoundedCornerShape(16.dp)) else Modifier)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 10.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(
                    when {
                        ping == null || failed || ms == null -> Palette.card
                        pending -> Palette.ping(ms).copy(alpha = 0.35f)
                        else -> Palette.ping(ms)
                    }
                ),
            contentAlignment = Alignment.Center,
        ) {
            // A dimmed flag is enough to show the server is down.
            FlagIcon(server.country, 17.dp, Modifier.graphicsLayer { alpha = if (failed) 0.45f else 1f })
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                Repo.displayName(server), fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                color = if (failed) Palette.textSecondary else Palette.text,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                listOfNotNull(Countries.name(server.country), group, server.protocol).joinToString(" · "),
                fontSize = 12.5.sp, color = Palette.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(10.dp))
        if (probing) {
            Box(Modifier.size(22.dp).pendingSpinner(true, spin, scale = 0.8f))
        } else {
            Text(
                when {
                    ms != null -> stringResource(R.string.ms, ms)
                    failed -> stringResource(R.string.ping_down)
                    else -> "—"
                },
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = Body,
                color = if (pending) Palette.textMuted else Palette.pingText(ms),
            )
        }
    }
}

@Composable
private fun EmptyHint(onAdd: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 28.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(Palette.card)
            .clickable(onClick = onAdd)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.empty_title), fontSize = 17.sp, fontWeight = FontWeight.Bold, fontFamily = Display, color = Palette.text)
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.empty_text), fontSize = 13.sp, color = Palette.textSecondary, textAlign = TextAlign.Center)
    }
}
