package app.borderless.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.borderless.ui.theme.Palette

/**
 * The app's own popup menu (instead of the plain Material look): rounded card on the background colour
 * with a hairline border and a soft shadow; items via [AppMenuItem].
 */
@Composable
fun AppMenu(expanded: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(18.dp),
        containerColor = Palette.background,
        shadowElevation = 6.dp,
        tonalElevation = 0.dp,
        border = BorderStroke(1.dp, Palette.outline),
        modifier = Modifier.widthIn(min = 210.dp).padding(vertical = 2.dp),
    ) {
        // Every item as wide as the widest one: the selection fills the row, trailing marks line up on the right.
        Column(Modifier.width(IntrinsicSize.Max).padding(horizontal = 6.dp), content = content)
    }
}

/**
 * One menu entry: an icon on a soft tinted square, the label and, optionally, a second line;
 * [danger] paints it in the danger colour; [trailing] e.g. an arrow to a second level.
 */
@Composable
fun AppMenuItem(
    label: String,
    icon: ImageVector? = null,
    sub: String? = null,
    danger: Boolean = false,
    selected: Boolean = false,
    trailing: ImageVector? = null,
    onClick: () -> Unit,
) {
    val tint = if (danger) Palette.danger else Palette.accentStrong
    Row(
        Modifier
            .fillMaxWidth()
            .widthIn(min = 198.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Palette.accentSoft else Palette.background)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(
                Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(if (danger) Palette.rose.copy(alpha = 0.18f) else Palette.accentSoft),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp)) }
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f).widthIn(max = 240.dp)) {
            Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (danger) Palette.danger else Palette.text)
            if (sub != null) Text(sub, fontSize = 12.5.sp, color = Palette.textSecondary)
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            Icon(trailing, null, tint = Palette.textMuted, modifier = Modifier.size(18.dp))
        }
    }
}
