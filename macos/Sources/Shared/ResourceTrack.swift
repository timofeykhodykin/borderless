import Foundation

/// One role's process counters, summed over a changing set of pids (ENERGY-IMPL, pure — the Checks
/// suite runs it on made-up readings). `proc_pid_rusage` counters are monotonic per process; a role
/// (the main core, the limited cores) is a set of pids that die and get replaced, so the role's
/// total is the sum of every pid's own delta — a restarted pid's fresh counters can't fake a
/// decrease, and a recycled pid (its start time changed) starts a fresh contribution instead of
/// reading the old process's counters as its own.
public struct ResourceTrack: Sendable {

    /// One `proc_pid_rusage` reading of one process: its start stamp (`ri_proc_start_abstime`,
    /// tells a recycled pid apart), CPU ms so far (`ri_user_time + ri_system_time`, mach units
    /// converted), nanojoules so far (`ri_energy_nj`, 0 forever on Intel) and the live physical
    /// footprint in KiB (`ri_phys_footprint`, instantaneous — not a counter).
    public struct Sample: Sendable, Equatable {
        public var start: UInt64
        public var cpuMs: Int64
        public var nj: Int64
        public var fbKb: Int64
        public init(start: UInt64, cpuMs: Int64, nj: Int64, fbKb: Int64) {
            self.start = start; self.cpuMs = cpuMs; self.nj = nj; self.fbKb = fbKb
        }
    }

    /// What the role reports: cumulative CPU ms and nanojoules (the Kotlin side's role counters)
    /// and the live footprint of the pids counted now.
    public private(set) var cpuMs: Int64 = 0
    public private(set) var nj: Int64 = 0
    public private(set) var fbKb: Int64 = 0

    /// Counters dead or replaced pids already contributed (their last delta).
    private var carried: (cpu: Int64, nj: Int64) = (0, 0)
    /// Live pids: start stamp → first and latest readings.
    private var live: [pid_t: (start: UInt64, first: Sample, cur: Sample)] = [:]

    public init() {}

    /// Refreshes the role with the current readings ([samples] is the role's whole live set — a pid
    /// missing here ended). A counter that moved backwards (a kernel quirk, never a fresh process —
    /// those come with a new start stamp) reads as no change, never a negative delta.
    public mutating func update(_ samples: [pid_t: Sample]) {
        var gone = Set(live.keys)
        for (pid, s) in samples {
            gone.remove(pid)
            if var e = live[pid], e.start == s.start {
                e.cur = s
                live[pid] = e
            } else {
                // A new process, or the pid recycled: fold whatever the previous entry contributed
                // into the role's total and start the new one at its own counters.
                if let e = live[pid] { fold(e) }
                live[pid] = (s.start, s, s)
            }
        }
        for pid in gone {
            if let e = live.removeValue(forKey: pid) { fold(e) }
        }
        cpuMs = carried.cpu + live.values.reduce(0) { $0 + max($1.cur.cpuMs - $1.first.cpuMs, 0) }
        nj = carried.nj + live.values.reduce(0) { $0 + max($1.cur.nj - $1.first.nj, 0) }
        fbKb = live.values.reduce(0) { $0 + max($1.cur.fbKb, 0) }
    }

    private mutating func fold(_ e: (start: UInt64, first: Sample, cur: Sample)) {
        carried.cpu += max(e.cur.cpuMs - e.first.cpuMs, 0)
        carried.nj += max(e.cur.nj - e.first.nj, 0)
    }
}
