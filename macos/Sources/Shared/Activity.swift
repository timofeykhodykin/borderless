import Foundation

/// The service's `stats` target "activity" (ENERGY-IMPL, `MacActivity.kt`): what the Mac's meter
/// measured over the period — the same figures Android's `ActivityData` carries — as one JSON the
/// app decodes leniently: every field reads as missing so an older or newer service's reply still
/// parses (mixed-version compatibility, like every `stats` reply).
public struct ActivityOut: Decodable, Sendable {
    public var from: Int64
    public var to: Int64
    /// Statistics exist only from here on; earlier time is unknown, not "off".
    public var dataStart: Int64
    /// Per kind: count, bytes, radio ms (always 0 on the Mac — no radio model exists), CPU ms and mJ.
    public var totals: [String: Total]
    /// Grouped action counts (the network actions tile and chart): PING / PROBE / OTHER / SCAN / SAVED / LOCAL.
    public var groups: [String: Int64]
    /// Pings that rode on the user's own traffic (WorkKind.PING_RIDE).
    public var pingsRide: Int64
    /// Actions that would wake a radio on their own — "woke the network" here.
    public var wakeups: Int64
    /// Rescans the battery saving skipped.
    public var skipped: Int64
    /// (t, group, n) action counts per time bucket.
    public var buckets: [Bucket]
    /// The power/conditions samples (each carries the CPU of all roles since the previous one).
    public var power: [Power]
    public var summary: Summary
    /// The energy breakdown's lines (biggest first); `label` is a strings name the app renders.
    public var parts: [Part]
    /// Energy over time: mJ per part name per bucket, and how long the service ran in it.
    public var energy: [EnergyBucket]
    /// `ri_energy_nj` produced real readings (Apple Silicon); false: the mJ are the CPU-time model.
    public var measuredEnergy: Bool

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        from = try c.decode(Int64.self, forKey: .from)
        to = try c.decode(Int64.self, forKey: .to)
        dataStart = try c.decodeIfPresent(Int64.self, forKey: .dataStart) ?? to
        totals = try c.decodeIfPresent([String: Total].self, forKey: .totals) ?? [:]
        groups = try c.decodeIfPresent([String: Int64].self, forKey: .groups) ?? [:]
        pingsRide = try c.decodeIfPresent(Int64.self, forKey: .pingsRide) ?? 0
        wakeups = try c.decodeIfPresent(Int64.self, forKey: .wakeups) ?? 0
        skipped = try c.decodeIfPresent(Int64.self, forKey: .skipped) ?? 0
        buckets = try c.decodeIfPresent([Bucket].self, forKey: .buckets) ?? []
        power = try c.decodeIfPresent([Power].self, forKey: .power) ?? []
        summary = try c.decode(Summary.self, forKey: .summary)
        parts = try c.decodeIfPresent([Part].self, forKey: .parts) ?? []
        energy = try c.decodeIfPresent([EnergyBucket].self, forKey: .energy) ?? []
        measuredEnergy = try c.decodeIfPresent(Bool.self, forKey: .measuredEnergy) ?? false
    }

    enum CodingKeys: String, CodingKey {
        case from, to, dataStart, totals, groups, pingsRide, wakeups, skipped, buckets, power
        case summary, parts, energy, measuredEnergy
    }

    /// Part name → its strings key (the energy chart's series labels).
    public var partLabels: [String: String] {
        Dictionary(uniqueKeysWithValues: parts.map { ($0.part, $0.label) })
    }

    /// Android's empty test: nothing counted and not enough power samples to say anything.
    public var isEmpty: Bool { totals.isEmpty && power.count < 2 }

    public struct Total: Decodable, Sendable {
        public var n: Int64 = 0
        public var bytes: Int64 = 0
        public var radioMs: Int64 = 0
        public var cpuMs: Int64 = 0
        public var mj: Int64 = 0
        public init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            n = try c.decodeIfPresent(Int64.self, forKey: .n) ?? 0
            bytes = try c.decodeIfPresent(Int64.self, forKey: .bytes) ?? 0
            radioMs = try c.decodeIfPresent(Int64.self, forKey: .radioMs) ?? 0
            cpuMs = try c.decodeIfPresent(Int64.self, forKey: .cpuMs) ?? 0
            mj = try c.decodeIfPresent(Int64.self, forKey: .mj) ?? 0
        }
        enum CodingKeys: String, CodingKey { case n, bytes, radioMs, cpuMs, mj }
    }

    public struct Bucket: Decodable, Sendable {
        public var t: Int64
        public var group: String
        public var n: Int64
        public init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            t = try c.decode(Int64.self, forKey: .t)
            group = try c.decodeIfPresent(String.self, forKey: .group) ?? "OTHER"
            n = try c.decodeIfPresent(Int64.self, forKey: .n) ?? 0
        }
        enum CodingKeys: String, CodingKey { case t, group, n }
    }

    public struct Power: Decodable, Sendable {
        public var ts: Int64
        public var cpuMs: Int64
        public init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            ts = try c.decode(Int64.self, forKey: .ts)
            cpuMs = try c.decodeIfPresent(Int64.self, forKey: .cpuMs) ?? 0
        }
        enum CodingKeys: String, CodingKey { case ts, cpuMs }
    }

    public struct Part: Decodable, Sendable {
        public var part: String
        public var label: String
        public var mj: Int64 = 0
        public var share: Double = 0
        public init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            part = try c.decode(String.self, forKey: .part)
            label = try c.decodeIfPresent(String.self, forKey: .label) ?? ""
            mj = try c.decodeIfPresent(Int64.self, forKey: .mj) ?? 0
            share = try c.decodeIfPresent(Double.self, forKey: .share) ?? 0
        }
        enum CodingKeys: String, CodingKey { case part, label, mj, share }
    }

    public struct EnergyBucket: Decodable, Sendable {
        public var t: Int64
        public var coveredMs: Int64
        public var mj: [String: Double]
        public init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            t = try c.decode(Int64.self, forKey: .t)
            coveredMs = try c.decodeIfPresent(Int64.self, forKey: .coveredMs) ?? 0
            mj = try c.decodeIfPresent([String: Double].self, forKey: .mj) ?? [:]
        }
        enum CodingKeys: String, CodingKey { case t, coveredMs, mj }
    }

    public struct Summary: Decodable, Sendable {
        public var hours = 0.0
        public var cpuSeconds = 0.0
        public var cpuPerHour: Double? = nil
        public var avgMemoryMb: Double? = nil
        public var maxMemoryMb: Double? = nil
        public var screenOff = 0.0
        public var metered = 0.0
        public var saver = 0.0
        public var charging = 0.0
        /// Hours of the covered time the battery saving mode was on.
        public var ecoHours = 0.0
        /// The measured energy as average watts over the covered time (null: nothing measured).
        public var watts: Double? = nil
        public var capacityMah: Int? = nil
        public var volts: Double? = nil
        public var drainPerHour: Double? = nil

        public init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            hours = try c.decodeIfPresent(Double.self, forKey: .hours) ?? 0
            cpuSeconds = try c.decodeIfPresent(Double.self, forKey: .cpuSeconds) ?? 0
            cpuPerHour = try c.decodeIfPresent(Double.self, forKey: .cpuPerHour)
            avgMemoryMb = try c.decodeIfPresent(Double.self, forKey: .avgMemoryMb)
            maxMemoryMb = try c.decodeIfPresent(Double.self, forKey: .maxMemoryMb)
            screenOff = try c.decodeIfPresent(Double.self, forKey: .screenOff) ?? 0
            metered = try c.decodeIfPresent(Double.self, forKey: .metered) ?? 0
            saver = try c.decodeIfPresent(Double.self, forKey: .saver) ?? 0
            charging = try c.decodeIfPresent(Double.self, forKey: .charging) ?? 0
            ecoHours = try c.decodeIfPresent(Double.self, forKey: .ecoHours) ?? 0
            watts = try c.decodeIfPresent(Double.self, forKey: .watts)
            capacityMah = try c.decodeIfPresent(Int.self, forKey: .capacityMah)
            volts = try c.decodeIfPresent(Double.self, forKey: .volts)
            drainPerHour = try c.decodeIfPresent(Double.self, forKey: .drainPerHour)
        }

        enum CodingKeys: String, CodingKey {
            case hours, cpuSeconds, cpuPerHour, avgMemoryMb, maxMemoryMb
            case screenOff, metered, saver, charging, ecoHours, watts
            case capacityMah, volts, drainPerHour
        }
    }
}
