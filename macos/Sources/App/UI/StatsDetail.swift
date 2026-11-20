import SwiftUI

/// What the statistics show besides the overview: one server or one subscription (Android `ServerStatsScreen`,
/// `SubscriptionStatsScreen`), opened from the menus, the Groups inspector or the overview's rows.
enum StatsTarget: Equatable {
    case server(String), subscription(String)
}

/// One server: tiles, its status over time, its pings, their distribution (Android `ServerStatsScreen`).
/// The server itself may be gone — deleted while the page is open: its statistics are the service's
/// anyway, so `server` is optional and the id leads (F-STATS-0073).
struct ServerStatsView: View {
    var model: MainModel
    var serverId: String
    var server: ServerItem?
    var d: StatsData

    /// The tile figures over the period's raw samples: the service's own `summarize` when the reply
    /// ships it, else what the ≤96 chart buckets can say (never shipped as the truth — see the note
    /// at `StatsData.summary`, F-STATS-0028).
    private var sum: StatsSummary {
        if let s = d.summary { return s }
        let points = d.pings[serverId] ?? []
        let values = points.compactMap(\.ms)
        let ok = values.sorted()
        return StatsSummary(count: points.count, ok: ok.count,
                            median: percentile(ok, 0.5), p95: percentile(ok, 0.95),
                            min: ok.first, max: ok.last, mean: nil, jitter: jitter(values), byTime: nil)
    }

    var body: some View {
        let ids: Set<String> = [serverId]
        let stat = d.servers.first { $0.id == serverId }
        let s = sum
        VStack(alignment: .leading, spacing: 10) {
            if s.count == 0 {
                Text(L("st_no_data")).font(Fonts.body(13)).foregroundStyle(Palette.textSecondary).padding(.vertical, 24).textSelection(.enabled)
            } else {
                TileGrid(tiles: [
                    (L("ss_availability"), "\(Int((s.availability ?? 0) * 100))%", L("ss_availability_sub", s.ok, s.count)),
                    (L("ss_median"), msText(s.median), s.p95 == nil ? nil : L("ss_p95", msText(s.p95))),
                    (L("ss_minmax"), s.min == nil || s.max == nil ? "—" : "\(s.min!) / " + L("ms", s.max!), nil),
                    (L("ss_jitter"), msText(s.jitter), nil),
                    (L("ss_usage"), (stat?.usage ?? 0) > 0 ? statsDuration(stat!.usage) : "—", nil),
                    (L("ss_chosen"), P("ss_chosen_value", d.chosen[serverId] ?? 0), nil),
                ])
                let states = d.serverStates(ids)
                if !states.isEmpty {
                    StateCard(spans: states, from: d.dataFrom, to: d.to, dataStart: d.dataStart, note: L("server_states_note"))
                }
                StatsCard(title: L("sec_ping_chart"), unit: L("ms_unit")) {
                    let points = d.pings[serverId] ?? []
                    // Android: the name cut at 24 chars, the failed-bucket ticks under the plot.
                    let series = ChartSeries(name: server.map { String(model.displayName($0).prefix(24)) } ?? "",
                                             color: Palette.accent, points: points.map { ($0.at, $0.ms.map(Double.init)) },
                                             failureTicks: points.compactMap { $0.total > 0 ? ($0.at, Double($0.failed) / Double($0.total)) : nil })
                    TimeChart(series: [series], bands: d.chartBands, from: d.dataFrom, to: d.to, scale: .latency,
                              failures: true, connectGapMs: 30 * 60, clearLabel: L("band_clear_server"),
                              markers: d.markers, probeFormat: { msText(Int($0)) })
                }
                StatsCard(title: L("sec_distribution")) { Histogram(counts: d.histogram(ids)) }
            }
        }
    }
}

/// One subscription: tiles, its servers' status, the best of them and the ones in use, how many answered, what the
/// provider reports, the distribution and its servers (Android `SubscriptionStatsScreen`).
struct SubscriptionStatsView: View {
    var model: MainModel
    var sub: SubscriptionItem
    var d: StatsData

    var body: some View {
        let servers = model.servers.filter { $0.subscriptionId == sub.id }
        let ids = Set(servers.map(\.id))
        let stats = d.servers.filter { ids.contains($0.id) }
        let usage = stats.map(\.usage).reduce(0, +)
        let weight = stats.reduce(0.0) { $0 + $1.weight }
        let share = weight == 0 ? nil : stats.reduce(0.0) { $0 + $1.answered } / weight
        let chosen = servers.reduce(0) { $0 + (d.chosen[$1.id] ?? 0) }
        // Latency over the target's raw samples when the reply carries it (`sub:` replies may), else
        // the chart buckets' figures — the sub's summary isn't shipped yet (SVC-ENGINE seam).
        let sum = d.summary ?? {
            let ok = ids.flatMap { d.pings[$0] ?? [] }.compactMap(\.ms).sorted()
            return StatsSummary(count: ids.flatMap { d.pings[$0] ?? [] }.count, ok: ok.count,
                                median: percentile(ok, 0.5), p95: percentile(ok, 0.95),
                                min: nil, max: nil, mean: nil, jitter: nil, byTime: nil)
        }()
        VStack(alignment: .leading, spacing: 10) {
            TileGrid(tiles: [
                (L("subst_traffic"), { let t = ids.reduce(Int64(0)) { $0 + (d.trafficByServer[$1] ?? 0) }
                    return t > 0 ? bytesText(t) : "—" }(), L("subst_traffic_sub")),
                (L("subst_provider"), providerText, providerSub),
                (L("subst_time"), usage > 0 ? statsDuration(usage) : "—", L("subst_time_sub")),
                (L("ss_chosen"), P("ss_chosen_value", chosen), nil),
                (L("ss_availability"), share.map { "\(Int($0 * 100))%" } ?? "—",
                 L("subst_answered", stats.filter { ($0.ok ?? Int($0.answered.rounded())) > 0 }.count, stats.count)),
                (L("ss_median"), msText(sum.median), sum.p95 == nil ? nil : L("ss_p95", msText(sum.p95))),
                (L("subst_server_count"), "\(servers.count)",
                 servers.contains(where: \.disabled) ? L("subst_hidden", servers.filter(\.disabled).count) : nil),
                (L("subst_updated"), sub.updatedAt.map(relativeText) ?? "—",
                 sub.viaServer ? L("sub_via_server") : sub.lastError != nil ? L("subst_error") : nil),
            ])
            let states = d.serverStates(ids)
            if !states.isEmpty {
                StateCard(spans: states, from: d.dataFrom, to: d.to, dataStart: d.dataStart, note: L("sub_states_note"))
            }
            // Hidden servers are checked rarely (auto-visibility) or never: a short period may have nothing.
            if sum.count == 0 {
                Text(L("subst_no_checks")).font(Fonts.body(13)).foregroundStyle(Palette.textSecondary)
                    .padding(.vertical, 16).textSelection(.enabled)
            } else {
                StatsCard(title: L("sec_ping_chart"), unit: L("ms_unit")) {
                    PingSection(model: model, d: d, ids: ids, bestLabel: L("subst_best"),
                                bestPoints: subBest(ids))
                }
                if !d.checked.isEmpty {
                    StatsCard(title: L("subst_available_chart")) {
                        // How many of its servers a rescan's slice checked and how many answered
                        // (the reply's `checked` — `StatsDb.checkedAmong` on Android).
                        TimeChart(series: [
                            ChartSeries(name: L("st_checked"), color: Palette.textSecondary, points: d.checked.map { ($0.at, Double($0.checked)) }, fill: true, fillOpacity: 0.28),
                            ChartSeries(name: L("st_answered"), color: Palette.accent, points: d.checked.map { ($0.at, Double($0.answered)) }, fill: true),
                        ], bands: d.chartBands, from: d.dataFrom, to: d.to,
                                  scale: .linear(Double(max(d.checked.map(\.checked).max() ?? 1, 1))), stepped: true,
                                  connectGapMs: 30 * 60, clearLabel: L("band_clear_server"), markers: d.markers,
                                  probeFormat: { countText($0) })
                    }
                }
                StatsCard(title: L("sec_distribution")) { Histogram(counts: d.histogram(ids)) }
            }
            if d.usageRows.contains(where: { $0.used != nil }) {
                StatsCard(title: L("subst_reported_chart"), unit: L("axis_gb"), note: L("subst_reported_note")) {
                    reportedChart
                }
            }
            let rows = stats.sorted { $0.availability > $1.availability }
            if !rows.isEmpty {
                StatsCard(title: L("subst_servers")) {
                    ForEach(rows) { s in
                        StatRow(icon: .server(s.server.country, s.avg), title: model.displayName(s.server),
                                sub: L("server_row_sub", msText(s.avg), P("samples_n", s.samples))
                                    + (s.usage > 0 ? " · " + statsDuration(s.usage) : ""),
                                share: s.availability) { model.statsTarget = .server(s.id) }
                    }
                }
            }
        }
    }

    /// The best of its servers per bucket — the reply's `subBest` (`StatsDb.bucketsAmong`), or the
    /// per-server minimum while the reply ships none (the approximation, kept only for older services).
    private func subBest(_ ids: Set<String>) -> [(Date, Double?)] {
        if !d.subBest.isEmpty { return d.subBest.map { ($0.at, $0.ms.map(Double.init)) } }
        let times = Array(Set(ids.flatMap { (d.pings[$0] ?? []).map(\.at) })).sorted()
        return times.map { t in (t, ids.compactMap { id in d.pings[id]?.first { $0.at == t }?.ms }.min().map(Double.init)) }
    }

    /// The provider's own words: the newest report's numbers, else the subscription card's
    /// (`used / total` together when it has both — Android's `SubTiles`).
    private var providerText: String {
        let last = d.usageRows.last
        let used = last?.used ?? sub.usedBytes
        let total = last?.total ?? sub.totalBytes
        if let used, let total { return "\(bytesText(used)) / \(bytesText(total))" }
        if let used { return bytesText(used) }
        return "—"
    }

    /// What the provider says besides the amount: "until …" of the newest report's expiry
    /// (`usageExpire`, else the subscription's), or that it doesn't report at all.
    private var providerSub: String? {
        (d.usageExpire ?? sub.expire).map { L("until", dateText($0)) } ?? L("subst_provider_silent")
    }

    /// What the provider reported at each update, in whole MB like Android (truncating division), the
    /// last value held to the period's end; the axis is marked in GB (`divide: 1024`).
    @ViewBuilder private var reportedChart: some View {
        let reported = d.usageRows.filter { $0.used != nil }
        let points: [(Date, Double?)] = reported.map { (max($0.at, d.dataFrom), Double($0.used! / 1_048_576)) }
            + [(d.to, Double(reported.last!.used! / 1_048_576))]
        let top = max(points.compactMap(\.1).max() ?? 0,
                      Double((reported.last?.total ?? 0) / 1_048_576), 1)
        let series = ChartSeries(name: L("subst_used"), color: Palette.accent, points: points, fill: true)
        TimeChart(series: [series], bands: [], from: d.dataFrom, to: d.to,
                  scale: .linear(top, integer: false, divide: 1024), stepped: true, height: 150,
                  probeFormat: { bytesText(Int64($0) * 1_048_576) })
    }
}

/// `StatsMath.percentile` for the bucket fallback (the same `ceil(p·n)-1` index, clamped).
private func percentile(_ sorted: [Int], _ p: Double) -> Int? {
    guard !sorted.isEmpty else { return nil }
    let idx = min(max(Int((p * Double(sorted.count)).rounded(.up)) - 1, 0), sorted.count - 1)
    return sorted[idx]
}

/// `StatsMath`'s jitter for the bucket fallback: the mean absolute difference between consecutive
/// answered buckets in time order (truncating like Kotlin's `.average().toInt()`).
private func jitter(_ values: [Int]) -> Int? {
    guard values.count > 1 else { return nil }
    var total = 0
    for i in 1..<values.count { total += abs(values[i] - values[i - 1]) }
    return total / (values.count - 1)
}

/// The status over time (Android `ServerStateCard` / `StateStrip`): in use, answering, no reply; gaps not checked.
private struct StateCard: View {
    var spans: [StateSpan]
    var from: Date
    var to: Date
    /// The service's first record, if known: the time before it is left bare — unknown, not "not checked".
    var dataStart: Date? = nil
    var note: String

    var body: some View {
        StatsCard(title: L("sec_server_states"), trailing: AnyView(legend)) {
            VStack(spacing: 4) {
                Canvas { ctx, size in
                    let span = max(to.timeIntervalSince(from), 1)
                    if let dataStart, dataStart > from {
                        let x1 = CGFloat(min(dataStart, to).timeIntervalSince(from) / span) * size.width
                        ctx.fill(Path(CGRect(x: 0, y: 0, width: x1, height: size.height)), with: .color(Palette.background))
                    }
                    for s in spans {
                        let x0 = CGFloat(s.from.timeIntervalSince(from) / span) * size.width
                        let x1 = CGFloat(s.to.timeIntervalSince(from) / span) * size.width
                        ctx.fill(Path(CGRect(x: x0, y: 0, width: max(x1 - x0, 1), height: size.height)), with: .color(color(s.state)))
                    }
                }
                .frame(height: 10)
                .background(Palette.unavailable.opacity(0.5))
                .clipShape(RoundedRectangle(cornerRadius: 3))
                HStack {
                    ForEach(0..<5) { i in
                        let t = from.addingTimeInterval(to.timeIntervalSince(from) * Double(i) / 4)
                        Text(to.timeIntervalSince(from) <= 86_400 ? t.formatted(.dateTime.hour(.twoDigits(amPM: .omitted)).minute(.twoDigits))
                             : t.formatted(.dateTime.day().month(.abbreviated)))
                            .font(Fonts.body(10)).foregroundStyle(Palette.textSecondary).textSelection(.enabled)
                        if i < 4 { Spacer() }
                    }
                }
            }
            Text(note).font(Fonts.body(11)).foregroundStyle(Palette.textSecondary).textSelection(.enabled)
        }
    }

    private var legend: some View {
        // Only the states the period saw, as `PhaseLegend` filters — "in use" of zero would mislead.
        let shown = [ServerState.inUse, .answered, .noReply].filter { s in
            spans.filter { $0.state == s }.reduce(0 as TimeInterval) { $0 + $1.to.timeIntervalSince($1.from) } > 0
        }
        return HStack(spacing: 12) {
            ForEach(shown, id: \.self) { s in
                HStack(spacing: 5) {
                    Circle().fill(color(s)).frame(width: 7, height: 7)
                    Text("\(L(s == .inUse ? "srv_state_in_use" : s == .answered ? "srv_state_answered" : "srv_state_no_reply")) \(statsDuration(durations(s)))")
                        .font(Fonts.body(11)).foregroundStyle(Palette.textSecondary)
                }
            }
        }
    }

    private func durations(_ s: ServerState) -> TimeInterval {
        spans.filter { $0.state == s }.reduce(0) { $0 + $1.to.timeIntervalSince($1.from) }
    }

    private func color(_ s: ServerState) -> Color {
        switch s {
        case .inUse: Palette.accent
        case .answered: Palette.accentContainer
        case .noReply: Palette.unavailableMark
        }
    }
}

/// Ping bands as bars in their colours with the share on top (Android `Histogram`).
private struct Histogram: View {
    var counts: [Int]
    /// The same bands `StatsData.histogram` counted `counts` by (one more edge than bars: the last one opens ">").
    private let edges = StatsData.histogramEdges

    var body: some View {
        let total = max(counts.reduce(0, +), 1)
        let top = Double(counts.max() ?? 1) / Double(total)
        HStack(alignment: .bottom, spacing: 6) {
            ForEach(counts.indices, id: \.self) { i in
                let share = Double(counts[i]) / Double(total)
                VStack(spacing: 3) {
                    // Integer percent like Android's `c * 100 / total` (truncated, as every availability figure).
                    Text(share > 0 ? "\(counts[i] * 100 / total)%" : "").font(Fonts.body(10)).foregroundStyle(Palette.textSecondary).textSelection(.enabled)
                    RoundedRectangle(cornerRadius: 3)
                        .fill(Palette.ping(i == counts.count - 1 ? 3200 : (edges[i] + edges[min(i + 1, edges.count - 1)]) / 2))
                        .frame(height: max(2, CGFloat(top > 0 ? share / top : 0) * 110))
                    Text(label(i)).font(Fonts.body(10)).foregroundStyle(Palette.textSecondary).lineLimit(1).minimumScaleFactor(0.8).textSelection(.enabled)
                }
                .frame(maxWidth: .infinity)
            }
        }
        .frame(height: 150, alignment: .bottom)
    }

    private func label(_ i: Int) -> String {
        let unit = L("ms_unit")
        if i == 0 { return "< \(edges[1]) \(unit)" }
        if i == counts.count - 1 { return "> \(edges[i]) \(unit)" }
        return "\(edges[i])–\(edges[i + 1])"
    }
}
