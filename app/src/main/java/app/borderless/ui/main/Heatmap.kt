package app.borderless.ui.main

import androidx.compose.foundation.ExperimentalFoundationApi
import app.borderless.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.borderless.data.PingRecord
import app.borderless.data.Server
import app.borderless.ui.theme.Palette

private val CELL = 22.dp
private val GAP = 5.dp
private val GROUP_GAP = 13.dp

/**
 * One cell per server, group by group: each group starts on a new row, servers in their order
 * within the group. Positions are stable, so the picture is recognisable between scans.
 * Colour = latency, cross = unavailable.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Heatmap(
    groups: List<List<Server>>,
    pings: Map<String, PingRecord>,
    pending: Set<String>,
    probing: Set<String>,
    spin: () -> Float,
    currentId: String?,
    peekId: String?,
    onCell: (Server) -> Unit,
    onCellLongClick: (Server) -> Unit,
    /** Details of the tapped server, shown under the grid. */
    panel: @Composable () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 18.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(Palette.card)
            .padding(14.dp),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columns = ((maxWidth + GAP) / (CELL + GAP)).toInt().coerceAtLeast(1)
            // Stretch cells so the grid fills the card edge to edge.
            val cell = (maxWidth - GAP * (columns - 1)) / columns
            val grid = remember(groups, columns, cell) { layout(groups, columns, cell) }
            AnimatedCells(grid) { server, modifier ->
                Cell(
                    server, pings[server.id], server.id in pending, server.id in probing, server.id == currentId, server.id == peekId,
                    cell, spin, onCell, onCellLongClick, modifier,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        panel()
        Spacer(Modifier.height(10.dp))
        Legend()
    }
}

/** Where a server's cell sits in the grid. */
private class Slot(val server: Server, val x: Dp, val y: Dp)

private class Grid(val slots: Map<String, Slot>, val height: Dp)

/** Groups one after another, each starting on a new row, servers in order within the group. */
private fun layout(groups: List<List<Server>>, columns: Int, cell: Dp): Grid {
    val slots = LinkedHashMap<String, Slot>()
    var y = 0.dp
    groups.filter { it.isNotEmpty() }.forEachIndexed { g, servers ->
        if (g > 0) y += GROUP_GAP
        servers.forEachIndexed { i, s -> slots[s.id] = Slot(s, (cell + GAP) * (i % columns), y + (cell + GAP) * (i / columns)) }
        val rows = (servers.size + columns - 1) / columns
        y += cell * rows + GAP * (rows - 1)
    }
    return Grid(slots, y)
}

private const val MOVE_MS = 350
private const val EXIT_MS = 250

private class Holder<T>(var value: T)

/**
 * Cells placed at their [Grid] positions. When servers appear or disappear (auto-visibility, groups,
 * hiding) cells slide to their new places, new ones grow in and removed ones shrink away.
 */
@Composable
private fun AnimatedCells(grid: Grid, cell: @Composable (Server, Modifier) -> Unit) {
    val scope = rememberCoroutineScope()
    // Cells that just left, kept (at their last place) until the exit animation is over.
    val leaving = remember { mutableStateMapOf<String, Slot>() }
    val last = remember { Holder<Map<String, Slot>>(emptyMap()) }
    val gone = last.value.filterKeys { it !in grid.slots }
    SideEffect {
        last.value = grid.slots
        grid.slots.keys.forEach { leaving.remove(it) }
        gone.forEach { (id, slot) ->
            if (id !in leaving) {
                leaving[id] = slot
                scope.launch { delay(EXIT_MS + 50L); if (id !in last.value) leaving.remove(id) }
            }
        }
    }
    // The first frame shows the grid as it is; only later changes animate in.
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { settled = true }
    val height by animateDpAsState(grid.height, tween(MOVE_MS), label = "grid")
    val exiting = (leaving + gone).filterKeys { it !in grid.slots }
    Box(Modifier.fillMaxWidth().height(height)) {
        (grid.slots.values + exiting.values).forEach { slot ->
            key(slot.server.id) {
                val out = slot.server.id !in grid.slots
                val x by animateDpAsState(slot.x, tween(MOVE_MS), label = "x")
                val y by animateDpAsState(slot.y, tween(MOVE_MS), label = "y")
                val shown = remember { Animatable(if (settled) 0f else 1f) }
                LaunchedEffect(out) { shown.animateTo(if (out) 0f else 1f, tween(if (out) EXIT_MS else MOVE_MS)) }
                cell(
                    slot.server,
                    Modifier
                        .offset { IntOffset(x.roundToPx(), y.roundToPx()) }
                        .graphicsLayer {
                            val v = shown.value
                            scaleX = 0.3f + 0.7f * v
                            scaleY = 0.3f + 0.7f * v
                            alpha = v
                        },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Cell(
    server: Server,
    p: PingRecord?,
    waiting: Boolean,
    probing: Boolean,
    current: Boolean,
    peeked: Boolean,
    size: androidx.compose.ui.unit.Dp,
    spin: () -> Float,
    onClick: (Server) -> Unit,
    onLongClick: (Server) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(7.dp)
    Box(
        modifier
            .size(size)
            .clip(shape)
            .background(
                when {
                    p == null -> Palette.untested
                    // While waiting, keep a faint hint of the previous result.
                    waiting && p.ok -> Palette.ping(p.ms).copy(alpha = 0.35f)
                    waiting -> Palette.untested
                    p.ok -> Palette.ping(p.ms)
                    else -> Palette.unavailable
                }
            )
            // Only probes in flight spin; queued cells just stay dimmed, so the scan's progress is visible.
            .pendingSpinner(probing, spin)
            .then(
                when {
                    current -> Modifier.border(2.dp, Palette.currentOutline, shape)
                    peeked -> Modifier.border(2.dp, Palette.textSecondary, shape)
                    else -> Modifier
                }
            )
            .combinedClickable(onClick = { onClick(server) }, onLongClick = { onLongClick(server) }),
        contentAlignment = Alignment.Center,
    ) {
        if (p != null && !p.ok && !waiting) CrossMark(Modifier.size(size), Palette.unavailableMark)
    }
}

@Composable
private fun Legend() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.ms, Palette.PING_FAST), fontSize = 11.sp, color = Palette.textSecondary)
        Spacer(Modifier.width(6.dp))
        Box(
            Modifier
                .width(72.dp)
                .height(6.dp)
                .clip(RoundedCornerShape(50))
                .background(Brush.horizontalGradient(Palette.pingLegend)),
        )
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.legend_slow, Palette.PING_SLOW / 1000), fontSize = 11.sp, color = Palette.textSecondary)
        Spacer(Modifier.weight(1f))
        Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(Palette.unavailable), contentAlignment = Alignment.Center) {
            CrossMark(Modifier.size(12.dp), Palette.unavailableMark)
        }
        Spacer(Modifier.width(5.dp))
        Text(stringResource(R.string.legend_down), fontSize = 11.sp, color = Palette.textSecondary)
    }
}

@Composable
fun CrossMark(modifier: Modifier, color: Color) {
    Box(
        modifier.drawBehind {
            val inset = size.minDimension * 0.3f
            val w = (size.minDimension * 0.09f).coerceAtLeast(1.5f)
            drawLine(color, Offset(inset, inset), Offset(size.width - inset, size.height - inset), w, StrokeCap.Round)
            drawLine(color, Offset(size.width - inset, inset), Offset(inset, size.height - inset), w, StrokeCap.Round)
        },
    )
}
