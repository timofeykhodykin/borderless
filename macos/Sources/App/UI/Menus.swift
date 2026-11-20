import AppKit
import SwiftUI

/// The app's own popup menu, as on Android (`ui/Menus.kt` `AppMenu` / `AppMenuItem`) instead of the system's: a
/// rounded card on the background colour with a hairline border and a soft shadow; items with an icon, a second line,
/// a check or an arrow to a second level; dangerous ones in red. Mac-sized: 13 pt rows, the hovered one in the accent. One menu at a time, drawn over the
/// window by [MenuLayer] at the point it was opened from.
struct MenuItem: Identifiable {
    let id = UUID()
    var title: String
    var symbol: String? = nil
    var sub: String? = nil
    var danger = false
    var selected = false
    /// A second level (Android's "Move to group" style): the item opens it in place.
    var submenu: [MenuItem]? = nil
    var action: () -> Void = {}
}

enum PopupPlacement { case below, trailing }

@MainActor @Observable
final class MenuState {
    var items: [MenuItem] = []
    /// Where the menu hangs from, in the window's "shell" coordinate space.
    var anchor: CGPoint = .zero
    /// The first level while a second one is shown (for its "back" row).
    var parent: [MenuItem]?
    /// A popup with content of its own instead of items (the country list with its search), in the same card.
    var popup: AnyView?
    /// Where the popup hangs from — separate from `anchor` because a menu can fly out of the popup (the
    /// countries' Sort) and then hangs from a point of its own while the popup stays under it.
    var popupAnchor: CGPoint = .zero
    var popupPlacement: PopupPlacement = .below
    /// A dialog in the middle of the window over a dimmed screen (adding servers, sharing); menus open over it too.
    var dialog: AnyView?
    /// The placed cards' frames in "shell" coordinates (`Place` writes them): the scrim lets the hits
    /// on the cards through while swallowing everything else — wheel and right clicks too.
    var menuRect: CGRect = .zero
    var popupRect: CGRect = .zero
    /// The frames of the views the open layers were opened from — a re-click on one's own anchor is the
    /// layer's toggle, not a scrim tap, so the scrim lets a left click there through.
    var anchorRect: CGRect = .zero
    var popupAnchorRect: CGRect = .zero
    /// The countries popup's working state, hoisted out of its @State: a menu flown out of the popup
    /// (its Sort) used to destroy the popup and the pick wrote into the dead view's @State — sorting
    /// was dead. Here it survives the menu, and a re-opened popup keeps the last picks.
    var countriesQuery = ""
    var countriesSort: CountriesPopup.Sort = .name
    var countriesShowEmpty = false
    /// The server list's search text (Android keeps per-screen list state): `ServersColumn` dies on
    /// every `switch model.section`, so a @State in it silently cleared a typed search.
    var serversQuery = ""
    var open: Bool { !items.isEmpty || popup != nil }
    /// The frame of the view the open layer hangs from — `.zero` while nothing is open.
    var openAnchorRect: CGRect { !items.isEmpty ? anchorRect : popup != nil ? popupAnchorRect : .zero }

    func show(dialog: AnyView) {
        close()
        self.dialog = dialog
    }

    func closeDialog() {
        close()
        dialog = nil
    }

    /// `from` is the anchor view's own frame in "shell" coordinates (a re-click on it toggles).
    /// `keepingPopup` keeps an open popup under the menu — used when the menu flies out of the popup
    /// itself (the countries' Sort): the pick then closes back to the still-open popup.
    func show(_ items: [MenuItem], at point: CGPoint, from rect: CGRect = .zero, keepingPopup: Bool = false) {
        parent = nil
        if !keepingPopup { popup = nil; popupRect = .zero; popupAnchorRect = .zero }
        anchor = point
        anchorRect = rect
        menuRect = .zero
        self.items = items
    }

    func show(popup: AnyView, at point: CGPoint, from rect: CGRect = .zero, placement: PopupPlacement = .below) {
        parent = nil
        items = []
        anchorRect = .zero
        menuRect = .zero
        popupRect = .zero
        popupAnchor = point
        popupPlacement = placement
        popupAnchorRect = rect
        self.popup = popup
    }

    /// The menu level only: a popup the menu flew out of stays open underneath.
    func closeMenu() {
        items = []
        parent = nil
        menuRect = .zero
        anchorRect = .zero
    }

    func close() {
        closeMenu()
        popup = nil
        popupRect = .zero
        popupAnchorRect = .zero
    }
}

/// Draws the open menu over everything and closes it on a click elsewhere or Escape.
struct MenuLayer: View {
    var menus: MenuState
    @Environment(\.snapshot) private var snapshot

    var body: some View {
        GeometryReader { geo in
            if let dialog = menus.dialog {
                Color.black.opacity(0.18)
                    .contentShape(Rectangle())
                    .onTapGesture { menus.closeDialog() }
                dialog
                    .background(RoundedRectangle(cornerRadius: 12).fill(Palette.background))
                    .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(Palette.outline, lineWidth: 1))
                    .shadow(color: .black.opacity(0.2), radius: 24, y: 8)
                    .frame(width: geo.size.width, height: geo.size.height)
                    .onExitCommand { menus.closeDialog() }
                    .transition(.opacity.combined(with: .scale(scale: 0.98)))
            }
            if menus.open {
                // A real view, not a SwiftUI colour: that ate left taps only, and the wheel kept
                // scrolling and right clicks kept opening menus on the content under it. Snapshots
                // get the colour — ImageRenderer can't draw the view, and gestures don't matter there.
                Group {
                    if snapshot {
                        Color.black.opacity(0.001)
                            .contentShape(Rectangle())
                            .onTapGesture { menus.close() }
                    } else {
                        MenuScrim(through: [menus.popup != nil ? menus.popupRect : nil,
                                            !menus.items.isEmpty ? menus.menuRect : nil].compactMap { $0 },
                                  pass: menus.openAnchorRect) { menus.close() }
                            .frame(maxWidth: .infinity, maxHeight: .infinity)
                    }
                }
                .onExitCommand { menus.close() }
                if let popup = menus.popup {
                    popup.modifier(CardLook())
                        .modifier(Place(anchor: menus.popupAnchor, bounds: geo.size,
                                        alignBottom: menus.popupPlacement == .trailing) { menus.popupRect = $0 })
                        // A tap in a dead area of the card is an outside click: dismiss (the card
                        // covers part of the sidebar, and presses there must not look dead — owner,
                        // 2026-11-09). The claim sits in the card's background, behind its content:
                        // the rows and controls drawn in front keep their presses — a gesture wrapped
                        // around the whole card had shadowed them.
                        .background { Color.clear.contentShape(Rectangle()).onTapGesture { menus.close() } }
                        .transition(.opacity.combined(with: .scale(scale: 0.97, anchor: .topLeading)))
                }
                if !menus.items.isEmpty {
                    // A menu flown out of a popup covers the popup with a scrim of its own: a click
                    // outside the card closes the menu level only, back to the popup (the countries'
                    // Sort opens this way). Without it the popup's rows stayed tappable under the menu.
                    if menus.popup != nil, !snapshot {
                        MenuScrim(through: [menus.menuRect], pass: .zero) { menus.closeMenu() }
                            .frame(maxWidth: .infinity, maxHeight: .infinity)
                    }
                    // With second lines the card has a set width, so their wrapped height is known before it is drawn
                    // (an ideal size counts them as one line); without, it takes its natural width.
                    let wide = menus.items.contains { $0.sub != nil }
                    MenuCard(menus: menus, maxHeight: max(80, geo.size.height - 16))
                        .frame(width: wide ? 290 : nil)
                        .fixedSize(horizontal: !wide, vertical: true)
                        .modifier(Place(anchor: menus.anchor, bounds: geo.size) { menus.menuRect = $0 })
                        // A dead-area tap dismisses this level only — like the second scrim over a
                        // popup it folds back to the popup, not all the way out.
                        .background { Color.clear.contentShape(Rectangle()).onTapGesture { menus.closeMenu() } }
                        .transition(.opacity.combined(with: .scale(scale: 0.97, anchor: .topLeading)))
                }
            }
        }
        .animation(.easeOut(duration: 0.12), value: menus.open)
        .animation(.easeOut(duration: 0.12), value: menus.items.isEmpty)
        .animation(.easeOut(duration: 0.15), value: menus.dialog != nil)
    }
}

/// Puts the card at the anchor, kept inside the window.
private struct Place: ViewModifier {
    var anchor: CGPoint
    var bounds: CGSize
    var alignBottom = false
    /// The card's placed frame in "shell" coordinates — the scrims let hits there through.
    var placed: (CGRect) -> Void = { _ in }
    @State private var size: CGSize = .zero

    func body(content: Content) -> some View {
        content
            // The size arrives a frame late; until then the offset can't clamp to the bottom or the
            // right edge and the card would flash at the raw anchor — hidden instead.
            .opacity(size == .zero ? 0 : 1)
            .background(GeometryReader { g in
                Color.clear.onAppear { size = g.size }.onChange(of: g.size) { _, s in size = s }
            })
            .offset(x: min(max(8, anchor.x), bounds.width - size.width - 8),
                    y: alignBottom ? max(8, min(anchor.y - size.height, bounds.height - size.height - 8))
                        : anchor.y + size.height + 8 > bounds.height ? max(8, anchor.y - size.height) : anchor.y)
            .background(GeometryReader { g in
                Color.clear.onAppear { placed(g.frame(in: .named("shell"))) }
                    .onChange(of: g.frame(in: .named("shell"))) { _, f in placed(f) }
            })
    }
}

/// A ScrollView that hugs its content up to `max` and scrolls past it — a fixed `.frame(height:)`
/// leaves dead space under a short list, and an uncapped menu runs past the window where `Place`
/// can't hold it in. Snapshots lay the content plainly: ImageRenderer draws no ScrollView content.
struct HugScroll<Content: View>: View {
    var max: CGFloat
    @ViewBuilder var content: Content
    @Environment(\.snapshot) private var snapshot
    /// The content's measured height; `max` until known, so a long list — the common case — is right
    /// at once and a short one only pulls the card in a frame later.
    @State private var height: CGFloat = .infinity

    var body: some View {
        if snapshot {
            content
        } else {
            ScrollView {
                content.background(GeometryReader { g in
                    Color.clear.onAppear { height = g.size.height }
                        .onChange(of: g.size.height) { _, h in height = h }
                })
            }
            .frame(height: min(height, max))
        }
    }
}

private struct MenuCard: View {
    var menus: MenuState
    /// The window height minus a margin — a menu longer than that scrolls instead of running off.
    var maxHeight: CGFloat

    var body: some View {
        HugScroll(max: maxHeight) {
            VStack(alignment: .leading, spacing: 0) {
                if let parent = menus.parent {
                    MenuRow(item: MenuItem(title: L("back"), symbol: "chevron.left")) {
                        menus.items = parent
                        menus.parent = nil
                    }
                }
                ForEach(menus.items) { item in
                    MenuRow(item: item) {
                        if let sub = item.submenu {
                            menus.parent = menus.items
                            menus.items = sub
                        } else {
                            // A menu flown out of a popup closes back to the popup, which is still
                            // there under it — a pick is the menu's dismissal, not the popup's.
                            menus.closeMenu()
                            item.action()
                        }
                    }
                }
            }
            .padding(5)
        }
        .frame(minWidth: 180)
        .modifier(CardLook())
    }
}

/// The menu's card: background, hairline border, soft shadow.
struct CardLook: ViewModifier {
    func body(content: Content) -> some View {
        content
            .background(RoundedRectangle(cornerRadius: 10).fill(Palette.background))
            .overlay(RoundedRectangle(cornerRadius: 10).strokeBorder(Palette.outline, lineWidth: 1))
            .shadow(color: .black.opacity(0.14), radius: 12, y: 5)
    }
}

private struct MenuRow: View {
    var item: MenuItem
    var action: () -> Void
    @State private var hover = false

    var body: some View {
        // onAccent goes near-black in the dark schemes — unreadable on the muted red of a hovered
        // danger row, so the text on that fill is plain white instead.
        let onFill = item.danger ? Color.white : Palette.onAccent
        let tint = hover ? onFill : item.danger ? Palette.danger : Palette.textSecondary
        HStack(alignment: .center, spacing: 8) {
            if let symbol = item.symbol {
                Image(systemName: symbol).font(.system(size: 12, weight: .regular)).foregroundStyle(tint)
                    .frame(width: 16, height: 16)
            }
            VStack(alignment: .leading, spacing: 1) {
                Text(item.title).font(Fonts.body(13, bold: item.sub != nil))
                    .foregroundStyle(hover ? onFill : item.danger ? Palette.danger : Palette.text)
                if let sub = item.sub {
                    Text(sub).font(Fonts.body(11)).foregroundStyle(hover ? onFill.opacity(0.85) : Palette.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            if item.selected {
                CheckMark(color: hover ? onFill : Palette.accentStrong)
            } else if item.submenu != nil {
                Image(systemName: "chevron.right").font(.system(size: 10, weight: .semibold))
                    .foregroundStyle(hover ? onFill : Palette.textMuted)
            }
        }
        .padding(.horizontal, 8)
        .padding(.vertical, item.sub == nil ? 4 : 6)
        .frame(minWidth: 170)
        .background(RoundedRectangle(cornerRadius: 5).fill(hover ? (item.danger ? Palette.danger : Palette.accent) : .clear))
        .contentShape(RoundedRectangle(cornerRadius: 5))
        .onHover { hover = $0 }
        .onTapGesture(perform: action)
    }
}

// MARK: Opening

extension View {
    /// Right click opens [items] at the pointer (the Mac's long press). Left clicks and hover pass through untouched.
    func appContextMenu(_ menus: MenuState, _ items: @escaping () -> [MenuItem]) -> some View {
        modifier(ContextMenuModifier(menus: menus) { _ in items() })
    }

    /// The same with the click's place in the view's own coordinates handed to the builder — one catcher over
    /// a grid lets the grid itself say what was hit (a catcher per cell is an NSView and a GeometryReader each).
    func appContextMenu(_ menus: MenuState, at items: @escaping (CGPoint) -> [MenuItem]) -> some View {
        modifier(ContextMenuModifier(menus: menus, items: items))
    }

    /// A left click opens [items] under the view (chips and "⋮" buttons).
    func appMenuOnClick(_ menus: MenuState, _ items: @escaping () -> [MenuItem]) -> some View {
        modifier(ClickMenuModifier(menus: menus, items: items, popup: nil))
    }

    /// A left click opens a popup of its own content under the view (in the menu's card).
    func appPopupOnClick(_ menus: MenuState, requested: Binding<Bool>? = nil, placement: PopupPlacement = .below,
                         _ popup: @escaping () -> AnyView) -> some View {
        modifier(ClickMenuModifier(menus: menus, items: { [] }, popup: popup, requested: requested,
                                   popupPlacement: placement))
    }
}

private struct ContextMenuModifier: ViewModifier {
    var menus: MenuState
    /// The click's local point in, the menu's items out (an empty list opens nothing).
    var items: (CGPoint) -> [MenuItem]
    @State private var frame: CGRect = .zero
    @Environment(\.snapshot) private var snapshot

    func body(content: Content) -> some View {
        content
            .background(GeometryReader { g in
                Color.clear.onAppear { frame = g.frame(in: .named("shell")) }
                    .onChange(of: g.frame(in: .named("shell"))) { _, f in frame = f }
            })
            // ImageRenderer can't draw AppKit views (a placeholder would cover the content): none in snapshots.
            .overlay(snapshot ? nil : RightClickCatcher { local in
                let items = items(local)
                if !items.isEmpty { menus.show(items, at: CGPoint(x: frame.minX + local.x, y: frame.minY + local.y)) }
            })
    }
}

private struct ClickMenuModifier: ViewModifier {
    var menus: MenuState
    var items: () -> [MenuItem]
    var popup: (() -> AnyView)?
    /// External opens use this view's measured anchor too, including after navigation creates it.
    var requested: Binding<Bool>? = nil
    var popupPlacement: PopupPlacement = .below
    @State private var frame: CGRect = .zero

    func body(content: Content) -> some View {
        content
            .background(GeometryReader { g in
                Color.clear.onAppear { updateFrame(g.frame(in: .named("shell"))) }
                    .onChange(of: g.frame(in: .named("shell"))) { _, f in updateFrame(f) }
            })
            .onChange(of: requested?.wrappedValue) { openIfRequested() }
            .onTapGesture {
                // The scrim lets a left click through only over the open layer's own anchor, so a tap
                // landing here while open is this anchor's — its toggle. (The countries' Sort button
                // is inside the popup, above the scrim: it opens a menu, and `openAnchorRect` — the
                // globe's frame — is not its frame.)
                if menus.open, frame == menus.openAnchorRect { menus.close(); return }
                let at = CGPoint(x: frame.minX, y: frame.maxY + 6)
                if let popup {
                    menus.show(popup: popup(), at: popupPoint(frame), from: frame, placement: popupPlacement)
                } else {
                    // An anchor tappable while a popup shows can only be inside it (the scrim covers
                    // every other) — the popup stays under the menu it opens.
                    menus.show(items(), at: at, from: frame, keepingPopup: menus.popup != nil)
                }
            }
    }

    private func updateFrame(_ rect: CGRect) {
        // Keep an already-open popup attached when the window is resized or first laid out.
        if popup != nil, menus.popup != nil, menus.popupAnchorRect == frame, !frame.isEmpty {
            menus.popupAnchorRect = rect
            menus.popupAnchor = popupPoint(rect)
        }
        frame = rect
        openIfRequested()
    }

    private func openIfRequested() {
        guard requested?.wrappedValue == true, !frame.isEmpty, let popup else { return }
        requested?.wrappedValue = false
        menus.show(popup: popup(), at: popupPoint(frame), from: frame, placement: popupPlacement)
    }

    private func popupPoint(_ rect: CGRect) -> CGPoint {
        switch popupPlacement {
        case .below: CGPoint(x: rect.minX, y: rect.maxY + 6)
        case .trailing: CGPoint(x: rect.maxX + 8, y: rect.maxY)
        }
    }
}

/// An AppKit view that takes part in hit testing only for right clicks, reporting where (top-left origin).
/// Every rightMouseDown in its frame is claimed: a native context menu under it — an editable child's
/// own, say — can never open, so wrap only rows that hold no editable children.
private struct RightClickCatcher: NSViewRepresentable {
    var onRightClick: (CGPoint) -> Void

    func makeNSView(context: Context) -> CatcherView {
        let v = CatcherView()
        v.onRightClick = onRightClick
        return v
    }

    func updateNSView(_ view: CatcherView, context: Context) {
        view.onRightClick = onRightClick
    }

    final class CatcherView: NSView {
        var onRightClick: ((CGPoint) -> Void)?

        override func hitTest(_ point: NSPoint) -> NSView? {
            let type = NSApp.currentEvent?.type
            return type == .rightMouseDown || type == .rightMouseUp ? super.hitTest(point) : nil
        }

        override func rightMouseDown(with event: NSEvent) {
            let p = convert(event.locationInWindow, from: nil)
            onRightClick?(CGPoint(x: p.x, y: isFlipped ? p.y : bounds.height - p.y))
        }
    }
}

/// The full-window view under an open menu or popup — the pattern of `RightClickCatcher`, inverted:
/// it claims every mouse event so the wheel can't scroll and right clicks can't open menus on the
/// content under it. `through` are the placed cards' frames (their hits are theirs), `pass` the open
/// layer's anchor frame for left clicks — a re-click there reaches the anchor and toggles its menu.
private struct MenuScrim: NSViewRepresentable {
    /// The cards' frames in "shell" coordinates; hits inside them fall through.
    var through: [CGRect]
    /// The anchor's frame; left clicks inside it fall through so the anchor can toggle its menu.
    var pass: CGRect
    var onClick: () -> Void

    func makeNSView(context: Context) -> ScrimView {
        let v = ScrimView()
        v.onClick = onClick
        return v
    }

    func updateNSView(_ view: ScrimView, context: Context) {
        view.through = through
        view.pass = pass
        view.onClick = onClick
    }

    final class ScrimView: NSView {
        var through: [CGRect] = []
        var pass: CGRect = .zero
        var onClick: (() -> Void)?

        /// `point` is in the superview's coordinates; `through`/`pass` are in "shell" (top-left origin).
        private func shellPoint(_ point: NSPoint) -> CGPoint {
            let p = convert(point, from: superview)
            return CGPoint(x: p.x, y: isFlipped ? p.y : bounds.height - p.y)
        }

        override func hitTest(_ point: NSPoint) -> NSView? {
            let at = shellPoint(point)
            if through.contains(where: { $0.contains(at) }) { return nil }
            let type = NSApp.currentEvent?.type
            if !pass.isEmpty, pass.contains(at), type == .leftMouseDown || type == .leftMouseUp { return nil }
            return super.hitTest(point)
        }

        // A click into an inactive window still dismisses, like a tap on the old scrim did.
        override func acceptsFirstMouse(for event: NSEvent?) -> Bool { true }

        override func mouseDown(with event: NSEvent) { onClick?() }
        // Everything else the scrim claims is meant for the content it covers — it must not arrive,
        // and the responder chain never leads down to it, so these stay empty.
        override func scrollWheel(with event: NSEvent) {}
        override func rightMouseDown(with event: NSEvent) {}
        override func rightMouseUp(with event: NSEvent) {}
        override func otherMouseDown(with event: NSEvent) {}
        override func otherMouseUp(with event: NSEvent) {}
    }
}
