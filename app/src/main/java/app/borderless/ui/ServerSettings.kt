package app.borderless.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.borderless.R
import app.borderless.core.Engine
import app.borderless.core.Limited
import app.borderless.data.Countries
import app.borderless.data.LimitedPower
import app.borderless.data.Repo
import app.borderless.data.Server
import app.borderless.data.Subscription
import app.borderless.service.TunnelService
import app.borderless.ui.theme.Display
import app.borderless.ui.theme.Palette

/**
 * Everything the user sets for one server, in one dialog (the "⋮" menu and the server sheet have a single "Settings"):
 * its name (the real one; empty restores the name from the link), its country (an ISO code, a country name or a city;
 * empty gives it back to automatic detection, see [Server.countryManual]) and, for a limited-network server, how many
 * streams it keeps, whether the server's own stream limit binds, and the device id sent to it. Only what was changed
 * is saved, so opening and saving doesn't turn a detected country into a manual one or log anything.
 */
@Composable
fun ServerSettingsDialog(server: Server, onDismiss: () -> Unit) {
    val nameBefore = remember(server.id) { Repo.realName(server) }
    var name by remember { mutableStateOf(nameBefore) }
    val countryBefore = server.country.orEmpty()
    var country by remember { mutableStateOf(countryBefore) }
    val detected = remember(country) { if (country.length == 2) country.uppercase() else Countries.detect(country) }
    var auto by remember { mutableStateOf(server.limitedStreams == 0) }
    var streams by remember { mutableStateOf(server.limitedStreams.takeIf { it > 0 } ?: 4) }
    var device by remember { mutableStateOf(server.limitedDevice) }
    var ignoreLimit by remember { mutableStateOf(server.limitedIgnoreLimit) }
    AppAlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.background,
        title = { Text(stringResource(R.string.server_settings)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Heading(stringResource(R.string.settings_name), first = true)
                Field(name, { name = it }, Countries.stripFlag(server.name))

                Heading(stringResource(R.string.settings_country))
                Field(country, { country = it }, stringResource(R.string.country_hint))
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (detected != null) {
                        FlagIcon(detected, 14.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Note(
                        when {
                            country.isBlank() -> stringResource(R.string.country_auto)
                            detected != null -> Countries.name(detected)
                            else -> stringResource(R.string.country_unrecognized)
                        },
                    )
                }

                if (server.limited) LimitedSection(
                    server, auto, { auto = it }, streams, { streams = it }, ignoreLimit, { ignoreLimit = it }, device, { device = it },
                )
            }
        },
        confirmButton = {
            TextButton(enabled = detected != null || country.isBlank(), onClick = {
                if (name.trim() != nameBefore) Repo.renameServer(server.id, name)
                if (country.trim() != countryBefore) {
                    Repo.setCountry(server.id, detected, manual = country.isNotBlank())
                    // With a country filter on, the server in use may have just fallen out of the chosen countries.
                    TunnelService.send(Engine.Event.ServerCountryChanged)
                }
                if (server.limited) {
                    val n = if (auto) 0 else streams
                    if (n != server.limitedStreams || device.trim() != server.limitedDevice || ignoreLimit != server.limitedIgnoreLimit) {
                        Repo.setLimitedOptions(server.id, n, device, ignoreLimit)
                        Limited.repace()
                    }
                }
                onDismiss()
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** A subscription's settings: for now its name (the field holds the name in use, empty restores the provider's title). */
@Composable
fun SubscriptionSettingsDialog(sub: Subscription, onDismiss: () -> Unit) {
    val before = remember(sub.id) { Repo.subName(sub) }
    var name by remember { mutableStateOf(before) }
    AppAlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.background,
        title = { Text(stringResource(R.string.subscription_settings)) },
        text = {
            Column {
                Heading(stringResource(R.string.settings_name), first = true)
                Field(name, { name = it }, sub.name)
            }
        },
        confirmButton = {
            TextButton({
                if (name.trim() != before) Repo.renameSubscription(sub.id, name)
                onDismiss()
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * The limited-network part: streams (automatic — paced by the strategy, the screen and battery saving — or a fixed
 * number, with the live count), the server's limit when it has one, and the device id. Empty id: the one the app sends
 * instead (the link's or its own) is shown once, in a line that can be selected and copied to another phone.
 */
@Composable
private fun LimitedSection(
    server: Server,
    auto: Boolean, onAuto: (Boolean) -> Unit,
    streams: Int, onStreams: (Int) -> Unit,
    ignoreLimit: Boolean, onIgnoreLimit: (Boolean) -> Unit,
    device: String, onDevice: (String) -> Unit,
) {
    val live by Limited.live.collectAsStateWithLifecycle()
    val liveNow = live?.takeIf { it.serverId == server.id }
    val serverMax = remember(server.link) { Limited.maxWorkers(server) }

    Heading(stringResource(R.string.limited_streams), liveNow?.let { stringResource(R.string.limited_live_short, it.active, it.wanted) })
    // When the app uses such a server and what it costs: not obvious from the list.
    Note(stringResource(R.string.limited_about_short))
    SwitchLine(stringResource(R.string.limited_auto), auto, onAuto)
    if (!auto) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            IconButton({ if (streams > 1) onStreams(streams - 1) }) { Icon(Icons.Rounded.Remove, null, tint = Palette.text) }
            Text(stringResource(R.string.limited_n_streams, streams), fontFamily = Display, fontSize = 18.sp, color = Palette.text)
            IconButton({ if (streams < LimitedPower.MAX_STREAMS) onStreams(streams + 1) }) { Icon(Icons.Rounded.Add, null, tint = Palette.text) }
        }
    }
    Note(stringResource(if (auto) R.string.limited_auto_short else R.string.limited_manual_short))
    val shownMax = serverMax ?: liveNow?.serverLimit
    if (shownMax != null) {
        Spacer(Modifier.height(6.dp))
        SwitchLine(stringResource(R.string.limited_over_limit, shownMax), ignoreLimit, onIgnoreLimit)
        Note(stringResource(R.string.limited_over_limit_note))
    }

    Heading(stringResource(R.string.limited_device_title))
    val sentNow = remember(server) { Limited.deviceId(server.copy(limitedDevice = "")) }
    Field(device, onDevice, null)
    Spacer(Modifier.height(4.dp))
    if (device.isBlank()) SelectionContainer { Note(stringResource(R.string.limited_device_now, sentNow)) }
    Note(stringResource(R.string.limited_device_short))
}

@Composable
private fun Heading(text: String, trailing: String? = null, first: Boolean = false) =
    Row(Modifier.fillMaxWidth().padding(top = if (first) 0.dp else 18.dp, bottom = 6.dp), verticalAlignment = Alignment.Bottom) {
        Text(text, Modifier.weight(1f), color = Palette.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        trailing?.let { Text(it, color = Palette.accentStrong, fontSize = 13.sp) }
    }

@Composable
private fun Note(text: String) = Text(text, color = Palette.textSecondary, fontSize = 13.sp, lineHeight = 17.sp)

@Composable
private fun SwitchLine(text: String, checked: Boolean, onChange: (Boolean) -> Unit) =
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onChange(!checked) }) {
        Text(text, Modifier.weight(1f), color = Palette.text, fontSize = 15.sp)
        Switch(checked = checked, onCheckedChange = onChange)
    }

/** A one-line field in the text colour (what the user typed must not look disabled); [placeholder] is grey. */
@Composable
private fun Field(value: String, onChange: (String) -> Unit, placeholder: String?) = OutlinedTextField(
    value = value, onValueChange = onChange, singleLine = true,
    placeholder = placeholder?.let { { Text(it, color = Palette.textMuted, maxLines = 1) } },
    shape = RoundedCornerShape(14.dp),
    modifier = Modifier.fillMaxWidth(),
    textStyle = TextStyle(fontSize = 15.sp, color = Palette.text),
    colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Palette.outline, focusedBorderColor = Palette.accent),
)
