import Foundation

// MARK: The statistics reply's additive fields (APP-STATS)
//
// The base decode lives in `StatsData.init?(json:model:)` inside Model.swift (APP-LIST's file). The
// newer fields the service ships — the raw-sample summary of a `s:` target, per-bucket failure
// counts, the subscription's own check coverage, strategy markers and eco spans — are read here so
// the screens get them without a foreign-file edit. `enrich` reads the same reply a second time
// leniently: a missing additive field leaves the matching fallback in place.
//
// Wire additions this file already understands (present fields are used, absent ones stay empty —
// they are seams for SVC-ENGINE until the reply carries them):
//   `servers[].ok`, `servers[].slices`      Aggregate.ok / slices → weighted availability & score
//   `pings[][].failed`, `…[].total`         failure ticks under the detail chart
//   `best[].failed`, `best[].total`         same for the best-of-all line
//   `subBest`                               StatsDb.bucketsAmong — the sub's real best line
//   `pingOrder`                             the service's own top-5 order of `pings`
//   `checked`                               StatsDb.checkedAmong — the sub's own answered chart
//   `markers`   [{t, strategy, edge, line}] strategy changes over the period (settings history)
//   `ecoSpans`  [{start, end}]              the battery-saving intervals (hatched bands)
//   `usage[].expire`                        provider's expiry in the newest report
//   `answers`, `checks`, `availability`, `median`, `p95`, `min`, `max`, `jitter`
//                                           StatsMath.summarize over raw samples (shipped now)

extension StatsData {
    /// Fills the additive fields of a decoded `StatsData` from the same `stats` reply JSON.
    /// Cheap enough for the 15 s refresh (one small JSON pass over the same text).
    @MainActor
    mutating func enrich(wire json: String) {
        struct Extra: Decodable {
            var pings: [String: [Pt]] = [:]
            var best: [Pt] = []
            var subBest: [Pt] = []
            var servers: [Srv] = []
            var checked: [Chk] = []
            var markers: [Mk] = []
            var ecoSpans: [Sp] = []
            var pingOrder: [String] = []
            var usage: [Use] = []
            var answers: Int?
            var checks: Int?
            var availability: Double?
            var median: Int?
            var p95: Int?
            var min: Int?
            var max: Int?
            var jitter: Int?

            init(from decoder: Decoder) throws {
                let c = try decoder.container(keyedBy: CodingKeys.self)
                pings = try c.decodeIfPresent([String: [Pt]].self, forKey: .pings) ?? [:]
                best = try c.decodeIfPresent([Pt].self, forKey: .best) ?? []
                subBest = try c.decodeIfPresent([Pt].self, forKey: .subBest) ?? []
                servers = try c.decodeIfPresent([Srv].self, forKey: .servers) ?? []
                checked = try c.decodeIfPresent([Chk].self, forKey: .checked) ?? []
                markers = try c.decodeIfPresent([Mk].self, forKey: .markers) ?? []
                ecoSpans = try c.decodeIfPresent([Sp].self, forKey: .ecoSpans) ?? []
                pingOrder = try c.decodeIfPresent([String].self, forKey: .pingOrder) ?? []
                usage = try c.decodeIfPresent([Use].self, forKey: .usage) ?? []
                answers = try c.decodeIfPresent(Int.self, forKey: .answers)
                checks = try c.decodeIfPresent(Int.self, forKey: .checks)
                availability = try c.decodeIfPresent(Double.self, forKey: .availability)
                median = try c.decodeIfPresent(Int.self, forKey: .median)
                p95 = try c.decodeIfPresent(Int.self, forKey: .p95)
                min = try c.decodeIfPresent(Int.self, forKey: .min)
                max = try c.decodeIfPresent(Int.self, forKey: .max)
                jitter = try c.decodeIfPresent(Int.self, forKey: .jitter)
            }

            enum CodingKeys: String, CodingKey {
                case pings, best, subBest, servers, checked, markers, ecoSpans, pingOrder, usage
                case answers, checks, availability, median, p95, min, max, jitter
            }

            struct Pt: Decodable {
                var t: Int64
                var ms: Int?
                var failed = 0
                var total = 0
                init(from decoder: Decoder) throws {
                    let c = try decoder.container(keyedBy: CodingKeys.self)
                    t = try c.decode(Int64.self, forKey: .t)
                    ms = try c.decodeIfPresent(Int.self, forKey: .ms)
                    failed = try c.decodeIfPresent(Int.self, forKey: .failed) ?? 0
                    total = try c.decodeIfPresent(Int.self, forKey: .total) ?? 0
                }
                enum CodingKeys: String, CodingKey { case t, ms, failed, total }
            }
            struct Srv: Decodable {
                var id: String
                var availability: Double?
                var avg: Int?
                var samples = 0
                var usage: Int64 = 0
                var chosen = 0
                var ok: Int?
                var slices: Int?
                init(from decoder: Decoder) throws {
                    let c = try decoder.container(keyedBy: CodingKeys.self)
                    id = try c.decode(String.self, forKey: .id)
                    availability = try c.decodeIfPresent(Double.self, forKey: .availability)
                    avg = try c.decodeIfPresent(Int.self, forKey: .avg)
                    samples = try c.decodeIfPresent(Int.self, forKey: .samples) ?? 0
                    usage = try c.decodeIfPresent(Int64.self, forKey: .usage) ?? 0
                    chosen = try c.decodeIfPresent(Int.self, forKey: .chosen) ?? 0
                    ok = try c.decodeIfPresent(Int.self, forKey: .ok)
                    slices = try c.decodeIfPresent(Int.self, forKey: .slices)
                }
                enum CodingKeys: String, CodingKey { case id, availability, avg, samples, usage, chosen, ok, slices }
            }
            struct Chk: Decodable {
                var t = Int64(0)
                var checked = 0
                var answered = 0
                init(from decoder: Decoder) throws {
                    let c = try decoder.container(keyedBy: CodingKeys.self)
                    t = try c.decodeIfPresent(Int64.self, forKey: .t) ?? 0
                    checked = try c.decodeIfPresent(Int.self, forKey: .checked) ?? 0
                    answered = try c.decodeIfPresent(Int.self, forKey: .answered) ?? 0
                }
                enum CodingKeys: String, CodingKey { case t, checked, answered }
            }
            struct Mk: Decodable {
                var t: Int64
                var strategy: String
                var edge = false
                var line = true
                init(from decoder: Decoder) throws {
                    let c = try decoder.container(keyedBy: CodingKeys.self)
                    t = try c.decode(Int64.self, forKey: .t)
                    strategy = try c.decode(String.self, forKey: .strategy)
                    edge = try c.decodeIfPresent(Bool.self, forKey: .edge) ?? false
                    line = try c.decodeIfPresent(Bool.self, forKey: .line) ?? true
                }
                enum CodingKeys: String, CodingKey { case t, strategy, edge, line }
            }
            struct Sp: Decodable {
                var start = Int64(0)
                var end = Int64(0)
                init(from decoder: Decoder) throws {
                    let c = try decoder.container(keyedBy: CodingKeys.self)
                    start = try c.decodeIfPresent(Int64.self, forKey: .start) ?? 0
                    end = try c.decodeIfPresent(Int64.self, forKey: .end) ?? 0
                }
                enum CodingKeys: String, CodingKey { case start, end }
            }
            struct Use: Decodable {
                var t = Int64(0)
                var expire: Int64?
                init(from decoder: Decoder) throws {
                    let c = try decoder.container(keyedBy: CodingKeys.self)
                    t = try c.decodeIfPresent(Int64.self, forKey: .t) ?? 0
                    expire = try c.decodeIfPresent(Int64.self, forKey: .expire)
                }
                enum CodingKeys: String, CodingKey { case t, expire }
            }
        }
        guard let e = try? JSONDecoder().decode(Extra.self, from: Data(json.utf8)) else { return }
        func date(_ ms: Int64) -> Date { Date(timeIntervalSince1970: Double(ms) / 1000) }
        func points(_ pts: [Extra.Pt]) -> [PingPoint] {
            pts.map { PingPoint(at: date($0.t), ms: $0.ms, failed: $0.failed, total: $0.total) }
        }
        // The series' decode is repeated with the failure counts on top of what the base decode saw.
        if !e.pings.isEmpty { pings = e.pings.mapValues(points) }
        if !e.best.isEmpty { best = points(e.best) }
        if !e.subBest.isEmpty { subBest = points(e.subBest) }
        for s in e.servers {
            if let i = servers.firstIndex(where: { $0.id == s.id }) {
                servers[i].ok = s.ok
                servers[i].slices = s.slices
            } else {
                // A server the list no longer has (deleted) still owns its statistics — the base
                // decode drops rows it can't name, so the `s:<id>` page would sit empty (F-STATS-0073).
                // The phantom row feeds the detail screen; overview lists filter `phantom` out.
                let stub = ServerItem(id: s.id, name: s.id, country: nil, groupId: "", proto: "", ms: nil)
                servers.append(ServerStat(server: stub, availability: s.availability ?? 0, avg: s.avg,
                                          samples: s.samples, usage: Double(s.usage) / 1000,
                                          ok: s.ok, slices: s.slices, phantom: true))
                if chosen[s.id] == nil { chosen[s.id] = s.chosen }
            }
        }
        if !e.checked.isEmpty {
            checked = e.checked.map { CheckedPoint(at: date($0.t), checked: $0.checked, answered: $0.answered) }
        }
        if !e.markers.isEmpty {
            markers = e.markers.map {
                ChartMarker(at: date($0.t), label: Strategy(engine: $0.strategy)?.title ?? $0.strategy,
                            edge: $0.edge, line: $0.line)
            }
        }
        if !e.ecoSpans.isEmpty {
            ecoSpans = e.ecoSpans.filter { $0.end > $0.start }
                .map { PhaseSpan(from: date($0.start), to: date($0.end), phase: .connected, eco: true) }
        }
        if !e.pingOrder.isEmpty { pingOrder = e.pingOrder }
        usageExpire = e.usage.last(where: { $0.expire != nil })?.expire.map(date)
        if e.checks != nil || e.answers != nil || e.median != nil {
            summary = StatsSummary(count: e.checks ?? 0, ok: e.answers ?? 0, median: e.median, p95: e.p95,
                                   min: e.min, max: e.max, mean: nil, jitter: e.jitter, byTime: e.availability)
        }
    }
}

extension StatsData {
    /// The reply decoded whole: the base fields by `init(json:model:)` (Model.swift), the additive
    /// ones by [enrich]. The loadScreens call site keeps `init(json:model:)` + `enrich` so the
    /// wire stays one parse in each direction of a service update; this convenience is for new code.
    @MainActor
    init?(wireJson json: String, model: MainModel) {
        self.init(json: json, model: model)
        enrich(wire: json)
    }
}
