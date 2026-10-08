package app.borderless.ui.main

import androidx.compose.material.icons.rounded.Folder
import app.borderless.data.launchSafe
import app.borderless.data.Errors
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import app.borderless.ui.share.Qr
import com.journeyapps.barcodescanner.ScanOptions
import com.journeyapps.barcodescanner.ScanContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.QrCodeScanner
import app.borderless.ui.share.ShareSheet
import androidx.compose.material.icons.rounded.Share
import app.borderless.ui.FlagIcon
import android.content.ClipData
import app.borderless.R
import androidx.compose.ui.res.stringResource
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.NetworkPing
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.borderless.core.Scanner
import app.borderless.data.Countries
import app.borderless.data.Group
import app.borderless.data.Importer
import app.borderless.data.PingRecord
import app.borderless.data.Repo
import app.borderless.data.Server
import app.borderless.ui.settings.NameDialog
import app.borderless.ui.theme.Display
import app.borderless.ui.theme.Palette
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddSheet(initial: String = "", onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf(initial) }
    var busy by remember { mutableStateOf(false) }
    val groups by Repo.groups.collectAsStateWithLifecycle()
    // Null = automatic: links to the default group, each subscription to a group of its own.
    var target by remember { mutableStateOf<String?>(null) }
    var naming by remember { mutableStateOf(false) }
    fun append(more: String) { text = if (text.isBlank()) more else text.trimEnd() + "\n" + more }
    val notFound = stringResource(R.string.qr_not_found)
    val scanQr = rememberLauncherForActivityResult(ScanContract()) { r -> r.contents?.let(::append) }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launchSafe("reading a QR code from an image") {
            val decoded = withContext(Dispatchers.IO) { Qr.decode(context, uri) }
            if (decoded != null) append(decoded) else Toast.makeText(context, notFound, Toast.LENGTH_SHORT).show()
        }
    }
    // The last import: what was added and the problems, explained; [importedText] is what it was run on.
    var result by remember { mutableStateOf<Importer.Result?>(null) }
    var importedText by remember { mutableStateOf<String?>(null) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Palette.background) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().imePadding()) {
            Text(stringResource(R.string.add_title), fontSize = 21.sp, fontWeight = FontWeight.Bold, fontFamily = Display, color = Palette.text)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.add_hint),
                fontSize = 13.sp, color = Palette.textSecondary,
            )
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 260.dp),
                placeholder = { Text(stringResource(R.string.add_placeholder), color = Palette.textMuted) },
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = Palette.outline,
                    focusedBorderColor = Palette.accent,
                    unfocusedContainerColor = Palette.card,
                    focusedContainerColor = Palette.background,
                ),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = Palette.text),
            )
            result?.let { r ->
                Spacer(Modifier.height(10.dp))
                ImportReport(r)
            }
            Spacer(Modifier.height(14.dp))
            Text(stringResource(R.string.add_to_group), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Palette.textSecondary)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GroupChip(stringResource(R.string.add_group_auto), target == null) { target = null }
                groups.forEach { g -> GroupChip(Repo.groupName(g), g.id == target) { target = g.id } }
                GroupChip("+ " + stringResource(R.string.add_group_new), false) { naming = true }
            }
            if (target == null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.add_group_auto_hint, Repo.groupName(Repo.group(Group.DEFAULT_ID))),
                    fontSize = 12.5.sp, color = Palette.textMuted,
                )
            }
            Spacer(Modifier.height(16.dp))
            // Ways to get text in: clipboard, camera, a picture with a QR code.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InputAction(Icons.Rounded.ContentPaste, stringResource(R.string.paste), Modifier.weight(1f)) {
                    val clip = context.getSystemService(ClipboardManager::class.java).primaryClip
                    val pasted = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
                    if (!pasted.isNullOrBlank()) append(pasted)
                }
                InputAction(Icons.Rounded.QrCodeScanner, stringResource(R.string.scan_qr), Modifier.weight(1f)) {
                    Errors.guard("opening the QR scanner", Unit) { scanQr.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt(context.getString(R.string.scan_prompt)).setBeepEnabled(false).setOrientationLocked(false)) }
                }
                InputAction(Icons.Rounded.Image, stringResource(R.string.qr_from_image), Modifier.weight(1f)) {
                    Errors.guard("choosing an image", Unit) { pickImage.launch("image/*") }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row {
                val done = importedText == text && (result?.let { it.servers + it.subscriptions > 0 } == true)
                Button(
                    onClick = {
                        if (done) { onDismiss(); return@Button }
                        busy = true
                        scope.launchSafe("adding servers", finally = { busy = false }) {
                            val r = Importer.importText(text, target)
                            busy = false
                            if (r.servers + r.subscriptions > 0) Scanner.start()
                            if (r.errors.isEmpty() && r.servers + r.subscriptions > 0) {
                                Toast.makeText(context, r.message, Toast.LENGTH_LONG).show()
                                onDismiss()
                            } else {
                                // Problems stay on screen until read; what worked is already added.
                                result = r
                                importedText = text
                            }
                        }
                    },
                    enabled = text.isNotBlank() && !busy,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                    // Disabled (nothing pasted yet, or adding) stays a paler accent with white text, not Material's grey.
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Palette.accent, contentColor = androidx.compose.ui.graphics.Color.White,
                        disabledContainerColor = Palette.bar, disabledContentColor = androidx.compose.ui.graphics.Color.White,
                    ),
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = androidx.compose.ui.graphics.Color.White, strokeWidth = 2.dp)
                    // Explicit: the theme's body style carries a text colour that would win over contentColor.
                    else Text(stringResource(if (done) R.string.done else R.string.add), color = androidx.compose.ui.graphics.Color.White)
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
    if (naming) {
        NameDialog(stringResource(R.string.group_new), "", onDismiss = { naming = false }) { name ->
            target = Repo.createGroup(name).id
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerSheet(server: Server, ping: PingRecord?, onConnect: () -> Unit, onStats: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var askCountry by remember { mutableStateOf(false) }
    var moveMenu by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }
    val otherGroups = remember(server) {
        Repo.groups.value.filter { it.id != Repo.groupOf(server) }
    }
    var confirmDelete by remember { mutableStateOf(false) }
    var probing by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Palette.background) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(46.dp).clip(RoundedCornerShape(13.dp)).background(if (ping == null) Palette.card else Palette.ping(ping.ms)),
                    contentAlignment = Alignment.Center,
                ) { FlagIcon(server.country, 20.dp) }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(Repo.displayName(server), fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = Display, color = Palette.text)
                    // With generic names on, the real name is still shown here.
                    if (Repo.displayName(server) != Repo.realName(server)) {
                        Text(Repo.realName(server), fontSize = 13.sp, color = Palette.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(
                        "${Countries.name(server.country)} · ${Repo.groupName(Repo.group(Repo.groupOf(server)))}",
                        fontSize = 13.sp, color = Palette.textSecondary,
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            InfoLine(stringResource(R.string.info_protocol), server.protocol)
            InfoLine(stringResource(R.string.info_address), "${server.host}:${server.port}")
            InfoLine(
                stringResource(R.string.info_ping), when {
                    ping == null -> stringResource(R.string.ping_never)
                    ping.ok -> stringResource(R.string.ms, ping.ms!!)
                    else -> stringResource(R.string.ping_unavailable)
                }
            )
            Spacer(Modifier.height(10.dp))
            SheetAction(Icons.Rounded.PowerSettingsNew, stringResource(R.string.action_connect)) { onConnect(); onDismiss() }
            SheetAction(Icons.Rounded.NetworkPing, stringResource(if (probing) R.string.action_probing else R.string.action_probe)) {
                if (!probing) {
                    probing = true
                    scope.launchSafe("checking a server", finally = { probing = false }) { Scanner.probeOne(server) }
                }
            }
            SheetAction(Icons.Rounded.Insights, stringResource(R.string.stats)) { onStats(); onDismiss() }
            SheetAction(Icons.Rounded.Edit, stringResource(R.string.action_rename)) { renaming = true }
            SheetAction(Icons.Rounded.Flag, stringResource(R.string.action_country)) { askCountry = true }
            // Subscription servers always stay in their subscription's group.
            if (server.subscriptionId == null && otherGroups.isNotEmpty()) Box {
                SheetAction(Icons.AutoMirrored.Rounded.DriveFileMove, stringResource(R.string.action_move_group)) { moveMenu = true }
                app.borderless.ui.AppMenu(moveMenu, { moveMenu = false }) {
                    otherGroups.forEach { g ->
                        app.borderless.ui.AppMenuItem(Repo.groupName(g), Icons.Rounded.Folder) { moveMenu = false; Repo.setServerGroup(server.id, g.id) }
                    }
                }
            }
            SheetAction(Icons.Rounded.Share, stringResource(R.string.share)) { sharing = true }
            if (server.subscriptionId == null) {
                SheetAction(Icons.Rounded.DeleteOutline, stringResource(R.string.delete), danger = true) { confirmDelete = true }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (askCountry) CountryDialog(server, onDismiss = { askCountry = false })
    if (renaming) ServerRenameDialog(server) { renaming = false }
    if (sharing) ShareSheet(Repo.realName(server), Repo.shareServer(server.id), allowKeep = false) { sharing = false }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            confirmButton = { TextButton({ Repo.removeServer(server.id); confirmDelete = false; onDismiss() }) { Text(stringResource(R.string.delete), color = Palette.danger) } },
            dismissButton = { TextButton({ confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
            title = { Text(stringResource(R.string.delete_server_q)) },
            text = { Text(server.name) },
            containerColor = Palette.background,
        )
    }
}

@Composable
private fun InputAction(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Palette.card)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = Palette.textSecondary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, fontSize = 13.sp, color = Palette.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun GroupChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 14.sp, fontWeight = FontWeight.Bold,
        color = if (selected) Palette.accentStrong else Palette.text,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) Palette.selected else Palette.card)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    )
}

@Composable
private fun CountryDialog(server: Server, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf(server.country.orEmpty()) }
    val detected = remember(value) { if (value.length == 2) value.uppercase() else Countries.detect(value) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.background,
        title = { Text(stringResource(R.string.country_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = value, onValueChange = { value = it }, singleLine = true,
                    placeholder = { Text(stringResource(R.string.country_hint)) },
                    shape = RoundedCornerShape(14.dp),
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (detected != null) {
                        FlagIcon(detected, 14.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        if (detected != null) Countries.name(detected) else stringResource(R.string.country_unrecognized),
                        color = Palette.textSecondary, fontSize = 13.sp,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = detected != null || value.isBlank(), onClick = {
                Repo.setCountry(server.id, detected, manual = value.isNotBlank())
                onDismiss()
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, fontSize = 13.sp, color = Palette.textSecondary, modifier = Modifier.width(90.dp))
        Text(value, fontSize = 13.sp, color = Palette.text)
    }
}

@Composable
private fun SheetAction(icon: ImageVector, label: String, danger: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (danger) Palette.danger else Palette.textSecondary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Text(label, fontSize = 15.sp, color = if (danger) Palette.danger else Palette.text)
    }
}

fun Context.toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

/** Rename a server; the field holds its real name (not the generic one), empty restores the name from the link. */
@Composable
fun ServerRenameDialog(server: Server, onDismiss: () -> Unit) {
    NameDialog(
        stringResource(R.string.action_rename), Repo.realName(server),
        placeholder = Countries.stripFlag(server.name), allowEmpty = true,
        onDismiss = onDismiss,
    ) { Repo.renameServer(server.id, it) }
}

/**
 * What an import did: the line of what was added, then every problem with what it was about (a short
 * excerpt), a plain explanation and, small, the technical detail.
 */
@Composable
private fun ImportReport(r: Importer.Result) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.card).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(r.message, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Palette.text)
        if (r.errors.isNotEmpty()) {
            Text(stringResource(R.string.import_problems, r.errors.size), fontSize = 13.sp, color = Palette.textSecondary)
            Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // The same problem with many links is shown once, with how many.
                r.errors.groupBy { it.text }.forEach { (text, issues) ->
                    Row {
                        Box(Modifier.padding(top = 6.dp).size(6.dp).clip(CircleShape).background(Palette.danger))
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                issues.first().item + if (issues.size > 1) " " + stringResource(R.string.import_and_more, issues.size - 1) else "",
                                fontSize = 12.sp, color = Palette.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Text(text, fontSize = 13.sp, color = Palette.text)
                            issues.first().detail?.let { Text(it, fontSize = 11.sp, color = Palette.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                        }
                    }
                }
            }
        }
    }
}
