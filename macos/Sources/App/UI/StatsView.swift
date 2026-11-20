import SwiftUI

/// The statistics overview (Android `StatsScreen.kt`) for a wide window: the same tiles, cards and lists, two by two.
struct StatsView: View {
    @Bindable var model: MainModel
    @Environment(MenuState.self) private var menus
    @Environment(\.snapshot) private var snapshot
    /// The demo's made-up day and the activity figures computed from it, kept between `body` runs: both build
    /// hundreds of points, and `body` re-evaluates on every model change. A reference type, so filling it asks for
    /// no repaint of its own (like `OrbitSky`).
    @State private var demo = DemoStats()

    private final class DemoStats {
        var key = ""
        var data: StatsData?
        var activity: ActivityData?
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            // The same header in every state: during the first load (and with nothing to show) the
            // controls stay on but dimmed and untouchable, instead of popping in when data lands.
            header
                .allowsHitTesting(data != nil)
                .opacity(data == nil ? 0.5 : 1)
            Group {
                if let d = data {
                    loaded(d)
                } else if model.statsLoading {
                    loadingRow
                } else {
                    Text(L("st_no_data")).font(Fonts.body(13)).foregroundStyle(Palette.textSecondary).textSelection(.enabled)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            // A failed refresh of what is on screen is said, not silent — floating over the
            // content's top so its coming and going shifts nothing (the header keeps the drag
            // strip, the page keeps its layout).
            .overlay(alignment: .top) {
                if let error = model.statsError { banner(error) }
            }
        }
        .background(Palette.background)
        // The activity tab's reply rides its own request (the overview's isn't bloated with it):
        // switching to it asks the refresh loop for the new window's figures.
        .onChange(of: model.statsTab) { model.statsChanged() }
    }

    /// The spinner with its caption: centred on the first load, over the dimmed page on a refresh.
    private var loadingRow: some View {
        HStack(spacing: 8) {
            Spinner().frame(width: 14, height: 14)
            Text(String(localized: "mac.loading")).font(Fonts.body(13)).foregroundStyle(Palette.textSecondary).textSelection(.enabled)
        }
    }

    /// The error line with the retry `statsError`'s comment promises (a fresh request, forced past
    /// the refresh loop's throttle).
    private func banner(_ error: String) -> some View {
        HStack(spacing: 10) {
            Text(error).font(Fonts.body(11)).foregroundStyle(Palette.danger).lineLimit(2)
                .textSelection(.enabled).frame(maxWidth: .infinity, alignment: .leading)
            Button(macText("mac.stats.retry", "Try again")) { model.statsChanged() }
                .buttonStyle(.plain)
                .font(Fonts.body(11, bold: true))
                .foregroundStyle(Palette.accentStrong)
        }
        .padding(.horizontal, 10).padding(.vertical, 5)
        .background(RoundedRectangle(cornerRadius: 7).fill(Palette.card))
        .overlay(RoundedRectangle(cornerRadius: 7).strokeBorder(Palette.outline, lineWidth: 1))
        .shadow(color: .black.opacity(0.08), radius: 2, y: 1)
        .padding(.horizontal, 20).padding(.top, 6)
    }

    /// The demo makes its own day up — once per period / range / server set (`demo` above); the real one comes
    /// from the service while the screen is open.
    private var data: StatsData? {
        guard model.backend != nil else {
            let key = [String(describing: model.statsPeriod),
                       model.statsRange.map { "\($0.lowerBound.timeIntervalSince1970)-\($0.upperBound.timeIntervalSince1970)" } ?? "",
                       model.servers.map { "\($0.id):\($0.ms ?? -1)" }.joined(separator: ",")].joined(separator: "|")
            if demo.key != key || demo.data == nil {
                demo.key = key
                demo.data = StatsData.demo(model, period: model.statsPeriod, range: model.statsRange)
                demo.activity = nil
            }
            return demo.data
        }
        return model.stats
    }

    /// The activity tab's demo figures, computed once for the day `demo` currently holds.
    private func demoActivity(_ d: StatsData) -> ActivityData {
        if demo.activity == nil { demo.activity = ActivityData.demo(d) }
        return demo.activity!
    }

    /// The page under the header, dimmed and locked while its refresh is on the wire (the old data
    /// stays under a spinner until the new one is filed).
    @ViewBuilder private func loaded(_ d: StatsData) -> some View {
        Group {
            if snapshot {
                GeometryReader { g in page(d).frame(width: g.size.width, alignment: .top) }.clipped()
            } else {
                // A scroll view of its own per page: a new target or tab opens at the top, a refresh
                // of the same page keeps the offset it had.
                ScrollView { page(d).padding(.bottom, 16).padding(.trailing, 6) }
                    .scrollIndicators(.automatic)
                    .id(pageKey)
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 14)
        .opacity(model.statsLoading ? 0.4 : 1)
        .allowsHitTesting(!model.statsLoading)
        .overlay {
            if model.statsLoading { loadingRow }
        }
    }

    /// The scroll view's identity, written out because `StatsTarget` is `Equatable` only.
    private var pageKey: String {
        switch liveTarget {
        case .server(let id): "s:\(id)"
        case .subscription(let id): "sub:\(id)"
        case nil: "overview:\(model.statsTab)"
        }
    }

    /// The target on screen. A server's statistics stay reachable after it is deleted — the service
    /// keeps its rows and answers `s:<id>` for any id (F-STATS-0073), so a missing server is shown,
    /// not fallen back to the overview (Android's `ServerStatsScreen` works with `server == null`
    /// the same way). A gone subscription ends its page like Android's `onBack` — the overview.
    private var liveTarget: StatsTarget? {
        switch model.statsTarget {
        case .server: model.statsTarget
        case .subscription(let id): model.subscriptions.contains { $0.id == id } ? model.statsTarget : nil
        case nil: nil
        }
    }

    /// The screen's top bar: title, the overview's tabs, the period segments; one bar, and in
    /// longer languages the periods move to a line of their own.
    private var header: some View {
        VStack(spacing: 0) {
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 12) {
                    title
                    tabs
                    Spacer(minLength: 8)
                    periods
                }
                .frame(height: 52)
                VStack(alignment: .leading, spacing: 8) {
                    HStack(spacing: 12) { title; tabs; Spacer(minLength: 0) }
                    periods
                }
                .padding(.vertical, 12)
            }
            .padding(.leading, 20).padding(.trailing, 14)
            // The header's own drag surface — the shell's strip sits under content the
            // pages' hit regions claim first (see ScreenHeader).
            .background { WindowDrag(menus: menus) }
            Hairline()
        }
    }

    /// "Statistics", or the server's / subscription's name with a way back to the overview.
    @ViewBuilder private var title: some View {
        if let target = liveTarget {
            HStack(spacing: 8) {
                Image(systemName: "chevron.left").font(.system(size: 12, weight: .semibold)).foregroundStyle(Palette.textSecondary)
                    .frame(width: 24, height: 24)
                    .background(RoundedRectangle(cornerRadius: 6).fill(Palette.card))
                    .contentShape(Rectangle())
                    .onTapGesture { model.statsTarget = nil }
                    .help(L("stats"))
                switch target {
                case .server(let id):
                    // A deleted server keeps its page under its id — no flag, no list name for it.
                    let s = model.servers.first { $0.id == id }
                    FlagView(code: s?.country, height: 13)
                    Text(s.map { model.displayName($0) } ?? id).font(Fonts.display(19, bold: true)).foregroundStyle(Palette.text).lineLimit(1).textSelection(.enabled)
                case .subscription(let id):
                    Image(systemName: "dot.radiowaves.up.forward").font(.system(size: 13, weight: .semibold)).foregroundStyle(Palette.accent)
                    Text(model.subscriptions.first { $0.id == id }?.name ?? "").font(Fonts.display(19, bold: true))
                        .foregroundStyle(Palette.text).lineLimit(1).textSelection(.enabled)
                }
            }
            // No fixedSize: a long name must reach its ellipsis, not push the tabs off the bar.
        } else {
            Text(L("stats")).font(Fonts.display(19, bold: true)).foregroundStyle(Palette.text).fixedSize().textSelection(.enabled)
        }
    }

    @ViewBuilder private var tabs: some View {
        if liveTarget == nil {
            Segmented(items: [(0, L("stats_tab_servers")), (1, L("stats_tab_activity"))], selection: $model.statsTab).fixedSize()
        }
    }

    private func openRange() {
        // The range the dialog starts on (Android `PeriodChips`: the last three hours, not a day).
        let initial = model.statsRange ?? Date().addingTimeInterval(-3 * 3600)...Date()
        menus.show(dialog: AnyView(RangeDialog(from: initial.lowerBound, to: initial.upperBound,
                                             statsDays: model.settings.statsDays) { range in
            model.setStatsRange(range)
            model.statsPeriod = .custom
            model.statsChanged()
        }))
    }

    private var periods: some View {
        // "Custom range" opens the range dialog; once chosen it shows the range itself.
        let choose = Binding<Period>(get: { model.statsPeriod }, set: { p in
            if p == .custom { openRange() } else {
                // A tap on the period already on asks for nothing — the figures would be the same.
                guard p != model.statsPeriod else { return }
                model.statsPeriod = p
                // The saved range is let go, as on Android (it returns by choosing Custom again).
                if model.statsRange != nil { model.setStatsRange(nil) }
                model.statsChanged()
            }
        })
        return Segmented(items: Period.allCases.map { p in
            (p, p == .custom && model.statsPeriod == .custom ? model.statsRange.map(rangeText) ?? p.title : p.title)
        }, selection: choose).fixedSize()
    }

    @ViewBuilder private func page(_ d: StatsData) -> some View {
        switch liveTarget {
        case .server(let id):
            // The server itself may be gone (deleted): its statistics still draw from the reply.
            ServerStatsView(model: model, serverId: id, server: model.servers.first { $0.id == id }, d: d)
        case .subscription(let id):
            if let sub = model.subscriptions.first(where: { $0.id == id }) { SubscriptionStatsView(model: model, sub: sub, d: d) }
            else { overviewPage(d) }
        case nil:
            overviewPage(d)
        }
    }

    /// The landing page: the servers' cards, or the activity tab.
    @ViewBuilder private func overviewPage(_ d: StatsData) -> some View {
        if model.statsTab == 1 {
            // The Mac's own energy, measured by the service (ENERGY-IMPL): the real figures when the
            // activity reply carried any, `act_no_data` while the tables are still empty (Android's
            // same empty test); the demo keeps its made-up day.
            if model.backend != nil {
                if let a = d.activity {
                    ActivityView(d: d, a: a)
                } else {
                    Text(L("act_no_data")).font(Fonts.body(13)).foregroundStyle(Palette.textSecondary)
                        .textSelection(.enabled)
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top).padding(.top, 40)
                }
            } else {
                ActivityView(d: d, a: demoActivity(d))
            }
        } else { content(d) }
    }

    /// The sort of the server / subscription rows (Android `ListHeader`'s chips): availability,
    /// ping, time in use. Session state, as on Android (`rememberSaveable`).
    @State private var sort = 0
    @State private var subSort = 0

    private func content(_ d: StatsData) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            // Android `Overview.empty`: nothing recorded in the period is one quiet line, not
            // tiles of em-dashes over empty cards.
            if d.isEmpty {
                Text(L("st_no_data")).font(Fonts.body(13)).foregroundStyle(Palette.textSecondary)
                    .padding(.vertical, 24).textSelection(.enabled)
            } else {
            Tiles(d: d, period: model.statsPeriod)
            StatsCard(title: L("sec_states"), trailing: AnyView(PhaseLegend(d: d))) {
                StateStrip(spans: d.recordedPhases, from: d.dataFrom, to: d.to, dataStart: d.dataStart)
            }
            HStack(alignment: .top, spacing: 10) {
                if !d.scans.isEmpty {
                    StatsCard(title: L("sec_available_chart"), note: L("available_chart_note")) {
                        let total = Double(d.scans.map(\.total).max() ?? 1)
                        // Scans don't always check every server (auto-visibility, battery saving):
                        // the grey envelope is how many were checked, the coloured area the answers.
                        TimeChart(series: [
                            ChartSeries(name: L("st_checked"), color: Palette.textSecondary, points: d.scans.map { ($0.at, Double($0.total)) }, fill: true, fillOpacity: 0.28),
                            ChartSeries(name: L("st_answered"), color: Palette.accent, points: d.scans.map { ($0.at, Double($0.answered)) }, fill: true),
                        ], bands: d.chartBands, from: d.dataFrom, to: d.to, scale: .linear(total), stepped: true,
                                  connectGapMs: 30 * 60, clearLabel: L("band_clear_server"), markers: d.markers,
                                  probeFormat: { countText($0) })
                    }
                }
                StatsCard(title: L("sec_ping_chart"), unit: L("ms_unit")) {
                    PingSection(model: model, d: d, ids: nil, bestLabel: L("best_of_all"),
                                bestPoints: d.best.map { ($0.at, $0.ms.map(Double.init)) })
                }
            }
            HStack(alignment: .top, spacing: 10) {
                // Subscriptions as a whole: their servers' availability, ping and connected time
                // through them — shares weighted by how long each was checked (Android's
                // Σanswered/Σweight, not a mean of means).
                let subRows: [(sub: SubscriptionItem, count: Int, share: Double?, avg: Int?, time: TimeInterval)] =
                    model.subscriptions.map { sub in
                        let stats = d.servers.filter { $0.server.subscriptionId == sub.id }
                        let weight = stats.reduce(0.0) { $0 + $1.weight }
                        let share = weight == 0 ? nil : stats.reduce(0.0) { $0 + $1.answered } / weight
                        let w = stats.filter { $0.avg != nil && $0.answered > 0 }
                        let avg = w.isEmpty ? nil : Int((w.reduce(0.0) { $0 + Double($1.avg!) * $1.answered }
                            / w.reduce(0.0) { $0 + $1.answered }).rounded())
                        return (sub, stats.count, share, avg, stats.reduce(0) { $0 + $1.usage })
                    }
                let subs = subSort == 1 ? subRows.sorted { ($0.avg ?? Int.max) < ($1.avg ?? Int.max) }
                    : subSort == 2 ? subRows.sorted { $0.time > $1.time }
                    : subRows.sorted { ($0.share ?? -1) > ($1.share ?? -1) }
                if !subs.isEmpty {
                    StatsCard(title: L("sec_subs_stats"), note: L("subs_percent_legend"),
                              trailing: AnyView(SortChips(sort: $subSort))) {
                        ForEach(Array(subs.enumerated()), id: \.offset) { _, r in
                            StatRow(icon: .subscription, title: r.sub.name,
                                    sub: L("sub_stat_row", r.count, msText(r.avg))
                                        + (r.time > 0 ? " · " + statsDuration(r.time) : ""),
                                    share: r.share) { model.openStats(.subscription(r.sub.id)) }
                        }
                    }
                }
                StatsCard(title: L("sec_servers_stats"), note: L("servers_percent_legend"),
                          trailing: AnyView(SortChips(sort: $sort))) {
                    // Phantom rows are deleted servers' statistics — reachable over `s:<id>` but not
                    // listed (Android filters aggregates to the servers it knows).
                    let list = d.servers.filter { !$0.phantom }
                    let rows = sort == 1 ? list.sorted { ($0.avg ?? Int.max) < ($1.avg ?? Int.max) }
                        : sort == 2 ? list.sorted { $0.usage > $1.usage }
                        : list.sorted { $0.availability > $1.availability }
                    let limit = snapshot ? 6 : 100
                    ForEach(rows.prefix(limit)) { s in
                        StatRow(icon: .server(s.server.country, s.avg), title: model.displayName(s.server),
                                sub: L("server_row_sub", msText(s.avg), P("samples_n", s.samples))
                                    + (s.usage > 0 ? " · " + statsDuration(s.usage) : ""),
                                share: s.availability) { model.openStats(.server(s.id)) }
                    }
                    if rows.count > limit {
                        Text(L("import_and_more", rows.count - limit))
                            .font(Fonts.body(11)).foregroundStyle(Palette.textSecondary).textSelection(.enabled)
                            .padding(.horizontal, 6).padding(.vertical, 5)
                    }
                }
            }
            CountriesCard(model: model, d: d)
            }
        }
    }
}

/// The three sort chips under a statistics list's title (Android `ListHeader`).
private struct SortChips: View {
    @Binding var sort: Int

    var body: some View {
        HStack(spacing: 6) {
            ForEach(0..<3, id: \.self) { i in
                let label = [L("sort_availability"), L("sort_ping"), L("sort_usage")][i]
                Text(label).font(Fonts.body(11, bold: sort == i))
                    .foregroundStyle(sort == i ? Palette.accentStrong : Palette.textSecondary)
                    .padding(.horizontal, 10).padding(.vertical, 4)
                    .background(Capsule().fill(sort == i ? Palette.accentSoft : Palette.card))
                    .contentShape(Capsule())
                    .onTapGesture { sort = i }
            }
        }
    }
}

/// The countries' metrics the service ships for `target:"countries"` (Android `CountryStats` —
/// the `chosen`/`answered`/`checked` behind its used and stable sorts), as rows in the overview:
/// a country's own share, weighted by its servers' checked slices.
private struct CountriesCard: View {
    var model: MainModel
    var d: StatsData

    var body: some View {
        // Per-country availability from the servers card's own aggregates — the `countries` reply
        // (model.countryStats) adds the all-time chosen counts and the week's stability.
        let byCountry = Dictionary(grouping: d.servers.filter { !$0.phantom }) { countryOf($0.server) }
        let rows: [(code: String, count: Int, share: Double?, avg: Int?, chosen: Int)] = byCountry.map { code, stats in
            let weight = stats.reduce(0.0) { $0 + $1.weight }
            let share = weight == 0 ? nil : stats.reduce(0.0) { $0 + $1.answered } / weight
            let w = stats.filter { $0.avg != nil && $0.answered > 0 }
            let avg = w.isEmpty ? nil : Int((w.reduce(0.0) { $0 + Double($1.avg!) * $1.answered }
                / w.reduce(0.0) { $0 + $1.answered }).rounded())
            return (code, stats.count, share, avg, model.countryStats?[code]?.chosen ?? 0)
        }
        .sorted { a, b in (a.share ?? -1) != (b.share ?? -1) ? (a.share ?? -1) > (b.share ?? -1)
            : a.chosen != b.chosen ? a.chosen > b.chosen
            : countryName(a.code) < countryName(b.code) }
        return Group {
            if !rows.isEmpty {
                StatsCard(title: macText("mac.stats.countries", "Countries"), note: L("servers_percent_legend")) {
                    ForEach(rows, id: \.code) { r in
                        StatRow(icon: .server(r.code, r.avg), title: countryName(r.code),
                                sub: L("server_row_sub", msText(r.avg), P("country_servers", r.count))
                                    + (r.chosen > 0 ? " · " + P("country_used_n", r.chosen) : ""),
                                share: r.share) { }
                    }
                }
            }
        }
        .onAppear { model.refreshCountryStats() }
    }
}

/// A titled card (Android `Card` in the statistics) at the Mac's density: a hairline box, the title in bold system text,
/// an optional unit or legend on its line, a grey note.
struct StatsCard<Content: View>: View {
    var title: String
    var unit: String? = nil
    var note: String? = nil
    var trailing: AnyView? = nil
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text(title).font(Fonts.body(13, bold: true)).foregroundStyle(Palette.text).textSelection(.enabled)
                if let unit { Text(unit).font(Fonts.body(11)).foregroundStyle(Palette.textSecondary).textSelection(.enabled) }
                Spacer(minLength: 8)
                if let trailing { trailing }
            }
            if let note { Text(note).font(Fonts.body(11)).foregroundStyle(Palette.textSecondary).fixedSize(horizontal: false, vertical: true).textSelection(.enabled) }
            content
        }
        .padding(.horizontal, 12).padding(.vertical, 10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 8).fill(Palette.background))
        .overlay(RoundedRectangle(cornerRadius: 8).strokeBorder(Palette.outline, lineWidth: 1))
    }
}

/// The eight tiles of the overview, four to a row.
private struct Tiles: View {
    var d: StatsData
    var period: Period

    var body: some View {
        let on = d.onTime
        let connected = d.duration(.connected)
        let without = d.duration(.direct) + d.duration(.paused) + d.duration(.limited)
        // "All time" reads "since <date>" where shorter periods read "of <the window>" (Android `st_since`).
        let periodText = period == .all ? L("st_since", d.dataFrom.formatted(.dateTime.day().month(.abbreviated).year()))
            : L("st_of", statsDuration(d.to.timeIntervalSince(d.from)))
        let tiles: [(String, String, String?)] = [
            (L("st_on_time"), statsDuration(on), periodText),
            (L("st_via_server"), pct(connected, on), L("st_via_server_sub", statsDuration(connected), statsDuration(without))),
            (L("st_found"), d.found.map { String(format: "%.1f", locale: Locale.current, $0.place) } ?? "—",
             d.found.map { String.localizedStringWithFormat(Bundle.main.localizedString(forKey: "st_found_sub", value: nil, table: "Shared"), $0.count, $0.of) }
                ?? L("st_found_none")),
            // "once every …" needs a nonzero on-time — losses with none would read "every 0 s".
            (L("st_failures"), countText(Double(d.losses)), d.losses == 0 ? L("st_fail_none") : on > 0 ? L("st_fail_every", statsDuration(on / Double(d.losses))) : nil),
            (L("st_switches"), countText(Double(d.switches.failover + d.switches.faster + d.switches.manual)),
             L("st_switch_breakdown", d.switches.failover, d.switches.faster, d.switches.manual)),
            (L("st_ping"), d.health.median > 0 ? msText(d.health.median) : "—",
             d.health.median > 0 ? L("st_ping_sub", msText(d.health.p95), msText(d.health.jitter)) : nil),
            // Share of checked servers that answered (by time, skipping no-network stretches), and
            // a typical scan — the weighted figures of `scanShare`, not raw sums (Android's tile).
            (L("st_available"), d.availableShare.map { "\(Int(($0 * 100).rounded()))%" } ?? "—",
             d.scanShare.map { L("st_available_sub2", Int($0.available.rounded()), Int($0.total.rounded())) }),
            (L("st_traffic"), bytesText(d.trafficProxy), L("st_traffic_sub", bytesText(d.trafficDirect))),
        ]
        TileGrid(tiles: tiles)
    }
}

private func pct(_ part: TimeInterval, _ whole: TimeInterval) -> String { whole <= 0 ? "—" : "\(Int(part * 100 / whole))%" }

private struct PhaseLegend: View {
    var d: StatsData

    var body: some View {
        let names: [(Phase, String)] = [(.connected, L("ph_connected")), (.searching, L("ph_searching")), (.direct, L("ph_direct")),
                                        (.paused, L("ph_paused")), (.noNetwork, L("ph_no_network")), (.limited, L("ph_limited"))]
        FlowCells(spacing: 12) {
            ForEach(names.filter { d.duration($0.0) > 0 }, id: \.1) { p, name in
                HStack(spacing: 5) {
                    Circle().fill(phaseColor(p)).frame(width: 7, height: 7)
                    Text("\(name) \(statsDuration(d.duration(p)))").font(Fonts.body(11)).foregroundStyle(Palette.textSecondary).textSelection(.enabled)
                }
            }
        }
    }
}

enum RowIcon {
    case subscription
    case server(String?, Int?)
}

/// A server or a subscription with its availability bar and percentage (Android `ServerStatRow` / `SubStatRow`).
struct StatRow: View {
    var icon: RowIcon
    var title: String
    var sub: String
    var share: Double?
    /// Opens its own statistics.
    var open: (() -> Void)? = nil
    @State private var hover = false

    var body: some View {
        HStack(spacing: 9) {
            switch icon {
            case .subscription:
                Image(systemName: "dot.radiowaves.up.forward").font(.system(size: 11, weight: .semibold)).foregroundStyle(Palette.accent)
                    .frame(width: 20, height: 14)
            case .server(let country, let avg):
                // The flag on a tile of the server's ping colour (Android `ServerStatRow`).
                FlagView(code: country, height: 13)
                    .frame(width: 26, height: 19)
                    .background(RoundedRectangle(cornerRadius: 5).fill(avg != nil ? Palette.ping(avg) : Palette.card))
            }
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(title).font(Fonts.body(13)).foregroundStyle(Palette.text).lineLimit(1).layoutPriority(1)
                    Text(sub).font(Fonts.body(11)).foregroundStyle(Palette.textSecondary).lineLimit(1)
                }
                UsageBar(share: share ?? 0)
            }
            // Availability truncates like Android's rows (`.toInt()`); the parts' shares round (`PartRow`).
            Text(share.map { "\(Int($0 * 100))%" } ?? "—").font(Fonts.display(13)).monospacedDigit().foregroundStyle(Palette.text)
                .frame(width: 40, alignment: .trailing)
        }
        .padding(.horizontal, 6).padding(.vertical, 5)
        .background(RoundedRectangle(cornerRadius: 6).fill(hover ? Palette.card : .clear))
        .contentShape(RoundedRectangle(cornerRadius: 6))
        .onHover { hover = $0 }
        .onTapGesture { open?() }
    }
}

// MARK: The ping chart's server choice

/// At most this many servers on the ping chart (Android: the palette has `SeriesColors.size` slots).
private let pingMaxServers = 8

/// The ping chart and its choice of servers (Android `PingSection` + `PingCard`): automatically the
/// one in use at the end of the period and the best by `StatsMath.score`, until the user picks their
/// own — "Best 5" returns to automatic. The best-of line first, muted with a halo.
struct PingSection: View {
    var model: MainModel
    var d: StatsData
    /// The servers the chart may show — nil = all the reply has stats for; a subscription's page
    /// passes its own ids. Phantom rows (deleted servers' statistics) never join the lineup:
    /// Android filters its candidates to the servers it knows.
    var ids: Set<String>? = nil
    /// The muted line's name and points ("best of all" / "best of the subscription").
    var bestLabel: String
    var bestPoints: [(Date, Double?)]

    @Environment(MenuState.self) private var menus
    @State private var manual = false
    @State private var picked: [String] = []
    /// The colour each server keeps while it stays on the chart (Android's `palette` map).
    @State private var palette: [String: Int] = [:]

    /// Servers with samples in the period, best first — the picker's and the auto lineup's order.
    private var candidates: [ServerStat] {
        d.servers.filter { !$0.phantom && (ids?.contains($0.id) ?? true) }
            .sorted { $0.score > $1.score }
    }

    /// Android `topServers`: the server carrying traffic at the end of the period (or the last one
    /// used), then the best by score — at most five together. The service's own `pingOrder` (the
    /// lineup it picked `pings` for) leads when the reply carries it (F-STATS-0020: a Mac-picked
    /// server outside the shipped set would draw an empty series).
    private var auto: [String] {
        let byId = Set(candidates.map(\.id))
        let current = d.phases.last { $0.phase == .connected && ($0.server.map { byId.contains($0) } ?? false) }?.server
        // Only a server with a shipped series can draw (the service sends its own score-picked
        // top set in `pings`); without `pingOrder` the same score ranking picks among them.
        let hasSeries = candidates.filter { !(d.pings[$0.id] ?? []).isEmpty }
        let ranked = d.pingOrder.isEmpty
            ? hasSeries.sorted { $0.score > $1.score }.map(\.id)
            : d.pingOrder.filter { byId.contains($0) && !(d.pings[$0] ?? []).isEmpty }
        return ([current].compactMap { $0 } + ranked).reduce(into: [String]()) { if !$0.contains($1), $0.count < 5 { $0.append($1) } }
    }

    private var selected: [String] {
        let byId = Set(candidates.map(\.id))
        return manual ? picked.filter { byId.contains($0) } : auto
    }

    /// A colour per shown server: a picked one keeps the slot it took (the palette map), the
    /// automatic lineup fills from the first free colour.
    private var colorOf: [String: Color] {
        var used = Set<Int>(), out: [String: Color] = [:]
        for id in selected {
            let i = palette[id].flatMap { used.contains($0) ? nil : $0 }
                ?? SeriesColors.all.indices.first { !used.contains($0) }
            guard let i else { continue }
            used.insert(i); out[id] = SeriesColors.all[i]
        }
        return out
    }

    private func toggle(_ id: String) {
        var sel = selected
        if let i = sel.firstIndex(of: id) { sel.remove(at: i) }
        else if sel.count < pingMaxServers {
            sel.append(id)
            if palette[id] == nil {
                palette[id] = SeriesColors.all.indices.first { !palette.values.contains($0) }
            }
        }
        picked = sel
        manual = true
    }

    var body: some View {
        let colors = colorOf
        VStack(alignment: .leading, spacing: 8) {
            // The probe reads in ms as Android's `msText` (seconds past 10 s); the axis keeps "1k"/"3k".
            TimeChart(series: [ChartSeries(name: bestLabel, color: Palette.textMuted, points: bestPoints,
                                           lineWidth: 1.4, halo: true)]
                + selected.compactMap { id in
                    model.servers.first { $0.id == id }.map { s in
                        ChartSeries(name: shortName(model.displayName(s), 18), color: colors[id] ?? SeriesColors.all[0],
                                    points: (d.pings[id] ?? []).map { ($0.at, $0.ms.map(Double.init)) })
                    }
                },
                bands: d.chartBands, from: d.dataFrom, to: d.to, scale: .latency,
                connectGapMs: 30 * 60, clearLabel: L("band_clear_server"), markers: d.markers,
                probeFormat: { msText(Int($0)) })
            Text(manual ? L("ping_chart_hint") : L("ping_chart_auto"))
                .font(Fonts.body(11)).foregroundStyle(Palette.textSecondary)
                .fixedSize(horizontal: false, vertical: true).textSelection(.enabled)
            // Selected servers (a tap removes one) and the way to the full list / back to automatic.
            FlowCells(spacing: 6) {
                ForEach(selected, id: \.self) { id in
                    if let s = model.servers.first(where: { $0.id == id }) {
                        chip(shortName(model.displayName(s), 22) + "  ✕", selected: true, dot: colors[id],
                             flag: s.country) { toggle(id) }
                    }
                }
                chip("+ " + L("choose_servers"), selected: false, dot: nil, flag: nil) {
                    menus.show(dialog: AnyView(PingServerPicker(model: model, candidates: candidates,
                                                                auto: auto, manual: $manual, picked: $picked) { toggle($0) }))
                }
                if manual { chip("↺ " + L("ping_chart_best"), selected: false, dot: nil, flag: nil) { manual = false; picked = [] } }
            }
        }
    }

    /// A capsule like Android's `Chip`: accent ring while selected, a colour dot or a flag inside.
    private func chip(_ title: String, selected: Bool, dot: Color?, flag: String?, action: @escaping () -> Void) -> some View {
        HStack(spacing: 5) {
            if let dot { Circle().fill(dot).frame(width: 7, height: 7) }
            if let flag { FlagView(code: flag, height: 11) }
            Text(title).font(Fonts.body(12, bold: true))
                .foregroundStyle(selected ? Palette.accentStrong : Palette.text).lineLimit(1)
        }
        .padding(.horizontal, 10).padding(.vertical, 5)
        .background(Capsule().fill(selected ? Palette.accentSoft : Palette.card))
        .contentShape(Capsule())
        .onTapGesture { action() }
    }
}

/// The full list of servers with samples in the period, best first, to pick the chart's lineup
/// (Android `ServerPicker` — a checklist of at most `SeriesColors.size`). The selection reads live:
/// `manual`/`picked` are the chart's state as bindings — a stored `AnyView` sees the changes.
private struct PingServerPicker: View {
    var model: MainModel
    var candidates: [ServerStat]
    /// What the chart draws while the user hasn't chosen (shown checked until a first toggle).
    var auto: [String]
    @Binding var manual: Bool
    @Binding var picked: [String]
    var toggle: (String) -> Void

    @Environment(MenuState.self) private var menus

    private var selected: [String] { manual ? picked : auto }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(L("choose_servers")).font(Fonts.display(17, bold: true)).foregroundStyle(Palette.text)
                .padding(.horizontal, 4)
            ScrollView {
                VStack(spacing: 0) {
                    ForEach(candidates) { s in
                        let checked = selected.contains(s.id)
                        let full = !checked && selected.count >= pingMaxServers
                        HStack(spacing: 8) {
                            Image(systemName: checked ? "checkmark.square.fill" : "square")
                                .font(.system(size: 14)).foregroundStyle(checked ? Palette.accent : Palette.textMuted)
                            FlagView(code: s.server.country, height: 12)
                            Text(model.displayName(s.server)).font(Fonts.body(13)).foregroundStyle(Palette.text)
                                .lineLimit(1).layoutPriority(1)
                            Spacer(minLength: 8)
                            Text("\(Int(s.availability * 100))% · \(msText(s.avg))")
                                .font(Fonts.body(12, bold: true)).foregroundStyle(Palette.textSecondary)
                        }
                        .padding(.horizontal, 4).padding(.vertical, 6)
                        .opacity(full ? 0.4 : 1)
                        .contentShape(Rectangle())
                        .onTapGesture { if !full { toggle(s.id) } }
                    }
                }
            }
            .frame(maxHeight: 320)
            HStack {
                Spacer()
                Button(L("done")) { menus.closeDialog() }
                    .buttonStyle(.plain)
                    .font(Fonts.body(12, bold: true)).foregroundStyle(Palette.accentStrong)
                    .padding(.horizontal, 12).padding(.vertical, 6)
            }
        }
        .padding(14).frame(width: 380)
    }
}
