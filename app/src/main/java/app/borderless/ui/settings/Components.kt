package app.borderless.ui.settings

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import app.borderless.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.borderless.ui.theme.Display
import app.borderless.ui.theme.Palette

@Composable
fun ScreenScaffold(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().background(Palette.background).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back), tint = Palette.textSecondary)
            }
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = Display, color = Palette.text, modifier = Modifier.weight(1f))
            actions()
        }
        content()
    }
}

@Composable
fun Section(title: String, inset: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    // [inset] false: the container already has side padding (e.g. a padded list).
    Text(
        title, fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = Palette.accentStrong,
        modifier = Modifier.padding(start = if (inset) 20.dp else 4.dp, top = 24.dp, bottom = 8.dp),
    )
    Column(
        Modifier
            .padding(horizontal = if (inset) 16.dp else 0.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Palette.card),
        content = content,
    )
}

@Composable
private fun RowBase(title: String, subtitle: String?, onClick: (() -> Unit)?, trailing: @Composable () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 17.sp, color = Palette.text)
            if (!subtitle.isNullOrEmpty()) Text(subtitle, fontSize = 14.sp, color = Palette.textSecondary, modifier = Modifier.padding(top = 2.dp))
        }
        Spacer(Modifier.width(10.dp))
        trailing()
    }
}

/** [enabled] false: shown off-limits (its subtitle should say why). */
@Composable
fun SwitchRow(title: String, subtitle: String? = null, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) =
    RowBase(title, subtitle, if (enabled) ({ onChange(!checked) }) else null) {
        Switch(
            checked = checked, onCheckedChange = onChange, enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedTrackColor = Palette.accent,
                uncheckedTrackColor = Palette.cardStrong,
                uncheckedBorderColor = Palette.outline,
                uncheckedThumbColor = Palette.textMuted,
            ),
        )
    }

/** A settings category on the root page: icon, title, summary of what is set inside. */
@Composable
fun CategoryRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, summary: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 68.dp).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(38.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Palette.accentSoft), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Palette.accentStrong, modifier = Modifier.size(21.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 17.sp, color = Palette.text)
            if (summary.isNotEmpty()) Text(summary, fontSize = 13.5.sp, color = Palette.textSecondary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Palette.textMuted)
    }
}

/** A row that does something when tapped (no arrow: it doesn't open another screen). */
@Composable
fun ActionRow(title: String, subtitle: String? = null, onClick: () -> Unit) = RowBase(title, subtitle, onClick) {}

/** A row that folds text out below itself: the arrow turns down while it is open. */
@Composable
fun ExpandRow(title: String, subtitle: String? = null, open: Boolean, onClick: () -> Unit) =
    RowBase(title, subtitle, onClick) {
        val turn by androidx.compose.animation.core.animateFloatAsState(if (open) 90f else 0f, label = "arrow")
        Icon(
            Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Palette.textMuted,
            modifier = Modifier.rotate(turn),
        )
    }

@Composable
fun NavRow(title: String, subtitle: String? = null, onClick: () -> Unit) =
    RowBase(title, subtitle, onClick) {
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Palette.textMuted)
    }

@Composable
fun InfoNote(text: String) {
    Text(text, fontSize = 13.5.sp, color = Palette.textMuted, modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp))
}

@Composable
fun InfoRow(title: String, value: String) = RowBase(title, null, null) {
    Text(value, fontSize = 15.sp, color = Palette.textSecondary)
}

@Composable
fun NumberRow(
    title: String, subtitle: String? = null, value: Int, range: IntRange, suffix: String = "",
    /** How the value is shown (e.g. 600 s as "10 min"); it is still edited in the row's own unit. */
    display: ((Int) -> String)? = null,
    onChange: (Int) -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    RowBase(title, subtitle, { editing = true }) {
        Text(display?.invoke(value) ?: "$value$suffix", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Palette.accentStrong)
    }
    if (editing) {
        var text by remember { mutableStateOf(value.toString()) }
        val parsed = text.toIntOrNull()?.takeIf { it in range }
        AlertDialog(
            onDismissRequest = { editing = false },
            containerColor = Palette.background,
            title = { Text(title, fontSize = 18.sp) },
            text = {
                Column {
                    OutlinedTextField(
                        value = text, onValueChange = { text = it.filter(Char::isDigit) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        shape = RoundedCornerShape(14.dp), isError = parsed == null,
                    )
                    Text(stringResource(R.string.range, range.first, range.last), fontSize = 12.sp, color = Palette.textSecondary, modifier = Modifier.padding(top = 6.dp))
                }
            },
            confirmButton = { TextButton(enabled = parsed != null, onClick = { onChange(parsed!!); editing = false }) { Text("OK") } },
            dismissButton = { TextButton({ editing = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
fun TextRow(title: String, value: String, multiline: Boolean = false, hint: String? = null, emptyText: String? = null, onChange: (String) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    RowBase(title, value.ifEmpty { emptyText ?: stringResource(R.string.not_set) }.let { if (multiline) it.lines().take(3).joinToString(", ") else it }, { editing = true }) {}
    if (editing) {
        var text by remember { mutableStateOf(value) }
        AlertDialog(
            onDismissRequest = { editing = false },
            containerColor = Palette.background,
            title = { Text(title, fontSize = 18.sp) },
            text = {
                Column {
                    OutlinedTextField(
                        value = text, onValueChange = { text = it }, singleLine = !multiline,
                        minLines = if (multiline) 5 else 1, shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    hint?.let { Text(it, fontSize = 12.sp, color = Palette.textSecondary, modifier = Modifier.padding(top = 6.dp)) }
                }
            },
            confirmButton = { TextButton({ onChange(text.trim()); editing = false }) { Text("OK") } },
            dismissButton = { TextButton({ editing = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
fun <T> ChoiceRow(title: String, value: T, options: List<Pair<T, String>>, onChange: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    RowBase(title, options.firstOrNull { it.first == value }?.second, { open = true }) {}
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            containerColor = Palette.background,
            title = { Text(title, fontSize = 18.sp) },
            text = {
                Column {
                    options.forEach { (v, label) ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onChange(v); open = false }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = v == value, onClick = { onChange(v); open = false }, colors = RadioButtonDefaults.colors(selectedColor = Palette.accent))
                            Text(label, fontSize = 16.sp, color = Palette.text)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton({ open = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
