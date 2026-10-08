package app.borderless.ui.stats

import androidx.compose.material.icons.rounded.RssFeed
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.drop
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import app.borderless.ui.FlagIcon
import app.borderless.ui.TabPages
import app.borderless.ui.rememberTabs
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.borderless.R
import app.borderless.core.Phase
import app.borderless.data.Countries
import app.borderless.data.Repo
import app.borderless.data.Server
import app.borderless.data.StatsDb
import app.borderless.data.StatsMath
import app.borderless.ui.settings.ScreenScaffold
import app.borderless.ui.theme.Display
import app.borderless.ui.theme.Palette
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.math.roundToInt

enum class Period(val label: Int, val ms: Long?) {
    HOUR(R.string.period_1h, 3_600_000L),
    DAY(R.string.period_24h, 86_400_000L),
    WEEK(R.string.period_7d, 7 * 86_400_000L),
    MONTH(R.string.period_30d, 30 * 86_400_000L),
    ALL(R.string.period_all, null),
    CUSTOM(R.string.period_custom, null),
}

internal const val BUCKETS = 96

/** Width of the percentage after a row's bar: wide enough for "100%", the same in every row. */
private val PERCENT_COLUMN = 54.dp

/** Pings can be minutes apart (idle phone, scans every 10+ min): still one line up to this gap. */
internal const val CONNECT_GAP_MS = 30 * 60_000L

// ------------------------------------------------------------------ formatting

fun formatDuration(context: Context, ms: Long): String {
    val m = ms / 60_000
    return when {
        m < 1 -> context.getString(R.string.dur_lt_min)
        m < 60 -> context.getString(R.string.dur_m, m.toInt())
        m < 24 * 60 -> context.getString(R.string.dur_hm, (m / 60).toInt(), (m % 60).toInt())
        else -> context.getString(R.string.dur_dh, (m / 1440).toInt(), ((m % 1440) / 60).toInt())
    }
}

@Suppress("UNUSED_PARAMETER")
fun formatBytes(context: Context, b: Long): String = app.borderless.data.Units.bytes(b)

/** A ping: "85 ms"; from 10 s on in seconds ("12.4 s" — a timeout-sized value, or damaged data). */
internal fun msText(context: Context, v: Int?) = v?.let { if (it >= 10_000) app.borderless.data.Units.seconds(it / 1000.0) else context.getString(R.string.ms, it) } ?: "—"

// ------------------------------------------------------------------ data

private class Overview(
    val from: Long,
    val to: Long,
    val segments: List<StatsMath.Segment>,
    val durations: Map<Phase, Long>,
    val usage: Map<String, Long>,
    val events: List<StatsDb.EventRow>,
    val scans: List<StatsDb.ScanRow>,
    val traffic: StatsDb.Traffic,
    val health: StatsMath.Summary,
    val aggregates: Map<String, StatsDb.Aggregate>,
    val best: List<StatsMath.Point>,
    /** Statistics exist only from here on; earlier time is unknown, not "off". */
    val dataStart: Long,
    val zones: Zones,
) {
    /** Connection states plus the battery saving mode's periods. */
    val stateBands: List<Band> get() = bands(segments, maxOf(from, dataStart), to) + zones.eco

    val onTime get() = durations.values.sum()
    val empty get() = segments.isEmpty() && aggregates.isEmpty() && scans.isEmpty()
}

/**
 * Changes whenever an open statistics screen should reload: when new data is committed (every ~10 s
 * while the tunnel works), at most every 10 s for short periods and every minute for long ones (their
 * queries cost more), and at least once a minute so the right edge keeps up with the time.
 */
@Composable
internal fun rememberLiveTick(period: Period): Int {
    var tick by remember { mutableIntStateOf(0) }
    // Battery saving mode: at most once a minute for every period.
    val long = period == Period.WEEK || period == Period.MONTH || period == Period.ALL || Repo.settings.value.ecoOn
    LaunchedEffect(long) {
        val minGap = if (long) 60_000L else 10_000L
        var last = System.currentTimeMillis()
        launch { while (true) { delay(60_000); if (System.currentTimeMillis() - last >= 60_000) { last = System.currentTimeMillis(); tick++ } } }
        StatsDb.changes.drop(1).collect {
            val wait = last + minGap - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            last = System.currentTimeMillis()
            tick++
        }
    }
    return tick
}

/**
 * The period of the statistics screens, one for all of them: the overview, a subscription and a server
 * opened from it show the same stretch of time. It starts at one hour each time the statistics are entered
 * (see MainActivity); a custom range stays chosen (across restarts) until another period is picked.
 */
object StatsPeriod {
    internal var period by mutableStateOf(Period.HOUR)

    fun reset() {
        val s = Repo.settings.value
        period = if (s.statsTo > s.statsFrom) Period.CUSTOM else Period.HOUR
    }
}

/** The chosen period of the statistics screens and how to change it. */
internal class PeriodState(val period: Period, val custom: Pair<Long, Long>?, val select: (Period) -> Unit, val setCustom: (Long, Long) -> Unit)

@Composable
internal fun rememberPeriod(): PeriodState {
    val s by Repo.settings.collectAsStateWithLifecycle()
    val saved = (s.statsFrom to s.statsTo).takeIf { s.statsTo > s.statsFrom }
    if (StatsPeriod.period == Period.CUSTOM && saved == null) StatsPeriod.period = Period.HOUR
    val period = StatsPeriod.period
    return PeriodState(
        period, saved?.takeIf { period == Period.CUSTOM },
        select = { p -> StatsPeriod.period = p; Repo.updateSettings { it.copy(statsFrom = 0, statsTo = 0) } },
        setCustom = { f, t -> Repo.updateSettings { it.copy(statsFrom = f, statsTo = t) }; StatsPeriod.period = Period.CUSTOM },
    )
}

internal suspend fun period(p: Period, custom: Pair<Long, Long>? = null): Pair<Long, Long> {
    if (p == Period.CUSTOM && custom != null) return custom
    val now = System.currentTimeMillis()
    val from = p.ms?.let { now - it } ?: (StatsDb.firstTimestamp() ?: (now - 3_600_000L))
    return from to now
}

private suspend fun loadOverview(context: Context, p: Period, custom: Pair<Long, Long>?): Overview {
    StatsDb.flush()
    val (from, to) = period(p, custom)
    val segments = StatsMath.segments(StatsDb.phases(from, to), from, to, to)
    return Overview(
        from = from,
        to = to,
        segments = segments,
        durations = StatsMath.durations(segments),
        usage = StatsMath.usage(segments),
        events = StatsDb.events(from, to),
        scans = StatsDb.scans(from, to),
        traffic = StatsDb.traffic(from, to),
        health = StatsMath.summarize(StatsDb.samplesLimited(from, to, null, StatsDb.Kind.HEALTH)),
        aggregates = StatsDb.aggregates(from, to),
        best = StatsDb.buckets(from, to, BUCKETS, null),
        dataStart = StatsDb.firstTimestamp() ?: to,
        zones = loadZones(context, from, to),
    )
}

// ------------------------------------------------------------------ overview screen

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StatsScreen(onBack: () -> Unit, onServer: (String) -> Unit, onSubscription: (String) -> Unit = {}) {
    val context = LocalContext.current
    val periodState = rememberPeriod()
    val period = periodState.period
    val custom = periodState.custom
    // 0 = servers, 1 = app activity (battery / network load); tap a tab or swipe sideways.
    val tabs = rememberTabs(2)
    val tick = rememberLiveTick(period)
    val data by produceState<Overview?>(null, period, custom, tick) { value = loadOverview(context, period, custom) }
    val servers by Repo.servers.collectAsStateWithLifecycle()
    var sort by rememberSaveable { mutableIntStateOf(0) }
    var subSort by rememberSaveable { mutableIntStateOf(0) }

    ScreenScaffold(stringResource(R.string.stats), onBack) {
        StatsTabs(tabs)
        PeriodChips(period, custom, periodState.select, periodState.setCustom)
        TabPages(tabs) { page ->
            if (page == 1) {
                ActivityTab(period, custom, tick)
                return@TabPages
            }
            val d = data
            if (d == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Palette.accent) }
                return@TabPages
            }
            val byId = remember(servers) { servers.associateBy { it.id } }
            val bestLabel = stringResource(R.string.best_of_all)
            val pings = remember(d) {
                PingSource(d.from, d.to, d.segments, d.aggregates, d.stateBands, d.zones.strategy, bestLabel) { f, t, b -> StatsDb.buckets(f, t, b, null) }
            }
            LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 40.dp)) {
                if (d.empty) {
                    item { Text(stringResource(R.string.st_no_data), fontSize = 14.sp, color = Palette.textSecondary, modifier = Modifier.padding(vertical = 24.dp)) }
                    return@LazyColumn
                }
                item { SummaryTiles(context, d, period) }
                item {
                    Card(stringResource(R.string.sec_states)) {
                        StateTimeline(d.segments, d.from, d.to)
                        Spacer(Modifier.height(8.dp))
                        PhaseLegend(context, d.durations)
                    }
                }
                if (d.scans.isNotEmpty()) item {
                    Card(stringResource(R.string.sec_available_chart)) {
                        // Scans don't always check every server (auto-visibility, battery saving): the grey
                        // envelope is how many were checked, the coloured area how many of them answered.
                        val total = d.scans.maxOf { it.total }
                        TimeChart(
                            listOf(
                                Series(stringResource(R.string.st_checked), Palette.textSecondary, d.scans.map { StatsMath.Point(it.ts, it.total, 0, 1) }, fillAlpha = 0.28f),
                                Series(stringResource(R.string.st_answered), Palette.accent, d.scans.map { StatsMath.Point(it.ts, it.available, 0, 1) }, fillAlpha = 0.3f),
                            ),
                            d.from, d.to, YScale.linear(total.toDouble()),
                            format = { app.borderless.data.Units.count(it.toLong()) }, height = 150.dp, fill = true, stepped = true,
                            bands = d.stateBands, resetKey = period to custom, markers = d.zones.strategy,
                            connectGapMs = CONNECT_GAP_MS, clearLabel = stringResource(R.string.band_clear_server),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.available_chart_note), fontSize = 12.sp, color = Palette.textMuted)
                    }
                }
                item(key = "pings") { PingSection(context, pings, byId, resetKey = period to custom) }
                // Subscriptions as a whole: their servers' availability, ping and the connected time through them.
                val subRows = Repo.subscriptions.value.map { sub ->
                    val ids = Repo.servers.value.filter { it.subscriptionId == sub.id }.map { it.id }.toSet()
                    val aggs = d.aggregates.filterKeys { it in ids }.values
                    val count = aggs.sumOf { it.count }
                    val ok = aggs.sumOf { it.ok }
                    // Average latency of the answers, weighted by how many answers each server gave.
                    val avg = aggs.filter { it.avg != null && it.ok > 0 }.let { a -> if (a.isEmpty()) null else a.sumOf { it.avg!!.toLong() * it.ok } / a.sumOf { it.ok } }
                    SubStat(sub.id, Repo.subName(sub), ids.size, if (count == 0) null else ok.toDouble() / count, avg?.toInt(), d.usage.filterKeys { it in ids }.values.sum())
                }.let { list ->
                    when (subSort) {
                        1 -> list.sortedBy { it.avg ?: Int.MAX_VALUE }
                        2 -> list.sortedByDescending { it.time }
                        else -> list.sortedByDescending { it.availability ?: -1.0 }
                    }
                }
                if (subRows.isNotEmpty()) {
                    item { ListHeader(R.string.sec_subs_stats, R.string.subs_percent_legend, subSort) { subSort = it } }
                    items(subRows, key = { "sub:" + it.id }) { r -> SubStatRow(context, r) { onSubscription(r.id) } }
                }
                item { ListHeader(R.string.sec_servers_stats, R.string.servers_percent_legend, sort) { sort = it } }
                val rows = d.aggregates.entries.mapNotNull { (id, a) -> byId[id]?.let { it to a } }.let { list ->
                    when (sort) {
                        1 -> list.sortedBy { it.second.avg ?: Int.MAX_VALUE }
                        2 -> list.sortedByDescending { d.usage[it.first.id] ?: 0 }
                        else -> list.sortedByDescending { it.second.availability ?: 0.0 }
                    }
                }
                items(rows, key = { it.first.id }) { (server, agg) -> ServerStatRow(context, server, agg, d.usage[server.id]) { onServer(server.id) } }
            }
        }
    }
}

@Composable
internal fun PeriodChips(period: Period, custom: Pair<Long, Long>?, onChange: (Period) -> Unit, onCustom: (Long, Long) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Period.entries.forEach { p ->
            if (p == Period.CUSTOM) {
                val label = custom?.let { formatRange(it.first, it.second) } ?: stringResource(p.label)
                Chip(label, p == period) { picking = true }
            } else {
                Chip(stringResource(p.label), p == period) { onChange(p) }
            }
        }
    }
    if (picking) {
        val now = System.currentTimeMillis()
        RangeDialog(custom?.first ?: (now - 3 * 3_600_000L), custom?.second ?: now, onDismiss = { picking = false }, onApply = onCustom)
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, dot: Color? = null, flag: String? = null, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) Palette.selected else Palette.card)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot != null) {
            Box(Modifier.size(9.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(6.dp))
        }
        if (flag != null) {
            FlagIcon(flag, 12.dp)
            Spacer(Modifier.width(6.dp))
        }
        Text(label, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = if (selected) Palette.accentStrong else Palette.text, maxLines = 1)
    }
}

/** The unit of a chart's value axis, on the line of the card's title. */
@Composable
internal fun RowScope.AxisUnit(text: String) =
    Text(text, fontSize = 11.sp, color = Palette.textMuted, modifier = Modifier.alignByBaseline())

@Composable
internal fun Card(title: String, header: (@Composable RowScope.() -> Unit)? = null, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 14.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Palette.card)
            .padding(16.dp),
    ) {
        // [header]: extra items on the title's line (e.g. column headings), aligned by baseline.
        Row {
            Text(
                title, fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = Display, color = Palette.text,
                modifier = Modifier.weight(1f).alignByBaseline(),
            )
            header?.invoke(this)
        }
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun Tile(label: String, value: String, sub: String?, modifier: Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Palette.card)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(label, fontSize = 12.sp, color = Palette.textSecondary)
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = Display, color = Palette.text, maxLines = 1)
        // Always there (empty if nothing to say), so tiles don't differ in height.
        Text(sub.orEmpty(), fontSize = 11.5.sp, color = Palette.textSecondary, minLines = 1, maxLines = 2)
    }
}

@Composable
internal fun TileGrid(tiles: List<Triple<String, String, String?>>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        tiles.chunked(2).forEach { row ->
            // Both tiles of a row as tall as the taller one.
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (l, v, s) -> Tile(l, v, s, Modifier.weight(1f).fillMaxHeight()) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SummaryTiles(context: Context, d: Overview, period: Period) {
    val on = d.onTime
    val connected = d.durations[Phase.CONNECTED] ?: 0
    val without = (d.durations[Phase.DIRECT] ?: 0) + (d.durations[Phase.PAUSED] ?: 0)
    val lost = d.events.count { it.type == StatsDb.Event.LOST }
    val failover = d.events.count { it.type == StatsDb.Event.FAILOVER }
    val faster = d.events.count { it.type == StatsDb.Event.OPTIMIZE || it.type == StatsDb.Event.BEST }
    val manual = d.events.count { it.type == StatsDb.Event.MANUAL }
    val avail = d.scans.takeIf { it.isNotEmpty() }
    fun pct(part: Long, whole: Long) = if (whole <= 0) "—" else "${part * 100 / whole}%"
    val periodText = if (period == Period.ALL) stringResource(R.string.st_since, SimpleDateFormat("d MMM yyyy", app.borderless.Res.locale).format(Date(d.from)))
    else stringResource(R.string.st_of, formatDuration(context, d.to - d.from))
    TileGrid(
        listOf(
            Triple(stringResource(R.string.st_on_time), formatDuration(context, on), periodText),
            Triple(stringResource(R.string.st_via_server), pct(connected, on), formatDuration(context, connected)),
            Triple(stringResource(R.string.st_without), formatDuration(context, without), pct(without, on)),
            Triple(
                stringResource(R.string.st_failures), app.borderless.data.Units.count(lost.toLong()),
                if (lost == 0) stringResource(R.string.st_fail_none) else stringResource(R.string.st_fail_every, formatDuration(context, on / lost)),
            ),
            Triple(stringResource(R.string.st_switches), app.borderless.data.Units.count((failover + faster + manual).toLong()), stringResource(R.string.st_switch_breakdown, failover, faster, manual)),
            Triple(
                stringResource(R.string.st_ping), msText(context, d.health.median),
                if (d.health.median == null) null else stringResource(R.string.st_ping_sub, msText(context, d.health.p95), msText(context, d.health.jitter)),
            ),
            // Share of checked servers that answered (scans may check only some), and a typical scan.
            Triple(
                stringResource(R.string.st_available),
                avail?.let { a -> a.sumOf { it.total }.takeIf { it > 0 }?.let { t -> "${a.sumOf { it.available } * 100 / t}%" } } ?: "—",
                avail?.let { a ->
                    stringResource(R.string.st_available_sub2, a.map { it.available }.average().roundToInt(), a.map { it.total }.average().roundToInt())
                },
            ),
            Triple(stringResource(R.string.st_traffic), formatBytes(context, d.traffic.proxy), stringResource(R.string.st_traffic_sub, formatBytes(context, d.traffic.direct))),
        )
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PhaseLegend(context: Context, durations: Map<Phase, Long>) {
    val names = mapOf(
        Phase.CONNECTED to R.string.ph_connected, Phase.SEARCHING to R.string.ph_searching, Phase.DIRECT to R.string.ph_direct,
        Phase.PAUSED to R.string.ph_paused, Phase.NO_NETWORK to R.string.ph_no_network,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        names.forEach { (phase, label) ->
            val ms = durations[phase] ?: return@forEach
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).clip(RoundedCornerShape(2.dp)).background(phaseColor(phase)))
                Spacer(Modifier.width(5.dp))
                Text("${stringResource(label)} ${formatDuration(context, ms)}", fontSize = 12.sp, color = Palette.textSecondary)
            }
        }
    }
}

/**
 * What the ping chart with its choice of servers works on: a period, the servers that may be shown (those
 * with samples in [aggregates] and in the caller's server map) and the first, muted line — the best of
 * all servers on the overview, the best of one subscription's servers on its screen.
 */
internal class PingSource(
    val from: Long,
    val to: Long,
    val segments: List<StatsMath.Segment>,
    val aggregates: Map<String, StatsDb.Aggregate>,
    val bands: List<Band>,
    val markers: List<Marker>,
    val bestLabel: String,
    val best: suspend (from: Long, to: Long, buckets: Int) -> List<StatsMath.Point>,
)

/**
 * The ping chart: the best line and a few servers — automatically the one in use and the best ones,
 * until the user picks their own ("Best 5" returns to automatic).
 */
@Composable
internal fun PingSection(context: Context, src: PingSource, byId: Map<String, Server>, resetKey: Any) {
    var manual by rememberSaveable { mutableStateOf(false) }
    var picked by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    val palette = remember { HashMap<String, Int>() }
    val selected = if (manual) picked.filter { it in byId } else remember(src, byId) { topServers(src, byId) }
    // Colours follow the server: one that stays on the chart keeps its colour.
    val colorOf = remember(selected) {
        palette.keys.retainAll(selected.toSet())
        selected.forEach { id -> if (id !in palette) palette[id] = SeriesColors.indices.first { it !in palette.values } }
        selected.associateWith { SeriesColors[palette.getValue(it)] }
    }
    PingCard(
        context, src, selected, colorOf, byId, auto = !manual, resetKey = resetKey,
        onToggle = { id ->
            picked = if (id in selected) selected - id else if (selected.size < SeriesColors.size) selected + id else selected
            manual = true
        },
        onAuto = { manual = false },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PingCard(
    context: Context,
    d: PingSource,
    selected: List<String>,
    colorOf: Map<String, Color>,
    byId: Map<String, Server>,
    auto: Boolean,
    resetKey: Any,
    onToggle: (String) -> Unit,
    onAuto: () -> Unit,
) {
    // The best line first, then the selected servers; the same list for any time window.
    suspend fun load(from: Long, to: Long, buckets: Int): List<Series> = buildList {
        add(Series(d.bestLabel, Palette.textMuted, d.best(from, to, buckets)))
        selected.forEach { id ->
            val s = byId[id] ?: return@forEach
            add(Series(shortName(Repo.displayName(s), 18), colorOf.getValue(id), StatsDb.buckets(from, to, buckets, id)))
        }
    }
    val series by produceState(emptyList<Series>(), selected, d) { value = load(d.from, d.to, BUCKETS) }
    Card(stringResource(R.string.sec_ping_chart), header = { AxisUnit(stringResource(R.string.ms_unit)) }) {
        TimeChart(
            series, d.from, d.to, YScale.latency(), format = { msText(context, it.toInt()) }, height = 210.dp,
            bands = d.bands, resetKey = resetKey, markers = d.markers,
            connectGapMs = CONNECT_GAP_MS, clearLabel = stringResource(R.string.band_clear_server),
            detail = { f, t -> load(f, t, BUCKETS * 2) },
        )
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(if (auto) R.string.ping_chart_auto else R.string.ping_chart_hint),
            fontSize = 12.sp, color = Palette.textMuted,
        )
        Spacer(Modifier.height(8.dp))
        // Selected servers (tap to remove) and a button that opens the full list.
        var picking by remember { mutableStateOf(false) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            selected.forEach { id ->
                val s = byId[id] ?: return@forEach
                Chip("${shortName(Repo.displayName(s), 22)}  ✕", true, colorOf[id], s.country) { onToggle(id) }
            }
            Chip("+ " + stringResource(R.string.choose_servers), false) { picking = true }
            if (!auto) Chip("↺ " + stringResource(R.string.ping_chart_best), false, onClick = onAuto)
        }
        if (picking) ServerPicker(d, byId, selected, onToggle) { picking = false }
    }
}

/**
 * A name cut to [max] characters in the middle: servers of one subscription often share a long start
 * ("host.example #3", "host.example #4"), and the end is what tells them apart.
 */
internal fun shortName(name: String, max: Int): String =
    if (name.length <= max) name else name.take(max - 7).trimEnd() + "…" + name.takeLast(6).trimStart()

private fun score(a: StatsDb.Aggregate) = StatsMath.score(a.count, a.ok, a.avg?.toDouble())

/**
 * Servers the ping chart shows by default: the one in use at the end of the period (or the last one
 * used), then the best by availability and latency over the period ([StatsMath.score]).
 */
private fun topServers(d: PingSource, byId: Map<String, Server>, n: Int = 5): List<String> {
    val current = d.segments.lastOrNull { it.phase == Phase.CONNECTED }?.server
    val best = d.aggregates.entries.filter { it.key in byId && it.value.ok > 0 }.sortedByDescending { score(it.value) }.map { it.key }
    return (listOfNotNull(current?.takeIf { it in byId }) + best).distinct().take(n)
}

/** Checklist of servers with samples in the period, best first; at most eight selected. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerPicker(
    d: PingSource,
    byId: Map<String, Server>,
    selected: List<String>,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val candidates = remember(d) {
        d.aggregates.entries.filter { it.key in byId }.sortedByDescending { score(it.value) }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Palette.background) {
        Text(
            stringResource(R.string.choose_servers), fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = Display,
            color = Palette.text, modifier = Modifier.padding(horizontal = 20.dp),
        )
        LazyColumn(Modifier.heightIn(max = 520.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
            items(candidates, key = { it.key }) { (id, agg) ->
                val s = byId.getValue(id)
                val checked = id in selected
                val full = !checked && selected.size >= SeriesColors.size
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(enabled = !full) { onToggle(id) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = checked, enabled = !full, onCheckedChange = { onToggle(id) },
                        colors = CheckboxDefaults.colors(checkedColor = Palette.accent, uncheckedColor = Palette.textMuted),
                    )
                    FlagIcon(s.country, 13.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(Repo.displayName(s), fontSize = 15.sp, color = Palette.text,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(
                        "${((agg.availability ?: 0.0) * 100).toInt()}% · ${msText(LocalContext.current, agg.avg)}",
                        fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Palette.textSecondary,
                    )
                }
            }
        }
        TextButton(onDismiss, Modifier.align(Alignment.End).padding(end = 12.dp, bottom = 16.dp)) { Text(stringResource(R.string.done)) }
    }
}

/** Title of a list (subscriptions, servers) with what its percentages mean and the sort chips. */
@Composable
private fun ListHeader(title: Int, legend: Int, sort: Int, onSort: (Int) -> Unit) {
    Column(Modifier.padding(top = 22.dp, bottom = 6.dp)) {
        Text(stringResource(title), fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = Display, color = Palette.text)
        Text(stringResource(legend), fontSize = 12.5.sp, color = Palette.textSecondary, modifier = Modifier.padding(top = 2.dp))
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(R.string.sort_availability, R.string.sort_ping, R.string.sort_usage).forEachIndexed { i, label ->
                Chip(stringResource(label), sort == i) { onSort(i) }
            }
        }
    }
}

/** One subscription in the overview: its servers' answers, average ping and connected time through it. */
private class SubStat(val id: String, val name: String, val servers: Int, val availability: Double?, val avg: Int?, val time: Long)

@Composable
private fun SubStatRow(context: Context, r: SubStat, onClick: () -> Unit) {
    val name = r.name
    val availability = r.availability
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(Palette.accentSoft), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.RssFeed, null, tint = Palette.accent, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = Palette.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(R.string.sub_stat_row, r.servers, msText(context, r.avg)) + (r.time.takeIf { it > 0 }?.let { " · " + formatDuration(context, it) } ?: ""),
                fontSize = 12.sp, color = Palette.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Palette.cardStrong)) {
                Box(Modifier.fillMaxWidth((availability ?: 0.0).toFloat()).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Palette.bar))
            }
        }
        Spacer(Modifier.width(12.dp))
        // A fixed column, so every row's bar has the same length ("—" and "100%" alike).
        Text(
            availability?.let { "${(it * 100).toInt()}%" } ?: "—", fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = Display,
            color = Palette.text, textAlign = TextAlign.End, maxLines = 1, modifier = Modifier.width(PERCENT_COLUMN),
        )
    }
}

@Composable
internal fun ServerStatRow(context: Context, server: Server, agg: StatsDb.Aggregate, usage: Long?, onClick: () -> Unit) {
    val av = agg.availability ?: 0.0
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(if (agg.avg != null) Palette.ping(agg.avg) else Palette.card), contentAlignment = Alignment.Center) {
            FlagIcon(server.country, 16.dp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(Repo.displayName(server), fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = Palette.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(R.string.server_row_sub, msText(context, agg.avg), pluralStringResource(R.plurals.samples_n, agg.count, agg.count)) + (usage?.let { " · " + formatDuration(context, it) } ?: ""),
                fontSize = 12.sp, color = Palette.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            // Availability bar.
            Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Palette.cardStrong)) {
                Box(Modifier.fillMaxWidth(av.toFloat()).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Palette.bar))
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            "${(av * 100).toInt()}%", fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = Display, color = Palette.text,
            textAlign = TextAlign.End, maxLines = 1, modifier = Modifier.width(PERCENT_COLUMN),
        )
    }
}

// ------------------------------------------------------------------ server screen

private class ServerData(
    val from: Long,
    val to: Long,
    val summary: StatsMath.Summary,
    val points: List<StatsMath.Point>,
    val histogram: List<Int>,
    val usage: Long,
    val chosen: Int,
    val segments: List<StatsMath.Segment>,
    val dataStart: Long,
    val zones: Zones,
)

private suspend fun loadServer(context: Context, id: String, p: Period, custom: Pair<Long, Long>?): ServerData {
    StatsDb.flush()
    val (from, to) = period(p, custom)
    val samples = StatsDb.samplesLimited(from, to, listOf(id), null)
    val segments = StatsMath.segments(StatsDb.phases(from, to), from, to, to)
    val chosen = StatsDb.events(from, to).count { it.server == id && it.type != StatsDb.Event.LOST }
    return ServerData(
        from, to,
        StatsMath.summarize(samples),
        StatsMath.bucket(samples, from, to, BUCKETS),
        StatsMath.histogram(samples),
        StatsMath.usage(segments)[id] ?: 0,
        chosen,
        segments,
        StatsDb.firstTimestamp() ?: to,
        loadZones(context, from, to),
    )
}

@Composable
fun ServerStatsScreen(serverId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val server = Repo.server(serverId)
    val periodState = rememberPeriod()
    val period = periodState.period
    val custom = periodState.custom
    val tick = rememberLiveTick(period)
    val data by produceState<ServerData?>(null, period, custom, tick) { value = loadServer(context, serverId, period, custom) }

    ScreenScaffold(server?.let { Repo.displayName(it) } ?: stringResource(R.string.stats), onBack) {
        PeriodChips(period, custom, periodState.select, periodState.setCustom)
        val d = data
        if (d == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Palette.accent) }
            return@ScreenScaffold
        }
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 40.dp)) {
            if (d.summary.count == 0) {
                item { Text(stringResource(R.string.st_no_data), fontSize = 14.sp, color = Palette.textSecondary, modifier = Modifier.padding(vertical = 24.dp)) }
                return@LazyColumn
            }
            item {
                val s = d.summary
                TileGrid(
                    listOf(
                        Triple(stringResource(R.string.ss_availability), "${((s.availability ?: 0.0) * 100).toInt()}%", "${s.ok} / ${s.count}"),
                        Triple(stringResource(R.string.ss_median), msText(context, s.median), stringResource(R.string.ss_p95, msText(context, s.p95))),
                        Triple(stringResource(R.string.ss_minmax), if (s.min == null || s.max == null) "—" else "${s.min} / ${stringResource(R.string.ms, s.max)}", null),
                        Triple(stringResource(R.string.ss_jitter), msText(context, s.jitter), null),
                        Triple(stringResource(R.string.ss_usage), if (d.usage == 0L) "—" else formatDuration(context, d.usage), null),
                        Triple(stringResource(R.string.ss_chosen), stringResource(R.string.ss_chosen_value, d.chosen), null),
                    )
                )
            }
            item {
                val label = server?.let { Repo.displayName(it).take(24) } ?: ""
                Card(stringResource(R.string.sec_ping_chart), header = { AxisUnit(stringResource(R.string.ms_unit)) }) {
                    TimeChart(
                        listOf(Series(label, Palette.accent, d.points)),
                        d.from, d.to, YScale.latency(), format = { msText(context, it.toInt()) }, failures = true,
                        bands = bands(d.segments, maxOf(d.from, d.dataStart), d.to) + d.zones.eco, resetKey = period to custom,
                        markers = d.zones.strategy,
                        connectGapMs = CONNECT_GAP_MS, clearLabel = stringResource(R.string.band_clear_server),
                        detail = { f, t -> listOf(Series(label, Palette.accent, StatsDb.buckets(f, t, BUCKETS * 2, serverId))) },
                    )
                }
            }
            item {
                Card(stringResource(R.string.sec_distribution)) {
                    val unit = stringResource(R.string.ms_unit)
                    val e = StatsMath.histogramEdges
                    val labels = e.zipWithNext().mapIndexed { i, (a, b) ->
                        when (i) {
                            0 -> "< $b $unit"
                            e.size - 2 -> "> $a $unit"
                            else -> "$a–$b"
                        }
                    }
                    val colors = e.zipWithNext().map { (a, b) -> Palette.ping(if (b == Int.MAX_VALUE) a * 2 else (a + b) / 2) }
                    Histogram(d.histogram, labels, colors)
                }
            }
        }
    }
}
