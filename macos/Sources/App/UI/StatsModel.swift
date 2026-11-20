import Foundation

/// Statistics shown by the screens (Android `Overview` in `StatsScreen.kt`). Demo for now: the same day as Android's
/// `Demo.kt` (off at night, a failover in the afternoon, a switch back), repeated over longer periods.
enum Period: CaseIterable, Identifiable {
    case hour, day, week, month, all, custom
    var id: Self { self }

    var title: String {
        switch self {
        case .hour: L("period_1h")
        case .day: L("period_24h")
        case .week: L("period_7d")
        case .month: L("period_30d")
        case .all: L("period_all")
        case .custom: L("period_custom")
        }
    }

    var seconds: TimeInterval {
        switch self {
        case .hour: 3600
        case .day: 86_400
        case .week: 7 * 86_400
        case .month, .all, .custom: 30 * 86_400
        }
    }
}

/// A stretch of one connection state (Android `StatsMath.Segment`).
struct PhaseSpan {
    var from: Date
    var to: Date
    var phase: Phase
    /// The server in use while connected.
    var server: String? = nil
    /// An eco-mode band (Android `BandKind.ECO`): hatched over whatever is behind, the phase ignored.
    var eco = false
}

/// One check of all servers: how many were checked and how many answered.
struct ScanPoint {
    var at: Date
    var total: Int
    var answered: Int
}

struct PingPoint {
    var at: Date
    var ms: Int?
    /// Samples that failed / were checked in the bucket (Android `StatsMath.Point.failed/total`),
    /// for the failure ticks under the detail chart; 0 while the service ships medians only.
    var failed = 0
    var total = 0
}

struct ServerStat: Identifiable {
    var id: String { server.id }
    var server: ServerItem
    /// Share of the period it answered (by time), its average ping, how many checks, time in use.
    var availability: Double
    var avg: Int?
    var samples: Int
    var usage: TimeInterval
    /// How many samples answered / how many 10-min slices held a check, when the service ships them
    /// (nil on an older reply — the weight then falls back to the sample count, as `Aggregate` does).
    var ok: Int? = nil
    var slices: Int? = nil
    /// The server is gone from the list (deleted) but the service still keeps its statistics
    /// (F-STATS-0073): the row exists so a `s:<id>` page can show them; overview lists skip it.
    var phantom = false
    /// Android `Aggregate.weight` / `answered`: the checked time the server's share stands for.
    var weight: Double { (slices ?? 0) > 0 ? Double(slices!) : Double(samples) }
    var answered: Double { availability * weight }
    /// Android `StatsMath.score` — the ordering behind the ping chart's default lineup.
    var score: Double { (answered + 1) / (weight + 2) / (1 + Double(avg ?? 2000) / 400) }
}

struct StatsData {
    var from = Date.distantPast
    var to = Date.distantPast
    var phases: [PhaseSpan] = []
    var scans: [ScanPoint] = []
    /// Ping series per server (the chart's chosen ones) and the best of all at each moment.
    var pings: [String: [PingPoint]] = [:]
    var best: [PingPoint] = []
    var servers: [ServerStat] = []
    var trafficProxy: Int64 = 0
    var trafficDirect: Int64 = 0
    /// Per-server traffic (the service's `trafficByServer`): the subscription tile sums it.
    var trafficByServer: [String: Int64] = [:]
    var losses = 0
    var switches: (failover: Int, faster: Int, manual: Int) = (0, 0, 0)
    var found: (place: Double, of: Int, count: Int)?
    var health: (median: Int, p95: Int, jitter: Int) = (0, 0, 0)
    /// Statistics exist only from here on; earlier time is unknown, not "off" (the service's first record).
    var dataStart = Date.distantPast
    /// The left edge the charts draw from. "All time" asks from the epoch and the reply echoes it
    /// (`StatsOut.from`), while Android's `period()` starts at the first record — [dataStart] here.
    var dataFrom: Date { max(from, dataStart) }
    /// The phases clipped to the recorded stretch: the charts leave the time before `dataStart` bare
    /// instead of painting it "off" (Android starts its bands at `maxOf(from, dataStart)`).
    var recordedPhases: [PhaseSpan] {
        phases.compactMap { s in
            guard s.to > dataStart else { return nil }
            var s = s
            s.from = max(s.from, dataStart)
            return s
        }
    }
    /// How often each server was chosen while the period (the detail tile); the overview needs none.
    var chosen: [String: Int] = [:]
    /// The target's states over time as the service measured them (real mode; the demo computes its own).
    var states: [StateSpan]?
    /// Samples per band of the distribution, per server, as the service counted them.
    var histograms: [String: [Int]]?
    /// What the target subscription's provider reported at each update.
    var usageRows: [UsageRow] = []

    /// Moments to mark on the charts (Android `loadZones` strategy markers); empty until the stats
    /// reply carries the settings history.
    var markers: [ChartMarker] = []
    /// The eco-mode's intervals as hatched bands (Android `zones.eco`); empty until the reply ships them.
    var ecoSpans: [PhaseSpan] = []
    /// A subscription's checked/answered slices (Android `StatsDb.checkedAmong`), for "its servers
    /// answering"; empty until the reply ships them — the screen falls back to the global scans then.
    var checked: [CheckedPoint] = []
    /// The service's own order of `pings` (its `topServers` pick), when it ships it; else the overview
    /// approximates it from the aggregates.
    var pingOrder: [String] = []
    /// The best of a subscription's servers per bucket (Android `StatsDb.bucketsAmong`), shipped for
    /// `sub:` targets; the screen falls back to a per-server minimum while absent.
    var subBest: [PingPoint] = []
    /// The target's latency summary over raw samples (Android `StatsMath.summarize`), shipped for
    /// `s:`/`sub:` targets; the detail tiles fall back to bucket math while absent.
    var summary: StatsSummary? = nil
    /// The provider's `expire` of the newest usage report ([UsageRow] can't grow a field — it lives
    /// in Model.swift); the tile falls back to `SubscriptionItem.expire` while nil.
    var usageExpire: Date? = nil
    /// ENERGY-IMPL: the service's measured activity for the period (Android `ActivityTab`'s rows),
    /// decoded from the `stats` target "activity" — its own request, asked only while the tab is on.
    /// nil while the tables are empty or the tab was never opened: the tab shows `act_no_data` then.
    var activity: ActivityData? = nil

    /// Android `Overview.empty`: no connection records, no samples, no scans in the period.
    var isEmpty: Bool { phases.isEmpty && servers.isEmpty && scans.isEmpty }

    /// The "Available" tile's headline: the time-weighted answered share of [scanShare]
    /// (Android `StatsMath.scanShare`'s `share`), nil with no usable scans.
    var availableShare: Double? { scanShare?.share }

    func duration(_ phase: Phase) -> TimeInterval {
        phases.filter { $0.phase == phase }.reduce(0) { $0 + $1.to.timeIntervalSince($1.from) }
    }

    var onTime: TimeInterval { phases.filter { $0.phase != .off }.reduce(0) { $0 + $1.to.timeIntervalSince($1.from) } }

    /// Answered share by time and the typical scan (Android `StatsMath.scanShare`): scans inside
    /// no-network spans are skipped (the phone's fault, not the servers'), each scan weighs the time
    /// until the next one — at most 15 min, the last reaching the period's end.
    var scanShare: (share: Double, available: Double, total: Double)? {
        let all = scans.sorted { $0.at < $1.at }
        let skip = recordedPhases.filter { $0.phase == .noNetwork }
        var weight = 0.0, share = 0.0, available = 0.0, total = 0.0
        for (i, r) in all.enumerated() {
            guard r.total > 0, !skip.contains(where: { r.at >= $0.from && r.at < $0.to }) else { continue }
            let w = min(max((i + 1 < all.count ? all[i + 1].at : to).timeIntervalSince(r.at), 1), 900)
            weight += w
            share += w * Double(r.answered) / Double(r.total)
            available += w * Double(r.answered)
            total += w * Double(r.total)
        }
        return weight > 0 ? (share / weight, available / weight, total / weight) : nil
    }

    /// The bands the charts shade behind the series (Android `bands()` + `zones.eco`): gaps between
    /// recorded spans mean the tunnel was off — phases never carry OFF rows, so the bands are
    /// synthesized here — and the eco spans join as hatched overlays.
    var chartBands: [PhaseSpan] {
        let start = max(from, dataStart)
        var out: [PhaseSpan] = []
        func add(_ a: Date, _ b: Date, _ phase: Phase) {
            let (a, b) = (max(a, start), min(b, to))
            guard b > a else { return }
            if let last = out.last, last.phase == phase, a <= last.to { out[out.count - 1].to = b }
            else { out.append(PhaseSpan(from: a, to: b, phase: phase)) }
        }
        var cursor = start
        for s in recordedPhases.sorted(by: { $0.from < $1.from }) {
            add(cursor, s.from, .off)
            switch s.phase {
            case .direct, .paused, .noNetwork, .limited: add(s.from, s.to, s.phase)
            default: break
            }
            cursor = max(cursor, s.to)
        }
        add(cursor, to, .off)
        return out + ecoSpans
    }

    @MainActor
    static func demo(_ model: MainModel, period: Period, range: ClosedRange<Date>? = nil, now: Date = Date()) -> StatsData {
        var rng = SeededRandom(seed: 7)
        let custom = period == .custom ? range : nil
        let now = custom?.upperBound ?? now
        let from = custom?.lowerBound ?? now.addingTimeInterval(-period.seconds)
        let servers = model.servers
        // The day of the Android demo, by hour of a 24-hour cycle starting at `from`.
        func phase(at t: Date) -> (Phase, String?) {
            let h = t.timeIntervalSince(from).truncatingRemainder(dividingBy: 86_400) / 3600
            switch h {
            case ..<0.3: return (.off, nil)
            case ..<7: return (.connected, "s0")
            case ..<9: return (.off, nil)
            case ..<15: return (.connected, "s0")
            case ..<15.15: return (.direct, nil)
            case ..<18: return (.connected, "s1")
            default: return (.connected, "s0")
            }
        }
        var phases: [PhaseSpan] = []
        let stepPhase: TimeInterval = period == .hour ? 60 : 300
        var t = from
        while t < now {
            let next = min(t.addingTimeInterval(stepPhase), now)
            let (p, server) = phase(at: t)
            if let last = phases.last, last.phase == p, last.server == server { phases[phases.count - 1].to = next }
            else { phases.append(PhaseSpan(from: t, to: next, phase: p, server: server)) }
            t = next
        }
        // Checks of servers: every 10 minutes (every minute over an hour); fewer answer in the evening dip.
        let scanStep: TimeInterval = period == .hour ? 60 : period == .day ? 600 : 1800
        var scans: [ScanPoint] = []
        var pings: [String: [PingPoint]] = [:]
        var best: [PingPoint] = []
        t = from
        while t < now {
            let hour = Int(t.timeIntervalSince(from).truncatingRemainder(dividingBy: 86_400) / 3600)
            let evening = (14...15).contains(hour)
            var answered = 0
            var bestMs: Int?
            for (i, s) in servers.enumerated() {
                guard let base = s.ms else { continue }
                let down = (evening && i % 3 == 0) || rng.next(100) < 4
                let wave = 1 + 0.25 * sin(t.timeIntervalSince(from) / 7200 + Double(i))
                let ms = down ? nil : Int(Double(base) * wave) + rng.next(25)
                if ms != nil { answered += 1 }
                pings[s.id, default: []].append(PingPoint(at: t, ms: ms))
                if let ms { bestMs = min(bestMs ?? ms, ms) }
            }
            best.append(PingPoint(at: t, ms: bestMs.map { max(30, $0 - 8) }))
            scans.append(ScanPoint(at: t, total: evening ? 10 : servers.count, answered: min(answered, evening ? 10 : servers.count)))
            t = t.addingTimeInterval(scanStep)
        }
        let stats = servers.map { s -> ServerStat in
            let series = pings[s.id] ?? []
            let ok = series.compactMap(\.ms)
            let availability = series.isEmpty ? 0 : Double(ok.count) / Double(series.count)
            let usage: TimeInterval = s.id == "s0" ? 0.82 : s.id == "s1" ? 0.12 : 0
            return ServerStat(server: s, availability: availability, avg: ok.isEmpty ? nil : ok.reduce(0, +) / ok.count,
                              samples: series.count, usage: usage * period.seconds)
        }
        let scale = period.seconds / 86_400
        return StatsData(
            from: from, to: now, phases: phases, scans: scans, pings: pings, best: best, servers: stats,
            trafficProxy: Int64(13_100_000_000 * scale), trafficDirect: Int64(329_000_000 * scale),
            losses: max(0, Int(1 * scale.rounded(.up))), switches: (1, 1, 0), found: (2.3, servers.count, 3),
            health: (77, 92, 11))
    }
}

/// A small deterministic generator (the demo looks the same every time).
struct SeededRandom {
    private var state: UInt64
    init(seed: UInt64) { state = seed &* 6364136223846793005 &+ 1442695040888963407 }

    mutating func next(_ bound: Int) -> Int {
        state = state &* 6364136223846793005 &+ 1442695040888963407
        return Int((state >> 33) % UInt64(max(bound, 1)))
    }
}

/// A moment marked on a chart by a vertical line with a label (e.g. the strategy changed);
/// `edge`: the state the period started in (label at the left edge); `line` false: the first known
/// state, not a change (label only). Android `Marker`.
struct ChartMarker {
    var at: Date
    var label: String
    var edge = false
    var line = true
}

/// One slice of a subscription's check coverage (Android `StatsDb.Checked`): how many of its
/// servers a 10-minute slice checked and how many answered.
struct CheckedPoint {
    var at: Date
    var checked: Int
    var answered: Int
}

/// Latency over a period's raw samples (Android `StatsMath.Summary`): the detail tiles' figures.
struct StatsSummary {
    var count = 0
    var ok = 0
    var median: Int? = nil
    var p95: Int? = nil
    var min: Int? = nil
    var max: Int? = nil
    var mean: Int? = nil
    /// Mean absolute difference between consecutive successful samples.
    var jitter: Int? = nil
    /// Share answered by time ([ok]/[count] are plain counts).
    var byTime: Double? = nil
    var availability: Double? { byTime ?? (count == 0 ? nil : Double(ok) / Double(count)) }
}

/// Durations on the statistics screens as Android's `formatDuration` (not `Units.durationText`):
/// "<1 min", "1 min", "2 h 5 min", "3 d 4 h" — sub-minute values round up, seconds never show.
func statsDuration(_ seconds: TimeInterval) -> String {
    let m = Int(max(seconds, 0)) / 60
    if m < 1 { return L("dur_lt_min") }
    if m < 60 { return L("dur_m", m) }
    if m < 24 * 60 { return L("dur_hm", m / 60, m % 60) }
    return L("dur_dh", m / 1440, (m % 1440) / 60)
}

/// A ping as Android's `msText`: "85 ms"; from 10 s on in seconds, `Units.seconds`-style ("12 s").
func msText(_ ms: Int?) -> String {
    guard let ms else { return "—" }
    if ms >= 10_000 { return durationText(Double(ms) / 1000) }
    return L("ms", ms)
}

/// A count as Android's `Units.count`: a grouped integer ("2 865", "123 456"), then "1.2 M".
func countText(_ v: Double) -> String {
    let n = max(v, 0)
    guard n >= 1_000_000 else {
        let f = NumberFormatter()
        f.numberStyle = .decimal
        f.locale = Locale.current
        return f.string(from: NSNumber(value: Int(n.rounded()))) ?? "\(Int(n.rounded()))"
    }
    let m = n / 1_000_000
    return L("count_millions", m < 10 ? String(format: "%.1f", locale: Locale.current, m) : "\(Int(m.rounded()))")
}

/// A name cut to `max` characters in the middle (Android `shortName`): servers of one subscription
/// often share a long start ("host.example #3", "host.example #4") — the end is what tells them apart.
func shortName(_ name: String, _ max: Int) -> String {
    guard name.count > max else { return name }
    let head = name.prefix(Swift.max(max - 7, 1)).description.trimmingCharacters(in: .whitespaces)
    let tail = String(name.suffix(6)).trimmingCharacters(in: .whitespaces)
    return head + "…" + tail
}

/// Durations as Android's `Units.durationText`: "45 s", "1 min 30 s", "2 h 5 min", "3 d 4 h".
func durationText(_ seconds: TimeInterval) -> String {
    let s = Int(max(seconds, 0))
    let m = s / 60
    if s < 60 { return L("act_seconds", s) }
    if m < 10 && s % 60 != 0 { return L("dur_ms", m, s % 60) }
    if m < 60 { return L("dur_m", m) }
    if m < 24 * 60 { return m % 60 == 0 ? L("dur_h_short", m / 60) : L("dur_hm", m / 60, m % 60) }
    if (m % 1440) / 60 == 0 { return L("dur_d", m / 1440) }
    return L("dur_dh", m / 1440, (m % 1440) / 60)
}

/// A server's (or a subscription's servers') state over time (Android `StatsMath.serverStates`): in use, answering
/// (a 10-min slice with any answer), no reply; a checked slice holds until the next one, ≤ 30 min; gaps = not checked.
enum ServerState { case inUse, answered, noReply }

struct StateSpan {
    var from: Date
    var to: Date
    var state: ServerState
}

extension StatsData {
    /// Ping bands of the distribution (Android `StatsMath.histogramEdges`), shared by `histogram` and the bars
    /// that draw it; the last band is "everything above".
    static let histogramEdges = [0, 50, 100, 200, 400, 800, 1600, Int.max]

    /// The target's states: the service's measurement for the detail screens, else what the demo computes.
    /// An empty measurement falls through to the computation — the service sends none when it recorded none.
    func serverStates(_ ids: Set<String>) -> [StateSpan] {
        if let states, !states.isEmpty { return states }
        var out: [StateSpan] = []
        func add(_ from: Date, _ to: Date, _ s: ServerState) {
            if let last = out.last, last.state == s, abs(last.to.timeIntervalSince(from)) < 1 { out[out.count - 1].to = to }
            else { out.append(StateSpan(from: from, to: to, state: s)) }
        }
        let checks = ids.flatMap { pings[$0] ?? [] }.sorted { $0.at < $1.at }
        let times = Array(Set(checks.map(\.at))).sorted()
        for (i, t) in times.enumerated() {
            let end = min(i + 1 < times.count ? times[i + 1] : to, t.addingTimeInterval(1800))
            let used = phases.contains { $0.phase == .connected && ids.contains($0.server ?? "") && $0.from <= t && t < $0.to }
            let answered = checks.contains { $0.at == t && $0.ms != nil }
            add(t, end, used && answered ? .inUse : answered ? .answered : .noReply)
        }
        return out
    }

    /// How many samples fall into each band (`histogramEdges`).
    func histogram(_ ids: Set<String>) -> [Int] {
        let edges = Self.histogramEdges
        var counts = Array(repeating: 0, count: edges.count - 1)
        if let histograms, !histograms.isEmpty {
            for id in ids {
                for (i, n) in (histograms[id] ?? []).enumerated() where i < counts.count { counts[i] += n }
            }
            return counts
        }
        for p in ids.flatMap({ pings[$0] ?? [] }) {
            guard let ms = p.ms, let i = edges.indices.dropLast().last(where: { edges[$0] <= ms }) else { continue }
            counts[i] += 1
        }
        return counts
    }
}
