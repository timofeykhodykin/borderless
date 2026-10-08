package app.borderless.ui.share

import app.borderless.data.Errors
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.borderless.R
import app.borderless.data.ShareFormat
import app.borderless.ui.theme.Display
import app.borderless.ui.theme.Palette

/**
 * "Share" for a server, group, subscription or everything: copy, Android share sheet or QR code.
 * [allowKeep] offers the Border(less) format that keeps group names and hidden servers.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareSheet(title: String, bundle: ShareFormat.Bundle, allowKeep: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var keep by remember { mutableStateOf(false) }
    var qr by remember { mutableStateOf(false) }
    val text = remember(bundle, keep) { if (keep) ShareFormat.encode(bundle) else ShareFormat.plain(bundle) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Palette.background) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding()) {
            Text(stringResource(R.string.share), fontSize = 21.sp, fontWeight = FontWeight.Bold, fontFamily = Display, color = Palette.text)
            Text(title, fontSize = 14.sp, color = Palette.textSecondary)
            Spacer(Modifier.height(4.dp))
            val servers = ShareFormat.serverCount(bundle)
            val subs = ShareFormat.subCount(bundle)
            Text(
                listOfNotNull(
                    if (servers > 0) pluralStringResource(R.plurals.n_servers, servers, servers) else null,
                    if (subs > 0) pluralStringResource(R.plurals.n_subscriptions, subs, subs) else null,
                ).joinToString(" · "),
                fontSize = 13.sp, color = Palette.textMuted,
            )
            if (allowKeep) {
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.card).clickable { keep = !keep }.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.share_keep), fontSize = 16.sp, color = Palette.text)
                        Text(
                            stringResource(if (keep) R.string.share_keep_sub else R.string.share_plain_sub),
                            fontSize = 13.sp, color = Palette.textSecondary, modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    Switch(
                        checked = keep, onCheckedChange = { keep = it },
                        colors = SwitchDefaults.colors(checkedTrackColor = Palette.accent, uncheckedTrackColor = Palette.cardStrong, uncheckedBorderColor = Palette.outline, uncheckedThumbColor = Palette.textMuted),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            if (text.isBlank()) {
                Text(stringResource(R.string.share_empty), fontSize = 14.sp, color = Palette.danger, modifier = Modifier.padding(vertical = 12.dp))
            } else {
                Action(Icons.Rounded.ContentCopy, stringResource(R.string.share_copy)) {
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Border(less)", text))
                    Toast.makeText(context, context.getString(R.string.copied), Toast.LENGTH_SHORT).show()
                    onDismiss()
                }
                Action(Icons.Rounded.Share, stringResource(R.string.share_send)) {
                    val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                    Errors.guard("sharing", Unit) { context.startActivity(Intent.createChooser(intent, title)) }
                }
                Action(Icons.Rounded.QrCode2, stringResource(R.string.share_qr)) { qr = true }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (qr) {
        val bitmap = remember(text) { Qr.encode(text) }
        AlertDialog(
            onDismissRequest = { qr = false },
            containerColor = Palette.background,
            title = { Text(title) },
            text = {
                if (bitmap != null) {
                    Image(bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)))
                } else {
                    Text(stringResource(R.string.share_qr_too_big, text.length), color = Palette.textSecondary)
                }
            },
            confirmButton = { TextButton({ qr = false }) { Text(stringResource(R.string.done)) } },
        )
    }
}

@Composable
private fun Action(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Icon(icon, null, tint = Palette.textSecondary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Text(label, fontSize = 16.sp, color = Palette.text)
    }
}
