package app.borderless.ui.stats

import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.borderless.R
import app.borderless.data.Activity
import app.borderless.data.ActivityMath
import app.borderless.data.ProtocolCost
import app.borderless.data.StatsDb
import app.borderless.data.StatsMath
import app.borderless.data.Units
import app.borderless.ui.theme.Display
import app.borderless.ui.theme.Palette
import kotlin.math.roundToInt

/** Servers / App activity switch at the top of the statistics screen. */
@Composable
internal fun StatsTabs(tabs: androidx.compose.foundation.pager.PagerState) =
    app.borderless.ui.TabStrip(listOf(stringResource(R.string.stats_tab_servers), stringResource(R.string.stats_tab_activity)), tabs)

private class ActivityData(
    val from: Long,
    val to: Long,
    val totals: Map<String, StatsDb.ActivityTotal>,
    val measured: List<StatsDb.MeasuredKind>,
    val buckets: List<Triple<Long, String, Long>>,
    val power: List<StatsDb.PowerRow>,
    val summary: ActivityMath.PowerSummary,
    val segments: List<StatsMath.Segment>,
    val dataStart: Long,
    val zones: Zones,
    val protocols: Map<String, ProtocolCost.Measured>,
) {
    /** Everything shaded behind the activity charts (see [stateBands]). */
    val bands: List<Band> by lazy { stateBands(power) + bands(segments, maxOf(from, dataStart), to) + zones.eco }
}

private suspend fun loadActivity(context: Context, p: Period, custom: Pair<Long, Long>?): ActivityData {
    StatsDb.flush()
    val (from, to) = period(p, custom)
    val power = StatsDb.powerRows(from, to)
    val summary = ActivityMath.summarize(power)
    val zones = loadZones(context, from, to)
    return ActivityData(
        from, to, StatsDb.activityTotals(from, to), StatsDb.measuredKinds(from, to),
        StatsDb.activityBuckets(from, to, BUCKETS), power, summary,
        StatsMath.segments(StatsDb.phases(from, to), from, to, to), StatsDb.firstTimestamp() ?: to,
        zones,
        StatsDb.coreCostByClass(from, to),
    )
}

/**
 * What the app itself costs: its network actions (and which of them likely woke the radio), CPU
 * time, memory, and the phone's battery with the conditions (screen, mobile data, saver, charging).
 */
@Composable
internal fun ActivityTab(period: Period, custom: Pair<Long, Long>?, tick: Int) {
    val context = LocalContext.current
    val data by produceState<ActivityData?>(null, period, custom, tick) { value = loadActivity(context, period, custom) }
    val d = data
    if (d == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Palette.accent) }
        return
    }
    val empty = d.totals.isEmpty() && d.power.size < 2
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 40.dp)) {
        if (empty) {
            item { Text(stringResource(R.string.act_no_data), fontSize = 14.sp, color = Palette.textSecondary, modifier = Modifier.padding(vertical = 24.dp)) }
            return@LazyColumn
        }
        item { ActivityTiles(context, d) }
        item { EnergyCard(context, d) }
        item { CostCard(context, d.totals, d.measured) }
        item { ProtocolsCard(context, d.protocols) }
        item { NetworkChart(context, d) }
        if (d.power.size >= 2) item { CpuChart(context, d) }
        item { Conditions(context, d.summary, ActivityMath.hoursIn(d.power, d.zones.eco.map { it.start to it.end })) }
        item { Breakdown(context, d.totals) }
        item {
            Text(stringResource(R.string.act_note), fontSize = 12.sp, color = Palette.textMuted, modifier = Modifier.padding(top = 14.dp))
        }
    }
}


private fun perHour(n: Long, hours: Double): String? = if (hours < 0.25) null else (n / hours).let { if (it < 100) "%.1f".format(it) else Units.count(it.toLong()) }

@Composable
private fun ActivityTiles(context: Context, d: ActivityData) {
    val t = d.totals
    // Hours the period actually covers with the app running, else the period length.
    val hours = d.summary.hours.takeIf { it > 0 } ?: ((d.to - d.from) / 3_600_000.0)
    val pings = ActivityMath.total(t, Activity.Group.PING)
    val probes = ActivityMath.total(t, Activity.Group.PROBE)
    val other = ActivityMath.total(t, Activity.Group.OTHER)
    val scans = ActivityMath.total(t, Activity.Group.SCAN)
    val network = pings + probes + other
    val wake = ActivityMath.wakeups(t)
    val ride = t[Activity.Kind.PING_RIDE.name]?.n ?: 0L
    val s = d.summary
    fun ph(n: Long) = perHour(n, hours)?.let { context.getString(R.string.act_per_hour, it) }
    TileGrid(
        listOf(
            Triple(stringResource(R.string.act_network), Units.count(network), ph(network)),
            Triple(stringResource(R.string.act_wakeups), Units.count(wake), ph(wake)),
            Triple(
                stringResource(R.string.act_pings), Units.count(pings),
                if (pings == 0L) null else stringResource(R.string.act_pings_sub, (ride * 100 / pings).toInt()),
            ),
            Triple(stringResource(R.string.act_probes), Units.count(probes), pluralStringResource(R.plurals.act_scans, scans.toInt(), scans.toInt())),
            Triple(
                stringResource(R.string.act_cpu), Units.seconds(s.cpuSeconds),
                s.cpuPerHour?.let { stringResource(R.string.act_per_hour, Units.seconds(it)) },
            ),
            run {
                // The app's own estimated use (all categories), not the whole phone's drain, averaged over the
                // hours it ran in the period (said in the sub line).
                val mj = d.totals.values.sumOf { it.mj }.toDouble()
                val cpu = d.totals.values.sumOf { it.cpuMs }
                val r = ActivityMath.rate(mj, cpu, s.hours, s.volts, s.capacityMah).takeIf { s.hours >= 0.25 }
                val over = stringResource(R.string.act_app_use_sub, formatDuration(context, (s.hours * 3_600_000).toLong()))
                Triple(
                    stringResource(R.string.act_app_use),
                    r?.let { rateText(it) } ?: "—",
                    if (r == null) over else stringResource(R.string.act_pm_sub, pmText(r), over),
                )
            },
            Triple(
                stringResource(R.string.act_memory), s.avgMemoryMb?.let { stringResource(R.string.act_mb, it.roundToInt()) } ?: "—",
                s.maxMemoryMb?.let { stringResource(R.string.act_memory_sub, it.roundToInt()) },
            ),
            Triple(
                stringResource(R.string.act_saved), Units.count(t[Activity.Kind.RESCAN_SKIPPED.name]?.n ?: 0L),
                stringResource(R.string.act_saved_sub),
            ),
        )
    )
}

/** "0.25%/h" (or mAh/h when the capacity is unknown). */
@Composable
private fun rateText(r: ActivityMath.Rate): String =
    r.percentPerHour?.let { stringResource(R.string.act_drain_value, "%.2f".format(it)) } ?: stringResource(R.string.act_mah_h, "%.1f".format(r.mahPerHour))

/** The rate's absolute error, in the same unit. */
@Composable
private fun pmText(r: ActivityMath.Rate): String =
    r.percentPlusMinus?.let { stringResource(R.string.act_drain_value, "%.2f".format(it)) } ?: stringResource(R.string.act_mah_h, "%.1f".format(r.mahPlusMinus))

@Composable
private fun NetworkChart(context: Context, d: ActivityData) {
    val group = Activity.Kind.entries.associate { it.name to it.group }
    fun series(label: Int, color: androidx.compose.ui.graphics.Color, groups: Set<Activity.Group>) = Series(
        context.getString(label), color,
        d.buckets.filter { group[it.second] in groups }.groupBy { it.first }.toSortedMap()
            .map { (t, rows) -> StatsMath.Point(t, rows.sumOf { it.third }.toInt(), 0, 1) },
    )
    val all = listOf(
        series(R.string.act_series_pings, SeriesColors[0], setOf(Activity.Group.PING)),
        series(R.string.act_series_probes, SeriesColors[1], setOf(Activity.Group.PROBE)),
        series(R.string.act_series_other, SeriesColors[2], setOf(Activity.Group.OTHER)),
    ).filter { it.points.isNotEmpty() }
    if (all.isEmpty()) return
    val max = all.maxOf { s -> s.points.maxOf { it.ms ?: 0 } }.toDouble()
    Card(stringResource(R.string.act_chart_network)) {
        TimeChart(
            all, d.from, d.to, YScale.linear(max), format = { Units.count(it.toLong()) }, height = 170.dp,
            bands = d.bands, resetKey = d.from to d.to, markers = d.zones.strategy,
            connectGapMs = ActivityMath.MAX_GAP_MS, clearLabel = stringResource(R.string.band_clear_phone),
        )
    }
}

@Composable
private fun CpuChart(context: Context, d: ActivityData) {
    // In milliseconds (integer chart points); the axis in seconds, minutes or hours, whichever fits the amounts.
    val points = ActivityMath.cpuBuckets(d.power, d.from, d.to, BUCKETS).map { (t, s) -> StatsMath.Point(t, (s * 1000).roundToInt(), 0, 1) }
    if (points.isEmpty()) return
    val max = points.maxOf { it.ms ?: 0 }.toDouble()
    val divide = when {
        max >= 2 * 3_600_000.0 -> 3_600_000.0
        max >= 120_000.0 -> 60_000.0
        else -> 1000.0
    }
    val unit = stringResource(
        when (divide) {
            3_600_000.0 -> R.string.act_axis_h
            60_000.0 -> R.string.act_axis_min
            else -> R.string.act_axis_s
        }
    )
    Card(stringResource(R.string.act_chart_cpu), header = { AxisUnit(unit) }) {
        TimeChart(
            listOf(Series(context.getString(R.string.act_series_cpu), SeriesColors[6], points)),
            d.from, d.to, YScale.linear(max, integer = false, divide = divide), format = { Units.seconds(it / 1000) },
            height = 150.dp, bands = d.bands, resetKey = d.from to d.to, markers = d.zones.strategy,
            connectGapMs = ActivityMath.MAX_GAP_MS, clearLabel = stringResource(R.string.band_clear_phone),
        )
    }
}

@Composable
private fun Conditions(context: Context, s: ActivityMath.PowerSummary, ecoHours: Double) {
    if (s.hours <= 0) return
    // Shares of the time the app ran in the period (said under the title).
    Card(stringResource(R.string.act_conditions)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.act_cond_of, formatDuration(context, (s.hours * 3_600_000).toLong())), fontSize = 12.sp, color = Palette.textMuted)
            listOf(
                R.string.act_cond_screen_off to s.screenOff,
                R.string.act_cond_metered to s.metered,
                R.string.act_cond_saver to s.saver,
                R.string.act_cond_eco to (ecoHours / s.hours).coerceIn(0.0, 1.0),
                R.string.act_cond_charging to s.charging,
            ).forEach { (label, share) ->
                Column {
                    Row {
                        Text(stringResource(label), fontSize = 13.sp, color = Palette.textSecondary, modifier = Modifier.weight(1f))
                        Text("${(share * 100).roundToInt()}%", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Palette.text)
                    }
                    Spacer(Modifier.height(4.dp))
                    Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Palette.cardStrong)) {
                        Box(Modifier.fillMaxWidth(share.toFloat().coerceIn(0f, 1f)).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Palette.bar))
                    }
                }
            }
            Text(stringResource(R.string.act_cond_note), fontSize = 12.sp, color = Palette.textMuted)
        }
    }
}

@Composable
private fun Breakdown(context: Context, totals: Map<String, StatsDb.ActivityTotal>) {
    val rows = Activity.Kind.entries.mapNotNull { k -> totals[k.name]?.takeIf { it.n > 0 }?.let { k to it } }
    if (rows.isEmpty()) return
    Card(stringResource(R.string.act_breakdown)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            rows.forEach { (k, v) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Name, then the badge right after it; the count stays at the right edge.
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(k.label), fontSize = 14.sp, color = Palette.text, modifier = Modifier.weight(1f, fill = false))
                        if (k.wake) {
                            // Trimmed line box, centred: the font's own padding put the text a little low in the pill.
                            Box(
                                Modifier.padding(start = 8.dp, end = 8.dp).clip(RoundedCornerShape(6.dp)).background(Palette.accentSoft)
                                    .padding(horizontal = 6.dp, vertical = 3.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    stringResource(R.string.act_wake_badge), color = Palette.accentStrong, maxLines = 1,
                                    style = TextStyle(
                                        fontSize = 11.sp, lineHeight = 11.sp,
                                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                                        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
                                    ),
                                )
                            }
                        }
                    }
                    val bytes = if (v.bytes > 0) " · " + formatBytes(context, v.bytes) else ""
                    Text("${Units.count(v.n)}$bytes", fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = Display, color = Palette.text)
                }
            }
        }
    }
}

/**
 * Estimated energy of the app by part (see Energy): where the battery goes, to know what to optimise.
 * Also the total in mAh and as a share of the battery.
 */
@Composable
private fun EnergyCard(context: Context, d: ActivityData) {
    val shares = ActivityMath.energyShares(d.totals)
    if (shares.isEmpty()) return
    val totalMj = shares.sumOf { it.mj }.toDouble()
    // Voltage and capacity as this phone reports them; without a capacity only mAh is shown. The
    // range (over "one hour", i.e. as totals) comes from the device profile's spread.
    val total = ActivityMath.rate(totalMj, d.totals.values.sumOf { it.cpuMs }, 1.0, d.summary.volts, d.summary.capacityMah)
    val mah = total?.mahPerHour ?: 0.0
    val cap = total?.capacityMah
    val percents = ActivityMath.percentages(shares.map { it.share })
    Card(stringResource(R.string.act_energy)) {
        Text(
            if (cap != null) stringResource(
                R.string.act_energy_total, "%.1f".format(mah), "%.1f".format(total?.mahPlusMinus ?: 0.0),
                "%.2f".format(mah / cap * 100), "%.2f".format(total?.percentPlusMinus ?: 0.0),
            )
            else stringResource(R.string.act_energy_total_mah, "%.1f".format(mah), "%.1f".format(total?.mahPlusMinus ?: 0.0)),
            fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Palette.text,
        )
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            shares.forEachIndexed { i, sh ->
                val label = if (sh.kinds.size == 1) stringResource(sh.kinds.first().label) else stringResource(groupLabel(sh.kinds.first()))
                Column {
                    Row {
                        Column(Modifier.weight(1f)) {
                            Text(label, fontSize = 14.sp, color = Palette.text)
                            // What to optimise: radio time (do it less often / along with traffic) or CPU.
                            if (sh.radioShare > 0.005) {
                                val radio = (sh.radioShare * 100).roundToInt()
                                Text(stringResource(R.string.act_energy_split, radio, 100 - radio), fontSize = 11.sp, color = Palette.textMuted)
                            }
                        }
                        Text("${percents[i]}%", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Palette.text)
                    }
                    Spacer(Modifier.height(4.dp))
                    Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(Palette.cardStrong)) {
                        Box(Modifier.fillMaxWidth(sh.share.toFloat().coerceIn(0.01f, 1f)).height(5.dp).clip(RoundedCornerShape(3.dp)).background(Palette.bar))
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        val p = app.borderless.data.DeviceProfile.info
        Text(
            stringResource(
                R.string.act_energy_note, "%.2f".format(p.cpu.mid), "%.2f".format(p.cell.mid), "%.2f".format(p.wifi.mid),
                stringResource(if (p.source.startsWith("power_profile")) R.string.act_profile_phone else R.string.act_profile_generic),
            ),
            fontSize = 12.sp, color = Palette.textMuted,
        )
    }
}

private fun groupLabel(first: Activity.Kind): Int = when (first) {
    Activity.Kind.PING_STALL -> R.string.act_part_ping_checks
    Activity.Kind.SUB_UPDATE -> R.string.act_part_lookups
    Activity.Kind.CORE_START -> R.string.act_part_local
    Activity.Kind.DB_COMMIT -> R.string.act_part_stats
    else -> first.label
}

/**
 * What one action of each kind costs: the model's estimate (always) and, with current measurement
 * on, the measured average next to it. Numbers right-aligned in two columns.
 */
@Composable
private fun CostCard(context: Context, totals: Map<String, StatsDb.ActivityTotal>, measured: List<StatsDb.MeasuredKind>) {
    val real = measured.associateBy { it.kind }
    val rows = Activity.Kind.entries.filter { it != Activity.Kind.CORE_BASE && it != Activity.Kind.TRACKING }
        .mapNotNull { k -> totals[k.name]?.takeIf { it.n > 0 && it.mj > 0 }?.let { k to it } }
    if (rows.isEmpty()) return
    val col = 82.dp
    Card(stringResource(R.string.act_cost), header = {
        Text(stringResource(R.string.act_measured_model), fontSize = 11.sp, color = Palette.textMuted, textAlign = TextAlign.End, modifier = Modifier.width(col).alignByBaseline())
        Text(stringResource(R.string.act_measured_real), fontSize = 11.sp, color = Palette.textMuted, textAlign = TextAlign.End, modifier = Modifier.width(col).alignByBaseline())
    }) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            rows.forEach { (kind, t) ->
                val m = real[kind.name]
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(kind.label), fontSize = 13.sp, color = Palette.text)
                        Text(
                            stringResource(R.string.act_cost_n, Units.count(t.n)) + (m?.let { " · " + stringResource(R.string.act_measured_n, it.count) } ?: ""),
                            fontSize = 11.sp, color = Palette.textMuted,
                        )
                    }
                    Text(mjText(context, t.mj.toDouble() / t.n), fontSize = 13.sp, color = Palette.textSecondary, textAlign = TextAlign.End, modifier = Modifier.width(col))
                    Text(
                        // Below zero only by noise (the phone's own draw fell meanwhile): the action cost ~nothing.
                        m?.let { if (it.measuredMj <= 0) "≈ 0" else mjText(context, it.measuredMj) } ?: "—", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Palette.text,
                        textAlign = TextAlign.End, modifier = Modifier.width(col),
                    )
                }
            }
            Text(stringResource(R.string.act_cost_note), fontSize = 12.sp, color = Palette.textMuted)
        }
    }
}

/**
 * The core's own work per protocol class on this phone: CPU time per GB of traffic, relative to the
 * cheapest class (the battery saving mode prefers cheaper ones among working servers).
 */
@Composable
private fun ProtocolsCard(context: Context, measured: Map<String, ProtocolCost.Measured>) {
    val rows = measured.filter { it.value.mb >= 1 }.entries.sortedBy { it.value.cpuMsPerMb }
    if (rows.isEmpty()) return
    val cheapest = rows.filter { it.value.mb >= ProtocolCost.MIN_MB }.minOfOrNull { it.value.cpuMsPerMb }
    val col = 82.dp
    Card(stringResource(R.string.act_protocols), header = {
        Text(stringResource(R.string.act_proto_traffic), fontSize = 11.sp, color = Palette.textMuted, textAlign = TextAlign.End, modifier = Modifier.width(col).alignByBaseline())
        Text(stringResource(R.string.act_proto_cpu), fontSize = 11.sp, color = Palette.textMuted, textAlign = TextAlign.End, modifier = Modifier.width(col).alignByBaseline())
    }) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            rows.forEach { (key, m) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(key, fontSize = 13.sp, color = Palette.text)
                        val rel = cheapest?.takeIf { m.mb >= ProtocolCost.MIN_MB && it > 0 }?.let { m.cpuMsPerMb / it }
                        Text(
                            rel?.let { stringResource(R.string.act_proto_rel, "%.2f".format(it)) } ?: stringResource(R.string.act_proto_little),
                            fontSize = 11.sp, color = Palette.textMuted,
                        )
                    }
                    Text(formatBytes(context, m.bytes), fontSize = 13.sp, color = Palette.textSecondary, textAlign = TextAlign.End, modifier = Modifier.width(col))
                    Text(
                        Units.seconds(m.cpuMsPerMb * 1024 / 1000), fontSize = 13.sp,
                        fontWeight = FontWeight.Bold, color = Palette.text, textAlign = TextAlign.End, modifier = Modifier.width(col),
                    )
                }
            }
            Text(stringResource(R.string.act_proto_note), fontSize = 12.sp, color = Palette.textMuted)
        }
    }
}

@Suppress("UNUSED_PARAMETER")
private fun mjText(context: Context, mj: Double): String = Units.energy(mj)

/**
 * Phone states behind the activity charts, from the power samples: charging or screen off (full
 * height, in that priority), the phone's battery saver (a strip along the top) and mobile data (a
 * strip along the bottom). Each sample's state stands for the time until the next one; gaps longer
 * than [ActivityMath.MAX_GAP_MS] stay clear. On top of them the charts draw the connection states (off,
 * without a server, no network) and the battery saving mode's hatching, plus strategy changes.
 */
private fun stateBands(rows: List<StatsDb.PowerRow>): List<Band> {
    val out = ArrayList<Band>()
    fun add(start: Long, end: Long, kind: BandKind) {
        val i = out.indexOfLast { it.kind == kind }
        if (i >= 0 && out[i].end >= start) out[i] = out[i].copy(end = end) else out += Band(start, end, kind)
    }
    for ((a, b) in rows.zipWithNext()) {
        if (b.ts - a.ts > ActivityMath.MAX_GAP_MS) continue
        when {
            a.charging -> add(a.ts, b.ts, BandKind.CHARGING)
            !a.screen -> add(a.ts, b.ts, BandKind.SCREEN_OFF)
        }
        if (a.saver) add(a.ts, b.ts, BandKind.SAVER)
        if (a.metered) add(a.ts, b.ts, BandKind.METERED)
    }
    return out
}
