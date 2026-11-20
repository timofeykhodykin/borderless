package app.borderless.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
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

/**
 * One colour scheme of the interface. Every palette has a light and a dark scheme (same [id], [dark] tells them
 * apart). The connection's state colours, the latency scale and the chart shades come with the scheme: the light
 * schemes share one set, the dark ones another, and a palette may nudge a state whose hue would come too close to
 * its accent ([StateSet.copy]).
 */
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
    val dark: Boolean = false,
    /** Text and icons drawn on [accent]: white on the light schemes, the background on the dark ones. */
    val onAccent: Color = Color.White,
    val states: StateSet = StateSet.LIGHT,
    /** The latency scale from fast to slow (see [Palette.ping]). */
    val ping: List<Color> = PING_LIGHT,
    val chart: ChartColors = ChartColors.LIGHT,
) {
    /** Fill of progress and share bars: the accent softened towards the background, by the same rule in every scheme. */
    val bar: Color get() = lerp(accent, background, 0.4f)

    /**
     * Fill of a selected chip or tab sitting on [card]. Derived from the accent by one rule for every
     * scheme, far enough from [card] to be read at a glance even where the accent is a grey.
     */
    val selected: Color get() = lerp(accent, background, 0.82f)

    /**
     * Colours shown in the settings preview strip: only what tells the palettes apart (the near-black text and the
     * shared state colours look the same in all of them).
     */
    val preview: List<Color> get() = listOf(background, card, accentSoft, accentContainer, accent, accentStrong, textSecondary)
}

/** Pastel latency scale of the light schemes, from fast (green) to slow (red). */
private val PING_LIGHT = listOf(
    Color(0xFFA9DFA9), Color(0xFFC2E7A7), Color(0xFFDCECA9), Color(0xFFFFE3A3), Color(0xFFFFC9A5), Color(0xFFFFAEAE),
)

/** The same hues, lifted out of the mud for a dark background: darker than the light pastels, lighter than the fills. */
private val PING_DARK = listOf(
    Color(0xFF5F9661), Color(0xFF709353), Color(0xFF808E4B), Color(0xFF9E8240), Color(0xFFAD7955), Color(0xFFBB6C6E),
)

/**
 * The connection's states other than "through a server" and "connecting" (those follow the accent): each has a hue
 * of its own, so the button, the state timeline and the chart bands never mix two states up. Limited network is
 * yellow, and so are the limited-network servers made for it. [off] = the chart band of the tunnel being off.
 */
data class StateSet(
    /** Traffic without the tunnel: no server works (or none to use). */
    val direct: StateColors.Tone,
    /** Limited network (only some sites open), and the limited-network servers. */
    val limited: StateColors.Tone,
    /** No internet at all. */
    val noNetwork: StateColors.Tone,
    /** Traffic paused (no server and the user chose to pause): a wine red, darker and bluer than the coral accent. */
    val paused: StateColors.Tone,
    val off: Color,
) {
    companion object {
        val LIGHT = StateSet(
            direct = StateColors.Tone(Color(0xFFFFC58A), Color(0xFFE8873A), Color(0xFF8A4710), band = Color(0xFFFFD8C2)),
            limited = StateColors.Tone(Color(0xFFFFD98F), Color(0xFFE9B45A), Color(0xFF9C6B2F), band = Color(0xFFFFEBC5)),
            noNetwork = StateColors.Tone(Color(0xFFDCE3EE), Color(0xFF8DA0BE), Color(0xFF4B5E7C), band = Color(0xFFDCE3EE)),
            paused = StateColors.Tone(Color(0xFFF7CBD3), Color(0xFFB0304A), Color(0xFF962A40), band = Color(0xFFF9E0E5)),
            off = Color(0xFFE7DFDB),
        )

        /** Same hues on a dark background: deep fills, bright rings, muted bands. */
        val DARK = StateSet(
            direct = StateColors.Tone(Color(0xFF794722), Color(0xFFFFA15C), Color(0xFFFFD7AD), band = Color(0xFF4F392A)),
            limited = StateColors.Tone(Color(0xFF775C25), Color(0xFFF5C66D), Color(0xFFFFE8B0), band = Color(0xFF4C3F28)),
            noNetwork = StateColors.Tone(Color(0xFF414B5B), Color(0xFF9BAFCE), Color(0xFFD4E1F4), band = Color(0xFF363C47)),
            paused = StateColors.Tone(Color(0xFF793942), Color(0xFFEF7688), Color(0xFFFFD7DC), band = Color(0xFF4F3336)),
            off = Color(0xFF3D3836),
        )
    }
}

/** Shades of the phone's own states on the activity charts (teal saver: the warm hues belong to the connection). */
class ChartColors(val charging: Color, val screenOff: Color, val saver: Color, val metered: Color) {
    companion object {
        val LIGHT = ChartColors(Color(0xFFDCEFD8), Color(0xFFE4E0EC), Color(0xFF7DC4B8), Color(0xFF9FB4DE))
        val DARK = ChartColors(Color(0xFF2D402E), Color(0xFF393644), Color(0xFF34766A), Color(0xFF4A5E80))
    }
}

/**
 * The palettes. Each light scheme has a dark twin (`_DARK`); values are worked out in OKLab so that the accent stays
 * as far from every state colour as the coral one (Powder, Sage, Lavender, Sky added 2026-10-18; a burgundy one was
 * dropped the same day as too close to the paused red). Sky turns "no network" grey (its blue-grey matched the
 * accent); dark Coral pauses in plum. Sage's dark surfaces are almost neutral: the green tint tired the eye.
 */
object Schemes {
    val CORAL = Scheme(
        id = "coral",
        background = Color(0xFFFFFFFF), card = Color(0xFFF8F4F2), cardStrong = Color(0xFFF0E9E6), outline = Color(0xFFEBE3DF),
        text = Color(0xFF2B2422), textSecondary = Color(0xFF8C807B), textMuted = Color(0xFFBDB2AD), accent = Color(0xFFE0654D),
        accentStrong = Color(0xFFB9472F), accentContainer = Color(0xFFF8D5CA), accentSoft = Color(0xFFFCE9E3), peach = Color(0xFFFFD9C7),
        butter = Color(0xFFFFE6A8), rose = Color(0xFFFFC9C3), danger = Color(0xFFD45545), warning = Color(0xFFFFD98F),
        warningStrong = Color(0xFFE9B45A), warningIcon = Color(0xFF9C6B2F), unavailable = Color(0xFFF1EBE8), unavailableMark = Color(0xFFC9BEB9),
    )

    val CORAL_DARK = Scheme(
        id = "coral",
        background = Color(0xFF231F1F), card = Color(0xFF312B2A), cardStrong = Color(0xFF3D3634), outline = Color(0xFF463E3C),
        text = Color(0xFFE8E1DF), textSecondary = Color(0xFFB9AAA7), textMuted = Color(0xFF847976), accent = Color(0xFFE1745E),
        accentStrong = Color(0xFFFAA592), accentContainer = Color(0xFF774B41), accentSoft = Color(0xFF472C26), peach = Color(0xFF6F4C34),
        butter = Color(0xFF766032), rose = Color(0xFF854A4B), danger = Color(0xFFE66F62), warning = Color(0xFF775C25),
        warningStrong = Color(0xFFF5C66D), warningIcon = Color(0xFFFFE8B0), unavailable = Color(0xFF373130), unavailableMark = Color(0xFF7A716E),
        dark = true, onAccent = Color(0xFF231F1F), ping = PING_DARK, chart = ChartColors.DARK,
        states = StateSet.DARK.copy(paused = StateColors.Tone(Color(0xFF6A355A), Color(0xFFEF8FD0), Color(0xFFFFD7F3), band = Color(0xFF4C3544))),
    )

    val POWDER = Scheme(
        id = "powder",
        background = Color(0xFFFFFFFF), card = Color(0xFFF8F4F5), cardStrong = Color(0xFFF0E8EB), outline = Color(0xFFEBE2E5),
        text = Color(0xFF2B2426), textSecondary = Color(0xFF8C7F83), textMuted = Color(0xFFBCB1B5), accent = Color(0xFFC58BA1),
        accentStrong = Color(0xFFA36C81), accentContainer = Color(0xFFEFD6DF), accentSoft = Color(0xFFF7EAEE), peach = Color(0xFFFFD9C7),
        butter = Color(0xFFFFE6A8), rose = Color(0xFFFFC9C3), danger = Color(0xFFD45545), warning = Color(0xFFFFD98F),
        warningStrong = Color(0xFFE9B45A), warningIcon = Color(0xFF9C6B2F), unavailable = Color(0xFFF1EAED), unavailableMark = Color(0xFFC9BDC1),
    )

    val POWDER_DARK = Scheme(
        id = "powder",
        background = Color(0xFF231F20), card = Color(0xFF302B2D), cardStrong = Color(0xFF3D3638), outline = Color(0xFF453D40),
        text = Color(0xFFE8E1E3), textSecondary = Color(0xFFB8A9AE), textMuted = Color(0xFF84787C), accent = Color(0xFFD297AD),
        accentStrong = Color(0xFFF0C2D3), accentContainer = Color(0xFF685059), accentSoft = Color(0xFF3E2F35), peach = Color(0xFF6F4C34),
        butter = Color(0xFF766032), rose = Color(0xFF854A4B), danger = Color(0xFFE66F62), warning = Color(0xFF775C25),
        warningStrong = Color(0xFFF5C66D), warningIcon = Color(0xFFFFE8B0), unavailable = Color(0xFF363133), unavailableMark = Color(0xFF797073),
        dark = true, onAccent = Color(0xFF231F20), ping = PING_DARK, chart = ChartColors.DARK,
        states = StateSet.DARK,
    )

    val SAGE = Scheme(
        id = "sage",
        background = Color(0xFFFFFFFF), card = Color(0xFFF3F6F3), cardStrong = Color(0xFFE6EDE8), outline = Color(0xFFE0E7E2),
        text = Color(0xFF222823), textSecondary = Color(0xFF7B867E), textMuted = Color(0xFFAEB7B0), accent = Color(0xFF7DA388),
        accentStrong = Color(0xFF5F836A), accentContainer = Color(0xFFCFE4D5), accentSoft = Color(0xFFE6F1E9), peach = Color(0xFFFFD9C7),
        butter = Color(0xFFFFE6A8), rose = Color(0xFFFFC9C3), danger = Color(0xFFD45545), warning = Color(0xFFFFD98F),
        warningStrong = Color(0xFFE9B45A), warningIcon = Color(0xFF9C6B2F), unavailable = Color(0xFFE9EEEA), unavailableMark = Color(0xFFBAC3BC),
    )

    val SAGE_DARK = Scheme(
        id = "sage",
        background = Color(0xFF202120), card = Color(0xFF2C2D2C), cardStrong = Color(0xFF373837), outline = Color(0xFF3F403F),
        text = Color(0xFFE2E3E2), textSecondary = Color(0xFFACAEAD), textMuted = Color(0xFF7A7C7B), accent = Color(0xFF88B897),
        accentStrong = Color(0xFFB7DCC2), accentContainer = Color(0xFF4B5D50), accentSoft = Color(0xFF2C372F), peach = Color(0xFF6F4C34),
        butter = Color(0xFF766032), rose = Color(0xFF854A4B), danger = Color(0xFFE66F62), warning = Color(0xFF775C25),
        warningStrong = Color(0xFFF5C66D), warningIcon = Color(0xFFFFE8B0), unavailable = Color(0xFF323332), unavailableMark = Color(0xFF727372),
        dark = true, onAccent = Color(0xFF202120), ping = PING_DARK, chart = ChartColors.DARK,
        states = StateSet.DARK,
    )

    val LAVENDER = Scheme(
        id = "lavender",
        background = Color(0xFFFFFFFF), card = Color(0xFFF5F4F8), cardStrong = Color(0xFFECEAF0), outline = Color(0xFFE6E3EB),
        text = Color(0xFF27252B), textSecondary = Color(0xFF84818C), textMuted = Color(0xFFB6B3BC), accent = Color(0xFFAD98D5),
        accentStrong = Color(0xFF8D78B3), accentContainer = Color(0xFFE0DAEF), accentSoft = Color(0xFFEFECF7), peach = Color(0xFFFFD9C7),
        butter = Color(0xFFFFE6A8), rose = Color(0xFFFFC9C3), danger = Color(0xFFD45545), warning = Color(0xFFFFD98F),
        warningStrong = Color(0xFFE9B45A), warningIcon = Color(0xFF9C6B2F), unavailable = Color(0xFFEDEBF1), unavailableMark = Color(0xFFC2BFC8),
        states = StateSet.LIGHT.copy(noNetwork = StateColors.Tone(Color(0xFFE1E3E5), Color(0xFF8F9397), Color(0xFF4A4D51), band = Color(0xFFE1E3E5))),
    )

    val LAVENDER_DARK = Scheme(
        id = "lavender",
        background = Color(0xFF212023), card = Color(0xFF2D2C30), cardStrong = Color(0xFF39373D), outline = Color(0xFF413E45),
        text = Color(0xFFE3E2E8), textSecondary = Color(0xFFAFACB8), textMuted = Color(0xFF7D7A84), accent = Color(0xFFC8B6EB),
        accentStrong = Color(0xFFEADDFF), accentContainer = Color(0xFF5B5468), accentSoft = Color(0xFF36323E), peach = Color(0xFF6F4C34),
        butter = Color(0xFF766032), rose = Color(0xFF854A4B), danger = Color(0xFFE66F62), warning = Color(0xFF775C25),
        warningStrong = Color(0xFFF5C66D), warningIcon = Color(0xFFFFE8B0), unavailable = Color(0xFF333236), unavailableMark = Color(0xFF747279),
        dark = true, onAccent = Color(0xFF212023), ping = PING_DARK, chart = ChartColors.DARK,
        states = StateSet.DARK.copy(noNetwork = StateColors.Tone(Color(0xFF494B4C), Color(0xFF9B9FA3), Color(0xFFDDE0E3), band = Color(0xFF3A3C3E))),
    )

    val SKY = Scheme(
        id = "sky",
        background = Color(0xFFFFFFFF), card = Color(0xFFF3F5F8), cardStrong = Color(0xFFE6EBF1), outline = Color(0xFFE0E6EB),
        text = Color(0xFF22262B), textSecondary = Color(0xFF7B848D), textMuted = Color(0xFFAEB5BD), accent = Color(0xFF76A9DE),
        accentStrong = Color(0xFF5789BC), accentContainer = Color(0xFFCFE0F1), accentSoft = Color(0xFFE6EFF8), peach = Color(0xFFFFD9C7),
        butter = Color(0xFFFFE6A8), rose = Color(0xFFFFC9C3), danger = Color(0xFFD45545), warning = Color(0xFFFFD98F),
        warningStrong = Color(0xFFE9B45A), warningIcon = Color(0xFF9C6B2F), unavailable = Color(0xFFE9EDF2), unavailableMark = Color(0xFFBAC1C9),
        states = StateSet.LIGHT.copy(noNetwork = StateColors.Tone(Color(0xFFE1E3E5), Color(0xFF8F9397), Color(0xFF4A4D51), band = Color(0xFFE1E3E5))),
    )

    val SKY_DARK = Scheme(
        id = "sky",
        background = Color(0xFF1E2123), card = Color(0xFF292D31), cardStrong = Color(0xFF34383E), outline = Color(0xFF3B4046),
        text = Color(0xFFDFE3E8), textSecondary = Color(0xFFA6AFB9), textMuted = Color(0xFF757D85), accent = Color(0xFF9DC2EA),
        accentStrong = Color(0xFFCBE8FF), accentContainer = Color(0xFF4B5A69), accentSoft = Color(0xFF2C353F), peach = Color(0xFF6F4C34),
        butter = Color(0xFF766032), rose = Color(0xFF854A4B), danger = Color(0xFFE66F62), warning = Color(0xFF775C25),
        warningStrong = Color(0xFFF5C66D), warningIcon = Color(0xFFFFE8B0), unavailable = Color(0xFF303337), unavailableMark = Color(0xFF6E747A),
        dark = true, onAccent = Color(0xFF1E2123), ping = PING_DARK, chart = ChartColors.DARK,
        states = StateSet.DARK.copy(noNetwork = StateColors.Tone(Color(0xFF494B4C), Color(0xFF9B9FA3), Color(0xFFDDE0E3), band = Color(0xFF3A3C3E))),
    )

    val MONO = Scheme(
        id = "mono",
        background = Color(0xFFFFFFFF), card = Color(0xFFF5F5F5), cardStrong = Color(0xFFEAEAEA), outline = Color(0xFFE2E2E2),
        text = Color(0xFF1C1C1C), textSecondary = Color(0xFF7A7A7A), textMuted = Color(0xFFB4B4B4), accent = Color(0xFF2E2E2E),
        accentStrong = Color(0xFF000000), accentContainer = Color(0xFFDCDCDC), accentSoft = Color(0xFFEFEFEF), peach = Color(0xFFE2E2E2),
        butter = Color(0xFFEDEDED), rose = Color(0xFFD9D9D9), danger = Color(0xFFB3261E), warning = Color(0xFFDADADA),
        warningStrong = Color(0xFFBDBDBD), warningIcon = Color(0xFF4A4A4A), unavailable = Color(0xFFF0F0F0), unavailableMark = Color(0xFFC4C4C4),
    )

    val MONO_DARK = Scheme(
        id = "mono",
        background = Color(0xFF231F20), card = Color(0xFF312B2C), cardStrong = Color(0xFF3D3638), outline = Color(0xFF453D3F),
        text = Color(0xFFE8E1E3), textSecondary = Color(0xFFB8A9AD), textMuted = Color(0xFF84787B), accent = Color(0xFFD7D7D7),
        accentStrong = Color(0xFFFFFFFF), accentContainer = Color(0xFF585858), accentSoft = Color(0xFF343434), peach = Color(0xFF6F4C34),
        butter = Color(0xFF766032), rose = Color(0xFF854A4B), danger = Color(0xFFE66F62), warning = Color(0xFF775C25),
        warningStrong = Color(0xFFF5C66D), warningIcon = Color(0xFFFFE8B0), unavailable = Color(0xFF373133), unavailableMark = Color(0xFF797073),
        dark = true, onAccent = Color(0xFF231F20), ping = PING_DARK, chart = ChartColors.DARK,
        states = StateSet.DARK,
    )

    /** The chooser's order; each one's dark twin is in [darkOf]. */
    val all = listOf(CORAL, POWDER, SAGE, LAVENDER, SKY, MONO)

    private val darkOf = mapOf(
        "coral" to CORAL_DARK, "powder" to POWDER_DARK, "sage" to SAGE_DARK,
        "lavender" to LAVENDER_DARK, "sky" to SKY_DARK, "mono" to MONO_DARK,
    )

    fun byId(id: String?, dark: Boolean = false): Scheme {
        val light = all.firstOrNull { it.id == id } ?: CORAL
        return if (dark) darkOf.getValue(light.id) else light
    }
}

/**
 * All colours of the app, read from the selected [Scheme] (Compose state: switching repaints
 * everything at once). Restyling means editing [Schemes].
 */
object Palette {
    var scheme by mutableStateOf(Schemes.CORAL)

    val background get() = scheme.background
    val dark get() = scheme.dark
    /** Text and icons on an [accent] fill. */
    val onAccent get() = scheme.onAccent
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

    private val pingColors get() = scheme.ping

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

    /** A shade of the latency colour readable as text (deeper on light schemes, lighter on dark ones). */
    fun pingText(ms: Int?): Color = if (ms == null) textMuted else lerp(ping(ms), text, 0.55f)
}

/** The current scheme's state colours (see [StateSet]); the names every screen uses. */
object StateColors {
    /**
     * [fill]: the button; [strong]: ring, timeline, markers; [icon]: the symbol on [fill] and readable text in the
     * state's colour; [band]: the chart background, chosen so the bands stay apart side by side (orange and yellow
     * fills next to each other were nearly the same pale cream).
     */
    data class Tone(val fill: Color, val strong: Color, val icon: Color, val band: Color)

    val direct get() = Palette.scheme.states.direct
    val limited get() = Palette.scheme.states.limited
    val noNetwork get() = Palette.scheme.states.noNetwork
    val paused get() = Palette.scheme.states.paused
    /**
     * The limited-network tower on a heatmap cell: apart from every colour of the ping scale by lightness (contrast
     * ≥ 4.5:1 on all of it; [limited]'s icon tone fell to 2.6:1 on the slow cells and shared their yellow).
     */
    val limitedOnCell get() = if (Palette.scheme.dark) Color(0xFFFFE9C2) else Color(0xFF6B4418)
    /** The tunnel was off (chart bands). */
    val off get() = Palette.scheme.states.off
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

/** Material's own colours (switches, fields, pickers) from the scheme, on Material's light or dark defaults. */
private fun colorsOf(p: Scheme) = (if (p.dark) darkColorScheme() else lightColorScheme()).copy(
    primary = p.accent,
    onPrimary = p.onAccent,
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
