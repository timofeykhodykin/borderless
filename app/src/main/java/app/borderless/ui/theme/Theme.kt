package app.borderless.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.borderless.R
import kotlin.math.ln
import kotlin.math.pow

/** One colour scheme of the interface (the latency scale is shared by all of them). */
data class Scheme(
    val id: String,
    val background: Color,
    val card: Color,
    val cardStrong: Color,
    val outline: Color,
    val text: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val accent: Color,
    val accentStrong: Color,
    val accentContainer: Color,
    val accentSoft: Color,
    val peach: Color,
    val butter: Color,
    val rose: Color,
    val danger: Color,
    val warning: Color,
    val warningStrong: Color,
    val warningIcon: Color,
    val unavailable: Color,
    val unavailableMark: Color,
) {
    /** Fill of progress and share bars: the accent softened towards the background, by the same rule in every scheme. */
    val bar: Color get() = lerp(accent, background, 0.4f)

    /**
     * Fill of a selected chip or tab sitting on [card]. Derived from the accent by one rule for every
     * scheme, far enough from [card] to be read at a glance even where the accent is a grey.
     */
    val selected: Color get() = lerp(accent, background, 0.82f)

    /** Colours shown in the settings preview strip. */
    val preview: List<Color> get() = listOf(background, card, accentSoft, accentContainer, accent, accentStrong, textSecondary, text, warning)
}

object Schemes {
    /** Warm neutrals with a coral accent: the original look. */
    val CORAL = Scheme(
        id = "coral",
        background = Color(0xFFFFFFFF), card = Color(0xFFF8F4F2), cardStrong = Color(0xFFF0E9E6), outline = Color(0xFFEBE3DF),
        text = Color(0xFF2B2422), textSecondary = Color(0xFF8C807B), textMuted = Color(0xFFBDB2AD),
        accent = Color(0xFFE0654D), accentStrong = Color(0xFFB9472F), accentContainer = Color(0xFFF8D5CA), accentSoft = Color(0xFFFCE9E3),
        peach = Color(0xFFFFD9C7), butter = Color(0xFFFFE6A8), rose = Color(0xFFFFC9C3), danger = Color(0xFFD45545),
        warning = Color(0xFFFFD98F), warningStrong = Color(0xFFE9B45A), warningIcon = Color(0xFF9C6B2F),
        unavailable = Color(0xFFF1EBE8), unavailableMark = Color(0xFFC9BEB9),
    )

    /** Black and white: neutral greys, a near-black accent; only the latency scale and errors keep colour. */
    val MONO = Scheme(
        id = "mono",
        background = Color(0xFFFFFFFF), card = Color(0xFFF5F5F5), cardStrong = Color(0xFFEAEAEA), outline = Color(0xFFE2E2E2),
        text = Color(0xFF1C1C1C), textSecondary = Color(0xFF7A7A7A), textMuted = Color(0xFFB4B4B4),
        accent = Color(0xFF2E2E2E), accentStrong = Color(0xFF000000), accentContainer = Color(0xFFDCDCDC), accentSoft = Color(0xFFEFEFEF),
        peach = Color(0xFFE2E2E2), butter = Color(0xFFEDEDED), rose = Color(0xFFD9D9D9), danger = Color(0xFFB3261E),
        warning = Color(0xFFDADADA), warningStrong = Color(0xFFBDBDBD), warningIcon = Color(0xFF4A4A4A),
        unavailable = Color(0xFFF0F0F0), unavailableMark = Color(0xFFC4C4C4),
    )

    val all = listOf(CORAL, MONO)

    fun byId(id: String?): Scheme = all.firstOrNull { it.id == id } ?: CORAL
}

/**
 * All colours of the app, read from the selected [Scheme] (Compose state: switching repaints
 * everything at once). Restyling means editing [Schemes].
 */
object Palette {
    var scheme by mutableStateOf(Schemes.CORAL)

    val background get() = scheme.background
    val card get() = scheme.card
    val cardStrong get() = scheme.cardStrong
    val outline get() = scheme.outline
    val text get() = scheme.text
    val textSecondary get() = scheme.textSecondary
    val textMuted get() = scheme.textMuted

    /** Accent (coral in the default scheme). */
    val accent get() = scheme.accent
    /** Outline of the "on" button, pressed states, accent text. */
    val accentStrong get() = scheme.accentStrong
    /** Searching state, pressed chips. */
    val accentContainer get() = scheme.accentContainer
    /** Selected rows, hints, icon tiles. */
    val accentSoft get() = scheme.accentSoft
    /** Selected chip or tab on a [card] background (see [Scheme.selected]). */
    val selected get() = scheme.selected
    val peach get() = scheme.peach
    val butter get() = scheme.butter
    val rose get() = scheme.rose
    val danger get() = scheme.danger
    val bar get() = scheme.bar

    /** Button when traffic goes direct / is paused. */
    val warning get() = scheme.warning
    val warningStrong get() = scheme.warningStrong
    val warningIcon get() = scheme.warningIcon

    val unavailable get() = scheme.unavailable
    val unavailableMark get() = scheme.unavailableMark
    val untested get() = scheme.background
    val currentOutline get() = scheme.accentStrong

    /** Latency range of the colour scale; anything outside is clamped to its ends. */
    const val PING_FAST = 50
    const val PING_SLOW = 3000

    /** Pastel scale from fast (green) to slow (red). */
    private val pingColors = listOf(
        Color(0xFFA9DFA9),
        Color(0xFFC2E7A7),
        Color(0xFFDCECA9),
        Color(0xFFFFE3A3),
        Color(0xFFFFC9A5),
        Color(0xFFFFAEAE),
    )

    /**
     * Strongly logarithmic: position on a log scale, then bent once more (power < 1), so small
     * latencies spread over most of the colours and everything slow ends up near red.
     */
    fun ping(ms: Int?): Color {
        if (ms == null) return unavailable
        val t = ((ln(ms.coerceAtLeast(1).toFloat()) - ln(PING_FAST.toFloat())) /
            (ln(PING_SLOW.toFloat()) - ln(PING_FAST.toFloat()))).coerceIn(0f, 1f).pow(0.65f)
        val pos = t * (pingColors.size - 1)
        val i = pos.toInt().coerceAtMost(pingColors.size - 2)
        return lerp(pingColors[i], pingColors[i + 1], pos - i)
    }

    /** Colours for a legend bar of the scale. */
    val pingLegend: List<Color> get() = pingColors

    /** A deeper shade of the latency colour, readable as text on white. */
    fun pingText(ms: Int?): Color = if (ms == null) textMuted else lerp(ping(ms), text, 0.55f)
}

/** PT Serif (ParaType, SIL OFL): titles, numbers, the app name. */
val Display = FontFamily(
    Font(R.font.pt_serif_regular, FontWeight.Normal),
    Font(R.font.pt_serif_bold, FontWeight.Bold),
    Font(R.font.pt_serif_italic, FontWeight.Normal, FontStyle.Italic),
    Font(R.font.pt_serif_bold_italic, FontWeight.Bold, FontStyle.Italic),
)

/** PT Sans (ParaType, SIL OFL): all running text. */
val Body = FontFamily(
    Font(R.font.pt_sans_regular, FontWeight.Normal),
    Font(R.font.pt_sans_bold, FontWeight.Bold),
    Font(R.font.pt_sans_italic, FontWeight.Normal, FontStyle.Italic),
)

private fun colorsOf(p: Scheme) = lightColorScheme(
    primary = p.accent,
    onPrimary = Color.White,
    primaryContainer = p.accentSoft,
    onPrimaryContainer = p.text,
    secondary = p.peach,
    background = p.background,
    onBackground = p.text,
    surface = p.background,
    onSurface = p.text,
    surfaceVariant = p.card,
    onSurfaceVariant = p.textSecondary,
    surfaceContainer = p.card,
    surfaceContainerLow = p.background,
    surfaceContainerHigh = p.background,
    surfaceContainerHighest = p.cardStrong,
    outline = p.outline,
    outlineVariant = p.outline,
    error = p.danger,
)

/** PT Sans is compact vertically; a 1.35 line height keeps two-line rows from feeling cramped. */
private fun TextStyle.m() = copy(fontFamily = Body, lineHeight = 1.35.em)

private fun typographyOf(p: Scheme) = Typography().let { d ->
    Typography(
        displayLarge = d.displayLarge.m(),
        displayMedium = d.displayMedium.m(),
        displaySmall = d.displaySmall.m(),
        headlineLarge = d.headlineLarge.m(),
        headlineMedium = d.headlineMedium.m(),
        headlineSmall = d.headlineSmall.m(),
        titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = p.text).m(),
        titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = p.text).m(),
        titleSmall = d.titleSmall.m(),
        bodyLarge = TextStyle(fontSize = 15.sp, color = p.text).m(),
        bodyMedium = TextStyle(fontSize = 14.sp, color = p.text).m(),
        bodySmall = TextStyle(fontSize = 12.5.sp, color = p.textSecondary).m(),
        labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold).m(),
        labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = p.textSecondary).m(),
        labelSmall = d.labelSmall.m(),
    )
}

@Composable
fun BorderlessTheme(content: @Composable () -> Unit) {
    val scheme = Palette.scheme
    val colors = remember(scheme) { colorsOf(scheme) }
    val typography = remember(scheme) { typographyOf(scheme) }
    MaterialTheme(colorScheme = colors, typography = typography, content = content)
}
