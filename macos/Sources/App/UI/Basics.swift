import AppKit
import SwiftUI

// MARK: Texts

/// A text of the Android app, by its resource name (`Shared.strings`, generated from strings.xml by
/// scripts/mac_strings.py): both platforms say the same thing in the same words.
func L(_ key: String, _ args: CVarArg...) -> String { Lv(key, args) }

/// [L] with the arguments as an array (texts asked for by the shared code).
func Lv(_ key: String, _ args: [CVarArg]) -> String {
    let format = Bundle.main.localizedString(forKey: key, value: key, table: "Shared")
    return args.isEmpty ? format : String(format: format, locale: Locale.current, arguments: args)
}

/// [L] for texts the shared code formats: its arguments arrive already rendered to strings, so
/// `String(format:)` can't take them — a `%ld` would print a string's address as a huge number
/// (the log's "…updated: 32 810 427 072 servers", owner 2026-11-09). Placeholders as they arrive
/// after the strings generator's NSString conversion (and the raw Android ones alike): `%@`, `%s`,
/// `%d`, `%i`, `%u`, `%x` and the `l`/`ll`/etc.-modified forms take the next argument, `%N$…` the
/// N-th (1-based), `%.Nf` rounds to N digits, `%%` a literal percent.
func Ls(_ key: String, _ args: [String]) -> String {
    let pattern = Bundle.main.localizedString(forKey: key, value: key, table: "Shared")
    guard !args.isEmpty else { return pattern }
    var out = ""
    var next = 0
    var i = pattern.startIndex
    while i < pattern.endIndex {
        if pattern[i] != "%" || pattern.index(after: i) == pattern.endIndex {
            out.append(pattern[i]); i = pattern.index(after: i); continue
        }
        var j = pattern.index(after: i)
        var positional = -1
        let at = j
        while j < pattern.endIndex && pattern[j].isNumber { j = pattern.index(after: j) }
        if j < pattern.endIndex && pattern[j] == "$" && j > at {
            positional = (Int(pattern[at..<j]) ?? 1) - 1
            j = pattern.index(after: j)
        }
        var precision = -1
        if j < pattern.endIndex && pattern[j] == "." {
            j = pattern.index(after: j)
            let p = j
            while j < pattern.endIndex && pattern[j].isNumber { j = pattern.index(after: j) }
            precision = Int(pattern[p..<j]) ?? -1
        }
        // The length modifiers `NSString` formats carry (`%ld`, `%lld`, `%llu`, `%hhd`, `%zd`, `%qx`).
        while j < pattern.endIndex && "hlLjtzq".contains(pattern[j]) { j = pattern.index(after: j) }
        if j >= pattern.endIndex { out.append(contentsOf: pattern[i..<j]); break }
        let spec = pattern[j]
        if spec == "%" {
            out.append("%")
        } else if "@sdiuUxXoDcCfegap".contains(spec) {
            let idx = positional >= 0 ? positional : next
            if positional < 0 { next += 1 }
            if args.indices.contains(idx) {
                let arg = args[idx]
                if "fega".contains(spec), precision >= 0, let n = Double(arg) {
                    out.append(String(format: "%.\(precision)f", n))
                } else {
                    out.append(arg)
                }
            }
        } else {
            out.append(contentsOf: pattern[i...j]) // not a placeholder: the span goes out as it is
        }
        i = pattern.index(after: j)
    }
    return out
}

/// A plural of the Android app (`<plurals>` → `Shared.stringsdict`).
func P(_ key: String, _ count: Int) -> String {
    let format = Bundle.main.localizedString(forKey: key, value: key, table: "Shared")
    return String.localizedStringWithFormat(format, count)
}

/// A macOS-only text with its English built in, so a key not yet in Localizable.strings still reads right.
func macText(_ key: String, _ fallback: String) -> String {
    Bundle.main.localizedString(forKey: key, value: fallback, table: nil)
}

/// "128 ms" with the unit (whatever follows the last digit) at 70 % of the number's size, as on Android.
func withSmallUnit(_ value: String, size: CGFloat, font: (CGFloat) -> Font) -> Text {
    guard let last = value.lastIndex(where: \.isNumber) else { return Text(value).font(font(size)) }
    let end = value.index(after: last)
    guard end < value.endIndex else { return Text(value).font(font(size)) }
    return Text(value[..<end]).font(font(size)) + Text(value[end...]).font(font(size * 0.7))
}

// MARK: Colours of the connection's states (the palette tokens themselves are `Palette` in Theme.swift)

/// The connection's states other than "through a server" / "connecting" (Android `StateColors`, `Scheme.states`):
/// each has a hue of its own, so the button, the state timeline and the chart bands never mix two states up.
enum StateTone {
    case direct, limited, noNetwork, paused

    private var tone: LiveTone {
        let live = PaletteStore.shared.live
        return switch self {
        case .direct: live.direct
        case .limited: live.limited
        case .noNetwork: live.noNetwork
        case .paused: live.paused
        }
    }

    var fill: Color { tone.fill }
    var strong: Color { tone.strong }
    var icon: Color { tone.icon }
    /// The chart's background band (`StateColors.Tone.band`).
    var band: Color { tone.band }
}

extension Phase {
    /// The state's own colours for the phases that have them — traffic without a server, no network, paused,
    /// limited. Connected / searching / off have none of their own: each render colours those itself (the accent
    /// family, the greys) — the one mapping every site shares.
    var tone: StateTone? {
        switch self {
        case .direct: .direct
        case .paused: .paused
        case .noNetwork: .noNetwork
        case .limited: .limited
        case .off, .searching, .connected: nil
        }
    }
}

// MARK: Flags

/// Flat flag in a 3:2 tile with slightly rounded corners — the Android app's images (flagcdn.com, public domain), copied
/// into `Contents/Resources/Flags`. Square flags (CH, VA, NP) are shown whole on their edge colour; no flag → the code.
struct FlagView: View {
    var code: String?
    var height: CGFloat = 16

    private static var cache: [String: NSImage] = [:]
    private static let padded: [String: Color] = ["CH": Color(red: 1, green: 0, blue: 0), "VA": .white, "NP": .clear]

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: height * 0.2)
        let upper = code?.uppercased()
        if let upper, let image = Self.image(upper) {
            if let pad = Self.padded[upper] {
                Image(nsImage: image).resizable().aspectRatio(contentMode: .fit)
                    .frame(width: height * 1.5, height: height).background(pad).clipShape(shape)
            } else {
                Image(nsImage: image).resizable().aspectRatio(contentMode: .fill)
                    .frame(width: height * 1.5, height: height).clipShape(shape)
            }
        } else {
            shape.fill(Palette.cardStrong).frame(width: height * 1.5, height: height)
                .overlay(Text(upper ?? "?").font(Fonts.body(height * 0.5, bold: true)).foregroundStyle(Palette.textSecondary))
        }
    }

    @MainActor
    private static func image(_ code: String) -> NSImage? {
        if let cached = cache[code] { return cached }
        guard let url = Bundle.main.url(forResource: code.lowercased(), withExtension: "webp", subdirectory: "Flags"),
              let image = NSImage(contentsOf: url) else { return nil }
        cache[code] = image
        return image
    }
}

/// The country's name in the UI language, as Android's `Countries.name`: the curated short names of
/// `country_short_names` first ("USA", "UK" — what the translation prefers to the system's full
/// name), then the system's own region name, then the bare code.
func countryName(_ code: String?) -> String {
    guard let code, !code.isEmpty else { return "" }
    if let short = countryShortNames[code] { return short }
    return Locale.current.localizedString(forRegionCode: code) ?? code
}

/// The overrides of Android's `country_short_names` string-array (`CODE=Name` items,
/// `shared/data/Countries.kt` `shortNames()`). On Android the array is translatable; here each name
/// is a `mac.*`-style key so Localizable.strings can do the same later — the English infix stands
/// until then.
private let countryShortNames: [String: String] = [
    "US": macText("country_short_us", "USA"),
    "GB": macText("country_short_gb", "UK"),
    "AE": macText("country_short_ae", "UAE"),
    "KR": macText("country_short_kr", "South Korea"),
    "HK": macText("country_short_hk", "Hong Kong"),
]

/// The cross of "no reply" (Android `CrossMark`).
struct CrossMark: View {
    var color: Color

    var body: some View {
        Canvas { ctx, size in
            let inset = min(size.width, size.height) * 0.3
            var p = Path()
            p.move(to: CGPoint(x: inset, y: inset))
            p.addLine(to: CGPoint(x: size.width - inset, y: size.height - inset))
            p.move(to: CGPoint(x: size.width - inset, y: inset))
            p.addLine(to: CGPoint(x: inset, y: size.height - inset))
            ctx.stroke(p, with: .color(color), style: StrokeStyle(lineWidth: max(min(size.width, size.height) * 0.09, 1.5), lineCap: .round))
        }
    }
}

/// A spinning arc (Android's own spinner on probing cells and rows, not the system indicator).
struct Spinner: View {
    var color: Color = Palette.accent
    var lineWidth: CGFloat = 2
    @State private var turn = false

    var body: some View {
        Circle().trim(from: 0, to: 0.7)
            .stroke(color, style: StrokeStyle(lineWidth: lineWidth, lineCap: .round))
            .rotationEffect(.degrees(turn ? 360 : 0))
            .onAppear { withAnimation(.linear(duration: 0.9).repeatForever(autoreverses: false)) { turn = true } }
    }
}

/// Progress of a check as a ring (the Ping all chip).
struct ProgressRing: View {
    var value: Double
    var body: some View {
        ZStack {
            Circle().stroke(Palette.outline, lineWidth: 2)
            Circle().trim(from: 0, to: max(0, min(1, value))).stroke(Palette.bar, style: StrokeStyle(lineWidth: 2, lineCap: .round))
                .rotationEffect(.degrees(-90))
        }
    }
}

/// Amounts as Android's `Units.bytes`: binary steps, one decimal from GB up.
func bytesText(_ b: Int64) -> String {
    let v = Double(max(b, 0))
    if v >= 1_099_511_627_776 { return L("bytes_tb", v / 1_099_511_627_776) }
    if v >= 1_073_741_824 { return L("bytes_gb", v / 1_073_741_824) }
    if v >= 1_048_576 { return L("bytes_mb", v / 1_048_576) }
    return L("bytes_kb", v / 1024)
}

/// "42 minutes ago" in the UI language (Android `DateUtils.getRelativeTimeSpanString`, lower case).
func relativeText(_ date: Date) -> String {
    let f = RelativeDateTimeFormatter()
    f.unitsStyle = .full
    return f.localizedString(for: date, relativeTo: Date()).lowercased()
}

func dateText(_ date: Date) -> String {
    date.formatted(date: .abbreviated, time: .omitted)
}
