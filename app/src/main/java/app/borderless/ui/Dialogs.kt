package app.borderless.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.borderless.ui.theme.Body
import app.borderless.ui.theme.Display
import app.borderless.ui.theme.Palette

/**
 * The app's own dialog (instead of the plain Material look), like [AppMenu]: a rounded card on the background colour
 * with a hairline border and a soft shadow, the title in the display font, the text in the body font, buttons at the
 * bottom right. Takes what Material's AlertDialog takes, so it replaces it as is ([containerColor] is accepted and
 * ignored: the card is always the background colour).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    @Suppress("UNUSED_PARAMETER") containerColor: Color = Palette.background,
    /** Deleting / clearing something: a line under the text says it can't be undone. */
    irreversible: Boolean = false,
    /** Content that needs the room (a calendar): nearly the screen's width, narrow side padding. */
    wide: Boolean = false,
) {
    val shape = RoundedCornerShape(24.dp)
    val side = if (wide) 10.dp else 22.dp
    // The dialog's window doesn't shrink for the keyboard by itself (it only pans, which hid the buttons of a tall
    // dialog): it draws behind the system bars and the keyboard, and the card keeps clear of them, so its text part
    // gets the height that is really left and scrolls.
    BasicAlertDialog(
        onDismissRequest,
        (if (wide) modifier.padding(horizontal = 12.dp).widthIn(max = 420.dp) else modifier)
            .windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.safeDrawing).padding(vertical = 12.dp),
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = !wide, decorFitsSystemWindows = false),
    ) {
        Column(
            Modifier
                .shadow(8.dp, shape)
                .clip(shape)
                .background(Palette.background)
                .border(1.dp, Palette.outline, shape)
                .padding(start = side, end = side + if (wide) 8.dp else 0.dp, top = 22.dp, bottom = 12.dp),
        ) {
            if (title != null) {
                ProvideTextStyle(TextStyle(fontFamily = Display, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Palette.text)) {
                    androidx.compose.foundation.layout.Box(Modifier.padding(start = 22.dp - side)) { title() }
                }
                Spacer(Modifier.height(12.dp))
            }
            if (text != null) {
                // The text takes what is left between the title and the buttons (a scrolling text keeps the buttons on
                // screen, also above the keyboard). Grey comes as the content colour, not in the text style: a text
                // field takes the style's colour, and a value the user typed must not look disabled.
                androidx.compose.foundation.layout.Box(Modifier.weight(1f, fill = false)) {
                    androidx.compose.runtime.CompositionLocalProvider(
                        androidx.compose.material3.LocalTextStyle provides androidx.compose.material3.LocalTextStyle.current
                            .merge(TextStyle(fontFamily = Body, fontSize = 15.sp, lineHeight = 21.sp)).copy(color = Color.Unspecified),
                        androidx.compose.material3.LocalContentColor provides Palette.textSecondary,
                    ) { text() }
                }
            }
            if (irreversible) {
                if (text != null) Spacer(Modifier.height(8.dp))
                androidx.compose.material3.Text(
                    androidx.compose.ui.res.stringResource(app.borderless.R.string.cannot_undo),
                    fontFamily = Body, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Palette.danger,
                )
            }
            Spacer(Modifier.height(10.dp))
            // Button labels: the confirming one in the accent, the dismissing one quiet (the theme's label style carries
            // a text colour that would win over the buttons' own content colour); a label given its own colour (a red
            // "Delete") keeps it.
            val type = androidx.compose.material3.MaterialTheme.typography
            @Composable
            fun Labels(color: Color, content: @Composable () -> Unit) = androidx.compose.material3.MaterialTheme(
                typography = type.copy(labelLarge = type.labelLarge.copy(color = color, fontFamily = Body, fontWeight = FontWeight.Bold, fontSize = 15.sp)),
                content = content,
            )
            Row(Modifier.fillMaxWidth().offset(x = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp, androidx.compose.ui.Alignment.End)) {
                if (dismissButton != null) Labels(Palette.textSecondary, dismissButton)
                Labels(Palette.accentStrong, confirmButton)
            }
        }
    }
}
