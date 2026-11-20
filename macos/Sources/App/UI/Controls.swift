import AppKit
import SwiftUI

/// The Mac-sized controls every screen shares: 24 pt push buttons with a hairline border (the accent for the main
/// action), toolbar icons, a segmented control, a search field and a grouped form (label left, value right), the
/// pickers and server lines the lists repeat, the sizes they share (`Dims`) and the snapshot fallback of the
/// scrolling lists — in the app's colours, never the system's menus (owner).

/// The sizes every screen shares, named so a sweep of one measure (a denser row, a rounder corner) is one edit —
/// the palette of measures next to `Palette`.
enum Dims {
    /// The corner of a button, a list row, a small box.
    static let cornerRadius: CGFloat = 6
    /// Cards' corners (the grouped form, the heatmap, the tiles).
    static let cardRadius: CGFloat = 8
    /// The push buttons' height (`MacButton`, `MacButtonLook`).
    static let buttonH: CGFloat = 24
    /// A row of a server list (the main list's rows, the outline's headers).
    static let rowH: CGFloat = 30
    /// A row of the menu bar panel's lists (`PanelItem`).
    static let panelRowH: CGFloat = 26
    /// The search field.
    static let searchH: CGFloat = 22
    /// A dialog's padding.
    static let dialogPad: CGFloat = 20
    /// A popup's padding.
    static let popupPad: CGFloat = 12
    /// The room a server row keeps for the ping figure (`PingText`).
    static let pingW: CGFloat = 46
}

/// A push button: 24 pt high, 6 pt corners, a hairline border; `accent` fills it for the main action or a mode that is on.
struct MacButton: View {
    var title: String? = nil
    var symbol: String? = nil
    var accent = false
    /// A trailing chevron: the button opens a menu.
    var popup = false
    var help: String? = nil
    var action: () -> Void = {}

    var body: some View {
        let button = Button(action: action) {
            HStack(spacing: 5) {
                if let symbol {
                    Image(systemName: symbol).font(.system(size: 11.5, weight: .medium))
                        .foregroundStyle(accent ? Palette.onAccent : Palette.textSecondary)
                }
                if let title { Text(title).lineLimit(1) }
                if popup {
                    Image(systemName: "chevron.down").font(.system(size: 8.5, weight: .bold))
                        .foregroundStyle(accent ? Palette.onAccent : Palette.textSecondary)
                }
            }
            .padding(.horizontal, title == nil ? 0 : 9)
            .frame(minWidth: 26)
        }
        .buttonStyle(MacButtonStyle(accent: accent))
        // `.help("")` still installs a tooltip — an empty one: only a real text goes on.
        if let help {
            button.help(help)
        } else {
            button
        }
    }
}

struct MacButtonStyle: ButtonStyle {
    var accent = false
    var height: CGFloat = Dims.buttonH
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(Fonts.body(12.5))
            .foregroundStyle(accent ? Palette.onAccent : Palette.text)
            .frame(height: height)
            .background(RoundedRectangle(cornerRadius: Dims.cornerRadius).fill(accent ? Palette.accent : Palette.background))
            .overlay(RoundedRectangle(cornerRadius: Dims.cornerRadius).strokeBorder(accent ? Palette.accentStrong.opacity(0.35) : Palette.outline, lineWidth: 1))
            .shadow(color: .black.opacity(0.05), radius: 0.5, y: 0.5)
            // Greyed while it can't act — the 0.45 the settings' disabled rows use — and the
            // pressed darkening is off too: a dead button doesn't react.
            .brightness(configuration.isPressed && isEnabled ? -0.05 : 0)
            .opacity(isEnabled ? 1 : 0.45)
            .contentShape(RoundedRectangle(cornerRadius: Dims.cornerRadius))
    }
}

/// The push button's look for a view that isn't a Button (one that opens a menu — a Button would swallow
/// the click, so the `appMenuOnClick` / `appPopupOnClick` anchors take it instead).
struct MacButtonLook: ViewModifier {
    func body(content: Content) -> some View {
        content
            .font(Fonts.body(12.5))
            .foregroundStyle(Palette.text)
            .frame(height: Dims.buttonH)
            .background(RoundedRectangle(cornerRadius: Dims.cornerRadius).fill(Palette.background))
            .overlay(RoundedRectangle(cornerRadius: Dims.cornerRadius).strokeBorder(Palette.outline, lineWidth: 1))
            .shadow(color: .black.opacity(0.05), radius: 0.5, y: 0.5)
            .contentShape(RoundedRectangle(cornerRadius: Dims.cornerRadius))
    }
}

/// A small "choice" that opens a menu or a popup under itself (the strategy, a sort order, a form value):
/// the label in the push-button's look with a chevron. The anchor is the caller's — `appMenuOnClick` or
/// `appPopupOnClick` — and `.fixedSize()` goes on top where the row should hug the button.
struct PickerButton<Label: View>: View {
    /// The chevron that says the button opens a list: `.down` for a menu of actions, `.upDown` for a value
    /// to pick; `nil` when the label already says so (the sort's arrows).
    enum Chevron { case down, upDown }

    var spacing: CGFloat = 5
    var pad: CGFloat = 9
    var chevron: Chevron? = .upDown
    @ViewBuilder var label: Label

    var body: some View {
        HStack(spacing: spacing) {
            label
            if let chevron {
                Image(systemName: chevron == .down ? "chevron.down" : "chevron.up.chevron.down")
                    .font(chevron == .down ? .system(size: 8.5, weight: .bold) : .system(size: 9, weight: .semibold))
                    .foregroundStyle(Palette.textSecondary)
            }
        }
        .padding(.horizontal, pad)
        .modifier(MacButtonLook())
    }
}

/// A round 28 pt button for a mode (country, battery saving): bordered when off; filled with the accent and grown into a
/// pill with its label when on.
struct ModeButton: View {
    var symbol: String
    var on: Bool
    var label: String?
    var help: String
    /// nil: it opens a popup (`appPopupOnClick`), which a Button would swallow the click of.
    var action: (() -> Void)?
    @State private var hover = false

    var body: some View {
        if let action { Button(action: action) { face }.buttonStyle(.plain).help(help) } else { face.help(help) }
    }

    private var face: some View {
        Group {
            HStack(spacing: 5) {
                Image(systemName: symbol).font(.system(size: 13, weight: .medium))
                if let label { Text(label).font(Fonts.body(12, bold: true)) }
            }
            .foregroundStyle(on ? Palette.onAccent : Palette.textSecondary)
            .padding(.horizontal, label == nil ? 0 : 11)
            .frame(minWidth: 30, minHeight: 30)
            .background(Capsule().fill(on ? Palette.accent : hover ? Palette.card : Palette.background))
            .overlay(Capsule().strokeBorder(on ? .clear : Palette.outline, lineWidth: 1))
            .contentShape(Capsule())
        }
        .onHover { hover = $0 }
        .animation(.easeInOut(duration: 0.2), value: on)
    }
}

/// An icon in a screen's top bar; a soft accent fill while its mode is on.
struct ToolbarIcon: View {
    var symbol: String
    var on = false
    var help: String
    /// nil: the icon opens a popup (`appPopupOnClick`), which a Button would swallow the click of.
    var action: (() -> Void)?
    @State private var hover = false

    var body: some View {
        let icon = Image(systemName: symbol).font(.system(size: 13.5, weight: .regular))
            .foregroundStyle(on ? Palette.accentStrong : Palette.textSecondary)
            .frame(width: 28, height: Dims.buttonH)
            .background(RoundedRectangle(cornerRadius: Dims.cornerRadius).fill(on ? Palette.selected : hover ? Palette.card : .clear))
            .contentShape(RoundedRectangle(cornerRadius: Dims.cornerRadius))
        Group {
            if let action { Button(action: action) { icon }.buttonStyle(.plain) } else { icon }
        }
        .onHover { hover = $0 }
        .help(help)
        .animation(.easeInOut(duration: 0.15), value: on)
    }
}

/// A segmented control: the chosen segment raised on a white plate inside a grey track. The plate
/// slides to a new pick (the `plate` geometry) instead of blinking off and on, and a hovered
/// segment tints under the pointer — the control used to give no feedback at all.
struct Segmented<T: Hashable>: View {
    var items: [(T, String)]
    @Binding var selection: T
    @State private var hover: T?
    @Namespace private var plate

    var body: some View {
        HStack(spacing: 2) {
            ForEach(items, id: \.0) { value, title in
                let on = value == selection
                Text(title).font(Fonts.body(12, bold: on)).foregroundStyle(Palette.text).lineLimit(1)
                    .padding(.horizontal, 10).frame(height: 20)
                    .background {
                        if on {
                            RoundedRectangle(cornerRadius: Dims.chipRadius).fill(Palette.background)
                                .shadow(color: .black.opacity(0.12), radius: 1, y: 0.5)
                                .matchedGeometryEffect(id: "plate", in: plate)
                        }
                    }
                    .background(RoundedRectangle(cornerRadius: Dims.chipRadius).fill(!on && hover == value ? Palette.card : .clear))
                    .contentShape(Rectangle())
                    .onHover { hover = $0 ? value : nil }
                    .onTapGesture { withAnimation(.easeInOut(duration: 0.15)) { selection = value } }
            }
        }
        .padding(2)
        .background(RoundedRectangle(cornerRadius: Dims.tileRadius).fill(Palette.cardStrong))
    }
}

/// The search field of a list (Android's search, a Mac field). A leading space can't be typed (it never helps
/// a substring match); interior spaces stay — the rule every search in the app shares.
struct SearchField: View {
    @Binding var text: String
    var placeholder: String = L("search")
    @Environment(\.snapshot) private var snapshot

    var body: some View {
        let stripped = Binding(get: { text }, set: { text = String($0.drop(while: \.isWhitespace)) })
        HStack(spacing: 5) {
            Image(systemName: "magnifyingglass").font(.system(size: 11)).foregroundStyle(Palette.textSecondary)
            PlainField(placeholder: placeholder, text: stripped, snapshot: snapshot).font(Fonts.body(12))
            if !text.isEmpty {
                Image(systemName: "xmark.circle.fill").font(.system(size: 11)).foregroundStyle(Palette.textMuted)
                    // An 11 pt glyph is too small to aim at: the padding grows the tap area,
                    // the negative one hands the room back so the row's layout doesn't move.
                    .padding(4)
                    .contentShape(Rectangle())
                    .onTapGesture { text = "" }
                    .padding(-4)
            }
        }
        .padding(.horizontal, 7)
        .frame(height: Dims.searchH)
        .background(RoundedRectangle(cornerRadius: Dims.cornerRadius).fill(Palette.cardStrong.opacity(0.7)))
    }
}

/// A screen's top bar: the title in the serif, a grey line after it, the screen's controls on the right, a hairline below.
struct ScreenHeader<Trailing: View>: View {
    var title: String
    var subtitle: String? = nil
    @ViewBuilder var trailing: Trailing

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 8) {
                Text(title).font(Fonts.display(19, bold: true)).foregroundStyle(Palette.text).textSelection(.enabled)
                if let subtitle { Text(subtitle).font(Fonts.body(12)).foregroundStyle(Palette.textSecondary).lineLimit(1).textSelection(.enabled) }
                Spacer(minLength: 8)
                trailing
            }
            .padding(.leading, 20).padding(.trailing, 14)
            .frame(height: Dims.headerH)
            // The window's drag strip behind the content can't reach here — the pages' own
            // hit regions claim the header first — so the header carries a surface of its
            // own in its dead areas, like the sidebar's title row (owner, 2026-11-09).
            .background { WindowDrag(menus: nil) }
            Hairline()
        }
    }
}

struct Hairline: View {
    var vertical = false
    var body: some View {
        Rectangle().fill(Palette.outline).frame(width: vertical ? 1 : nil, height: vertical ? nil : 1)
    }
}

/// The hairline above a form row (the box's own border covers the first row's), shared by `FormRow` and the
/// settings' rows as data (`RowView`).
struct RowDivider: View {
    var body: some View {
        Rectangle().fill(Palette.outline.opacity(0.7)).frame(height: 1).padding(.leading, 12)
    }
}

/// The chevron that opens a list under its row: points right folded, down unfolded.
struct FoldChevron: View {
    var open: Bool
    var color: Color = Palette.textSecondary

    var body: some View {
        Image(systemName: "chevron.right").font(.system(size: 9, weight: .bold)).foregroundStyle(color)
            .rotationEffect(.degrees(open ? 90 : 0))
    }
}

/// The chrome every dialog over the window shares (the MenuLayer dims the rest): the title in the serif,
/// the content under it, the card's padding and width.
struct AppDialog<Content: View>: View {
    var title: String
    var width: CGFloat
    var spacing: CGFloat = 10
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: spacing) {
            Text(title).font(Fonts.display(19, bold: true)).foregroundStyle(Palette.text).lineLimit(1).textSelection(.enabled)
            content
        }
        .padding(Dims.dialogPad)
        .frame(width: width)
    }
}

extension View {
    /// The pale rounded box of a monospaced text (the Add dialog's editor, a zone's lists, the settings' site
    /// lists, the share text): a hairline border, a fill the caller picks.
    func monoChrome(fill: Color = Palette.background) -> some View {
        background(RoundedRectangle(cornerRadius: Dims.cornerRadius).fill(fill))
            .overlay(RoundedRectangle(cornerRadius: Dims.cornerRadius).strokeBorder(Palette.textMuted.opacity(0.55), lineWidth: 1))
    }
}

/// The "Ping all" face both buttons share (the window's and the menu-bar panel's): the running check's ring
/// and count, the symbol and the words else.
struct PingAllLabel: View {
    var scan: ScanProgress?
    var iconSize: CGFloat = 11

    var body: some View {
        HStack(spacing: 5) {
            if let scan {
                ProgressRing(value: Double(scan.done) / Double(max(scan.total, 1))).frame(width: 12, height: 12)
                Text("\(scan.done)/\(scan.total)").monospacedDigit()
            } else {
                Image(systemName: "arrow.clockwise").font(.system(size: iconSize, weight: .medium)).foregroundStyle(Palette.textSecondary)
                Text(L("ping_all"))
            }
        }
    }
}

/// A grouped form: rows with a hairline between them in a bordered box (the Mac's settings / inspector look). Each row
/// draws the line above itself; the first one falls on the box's own border. (Shifting the rows up to hide it made the
/// first row's content sit 2 pt high.)
struct FormBox<Content: View>: View {
    @ViewBuilder var content: Content

    var body: some View {
        VStack(spacing: 0) { content }
            .clipShape(RoundedRectangle(cornerRadius: Dims.cardRadius))
            .background(RoundedRectangle(cornerRadius: Dims.cardRadius).fill(Palette.background))
            .overlay(RoundedRectangle(cornerRadius: Dims.cardRadius).strokeBorder(Palette.outline, lineWidth: 1))
    }
}

/// A form row: the label in grey on the left, the value (text or a control) on the right.
struct FormRow<Value: View>: View {
    var label: String
    @ViewBuilder var value: Value

    var body: some View {
        HStack(spacing: 10) {
            Text(label).font(Fonts.body()).foregroundStyle(Palette.textSecondary).lineLimit(2).fixedSize(horizontal: false, vertical: true).layoutPriority(1)
            Spacer(minLength: 8)
            value.font(Fonts.body()).foregroundStyle(Palette.text)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 5)
        .frame(minHeight: 30)
        .overlay(alignment: .top) { RowDivider() }
    }
}

/// A form row whose value is an editable field (the server's name, its country, a device id).
/// Commits on focus lost as well as on Return — a click elsewhere used to leave the typed text
/// uncommitted. The commits are idempotent (a save checks the value changed), so a Return that
/// also moves the focus doesn't run one twice.
struct FormField: View {
    var label: String
    @Binding var text: String
    var placeholder: String
    var onCommit: () -> Void
    @Environment(\.snapshot) private var snapshot
    @FocusState private var focused: Bool

    var body: some View {
        FormRow(label: label) {
            PlainField(placeholder: placeholder, text: $text, snapshot: snapshot, onCommit: onCommit)
                .focused($focused)
                .onChange(of: focused) { _, on in if !on { onCommit() } }
                .modifier(FieldBezel())
                .frame(maxWidth: 150)
        }
    }
}

/// A borderless text field; in snapshots its text drawn plainly (ImageRenderer can't draw the AppKit field).
/// The commit is Return-only here: whether leaving the field also commits is the caller's pick, added
/// where it means "keep the edit" (`FormField`'s focus watch, `NumberField`'s own). A field whose
/// commit *does* something must not take it — the Add dialog's "new group" would create the group
/// when the user clicked away to cancel, and `NameField`'s popup would rename on a dismiss.
struct PlainField: View {
    var placeholder: String
    @Binding var text: String
    var snapshot = false
    var onCommit: () -> Void = {}

    var body: some View {
        if snapshot {
            Text(text.isEmpty ? placeholder : text).foregroundStyle(text.isEmpty ? Palette.textMuted : Palette.text).lineLimit(1)
                .frame(maxWidth: .infinity, alignment: .leading)
        } else {
            TextField(placeholder, text: $text).textFieldStyle(.plain).onSubmit(onCommit)
        }
    }
}

/// The frame of an editable field (a Mac text field's bezel), so a value that can be typed into reads differently
/// from the plain values of a table.
struct FieldBezel: ViewModifier {
    func body(content: Content) -> some View {
        content
            .font(Fonts.body(12.5))
            .padding(.horizontal, 6)
            .frame(height: 22)
            .background(RoundedRectangle(cornerRadius: 5).fill(Palette.background))
            .overlay(RoundedRectangle(cornerRadius: 5).strokeBorder(Palette.textMuted.opacity(0.55), lineWidth: 1))
    }
}

/// The check of a chosen item: Android's (Material) check, more upright than the system symbol.
struct CheckMark: View {
    var color: Color
    var size: CGFloat = 11

    var body: some View {
        Canvas { ctx, s in
            var p = Path()
            p.move(to: CGPoint(x: s.width * 0.16, y: s.height * 0.54))
            p.addLine(to: CGPoint(x: s.width * 0.40, y: s.height * 0.78))
            p.addLine(to: CGPoint(x: s.width * 0.86, y: s.height * 0.26))
            ctx.stroke(p, with: .color(color), style: StrokeStyle(lineWidth: s.width * 0.16, lineCap: .round, lineJoin: .round))
        }
        .frame(width: size, height: size)
    }
}

/// The ping's colour as a dot (lists, figures).
struct PingDot: View {
    var ms: Int?
    var size: CGFloat = 8

    var body: some View {
        Circle().fill(Palette.ping(ms)).frame(width: size, height: size)
            .overlay(Circle().strokeBorder(Color.black.opacity(0.12), lineWidth: 0.5))
    }
}

/// The figure `PingText` shows, as a value for the rows and tooltips that compose it themselves.
func pingText(_ s: ServerItem) -> String {
    s.ms.map { L("ms", $0) } ?? (s.checked ? L("legend_down") : "—")
}

/// A server's ping as the row's figure, as Android's server rows: "84 ms", "no reply" for a check it
/// didn't answer, "—" when it was never checked, including a manual limited-network check.
/// `light` is the row on the accent fill (the server in use),
/// `dim` a hidden server's row.
struct PingText: View {
    var server: ServerItem
    var light = false
    var dim = false

    var body: some View {
        Text(pingText(server))
            .font(Fonts.display(13)).monospacedDigit()
            .foregroundStyle(light ? Palette.onAccent : server.ms == nil || dim ? Palette.textSecondary : Palette.text)
            .frame(minWidth: Dims.pingW, alignment: .trailing)
    }
}

/// The same single-server check on Main and in Groups, including WDTT.
struct ServerProbeButton: View {
    var model: MainModel
    var serverId: String

    var body: some View {
        let checking = model.pendingIds.contains(serverId)
        MacButton(title: L(checking ? "action_probing" : "action_probe"),
                  symbol: checking ? "hourglass" : "waveform.path.ecg") {
            model.backend?.runAction("probe_one:\(serverId)")
        }
        .disabled(checking)
    }
}

/// The limited-network server's mark (Android `ui/TowerIcons`): the cell tower, and the same tower
/// without its signal arcs — the "silent" one — for a check that got no reply. The symbol's
/// `variableValue` is the arc count, so 0 draws the bare mast like Android's hand-cut icon.
struct TowerMark: View {
    var silent = false
    var color: Color = StateTone.limited.strong

    var body: some View {
        Image(systemName: "antenna.radiowaves.left.and.right", variableValue: silent ? 0 : nil)
            .font(.system(size: 10.5, weight: .semibold))
            .foregroundStyle(color)
    }
}

/// The leading part every server row shares (the main list, the groups outline): the flag, the display
/// name and a grey detail — "group · protocol" in the list, the protocol alone in the outline, where a
/// limited-network server also carries the tower mark (`mark`). `light` is the row on the accent fill
/// (the server in use); `fade` dims it — `.down` for a checked server that gave no reply, `.dim` for a
/// hidden one. What follows (marks, menus, the ping) stays the row's own, as does the spacing — the
/// children land in the row's stack as if written there.
struct ServerLine: View {
    /// How a row fades a server: a check it didn't answer (the list) vs. a hidden one (the outline).
    enum Fade { case none, down, dim }

    var model: MainModel
    var server: ServerItem
    /// The grey text after the name: "group · protocol" or the protocol alone.
    var detail: String
    var light = false
    var fade: Fade = .none
    /// The limited-network tower before the detail (the outline; the main list has none).
    var mark = false

    var body: some View {
        FlagView(code: server.country, height: 13).opacity(fade == .none ? 1 : fade == .down ? 0.5 : 0.4)
        Text(model.displayName(server)).font(Fonts.body(13, bold: light))
            .foregroundStyle(light ? Palette.onAccent : fade == .down ? Palette.textSecondary : fade == .dim ? Palette.textMuted : Palette.text)
            .lineLimit(1).layoutPriority(1)
        if mark {
            TowerMark(silent: fade == .down, color: light ? Palette.onAccent : StateTone.limited.strong)
        }
        Text(detail).font(Fonts.body(12))
            .foregroundStyle(light ? Palette.onAccent.opacity(0.85) : Palette.textSecondary).lineLimit(1)
        // An unprotected link says so in the row (Android appends the `ins_*` word to the same
        // subtitle): "not encrypted" outranks "certificate not checked" when both apply.
        if !server.insecure.isEmpty {
            Text("· \(server.insecure.contains(.noEncryption) ? InsecureIssue.noEncryption.label : InsecureIssue.noCertCheck.label)")
                .font(Fonts.body(12))
                .foregroundStyle(light ? Palette.onAccent : Palette.danger).lineLimit(1)
        }
    }
}

/// A small switch in the accent (the system's, drawn plainly in snapshots where AppKit controls can't be rendered).
struct AppSwitch: View {
    @Binding var isOn: Bool
    @Environment(\.snapshot) private var snapshot

    var body: some View {
        if snapshot {
            Capsule().fill(isOn ? Palette.accent : Palette.cardStrong).frame(width: 26, height: 15)
                .overlay(Circle().fill(.white).padding(1.5).shadow(color: .black.opacity(0.2), radius: 0.5),
                         alignment: isOn ? .trailing : .leading)
        } else {
            Toggle("", isOn: $isOn).toggleStyle(.switch).controlSize(.mini).labelsHidden().tint(Palette.accent)
        }
    }
}

/// A scrolling list whose hover highlight is worked out the way the system's menus do it: from where the pointer is in
/// the visible part plus how far the content is scrolled, both read in the same frame, so the highlight stays under a
/// still pointer while the list scrolls. (Rows reporting their own enter / exit, as tracking areas do, lag behind the
/// scrolling and flicker.) `content` gets the pointer's height in the content's coordinates, nil when it is outside.
/// macOS 14 has no synchronous scroll offset: there the highlight hides while the wheel turns.
struct HoverScroll<Content: View>: View {
    @ViewBuilder var content: (CGFloat?) -> Content
    @State private var pointer: CGFloat?
    @State private var offset: CGFloat = 0
    @State private var monitor: Any?
    /// The window the list lives in: the macOS-14 fallback's monitor must tell its wheel events
    /// from the app's other windows'.
    @State private var window: NSWindow?

    var body: some View {
        let scroll = ScrollView { content(pointer.map { $0 + offset }) }
            .onContinuousHover { phase in
                if case .active(let at) = phase { pointer = at.y } else { pointer = nil }
            }
        if #available(macOS 15.0, *) {
            // Past either edge the offset overshoots while the list rubber-bands: a state write every frame
            // re-created the content mid-gesture and the bounce snapped instead of gliding back (owner,
            // 2026-11-07 — the top edge jerked). Clamped to the content's range the transform reports no
            // change during the bounce, so nothing re-renders; the hover just maps as if the content were
            // home, which it nearly is again.
            scroll.onScrollGeometryChange(for: CGFloat.self) { g in
                let end = g.contentSize.height - g.containerSize.height + g.contentInsets.top + g.contentInsets.bottom
                return min(max(g.contentOffset.y + g.contentInsets.top, 0), max(end, 0))
            } action: { _, y in
                offset = y
            }
        } else {
            scroll
                .background(WindowProbe { window = $0 })
                .onAppear {
                    monitor = NSEvent.addLocalMonitorForEvents(matching: .scrollWheel) { event in
                        // A local monitor sees the app's every wheel event — the menu bar
                        // panel's too: only a turn inside this list's own window hides the
                        // highlight.
                        if event.window === window { pointer = nil }
                        return event
                    }
                }
                .onDisappear { if let monitor { NSEvent.removeMonitor(monitor) } }
        }
    }
}

/// The window a view sits in, handed out once the view attaches — for code that must tell an
/// event in the view's own window from one anywhere in the app (a local monitor sees them all).
private struct WindowProbe: NSViewRepresentable {
    var found: @MainActor (NSWindow?) -> Void

    func makeNSView(context: Context) -> ProbeView {
        let view = ProbeView()
        view.found = found
        return view
    }

    func updateNSView(_ view: ProbeView, context: Context) {
        view.found = found
    }

    /// `viewDidMoveToWindow` covers both the first attach (which can come later than the
    /// representable's creation) and the detach — like `WindowLifeView` does for the app window.
    final class ProbeView: NSView {
        var found: (@MainActor (NSWindow?) -> Void)?

        override func viewDidMoveToWindow() {
            MainActor.assumeIsolated { found?(window) }
        }
    }
}

/// A list that scrolls live and lies flat in a snapshot — ImageRenderer draws no ScrollView content.
/// `clipped` (the default) takes the space offered and cuts the rest, for a fixed-size window a plainly
/// laid-out list would grow; unclipped lays the content out plainly, for a window that sizes itself to
/// the content (the menu bar panel). `height` fixes the live ScrollView's height; nil takes what's given.
struct SnapshotScroll<Content: View>: View {
    var clipped = true
    var height: CGFloat? = nil
    @ViewBuilder var content: Content
    @Environment(\.snapshot) private var snapshot

    var body: some View {
        if snapshot {
            if clipped { SnapshotClip { content } } else { content }
        } else {
            ScrollView { content }.frame(height: height)
        }
    }
}

/// The flat half of `SnapshotScroll` on its own, for a list whose live half isn't a plain ScrollView
/// (`HoverScroll`, a scroll target): the content at the offered width, the rest clipped away.
struct SnapshotClip<Content: View>: View {
    @ViewBuilder var content: Content

    var body: some View {
        GeometryReader { g in content.frame(width: g.size.width, alignment: .top) }.clipped()
    }
}

/// Each row's frame in the list's content, so the row under the pointer can be found when rows differ in height.
struct RowFrames: PreferenceKey {
    static let defaultValue: [String: CGRect] = [:]
    static func reduce(value: inout [String: CGRect], nextValue: () -> [String: CGRect]) {
        value.merge(nextValue()) { $1 }
    }
}

extension View {
    /// Reports this row's frame in the named content space (see `RowFrames`).
    func rowFrame(_ id: String, in space: String) -> some View {
        background(GeometryReader { g in Color.clear.preference(key: RowFrames.self, value: [id: g.frame(in: .named(space))]) })
    }
}

/// Errors start compact, but their full text is always one click away and selectable.
struct ExpandableError: View {
    var message: String
    @State private var expanded = false

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Button { expanded.toggle() } label: {
                HStack(alignment: .top, spacing: 5) {
                    Image(systemName: expanded ? "chevron.down" : "chevron.right")
                        .font(.system(size: 9, weight: .semibold)).frame(width: 10, height: 14)
                    Text(expanded ? L("err_title") : message).lineLimit(2)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            if expanded {
                Text(message).textSelection(.enabled)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .font(Fonts.body(11.5)).foregroundStyle(Palette.danger)
        .onChange(of: message) { expanded = false }
    }
}
