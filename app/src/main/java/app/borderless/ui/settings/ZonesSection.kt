package app.borderless.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import app.borderless.ui.AppAlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.borderless.R
import app.borderless.data.AppSettings
import app.borderless.data.CustomZone
import app.borderless.data.DatFile
import app.borderless.data.Errors
import app.borderless.data.Repo
import app.borderless.data.ZoneConflicts
import app.borderless.data.ZoneFile
import app.borderless.ui.theme.Palette

/** Bigger files are not zones (and would make the core slow to start). */
private const val ZONE_FILE_MAX = 30_000_000

/** A file picked to become a zone: a text list or Xray's own `.dat`, read and waiting for the user's choices. */
internal class Picked(val fileName: String?, val text: ZoneFile.Parsed?, val dat: DatFile.Parsed?)

/** What came of loading: the zone (null: nothing usable) and what to tell about it. */
internal class Loaded(val id: String?, val entries: Int, val skipped: Int, val truncated: Boolean, val unreadable: Boolean = false)

/**
 * The user's own domain zones: each with its switch (the row opens it), a new one written here, or one loaded from
 * a file (any list of sites and addresses, see [ZoneFile]; or chosen categories of an Xray `.dat`, see [DatFile]).
 * Under them how zones and the user's own lists agree (see [ZoneConflicts]).
 */
@Composable
internal fun CustomZonesSection(s: AppSettings, onZone: (String) -> Unit) {
    val zones by Repo.zones.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var picked by remember { mutableStateOf<Picked?>(null) }
    var loaded by remember { mutableStateOf<Loaded?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val p = Errors.guard("loading a zone file", null, show = false) { read(context, uri) }
        if (p == null) loaded = Loaded(null, 0, 0, false, unreadable = true) else picked = p
    }
    Section(stringResource(R.string.zones_own)) {
        val chosen = zones.filter { it.id in s.regions }
        zones.forEach { z ->
            val conflicts = remember(z, chosen, s.directDomains, s.proxyDomains, s.appsTunnel) {
                ZoneConflicts.of(z, chosen, s.directDomains, s.proxyDomains, s.appsTunnel).size
            }
            SwitchRow(
                z.name,
                listOfNotNull(
                    stringResource(R.string.zone_part_direct, z.count(z.direct)).takeIf { z.direct.isNotEmpty() },
                    stringResource(R.string.zone_part_server, z.count(z.server)).takeIf { z.server.isNotEmpty() },
                    stringResource(R.string.zone_part_apps, z.apps.size).takeIf { z.apps.isNotEmpty() },
                    pluralStringResource(R.plurals.zone_conflicts_n, conflicts, conflicts).takeIf { conflicts > 0 },
                ).joinToString(" · ").ifEmpty { stringResource(R.string.zone_none) },
                checked = z.id in s.regions,
                onClick = { onZone(z.id) },
            ) { on -> Repo.updateSettings { it.copy(regions = if (on) it.regions + z.id else it.regions - z.id) } }
        }
        ActionRow(stringResource(R.string.zone_new), stringResource(R.string.zone_new_sub)) {
            val z = CustomZone(CustomZone.newId(), context.getString(R.string.zone_default_name, zones.size + 1))
            Repo.saveZone(z, enable = true)
            onZone(z.id)
        }
        ActionRow(stringResource(R.string.zone_load), stringResource(R.string.zone_load_sub)) { pick.launch("*/*") }
        InfoNote(stringResource(R.string.zones_order_note))
    }
    picked?.let { p ->
        ImportDialog(p, null, context.getString(R.string.zone_default_name, zones.size + 1), onDismiss = { picked = null }) { z, result ->
            picked = null
            if (z != null) Repo.saveZone(z, enable = true)
            loaded = result
        }
    }
    loaded?.let { r -> LoadedDialog(r, onZone) { loaded = null } }
}

/** How loading went; "Open" opens the zone ([onZone] null: it is open already). */
@Composable
internal fun LoadedDialog(r: Loaded, onZone: ((String) -> Unit)?, onDismiss: () -> Unit) {
    run {
        AppAlertDialog(
            onDismissRequest = onDismiss,
            containerColor = Palette.background,
            title = { Text(stringResource(if (r.id != null) R.string.zone_loaded else R.string.zone_load_failed)) },
            text = {
                Text(
                    when {
                        r.unreadable -> stringResource(R.string.zone_load_unreadable)
                        r.id == null -> stringResource(R.string.zone_load_nothing)
                        else -> listOfNotNull(
                            stringResource(R.string.zone_loaded_text, r.entries, r.skipped),
                            stringResource(R.string.zone_loaded_truncated, ZoneFile.MAX_ENTRIES).takeIf { r.truncated },
                        ).joinToString("\n")
                    }
                )
            },
            confirmButton = {
                TextButton({ onDismiss(); if (onZone != null) r.id?.let(onZone) }) {
                    Text(stringResource(if (r.id != null && onZone != null) R.string.zone_open else R.string.ok))
                }
            },
        )
    }
}

/** Reads a picked file: Xray's `.dat` if it is one (binary, starts with an entry), else text lines. */
internal fun read(context: android.content.Context, uri: Uri): Picked? {
    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
        val out = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        while (out.size() <= ZONE_FILE_MAX) {
            val n = input.read(chunk)
            if (n < 0) break
            out.write(chunk, 0, n)
        }
        out.takeIf { it.size() <= ZONE_FILE_MAX }?.toByteArray()
    } ?: return null
    val name = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }?.substringBefore('.')?.takeIf { it.isNotBlank() }
    val dat = if (bytes.firstOrNull() == 0x0A.toByte()) DatFile.read(bytes) else null
    return if (dat != null) Picked(name, null, dat) else Picked(name, ZoneFile.parse(String(bytes, Charsets.UTF_8)), null)
}

/**
 * The choices before a file becomes a zone (or is added to [target]): its name; the categories to take from a `.dat`
 * (found by a search, the file may have hundreds); and where the entries go when the file doesn't say (a `.dat`
 * never does). A `.dat`'s chosen categories are kept as a file of the zone's own (see [DatFile]).
 */
@Composable
internal fun ImportDialog(p: Picked, target: CustomZone?, defaultName: String, onDismiss: () -> Unit, onDone: (CustomZone?, Loaded) -> Unit) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(target?.name ?: p.text?.name ?: p.fileName ?: defaultName) }
    var server by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var cats by remember { mutableStateOf(emptySet<String>()) }
    val askWhere = p.dat != null || p.text?.sectioned == false
    AppAlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.background,
        title = { Text(stringResource(R.string.zone_import_title), fontSize = 18.sp) },
        text = {
            Column {
                if (target == null) OutlinedTextField(
                    name, { name = it }, singleLine = true, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.zone_name)) },
                )
                p.dat?.let { dat ->
                    Spacer(Modifier.height(10.dp))
                    Text(
                        stringResource(if (dat.kind == DatFile.Kind.SITES) R.string.zone_dat_sites else R.string.zone_dat_addresses, dat.categories.size),
                        fontSize = 13.sp, color = Palette.textSecondary,
                    )
                    if (dat.categories.size > 8) OutlinedTextField(
                        query, { query = it }, singleLine = true, shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp), placeholder = { Text(stringResource(R.string.zone_dat_search)) },
                    )
                    val shown = dat.categories.filter { query.isBlank() || it.code.contains(query.trim(), ignoreCase = true) }
                    LazyColumn(Modifier.heightIn(max = 240.dp).padding(top = 6.dp)) {
                        items(shown, key = { it.code }) { c ->
                            val on = c.code in cats
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { cats = if (on) cats - c.code else cats + c.code }
                                    .padding(horizontal = 6.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(c.code.lowercase(), fontSize = 15.sp, color = if (on) Palette.accentStrong else Palette.text, modifier = Modifier.weight(1f))
                                Text(c.size.toString(), fontSize = 13.sp, color = Palette.textMuted)
                                Spacer(Modifier.padding(start = 8.dp))
                                Icon(Icons.Rounded.Check, null, tint = if (on) Palette.accent else Palette.background, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
                if (askWhere) {
                    Spacer(Modifier.height(8.dp))
                    listOf(false to R.string.zone_direct, true to R.string.zone_server).forEach { (v, label) ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { server = v },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(server == v, { server = v }, colors = RadioButtonDefaults.colors(selectedColor = Palette.accent))
                            Text(stringResource(label), fontSize = 15.sp, color = Palette.text)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && (p.dat == null || cats.isNotEmpty()),
                onClick = {
                    val id = target?.id ?: CustomZone.newId()
                    val dat = p.dat
                    var sizes = emptyMap<String, Int>()
                    // New entries, where they go, and how many there are.
                    val (entries, count) = if (dat != null) {
                        val dir = app.borderless.data.GeoFile.dir ?: java.io.File(context.filesDir, "geo")
                        val file = DatFile.fileName(id, dat.kind)
                        val ok = Errors.guard("saving a zone's list file", false, show = false) {
                            java.io.File(dir, file).writeBytes(DatFile.subset(dat, cats)); true
                        }
                        if (!ok) return@TextButton onDone(null, Loaded(null, 0, 0, false, unreadable = true))
                        sizes = dat.categories.filter { it.code in cats }.associate { DatFile.rule(file, it.code) to it.size }
                        cats.sorted().map { DatFile.rule(file, it) } to sizes.values.sum()
                    } else {
                        val t = p.text ?: return@TextButton onDismiss()
                        if (t.empty) return@TextButton onDone(null, Loaded(null, 0, t.skipped.size, false))
                        emptyList<String>() to 0
                    }
                    val t = p.text
                    // A list that doesn't say where it goes goes where the user said.
                    val addDirect = if (t != null) (if (askWhere && server) emptyList() else t.direct) else if (server) emptyList() else entries
                    val addServer = if (t != null) (if (askWhere && server) t.direct else t.server) else if (server) entries else emptyList()
                    val base = target ?: CustomZone(id, name.trim().take(60))
                    val z = base.copy(
                        direct = (base.direct + addDirect).distinct().take(ZoneFile.MAX_ENTRIES),
                        server = (base.server + addServer).distinct().take(ZoneFile.MAX_ENTRIES),
                        apps = (base.apps + t?.apps.orEmpty()).distinct(),
                        listSizes = base.listSizes + sizes,
                    )
                    val n = if (t != null) t.direct.size + t.server.size + t.apps.size else count
                    onDone(z, Loaded(z.id, n, t?.skipped?.size ?: 0, t?.truncated == true))
                },
            ) { Text(stringResource(R.string.zone_import)) }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
