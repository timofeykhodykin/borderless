package app.borderless.ui.stats

import androidx.compose.foundation.border
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ZoomOutMap
import androidx.compose.material3.Icon
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.drawscope.clipRect
import kotlinx.coroutines.delay
import java.util.TimeZone
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.borderless.R
import app.borderless.Res
import app.borderless.core.Phase
import app.borderless.data.StatsMath
import app.borderless.ui.theme.Body
import app.borderless.ui.theme.Palette
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.math.abs

/**
 * Categorical colours for several servers on one chart (validated colour-blind-safe order).
 * A server keeps its colour while it stays selected; there are never more than eight series.
 */
val SeriesColors = listOf(
    Color(0xFF2A78D6), Color(0xFFEB6834), Color(0xFF1BAF7A), Color(0xFFEDA100),
    Color(0xFFE87BA4), Color(0xFF008300), Color(0xFF4A3AA7), Color(0xFFE34948),
)

/** [fillAlpha]: opacity of the area under the line when the chart fills (an envelope can be stronger). */
data class Series(val label: String, val color: Color, val points: List<StatsMath.Point>, val fillAlpha: Float = 0.12f)

/** Periods shaded behind a chart: when the tunnel was not carrying traffic through a server. */
enum class BandKind(val label: Int) {
    OFF(R.string.band_off),
    DIRECT(R.string.band_direct),
    NO_NETWORK(R.string.band_no_network),
    CHARGING(R.string.band_charging),
    SCREEN_OFF(R.string.band_screen_off),
    /** The phone's battery saver: a strip along the top (it often overlaps the app's own mode). */
    SAVER(R.string.band_saver),
    /** Drawn as a thin strip along the bottom: it overlaps the others (often with the screen off). */
    METERED(R.string.band_metered),
    /** The app's battery saving mode: diagonal hatching over whatever is behind. */
    ECO(R.string.band_eco);

    val strip: Boolean get() = this == METERED || this == SAVER
    val top: Boolean get() = this == SAVER
    val hatch: Boolean get() = this == ECO

    val fill: Color
        get() = when (this) {
            OFF -> Color(0xFFE7DFDB)
            DIRECT -> Palette.warning.copy(alpha = 0.45f)
            NO_NETWORK -> Color(0xFFDDE3EC)
            CHARGING -> Color(0xFFDCEFD8)
            SCREEN_OFF -> Color(0xFFE4E0EC)
            SAVER -> Color(0xFFF2B866)
            METERED -> Color(0xFF9FB4DE)
            ECO -> Palette.accentStrong.copy(alpha = 0.45f)
        }
}

data class Band(val start: Long, val end: Long, val kind: BandKind)

/**
 * A moment marked by a vertical line with a label (e.g. the strategy changed); [edge]: the state at the
 * period's start (label at the left edge); [line] false: the first known state, not a change (label only).
 */
data class Marker(val t: Long, val label: String, val edge: Boolean = false, val line: Boolean = true)

/** Diagonal hatching over a rectangle, lines [gap] apart. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.hatch(color: Color, x0: Float, y0: Float, x1: Float, y1: Float, gap: Float, width: Float) {
    clipRect(x0, y0, x1, y1) {
        val h = y1 - y0
        // Lines at 45°, aligned to absolute x so neighbouring bands continue the same pattern.
        var x = (kotlin.math.floor((x0 - h) / gap) * gap).toFloat()
        while (x < x1) {
            drawLine(color, Offset(x, y1), Offset(x + h, y0), width)
            x += gap
        }
    }
}

/**
 * Bands from connection state segments: gaps between segments are "off", direct/paused segments are
 * "without a server", no-network segments are "no network". Connected and searching stay clear.
 */
fun bands(segments: List<StatsMath.Segment>, from: Long, to: Long): List<Band> {
    val out = ArrayList<Band>()
    fun add(start: Long, end: Long, kind: BandKind) {
        if (end <= start) return
        val last = out.lastOrNull()
        if (last != null && last.kind == kind && start <= last.end) out[out.lastIndex] = last.copy(end = end)
        else out += Band(start, end, kind)
    }
    var cursor = from
    for (s in segments.sortedBy { it.start }) {
        add(cursor, s.start, BandKind.OFF)
        when (s.phase) {
            Phase.DIRECT, Phase.PAUSED -> add(s.start, s.end, BandKind.DIRECT)
            Phase.NO_NETWORK -> add(s.start, s.end, BandKind.NO_NETWORK)
            else -> {}
        }
        cursor = maxOf(cursor, s.end)
    }
    add(cursor, to, BandKind.OFF)
    return out
}

fun timeFormat(from: Long, to: Long): SimpleDateFormat =
    SimpleDateFormat(if (to - from <= 36 * 3_600_000L) "HH:mm" else "d MMM", Res.locale)

/** Higher-resolution series loaded for a zoomed-in window. */
private class Detail(val from: Long, val to: Long, val series: List<Series>)

/** What a gesture on the chart turned out to be. */
private enum class Gesture { UNDECIDED, CURSOR, SCROLL, PAN, ZOOM_TIME, ZOOM_VALUE }

/**
 * Time-series chart. Drag or tap to inspect a moment: a crosshair appears and the row above the
 * chart shows each series' value at that time (text in ink, colour only on the dots).
 *
 * Pinch to zoom: fingers side by side zoom time, one above the other zoom the value axis; moving
 * the fingers pans. When zoomed, a one-finger drag pans the zoomed axis (a vertical drag still
 * scrolls the page if only time is zoomed) and holding still shows the crosshair.
 * Zooming out stops at the full period / axis. Double tap or the button resets.
 * Labels follow the visible range. When zoomed in on time, [detail] (if given) loads finer data
 * for the visible window; it must return the same series in the same order.
 * The zoom survives data refreshes and resets when [resetKey] changes.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TimeChart(
    series: List<Series>,
    from: Long,
    to: Long,
    scale: YScale,
    format: (Double) -> String,
    height: Dp = 190.dp,
    fill: Boolean = false,
    failures: Boolean = false,
    stepped: Boolean = false,
    bands: List<Band> = emptyList(),
    resetKey: Any? = null,
    detail: (suspend (Long, Long) -> List<Series>)? = null,
    /**
     * Join points up to this far apart; null joins only neighbouring buckets. For sparse but real data:
     * server pings (also measured with the tunnel off), power samples.
     */
    connectGapMs: Long? = null,
    /** Legend entry for the unshaded background (the normal state), shown even without any band. */
    clearLabel: String? = null,
    /** Vertical lines with labels at moments (strategy changes). */
    markers: List<Marker> = emptyList(),
) {
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 10.sp, color = Palette.textSecondary, fontFamily = Body)
    val markerStyle = TextStyle(fontSize = 10.sp, color = Palette.textSecondary, fontFamily = Body)
    var cursor by remember(resetKey) { mutableStateOf<Long?>(null) }
    // Visible window; null = everything.
    var timeView by remember(resetKey) { mutableStateOf<Pair<Double, Double>?>(null) }
    var valueView by remember(resetKey) { mutableStateOf<Pair<Double, Double>?>(null) }
    val latest by rememberUpdatedState(Triple(from, to, scale))
    val loadDetail by rememberUpdatedState(detail)

    fun visibleTime(f: Long, t: Long): Pair<Double, Double> {
        val v = timeView ?: return f.toDouble() to t.toDouble()
        val span = minOf(v.second - v.first, (t - f).toDouble())
        val start = v.first.clampSafe(f.toDouble(), t - span)
        return start to start + span
    }
    fun visibleValue(s: YScale): Pair<Double, Double> {
        val v = valueView ?: return s.uMin to s.uMax
        val span = minOf(v.second - v.first, s.uMax - s.uMin)
        val start = v.first.clampSafe(s.uMin, s.uMax - span)
        return start to start + span
    }

    val (t0, t1) = visibleTime(from, to)
    val (u0, u1) = visibleValue(scale)
    val zoomed = timeView != null || valueView != null

    var detailed by remember(resetKey) { mutableStateOf<Detail?>(null) }
    LaunchedEffect(timeView, series) {
        val v = timeView
        val fn = loadDetail
        if (v == null || fn == null) { detailed = null; return@LaunchedEffect }
        delay(200)
        // Load a margin around the view so short pans stay sharp.
        val span = v.second - v.first
        val f = maxOf(from, (v.first - span / 2).toLong())
        val t = minOf(to, (v.second + span / 2).toLong())
        detailed = Detail(f, t, fn(f, t))
    }
    val shown = detailed?.takeIf { timeView != null && it.from <= t0 && it.to >= t1 && it.series.size == series.size }?.series ?: series

    val spanMs = (t1 - t0).toLong()
    val cursorFmt = remember(from, to, spanMs <= 15 * 60_000L) {
        val date = if (to - from > 36 * 3_600_000L) "d MMM, " else ""
        SimpleDateFormat(date + if (spanMs <= 15 * 60_000L) "HH:mm:ss" else "HH:mm", Res.locale)
    }

    Column {
        // Header: values under the cursor, or the legend when nothing is selected.
        Row(verticalAlignment = Alignment.Top) {
            FlowRow(
                Modifier.weight(1f).heightIn(min = 22.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val t = cursor
                if (t != null) Text(cursorFmt.format(Date(t)), fontSize = 12.sp, color = Palette.text, fontWeight = FontWeight.Bold)
                shown.forEach { s ->
                    val p = t?.let { c -> s.points.minByOrNull { abs(it.t - c) } }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(s.color))
                        Spacer(Modifier.width(5.dp))
                        val value = when {
                            t == null -> s.label
                            p == null -> "${s.label}: —"
                            p.ms == null -> "${s.label}: ✕"
                            else -> "${s.label}: ${format(p.ms.toDouble())}"
                        }
                        Text(value, fontSize = 12.sp, color = Palette.textSecondary, maxLines = 1)
                    }
                }
            }
            if (zoomed) {
                Row(
                    Modifier
                        .padding(start = 8.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Palette.accentSoft)
                        .clickable { timeView = null; valueView = null; cursor = null }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.ZoomOutMap, null, Modifier.size(14.dp), tint = Palette.accentStrong)
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.zoom_reset), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Palette.accentStrong)
                }
            }
        }
        if (markers.isEmpty()) Spacer(Modifier.height(6.dp))
        else Canvas(Modifier.fillMaxWidth().height(18.dp)) {
            // Marker labels above the plot, each starting at its line (with a short tick down to it).
            // Placed from the latest backwards: when labels would collide, the later one wins, so a burst
            // of changes shows the state it ended in.
            val left = ChartLeft.toPx()
            val plotW = size.width - left
            fun x(t: Long) = left + plotW * ((t - t0) / (t1 - t0).coerceAtLeast(1.0)).toFloat()
            var nextStart = Float.POSITIVE_INFINITY
            markers.sortedByDescending { if (it.edge) Long.MIN_VALUE else it.t }.forEach { m ->
                if (!m.edge && (m.t <= t0 || m.t >= t1)) return@forEach
                val xx = if (m.edge) left else x(m.t)
                if (!m.edge && m.line) drawLine(Palette.textMuted, Offset(xx, size.height - 4.dp.toPx()), Offset(xx, size.height), 1.dp.toPx())
                val text = measurer.measure(m.label, markerStyle)
                val lx = (xx - if (m.edge) 0f else 2.dp.toPx()).coerceAtMost(size.width - text.size.width)
                if (lx + text.size.width + 8.dp.toPx() > nextStart) return@forEach
                drawText(text, topLeft = Offset(lx, 0f))
                nextStart = lx
            }
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height)
                .pointerInput(resetKey, failures) {
                    val slop = viewConfiguration.touchSlop
                    var lastTap = 0L
                    var lastTapX = 0f
                    awaitEachGesture {
                        val first = awaitFirstDown(requireUnconsumed = false)
                        val left = ChartLeft.toPx()
                        val plotW = size.width - left
                        val plotH = size.height - 18.dp.toPx() - (if (failures) 8.dp.toPx() else 0f)
                        fun timeAtX(px: Float): Long {
                            val (f, t, _) = latest
                            val (a, b) = visibleTime(f, t)
                            return (a + (b - a) * ((px - left) / plotW).coerceIn(0f, 1f)).toLong()
                        }
                        var mode = Gesture.UNDECIDED
                        var tap = true
                        var prevA = Offset.Zero
                        var prevB = Offset.Zero
                        // When zoomed, a drag pans; holding the finger still brings up the crosshair instead.
                        val zoomedAtStart = timeView != null || valueView != null
                        val holdDeadline = first.uptimeMillis + viewConfiguration.longPressTimeoutMillis
                        var lastEventTime = first.uptimeMillis
                        while (true) {
                            val event = if (zoomedAtStart && mode == Gesture.UNDECIDED) {
                                withTimeoutOrNull((holdDeadline - lastEventTime).coerceAtLeast(1)) { awaitPointerEvent() }
                            } else awaitPointerEvent()
                            if (event == null) {
                                mode = Gesture.CURSOR
                                tap = false
                                cursor = timeAtX(first.position.x)
                                continue
                            }
                            lastEventTime = event.changes.maxOf { it.uptimeMillis }
                            val down = event.changes.filter { it.pressed }.sortedBy { it.id.value }
                            if (down.isEmpty()) break
                            if (down.size >= 2) {
                                tap = false
                                val a = down[0].position
                                val b = down[1].position
                                if (mode != Gesture.ZOOM_TIME && mode != Gesture.ZOOM_VALUE) {
                                    // The axis is chosen once, by how the fingers are placed.
                                    mode = if (abs(a.x - b.x) >= abs(a.y - b.y)) Gesture.ZOOM_TIME else Gesture.ZOOM_VALUE
                                    cursor = null
                                } else {
                                    val (f, t, s) = latest
                                    val minGap = 24.dp.toPx()
                                    if (mode == Gesture.ZOOM_TIME) {
                                        val (v0, v1) = visibleTime(f, t)
                                        val span = v1 - v0
                                        val full = (t - f).toDouble()
                                        val newSpan = (span * abs(prevA.x - prevB.x).coerceAtLeast(minGap) / abs(a.x - b.x).coerceAtLeast(minGap))
                                            .clampSafe(minOf(full, MIN_TIME_SPAN), full)
                                        // Keep the moment under the fingers' midpoint under it: zooms and pans at once.
                                        val anchor = v0 + ((prevA.x + prevB.x) / 2 - left) / plotW * span
                                        val start = (anchor - ((a.x + b.x) / 2 - left) / plotW * newSpan).clampSafe(f.toDouble(), t - newSpan)
                                        timeView = if (newSpan >= full * 0.999) null else start to start + newSpan
                                    } else {
                                        val (w0, w1) = visibleValue(s)
                                        val span = w1 - w0
                                        val full = s.uMax - s.uMin
                                        val newSpan = (span * abs(prevA.y - prevB.y).coerceAtLeast(minGap) / abs(a.y - b.y).coerceAtLeast(minGap))
                                            .clampSafe(minOf(full, s.minSpan), full)
                                        val anchor = w0 + (1 - (prevA.y + prevB.y) / 2 / plotH) * span
                                        val start = (anchor - (1 - (a.y + b.y) / 2 / plotH) * newSpan).clampSafe(s.uMin, s.uMax - newSpan)
                                        valueView = if (newSpan >= full * 0.999) null else start to start + newSpan
                                    }
                                }
                                prevA = a
                                prevB = b
                                event.changes.forEach { it.consume() }
                            } else if (mode == Gesture.ZOOM_TIME || mode == Gesture.ZOOM_VALUE) {
                                // One finger lifted after a pinch: don't let the page scroll.
                                event.changes.forEach { it.consume() }
                            } else {
                                val c = down[0]
                                val d = c.position - first.position
                                if (mode == Gesture.UNDECIDED) {
                                    // Sideways: pan a zoomed time axis, else move the crosshair.
                                    // Up/down: pan a zoomed ping axis, else let the page scroll.
                                    if (abs(d.x) > slop && abs(d.x) >= abs(d.y)) mode = if (timeView != null) Gesture.PAN else Gesture.CURSOR
                                    else if (abs(d.y) > slop) mode = if (valueView != null) Gesture.PAN else Gesture.SCROLL
                                }
                                if (mode != Gesture.UNDECIDED) tap = false
                                if (mode == Gesture.CURSOR) { cursor = timeAtX(c.position.x); c.consume() }
                                if (mode == Gesture.PAN) {
                                    val move = c.position - c.previousPosition
                                    val (f, t, s) = latest
                                    timeView?.let {
                                        val (v0, v1) = visibleTime(f, t)
                                        val span = v1 - v0
                                        val start = (v0 - move.x / plotW * span).clampSafe(f.toDouble(), t - span)
                                        timeView = start to start + span
                                    }
                                    valueView?.let {
                                        val (w0, w1) = visibleValue(s)
                                        val span = w1 - w0
                                        val start = (w0 + move.y / plotH * span).clampSafe(s.uMin, s.uMax - span)
                                        valueView = start to start + span
                                    }
                                    c.consume()
                                }
                            }
                        }
                        if (tap) {
                            val now = System.currentTimeMillis()
                            val double = now - lastTap < viewConfiguration.doubleTapTimeoutMillis && abs(first.position.x - lastTapX) < 48.dp.toPx()
                            if (double && (timeView != null || valueView != null)) {
                                timeView = null; valueView = null; cursor = null; lastTap = 0L
                            } else {
                                cursor = timeAtX(first.position.x); lastTap = now; lastTapX = first.position.x
                            }
                        }
                    }
                },
        ) {
            val left = ChartLeft.toPx()
            val bottom = 18.dp.toPx()
            val plotW = size.width - left
            val plotH = size.height - bottom - (if (failures) 8.dp.toPx() else 0f)
            fun x(t: Long) = left + plotW * ((t - t0) / (t1 - t0).coerceAtLeast(1.0)).toFloat()
            fun y(v: Double) = plotH * (1 - ((scale.unit(v) - u0) / (u1 - u0)).toFloat())
            val ft0 = t0.toLong()
            val ft1 = t1.toLong()

            // Recessive grid with y labels.
            scale.ticks(u0, u1).forEach { v ->
                val yy = y(v)
                drawLine(Palette.outline, Offset(left, yy), Offset(size.width, yy), 1.dp.toPx())
                val m = measurer.measure(scale.label(v), labelStyle)
                drawText(m, topLeft = Offset(left - m.size.width - 6.dp.toPx(), (yy - m.size.height / 2f).clampSafe(0f, plotH - m.size.height / 2f)))
            }
            // Time labels at round local times; midnight shows the date.
            val (step, ticks) = timeTicks(ft0, ft1)
            val zone = TimeZone.getDefault()
            ticks.forEach { t ->
                val midnight = Math.floorMod(t + zone.getOffset(t), 86_400_000L) == 0L
                val m = measurer.measure((if (step >= 86_400_000L || midnight) dayFmt else hourFmt).format(Date(t)), labelStyle)
                val xx = (x(t) - m.size.width / 2f).clampSafe(left, size.width - m.size.width)
                drawText(m, topLeft = Offset(xx, size.height - m.size.height))
            }

            // Plot contents are clipped to the plot when zoomed (lines run past the window).
            val pad = if (valueView == null) 5.dp.toPx() else 0f
            clipRect(left, -pad, size.width, plotH + pad) {
                // State bands behind everything, edged with dashed lines: fills first, then hatching,
                // then the strips along the edges.
                val dash = PathEffect.dashPathEffect(floatArrayOf(5f, 5f))
                val layered = bands.filter { !it.kind.hatch && !it.kind.strip } + bands.filter { it.kind.hatch } + bands.filter { it.kind.strip }
                layered.forEach { b ->
                    if (b.end <= ft0 || b.start >= ft1) return@forEach
                    val x0 = x(maxOf(b.start, ft0))
                    val x1 = x(minOf(b.end, ft1))
                    if (x1 - x0 < 0.5f) return@forEach
                    if (b.kind.strip) {
                        val h = 5.dp.toPx()
                        drawRect(b.kind.fill, Offset(x0, if (b.kind.top) 0f else plotH - h), Size(x1 - x0, h))
                        return@forEach
                    }
                    if (b.kind.hatch) hatch(b.kind.fill, x0, 0f, x1, plotH, 7.dp.toPx(), 1.2.dp.toPx())
                    else drawRect(b.kind.fill, Offset(x0, 0f), Size(x1 - x0, plotH))
                    if (b.start > ft0) drawLine(Palette.textMuted, Offset(x0, 0f), Offset(x0, plotH), 1.dp.toPx(), pathEffect = dash)
                    if (b.end < ft1) drawLine(Palette.textMuted, Offset(x1, 0f), Offset(x1, plotH), 1.dp.toPx(), pathEffect = dash)
                }
                // Markers: a thin line through the plot; their labels sit in the strip above it.
                markers.forEach { m ->
                    if (!m.edge && m.line && m.t > ft0 && m.t < ft1) drawLine(Palette.textMuted, Offset(x(m.t), 0f), Offset(x(m.t), plotH), 1.dp.toPx())
                }
                val bucketWidth = shown.firstOrNull()?.points?.zipWithNext { a, b -> b.t - a.t }?.minOrNull() ?: 0L
                val visiblePoints = shown.firstOrNull()?.points?.count { it.t in ft0..ft1 } ?: 0
                shown.forEach { s -> drawSeries(s, ::x, ::y, plotH, bucketWidth, fill, stepped, dots = visiblePoints <= 24, connectGapMs) }
            }

            if (failures) {
                // Failed probes as ticks below the plot; taller = larger share of failures in the bucket.
                val base = size.height - bottom
                shown.firstOrNull()?.points?.forEach { p ->
                    if (p.failed > 0 && p.t in ft0..ft1) {
                        val h = 3.dp.toPx() + 5.dp.toPx() * p.failed / p.total.coerceAtLeast(1)
                        drawLine(Palette.danger, Offset(x(p.t), base), Offset(x(p.t), base - h), 2.dp.toPx(), StrokeCap.Round)
                    }
                }
            }

            cursor?.takeIf { it in ft0..ft1 }?.let { t ->
                val xx = x(t)
                drawLine(Palette.textMuted, Offset(xx, 0f), Offset(xx, plotH), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
                shown.forEach { s ->
                    val p = s.points.minByOrNull { abs(it.t - t) } ?: return@forEach
                    val ms = p.ms ?: return@forEach
                    val yy = y(ms.toDouble())
                    if (yy < 0f || yy > plotH) return@forEach
                    drawCircle(Palette.background, 6.dp.toPx(), Offset(x(p.t), yy))
                    drawCircle(s.color, 4.dp.toPx(), Offset(x(p.t), yy))
                }
            }
        }
        // Legend for the bands that actually appear.
        val kinds = bands.filter { minOf(it.end, to) - maxOf(it.start, from) > (to - from) / 400 }.map { it.kind }.distinct()
        if (kinds.isNotEmpty() || clearLabel != null) {
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (clearLabel != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp, 9.dp).clip(RoundedCornerShape(2.dp)).background(Palette.background).border(1.dp, Palette.outline, RoundedCornerShape(2.dp)))
                    Spacer(Modifier.width(5.dp))
                    Text(clearLabel, fontSize = 11.5.sp, color = Palette.textSecondary)
                }
                kinds.forEach { k ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        when {
                            k.hatch -> Canvas(Modifier.size(12.dp, 9.dp).clip(RoundedCornerShape(2.dp)).border(1.dp, Palette.outline, RoundedCornerShape(2.dp))) {
                                hatch(k.fill, 0f, 0f, size.width, size.height, 3.5.dp.toPx(), 1.2.dp.toPx())
                            }
                            // Strips: a thin bar at their edge of the swatch.
                            k.strip -> Box(Modifier.size(12.dp, 9.dp), contentAlignment = if (k.top) Alignment.TopCenter else Alignment.BottomCenter) {
                                Box(Modifier.size(12.dp, 3.dp).clip(RoundedCornerShape(1.dp)).background(k.fill))
                            }
                            else -> Box(Modifier.size(12.dp, 9.dp).clip(RoundedCornerShape(2.dp)).background(k.fill))
                        }
                        Spacer(Modifier.width(5.dp))
                        Text(stringResource(k.label), fontSize = 11.5.sp, color = Palette.textSecondary)
                    }
                }
            }
        }
    }
}

/** Narrowest time window a pinch can reach. */
private const val MIN_TIME_SPAN = 5 * 60_000.0

private val hourFmt get() = SimpleDateFormat("HH:mm", Res.locale)
private val dayFmt get() = SimpleDateFormat("d MMM", Res.locale)

/** Width reserved for y-axis labels. */
private val ChartLeft = 30.dp

private fun DrawScope.drawSeries(
    s: Series,
    x: (Long) -> Float,
    y: (Double) -> Float,
    plotH: Float,
    bucketWidth: Long,
    fill: Boolean,
    stepped: Boolean,
    dots: Boolean,
    connectGapMs: Long?,
) {
    // Break the line across failed buckets and across gaps: longer than connectGapMs, or (without it)
    // much longer than one bucket.
    val segments = mutableListOf<MutableList<StatsMath.Point>>()
    var prev: StatsMath.Point? = null
    s.points.forEach { p ->
        val q = prev
        val gap = q != null && if (connectGapMs != null) p.t - q.t > connectGapMs else bucketWidth > 0 && p.t - q.t > bucketWidth * 3
        if (p.ms == null || gap || segments.isEmpty()) segments.add(mutableListOf())
        if (p.ms != null) segments.last().add(p)
        prev = p
    }
    segments.filter { it.isNotEmpty() }.forEach { seg ->
        val path = Path()
        seg.forEachIndexed { i, p ->
            val px = x(p.t)
            val py = y(p.ms!!.toDouble())
            if (i == 0) path.moveTo(px, py)
            else {
                if (stepped) path.lineTo(px, y(seg[i - 1].ms!!.toDouble()))
                path.lineTo(px, py)
            }
        }
        if (fill && seg.size > 1) {
            val area = Path().apply {
                addPath(path)
                lineTo(x(seg.last().t), plotH)
                lineTo(x(seg.first().t), plotH)
                close()
            }
            drawPath(area, s.color.copy(alpha = s.fillAlpha))
        }
        drawPath(path, s.color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        if (seg.size == 1 || dots) seg.forEach { p ->
            drawCircle(Palette.background, 4.dp.toPx(), Offset(x(p.t), y(p.ms!!.toDouble())))
            drawCircle(s.color, 3.dp.toPx(), Offset(x(p.t), y(p.ms.toDouble())))
        }
    }
}

fun phaseColor(p: Phase): Color = when (p) {
    Phase.CONNECTED -> Palette.accent
    Phase.SEARCHING -> Palette.accentContainer
    Phase.DIRECT -> Palette.warning
    Phase.PAUSED -> Palette.danger
    Phase.NO_NETWORK -> Palette.textMuted
    Phase.OFF -> Palette.card
}

/** One horizontal bar: what the tunnel was doing over the period. Gaps (turned off) stay empty. */
@Composable
fun StateTimeline(segments: List<StatsMath.Segment>, from: Long, to: Long) {
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 10.sp, color = Palette.textSecondary, fontFamily = Body)
    val fmt = remember(from, to) { timeFormat(from, to) }
    Canvas(Modifier.fillMaxWidth().height(40.dp)) {
        val barH = 18.dp.toPx()
        val r = CornerRadius(6.dp.toPx())
        drawRoundRect(Palette.card, size = Size(size.width, barH), cornerRadius = r)
        fun x(t: Long) = size.width * ((t - from).toFloat() / (to - from).coerceAtLeast(1))
        segments.forEach { s ->
            val x0 = x(s.start)
            val w = (x(s.end) - x0).coerceAtLeast(1.5f)
            drawRect(phaseColor(s.phase), topLeft = Offset(x0, 0f), size = Size(w, barH))
        }
        for (i in 0..4) {
            val t = from + (to - from) * i / 4
            val m = measurer.measure(fmt.format(Date(t)), labelStyle)
            drawText(m, topLeft = Offset((x(t) - m.size.width / 2f).clampSafe(0f, size.width - m.size.width), size.height - m.size.height))
        }
    }
}

/** Horizontal bars of how often each latency range occurred. */
@Composable
fun Histogram(counts: List<Int>, labels: List<String>, colors: List<Color>) {
    val total = counts.sum().coerceAtLeast(1)
    val max = (counts.maxOrNull() ?: 0).coerceAtLeast(1)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        counts.forEachIndexed { i, c ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(labels[i], fontSize = 12.sp, color = Palette.textSecondary, modifier = Modifier.width(78.dp))
                Box(Modifier.weight(1f).height(14.dp)) {
                    Box(
                        Modifier
                            .fillMaxWidth(c.toFloat() / max)
                            .height(14.dp)
                            .clip(RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp))
                            .background(if (c == 0) Color.Transparent else colors[i]),
                    )
                }
                Text(
                    "${c * 100 / total}%", fontSize = 12.sp, color = Palette.text, fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(44.dp).padding(start = 8.dp),
                )
            }
        }
    }
}

/**
 * Like coerceIn, but never throws: when the range is empty (floating-point rounding can make the
 * upper bound a hair below the lower one at full zoom-out, or a label is wider than the room for it),
 * the lower bound wins.
 */
internal fun Double.clampSafe(lo: Double, hi: Double): Double = if (hi < lo) lo else coerceIn(lo, hi)

internal fun Float.clampSafe(lo: Float, hi: Float): Float = if (hi < lo) lo else coerceIn(lo, hi)
