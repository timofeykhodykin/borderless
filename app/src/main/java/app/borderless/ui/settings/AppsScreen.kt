package app.borderless.ui.settings

import app.borderless.data.Errors
import android.content.Context
import app.borderless.R
import androidx.compose.ui.res.stringResource
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.borderless.data.AppSettings
import app.borderless.data.Region
import app.borderless.data.Repo
import app.borderless.ui.TabPages
import app.borderless.ui.TabStrip
import app.borderless.ui.rememberTabs
import app.borderless.data.Regions
import app.borderless.service.TunnelService
import app.borderless.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AppEntry(val pkg: String, val label: String, val system: Boolean)

fun loadApps(context: Context): List<AppEntry> {
    val pm = context.packageManager
    return pm.getInstalledApplications(PackageManager.GET_META_DATA)
        .filter { it.packageName != context.packageName }
        .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 || pm.getLaunchIntentForPackage(it.packageName) != null }
        .map { AppEntry(it.packageName, pm.getApplicationLabel(it).toString(), (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0) }
        .sortedBy { it.label.lowercase() }
}

/**
 * Two lists of apps: "without the tunnel" and "through the tunnel". An app can be in one of them at most (its box in
 * the other list is disabled); both win over the domain zones, whose apps go without the tunnel on their own (shown).
 */
@Composable
fun AppsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val s by Repo.settings.collectAsStateWithLifecycle()
    val initial = remember { Repo.settings.value.let { it.appsDirect to it.appsTunnel } }
    DisposableEffect(Unit) {
        onDispose { if (Repo.settings.value.let { it.appsDirect to it.appsTunnel } != initial) TunnelService.reconfigure() }
    }
    val apps by produceState<List<AppEntry>?>(null) {
        value = withContext(Dispatchers.IO) { Errors.guard("reading installed apps", emptyList()) { loadApps(context) } }
    }
    // The search text is shared by both lists.
    var query by remember { mutableStateOf("") }
    val zones = remember(s.regions) { Regions.active(s.regions) }
    // 0 = without the tunnel, 1 = through the tunnel; tap a tab or swipe sideways.
    val tabs = rememberTabs(2)

    ScreenScaffold(stringResource(R.string.apps_title), onBack) {
        TabStrip(
            listOf(
                stringResource(R.string.apps_without_n, s.appsDirect.size),
                stringResource(R.string.apps_through_n, s.appsTunnel.size),
            ),
            tabs,
        )
        TabPages(tabs) { tab ->
            AppsList(tab, s, apps, initial, zones, query) { query = it }
        }
    }
}

/** One of the two lists: the hint, the search field, "clear the list" and the apps with their boxes. */
@Composable
private fun AppsList(
    /** 0 = without the tunnel, 1 = through it. */
    tab: Int,
    s: AppSettings,
    apps: List<AppEntry>?,
    /** The two lists as they were when the screen opened (they decide the order of the rows). */
    initial: Pair<Set<String>, Set<String>>,
    zones: List<Region>,
    query: String,
    onQuery: (String) -> Unit,
) {
    val direct = tab == 0
    Column {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(
                stringResource(if (direct) R.string.apps_without_hint else R.string.apps_through_hint),
                fontSize = 12.5.sp, color = Palette.textSecondary, modifier = Modifier.padding(top = 6.dp, start = 4.dp, end = 4.dp),
            )
            OutlinedTextField(
                value = query, onValueChange = onQuery, singleLine = true,
                leadingIcon = { Icon(Icons.Rounded.Search, null, tint = Palette.textMuted) },
                placeholder = { Text(stringResource(R.string.search), color = Palette.textMuted) },
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = Palette.outline, focusedBorderColor = Palette.accent,
                    unfocusedContainerColor = Palette.card,
                ),
            )
            if ((if (direct) s.appsDirect else s.appsTunnel).isNotEmpty()) {
                Text(
                    stringResource(R.string.apps_clear), fontSize = 13.sp, color = Palette.accentStrong, fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 8.dp, start = 4.dp).clip(RoundedCornerShape(8.dp)).clickable {
                        Repo.updateSettings { if (direct) it.copy(appsDirect = emptySet()) else it.copy(appsTunnel = emptySet()) }
                    }.padding(4.dp),
                )
            }
        }
        if (apps == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Palette.accent) }
            return@Column
        }
        val q = query.trim().lowercase()
        val mine = if (direct) initial.first else initial.second
        val filtered = apps.filter { q.isEmpty() || q in it.label.lowercase() || q in it.pkg }
            // Apps in this list when the screen opened go first, then the zones' apps; the live set would make rows jump.
            .sortedWith(compareByDescending<AppEntry> { it.pkg in mine }.thenByDescending { app -> zones.any { it.apps.matches(app.pkg) } })
        LazyColumn(contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 32.dp)) {
            items(filtered, key = { it.pkg }) { app ->
                val inThis = app.pkg in (if (direct) s.appsDirect else s.appsTunnel)
                val inOther = app.pkg in (if (direct) s.appsTunnel else s.appsDirect)
                val zone = zones.firstOrNull { it.apps.matches(app.pkg) }
                fun toggle(on: Boolean) = Repo.updateSettings {
                    if (direct) it.copy(appsDirect = if (on) it.appsDirect + app.pkg else it.appsDirect - app.pkg)
                    else it.copy(appsTunnel = if (on) it.appsTunnel + app.pkg else it.appsTunnel - app.pkg)
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(enabled = !inOther) { toggle(!inThis) }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppIcon(app.pkg)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(app.label, fontSize = 15.sp, color = if (inOther) Palette.textMuted else Palette.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(app.pkg, fontSize = 11.5.sp, color = Palette.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        // Why it can't be ticked here, or that its zone already takes it out of the tunnel.
                        val note = when {
                            inOther -> stringResource(if (direct) R.string.apps_in_through else R.string.apps_in_without)
                            zone != null -> stringResource(R.string.apps_zone, zone.code)
                            else -> null
                        }
                        note?.let { Text(it, fontSize = 11.5.sp, color = Palette.accentStrong, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                    Checkbox(
                        checked = inThis,
                        enabled = !inOther,
                        onCheckedChange = { v -> toggle(v) },
                        colors = CheckboxDefaults.colors(checkedColor = Palette.accent, uncheckedColor = Palette.textMuted),
                    )
                }
            }
        }
    }
}

private val iconCache = android.util.LruCache<String, ImageBitmap>(200)

@Composable
private fun AppIcon(pkg: String) {
    val context = LocalContext.current
    val icon by produceState(iconCache.get(pkg), pkg) {
        if (value == null) value = withContext(Dispatchers.IO) {
            runCatching { context.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap() }
                .getOrNull()?.also { iconCache.put(pkg, it) }
        }
    }
    Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(Palette.card)) {
        icon?.let { Image(it, null, Modifier.size(40.dp)) }
    }
}
