package app.borderless.ui.settings

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import app.borderless.ui.AppAlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.borderless.R
import app.borderless.data.CustomZone
import app.borderless.data.Errors
import app.borderless.data.Repo
import app.borderless.data.RoutingMode
import app.borderless.data.ZoneConflicts
import app.borderless.data.ZoneFile
import app.borderless.service.TunnelService
import app.borderless.ui.theme.Palette
import java.io.File

/** Lists longer than this are not edited in a text field (it gets sluggish): the file is the way to change them. */
private const val EDITABLE_LINES = 3000

/**
 * One of the user's domain zones: its name, whether it is used, its sites and addresses without the tunnel and through
 * the server, its apps, and what in it doesn't apply because something else wins ([ZoneConflicts]). Shared as a zone
 * file, deleted from here. Changes reach a running tunnel when the screen closes.
 */
@Composable
fun ZoneScreen(id: String, onBack: () -> Unit) {
    val zones by Repo.zones.collectAsStateWithLifecycle()
    val s by Repo.settings.collectAsStateWithLifecycle()
    val zone = zones.firstOrNull { it.id == id }
    val initial = remember { Repo.zone(id) to (id in Repo.settings.value.regions) }
    DisposableEffect(Unit) {
        onDispose { if ((Repo.zone(id) to (id in Repo.settings.value.regions)) != initial) TunnelService.reconfigure() }
    }
    if (zone == null) {
        // Deleted (or never there): nothing to show.
        androidx.compose.runtime.LaunchedEffect(Unit) { onBack() }
        return
    }
    val context = LocalContext.current
    var skippedDirect by remember { mutableStateOf(emptyList<String>()) }
    var skippedServer by remember { mutableStateOf(emptyList<String>()) }
    var skippedApps by remember { mutableStateOf(emptyList<String>()) }
    var askDelete by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<Picked?>(null) }
    var loaded by remember { mutableStateOf<Loaded?>(null) }
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val p = Errors.guard("loading a zone file", null, show = false) { read(context, uri) }
        if (p == null) loaded = Loaded(null, 0, 0, false, unreadable = true) else picked = p
    }
    fun save(z: CustomZone) = Repo.saveZone(z)

    ScreenScaffold(zone.name, onBack) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            Section(stringResource(R.string.zone_section)) {
                TextRow(stringResource(R.string.zone_name), zone.name) { v -> v.trim().take(60).takeIf { it.isNotEmpty() }?.let { save(zone.copy(name = it)) } }
                SwitchRow(stringResource(R.string.zone_on), null, id in s.regions) { on ->
                    Repo.updateSettings { it.copy(regions = if (on) it.regions + id else it.regions - id) }
                }
                if (s.routingMode == RoutingMode.LISTED_ONLY) InfoNote(stringResource(R.string.zone_listed_note))
            }
            Section(stringResource(R.string.zone_direct)) {
                EntriesRow(stringResource(R.string.zone_sites), zone.direct, stringResource(R.string.zone_sites_hint), skippedDirect) { text ->
                    val (ok, bad) = ZoneFile.entries(text)
                    skippedDirect = bad
                    save(zone.copy(direct = ok + zone.direct.filter(::isList)))
                }
                ListRows(zone, zone.direct) { e -> save(zone.copy(direct = zone.direct - e, listSizes = zone.listSizes - e)) }
                EntriesRow(stringResource(R.string.zone_apps), zone.apps, stringResource(R.string.zone_apps_hint), skippedApps, apps = true) { text ->
                    val (ok, bad) = ZoneFile.entries(text, apps = true)
                    skippedApps = bad
                    save(zone.copy(apps = ok))
                }
            }
            Section(stringResource(R.string.zone_server)) {
                EntriesRow(stringResource(R.string.zone_sites), zone.server, stringResource(R.string.zone_sites_hint), skippedServer) { text ->
                    val (ok, bad) = ZoneFile.entries(text)
                    skippedServer = bad
                    save(zone.copy(server = ok + zone.server.filter(::isList)))
                }
                ListRows(zone, zone.server) { e -> save(zone.copy(server = zone.server - e, listSizes = zone.listSizes - e)) }
            }
            // What in it doesn't apply, and why (the order in the core: own lists, then "through the server").
            val chosen = zones.filter { it.id in s.regions }
            val conflicts = remember(zone, chosen, s.directDomains, s.proxyDomains, s.appsTunnel) {
                ZoneConflicts.of(zone, chosen, s.directDomains, s.proxyDomains, s.appsTunnel)
            }
            if (conflicts.isNotEmpty()) Section(stringResource(R.string.zone_conflicts)) {
                conflicts.take(50).forEach { c ->
                    val e = ZoneFile.display(c.entry)
                    InfoNote(
                        when (c.reason) {
                            ZoneConflicts.Reason.OWN_SERVER -> stringResource(R.string.zone_c_own_server, e)
                            ZoneConflicts.Reason.OWN_DIRECT -> stringResource(R.string.zone_c_own_direct, e)
                            ZoneConflicts.Reason.ZONE_SERVER -> stringResource(R.string.zone_c_zone_server, e, c.by.orEmpty())
                            ZoneConflicts.Reason.OWN_APP_TUNNEL -> stringResource(R.string.zone_c_app_tunnel, e)
                        }
                    )
                }
                if (conflicts.size > 50) InfoNote(stringResource(R.string.zone_c_more, conflicts.size - 50))
            }
            Section(stringResource(R.string.zone_file)) {
                // More of a list into this zone: e.g. a region's sites and, from another file, its addresses.
                ActionRow(stringResource(R.string.zone_add_file), stringResource(R.string.zone_add_file_sub)) { pick.launch("*/*") }
                ActionRow(stringResource(R.string.zone_share), stringResource(R.string.zone_share_sub)) {
                    Errors.guard("sharing a zone", Unit) {
                        val dir = File(context.cacheDir, "share").apply { mkdirs() }
                        val file = File(dir, zone.name.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").ifBlank { "zone" } + ".zone.txt")
                        file.writeText(ZoneFile.write(zone))
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        context.startActivity(Intent.createChooser(send, zone.name))
                    }
                }
                ActionRow(stringResource(R.string.zone_delete)) { askDelete = true }
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    picked?.let { p ->
        ImportDialog(p, zone, zone.name, onDismiss = { picked = null }) { z, result ->
            picked = null
            if (z != null) Repo.saveZone(z)
            loaded = result
        }
    }
    loaded?.let { r -> LoadedDialog(r, onZone = null) { loaded = null } }

    if (askDelete) {
        AppAlertDialog(
            irreversible = true,
            onDismissRequest = { askDelete = false },
            containerColor = Palette.background,
            title = { Text(stringResource(R.string.zone_delete_q, zone.name)) },
            confirmButton = {
                TextButton({ askDelete = false; Repo.deleteZone(id); onBack() }) { Text(stringResource(R.string.delete), color = Palette.danger) }
            },
            dismissButton = { TextButton({ askDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** A category of a `.dat` kept with the zone (see DatFile): not edited as text, shown and removed on its own. */
private fun isList(entry: String) = entry.startsWith("ext:")

/** The `.dat` categories among [entries]: name and size; tap asks whether to remove it. */
@Composable
private fun ListRows(zone: CustomZone, entries: List<String>, onRemove: (String) -> Unit) {
    var ask by remember { mutableStateOf<String?>(null) }
    entries.filter(::isList).forEach { e ->
        val name = e.substringAfterLast(':').lowercase()
        val size = zone.listSizes[e]
        ActionRow(name, if (size != null) stringResource(R.string.zone_list_size, size) else stringResource(R.string.zone_list)) { ask = e }
    }
    ask?.let { e ->
        AppAlertDialog(
            irreversible = true,
            onDismissRequest = { ask = null },
            containerColor = Palette.background,
            title = { Text(stringResource(R.string.zone_list_remove_q, e.substringAfterLast(':').lowercase())) },
            confirmButton = { TextButton({ onRemove(e); ask = null }) { Text(stringResource(R.string.delete), color = Palette.danger) } },
            dismissButton = { TextButton({ ask = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** A list of entries edited as text, one per line; what wasn't understood is said under it. */
@Composable
private fun EntriesRow(title: String, all: List<String>, hint: String, skipped: List<String>, apps: Boolean = false, onChange: (String) -> Unit) {
    val entries = all.filterNot(::isList)
    if (entries.size > EDITABLE_LINES) {
        InfoRow(title, entries.size.toString(), stringResource(R.string.zone_too_long))
    } else {
        TextRow(
            title, entries.joinToString("\n") { if (apps) it else ZoneFile.display(it) }, multiline = true, hint = hint,
            emptyText = stringResource(R.string.zone_none),
        ) { onChange(it) }
    }
    if (skipped.isNotEmpty()) InfoNote(stringResource(R.string.zone_skipped, skipped.take(10).joinToString(", ") + if (skipped.size > 10) "…" else ""))
}
