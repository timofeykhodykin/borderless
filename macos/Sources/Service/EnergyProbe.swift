import Foundation
import IOKit
import IOKit.ps
import Shared

/// The Mac energy meter's readings (ENERGY-DESIGN §4): `proc_pid_rusage` (RUSAGE_INFO_V6 =
/// RUSAGE_INFO_CURRENT on this SDK; `ri_energy_nj` exists from V5 on) for the service itself, its
/// helper, the main core and the live limited cores, folded into the four roles the Kotlin meter
/// expects (`MacProcesses.resources()`), plus the battery figures `AppleSmartBattery` reports honestly.
///
/// Units the engine's line carries: CPU in milliseconds (`ri_user_time + ri_system_time` are mach
/// absolute-time units, NOT nanoseconds — verified live against `getrusage` on the owner's M5;
/// convert by `mach_timebase_info`, numer/denom = 125/3 on arm64), energy in nanojoules
/// (`ri_energy_nj`; 0 forever on Intel, real on Apple Silicon — never estimated here), footprint in
/// KiB (`ri_phys_footprint`). The roles' counters only grow: a pid that died or was replaced folds
/// its contribution into the role's total ([ResourceTrack]).
enum EnergyProbe {

    /// The whole `resources()` line: "cpuMs nj fbKb" × 4 roles (service, helper, core, limited
    /// summed), then the wall-clock ms of the last heavy client request ([noteClientActivity] —
    /// the stats/log screens' work; the ambient status poll doesn't stamp, the menubar icon would
    /// keep the service "non-quiet" forever otherwise). Called on the engine's threads; the pid sets
    /// come from the callers that own the processes ([EngineBridge]'s helper, tunnel, limited cores).
    static func resources(helper helperPid: pid_t?, core corePid: pid_t?, limited limitedPids: [pid_t]) -> String {
        lock.lock()
        defer { lock.unlock() }
        // The service itself, keyed like the children (its pid is stable, but the same code covers it).
        self_.update(snapshot(of: getpid()))
        helper.update(one(helperPid))
        core.update(one(corePid))
        var lim = [pid_t: ResourceTrack.Sample]()
        for p in limitedPids { if let s = sample(of: p) { lim[p] = s } }
        limited.update(lim)
        return "\(self_.cpuMs) \(self_.nj) \(self_.fbKb) \(helper.cpuMs) \(helper.nj) \(helper.fbKb) " +
            "\(core.cpuMs) \(core.nj) \(core.fbKb) \(limited.cpuMs) \(limited.nj) \(limited.fbKb) \(lastClientReqMs)"
    }

    /// A heavy client request ran (everything but the `.hello`/`.status` ambient poll — see above):
    /// stamps the "the app's screens are up" moment the engine's `uiQuietSince` answers from.
    static func noteClientActivity() {
        lock.lock()
        lastClientReqMs = Int64(Date().timeIntervalSince1970 * 1000)
        lock.unlock()
    }

    /// The battery's own numbers when `AppleSmartBattery` reports them (nil each on a desktop or a
    /// missing key — never estimated): design capacity in mAh and the voltage in mV. The charging
    /// state and percent come separately (EngineBridge's `power()`): those exist on every Mac.
    static func battery() -> (capacityMah: Int?, mv: Int?) {
        let service = IOServiceGetMatchingService(kIOMainPortDefault, IOServiceMatching("AppleSmartBattery"))
        guard service != 0 else { return (nil, nil) }
        defer { IOObjectRelease(service) }
        var props: Unmanaged<CFMutableDictionary>?
        guard IORegistryEntryCreateCFProperties(service, &props, kCFAllocatorDefault, 0) == KERN_SUCCESS,
              let d = props?.takeRetainedValue() as? [String: Any]
        else { return (nil, nil) }
        // The keys sit both at the top level and inside `BatteryData` (the SDK's shapes differ by
        // macOS version — check both, nil stays nil).
        let sub = d["BatteryData"] as? [String: Any] ?? [:]
        func num(_ key: String) -> Int? {
            (d[key] as? Int ?? sub[key] as? Int).flatMap { $0 > 0 ? $0 : nil }
        }
        return (num("DesignCapacity"), num("Voltage"))
    }

    // ------------------------------------------------------------------ internals

    private static let lock = NSLock()
    // `nonisolated(unsafe)`: guarded by `lock` — the pattern the service's other singletons use.
    private nonisolated(unsafe) static var self_ = ResourceTrack()
    private nonisolated(unsafe) static var helper = ResourceTrack()
    private nonisolated(unsafe) static var core = ResourceTrack()
    private nonisolated(unsafe) static var limited = ResourceTrack()
    private nonisolated(unsafe) static var lastClientReqMs: Int64 = 0

    /// mach absolute-time → nanoseconds (`ri_user_time`/`ri_system_time` tick in mach units;
    /// numer/denom is 125/3 on arm64 — ENERGY-DESIGN §1a, verified against getrusage).
    private static let timebase: (numer: UInt64, denom: UInt64) = {
        var info = mach_timebase_info_data_t()
        mach_timebase_info(&info)
        return (UInt64(info.numer), UInt64(info.denom))
    }()

    /// One `proc_pid_rusage` reading, or nil when the pid can't be read (ended meanwhile — its last
    /// contribution stays folded in the role's [ResourceTrack]).
    private static func sample(of pid: pid_t) -> ResourceTrack.Sample? {
        var ri = rusage_info_v6()
        // RUSAGE_INFO_V6 is what RUSAGE_INFO_CURRENT resolves to on this SDK; the fields the line
        // uses (energy, footprint, start stamp) exist from V4 on, V6 is simply the current flavor.
        // ≥ 0 is success (0 per the verified probe; a byte count would count too) — −1 is the error.
        // (`rusage_info_t` is `void *` — the buffer is passed as a pointer to it.)
        let rc = withUnsafeMutablePointer(to: &ri) {
            $0.withMemoryRebound(to: rusage_info_t?.self, capacity: 1) {
                proc_pid_rusage(pid, RUSAGE_INFO_V6, $0)
            }
        }
        guard rc >= 0 else { return nil }
        let cpuNs = (ri.ri_user_time + ri.ri_system_time) * timebase.numer / timebase.denom
        return ResourceTrack.Sample(start: ri.ri_proc_start_abstime, cpuMs: Int64(cpuNs / 1_000_000),
                                    nj: Int64(ri.ri_energy_nj), fbKb: Int64(ri.ri_phys_footprint / 1024))
    }

    /// The single-pid sample set for a role of one process (empty when the pid can't be read — the
    /// role's [ResourceTrack] then folds the last contribution it saw).
    private static func one(_ pid: pid_t?) -> [pid_t: ResourceTrack.Sample] {
        pid.flatMap { p in sample(of: p).map { [p: $0] } } ?? [:]
    }

    /// The one-pid form for the service itself.
    private static func snapshot(of pid: pid_t) -> [pid_t: ResourceTrack.Sample] {
        one(pid)
    }
}
