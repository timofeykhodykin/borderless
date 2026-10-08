package app.borderless.ui.settings

import androidx.compose.material.icons.rounded.Insights
import app.borderless.ui.AppMenu
import app.borderless.ui.AppMenuItem
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Folder
import android.content.Context
import android.text.format.DateUtils
import java.text.SimpleDateFormat
import java.util.Date
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.platform.LocalContext
import app.borderless.data.AppSettings
import app.borderless.data.Importer
import app.borderless.data.SubMath
import app.borderless.data.launchSafe
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import app.borderless.ui.share.ShareSheet
import app.borderless.data.Subscription
import app.borderless.data.ShareFormat
import androidx.compose.material.icons.rounded.RssFeed
import androidx.compose.material.icons.rounded.Share
import app.borderless.ui.FlagIcon
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoMode
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.borderless.R
import app.borderless.core.Engine
import app.borderless.core.AutoPool
import app.borderless.core.Scanner
import app.borderless.data.Countries
import app.borderless.data.Group
import app.borderless.data.PingRecord
import app.borderless.data.Repo
import app.borderless.data.Server
import app.borderless.ui.main.ServerRenameDialog
import app.borderless.service.TunnelService
import app.borderless.ui.theme.Display
import app.borderless.ui.theme.Palette
import sh.calvin.reorderable.ReorderableCollectionItemScope
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * Rows of the flattened groups list: a header per group; if expanded, the servers added by hand, then
 * each subscription with its servers below it (indented, folding under the subscription's arrow).
 */
private sealed interface GroupRow {
    val key: String

    data class Header(val group: Group, val members: List<Server>) : GroupRow {
        override val key get() = "g:" + group.id
    }

    /** [nested]: a subscription's server, shown under it. */
    data class Member(val group: Group, val server: Server, val last: Boolean, val nested: Boolean = false) : GroupRow {
        override val key get() = "s:" + server.id
    }

    data class SubRow(val group: Group, val sub: Subscription, val last: Boolean, val open: Boolean, val count: Int) : GroupRow {
        override val key get() = "u:" + sub.id
    }

    data class Empty(val group: Group) : GroupRow {
        override val key get() = "e:" + group.id
    }
}

@Composable
fun GroupsScreen(onBack: () -> Unit, onSubStats: (String) -> Unit = {}, onServerStats: (String) -> Unit = {}) {
    val groups by Repo.groups.collectAsStateWithLifecycle()
    val servers by Repo.servers.collectAsStateWithLifecycle()
    val pings by Repo.pings.collectAsStateWithLifecycle()
    // Re-read names when subscriptions change (their titles are the default group names).
    val subs by Repo.subscriptions.collectAsStateWithLifecycle()
    val settings by Repo.settings.collectAsStateWithLifecycle()
    val plan by Scanner.plan.collectAsStateWithLifecycle()
    val autoState by Repo.auto.collectAsStateWithLifecycle()
    // Auto-visibility: the pool decides what is active; the user's own flags wait until it is off.
    val autoActive = settings.autoActive
    val isActive: (Server) -> Boolean = { if (autoActive) it.id in autoState.pool else !it.disabled }
    // Auto-sort only changes what is shown: the user's order stays saved and comes back when it is off.
    val auto = settings.autoSort
    val rank = remember(plan) { plan.withIndex().associate { (i, id) -> id to i + 1 } }
    var expanded by rememberSaveable { mutableStateOf<Set<String>>(emptySet()) }
    // Subscriptions are open by default; this holds the folded ones.
    var folded by rememberSaveable { mutableStateOf<Set<String>>(emptySet()) }
    // Subscriptions being downloaded now.
    var busy by remember { mutableStateOf<Set<String>>(emptySet()) }
    val scope = rememberCoroutineScope()
    fun refresh(list: List<Subscription>) {
        busy = busy + list.map { it.id }
        scope.launchSafe("updating subscriptions", finally = { busy = busy - list.map { it.id }.toSet() }) {
            list.forEach { Importer.update(it); busy = busy - it.id }
            Importer.resolveCountries()
            Scanner.start()
        }
    }
    var renaming by remember { mutableStateOf<Group?>(null) }
    var renamingServer by remember { mutableStateOf<Server?>(null) }
    var renamingSub by remember { mutableStateOf<Subscription?>(null) }
    var deletingServer by remember { mutableStateOf<Server?>(null) }
    var deletingSub by remember { mutableStateOf<Subscription?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Group?>(null) }
    var sharing by remember { mutableStateOf<Share?>(null) }
    val haptic = LocalHapticFeedback.current

    val rows = remember(groups, servers, subs, expanded, folded, auto, plan, autoActive, autoState.pool) {
        Repo.serversByGroup(servers, groups, plan.takeIf { auto }, subs).flatMap { (g, members) ->
            buildList {
                add(GroupRow.Header(g, members))
                if (g.id in expanded) {
                    val own = members.filter { it.subscriptionId == null }
                    val groupSubs = subs.filter { it.groupId == g.id }
                    if (own.isEmpty() && groupSubs.isEmpty()) add(GroupRow.Empty(g))
                    own.forEachIndexed { i, s -> add(GroupRow.Member(g, s, groupSubs.isEmpty() && i == own.lastIndex)) }
                    groupSubs.forEachIndexed { si, sub ->
                        val its = members.filter { it.subscriptionId == sub.id }
                        val open = sub.id !in folded
                        val lastSub = si == groupSubs.lastIndex
                        add(GroupRow.SubRow(g, sub, lastSub && (!open || its.isEmpty()), open, its.size))
                        if (open) its.forEachIndexed { i, s -> add(GroupRow.Member(g, s, lastSub && i == its.lastIndex, nested = true)) }
                    }
                }
            }
        }
    }
    val userGroups = remember(groups, subs) { groups.map { it to Repo.groupName(it) } }

    val listState = rememberLazyListState()
    val reorder = rememberReorderableLazyListState(listState) { from, to ->
        val a = from.key as String
        val b = to.key as String
        when {
            // Groups only swap with other group headers (groups are collapsed while dragging).
            a.startsWith("g:") && b.startsWith("g:") -> Repo.moveGroup(a.drop(2), b.drop(2))
            // Servers only move within their own group, and not while auto-sorted.
            a.startsWith("s:") && b.startsWith("s:") && !auto -> Repo.moveServer(a.drop(2), b.drop(2))
            // Subscriptions swap with other subscriptions of the same group (all blocks are folded while dragging).
            a.startsWith("u:") && b.startsWith("u:") -> Repo.moveSubscriptionOrder(a.drop(2), b.drop(2))
        }
        haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
    }

    ScreenScaffold(stringResource(R.string.groups), onBack, actions = {
        val allTitle = stringResource(R.string.share_all)
        ToggleAction(Icons.Rounded.AutoMode, stringResource(R.string.auto_active), autoActive) {
            val on = !settings.autoActive
            Repo.updateSettings { it.copy(autoActive = on) }
            if (on) AutoPool.refresh()
        }
        ToggleAction(Icons.AutoMirrored.Rounded.Sort, stringResource(R.string.auto_sort), auto) {
            Repo.updateSettings { it.copy(autoSort = !it.autoSort) }
        }
        IconButton({ sharing = Share(allTitle, Repo.shareAll(), true) }) { Icon(Icons.Rounded.Share, stringResource(R.string.share), tint = Palette.textSecondary) }
        IconButton({ creating = true }) { Icon(Icons.Rounded.Add, stringResource(R.string.group_new), tint = Palette.textSecondary) }
    }) {
        LazyColumn(state = listState, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp)) {
            if (autoActive) item(key = "autoActive") {
                Hint(stringResource(R.string.auto_active_hint, servers.count { it.id in autoState.pool }, servers.size))
            }
            if (auto) item(key = "auto") { Hint(stringResource(R.string.auto_sort_hint)) }
            items(rows, key = { it.key }) { row ->
                ReorderableItem(reorder, key = row.key) { dragging ->
                    when (row) {
                        is GroupRow.Header -> GroupHeader(
                            row, open = row.group.id in expanded, dragging = dragging, off = row.members.count { !isActive(it) },
                            onToggle = { expanded = if (row.group.id in expanded) expanded - row.group.id else expanded + row.group.id },
                            onDragStart = { expanded = emptySet(); haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate) },
                            onRename = { renaming = row.group },
                            onDelete = { deleting = row.group },
                            onShare = { sharing = Share(Repo.groupName(row.group), Repo.shareGroup(row.group.id), true) },
                        )
                        is GroupRow.SubRow -> SubscriptionRow(
                            row, settings, busy = row.sub.id in busy, sorted = auto,
                            groups = userGroups.filter { it.first.id != row.group.id },
                            onToggle = { folded = if (row.sub.id in folded) folded - row.sub.id else folded + row.sub.id },
                            // Dragging a subscription folds every block, so the rows to swap with are next to each other.
                            onDragStart = {
                                folded = subs.map { it.id }.toSet()
                                haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                            },
                            onUpdate = { refresh(listOf(row.sub)) },
                            onShare = { sharing = Share(Repo.subName(row.sub), Repo.shareSubscription(row.sub.id), true) },
                            onMove = { Repo.moveSubscription(row.sub.id, it) },
                            onStats = { onSubStats(row.sub.id) },
                            onRename = { renamingSub = row.sub },
                            onDelete = { deletingSub = row.sub },
                        )
                        is GroupRow.Member -> MemberRow(
                            row, pings[row.server.id], dragging, sorted = auto, rank = rank[row.server.id],
                            active = isActive(row.server), autoActive = autoActive,
                            groups = userGroups.filter { it.first.id != row.group.id },
                            onDragStart = { haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate) },
                            onShare = { sharing = Share(Repo.realName(row.server), Repo.shareServer(row.server.id), false) },
                            onRename = { renamingServer = row.server },
                            onStats = { onServerStats(row.server.id) },
                            onDelete = { deletingServer = row.server },
                        )
                        is GroupRow.Empty -> Text(
                            stringResource(R.string.group_empty), fontSize = 13.sp, color = Palette.textSecondary,
                            modifier = Modifier.fillMaxWidth().background(Palette.card, BottomShape).padding(20.dp),
                        )
                    }
                }
            }
        }
    }

    sharing?.let { s -> ShareSheet(s.title, s.bundle, s.allowKeep) { sharing = null } }
    renamingServer?.let { srv -> ServerRenameDialog(srv) { renamingServer = null } }
    // Same rule as for servers: the field holds the name in use, empty restores the provider's title.
    renamingSub?.let { sub ->
        NameDialog(
            stringResource(R.string.action_rename), Repo.subName(sub), placeholder = sub.name, allowEmpty = true,
            onDismiss = { renamingSub = null },
        ) { Repo.renameSubscription(sub.id, it) }
    }
    deletingServer?.let { srv ->
        AlertDialog(
            onDismissRequest = { deletingServer = null },
            containerColor = Palette.background,
            title = { Text(stringResource(R.string.delete_server_q)) },
            text = { Text(Repo.realName(srv)) },
            confirmButton = {
                TextButton({
                    // Deleting the server in use: the engine moves to another one.
                    TunnelService.send(Engine.Event.Disabled(srv.id))
                    Repo.removeServer(srv.id)
                    deletingServer = null
                }) { Text(stringResource(R.string.delete), color = Palette.danger) }
            },
            dismissButton = { TextButton({ deletingServer = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    deletingSub?.let { sub ->
        AlertDialog(
            onDismissRequest = { deletingSub = null },
            containerColor = Palette.background,
            title = { Text(stringResource(R.string.delete_sub_q)) },
            text = { Text(stringResource(R.string.delete_sub_text, Repo.subName(sub))) },
            confirmButton = { TextButton({ Repo.removeSubscription(sub.id); deletingSub = null }) { Text(stringResource(R.string.delete), color = Palette.danger) } },
            dismissButton = { TextButton({ deletingSub = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (creating) NameDialog(stringResource(R.string.group_new), "", onDismiss = { creating = false }) { Repo.createGroup(it) }
    renaming?.let { g -> NameDialog(stringResource(R.string.group_rename), g.name ?: Repo.groupName(g), onDismiss = { renaming = null }) { Repo.renameGroup(g.id, it) } }
    deleting?.let { g ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            containerColor = Palette.background,
            title = { Text(stringResource(R.string.group_delete_q)) },
            text = {
                Text(stringResource(R.string.group_delete_text2, Repo.groupName(Repo.group(Group.DEFAULT_ID))))
            },
            confirmButton = { TextButton({ Repo.deleteGroup(g.id); deleting = null }) { Text(stringResource(R.string.delete), color = Palette.danger) } },
            dismissButton = { TextButton({ deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

private val TopShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
private val BottomShape = RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp)
private val FullShape = RoundedCornerShape(20.dp)

@Composable
private fun ReorderableCollectionItemScope.GroupHeader(
    row: GroupRow.Header,
    open: Boolean,
    dragging: Boolean,
    /** Members not taking part (hidden by the user, or by auto-visibility when it is on). */
    off: Int,
    onToggle: () -> Unit,
    onDragStart: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit,
) {
    val group = row.group
    val shape: Shape = if (open && !dragging) TopShape else FullShape
    Column {
        // Gap between group cards, inside the item so it moves with it.
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (dragging) Modifier.shadow(8.dp, shape) else Modifier)
                .clip(shape)
                .background(Palette.card)
                .clickable(onClick = onToggle)
                .padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.KeyboardArrowDown, null, tint = Palette.textSecondary,
                modifier = Modifier.size(22.dp).rotate(if (open) 0f else -90f),
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                Text(Repo.groupName(group), fontSize = 17.sp, fontWeight = FontWeight.Bold, fontFamily = Display, color = Palette.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val subNames = Repo.subscriptionsIn(group.id).map(Repo::subName)
                val kind = if (subNames.isEmpty()) stringResource(R.string.group_kind_own)
                else stringResource(R.string.group_kind_subs, subNames.joinToString(", "))
                Text(
                    if (off == 0) stringResource(R.string.group_count, row.members.size, kind)
                    else stringResource(R.string.group_count_off, row.members.size, off, kind),
                    fontSize = 12.5.sp, color = Palette.textSecondary,
                )
            }
            val editable = group.id != Group.DEFAULT_ID
            RowMenu(
                listOfNotNull(
                    MenuAction(Icons.Rounded.Share, stringResource(R.string.share), onClick = onShare),
                    if (editable) MenuAction(Icons.Rounded.Edit, stringResource(R.string.group_rename), onClick = onRename) else null,
                    if (editable) MenuAction(Icons.Rounded.DeleteOutline, stringResource(R.string.delete), danger = true, onClick = onDelete) else null,
                ),
                Modifier.offset(x = MENU_NUDGE),
            )
            DragHandle(Modifier.draggableHandle(onDragStarted = { onDragStart() }))
        }
    }
}

private data class Share(val title: String, val bundle: ShareFormat.Bundle, val allowKeep: Boolean)

/**
 * A subscription inside an expanded group: its servers fold under the arrow. Shows how many servers it
 * has, when it was updated (and whether through a server), traffic and expiry, how often it updates by
 * itself and the last error.
 */
@Composable
private fun ReorderableCollectionItemScope.SubscriptionRow(
    row: GroupRow.SubRow,
    settings: AppSettings,
    busy: Boolean,
    /** Auto-sorted: no drag handles in the list, so the menu goes to the right edge. */
    sorted: Boolean,
    groups: List<Pair<Group, String>>,
    onToggle: () -> Unit,
    onDragStart: () -> Unit,
    onUpdate: () -> Unit,
    onShare: () -> Unit,
    onMove: (String) -> Unit,
    onStats: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val sub = row.sub
    val context = LocalContext.current
    val shape: Shape = if (row.last) BottomShape else RoundedCornerShape(0.dp)
    Row(
        Modifier.fillMaxWidth().background(Palette.card, shape).clickable(onClick = onToggle)
            .padding(start = 14.dp, top = 6.dp, bottom = if (row.last) 10.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Rounded.KeyboardArrowDown, null, tint = Palette.textSecondary,
            modifier = Modifier.size(20.dp).rotate(if (row.open) 0f else -90f),
        )
        Spacer(Modifier.width(6.dp))
        Icon(Icons.Rounded.RssFeed, null, tint = Palette.accent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f).padding(vertical = 2.dp)) {
            // Tap the name to rename the subscription.
            Text(
                Repo.subName(sub), fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = Palette.text,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.clickable(onClick = onRename),
            )
            val updated = if (sub.updatedAt == 0L) stringResource(R.string.sub_never)
            else stringResource(R.string.sub_updated, DateUtils.getRelativeTimeSpanString(sub.updatedAt).toString().lowercase())
            val via = if (sub.viaServer) " · " + stringResource(R.string.sub_via_server) else ""
            Text(stringResource(R.string.sub_line, row.count, updated) + via, fontSize = 12.sp, color = Palette.textSecondary)
            subTraffic(context, sub)?.let { Text(stringResource(R.string.sub_traffic_line, it), fontSize = 12.sp, color = Palette.textSecondary) }
            Text(schedule(context, sub, settings), fontSize = 12.sp, color = Palette.textMuted)
            sub.lastError?.let { Text(stringResource(R.string.sub_error, it), fontSize = 12.sp, color = Palette.danger) }
        }
        if (busy) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Palette.accent)
            }
        } else RowMenu(
            listOfNotNull(
                MenuAction(Icons.Rounded.Refresh, stringResource(R.string.update), onClick = onUpdate),
                MenuAction(Icons.Rounded.Insights, stringResource(R.string.action_stats), onClick = onStats),
                MenuAction(Icons.Rounded.Edit, stringResource(R.string.action_rename), onClick = onRename),
                MenuAction(Icons.Rounded.Share, stringResource(R.string.share), onClick = onShare),
                if (groups.isNotEmpty()) MenuAction(
                    Icons.AutoMirrored.Rounded.DriveFileMove, stringResource(R.string.action_move_group),
                    sub = groups.map { (g, name) -> name to { onMove(g.id) } },
                ) else null,
                MenuAction(Icons.Rounded.DeleteOutline, stringResource(R.string.delete), danger = true, onClick = onDelete),
            ),
            if (sorted) Modifier else Modifier.offset(x = MENU_NUDGE),
        )
        // Dragging reorders the subscriptions of the group; auto-sorted, the handles are gone everywhere.
        if (sorted) Spacer(Modifier.width(6.dp)) else DragHandle(Modifier.draggableHandle(onDragStarted = { onDragStart() }))
    }
}

/** When the subscription is updated automatically: the provider's wish (not too often) or the setting. */
private fun schedule(context: Context, sub: Subscription, s: AppSettings): String {
    val hours = SubMath.intervalHours(s.subUpdateHours, sub.providerHours, s.ecoOn)
    val p = sub.providerHours
    return when {
        hours == 0 -> context.getString(R.string.sub_sched_manual)
        p == null -> context.getString(R.string.sub_sched, hours)
        p >= SubMath.MIN_PROVIDER_HOURS -> context.getString(R.string.sub_sched_provider, hours)
        else -> context.getString(R.string.sub_sched_provider_min, p, hours)
    }
}

private fun subTraffic(context: Context, sub: Subscription): String? {
    fun gb(bytes: Long) = app.borderless.data.Units.bytes(bytes)
    val used = (sub.uploadBytes ?: 0) + (sub.downloadBytes ?: 0)
    val parts = mutableListOf<String>()
    if (sub.totalBytes != null) parts += context.getString(R.string.traffic_of, gb(used), gb(sub.totalBytes))
    else if (used > 0) parts += context.getString(R.string.traffic_used, gb(used))
    sub.expire?.let {
        val date = SimpleDateFormat("d MMM yyyy", context.resources.configuration.locales[0]).format(Date(it * 1000))
        parts += context.getString(R.string.until, date)
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/** One entry of a row's "⋮" menu; with [sub] it opens a second list (e.g. the groups to move to). */
private class MenuAction(
    val icon: ImageVector,
    val label: String,
    val danger: Boolean = false,
    val sub: List<Pair<String, () -> Unit>>? = null,
    val onClick: () -> Unit = {},
)

/** The "⋮" button of a row with all its actions (the drag handle stays separate). */
@Composable
private fun RowMenu(actions: List<MenuAction>, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    var subOf by remember { mutableStateOf<MenuAction?>(null) }
    fun close() { open = false; subOf = null }
    Box(modifier) {
        IconButton({ open = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.more), tint = Palette.textSecondary) }
        AppMenu(open, ::close) {
            val sub = subOf
            if (sub == null) actions.forEach { a ->
                AppMenuItem(
                    a.label, a.icon, danger = a.danger,
                    trailing = if (a.sub != null) Icons.AutoMirrored.Rounded.KeyboardArrowRight else null,
                ) { if (a.sub != null) subOf = a else { close(); a.onClick() } }
            } else {
                // Second level: a header to go back, then the choices.
                AppMenuItem(sub.label, Icons.AutoMirrored.Rounded.ArrowBack) { subOf = null }
                sub.sub.orEmpty().forEach { (name, f) -> AppMenuItem(name, Icons.Rounded.Folder) { close(); f() } }
            }
        }
    }
}

@Composable
private fun ReorderableCollectionItemScope.MemberRow(
    row: GroupRow.Member,
    ping: PingRecord?,
    dragging: Boolean,
    /** Auto-sort is on: no dragging; [rank] is the position in the global scan order (null if not scanned). */
    sorted: Boolean,
    rank: Int?,
    /** Takes part in scans; while [autoActive] the pool decides and the eye only shows it. */
    active: Boolean,
    autoActive: Boolean,
    groups: List<Pair<Group, String>>,
    onDragStart: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onStats: () -> Unit,
    onDelete: () -> Unit,
) {
    val server = row.server
    // Subscription servers can be reordered but move between groups only with their subscription.
    val movable = server.subscriptionId == null
    val off = !active
    val shape: Shape = when {
        dragging -> RoundedCornerShape(14.dp)
        row.last -> BottomShape
        else -> RoundedCornerShape(0.dp)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (dragging) Modifier.shadow(8.dp, shape) else Modifier)
            .background(Palette.card, shape)
            // Long-press anywhere on the row also starts dragging.
            .then(if (sorted) Modifier else Modifier.longPressDraggableHandle(onDragStarted = { onDragStart() }))
            // A subscription's servers sit to the right, along a line down from the subscription.
            .then(
                if (row.nested && !dragging) Modifier.drawBehind {
                    val x = 23.dp.toPx()
                    drawLine(Palette.outline, Offset(x, 0f), Offset(x, if (row.last) size.height - 14.dp.toPx() else size.height), 1.5.dp.toPx())
                } else Modifier
            )
            .padding(start = if (row.nested) 30.dp else 8.dp, top = 2.dp, bottom = if (row.last) 8.dp else 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Disabled servers are greyed out: flag, name and protocol.
        Row(Modifier.weight(1f).alpha(if (off) 0.4f else 1f), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.padding(start = 8.dp).size(30.dp).clip(RoundedCornerShape(9.dp))
                    .background(if (ping?.ok == true && !off) Palette.ping(ping.ms) else Palette.background),
                contentAlignment = Alignment.Center,
            ) { FlagIcon(server.country, 13.dp) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                // Tap the name to rename the server.
                Text(
                    Repo.displayName(server), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Palette.text,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.clickable(onClick = onRename),
                )
                Text(server.protocol, fontSize = 12.sp, color = Palette.textSecondary, maxLines = 1)
            }
        }
        // Auto-sorted: no drag handle; the scan position sits before the menu, which goes to the right edge.
        if (sorted) Box(Modifier.width(28.dp), contentAlignment = Alignment.CenterEnd) {
            if (rank != null && active) Text("$rank", fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = Display, color = Palette.textMuted)
        }
        RowMenu(
            listOfNotNull(
                // With auto-visibility on the pool decides; the user's own choice waits until it is off.
                if (!autoActive) MenuAction(
                    if (off) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                    stringResource(if (off) R.string.action_enable else R.string.action_disable),
                ) {
                    Repo.setServerDisabled(server.id, !off)
                    if (!off) TunnelService.send(Engine.Event.Disabled(server.id))
                } else null,
                MenuAction(Icons.Rounded.Insights, stringResource(R.string.action_stats), onClick = onStats),
                MenuAction(Icons.Rounded.Edit, stringResource(R.string.action_rename), onClick = onRename),
                MenuAction(Icons.Rounded.Share, stringResource(R.string.share), onClick = onShare),
                if (movable && groups.isNotEmpty()) MenuAction(
                    Icons.AutoMirrored.Rounded.DriveFileMove, stringResource(R.string.action_move_group),
                    sub = groups.map { (g, name) -> name to { Repo.setServerGroup(server.id, g.id) } },
                ) else null,
                // Subscription servers would come back with the next update: hide them instead.
                if (server.subscriptionId == null) MenuAction(Icons.Rounded.DeleteOutline, stringResource(R.string.delete), danger = true, onClick = onDelete) else null,
            ),
            if (sorted) Modifier else Modifier.offset(x = MENU_NUDGE),
        )
        if (!sorted) DragHandle(Modifier.draggableHandle(onDragStarted = { onDragStart() }))
        else Spacer(Modifier.width(6.dp))
    }
}

/** Top-bar toggle: the highlight is smaller than the touch target, so neighbouring toggles keep a gap. */
@Composable
private fun ToggleAction(icon: ImageVector, label: String, on: Boolean, onClick: () -> Unit) {
    IconButton(onClick) {
        Box(
            Modifier.size(38.dp).clip(CircleShape).background(if (on) Palette.accentSoft else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, label, tint = if (on) Palette.accentStrong else Palette.textSecondary)
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text, fontSize = 13.sp, color = Palette.accentStrong,
        modifier = Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(Palette.accentSoft).padding(horizontal = 14.dp, vertical = 10.dp),
    )
}

/** The "⋮" sits this much closer to the drag handle after it (both buttons have empty margins around their icons). */
private val MENU_NUDGE = 10.dp

@Composable
private fun DragHandle(modifier: Modifier) {
    Box(modifier.size(44.dp), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.DragIndicator, null, tint = Palette.textMuted)
    }
}

@Composable
fun NameDialog(
    title: String,
    initial: String,
    placeholder: String? = null,
    /** Empty input is allowed (e.g. a server name: empty restores the original). */
    allowEmpty: Boolean = false,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.background,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, singleLine = true,
                placeholder = { Text(placeholder ?: stringResource(R.string.group_name)) },
                shape = RoundedCornerShape(14.dp),
            )
        },
        confirmButton = { TextButton(enabled = allowEmpty || text.isNotBlank(), onClick = { onSave(text.trim()); onDismiss() }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
