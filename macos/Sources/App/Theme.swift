import AppKit
import Observation
import SwiftUI

/// One colour scheme of the interface (Android `Scheme` in `ui/theme/Theme.kt`): every palette has a light and a
/// dark variant (the same `id`, `dark` tells them apart), kept as hex colours until `LivePalette` resolves them.
/// The connection's state colours, the latency scale and the chart shades come with the scheme: the light schemes
/// share one set, the dark ones another, and a palette may nudge a state whose hue would come too close to its
/// accent (`StateSetDef.copy`).
struct SchemeDef {
    var id: String
    var background: UInt32
    var card: UInt32
    var cardStrong: UInt32
    var outline: UInt32
    var text: UInt32
    var textSecondary: UInt32
    var textMuted: UInt32
    var accent: UInt32
    var accentStrong: UInt32
    var accentContainer: UInt32
    var accentSoft: UInt32
    var peach: UInt32
    var butter: UInt32
    var rose: UInt32
    var danger: UInt32
    /// The dark tone of a warning (the log's warn lines; Android `Scheme.warningIcon`). Android's `warning` /
    /// `warningStrong` are not ported: nothing draws them on either side.
    var warningIcon: UInt32
    var unavailable: UInt32
    var unavailableMark: UInt32
    var dark = false
    /// Text and icons drawn on `accent`: white on the light schemes, the background on the dark ones.
    var onAccent: UInt32 = 0xFFFFFF
    var states = StateSetDef.light
    /// The latency scale from fast to slow (see `Palette.ping`).
    var ping = SchemeDef.pingLight

    /// Fill of progress and share bars: the accent softened towards the background, by the same rule in every scheme.
    var bar: UInt32 { Palette.mix(accent, background, 0.4) }
    /// Fill of a selected chip or tab sitting on `card`: derived from the accent by one rule for every scheme, far
    /// enough from `card` to be read at a glance even where the accent is a grey.
    var selected: UInt32 { Palette.mix(accent, background, 0.82) }
    /// Colours shown in the settings preview strip: only what tells the palettes apart (the near-black text and the
    /// shared state colours look the same in all of them).
    var preview: [UInt32] { [background, card, accentSoft, accentContainer, accent, accentStrong, textSecondary] }

    /// Pastel latency scale of the light schemes, from fast (green) to slow (red) (Android `PING_LIGHT`).
    static let pingLight: [UInt32] = [0xA9DFA9, 0xC2E7A7, 0xDCECA9, 0xFFE3A3, 0xFFC9A5, 0xFFAEAE]
    /// The same hues, lifted out of the mud for a dark background: darker than the light pastels, lighter than the
    /// fills (Android `PING_DARK`).
    static let pingDark: [UInt32] = [0x5F9661, 0x709353, 0x808E4B, 0x9E8240, 0xAD7955, 0xBB6C6E]
}

/// A connection state's four roles (Android `StateColors.Tone`): the button's fill, the ring / timeline / markers
/// strong tone, the icon on the fill, and the chart's background band (chosen so the bands stay apart side by side).
struct StateToneDef {
    var fill: UInt32
    var strong: UInt32
    var icon: UInt32
    var band: UInt32
}

/// The connection's states other than "through a server" and "connecting" (those follow the accent): each has a hue
/// of its own, so the button, the state timeline and the chart bands never mix two states up. Limited network is
/// yellow, and so are the limited-network servers made for it (Android `StateSet`). `off` = the chart band of the
/// tunnel being off.
struct StateSetDef {
    /// Traffic without the tunnel: no server works (or none to use).
    var direct: StateToneDef
    /// Limited network (only some sites open), and the limited-network servers.
    var limited: StateToneDef
    /// No internet at all.
    var noNetwork: StateToneDef
    /// Traffic paused (no server and the user chose to pause).
    var paused: StateToneDef
    var off: UInt32

    /// Android `StateSet.copy`: one tone swapped (a palette nudging a state whose hue sits too close to its accent).
    func copy(direct: StateToneDef? = nil, limited: StateToneDef? = nil, noNetwork: StateToneDef? = nil,
              paused: StateToneDef? = nil, off: UInt32? = nil) -> StateSetDef {
        StateSetDef(direct: direct ?? self.direct, limited: limited ?? self.limited,
                    noNetwork: noNetwork ?? self.noNetwork, paused: paused ?? self.paused, off: off ?? self.off)
    }

    static let light = StateSetDef(
        direct: StateToneDef(fill: 0xFFC58A, strong: 0xE8873A, icon: 0x8A4710, band: 0xFFD8C2),
        limited: StateToneDef(fill: 0xFFD98F, strong: 0xE9B45A, icon: 0x9C6B2F, band: 0xFFEBC5),
        noNetwork: StateToneDef(fill: 0xDCE3EE, strong: 0x8DA0BE, icon: 0x4B5E7C, band: 0xDCE3EE),
        paused: StateToneDef(fill: 0xF7CBD3, strong: 0xB0304A, icon: 0x962A40, band: 0xF9E0E5),
        off: 0xE7DFDB)

    /// Same hues on a dark background: deep fills, bright rings, muted bands (Android `StateSet.DARK`).
    static let dark = StateSetDef(
        direct: StateToneDef(fill: 0x794722, strong: 0xFFA15C, icon: 0xFFD7AD, band: 0x4F392A),
        limited: StateToneDef(fill: 0x775C25, strong: 0xF5C66D, icon: 0xFFE8B0, band: 0x4C3F28),
        noNetwork: StateToneDef(fill: 0x414B5B, strong: 0x9BAFCE, icon: 0xD4E1F4, band: 0x363C47),
        paused: StateToneDef(fill: 0x793942, strong: 0xEF7688, icon: 0xFFD7DC, band: 0x4F3336),
        off: 0x3D3836)
}

/// Android's `ChartColors` (the device's own states on the activity charts — charging, screen off, saver, metered)
/// is not ported: the Mac has no activity chart to shade. Add the fields back with it.
/// The palettes (Android `object Schemes`). Each light scheme has a dark twin (`darkOf`); values are worked out in
/// OKLab so that the accent stays as far from every state colour as the coral one. Sky and Lavender turn
/// "no network" grey (its blue-grey matched the accent); dark Coral pauses in plum. Sage's dark surfaces are
/// almost neutral: the green tint tired the eye.
enum Schemes {
    static let coral = SchemeDef(
        id: "coral",
        background: 0xFFFFFF, card: 0xF8F4F2, cardStrong: 0xF0E9E6, outline: 0xEBE3DF,
        text: 0x2B2422, textSecondary: 0x8C807B, textMuted: 0xBDB2AD, accent: 0xE0654D,
        accentStrong: 0xB9472F, accentContainer: 0xF8D5CA, accentSoft: 0xFCE9E3, peach: 0xFFD9C7,
        butter: 0xFFE6A8, rose: 0xFFC9C3, danger: 0xD45545, warningIcon: 0x9C6B2F, unavailable: 0xF1EBE8, unavailableMark: 0xC9BEB9)

    static let coralDark = SchemeDef(
        id: "coral",
        background: 0x231F1F, card: 0x312B2A, cardStrong: 0x3D3634, outline: 0x463E3C,
        text: 0xE8E1DF, textSecondary: 0xB9AAA7, textMuted: 0x847976, accent: 0xE1745E,
        accentStrong: 0xFAA592, accentContainer: 0x774B41, accentSoft: 0x472C26, peach: 0x6F4C34,
        butter: 0x766032, rose: 0x854A4B, danger: 0xE66F62, warningIcon: 0xFFE8B0, unavailable: 0x373130, unavailableMark: 0x7A716E,
        dark: true, onAccent: 0x231F1F,
        states: StateSetDef.dark.copy(paused: StateToneDef(fill: 0x6A355A, strong: 0xEF8FD0, icon: 0xFFD7F3, band: 0x4C3544)),
        ping: SchemeDef.pingDark)

    static let powder = SchemeDef(
        id: "powder",
        background: 0xFFFFFF, card: 0xF8F4F5, cardStrong: 0xF0E8EB, outline: 0xEBE2E5,
        text: 0x2B2426, textSecondary: 0x8C7F83, textMuted: 0xBCB1B5, accent: 0xC58BA1,
        accentStrong: 0xA36C81, accentContainer: 0xEFD6DF, accentSoft: 0xF7EAEE, peach: 0xFFD9C7,
        butter: 0xFFE6A8, rose: 0xFFC9C3, danger: 0xD45545, warningIcon: 0x9C6B2F, unavailable: 0xF1EAED, unavailableMark: 0xC9BDC1)

    static let powderDark = SchemeDef(
        id: "powder",
        background: 0x231F20, card: 0x302B2D, cardStrong: 0x3D3638, outline: 0x453D40,
        text: 0xE8E1E3, textSecondary: 0xB8A9AE, textMuted: 0x84787C, accent: 0xD297AD,
        accentStrong: 0xF0C2D3, accentContainer: 0x685059, accentSoft: 0x3E2F35, peach: 0x6F4C34,
        butter: 0x766032, rose: 0x854A4B, danger: 0xE66F62, warningIcon: 0xFFE8B0, unavailable: 0x363133, unavailableMark: 0x797073,
        dark: true, onAccent: 0x231F20, states: .dark, ping: SchemeDef.pingDark)

    static let sage = SchemeDef(
        id: "sage",
        background: 0xFFFFFF, card: 0xF3F6F3, cardStrong: 0xE6EDE8, outline: 0xE0E7E2,
        text: 0x222823, textSecondary: 0x7B867E, textMuted: 0xAEB7B0, accent: 0x7DA388,
        accentStrong: 0x5F836A, accentContainer: 0xCFE4D5, accentSoft: 0xE6F1E9, peach: 0xFFD9C7,
        butter: 0xFFE6A8, rose: 0xFFC9C3, danger: 0xD45545, warningIcon: 0x9C6B2F, unavailable: 0xE9EEEA, unavailableMark: 0xBAC3BC)

    static let sageDark = SchemeDef(
        id: "sage",
        background: 0x202120, card: 0x2C2D2C, cardStrong: 0x373837, outline: 0x3F403F,
        text: 0xE2E3E2, textSecondary: 0xACAEAD, textMuted: 0x7A7C7B, accent: 0x88B897,
        accentStrong: 0xB7DCC2, accentContainer: 0x4B5D50, accentSoft: 0x2C372F, peach: 0x6F4C34,
        butter: 0x766032, rose: 0x854A4B, danger: 0xE66F62, warningIcon: 0xFFE8B0, unavailable: 0x323332, unavailableMark: 0x727372,
        dark: true, onAccent: 0x202120, states: .dark, ping: SchemeDef.pingDark)

    static let lavender = SchemeDef(
        id: "lavender",
        background: 0xFFFFFF, card: 0xF5F4F8, cardStrong: 0xECEAF0, outline: 0xE6E3EB,
        text: 0x27252B, textSecondary: 0x84818C, textMuted: 0xB6B3BC, accent: 0xAD98D5,
        accentStrong: 0x8D78B3, accentContainer: 0xE0DAEF, accentSoft: 0xEFECF7, peach: 0xFFD9C7,
        butter: 0xFFE6A8, rose: 0xFFC9C3, danger: 0xD45545, warningIcon: 0x9C6B2F, unavailable: 0xEDEBF1, unavailableMark: 0xC2BFC8,
        states: StateSetDef.light.copy(noNetwork: StateToneDef(fill: 0xE1E3E5, strong: 0x8F9397, icon: 0x4A4D51, band: 0xE1E3E5)))

    static let lavenderDark = SchemeDef(
        id: "lavender",
        background: 0x212023, card: 0x2D2C30, cardStrong: 0x39373D, outline: 0x413E45,
        text: 0xE3E2E8, textSecondary: 0xAFACB8, textMuted: 0x7D7A84, accent: 0xC8B6EB,
        accentStrong: 0xEADDFF, accentContainer: 0x5B5468, accentSoft: 0x36323E, peach: 0x6F4C34,
        butter: 0x766032, rose: 0x854A4B, danger: 0xE66F62, warningIcon: 0xFFE8B0, unavailable: 0x333236, unavailableMark: 0x747279,
        dark: true, onAccent: 0x212023,
        states: StateSetDef.dark.copy(noNetwork: StateToneDef(fill: 0x494B4C, strong: 0x9B9FA3, icon: 0xDDE0E3, band: 0x3A3C3E)),
        ping: SchemeDef.pingDark)

    static let sky = SchemeDef(
        id: "sky",
        background: 0xFFFFFF, card: 0xF3F5F8, cardStrong: 0xE6EBF1, outline: 0xE0E6EB,
        text: 0x22262B, textSecondary: 0x7B848D, textMuted: 0xAEB5BD, accent: 0x76A9DE,
        accentStrong: 0x5789BC, accentContainer: 0xCFE0F1, accentSoft: 0xE6EFF8, peach: 0xFFD9C7,
        butter: 0xFFE6A8, rose: 0xFFC9C3, danger: 0xD45545, warningIcon: 0x9C6B2F, unavailable: 0xE9EDF2, unavailableMark: 0xBAC1C9,
        states: StateSetDef.light.copy(noNetwork: StateToneDef(fill: 0xE1E3E5, strong: 0x8F9397, icon: 0x4A4D51, band: 0xE1E3E5)))

    static let skyDark = SchemeDef(
        id: "sky",
        background: 0x1E2123, card: 0x292D31, cardStrong: 0x34383E, outline: 0x3B4046,
        text: 0xDFE3E8, textSecondary: 0xA6AFB9, textMuted: 0x757D85, accent: 0x9DC2EA,
        accentStrong: 0xCBE8FF, accentContainer: 0x4B5A69, accentSoft: 0x2C353F, peach: 0x6F4C34,
        butter: 0x766032, rose: 0x854A4B, danger: 0xE66F62, warningIcon: 0xFFE8B0, unavailable: 0x303337, unavailableMark: 0x6E747A,
        dark: true, onAccent: 0x1E2123,
        states: StateSetDef.dark.copy(noNetwork: StateToneDef(fill: 0x494B4C, strong: 0x9B9FA3, icon: 0xDDE0E3, band: 0x3A3C3E)),
        ping: SchemeDef.pingDark)

    static let mono = SchemeDef(
        id: "mono",
        background: 0xFFFFFF, card: 0xF5F5F5, cardStrong: 0xEAEAEA, outline: 0xE2E2E2,
        text: 0x1C1C1C, textSecondary: 0x7A7A7A, textMuted: 0xB4B4B4, accent: 0x2E2E2E,
        accentStrong: 0x000000, accentContainer: 0xDCDCDC, accentSoft: 0xEFEFEF, peach: 0xE2E2E2,
        butter: 0xEDEDED, rose: 0xD9D9D9, danger: 0xB3261E, warningIcon: 0x4A4A4A, unavailable: 0xF0F0F0, unavailableMark: 0xC4C4C4)

    static let monoDark = SchemeDef(
        id: "mono",
        background: 0x231F20, card: 0x312B2C, cardStrong: 0x3D3638, outline: 0x453D3F,
        text: 0xE8E1E3, textSecondary: 0xB8A9AD, textMuted: 0x84787B, accent: 0xD7D7D7,
        accentStrong: 0xFFFFFF, accentContainer: 0x585858, accentSoft: 0x343434, peach: 0x6F4C34,
        butter: 0x766032, rose: 0x854A4B, danger: 0xE66F62, warningIcon: 0xFFE8B0, unavailable: 0x373133, unavailableMark: 0x797073,
        dark: true, onAccent: 0x231F20, states: .dark, ping: SchemeDef.pingDark)

    /// The chooser's order; each one's dark twin is in `darkOf`.
    static let all: [SchemeDef] = [coral, powder, sage, lavender, sky, mono]

    private static let darkOf: [String: SchemeDef] = [
        "coral": coralDark, "powder": powderDark, "sage": sageDark,
        "lavender": lavenderDark, "sky": skyDark, "mono": monoDark,
    ]

    static func byId(_ id: String?, dark: Bool = false) -> SchemeDef {
        let light = all.first { $0.id == id } ?? coral
        return dark ? (darkOf[light.id] ?? coralDark) : light
    }
}

/// A connection state's colours of the live palette (Android `StateColors.Tone` as resolved colours).
struct LiveTone {
    var fill: Color
    var strong: Color
    var icon: Color
    var band: Color
}

/// The palette now in effect: every token of one palette id's two `SchemeDef`s (light and dark) resolved into
/// colours that pick their side with the appearance at draw time. `PaletteStore` rebuilds it on a switch — views
/// reading `Palette.*` inside `body` observe it and repaint at once (Android's `Palette.scheme` as Compose state).
struct LivePalette {
    var background: Color
    var card: Color
    var cardStrong: Color
    var outline: Color
    var text: Color
    var textSecondary: Color
    var textMuted: Color
    var accent: Color
    var accentStrong: Color
    var accentContainer: Color
    var accentSoft: Color
    var peach: Color
    var butter: Color
    var rose: Color
    var danger: Color
    var warningIcon: Color
    var unavailable: Color
    var unavailableMark: Color
    var onAccent: Color
    var bar: Color
    var selected: Color
    /// The chart band of the tunnel being off.
    var off: Color
    var direct: LiveTone
    var limited: LiveTone
    var noNetwork: LiveTone
    var paused: LiveTone
    /// The latency scale, light-side and dark-side hexes (blended in `Palette.ping`).
    var pingLight: [UInt32]
    var pingDark: [UInt32]
    /// The scale as colours for a legend strip.
    var pingLegend: [Color]

    init(id: String) {
        let l = Schemes.byId(id), d = Schemes.byId(id, dark: true)
        func tone(_ a: StateToneDef, _ b: StateToneDef) -> LiveTone {
            LiveTone(fill: Palette.dynamic(a.fill, b.fill), strong: Palette.dynamic(a.strong, b.strong),
                     icon: Palette.dynamic(a.icon, b.icon), band: Palette.dynamic(a.band, b.band))
        }
        background = Palette.dynamic(l.background, d.background)
        card = Palette.dynamic(l.card, d.card)
        cardStrong = Palette.dynamic(l.cardStrong, d.cardStrong)
        outline = Palette.dynamic(l.outline, d.outline)
        text = Palette.dynamic(l.text, d.text)
        textSecondary = Palette.dynamic(l.textSecondary, d.textSecondary)
        textMuted = Palette.dynamic(l.textMuted, d.textMuted)
        accent = Palette.dynamic(l.accent, d.accent)
        accentStrong = Palette.dynamic(l.accentStrong, d.accentStrong)
        accentContainer = Palette.dynamic(l.accentContainer, d.accentContainer)
        accentSoft = Palette.dynamic(l.accentSoft, d.accentSoft)
        peach = Palette.dynamic(l.peach, d.peach)
        butter = Palette.dynamic(l.butter, d.butter)
        rose = Palette.dynamic(l.rose, d.rose)
        danger = Palette.dynamic(l.danger, d.danger)
        warningIcon = Palette.dynamic(l.warningIcon, d.warningIcon)
        unavailable = Palette.dynamic(l.unavailable, d.unavailable)
        unavailableMark = Palette.dynamic(l.unavailableMark, d.unavailableMark)
        onAccent = Palette.dynamic(l.onAccent, d.onAccent)
        bar = Palette.dynamic(l.bar, d.bar)
        selected = Palette.dynamic(l.selected, d.selected)
        off = Palette.dynamic(l.states.off, d.states.off)
        direct = tone(l.states.direct, d.states.direct)
        limited = tone(l.states.limited, d.states.limited)
        noNetwork = tone(l.states.noNetwork, d.states.noNetwork)
        paused = tone(l.states.paused, d.states.paused)
        pingLight = l.ping
        pingDark = d.ping
        pingLegend = zip(l.ping, d.ping).map { Palette.dynamic($0.0, $0.1) }
    }
}

/// The palette the settings chose (`AppSettings.palette`): its id and resolved colours. Reading a colour through
/// `Palette` inside a view's `body` observes `live`, so a switch repaints everything at once. Not actor-bound —
/// the colours are read inside `Canvas` draw closures too, which are synchronous and non-isolated; in practice
/// every read and every `select` happens on the main thread.
@Observable
final class PaletteStore {
    /// The one store (like `EngineBridge`'s `nonisolated(unsafe)` singletons): every read and `select` happens on
    /// the main thread.
    nonisolated(unsafe) static let shared = PaletteStore()

    /// The current palette's id.
    private(set) var id = "coral"
    /// Its colours, rebuilt on every switch.
    private(set) var live = LivePalette(id: "coral")

    /// Another palette (a settings patch, a pick in the chooser). An unknown id falls back to Coral, as
    /// `Schemes.byId` does; the same id is a no-op.
    func select(_ id: String) {
        let resolved = Schemes.byId(id).id
        guard resolved != self.id else { return }
        self.id = resolved
        live = LivePalette(id: resolved)
        MainActor.assumeIsolated { ThemedCaret.refresh() }
    }
}

/// All colours of the app, read from the palette the settings chose (`PaletteStore`); the names every screen uses
/// (Android `object Palette`). Each colour keeps its light/dark pair and picks a side at draw time, so the
/// appearance switch needs nothing. Restyling means editing `Schemes`.
enum Palette {
    static var background: Color { PaletteStore.shared.live.background }
    static var card: Color { PaletteStore.shared.live.card }
    static var cardStrong: Color { PaletteStore.shared.live.cardStrong }
    static var outline: Color { PaletteStore.shared.live.outline }
    static var text: Color { PaletteStore.shared.live.text }
    static var textSecondary: Color { PaletteStore.shared.live.textSecondary }
    static var textMuted: Color { PaletteStore.shared.live.textMuted }
    static var accent: Color { PaletteStore.shared.live.accent }
    static var accentStrong: Color { PaletteStore.shared.live.accentStrong }
    static var accentContainer: Color { PaletteStore.shared.live.accentContainer }
    static var accentSoft: Color { PaletteStore.shared.live.accentSoft }
    static var onAccent: Color { PaletteStore.shared.live.onAccent }
    /// Fill of a selected chip / tab on a card: accent 82 % towards the background (`SchemeDef.selected`).
    static var selected: Color { PaletteStore.shared.live.selected }
    /// Fill of progress / share bars: accent 40 % towards the background (`SchemeDef.bar`).
    static var bar: Color { PaletteStore.shared.live.bar }
    static var peach: Color { PaletteStore.shared.live.peach }
    static var butter: Color { PaletteStore.shared.live.butter }
    static var rose: Color { PaletteStore.shared.live.rose }
    static var danger: Color { PaletteStore.shared.live.danger }
    /// The dark tone of a warning (the log's warn lines).
    static var warningIcon: Color { PaletteStore.shared.live.warningIcon }
    static var unavailable: Color { PaletteStore.shared.live.unavailable }
    static var unavailableMark: Color { PaletteStore.shared.live.unavailableMark }
    /// A cell never checked (Android `untested` = the background): not "no reply" — the grey and the
    /// cross say a check answered nothing, the bare background says none ran.
    static var untested: Color { PaletteStore.shared.live.background }
    /// The chart band of the tunnel being off.
    static var off: Color { PaletteStore.shared.live.off }

    /// A light/dark pair for a fixed colour outside the schemes (the chart series).
    static func pair(_ light: UInt32, _ dark: UInt32) -> Color { dynamic(light, dark) }

    /// One colour resolved light or dark by the Mac's appearance when it is drawn (`NSAppearance`): the window's
    /// own, or the forced `.aqua` / `.darkAqua` of the theme override (`applyAppearance`).
    static func dynamic(_ light: UInt32, _ dark: UInt32) -> Color {
        Color(nsColor: NSColor(name: nil) { appearance in
            appearance.bestMatch(from: [.aqua, .darkAqua]) == .darkAqua ? rgb(dark) : rgb(light)
        })
    }

    static func rgb(_ v: UInt32) -> NSColor {
        NSColor(srgbRed: CGFloat((v >> 16) & 0xFF) / 255, green: CGFloat((v >> 8) & 0xFF) / 255,
                blue: CGFloat(v & 0xFF) / 255, alpha: 1)
    }

    static func nsColor(_ v: UInt32) -> NSColor { rgb(v) }

    /// `a` towards `b` by `t`, component-wise in sRGB (Android's `lerp` on scheme colours).
    static func mix(_ a: UInt32, _ b: UInt32, _ t: Double) -> UInt32 {
        func channel(_ x: UInt32, _ y: UInt32) -> UInt32 {
            UInt32((Double(x) + (Double(y) - Double(x)) * t).rounded())
        }
        return (channel((a >> 16) & 0xFF, (b >> 16) & 0xFF) << 16)
            | (channel((a >> 8) & 0xFF, (b >> 8) & 0xFF) << 8) | channel(a & 0xFF, b & 0xFF)
    }

    /// The latency scale: log from 50 ms to 3 s, then bent once more (power 0.65), as `Palette.ping` on Android.
    static func ping(_ ms: Int?) -> Color {
        guard let ms else { return unavailable }
        let fast = log(Double(pingFast)), slow = log(Double(pingSlow))
        let t = pow(min(max((log(Double(max(ms, 1))) - fast) / (slow - fast), 0), 1), 0.65)
        let live = PaletteStore.shared.live
        let light = blend(live.pingLight, t), dark = blend(live.pingDark, t)
        return Color(nsColor: NSColor(name: nil) { a in a.bestMatch(from: [.aqua, .darkAqua]) == .darkAqua ? dark : light })
    }

    /// Latency range of the colour scale; anything outside is clamped to its ends.
    static let pingFast = 50, pingSlow = 3000
    /// Colours for a legend bar of the scale.
    static var pingLegend: [Color] { PaletteStore.shared.live.pingLegend }

    private static func blend(_ scale: [UInt32], _ t: Double) -> NSColor {
        let pos = t * Double(scale.count - 1)
        let i = min(Int(pos), scale.count - 2)
        let f = pos - Double(i)
        let a = nsColor(scale[i]), b = nsColor(scale[i + 1])
        return NSColor(srgbRed: a.redComponent + (b.redComponent - a.redComponent) * f,
                       green: a.greenComponent + (b.greenComponent - a.greenComponent) * f,
                       blue: a.blueComponent + (b.blueComponent - a.blueComponent) * f, alpha: 1)
    }
}

/// The system font (SF) for text, as every Mac app; PT Serif — the Android app's display font — for the app's name,
/// titles and numbers (owner, 2026-10-22: PT Sans at the Android sizes made the window feel like a phone app). Sizes
/// are the Mac's own: 13 pt text, 11 pt secondary lines.
enum Fonts {
    static func display(_ size: CGFloat, bold: Bool = false) -> Font {
        .custom(bold ? "PTSerif-Bold" : "PTSerif-Regular", size: size)
    }

    static func displayItalic(_ size: CGFloat) -> Font {
        .custom("PTSerif-Italic", size: size)
    }

    static func body(_ size: CGFloat = 13, bold: Bool = false) -> Font {
        .system(size: size, weight: bold ? .semibold : .regular)
    }
}

/// "Border" bold + "(less)" italic, as everywhere.
struct AppTitle: View {
    var size: CGFloat = 26

    var body: some View {
        (Text("Border").font(Fonts.display(size, bold: true)) + Text("(less)").font(Fonts.displayItalic(size)))
            .foregroundStyle(Palette.text).textSelection(.enabled)
    }
}

/// More shared measures (the enum and the small ones are in `Controls.swift`): the literals the screens repeat,
/// named after the UI audit's U6 pass so a sweep of one measure stays one edit.
extension Dims {
    // Panes and the window

    /// The fixed window, and the snapshots' canvas (`BorderlessApp`, `Snapshots`).
    static let windowW: CGFloat = 980
    static let windowH: CGFloat = 640
    /// The sidebar (`Shell`).
    static let sidebarW: CGFloat = 196
    /// The settings' page list (`SettingsView`).
    static let settingsNavW: CGFloat = 236
    /// The inspector (`GroupsView`).
    static let inspectorW: CGFloat = 276
    /// The connect column of the main screen (`MainView`).
    static let connectColW: CGFloat = 316
    /// The menu-bar panel (`MenuBarPanel`).
    static let panelW: CGFloat = 320

    // Heights of bars and rows (`rowH` 30 and `panelRowH` 26 are in `Controls.swift`)

    /// A screen's title strip (`ScreenHeader`); also the window-drag band (`WindowDrag`'s strip).
    static let headerH: CGFloat = 52
    /// A compact row — and the taller buttons (`MacButtonStyle(height:)`).
    static let rowSmallH: CGFloat = 28
    /// The panel's folding section row (`MenuBarPanel`).
    static let panelSectionH: CGFloat = 44
    /// A row of the country lists (`CountriesPopup.rowHeight`).
    static let pickerRowH: CGFloat = 46

    // Corners (`cornerRadius` 6 and `cardRadius` 8 are in `Controls.swift`)

    /// A chip, a picker, a menu item, the segmented thumb.
    static let chipRadius: CGFloat = 5
    /// The segmented strip, a selected server tile.
    static let tileRadius: CGFloat = 7
    /// A popup menu's card (`CardLook`), a dashed info box.
    static let menuRadius: CGFloat = 10
    /// The in-window dialog card.
    static let dialogRadius: CGFloat = 12
    /// The setup screen's card.
    static let setupCardRadius: CGFloat = 16

    // Dialogs and popovers

    /// A small popover (the group filter, the log's range picker).
    static let popupW: CGFloat = 240
    /// A popover's usual width (the countries popup, a zone preview, a server menu).
    static let popoverW: CGFloat = 300
    /// A mostly-text dialog (error, share).
    static let dialogW: CGFloat = 480
    /// The zone editor.
    static let zoneDialogW: CGFloat = 520
    /// A dialog with a form (add servers, a custom period).
    static let formDialogW: CGFloat = 540

    // Fixed content heights (longer locales can clip — U6 keeps them visible)

    /// The zone dialog's list and apps boxes.
    static let zoneBoxH: CGFloat = 96
    /// The share dialog's payload box.
    static let shareBoxH: CGFloat = 120
    /// A stats chart; also the add dialog's drop box.
    static let chartH: CGFloat = 150
    /// The graphical DatePicker's column in a custom period.
    static let calendarH: CGFloat = 160
    /// The menu-bar panel's longest list.
    static let panelListMaxH: CGFloat = 230
    /// The country picker's list.
    static let pickerListH: CGFloat = 260
    /// The countries popup's list.
    static let popupListH: CGFloat = 340

    // Ad-hoc recurring sizes

    /// The QR image's side (`ShareDialog`); also the DatePicker column's width.
    static let qrSize: CGFloat = 220
}
