package app.borderless.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.borderless.ui.theme.Palette

/** Flags far from 3:2 (square or not rectangular) and what fills the tile beside them. */
private val padded: Map<String, Brush> = mapOf(
    "CH" to SolidColor(Color(0xFFFF0000)),
    "VA" to Brush.horizontalGradient(0f to Color(0xFFFFF200), 0.5f to Color(0xFFFFF200), 0.5f to Color.White, 1f to Color.White),
    "NP" to SolidColor(Color.Transparent),
)

/**
 * Flat flag in a 3:2 tile with slightly rounded corners. Countries without a flag (or not
 * detected) show a neutral tile with the code.
 */
@Composable
fun FlagIcon(code: String?, height: Dp = 16.dp, modifier: Modifier = Modifier) {
    val m = modifier.size(height * 1.5f, height).clip(RoundedCornerShape(height * 0.2f))
    val res = code?.let { FlagAssets.byCode[it.uppercase()] }
    val pad = code?.let { padded[it.uppercase()] }
    if (res != null && pad != null) {
        // Shown whole and widened with the flag's own edge colours: cropping would cut the cross.
        Image(painterResource(res), contentDescription = null, modifier = m.background(pad), contentScale = ContentScale.Fit)
    } else if (res != null) {
        Image(painterResource(res), contentDescription = null, modifier = m, contentScale = ContentScale.Crop)
    } else {
        Box(m.background(Palette.cardStrong), contentAlignment = Alignment.Center) {
            Text(code?.uppercase() ?: "?", fontSize = (height.value * 0.5f).sp, fontWeight = FontWeight.Bold, color = Palette.textSecondary)
        }
    }
}
