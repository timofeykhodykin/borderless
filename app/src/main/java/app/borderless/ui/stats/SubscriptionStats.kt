package app.borderless.ui.stats

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.borderless.R
import app.borderless.data.Repo
import app.borderless.data.Server
import app.borderless.data.StatsDb
import app.borderless.data.StatsMath
import app.borderless.data.Subscription
import app.borderless.ui.settings.ScreenScaffold
import app.borderless.ui.theme.Palette
import java.text.SimpleDateFormat
import java.util.Date

/**
 * One subscription over a period, like a server's screen for all its servers together: latency (summary,
 * chart with its best servers, distribution), how many of them answered, use and traffic, the provider's
 * reports.
 */
private class SubData(
    val from: Long,
    val to: Long,
    val servers: List<Server>,
    val aggregates: Map<String, StatsDb.Aggregate>,
    /** All samples of its servers together (thinned out for long periods). */
    val summary: StatsMath.Summary,
    val histogram: List<Int>,
    val checked: List<StatsDb.Checked>,
    /** Time each of its servers carried the traffic. */
    val usage: Map<String, Long>,
    /** Bytes each of its servers carried (counted by the app since statistics version 7). */
    val traffic: Map<String, Long>,
    /** How many times one of its servers was switched to. */
    val chosen: Int,
    val reports: List<StatsDb.UsageRow>,
    val segments: List<StatsMath.Segment>,
    val dataStart: Long,
    val zones: Zones,
) {
    val bands: List<Band> get() = bands(segments, maxOf(from, dataStart), to) + zones.eco
}

private suspend fun loadSub(context: Context, subId: String, p: Period, custom: Pair<Long, Long>?): SubData {
    StatsDb.flush()
    val (from, to) = period(p, custom)
    val servers = Repo.servers.value.filter { it.subscriptionId == subId }
    val ids = servers.map { it.id }.toSet()
    val segments = StatsMath.segments(StatsDb.phases(from, to), from, to, to)
    val samples = StatsDb.samplesLimited(from, to, ids, null)
    return SubData(
        from, to, servers,
        StatsDb.aggregates(from, to).filterKeys { it in ids },
        StatsMath.summarize(samples),
        StatsMath.histogram(samples),
        StatsDb.checkedAmong(from, to, AVAILABLE_BUCKETS, ids),
        StatsMath.usage(segments).filterKeys { it in ids },
        StatsDb.trafficByServer(from, to).filterKeys { it in ids },
        StatsDb.events(from, to).count { it.server in ids && it.type != StatsDb.Event.LOST },
        StatsDb.subUsage(subId, from, to),
        segments,
        StatsDb.firstTimestamp() ?: to,
        loadZones(context, from, to),
    )
}

/** Intervals of the "its servers answering" chart (each at least one rescan long, see [StatsDb.checkedAmong]). */
private const val AVAILABLE_BUCKETS = 60

@Composable
fun SubscriptionStatsScreen(subId: String, onBack: () -> Unit, onServer: (String) -> Unit) {
    val context = LocalContext.current
    val subs by Repo.subscriptions.collectAsStateWithLifecycle()
    val sub = subs.firstOrNull { it.id == subId }
    val periodState = rememberPeriod()
    val period = periodState.period
    val custom = periodState.custom
    val tick = rememberLiveTick(period)
    val data by produceState<SubData?>(null, period, custom, tick) { value = loadSub(context, subId, period, custom) }

    ScreenScaffold(sub?.let(Repo::subName) ?: stringResource(R.string.stats), onBack) {
        PeriodChips(period, custom, periodState.select, periodState.setCustom)
        val d = data
        if (d == null || sub == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Palette.accent) }
            return@ScreenScaffold
        }
        val byId = remember(d) { d.servers.associateBy { it.id } }
        val bestLabel = stringResource(R.string.subst_best)
        val pings = remember(d) {
            val ids = d.servers.map { it.id }
            PingSource(d.from, d.to, d.segments, d.aggregates, d.bands, d.zones.strategy, bestLabel) { f, t, b -> StatsDb.bucketsAmong(f, t, b, ids) }
        }
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 40.dp)) {
            item { SubTiles(context, sub, d) }
            // Hidden servers are checked rarely (auto-visibility) or never: a short period may have nothing.
            if (d.summary.count == 0) item {
                Text(stringResource(R.string.subst_no_checks), fontSize = 14.sp, color = Palette.textSecondary, modifier = Modifier.padding(vertical = 16.dp))
            } else {
                item(key = "pings") { PingSection(context, pings, byId, resetKey = period to custom) }
                if (d.checked.isNotEmpty()) item {
                    Card(stringResource(R.string.subst_available_chart)) {
                        TimeChart(
                            listOf(
                                Series(stringResource(R.string.st_checked), Palette.textSecondary, d.checked.map { StatsMath.Point(it.t, it.checked, 0, 1) }, fillAlpha = 0.28f),
                                Series(stringResource(R.string.st_answered), Palette.accent, d.checked.map { StatsMath.Point(it.t, it.answered, 0, 1) }, fillAlpha = 0.3f),
                            ),
                            d.from, d.to, YScale.linear(d.checked.maxOf { it.checked }.toDouble()),
                            format = { app.borderless.data.Units.count(it.toLong()) }, height = 150.dp, fill = true, stepped = true,
                            bands = d.bands, resetKey = period to custom, markers = d.zones.strategy,
                            connectGapMs = CONNECT_GAP_MS, clearLabel = stringResource(R.string.band_clear_server),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.subst_available_note), fontSize = 12.sp, color = Palette.textMuted)
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
            val reported = d.reports.filter { it.used != null }
            if (reported.isNotEmpty()) item {
                // The provider's count, in MB (whole numbers for the chart), shown in GB.
                fun mb(b: Long) = (b / 1_048_576L).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
                val points = reported.map { StatsMath.Point(maxOf(it.ts, d.from), mb(it.used!!), 0, 1) } +
                    StatsMath.Point(d.to, mb(reported.last().used!!), 0, 1)
                val total = reported.last().total
                val max = maxOf(points.maxOf { it.ms ?: 0 }.toDouble(), (total ?: 0) / 1_048_576.0, 1.0)
                Card(stringResource(R.string.subst_reported_chart), header = { AxisUnit(stringResource(R.string.axis_gb)) }) {
                    TimeChart(
                        listOf(Series(stringResource(R.string.subst_used), Palette.accent, points)),
                        d.from, d.to, YScale.linear(max, integer = false, divide = 1024.0), format = { app.borderless.data.Units.bytes((it * 1_048_576).toLong()) },
                        height = 150.dp, fill = true, stepped = true, resetKey = period to custom,
                    )
                    Text(stringResource(R.string.subst_reported_note), fontSize = 12.sp, color = Palette.textMuted, modifier = Modifier.padding(top = 8.dp))
                }
            }
            val rows = d.servers.mapNotNull { s -> d.aggregates[s.id]?.let { s to it } }.sortedByDescending { it.second.availability ?: 0.0 }
            if (rows.isNotEmpty()) item {
                Text(
                    stringResource(R.string.subst_servers), fontSize = 14.5.sp, color = Palette.accentStrong,
                    modifier = Modifier.padding(start = 4.dp, top = 22.dp, bottom = 6.dp),
                )
            }
            items(rows, key = { it.first.id }) { (server, agg) -> ServerStatRow(context, server, agg, d.usage[server.id]) { onServer(server.id) } }
        }
    }
}

@Composable
private fun SubTiles(context: Context, sub: Subscription, d: SubData) {
    val ownTraffic = d.traffic.values.sum()
    val samples = d.aggregates.values
    val count = samples.sumOf { it.count }
    val ok = samples.sumOf { it.ok }
    val last = d.reports.lastOrNull()
    val used = last?.used ?: ((sub.uploadBytes ?: 0) + (sub.downloadBytes ?: 0)).takeIf { sub.uploadBytes != null || sub.downloadBytes != null }
    val total = last?.total ?: sub.totalBytes
    val expire = last?.expire ?: sub.expire
    fun gb(b: Long) = app.borderless.data.Units.bytes(b)
    TileGrid(
        listOf(
            Triple(
                stringResource(R.string.subst_traffic), if (ownTraffic > 0) formatBytes(context, ownTraffic) else "—",
                stringResource(R.string.subst_traffic_sub),
            ),
            Triple(
                stringResource(R.string.subst_provider),
                when {
                    used != null && total != null -> "${gb(used)} / ${gb(total)}"
                    used != null -> gb(used)
                    else -> "—"
                },
                expire?.let { context.getString(R.string.until, SimpleDateFormat("d MMM yyyy", context.resources.configuration.locales[0]).format(Date(it * 1000))) }
                    ?: stringResource(R.string.subst_provider_silent),
            ),
            Triple(
                stringResource(R.string.subst_time),
                d.usage.values.sum().let { if (it == 0L) "—" else formatDuration(context, it) }, stringResource(R.string.subst_time_sub),
            ),
            Triple(stringResource(R.string.ss_chosen), stringResource(R.string.ss_chosen_value, d.chosen), null),
            Triple(
                stringResource(R.string.ss_availability), if (count == 0) "—" else "${ok.toLong() * 100 / count}%",
                stringResource(R.string.subst_answered, samples.count { it.ok > 0 }, samples.size),
            ),
            Triple(
                stringResource(R.string.ss_median), msText(context, d.summary.median),
                if (d.summary.p95 == null) null else stringResource(R.string.ss_p95, msText(context, d.summary.p95)),
            ),
            Triple(
                stringResource(R.string.subst_server_count), d.servers.size.toString(),
                stringResource(R.string.subst_hidden, d.servers.count { !Repo.isActive(it) }),
            ),
            Triple(
                stringResource(R.string.subst_updated),
                if (sub.updatedAt == 0L) "—"
                else DateUtils.getRelativeTimeSpanString(sub.updatedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString(),
                if (sub.viaServer) stringResource(R.string.sub_via_server) else sub.lastError?.let { stringResource(R.string.subst_error) } ?: "",
            ),
        )
    )
}
