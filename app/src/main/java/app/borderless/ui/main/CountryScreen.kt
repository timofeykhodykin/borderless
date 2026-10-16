package app.borderless.ui.main

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.borderless.R
import app.borderless.Res
import app.borderless.core.CountryFilter
import app.borderless.core.Scanner
import app.borderless.core.TunnelState
import app.borderless.data.Countries
import app.borderless.data.CountrySort
import app.borderless.data.CountryStats
import app.borderless.data.PingRecord
import app.borderless.data.Repo
import app.borderless.data.Server
import app.borderless.data.StatsDb
import app.borderless.ui.AppMenu
import app.borderless.ui.AppMenuItem
import app.borderless.ui.FlagIcon
import app.borderless.ui.settings.ScreenScaffold
import app.borderless.ui.settings.SearchField
import app.borderless.ui.theme.Body
import app.borderless.ui.theme.Display
import app.borderless.ui.theme.Palette
import java.text.Collator

/** Stability is judged over this long. */
private const val STABILITY_MS = 7 * 86_400_000L

/**
 * Servers of chosen countries only: every country (servers whose country isn't known: one list), sorted by name,
 * by how often it was chosen or by how reliably its servers answer, found by the search; each folded with its
 * servers inside (in the scan order with auto-sort on). The flag's square chooses a country — several can be
 * chosen: chosen ones move to the top, above a line; tapping the square again puts it back. The rest of the list
 * stays where it is meanwhile (only the country travels).
 */
@Composable
fun CountryScreen(onBack: () -> Unit) {
    val servers by Repo.servers.collectAsStateWithLifecycle()
    val pings by Repo.pings.collectAsStateWithLifecycle()
    val groups by Repo.groups.collectAsStateWithLifecycle()
    val subs by Repo.subscriptions.collectAsStateWithLifecycle()
    val settings by Repo.settings.collectAsStateWithLifecycle()
    val plan by Scanner.plan.collectAsStateWithLifecycle()
    val pending by Scanner.pending.collectAsStateWithLifecycle()
    val probing by Scanner.probing.collectAsStateWithLifecycle()
    val status by TunnelState.status.collectAsStateWithLifecycle()
    val spin = rememberSpin(pending.isNotEmpty())
    var showEmpty by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var sortMenu by remember { mutableStateOf(false) }
    // Folded by default.
    var open by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val chosen = settings.countries
    val sort = settings.countrySort

    // The user's order (groups, then their blocks), or the scan order with auto-sort; servers the plan doesn't
    // hold (they don't take part now) keep the user's order after it.
    val ordered = remember(servers, groups, subs, plan, settings.autoSort) {
        val users = Repo.serversByGroup(servers, groups, subscriptions = subs).flatMap { it.second }
        if (!settings.autoSort) users
        else plan.withIndex().associate { (i, id) -> id to i }.let { rank -> users.sortedBy { rank[it.id] ?: Int.MAX_VALUE } }
    }
    val byCountry = remember(ordered) { ordered.groupBy(Countries::of) }
    val groupNames = remember(groups, subs) { groups.associate { it.id to Repo.groupName(it) } }
    // What the sorts go by: times each country was chosen, and the share of answers of its servers lately.
    val used by produceState(emptyMap<String, Int>(), chosen) {
        value = CountryStats.timesChosen(StatsDb.settingsHistory("country", 0, System.currentTimeMillis()).map { it.second })
    }
    val stability by produceState(emptyMap<String, Double>(), servers) {
        val now = System.currentTimeMillis()
        value = CountryStats.stability(servers, StatsDb.aggregates(now - STABILITY_MS, now))
    }
    val collator = remember(Res.locale) { Collator.getInstance(Res.locale) }
    val byName = compareBy(collator) { code: String -> Countries.label(code) }
    val order: Comparator<String> = when (sort) {
        CountrySort.NAME -> byName
        CountrySort.NAME_DESC -> byName.reversed()
        // Ties (and the never chosen / never checked) alphabetically.
        CountrySort.USED -> compareByDescending<String> { used[it] ?: 0 }.then(byName)
        CountrySort.STABLE -> compareByDescending<String> { stability[it] ?: -1.0 }.then(byName)
    }
    val q = query.trim().lowercase()
    fun matches(code: String) = q.isEmpty() || Countries.label(code).lowercase().contains(q) || code.lowercase() == q
    val all = remember(byCountry, showEmpty) {
        byCountry.keys + if (showEmpty) java.util.Locale.getISOCountries().toSet() + Countries.UNKNOWN else emptySet()
    }
    // The unknown country's list stays last whatever the order (it isn't a country).
    fun arrange(codes: Collection<String>) = codes.filter(::matches).sortedWith(compareBy<String> { it == Countries.UNKNOWN }.then(order))

    // The list's items in order: the chosen countries, a note and a line, the switch, the other countries.
    fun keysFor(picked: Set<String>): List<String> = buildList {
        val top = arrange(picked)
        top.forEach { add(it) }
        if (top.isNotEmpty()) { add(NOTE); add(LINE) }
        add(EMPTY)
        arrange(all - picked).forEach { add(it) }
    }
    val keys = keysFor(chosen)
    val listState = rememberLazyListState()
    // A position to keep when the list gets its new order (see toggle): that order, the index and the offset.
    var scrollWanted by remember { mutableStateOf<Triple<List<String>, Int, Int>?>(null) }
    // A new search or order shows its list from the top (the chosen countries first), not wherever the old one was.
    val listedBy = q to sort
    var lastListedBy by remember { mutableStateOf(listedBy) }
    if (listedBy != lastListedBy) {
        lastListedBy = listedBy
        listState.requestScrollToItem(0)
    }

    // Choosing a country moves only that country; every row animates to its new place by itself (animateItem, also
    // to and from places off screen). A lazy list keeps its first visible row in place by key, which is right unless
    // that row is the country itself (the list would follow it) or the list is at its very top (a country chosen
    // there would appear above the screen). Then the list is told to stay at the same index and offset instead:
    // requested in the composition that already has the new order (the choice reaches the screen a frame after it
    // is made), with the position it has now, which keeps the item animations (asking for any other position would
    // count as a scroll and drop them).
    if (scrollWanted?.first == keys) {
        val (_, index, offset) = scrollWanted!!
        listState.requestScrollToItem(index, offset)
        scrollWanted = null
    }
    fun toggle(code: String) {
        val next = if (code in chosen) chosen - code else chosen + code
        val first = listState.layoutInfo.visibleItemsInfo.firstOrNull()
        val atTop = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
        scrollWanted = if (atTop || first?.key == code) Triple(keysFor(next), listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) else null
        CountryFilter.set(next)
        // With the connection off nobody else checks the new set (the engine does when it runs).
        if (!TunnelState.status.value.active && Repo.activeServers.isNotEmpty()) Scanner.start()
    }

    ScreenScaffold(stringResource(R.string.countries_title), onBack, actions = {
        Box {
            IconButton({ sortMenu = true }) { Icon(Icons.AutoMirrored.Rounded.Sort, stringResource(R.string.country_sort), tint = Palette.textSecondary) }
            AppMenu(sortMenu, { sortMenu = false }) {
                listOf(
                    CountrySort.NAME to R.string.country_sort_name,
                    CountrySort.NAME_DESC to R.string.country_sort_name_desc,
                    CountrySort.USED to R.string.country_sort_used,
                    CountrySort.STABLE to R.string.country_sort_stable,
                ).forEach { (v, label) ->
                    AppMenuItem(stringResource(label), selected = v == sort, trailing = if (v == sort) Icons.Rounded.Check else null) {
                        Repo.updateSettings { it.copy(countrySort = v) }
                        sortMenu = false
                    }
                }
            }
        }
    }) {
        SearchField(query, { query = it }, stringResource(R.string.country_search))
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
            modifier = Modifier.navigationBarsPadding(),
        ) {
            keys.forEach { key ->
                when (key) {
                    NOTE -> item(key = key) {
                        Text(
                            stringResource(R.string.country_note), fontSize = 13.sp, color = Palette.textMuted,
                            modifier = Modifier.animateItem().padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                    LINE -> item(key = key) { HorizontalDivider(Modifier.animateItem().padding(vertical = 8.dp), color = Palette.outline) }
                    EMPTY -> item(key = key) {
                        Row(
                            Modifier.animateItem().fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { showEmpty = !showEmpty }
                                .padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(stringResource(R.string.country_show_empty), fontSize = 15.sp, color = Palette.text, modifier = Modifier.weight(1f))
                            Switch(
                                showEmpty, { showEmpty = it },
                                colors = SwitchDefaults.colors(
                                    checkedTrackColor = Palette.accent, uncheckedTrackColor = Palette.cardStrong,
                                    uncheckedBorderColor = Palette.outline, uncheckedThumbColor = Palette.textMuted,
                                ),
                            )
                        }
                    }
                    else -> item(key = key) {
                        val members = byCountry[key].orEmpty()
                        val expanded = key in open
                        Column(Modifier.animateItem()) {
                            CountryRow(
                                key, members, pings, expanded, key in chosen,
                                extra = when (sort) {
                                    CountrySort.USED -> used[key]?.let { pluralStringResource(R.plurals.country_used_n, it, it) }
                                    CountrySort.STABLE -> stability[key]?.let { stringResource(R.string.country_answers, (it * 100).toInt()) }
                                    else -> null
                                },
                                onToggle = { open = if (expanded) open - key else open + key },
                                onChoose = { toggle(key) },
                            )
                            if (expanded) Column(Modifier.padding(start = 12.dp, bottom = 6.dp)) {
                                if (members.isEmpty()) {
                                    Text(stringResource(R.string.country_none), fontSize = 13.5.sp, color = Palette.textMuted, modifier = Modifier.padding(12.dp))
                                }
                                members.forEach { s ->
                                    ServerRow(
                                        server = s,
                                        ping = pings[s.id],
                                        group = groupNames[Repo.groupOf(s)],
                                        current = status.active && s.id == status.serverId,
                                        pending = s.id in pending,
                                        probing = s.id in probing,
                                        spin = spin,
                                        onClick = {},
                                        onLongClick = {},
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private const val NOTE = "#note"
private const val LINE = "#line"
private const val EMPTY = "#empty"

/**
 * A country: its flag in a square that is a button (tap: choose the country, or let it go again — outlined in the
 * accent on a pale accent fill while chosen), how many servers it has, the best ping among them and [extra] (what
 * the list is sorted by). Tapping the rest of the row folds its servers out.
 */
@Composable
private fun CountryRow(
    code: String, members: List<Server>, pings: Map<String, PingRecord>, open: Boolean, chosen: Boolean, extra: String?,
    onToggle: () -> Unit, onChoose: () -> Unit,
) {
    val best = members.mapNotNull { pings[it.id]?.takeIf { p -> p.ok }?.ms }.minOrNull()
    val fill by animateColorAsState(if (chosen) Palette.selected else Palette.card, label = "flagFill")
    val edge by animateColorAsState(if (chosen) Palette.accent else Palette.textMuted.copy(alpha = 0.45f), label = "flagEdge")
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(shape)
                .background(fill)
                .border(if (chosen) 2.dp else 1.dp, edge, shape)
                .clickable(onClickLabel = stringResource(if (chosen) R.string.country_unchoose else R.string.country_use), onClick = onChoose),
            contentAlignment = Alignment.Center,
        ) {
            FlagIcon(code.takeIf { it != Countries.UNKNOWN }, 18.dp)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                Countries.label(code), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, fontFamily = Display,
                color = if (chosen) Palette.accentStrong else if (members.isEmpty()) Palette.textSecondary else Palette.text,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(
                    if (members.isEmpty()) stringResource(R.string.country_no_servers)
                    else pluralStringResource(R.plurals.country_servers, members.size, members.size),
                    best?.let { stringResource(R.string.country_best, it) },
                    extra,
                ).joinToString(" · "),
                fontSize = 12.5.sp, color = Palette.textSecondary, fontFamily = Body,
            )
        }
        Icon(Icons.Rounded.KeyboardArrowDown, null, tint = Palette.textSecondary, modifier = Modifier.size(22.dp).rotate(if (open) 0f else -90f))
    }
}
