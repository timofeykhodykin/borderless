import AppKit
import Shared
import SwiftUI

/// The menu bar item's icon: the state at a glance, like Android's status-bar icons — the crane while through a server
/// (faded when off), a ring while searching, a slashed wifi without a network, an arrow while traffic goes direct, the
/// limited network's tower, ⦸ while paused.
struct MenuBarIcon: View {
    var phase: Phase

    var body: some View {
        switch phase {
        case .connected: Image(nsImage: Crane.menuBarImage())
        case .off: Image(nsImage: Crane.menuBarImage(alpha: 0.45))
        case .searching: Image(systemName: "circle.dashed")
        case .noNetwork: Image(systemName: "wifi.slash")
        case .direct: Image(systemName: "arrow.uturn.forward")
        case .limited: Image(systemName: "antenna.radiowaves.left.and.right")
        case .paused: Image(systemName: "nosign")
        }
    }
}

/// The panel under the menu bar item (local/macos/PLAN.md §4): the state with its switch, the server in use (click →
/// another one), the strategy, the three figures, Ping all, country and energy controls; then the window,
/// the network reset and Quit. The same model as the window, so it shows the demo with
/// `BORDERLESS_DEMO=1` and the service otherwise.
struct MenuBarPanel: View {
    var app: AppModel
    @Environment(\.openWindow) private var openWindow
    @Environment(\.snapshot) private var snapshot
    /// The inline lists: another server, another strategy.
    @State private var choosing: Choice?

    enum Choice { case server, strategy }

    /// The server list's filter and what it needs to be typed into: the field's focus, the panel's window (a
    /// MenuBarExtra window is a nonactivating panel — see `focusSearch`) and a key monitor rescuing unfocused keys.
    @State private var query = ""
    @FocusState private var searchFocused: Bool
    @State private var panelRef = PanelRef()
    @State private var keyMonitor: Any?
    /// A network reset is on the wire: a second click on the row can't start a parallel one.
    @State private var restoring = false

    init(app: AppModel, choosing: Choice? = nil) {
        self.app = app
        _choosing = State(initialValue: choosing)
    }

    private var model: MainModel { app.main }

    /// The tunnel can be driven: the demo, or a service that answers.
    private var live: Bool {
        if app.demo { return true }
        if case .running = app.service { return true }
        return false
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            header
            // The updater's word under the state (`engine.update`, PLAN §2): a row only while there
            // is something to tell — a staged build's tap asks, then installs (ruling R2).
            if !app.demo, let update = app.update, update.interesting, update.state != "error" {
                Hairline()
                updateRow(update)
            }
            if live {
                Hairline()
                serverRow
                if choosing == .server { serverList }
                Hairline()
                strategyRow
                if choosing == .strategy { strategyList }
                Hairline()
                Figures(model: model).padding(.vertical, 10)
                HStack(spacing: 6) {
                    PanelPingButton(scan: model.scan, enabled: model.canProbe) { model.pingAll() }
                    Spacer()
                    ModeButton(symbol: "globe", on: !model.countries.isEmpty,
                               label: model.countries.count == 1 ? model.countries.first
                                   : model.countries.count > 1 ? "\(model.countries.count)" : nil,
                               help: L("countries_title")) {
                        model.section = .main
                        model.showCountries = true
                        NSApp.setActivationPolicy(.regular)
                        NSApp.activate()
                        openWindow(id: "main")
                    }
                    // `ecoSaving` carries the last estimate (46 before any report): a number shown before the
                    // engine told one would be made up, so a nil estimate leaves the pill on but numberless.
                    ModeButton(symbol: "battery.75percent", on: model.eco,
                               label: model.eco ? model.ecoEstimate.map { L("eco_pill", $0) } : nil,
                               help: L("eco_title")) { model.setEco(!model.eco) }
                }
                .padding(.horizontal, 14).padding(.bottom, 10)
            }
            Hairline()
            VStack(spacing: 0) {
                PanelItem(title: String(localized: "menu.open"), symbol: "macwindow") {
                    // Back from the accessory state ⌘Q left (no Dock icon, no ⌘-Tab): the window is wanted again.
                    NSApp.setActivationPolicy(.regular)
                    NSApp.activate()
                    openWindow(id: "main")
                }
                PanelItem(title: macText("mac.routing.settings", "Routing settings"),
                          symbol: "arrow.triangle.branch", enabled: live) {
                    model.settingsQuery = ""
                    model.settingsPage = .routing
                    if model.section != .settings { model.section = .settings }
                    NSApp.setActivationPolicy(.regular)
                    NSApp.activate()
                    openWindow(id: "main")
                }
                PanelItem(title: String(localized: "menu.restore.network"), symbol: "arrow.counterclockwise",
                          enabled: !restoring) { restoreNetwork() }
                // `.unreachable`: the daemon is installed but quiet — reinstalling is the only repair
                // (agent-tasks 2026-11-09); confirmed first, since the administrator prompt follows.
                if case .unreachable = app.service, !app.demo {
                    PanelItem(title: macText("mac.service.repair", "Reinstall the service"), symbol: "wrench.and.screwdriver",
                              enabled: !app.repairingService && !restoring) { repairService() }
                }
                PanelItem(title: String(localized: "menu.quit"), symbol: "power") { AppDelegate.quit() }
            }
            .padding(5)
        }
        .frame(width: 320)
        .background(Palette.background)
        .tint(Palette.accent)
        .background(PanelWindowSpy { w in
            // Open/close is driven by the window's own notifications (`PanelRef`), not by view lifecycle — a
            // persistent MenuBarExtra view's `onAppear`/`onDisappear` may fire once and never again.
            self.panelRef.onVisible = { open in
                if open { self.panelOpened() } else { self.panelClosed() }
            }
            self.panelRef.window = w
            // The server list may already be open (the panel keeps its state between shows): keys for it.
            if w != nil, self.choosing == .server { self.focusSearch(); self.startKeyTap() }
        })
        // A torn-down view runs the same close teardown as a notification-reported one (see `panelClosed` —
        // every step is idempotent, so the two callers can't undo each other).
        .onDisappear(perform: panelClosed)
        .task { await app.refresh() }
    }

    // MARK: The state and its switch

    private var header: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                AppTitle(size: 17)
                Spacer()
                if live {
                    AppSwitch(isOn: Binding(get: { model.phase != .off }, set: { _ in model.toggle() }))
                        .disabled(app.busy)
                }
            }
            HStack(spacing: 6) {
                Circle().fill(live ? model.stateDot : Palette.textMuted).frame(width: 7, height: 7)
                Text(live ? (model.phase == .connected ? String(localized: "main.state.connected") : model.stateText) : serviceText).font(Fonts.body(12)).foregroundStyle(Palette.textSecondary).lineLimit(1).textSelection(.enabled)
                // A check running gets its progress on the same line, so "Ping all" is visibly working.
                if live, let scan = model.scan {
                    Spacer(minLength: 4)
                    HStack(spacing: 5) {
                        ProgressRing(value: Double(scan.done) / Double(max(scan.total, 1))).frame(width: 11, height: 11)
                        Text(L("status_checked", scan.done, scan.total)).font(Fonts.body(11))
                            .foregroundStyle(Palette.textSecondary).monospacedDigit().lineLimit(1).textSelection(.enabled)
                    }
                    .layoutPriority(1)
                }
            }
            if case .running(let reply) = app.service, !app.demo {
                if let other = reply.network?.otherTunnel, reply.tunnel == .off {
                    Text("main.other.tunnel \(other)").font(Fonts.body(11.5)).foregroundStyle(Palette.danger).textSelection(.enabled)
                }
            }
        }
        .padding(.horizontal, 14).padding(.top, 12)
        // Below the state line as well: not live the hairline comes next and the line sat right on it (owner,
        // 2026-11-07); live it replaces the padding the first hairline used to carry, so nothing moves there.
        .padding(.bottom, 10)
    }

    private var serviceText: String {
        switch app.service {
        case .checking: String(localized: "menu.service.checking")
        case .missing: String(localized: "menu.service.missing")
        case .unreachable: String(localized: "main.no.answer")
        case .outdated: String(localized: "menu.service.outdated")
        case .running: String(localized: "menu.service.running")
        }
    }

    // MARK: The updater's row and the dead daemon's repair

    /// The `engine.update` state as one row (same wording as the window's banner). A staged build's
    /// tap is confirmed first — the install replaces the service and the app under the running tunnel.
    private func updateRow(_ update: EngineStatus.UpdateStatus) -> some View {
        PanelItem(title: update.bannerTitle,
                  symbol: update.state == "error" ? "exclamationmark.triangle" : "arrow.down.circle",
                  enabled: live) {
            if update.waitsForUser { confirmUpdateInstall(update) }
            else if update.state == "error" { app.runAction("update_dismiss") }
        }
    }

    /// The install's confirm: the panel has no menu layer for a card, so it is an NSAlert like the
    /// quiet-reset one (ruling R2 — install only after an explicit yes).
    private func confirmUpdateInstall(_ update: EngineStatus.UpdateStatus) {
        NSApp.activate()
        let alert = NSAlert()
        alert.alertStyle = .informational
        alert.messageText = L("update_to", update.version ?? "?")
        alert.informativeText = macText("mac.update.install.q", "The service and the app are replaced and the tunnel reconnects — a moment without it.")
        alert.addButton(withTitle: L("update"))
        alert.addButton(withTitle: L("cancel"))
        if alert.runModal() == .alertFirstButtonReturn { app.runAction("update_install") }
    }

    /// Reinstall over the quiet daemon: the system's password prompt is coming, so the row asks first
    /// (like `confirmQuietReset` — an admin prompt must not drop on the user unannounced).
    private func repairService() {
        NSApp.activate()
        let alert = NSAlert()
        alert.alertStyle = .warning
        alert.messageText = macText("mac.service.repair.q", "Install the service again?")
        alert.informativeText = macText("mac.service.repair.sub", "The installed service isn't answering. macOS will ask for the administrator password, and the network settings it changed are put back first.")
        alert.addButton(withTitle: macText("mac.service.repair", "Reinstall the service"))
        alert.addButton(withTitle: L("cancel"))
        guard alert.runModal() == .alertFirstButtonReturn else { return }
        app.repairService()
    }

    // MARK: The server

    /// The server in use, or the one a connection would start with — `sorted`'s first, the order `toggle`
    /// and `connect` pick from (the ping order), not the stored one `visible.first` would give.
    private var shown: ServerItem? { model.current ?? model.sorted.first }

    private var serverRow: some View {
        PanelRow(open: choosing == .server, enabled: shown != nil) {
            if let s = shown {
                FlagView(code: s.country, height: 13)
                VStack(alignment: .leading, spacing: 1) {
                    Text(model.displayName(s)).font(Fonts.body(13, bold: model.current != nil)).foregroundStyle(Palette.text).lineLimit(1)
                    Text([model.groupName(s.groupId), s.proto].filter { !$0.isEmpty }.joined(separator: " · "))
                        .font(Fonts.body(11)).foregroundStyle(Palette.textSecondary).lineLimit(1)
                }
                Spacer(minLength: 6)
                if let ms = s.ms {
                    PingDot(ms: ms)
                    Text(L("ms", ms)).font(Fonts.body(12)).foregroundStyle(Palette.textSecondary).monospacedDigit()
                }
            } else {
                Text(L("empty_title")).font(Fonts.body(13)).foregroundStyle(Palette.textSecondary)
                Spacer()
            }
        } action: {
            choosing = choosing == .server ? nil : .server
        }
    }

    /// Another server: a click connects to it (here a click is the action; the list is short and says so).
    /// The field on top filters by anything the server can be known by — `MainModel.matches`, the window's
    /// list's own predicate — a MenuBarExtra window takes no keys by itself, so `focusSearch` makes it key.
    private var serverList: some View {
        // `sorted` already leaves the hidden servers out; the search only filters it.
        let list = model.sorted.filter { model.matches($0, query) }
        return VStack(spacing: 4) {
            searchField
                .padding(.top, 6)
            // Lazy rows: a subscription's hundreds of servers build only the slice that shows (the fixed frame
            // above keeps the window's size unchanged either way; snapshots render the whole stack flat).
            let rows = LazyVStack(spacing: 0) {
                    if list.isEmpty {
                        if model.sorted.isEmpty && query.isEmpty {
                            // No servers at all (Android's "no servers — add with +"): the row opens
                            // the window, where the add button lives — with the same "come forward"
                            // the other panel→window jumps make first.
                            PanelItem(title: L("status_no_servers"), symbol: "plus") {
                                NSApp.setActivationPolicy(.regular)
                                NSApp.activate()
                                openWindow(id: "main")
                            }
                        } else {
                            Text(L("set_search_none")).font(Fonts.body(13)).foregroundStyle(Palette.textSecondary)
                                .textSelection(.enabled).padding(.horizontal, 9).frame(maxWidth: .infinity, minHeight: PanelItem.height, alignment: .leading)
                        }
                    } else {
                        ForEach(list) { s in
                            // Never checked: the row leaves the place empty; limited is "—" (never probed),
                            // a failed check "no reply" — `pingText`, gated so the first case stays blank.
                            // The limited-network servers carry the tower `mark`, as the window's outline does.
                            PanelItem(title: model.displayName(s), flag: s.country, mark: s.limited,
                                      trailing: s.ms == nil && !s.checked && !s.limited ? nil : pingText(s),
                                      ms: s.ms, current: s.id == model.currentId && model.phase == .connected) {
                                choosing = nil
                                model.connect(to: s)
                            }
                            .id(s.id)
                        }
                    }
                }
                // The trailing margin is a little wider than the leading one so the overlay scroller
                // lands in it while scrolling — not on the rows' ping text.
                .padding(.leading, 5).padding(.trailing, 13)
            // Snapshots draw no ScrollView content: the rows are laid out plainly there (`clipped: false` —
            // the panel sizes to its content, no clip wanted). The height is a fixed one, not a cap: a
            // MenuBarExtra window sizes itself to the content once, and a `maxHeight` scroll view is
            // squeezed to zero in that pass — the list never showed (found 2026-11-07). At least one row:
            // without it a no-match search folded the list to a 4-pt sliver. The `+4` is slack so a list
            // that exactly fills its frame doesn't claim a stray scrollable pixel and flash a scroller;
            // `listCap` keeps the whole panel inside short screens.
            ScrollViewReader { proxy in
                SnapshotScroll(clipped: false, height: min(CGFloat(max(list.count, 1)) * PanelItem.height + 4, listCap)) { rows }
                    .onAppear {
                        // The server in use opens centred — a run later, once the laid-out rows carry ids.
                        guard !snapshot, model.phase == .connected, let id = model.currentId else { return }
                        DispatchQueue.main.async { proxy.scrollTo(id, anchor: .center) }
                    }
            }
        }
        .padding(.bottom, 6)
        .onAppear {
            guard !snapshot else { return }
            focusSearch()
            startKeyTap()
        }
        .onDisappear(perform: endSearch)
    }

    /// The list's filter, in the `SearchField` look. Each click on it asks for the keys again — a click elsewhere
    /// in the panel can take the first responder back without resigning the window.
    private var searchField: some View {
        HStack(spacing: 5) {
            Image(systemName: "magnifyingglass").font(.system(size: 11)).foregroundStyle(Palette.textSecondary)
            if snapshot {
                Text(query.isEmpty ? L("search") : query)
                    .font(Fonts.body(12)).foregroundStyle(query.isEmpty ? Palette.textMuted : Palette.text)
                    .lineLimit(1).frame(maxWidth: .infinity, alignment: .leading)
            } else {
                // A leading space can't be typed (it never helps a substring match); interior spaces stay.
                TextField(L("search"), text: Binding(get: { query }, set: { query = String($0.drop(while: \.isWhitespace)) }))
                    .textFieldStyle(.plain).font(Fonts.body(12))
                    .focused($searchFocused)
            }
            if !query.isEmpty {
                Image(systemName: "xmark.circle.fill").font(.system(size: 11)).foregroundStyle(Palette.textMuted)
                    .onTapGesture { query = "" }
            }
        }
        .padding(.horizontal, 7).frame(height: Dims.searchH)
        .background(RoundedRectangle(cornerRadius: Dims.cornerRadius).fill(Palette.cardStrong.opacity(0.7)))
        // The accent ring says the field holds the keys — a nonactivating panel shows nothing else for it.
        .overlay(RoundedRectangle(cornerRadius: Dims.cornerRadius)
            .strokeBorder(Palette.accent, lineWidth: 1)
            .opacity(searchFocused ? 1 : 0))
        .animation(.easeOut(duration: 0.15), value: searchFocused)
        .padding(.horizontal, 5)
        .contentShape(Rectangle())
        .onTapGesture { focusSearch() }
    }

    /// Makes the app active and the panel's window key, then points the focus at the field — the only way a text
    /// field inside a MenuBarExtra `.window` can be typed into (its window is a nonactivating panel, and a click
    /// on the status item doesn't activate the app). The focus is re-set a run later so a field that had already
    /// counted as focused — but lost the first responder to a click elsewhere — takes it again.
    private func focusSearch() {
        guard !snapshot, let window = panelRef.window else { return }
        NSApp.activate()
        // Panels can refuse key unless a view inside needs it: here one does.
        (window as? NSPanel)?.becomesKeyOnlyIfNeeded = false
        window.makeKey()
        DispatchQueue.main.async {
            searchFocused = false
            DispatchQueue.main.async { searchFocused = true }
        }
    }

    /// While the server list is up, keys that reach the app with no field to take them are still typed into the
    /// search (⌫ and esc edit it): a rescue for a panel that ended up key but couldn't hand the first responder
    /// to the field. Keys aimed at any other window pass untouched, and so do the ones a field already owns —
    /// esc excepted, which is the search's own (below).
    private func startKeyTap() {
        guard keyMonitor == nil else { return }
        keyMonitor = NSEvent.addLocalMonitorForEvents(matching: .keyDown) { event in
            guard event.window == nil || event.window === self.panelRef.window,
                  !event.modifierFlags.contains(.command), !event.modifierFlags.contains(.control)
            else { return event }
            // esc is taken even while the field holds the first responder — its own esc would only end
            // editing and leave the row open. A query clears first; with none, the key folds the list
            // (closing the panel itself is left to the system's outside click: ordering a MenuBarExtra
            // window out by hand desyncs the item's own toggle bookkeeping).
            if event.keyCode == 53 {
                if !self.query.isEmpty { self.query = "" } else { self.choosing = nil }
                return nil
            }
            guard !(event.window?.firstResponder is NSTextView) else { return event }
            if event.keyCode == 51, !self.query.isEmpty { self.query.removeLast(); return nil }  // ⌫
            guard event.specialKey == nil, let chars = event.characters,
                  chars.unicodeScalars.allSatisfy({ !CharacterSet.controlCharacters.contains($0) })
            else { return event }
            // The same rule the field holds: the query starts at the first non-space.
            self.query = String((self.query + chars).drop(while: \.isWhitespace))
            return nil
        }
    }

    private func stopKeyTap() {
        if let keyMonitor { NSEvent.removeMonitor(keyMonitor) }
        keyMonitor = nil
    }

    /// The panel is on screen — told by `PanelRef`'s notifications, not the view lifecycle: a persistent
    /// MenuBarExtra view's `onAppear` fired back at build time and won't again, so the search's re-arm and
    /// the filter's reset must be driven from here on every show (the query also resets on close, but a
    /// close the notification missed would leave it stale).
    private func panelOpened() {
        guard !snapshot else { return }
        query = ""
        if choosing == .server { focusSearch(); startKeyTap() }
    }

    /// The panel is off screen — also the root `onDisappear`'s job, for the case the view is torn down
    /// rather than kept alive: every step is idempotent, so the two callers can't undo each other.
    private func panelClosed() {
        guard !snapshot else { return }
        endSearch()
        releaseActivation()
    }

    /// The search's teardown, shared by the list folding and the panel hiding: the typed-keys monitor goes,
    /// the field's focus goes, and the key-capability `focusSearch` switched on goes back — a panel left
    /// key-capable with the monitor gone keeps swallowing keys nothing reads.
    private func endSearch() {
        query = ""
        searchFocused = false
        stopKeyTap()
        (panelRef.window as? NSPanel)?.becomesKeyOnlyIfNeeded = true
    }

    /// "Restore network settings", one run at a time (`restoring` greys the row). The service does the reset
    /// itself when it answers; a quiet one makes `ServiceClient` escalate to the standalone reset, which
    /// starts with the system's administrator prompt — a password dialog popping up off a menu click needs
    /// the user's "yes" first, so that path is confirmed. The demo touches no system state: nothing to do.
    private func restoreNetwork() {
        guard !restoring, !app.demo else { return }
        if !app.service.isRunning, !confirmQuietReset() { return }
        restoring = true
        Task { @MainActor in
            _ = await ServiceClient.restoreNetwork()
            await app.refresh()
            restoring = false
        }
    }

    /// The confirm before the standalone reset's administrator prompt. An `NSAlert`, not the window's popup
    /// cards — the panel has no menu layer to put one on.
    private func confirmQuietReset() -> Bool {
        // Alerts behind an inactive app take no keys, and the accessory app may be sitting back.
        NSApp.activate()
        let alert = NSAlert()
        alert.alertStyle = .warning
        alert.messageText = macText("mac.restore.q", "Restore the network settings?")
        alert.informativeText = macText("mac.restore.admin", "The service isn't answering, so the reset runs without it — macOS will ask for the administrator password.")
        alert.addButton(withTitle: String(localized: "menu.restore.network"))
        alert.addButton(withTitle: L("cancel"))
        return alert.runModal() == .alertFirstButtonReturn
    }

    /// The most the server list may take. 230 pt is the design cap; under it, whatever the screen leaves once
    /// the rest of the panel is paid for — a MenuBarExtra window can't scroll as a whole, so a list that
    /// outgrew the display would push Open / Restore / Quit off the bottom on short screens.
    private var listCap: CGFloat {
        /// The panel's height with the list empty: the header (with a problem line), the two fold rows, the
        /// search, the figures, the buttons and the menu items — measured generously so they always fit.
        let chrome: CGFloat = 440
        let visible = (panelRef.window?.screen ?? NSScreen.main)?.visibleFrame.height ?? 800
        return min(230, max(visible - chrome, PanelItem.height * 3))
    }

    // MARK: The strategy

    private var strategyRow: some View {
        PanelRow(open: choosing == .strategy) {
            Text(L("strategy")).font(Fonts.body(13)).foregroundStyle(Palette.text)
            Spacer()
            Text(model.strategy.title).font(Fonts.body(12)).foregroundStyle(Palette.textSecondary)
        } action: {
            choosing = choosing == .strategy ? nil : .strategy
        }
    }

    private var strategyList: some View {
        VStack(spacing: 0) {
            ForEach(Strategy.allCases) { s in
                // The list stays open for consecutive picks (the check moves to the new strategy).
                PanelItem(title: s.title, checked: s == model.strategy) {
                    model.setStrategy(s)
                }
            }
        }
        .padding(.horizontal, 5).padding(.bottom, 6)
    }
}

/// A row that folds a list out under it (the chevron turns). `enabled = false` draws a row with nothing to
/// open — an empty server list: no chevron to turn, no hover, no tap.
private struct PanelRow<Content: View>: View {
    var open: Bool
    var enabled = true
    @ViewBuilder var content: Content
    var action: () -> Void
    @State private var hover = false

    var body: some View {
        HStack(spacing: 8) {
            content
            if enabled { FoldChevron(open: open, color: Palette.textMuted) }
        }
        .padding(.horizontal, 14).frame(minHeight: 44)
        .background(enabled && hover ? Palette.card : .clear)
        .contentShape(Rectangle())
        .onHover { hover = $0 }
        .onTapGesture { if enabled { withAnimation(.easeInOut(duration: 0.15)) { action() } } }
    }
}

/// One line of the panel's lists, highlighted in the accent under the pointer like our menus (`AppMenu`).
private struct PanelItem: View {
    /// The fixed row height; the server list's fixed scroll frame sizes itself to a count of these.
    static let height = Dims.panelRowH

    var title: String
    var symbol: String? = nil
    var flag: String?? = nil
    /// The limited-network tower after the name — the mark the window's outline carries (`ServerLine.mark`).
    var mark = false
    var trailing: String? = nil
    var ms: Int? = nil
    var current = false
    var checked = false
    /// Drawn muted, no hover, no tap — an unavailable or busy action's row.
    var enabled = true
    var action: () -> Void
    @State private var hover = false

    var body: some View {
        let lit = enabled && (hover || current)
        HStack(spacing: 8) {
            if let symbol {
                Image(systemName: symbol).font(.system(size: 11.5)).frame(width: 16)
                    .foregroundStyle(lit ? Palette.onAccent : enabled ? Palette.textSecondary : Palette.textMuted)
            }
            if let flag { FlagView(code: flag, height: 11).opacity(enabled ? 1 : 0.55) }
            Text(title).font(Fonts.body(13)).lineLimit(1)
                .foregroundStyle(lit ? Palette.onAccent : enabled ? Palette.text : Palette.textMuted)
            if mark {
                // Rows keep the sounding tower like Android's server rows; the silent mast is the
                // no-reply variant, which this list doesn't carry a signal for.
                TowerMark(color: lit ? Palette.onAccent : StateTone.limited.strong)
            }
            Spacer(minLength: 6)
            if let trailing {
                if ms != nil && !lit { PingDot(ms: ms, size: 7) }
                Text(trailing).font(Fonts.body(12)).monospacedDigit()
                    .foregroundStyle(lit ? Palette.onAccent : enabled ? Palette.textSecondary : Palette.textMuted)
            }
            if checked { CheckMark(color: lit ? Palette.onAccent : Palette.accent) }
        }
        .padding(.horizontal, 9).frame(height: Self.height)
        .background(RoundedRectangle(cornerRadius: 5).fill(lit ? Palette.accent : .clear))
        .contentShape(Rectangle())
        .onHover { if enabled { hover = $0 } }
        .onTapGesture { if enabled { action() } }
    }
}

/// "Ping all" for the panel: while a check runs it carries the progress (the ring and done/total, like the
/// window's own button) — so the click visibly did something — and it sits greyed while checks can't run.
private struct PanelPingButton: View {
    var scan: ScanProgress?
    var enabled: Bool
    var action: () -> Void

    var body: some View {
        Button {
            // A tap mid-check is refused here rather than by `.disabled`: `MacButtonStyle` dims on
            // `isEnabled` now, and a running check keeps its colours so the ring reads.
            guard scan == nil else { return }
            action()
        } label: {
            PingAllLabel(scan: scan, iconSize: 11.5).padding(.horizontal, 9)
        }
        .buttonStyle(MacButtonStyle())
        .fixedSize()
        .disabled(!enabled)
    }
}

/// The panel's window, held weakly: MenuBarExtra hands out no handle, so `PanelWindowSpy` reports it. Its key and
/// occlusion notifications also drive the activation hand-back — a panel that resigned or hid while no window of
/// ours is on screen means the search's activation (see `MenuBarPanel.focusSearch`) is over — and the panel's
/// open/close itself through `onVisible`, because the view's own `onAppear`/`onDisappear` can't be trusted for
/// it (a MenuBarExtra view may be kept alive between shows and never fire them again).
@MainActor
private final class PanelRef {
    /// Told when the panel crosses between on screen and off. Only `isVisible` decides — a panel merely
    /// covered by another window is still open (covering it must not wipe the search), while a resign or
    /// an occlusion change still triggers the activation hand-back below.
    var onVisible: ((Bool) -> Void)?
    weak var window: NSWindow? {
        didSet {
            guard window !== oldValue else { return }
            tokens.forEach { NotificationCenter.default.removeObserver($0) }
            tokens = []
            // The window leaving counts as a close; attaching to an already-on-screen one as an open —
            // neither produces a notification of its own.
            guard let window else { report(false); return }
            for name in [NSWindow.didResignKeyNotification, NSWindow.didChangeOcclusionStateNotification] {
                tokens.append(NotificationCenter.default.addObserver(forName: name, object: window, queue: .main) { [weak self, weak window] _ in
                    MainActor.assumeIsolated {
                        guard let window else {
                            // The weak ref nilled on dealloc without firing didSet — count it as a close
                            // or `wasOpen` would swallow the next real open.
                            self?.report(false)
                            return
                        }
                        if !window.isVisible || !window.isKeyWindow { releaseActivation() }
                        self?.report(window.isVisible)
                    }
                })
            }
            report(window.isVisible)
        }
    }
    // `nonisolated(unsafe)`: `deinit` can't touch actor-isolated state, and a block token has to be
    // removed by hand — the tokens are only ever written/read on the main actor anyway.
    nonisolated(unsafe) private var tokens: [NSObjectProtocol] = []
    private var wasOpen = false

    /// Notifies only on the edge: the notifications fire for resigns and re-keys that change nothing.
    private func report(_ open: Bool) {
        guard open != wasOpen else { return }
        wasOpen = open
        onVisible?(open)
    }

    deinit { tokens.forEach { NotificationCenter.default.removeObserver($0) } }
}

/// Reports the window the panel sits in (the class name `MenuBarClickFix` matches on is private; this asks the
/// hosting view itself). Snapshots render with no window: the callback gets nil and the search code stays off.
private struct PanelWindowSpy: NSViewRepresentable {
    var onWindow: (NSWindow?) -> Void

    func makeNSView(context: Context) -> SpyView { SpyView(onWindow: onWindow) }
    func updateNSView(_ nsView: SpyView, context: Context) { nsView.onWindow = onWindow }

    final class SpyView: NSView {
        var onWindow: (NSWindow?) -> Void

        init(onWindow: @escaping (NSWindow?) -> Void) {
            self.onWindow = onWindow
            super.init(frame: .zero)
        }

        @available(*, unavailable) required init?(coder: NSCoder) { fatalError() }

        override func viewDidMoveToWindow() {
            super.viewDidMoveToWindow()
            onWindow(window)
        }
    }
}

/// The app was activated for the panel's search field. With the panel gone (resigned or hidden) and no window of
/// ours on screen, the previously frontmost app gets the menu bar back — the outside click that closed the panel
/// doesn't reactivate it by itself. A regular window of ours (the main one) means the user is with the app.
@MainActor
private func releaseActivation() {
    guard NSApp.isActive,
          !NSApp.windows.contains(where: { $0.isVisible && $0.styleMask.contains(.titled) }) else { return }
    NSApp.deactivate()
}
