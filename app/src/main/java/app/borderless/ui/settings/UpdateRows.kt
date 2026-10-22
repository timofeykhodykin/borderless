package app.borderless.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.borderless.R
import app.borderless.core.Updater
import app.borderless.data.launchSafe
import app.borderless.ui.theme.Display
import app.borderless.ui.theme.Palette

/** At the top of the settings: a newer version is out. "Update" updates; the cross hides it (the About row stays). */
@Composable
internal fun UpdateOffer() {
    val release by Updater.available.collectAsStateWithLifecycle()
    val dismissed by Updater.dismissed.collectAsStateWithLifecycle()
    val state by Updater.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val r = release
    AnimatedVisibility(r != null && r.code != dismissed) {
        if (r == null) return@AnimatedVisibility
        Row(
            Modifier
                .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 4.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Palette.selected)
                .border(1.dp, Palette.accent, RoundedCornerShape(20.dp))
                .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.SystemUpdate, null, tint = Palette.accentStrong, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(stringResource(R.string.update_available, r.version), fontSize = 17.sp, fontWeight = FontWeight.Bold, fontFamily = Display, color = Palette.text)
                Text(stateText(state) ?: stringResource(R.string.update_offer_sub), fontSize = 13.5.sp, color = Palette.textSecondary)
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.update_now), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Palette.onAccent,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(Palette.accent)
                        .clickable(enabled = state !is Updater.State.Downloading) { scope.launchSafe("updating the app") { Updater.update(context) } }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            IconButton({ Updater.dismiss() }) { Icon(Icons.Rounded.Close, stringResource(R.string.update_dismiss), tint = Palette.textSecondary) }
        }
    }
}

/** In About: the update when there is one, else a check (with when the last one was). */
@Composable
internal fun UpdateRow() {
    val release by Updater.available.collectAsStateWithLifecycle()
    val state by Updater.state.collectAsStateWithLifecycle()
    val checkedAt by Updater.checkedAt.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val r = release
    val checked = if (checkedAt > 0) android.text.format.DateUtils.getRelativeTimeSpanString(checkedAt).toString().lowercase() else null
    if (r != null) {
        ActionRow(stringResource(R.string.update_to, r.version), stateText(state) ?: stringResource(R.string.update_offer_sub)) {
            if (state !is Updater.State.Downloading) scope.launchSafe("updating the app") { Updater.update(context) }
        }
    } else {
        ActionRow(
            stringResource(R.string.update_check),
            stateText(state) ?: if (checked != null) stringResource(R.string.update_latest, checked) else null,
        ) { if (state != Updater.State.Checking) scope.launchSafe("checking for updates") { Updater.checkIfDue(force = true) } }
    }
}

/** In About: whether new versions install by themselves or are offered once in a notification. */
@Composable
internal fun AutoUpdateRow() {
    val s by app.borderless.data.Repo.settings.collectAsStateWithLifecycle()
    SwitchRow(stringResource(R.string.set_auto_update), stringResource(R.string.set_auto_update_sub), s.autoUpdate) { on ->
        app.borderless.data.Repo.updateSettings { it.copy(autoUpdate = on) }
    }
}

@Composable
private fun stateText(s: Updater.State): String? = when (s) {
    Updater.State.Idle -> null
    Updater.State.Checking -> stringResource(R.string.update_checking)
    is Updater.State.Downloading -> stringResource(R.string.update_downloading, s.percent)
    Updater.State.Ready -> stringResource(R.string.update_ready)
    Updater.State.Waiting -> stringResource(R.string.update_waiting)
    is Updater.State.Failed -> stringResource(R.string.update_failed, s.reason)
}
