package app.borderless.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.borderless.R
import app.borderless.core.Engine
import app.borderless.data.Countries
import app.borderless.data.Repo
import app.borderless.data.Server
import app.borderless.service.TunnelService
import app.borderless.ui.theme.Palette

/**
 * Sets the server's country by hand: an ISO code, a country name or a city ("DE", "Germany", "Frankfurt…").
 * The override survives subscription updates ([Server.countryManual]); an empty field gives the server back to
 * automatic detection. Setting it tells the engine, since a country filter may no longer cover the server in use.
 */
@Composable
fun CountryDialog(server: Server, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf(server.country.orEmpty()) }
    val detected = remember(value) { if (value.length == 2) value.uppercase() else Countries.detect(value) }
    AppAlertDialog(
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
                // With a country filter on, the server in use may have just fallen out of the chosen countries.
                TunnelService.send(Engine.Event.ServerCountryChanged)
                onDismiss()
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}