import Shared
import SwiftUI

/// Statistics → App activity (Android `ActivityTab.kt`): what the app itself costs. The Mac measures energy (macOS
/// counts it per process — `ri_energy_nj` on Apple Silicon, the CPU-time model where the kernel has none) instead of
/// Android's model of radio time and battery current, so the radio wake-ups, the cost of one action and the protocol
/// card are not here; the rest — tiles, where the energy goes, the charts, the conditions — is the same. The data is
/// the service's live `stats` target "activity" ([ActivityData.init(json:)]); the demo is `ActivityData.demo`.
struct ActivityView: View {
    var d: StatsData
    var a: ActivityData

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            ActivityTiles(a: a)
            StatsCard(title: String(localized: "mac.act.parts"), note: String(localized: "mac.act.parts.note")) {
                VStack(spacing: 2) {
                    ForEach(a.parts, id: \.name) { part in PartRow(name: part.name, sub: part.sub, share: part.share) }
                }
            }
            HStack(alignment: .top, spacing: 10) {
                StatsCard(title: String(localized: "mac.act.chart.energy"), unit: String(localized: "mac.unit.mw")) {
                    TimeChart(series: a.energySeries, bands: d.recordedPhases, from: d.from, to: d.to, scale: .linear(a.energyMax))
                }
                StatsCard(title: L("act_chart_network")) {
                    TimeChart(series: a.networkSeries, bands: d.recordedPhases, from: d.from, to: d.to, scale: .linear(a.networkMax))
                }
            }
            HStack(alignment: .top, spacing: 10) {
                StatsCard(title: L("act_chart_cpu"), unit: L("act_axis_s")) {
                    TimeChart(series: a.cpuSeries, bands: d.recordedPhases, from: d.from, to: d.to, scale: .linear(a.cpuMax))
                }
                StatsCard(title: L("act_conditions"), note: L("act_cond_of", durationText(a.hours * 3600))) {
                    VStack(spacing: 2) {
                        ForEach(a.conditions, id: \.0) { name, share in PartRow(name: name, sub: nil, share: share) }
                    }
                    Text(String(localized: "mac.act.cond.note")).font(Fonts.body(11)).foregroundStyle(Palette.textSecondary)
                        .fixedSize(horizontal: false, vertical: true).textSelection(.enabled)
                }
            }
            Text(L("act_note")).font(Fonts.body(11)).foregroundStyle(Palette.textSecondary).padding(.leading, 2).textSelection(.enabled)
        }
    }
}

/// The eight tiles (Android `ActivityTiles`), the radio's tile replaced by the energy of the period.
private struct ActivityTiles: View {
    var a: ActivityData

    private func perHour(_ n: Int) -> String? {
        guard a.hours >= 0.25 else { return nil }
        // The rate is a Double throughout: `n / Int(hours)` would trap below one hour (Int makes it 0).
        let rate = Double(n) / a.hours
        return L("act_per_hour", rate < 100 ? String(format: "%.1f", locale: Locale.current, rate) : "\(Int(rate.rounded()))")
    }

    var body: some View {
        let tiles: [(String, String, String?)] = [
            (L("act_network"), "\(a.pings + a.probes + a.other)", perHour(a.pings + a.probes + a.other)),
            (L("act_pings"), "\(a.pings)", L("act_pings_sub", a.ridePercent)),
            (L("act_probes"), "\(a.probes)", P("act_scans", a.scans)),
            (L("act_cpu"), durationText(a.cpuSeconds), L("act_per_hour", durationText(a.cpuSeconds / max(a.hours, 0.25)))),
            (String(localized: "mac.act.energy"), String(localized: "mac.w \(String(format: "%.2f", locale: Locale.current, a.averageWatts))"),
             String(localized: "mac.act.energy.sub \(durationText(a.hours * 3600))")),
            (String(localized: "mac.act.energy.total"), String(localized: "mac.wh \(String(format: "%.1f", locale: Locale.current, a.averageWatts * a.hours))"), nil),
            (L("act_memory"), L("act_mb", a.memoryMb), L("act_memory_sub", a.memoryMaxMb)),
            (L("act_saved"), "\(a.skipped)", L("act_saved_sub")),
        ]
        TileGrid(tiles: tiles)
    }
}

/// Eight tiles in one box, four to a row (as the servers tab's).
struct TileGrid: View {
    var tiles: [(String, String, String?)]

    var body: some View {
        Grid(horizontalSpacing: 0, verticalSpacing: 0) {
            ForEach(0..<(tiles.count + 3) / 4, id: \.self) { row in
                if row > 0 { Hairline().gridCellUnsizedAxes(.horizontal) }
                GridRow {
                    ForEach(0..<4) { col in
                        let i = row * 4 + col
                        if i < tiles.count {
                            StatTile(label: tiles[i].0, value: tiles[i].1, sub: tiles[i].2)
                                .overlay(alignment: .leading) { if col > 0 { Hairline(vertical: true) } }
                        } else {
                            Color.clear
                        }
                    }
                }
            }
        }
        .background(RoundedRectangle(cornerRadius: Dims.cardRadius).fill(Palette.background))
        .overlay(RoundedRectangle(cornerRadius: Dims.cardRadius).strokeBorder(Palette.outline, lineWidth: 1))
    }
}

struct StatTile: View {
    var label: String
    var value: String
    var sub: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 1) {
            Text(label).font(Fonts.body(11)).foregroundStyle(Palette.textSecondary).lineLimit(1).textSelection(.enabled)
            withSmallUnit(value, size: 16) { Fonts.display($0, bold: true) }
                .foregroundStyle(Palette.text).lineLimit(1).minimumScaleFactor(0.7)
                .fixedSize(horizontal: false, vertical: true).textSelection(.enabled)
            Text(sub ?? " ").font(Fonts.body(11)).foregroundStyle(Palette.textSecondary).lineLimit(2)
                .fixedSize(horizontal: false, vertical: true).textSelection(.enabled)
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 12).padding(.vertical, 9)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }
}

/// A part with its share: name, an optional grey line, a bar and the percentage.
private struct PartRow: View {
    var name: String
    var sub: String?
    var share: Double

    var body: some View {
        HStack(spacing: 10) {
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 6) {
                    Text(name).font(Fonts.body(13)).foregroundStyle(Palette.text).lineLimit(1).layoutPriority(1).textSelection(.enabled)
                    if let sub { Text(sub).font(Fonts.body(11)).foregroundStyle(Palette.textSecondary).lineLimit(1).textSelection(.enabled) }
                }
                UsageBar(share: share)
            }
            Text("\(Int((share * 100).rounded()))%").font(Fonts.display(13)).monospacedDigit().foregroundStyle(Palette.text)
                .textSelection(.enabled)
                .frame(width: 40, alignment: .trailing)
        }
        .padding(.vertical, 3)
    }
}

// MARK: Data

/// What the tab shows (Android `ActivityData`, Mac-measured energy). Demo: made up to match the servers tab's day.
struct ActivityData {
    var hours: Double
    var pings: Int
    var probes: Int
    var other: Int
    var scans: Int
    var ridePercent: Int
    var cpuSeconds: Double
    var averageWatts: Double
    var memoryMb: Int
    var memoryMaxMb: Int
    var skipped: Int
    var parts: [(name: String, sub: String?, share: Double)]
    var conditions: [(String, Double)]
    var energySeries: [ChartSeries]
    var energyMax: Double
    var networkSeries: [ChartSeries]
    var networkMax: Double
    var cpuSeries: [ChartSeries]
    var cpuMax: Double
    /// The mJ behind the figures came from `ri_energy_nj` (Apple Silicon); false: the CPU-time model
    /// (Intel — the kernel reports no energy, nothing is fabricated as measured).
    var measuredEnergy = false

    @MainActor
    static func demo(_ d: StatsData) -> ActivityData {
        var rng = SeededRandom(seed: 11)
        let on = d.onTime / 3600
        let buckets = 96
        let step = d.to.timeIntervalSince(d.from) / Double(buckets)
        func running(_ t: Date) -> Bool { d.phases.contains { $0.phase != .off && $0.from <= t && t < $0.to } }
        var whole: [(Date, Double?)] = [], core: [(Date, Double?)] = [], connection: [(Date, Double?)] = [], faster: [(Date, Double?)] = []
        var pings: [(Date, Double?)] = [], probes: [(Date, Double?)] = [], other: [(Date, Double?)] = [], cpu: [(Date, Double?)] = []
        for i in 0..<buckets {
            let t = d.from.addingTimeInterval(step * (Double(i) + 0.5))
            guard running(t) else {
                // The app wasn't running: a gap in every line.
                whole.append((t, nil)); core.append((t, nil)); connection.append((t, nil)); faster.append((t, nil))
                pings.append((t, nil)); probes.append((t, nil)); other.append((t, nil)); cpu.append((t, nil))
                continue
            }
            let busy = Double(rng.next(40)) / 10
            let c = 40 + busy * 12 + Double(rng.next(15))
            let w = 12 + Double(rng.next(8))
            let f = Double(rng.next(10)) + (i % 6 == 0 ? 18 : 2)
            core.append((t, c)); connection.append((t, w)); faster.append((t, f)); whole.append((t, c + w + f + 9))
            pings.append((t, Double(3 + rng.next(4)))); probes.append((t, Double(i % 6 == 0 ? 17 : rng.next(3))))
            other.append((t, Double(rng.next(2)))); cpu.append((t, 2 + busy + Double(rng.next(10)) / 10))
        }
        let colors = SeriesColors.all
        return ActivityData(
            hours: on, pings: Int(on * 14), probes: Int(on * 34), other: Int(on * 3), scans: Int(on * 2), ridePercent: 62,
            cpuSeconds: on * 41, averageWatts: 0.096, memoryMb: 74, memoryMaxMb: 96, skipped: Int(on * 2),
            parts: [
                (L("act_core_base"), L("act_part_traffic_sub"), 0.52),
                (L("act_part_connection"), nil, 0.16),
                (L("act_part_faster"), nil, 0.12),
                (L("act_ui_base"), nil, 0.08),
                (L("act_part_search"), nil, 0.05),
                (L("act_part_stats"), nil, 0.04),
                (L("act_part_subs"), nil, 0.02),
                (L("act_part_other"), nil, 0.01),
            ],
            conditions: [
                (String(localized: "mac.act.cond.display"), 0.41),
                (String(localized: "mac.act.cond.battery"), 0.63),
                (String(localized: "mac.act.cond.metered"), 0.06),
                (String(localized: "mac.act.cond.lowpower"), 0.0),
                (L("act_cond_eco"), 0.0),
            ],
            energySeries: [
                ChartSeries(name: L("act_series_all"), color: Palette.textMuted, points: whole, lineWidth: 1.4, halo: true),
                ChartSeries(name: L("act_core_base"), color: colors[0], points: core),
                ChartSeries(name: L("act_part_connection"), color: colors[1], points: connection),
                ChartSeries(name: L("act_part_faster"), color: colors[2], points: faster),
            ],
            energyMax: 140,
            networkSeries: [
                ChartSeries(name: L("act_series_pings"), color: colors[0], points: pings),
                ChartSeries(name: L("act_series_probes"), color: colors[1], points: probes),
                ChartSeries(name: L("act_series_other"), color: colors[3], points: other),
            ],
            networkMax: 18,
            cpuSeries: [ChartSeries(name: L("act_series_cpu"), color: colors[6], points: cpu, fill: true, fillOpacity: 0.18)],
            cpuMax: 8)
    }
}

extension ActivityData {
    /// The service's `stats` target "activity" (ENERGY-IMPL, `MacActivity.kt`; the wire type is
    /// `Shared/Activity.swift`): the same figures the Android tab computes from its `ActivityData`,
    /// decoded leniently. nil for an empty or unreadable payload: the tab then shows `act_no_data`
    /// (the same empty test Android's loader does).
    init?(json: String) {
        guard let out = try? JSONDecoder().decode(ActivityOut.self, from: Data(json.utf8)),
              !out.isEmpty else { return nil }
        func date(_ ms: Int64) -> Date { Date(timeIntervalSince1970: Double(ms) / 1000) }
        let hours = out.summary.hours > 0 ? out.summary.hours : Double(out.to - out.from) / 3_600_000
        let sumMj = out.totals.values.reduce(0) { $0 + $1.mj }
        // The energy chart's parts, top three by total, plus "all" (the whole app, like "best of all"
        // on the ping chart): mJ per covered ms → mW.
        let partTotals = out.energy.reduce(into: [String: Double]()) { r, b in
            for (p, mj) in b.mj { r[p, default: 0] += mj }
        }
        let top = partTotals.sorted { $0.value > $1.value }.prefix(3).map(\.key)
        func mw(_ mj: Double, _ covered: Int64) -> Double? { covered > 0 ? mj / Double(covered) * 1000 : nil }
        var energySeries = [ChartSeries(
            name: L("act_series_all"), color: Palette.textMuted,
            points: out.energy.map { (date($0.t), mw($0.mj.values.reduce(0, +), $0.coveredMs)) },
            lineWidth: 1.4, halo: true)]
        energySeries += top.enumerated().map { (i, p) in
            ChartSeries(name: out.partLabels[p].map { L($0) } ?? p, color: SeriesColors.all[i],
                        points: out.energy.map { b in (date(b.t), b.mj[p].flatMap { mw($0, b.coveredMs) }) })
        }
        let energyMax = max(out.energy.map { $0.mj.values.reduce(0, +) / Double(max($0.coveredMs, 1)) * 1000 }.max() ?? 0, 1)
        // The actions chart: pings / probes / the rest of the network groups per bucket.
        var networkSeries: [ChartSeries] = []
        for (i, group) in ["PING", "PROBE", "OTHER"].enumerated() {
            var perT = [Int64: Int64]()
            for b in out.buckets where b.group == group { perT[b.t, default: 0] += b.n }
            guard !perT.isEmpty else { continue }
            let points: [(Date, Double?)] = perT.sorted { $0.key < $1.key }.map { (date($0.key), Double($0.value)) }
            networkSeries.append(ChartSeries(
                name: L(["act_series_pings", "act_series_probes", "act_series_other"][i]),
                color: SeriesColors.all[i], points: points))
        }
        let networkMax = max(networkSeries.flatMap { $0.points.compactMap(\.1) }.max() ?? 0, 1)
        // CPU seconds per bucket from the power samples (each row carries the CPU since the previous one).
        let width = max(Int64(1), (out.to - out.from) / 96)
        var cpuPerBucket = [Int64: Int64]()
        for r in out.power.dropFirst() where r.ts >= out.from && r.ts < out.to {
            cpuPerBucket[(r.ts - out.from) / width, default: 0] += r.cpuMs
        }
        let cpuPoints: [(Date, Double?)] = cpuPerBucket.sorted { $0.key < $1.key }
            .map { (date(out.from + $0.key * width + width / 2), Double($0.value) / 1000) }
        let cpuMax = max(cpuPoints.compactMap(\.1).max() ?? 0, 1)
        // The conditions card (Android's "the app ran … under"): display off, on battery, metered
        // network, Low Power Mode, the app's own battery saving.
        let conditions: [(String, Double)] = [
            (String(localized: "mac.act.cond.display"), out.summary.screenOff.clamped),
            (String(localized: "mac.act.cond.battery"), (1 - out.summary.charging).clamped),
            (String(localized: "mac.act.cond.metered"), out.summary.metered.clamped),
            (String(localized: "mac.act.cond.lowpower"), out.summary.saver.clamped),
            (L("act_cond_eco"), (out.summary.ecoHours / max(out.summary.hours, 0.001)).clamped),
        ]
        let colors = SeriesColors.all
        self.init(
            hours: hours,
            pings: Int(out.groups["PING"] ?? 0),
            probes: Int(out.groups["PROBE"] ?? 0),
            other: Int(out.groups["OTHER"] ?? 0),
            scans: Int(out.groups["SCAN"] ?? 0),
            ridePercent: (out.groups["PING"] ?? 0) > 0 ? Int(out.pingsRide * 100 / (out.groups["PING"] ?? 1)) : 0,
            cpuSeconds: out.summary.cpuSeconds,
            averageWatts: out.summary.watts ?? (hours > 0 ? Double(sumMj) / 1000 / (hours * 3600) : 0),
            memoryMb: Int((out.summary.avgMemoryMb ?? 0).rounded()),
            memoryMaxMb: Int((out.summary.maxMemoryMb ?? 0).rounded()),
            skipped: Int(out.skipped),
            parts: out.parts.map { p in
                (L(p.label), p.part == "TRAFFIC" ? L("act_part_traffic_sub") :
                    p.part == "LIMITED" ? L("act_part_limited_sub") : nil, p.share)
            },
            conditions: conditions,
            energySeries: energySeries, energyMax: energyMax,
            networkSeries: networkSeries, networkMax: networkMax,
            cpuSeries: cpuPoints.isEmpty ? [] : [ChartSeries(
                name: L("act_series_cpu"), color: colors[6], points: cpuPoints, fill: true, fillOpacity: 0.18)],
            cpuMax: cpuMax,
            measuredEnergy: out.measuredEnergy)
    }
}

private extension Double {
    /// Shares of the covered time stay inside 0…1 (a coarse power row can say otherwise by a hair).
    var clamped: Double { min(max(self, 0), 1) }
}
