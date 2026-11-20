import SwiftUI

/// The main screen — the Android one laid out for a wide window: the connect button, the state and the controls on the
/// left; the figures, the map of servers and the list on the right. Same pieces, same colours, same words, at the Mac's
/// density: system text, 24 pt buttons, one-line rows.
struct MainView: View {
    @Bindable var model: MainModel
    @Environment(MenuState.self) private var menus

    var body: some View {
        HStack(alignment: .top, spacing: 0) {
            ConnectColumn(model: model)
                .frame(width: 316)
            Hairline(vertical: true)
            ServersColumn(model: model)
                .frame(maxWidth: .infinity)
        }
        .background(Palette.background)
        // This page has no header row of its own, so the drag surface covers its top strip —
        // the shell's strip under the content is claimed by the pages' hit regions first.
        .background(alignment: .top) { WindowDrag(menus: menus).frame(height: Dims.headerH).frame(maxWidth: .infinity) }
    }
}

// MARK: Left: the connection

private struct ConnectColumn: View {
    @Bindable var model: MainModel
    @Environment(MenuState.self) private var menus

    var body: some View {
        VStack(spacing: 0) {
            Spacer(minLength: 0)
                .background { WindowDrag(menus: menus) }
            ConnectButton(phase: model.phase, searching: model.phase == .searching || model.scan != nil, eco: model.eco,
                          sats: satellites, scanId: model.scan?.id) {
                model.toggle()
            }
            StatusText(model: model)
                .padding(.top, 2)
                .onReceive(NotificationCenter.default.publisher(for: .shotScan)) { _ in model.pingAll() }
            // Whole buttons move to a second line when the three don't fit (longer words in some languages).
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 6) { controls }
                VStack(spacing: 6) {
                    HStack(spacing: 6) {
                        RoutingButton(model: model)
                        StrategyButton(model: model)
                    }
                    PingButton(scan: model.scan) { model.pingAll() }.disabled(!model.canProbe)
                }
            }
            .padding(.top, 16)
            Spacer(minLength: 0)
                .background { WindowDrag(menus: menus) }
            HStack(spacing: 8) {
                ModeButton(symbol: "globe", on: !model.countries.isEmpty,
                           label: model.countries.count == 1 ? model.countries.first : model.countries.count > 1 ? "\(model.countries.count)" : nil,
                           help: L("countries_title"), action: nil)
                    .appPopupOnClick(menus, requested: $model.showCountries, placement: .trailing) {
                        AnyView(CountriesPopup(model: model))
                    }
                // As the panel's pill: `ecoSaving`'s 46 is made up until the engine reports, so a nil
                // `ecoEstimate` keeps the pill on but numberless.
                ModeButton(symbol: "battery.75percent", on: model.eco,
                           label: model.eco ? model.ecoEstimate.map { L("eco_pill", $0) } : nil,
                           help: L("eco_title")) { model.setEco(!model.eco) }
            }
            .padding(.bottom, 18)
        }
        .padding(.horizontal, 16)
    }

    /// The servers of the scan in progress for the orbits: probes in flight and this scan's answers (queued ones are
    /// not launched yet), as Android's `satellites`.
    private var satellites: [Satellite] {
        guard let scan = model.scan else { return [] }
        return Array(model.servers.compactMap { s -> Satellite? in
            if scan.probing.contains(s.id) { return Satellite(id: s.id, probing: true, ms: nil, failed: false) }
            if let r = scan.results[s.id] { return Satellite(id: s.id, probing: false, ms: r, failed: r == nil) }
            return nil
        }.prefix(Orbit.maxSatellites))
    }

    @ViewBuilder private var controls: some View {
        RoutingButton(model: model)
        StrategyButton(model: model)
        PingButton(scan: model.scan) { model.pingAll() }.disabled(!model.canProbe)
    }
}

/// The big round button: solid fill and a double ring when on (white gap + darker ring), dashed outer ring in battery
/// saving, a soft breathing glow while connected, and the orbits of the servers being checked ([Orbits]): they slide
/// out from under it while servers are checked and stay at least 1.5 s, so a quick connect still shows the motion.
struct ConnectButton: View {
    var phase: Phase
    var searching: Bool
    var eco: Bool
    var sats: [Satellite] = []
    var scanId: UUID?
    var action: () -> Void

    private let size: CGFloat = 128
    /// Out from under the button (searching, or for a moment after), and drawn at all (until drawn back in).
    @State private var shown = false
    @State private var alive = false
    @State private var linger: Task<Void, Never>?

    var body: some View {
        ZStack {
            if phase == .connected { Halo(eco: eco) }
            if alive || searching {
                Orbits(sats: sats, scanId: scanId, visible: searching || shown)
            }
            Button(action: action) { face }
                .buttonStyle(PressStyle())
                .help(help)
        }
        .frame(width: Orbit.box, height: Orbit.box)
        .animation(.easeInOut(duration: 0.35), value: phase)
        .onChange(of: searching, initial: true) { _, now in
            linger?.cancel()
            if now {
                shown = true
                alive = true
            } else if alive {
                linger = Task { @MainActor in
                    try? await Task.sleep(for: .milliseconds(1500))
                    guard !Task.isCancelled else { return }
                    shown = false
                    try? await Task.sleep(for: .milliseconds(600))
                    guard !Task.isCancelled else { return }
                    alive = false
                }
            }
        }
        // The orbits' slide-in must not keep writing this view's state after it's gone.
        .onDisappear { linger?.cancel() }
    }

    /// What the click does in this phase — "Disconnect" would lie while the button cancels a search or
    /// turns off a no-server state (the click drops the connection the phase armed, it doesn't cut a live tunnel).
    private var help: String {
        switch phase {
        case .off: L("connect")
        case .searching: L("cancel")
        case .connected: String(localized: "main.disconnect")
        case .direct, .paused, .noNetwork, .limited: macText("main.turn.off", "Turn off")
        }
    }

    private var face: some View {
        ZStack {
            if eco {
                Circle().strokeBorder(ring, style: StrokeStyle(lineWidth: 2.5, dash: [7, 5]))
            } else {
                Circle().fill(ring)
                Circle().fill(Palette.background).padding(2.5)
            }
            Circle().fill(fill).padding(5.5)
                .overlay(phase == .off ? Circle().strokeBorder(Palette.outline, lineWidth: 1.5).padding(5.5) : nil)
            Image(systemName: "power")
                .font(.system(size: 32, weight: .medium))
                .foregroundStyle(icon)
        }
        .frame(width: size, height: size)
        .contentShape(Circle())
    }

    private var fill: Color {
        switch phase {
        case .connected: Palette.accent
        case .searching: Palette.accentSoft
        case .off: Palette.background
        case .direct, .paused, .noNetwork, .limited: phase.tone!.fill
        }
    }

    private var ring: Color {
        switch phase {
        case .connected: Palette.accentStrong
        case .searching: Palette.accentContainer
        case .off: .clear
        case .direct, .paused, .noNetwork, .limited: phase.tone!.strong
        }
    }

    private var icon: Color {
        switch phase {
        case .connected: Palette.onAccent
        case .off: Palette.textMuted
        case .searching: Palette.accent
        case .direct, .paused, .noNetwork, .limited: phase.tone!.icon
        }
    }
}

/// Soft glow behind the button; breathes unless battery saving is on (no frames drawn for it then). Switching
/// eco while connected stops and restarts it — an `onAppear`-only check left a running breath going.
private struct Halo: View {
    var eco: Bool
    @State private var breathe = false

    var body: some View {
        Circle()
            .fill(RadialGradient(colors: [Palette.accent.opacity(0.2), Palette.accent.opacity(0)], center: .center,
                                 startRadius: 0, endRadius: 92))
            .frame(width: 184, height: 184)
            .scaleEffect(breathe ? 1.06 : 1)
            .onAppear { start() }
            .onChange(of: eco) { _, on in on ? stop() : start() }
    }

    private func start() {
        guard !eco, !breathe else { return }
        withAnimation(.easeInOut(duration: 2.2).repeatForever(autoreverses: true)) { breathe = true }
    }

    private func stop() {
        guard breathe else { return }
        withAnimation(.easeInOut(duration: 0.35)) { breathe = false }
    }
}

/// The button gives under the pointer: a little smaller and darker while pressed, springing back.
private struct PressStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.95 : 1)
            .brightness(configuration.isPressed ? -0.04 : 0)
            .animation(.spring(response: 0.25, dampingFraction: 0.6), value: configuration.isPressed)
    }
}

/// The status under the button — Android's `statusTitle`/`statusSub` matrix: a hand-picked server that
/// stopped answering, no working servers (direct vs. held traffic, filtered or not), the retry
/// countdown, no network, the limited-network state, "last connected" or "never" while off, and the
/// service's own error when there is one. The line ticks so the countdown and the "ago" stay live.
private struct StatusText: View {
    var model: MainModel

    var body: some View {
        TimelineView(.periodic(from: .now, by: model.phase == .off ? 30 : 1)) { _ in
            VStack(spacing: 2) {
                HStack(spacing: 8) {
                    if model.phase == .connected { FlagView(code: model.current?.country, height: 13) }
                    Text(title).font(Fonts.display(18, bold: true)).foregroundStyle(Palette.text).textSelection(.enabled)
                }
                Text(subtitle).font(Fonts.body(13)).foregroundStyle(Palette.text).lineLimit(1).textSelection(.enabled)
                // The line is always reserved (FigureView's " " trick): the controls below must not pop
                // up and down as the group · protocol detail comes and goes with the phase.
                Text(thirdLine ?? " ").font(Fonts.body(11)).foregroundStyle(Palette.textSecondary).lineLimit(1).textSelection(.enabled)
            }
            .multilineTextAlignment(.center)
        }
    }

    /// The server the engine reports as hand-picked (`EngineStatus.selectedId`) — the kept pick the
    /// "chosen by hand" subtitles name.
    private var selected: ServerItem? {
        model.lastEngine?.selectedId.flatMap { id in model.servers.first { $0.id == id } }
    }

    /// "5 m", "42 s" until the next try (`EngineStatus.nextRetryAt`), "—" once the moment passed.
    private var retryText: String {
        guard let at = model.nextRetryAt else { return "—" }
        return durationText(max(0, at.timeIntervalSinceNow))
    }

    private var thirdLine: String? {
        guard let s = model.current, model.phase == .connected || model.phase == .searching, model.scan == nil else {
            return model.engineError
        }
        let line = [model.groupName(s.groupId), s.proto].filter { !$0.isEmpty }.joined(separator: " · ")
        return line.isEmpty ? model.engineError : line
    }

    private var title: String {
        switch model.phase {
        case .off: return L("status_off")
        case .searching: return L("status_searching")
        case .connected:
            if model.engineUnanswered { return L("status_unanswered") }
            return [model.current?.country.map { shortCountryName($0) }, model.current?.ms.map { L("ms", $0) }].compactMap { $0 }
                .filter { !$0.isEmpty }.joined(separator: " · ")
        case .direct, .paused:
            if model.engineNoServers { return L("status_no_servers") }
            return selected != nil ? L("status_unanswered") : L("status_unavailable")
        case .noNetwork: return L("status_no_network")
        case .limited: return L("status_limited")
        }
    }

    private var subtitle: String {
        switch model.phase {
        case .off:
            return model.lastConnected.map { L("status_last_connected", agoText($0)) } ?? L("status_never_connected")
        case .searching:
            return model.scan.map { L("status_checked", $0.done, $0.total) } ?? model.current.map { model.displayName($0) } ?? ""
        case .connected:
            if model.engineUnanswered {
                return L("status_unanswered_sub", selected.map { model.displayName($0) } ?? "")
            }
            return model.current.map { model.displayName($0) } ?? ""
        case .direct:
            if model.engineNoServers {
                return L(model.countries.isEmpty ? "status_no_servers_sub" : "status_no_servers_country_sub")
            }
            if selected != nil {
                return L("status_selected_direct_sub", model.displayName(selected!), retryText)
            }
            return L("status_direct_sub", retryText)
        case .paused:
            if model.engineNoServers {
                return L(model.countries.isEmpty ? "status_no_servers_paused_sub" : "status_no_servers_country_paused_sub")
            }
            if selected != nil {
                return L("status_selected_paused_sub", model.displayName(selected!), retryText)
            }
            return L("status_paused_sub", retryText)
        case .noNetwork: return L("status_no_network_sub")
        case .limited:
            return L(model.enginePaused ? "status_limited_paused_sub" : "status_limited_sub", retryText)
        }
    }

    /// "5 m", "42 s", "2 h" ago — the "Last connected" figure's age.
    private func agoText(_ date: Date) -> String {
        let min = Int(Date().timeIntervalSince(date) / 60)
        if min < 1 { return L("ago_now") }
        if min < 60 { return L("ago_min", min) }
        return L("ago_h", min / 60)
    }
}

// MARK: Controls under the button (Android's chips)

private struct RoutingButton: View {
    @Bindable var model: MainModel

    var body: some View {
        MacButton(title: L("routing"), symbol: "arrow.triangle.branch", accent: model.routing) {
            withAnimation(.easeInOut(duration: 0.2)) { model.setRouting(!model.routing) }
        }
        .fixedSize()
    }
}

private struct StrategyButton: View {
    @Bindable var model: MainModel
    @Environment(MenuState.self) private var menus

    var body: some View {
        // The menu opens from the button's frame (a Button would swallow the click).
        PickerButton(chevron: .down) {
            Text(model.strategy.title).lineLimit(1)
        }
        .fixedSize()
        .appMenuOnClick(menus) {
            Strategy.allCases.map { s in
                MenuItem(title: s.title, sub: s.description, selected: s == model.strategy) { model.setStrategy(s) }
            }
        }
    }
}

private struct PingButton: View {
    var scan: ScanProgress?
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            // Hugs the label: the ring + "n/m" a check shows is narrower than "Ping all", and a
            // reserved minimum left dead space on the button's right (owner, 2026-11-11).
            PingAllLabel(scan: scan).padding(.horizontal, 9)
        }
        .buttonStyle(MacButtonStyle())
        .fixedSize()
    }
}

// MARK: Right: figures, map, list

private struct ServersColumn: View {
    @Bindable var model: MainModel
    @State private var frames: [String: CGRect] = [:]
    @Environment(\.snapshot) private var snapshot
    @Environment(MenuState.self) private var menus

    /// The search's text lives in `MenuState.serversQuery` (Android keeps per-screen list state —
    /// this column is destroyed on every section switch); the leading-space rule lives in
    /// `SearchField` (the panel's own field applies it too).
    private var queryText: Binding<String> {
        @Bindable var menus = menus
        return $menus.serversQuery
    }

    var body: some View {
        let rows = model.sorted.filter { model.matches($0, menus.serversQuery) }
        VStack(spacing: 0) {
            Figures(model: model)
                .padding(.bottom, 12)
            Heatmap(model: model)
            HStack(spacing: 8) {
                // The count of the servers the screen shows (Android `Repo.visible`), not the whole
                // list — hidden ones live on the Groups screen.
                Text(L("servers_header", model.visible.count)).font(Fonts.body(12, bold: true)).foregroundStyle(Palette.textSecondary).textSelection(.enabled)
                Spacer()
                SearchField(text: queryText).frame(width: 160)
            }
            .padding(.leading, 4)
            .padding(.top, 12)
            .padding(.bottom, 4)
            // The pause card sits over the list like Android's (`Phase.PAUSED || paused`, once per
            // install — "Got it" remembers, "Change" opens the server-selection settings).
            if (model.phase == .paused || model.enginePaused), !model.pauseCardSeen {
                PauseCard(model: model)
                    .padding(.bottom, 6)
            }
            if model.servers.isEmpty {
                // The zero state (Android `EmptyItem`): the words and the "+" that starts it.
                EmptyCard(title: L("empty_title"), text: L("empty_text"), symbol: "plus") {
                    menus.show(dialog: AnyView(AddDialog(model: model)))
                }
                .padding(.top, 18)
            } else if !model.countries.isEmpty,
                      !model.servers.contains(where: { model.countries.contains(countryOf($0)) }) {
                // The countries filter emptied the list (Android `CountryEmptyHint`): a tap opens
                // the country list again.
                EmptyCard(title: L("empty_country_title", chosenCountryNames),
                          text: L("empty_country_text"), symbol: "globe") {}
                .appPopupOnClick(menus) { AnyView(CountriesPopup(model: model)) }
                .padding(.top, 18)
            } else if snapshot {
                // ImageRenderer draws no ScrollView content: the first rows, clipped.
                SnapshotClip {
                    VStack(spacing: 2) {
                        ForEach(rows) { s in ServerRow(model: model, server: s, hover: false) }
                    }
                }
            } else {
                ScrollViewReader { proxy in
                    // The row under the pointer comes from the rows' frames and the pointer (`HoverScroll`): rows
                    // differ in height once one is opened.
                    HoverScroll { pointer in
                        // Rows overlap transiently while a card's height animates; a dictionary's order
                        // is not stable, so the upper of two candidates always wins and the hover
                        // can't flicker between them.
                        let hovered = pointer.flatMap { p in
                            frames.filter { $0.value.minY <= p && p < $0.value.maxY }.min { $0.value.minY < $1.value.minY }?.key
                        }
                        LazyVStack(spacing: 2) {
                            ForEach(rows) { s in
                                ServerRow(model: model, server: s, hover: hovered == s.id).id(s.id).rowFrame(s.id, in: "servers")
                            }
                        }
                        .coordinateSpace(name: "servers")
                        .onPreferenceChange(RowFrames.self) { frames = $0 }
                        .padding(.trailing, 14)
                    }
                    .scrollIndicators(.automatic)
                    // The scroller sits in the column's right margin, outside the rows: they end level with the search
                    // field above, and it covers no ping.
                    .padding(.trailing, -14)
                    // A server picked outside the list — on the map — is brought into view, minimally (no
                    // anchor scrolls the least distance that makes the row visible). The list's own tap
                    // (`pickInList`) asks for none: opening the details must not shift the list.
                    .onChange(of: model.pickedId) { _, id in
                        defer { model.pickedFromList = false }
                        guard let id, !model.pickedFromList else { return }
                        withAnimation(.easeInOut(duration: 0.25)) { proxy.scrollTo(id) }
                    }
                }
            }
        }
        .padding(.top, 18)
        .padding(.horizontal, 14)
        // The column's dead areas (paddings, the gaps around the figures and the search row)
        // drag the window like the sidebar's title row; claimed views — the map, the list —
        // keep their own presses.
        .background { WindowDrag(menus: menus) }
    }
}

private extension ServersColumn {
    /// "France, USA" or "-" → "Unknown country" — the countries in effect, sorted by name.
    var chosenCountryNames: String {
        model.countries.map { $0 == unknownCountryCode ? L("country_unknown") : shortCountryName($0) }
            .sorted().joined(separator: ", ")
    }
}

/// The pause card (Android `PauseCard`): traffic held while no server works — "Got it" dismisses it for
/// good (the `pause_card_seen` pref), "Change" opens the server-selection settings where the no-server
/// action lives.
private struct PauseCard: View {
    @Bindable var model: MainModel

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(L("pause_card_title")).font(Fonts.body(13, bold: true)).foregroundStyle(Palette.text).textSelection(.enabled)
            Text(L("pause_card_text")).font(Fonts.body(11.5)).foregroundStyle(Palette.textSecondary)
                .fixedSize(horizontal: false, vertical: true).textSelection(.enabled)
            HStack(spacing: 6) {
                Spacer()
                MacButton(title: L("pause_card_change")) {
                    model.pauseCardSeen = true
                    model.section = .settings
                    model.settingsPage = .server
                }
                MacButton(title: L("pause_card_ok"), accent: true) { model.pauseCardSeen = true }
            }
        }
        .padding(10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: Dims.cardRadius).fill(Palette.card))
        .overlay(RoundedRectangle(cornerRadius: Dims.cardRadius).strokeBorder(Palette.outline.opacity(0.7), lineWidth: 1))
    }
}

/// A card that takes the rows' place when there's nothing to list (Android `EmptyItem` and the
/// country-empty hint): a symbol, a title, a grey line — a tap runs [action].
private struct EmptyCard: View {
    var title: String
    var text: String
    var symbol: String
    var action: () -> Void
    @State private var hover = false

    var body: some View {
        VStack(spacing: 6) {
            Image(systemName: symbol).font(.system(size: 22, weight: .medium)).foregroundStyle(Palette.textSecondary)
            Text(title).font(Fonts.body(14, bold: true)).foregroundStyle(Palette.text).textSelection(.enabled)
            Text(text).font(Fonts.body(12)).foregroundStyle(Palette.textSecondary)
                .multilineTextAlignment(.center).fixedSize(horizontal: false, vertical: true).textSelection(.enabled)
        }
        .padding(.vertical, 22).padding(.horizontal, 16)
        .frame(maxWidth: .infinity)
        .background(RoundedRectangle(cornerRadius: Dims.cardRadius).fill(hover ? Palette.cardStrong.opacity(0.6) : Palette.card.opacity(0.55)))
        .overlay(RoundedRectangle(cornerRadius: Dims.cardRadius).strokeBorder(Palette.outline.opacity(0.7), lineWidth: 1))
        .contentShape(Rectangle())
        .onHover { hover = $0 }
        .onTapGesture(perform: action)
    }
}

/// The three figures of Android's cards, in one row divided by hairlines (the window and the menu bar panel).
struct Figures: View {
    var model: MainModel

    var body: some View {
        // The figures count the servers the screen is about (Android `Repo.visible`): the active
        // subset, not every stored server — hidden and filtered-out ones would pad the denominator.
        let shown = model.visible.filter(\.checked)
        let available = shown.filter { $0.ms != nil }.count
        let best = shown.compactMap(\.ms).min()
        HStack(spacing: 0) {
            // When the shown pings were taken; nothing while no server has been checked yet.
            let when = shown.isEmpty ? nil : ago(model.checkedAt)
            FigureView(label: L("stat_available"), value: "\(available) / \(model.visible.count)", ms: nil, sub: when)
            Hairline(vertical: true).padding(.vertical, 4)
            FigureView(label: L("stat_best"), value: best.map { L("ms", $0) } ?? "—", ms: best, sub: when)
            Hairline(vertical: true).padding(.vertical, 4)
            FigureView(label: L("stat_battery"),
                       value: model.phase == .connected ? model.batteryRate.map { L("stat_battery_pct", String(format: "%.1f", $0)) } ?? "—" : "—",
                       ms: nil,
                       sub: model.phase != .connected ? nil : model.batteryError.map {
                           L("stat_pm_over", L("stat_battery_pct", String(format: "%.1f", $0)), L("dur_m", model.batteryMinutes))
                       })
        }
        .fixedSize(horizontal: false, vertical: true)
    }

    private func ago(_ date: Date) -> String {
        let min = Int(Date().timeIntervalSince(date) / 60)
        if min < 1 { return L("ago_now") }
        if min < 60 { return L("ago_min", min) }
        return L("ago_h", min / 60)
    }
}

private struct FigureView: View {
    var label: String
    var value: String
    var ms: Int?
    var sub: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 1) {
            Text(label).font(Fonts.body(11)).foregroundStyle(Palette.textSecondary).textSelection(.enabled)
            HStack(spacing: 6) {
                if let ms { PingDot(ms: ms) }
                withSmallUnit(value, size: 17) { Fonts.display($0, bold: true) }
                    .foregroundStyle(Palette.text).lineLimit(1).minimumScaleFactor(0.6).textSelection(.enabled)
            }
            Text(sub ?? " ").font(Fonts.body(11)).foregroundStyle(Palette.textSecondary).lineLimit(1).minimumScaleFactor(0.8).textSelection(.enabled)
        }
        .padding(.horizontal, 12)
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// One server in the main list: flag, name, group · protocol in grey, the ping with its colour. The server in use is
/// filled with the accent; a click picks a server (here or on the map) — a light fill and, under it, what is known about
/// it with its actions, Connect among them (no double click: owner, 2026-10-23). Right click opens the server's menu.
struct ServerRow: View {
    var model: MainModel
    var server: ServerItem
    var hover: Bool
    @Environment(MenuState.self) private var menus

    var body: some View {
        let current = model.phase == .connected && server.id == model.currentId
        let picked = model.pickedId == server.id
        VStack(spacing: 0) {
            header(current: current)
                .background(RoundedRectangle(cornerRadius: Dims.cornerRadius).fill(current ? Palette.accent : hover && !picked ? Palette.card : .clear))
                .contentShape(RoundedRectangle(cornerRadius: Dims.cornerRadius))
                .onTapGesture {
                    // Every click toggles the details, a fast run of them too — each starts from wherever the
                    // animation is (owner, 2026-11-07). `pickInList`, not a `pickedId` write: the row is under
                    // the pointer — no scroll into view.
                    withAnimation(.easeInOut(duration: 0.2)) { model.pickInList(picked ? nil : server.id) }
                }
            // The details stay in the tree at height 0, clipped (DisclosureGroup's own reveal): a
            // transition's move drew them over the header and the rows above. Fast toggles still work
            // mid-animation — the height just animates from wherever it is.
            ServerDetails(model: model, server: server, current: current)
                .frame(height: picked ? nil : 0, alignment: .top)
                .clipped()
                .allowsHitTesting(picked)
                .accessibilityHidden(!picked)
        }
        .background(RoundedRectangle(cornerRadius: Dims.cardRadius).fill(picked ? Palette.selected : .clear))
        .appContextMenu(menus) { serverMenu(model, server, menus) }
    }

    private func header(current: Bool) -> some View {
        // Ordinary scans skip limited-network servers; keep their tower styling after a manual check too.
        let failed = server.checked && server.ms == nil && !server.limited
        let probing = model.probingIds.contains(server.id) || model.scan?.probing.contains(server.id) == true
        // Queued for the scan in progress (`EngineStatus.pending`): its stale result dims until its
        // turn, as Android's rows fade the old figure.
        let queued = model.pendingIds.contains(server.id)
        return HStack(spacing: 9) {
            ServerLine(model: model, server: server,
                       detail: detail,
                       light: current, fade: failed ? .down : .none, mark: server.limited)
            Spacer(minLength: 6)
            if probing {
                Spinner(color: current ? Palette.onAccent : Palette.accent, lineWidth: 1.5).frame(width: 12, height: 12)
            } else {
                if server.ms != nil { PingDot(ms: server.ms) }
                PingText(server: server, light: current)
            }
        }
        .opacity(queued ? 0.45 : 1)
        .padding(.horizontal, 10)
        .frame(height: Dims.rowH)
        .help([model.displayName(server), server.country.map { shortCountryName($0) } ?? ""].filter { !$0.isEmpty }.joined(separator: " · "))
    }

    /// "group · protocol", the country and the link's unprotected side joined on — Android's row
    /// carries the country and the "not encrypted" / "certificate unchecked" notes too.
    private var detail: String {
        var parts = [model.groupName(server.groupId), server.proto]
        if let country = server.country { parts.append(shortCountryName(country)) }
        parts.append(contentsOf: server.insecure.sorted { $0.rawValue < $1.rawValue }.map(\.label))
        return parts.filter { !$0.isEmpty }.joined(separator: " · ")
    }
}

/// What opens under a picked server: where it is and what it is, how it did over the last day, its pings as a small
/// line, and its actions.
private struct ServerDetails: View {
    var model: MainModel
    var server: ServerItem
    var current: Bool
    @Environment(MenuState.self) private var menus

    var body: some View {
        let points = model.dayStats?.pings[server.id] ?? []
        let ok = points.compactMap(\.ms).sorted()
        let availability = model.dayStats?.servers.first { $0.id == server.id }?.availability
        VStack(alignment: .leading, spacing: 8) {
            Grid(alignment: .leading, horizontalSpacing: 12, verticalSpacing: 5) {
                ForEach(serverFacts, id: \.label) { fact in
                    GridRow(alignment: .firstTextBaseline) {
                        Text(fact.label).font(Fonts.body(12)).foregroundStyle(Palette.textSecondary)
                            .fixedSize().textSelection(.enabled)
                        Text(fact.value).font(Fonts.body(12)).foregroundStyle(Palette.text)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .fixedSize(horizontal: false, vertical: true).textSelection(.enabled)
                    }
                }
            }
            ForEach(limitedFacts, id: \.self) { line in
                Text(line).font(Fonts.body(12)).foregroundStyle(Palette.textSecondary).textSelection(.enabled)
            }
            HStack(alignment: .bottom, spacing: 18) {
                // Availability truncates, like the same figure on Android's statistics screens.
                Figure(label: L("ss_availability"), value: availability.map { "\(Int($0 * 100))%" } ?? "—")
                Figure(label: L("ss_median"), value: ok.isEmpty ? "—" : L("ms", ok[ok.count / 2]))
                Figure(label: macText("mac.p95", "P95"), value: ok.isEmpty ? "—" : L("ms", ok[min(ok.count - 1, ok.count * 95 / 100)]))
                Spacer(minLength: 8)
                Sparkline(points: points.map(\.ms)).frame(minWidth: 60, maxWidth: 160).frame(height: 30)
            }
            HStack(spacing: 6) {
                if !current {
                    MacButton(title: L("connect"), symbol: "power", accent: true) { model.connect(to: server) }
                }
                if model.backend != nil {
                    ServerProbeButton(model: model, serverId: server.id)
                }
                MacButton(title: L("action_stats"), symbol: "chart.xyaxis.line") { model.openStats(.server(server.id)) }
                MacButton(title: L("action_server_settings"), symbol: "slider.horizontal.3") { model.openSettings(of: server.id) }
                MacButton(symbol: "square.and.arrow.up", help: L("share")) {
                    menus.show(dialog: AnyView(ShareDialog(model: model, scope: .server(server.id))))
                }
            }
        }
        .padding(.horizontal, 10).padding(.top, 6).padding(.bottom, 10)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    /// Label the location, connection address and the name hidden by generic server names.
    private var serverFacts: [(label: String, value: String)] {
        var facts: [(label: String, value: String)] = []
        if let country = server.country { facts.append((L("settings_country"), shortCountryName(country))) }
        if !server.host.isEmpty {
            let host = server.host.contains(":") ? "[\(server.host)]" : server.host
            facts.append((L("info_address"), server.port > 0 ? "\(host):\(server.port)" : host))
        }
        if model.settings.genericNames {
            let link = SharedCode.title(server.linkName.isEmpty ? server.name : server.linkName)
            if link != model.displayName(server) {
                facts.append((macText("mac.server.original_name", "Original name"), link))
            }
        }
        return facts
    }

    /// A limited-network server's live streams, declared limit and fixed count.
    private var limitedFacts: [String] {
        var lines: [String] = []
        if server.limited {
            if current, let live = model.limitedLive {
                let limit = live.limit ?? live.known ?? server.limitedMax
                lines.append(limit.map { macText("mac.limited_live", "\(live.active) of \($0) open") }
                             ?? macText("mac.limited_live_open", "\(live.active) open"))
            } else if let max = server.limitedMax {
                lines.append(macText("mac.limited_limit", "Server's limit: \(max)"))
            }
            if server.limitedStreams > 0 { lines.append(L("limited_n_streams", server.limitedStreams)) }
        }
        return lines
    }

    private struct Figure: View {
        var label: String
        var value: String
        var body: some View {
            VStack(alignment: .leading, spacing: 1) {
                Text(label.trimmingCharacters(in: .whitespaces)).font(Fonts.body(10.5)).foregroundStyle(Palette.textSecondary).lineLimit(1).textSelection(.enabled)
                withSmallUnit(value, size: 14) { Fonts.display($0, bold: true) }.foregroundStyle(Palette.text).textSelection(.enabled)
            }
            .fixedSize()
        }
    }
}

/// A day of pings as a thin line on a log scale, gaps where it gave no reply (marked under the line).
struct Sparkline: View {
    var points: [Int?]

    var body: some View {
        Canvas { ctx, size in
            guard points.count > 1 else { return }
            // Log scale over this server's own range, so a steady server still shows its waves.
            let values = points.compactMap { $0 }
            let lo = log(Double(max(10, (values.min() ?? 30) * 7 / 10))), hi = log(Double(max(40, (values.max() ?? 300) * 3 / 2)))
            func y(_ ms: Int) -> CGFloat { size.height - 2 - CGFloat((log(Double(max(ms, 30))) - lo) / (hi - lo)) * (size.height - 6) }
            var line = Path()
            var pen = false
            for (i, p) in points.enumerated() {
                let x = CGFloat(i) / CGFloat(points.count - 1) * size.width
                if let p {
                    if pen { line.addLine(to: CGPoint(x: x, y: y(p))) } else { line.move(to: CGPoint(x: x, y: y(p))) }
                    pen = true
                } else {
                    pen = false
                    ctx.fill(Path(CGRect(x: x - 0.75, y: size.height - 2, width: 1.5, height: 2)), with: .color(Palette.danger.opacity(0.7)))
                }
            }
            ctx.stroke(line, with: .color(Palette.accent), style: StrokeStyle(lineWidth: 1.3, lineCap: .round, lineJoin: .round))
        }
    }
}

/// Right click on a server (cell or row): Android's server menu, same items, same order.
@MainActor
func serverMenu(_ model: MainModel, _ server: ServerItem, _ menus: MenuState) -> [MenuItem] {
    // Enable/Disable reads the active subset, not only the flag (Android's `off = !isActive`): under
    // auto-visibility or the countries filter an inactive server offers "Enable" — clearing the
    // disabled flag, as Android's `setServerDisabled(!off)` does (the pool still decides from there).
    let off = !model.isActive(server)
    var items = [
        MenuItem(title: L("connect"), symbol: "power") { model.connect(to: server) },
        MenuItem(title: L(off ? "action_enable" : "action_disable"), symbol: off ? "eye" : "eye.slash") {
            if let i = model.servers.firstIndex(where: { $0.id == server.id }) { model.servers[i].disabled = !off }
        },
        MenuItem(title: L("action_stats"), symbol: "chart.xyaxis.line") { model.openStats(.server(server.id)) },
        MenuItem(title: L("action_server_settings"), symbol: "slider.horizontal.3") { model.openSettings(of: server.id) },
        MenuItem(title: L("share"), symbol: "square.and.arrow.up") {
            menus.show(dialog: AnyView(ShareDialog(model: model, scope: .server(server.id))))
        },
    ]
    // Hand-added servers move between groups and can be deleted; a subscription's would come back with its update.
    if server.subscriptionId == nil {
        // Every other group is a valid home — including a subscription's, as on Android.
        let others = model.groups.filter { $0.id != server.groupId }
        if !others.isEmpty {
            items.append(MenuItem(title: L("action_move_group"), symbol: "folder", submenu: others.map { g in
                MenuItem(title: g.name, symbol: "folder") {
                    if let i = model.servers.firstIndex(where: { $0.id == server.id }) { model.servers[i].groupId = g.id }
                }
            }))
        }
        items.append(MenuItem(title: L("delete"), symbol: "trash", danger: true) {
            confirmDelete(menus, L("delete_server_q"), detail: model.displayName(server)) {
                model.servers.removeAll { $0.id == server.id }
            }
        })
    }
    return items
}

/// A delete asks first, in a small dialog that names what goes and says it can't be undone (Android
/// `AppAlertDialog(irreversible)`; [detail] is the target's name or what else goes with it).
@MainActor
func confirmDelete(_ menus: MenuState, _ question: String, detail: String? = nil, _ delete: @escaping () -> Void) {
    menus.show(dialog: AnyView(
        VStack(alignment: .leading, spacing: 8) {
            Text(question).font(Fonts.body(13, bold: true)).foregroundStyle(Palette.text).textSelection(.enabled)
            if let detail {
                Text(detail).font(Fonts.body(12)).foregroundStyle(Palette.textSecondary).lineLimit(2).textSelection(.enabled)
            }
            Text(L("cannot_undo")).font(Fonts.body(11)).foregroundStyle(Palette.danger).textSelection(.enabled)
            HStack(spacing: 6) {
                Spacer()
                MacButton(title: L("cancel")) { menus.closeDialog() }.keyboardShortcut(.cancelAction)
                MacButton(title: L("delete"), accent: true) { menus.closeDialog(); delete() }.keyboardShortcut(.defaultAction)
            }
            .padding(.top, 4)
        }
        .padding(18)
        .frame(width: 300)
    ))
}

/// Rendering for a snapshot (ImageRenderer): scrolling content is laid out plainly. (A plain key: the Command Line
/// Tools have no macro plugins, so no `@Entry`.)
private struct SnapshotKey: EnvironmentKey {
    static let defaultValue = false
}

extension EnvironmentValues {
    var snapshot: Bool {
        get { self[SnapshotKey.self] }
        set { self[SnapshotKey.self] = newValue }
    }
}
