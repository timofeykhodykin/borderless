import Foundation
import Network
import Shared
@preconcurrency import SystemConfiguration

/// Reads the network state and makes / undoes our changes, write-ahead (see [NetworkPlan] for the rules).
enum NetworkHost {
    /// Changes and restores never overlap (requests come in on several threads).
    static let lock = NSLock()
    nonisolated(unsafe) private static let store = SCDynamicStoreCreate(nil, "app.borderless.service" as CFString, nil, nil)

    // MARK: Reading

    static func snapshot() -> NetworkSnapshot {
        let ipv4 = value("State:/Network/Global/IPv4")
        let dns = value("State:/Network/Global/DNS")
        var tunnels: [NetworkSnapshot.Tunnel] = []
        if let store, let keys = SCDynamicStoreCopyKeyList(store, "State:/Network/Service/[^/]+/IPv4" as CFString)
            as? [String] {
            for key in keys {
                guard let iface = value(key)?["InterfaceName"] as? String, iface.hasPrefix("utun") else { continue }
                let id = key.split(separator: "/")[3]
                tunnels.append(.init(serviceID: String(id), interface: iface))
            }
        }
        let routes = RouteTable.wide(RouteTable.parse(run("/usr/sbin/netstat", ["-rn"]).output))
        return NetworkSnapshot(
            takenAt: Date(), primaryInterface: ipv4?["PrimaryInterface"] as? String,
            primaryService: ipv4?["PrimaryService"] as? String, router: ipv4?["Router"] as? String,
            dnsServers: dns?["ServerAddresses"] as? [String] ?? [], defaultRoutes: routes, tunnels: tunnels)
    }

    /// The dynamic store's own values as one comparable text, no `netstat` (the tunnel's 5 s check reads the route
    /// table only when this moved — a network or a tunnel coming or going always moves the primary service, the
    /// DNS, a service's interface/addresses or the service order). A fresh value every call when the store itself
    /// is missing, so the route table is still read then.
    static func glance() -> String {
        guard let store else { return UUID().uuidString }
        var parts: [String] = []
        let ipv4 = value("State:/Network/Global/IPv4")
        parts.append(["PrimaryInterface", "PrimaryService", "Router"].map { ipv4?[$0] as? String ?? "-" }
            .joined(separator: " "))
        parts.append((value("State:/Network/Global/DNS")?["ServerAddresses"] as? [String] ?? []).joined(separator: ","))
        parts.append((value("Setup:/Network/Global/IPv4")?["ServiceOrder"] as? [String] ?? []).joined(separator: ","))
        let keys = SCDynamicStoreCopyKeyList(store, "State:/Network/Service/[^/]+/IPv4" as CFString) as? [String] ?? []
        for key in keys.sorted() {
            let state = value(key)
            let addrs = (state?["Addresses"] as? [String] ?? []).joined(separator: ",")
            parts.append("\(key)|\(state?["InterfaceName"] as? String ?? "-")|\(addrs)")
        }
        return parts.joined(separator: "\n")
    }

    /// The app asks the state every 1–3 s (`hello`), and every answer used to run `netstat -rn` and read the undo
    /// log. A second's cache makes a poll cheap; past it, the store's own values ([glance]) say whether the state
    /// underneath could have moved — the same gate the tunnel's 5 s check uses — and only then is the snapshot
    /// (the `netstat` fork) rebuilt. The undo log moves nothing in the store (a route add changes no key there),
    /// so it is still read on every answer.
    nonisolated(unsafe) private static let statusLock = NSLock()
    nonisolated(unsafe) private static var statusAt = Date.distantPast
    /// The last answers by their `ours` key (a handful of values in practice: the utun's name or none).
    nonisolated(unsafe) private static var statusCache: [String: NetworkStatus] = [:]
    /// The [glance] value [lastSnapshot] was taken against, guarded by [statusLock].
    nonisolated(unsafe) private static var lastGlance = ""
    nonisolated(unsafe) private static var lastSnapshot: NetworkSnapshot?

    static func status(ours: String? = nil) -> NetworkStatus {
        let key = ours ?? "-"
        statusLock.lock()
        defer { statusLock.unlock() }
        if Date().timeIntervalSince(statusAt) < 1, let hit = statusCache[key] { return hit }
        let now = glance()
        if lastSnapshot == nil || now != lastGlance {
            lastSnapshot = snapshot()
            // Remembered as of *before* the read: a store value that moved while `netstat` ran only makes the
            // next answer build fresh again.
            lastGlance = now
        }
        let snap = lastSnapshot!
        let status = NetworkStatus(primaryInterface: snap.primaryInterface, dnsServers: snap.dnsServers,
                                   otherTunnel: snap.otherTunnel(ours: ours), changes: UndoLog.records().count)
        statusAt = Date()
        if statusCache.count > 8 { statusCache.removeAll() }
        statusCache[key] = status
        return status
    }

    private static func value(_ key: String) -> [String: Any]? {
        guard let store else { return nil }
        return SCDynamicStoreCopyValue(store, key as CFString) as? [String: Any]
    }

    /// The DNS the network hands [iface] now, from that interface's own service keys: the global DNS
    /// can't be asked while our tunnel is up — it answers our own entry — so the service bound to the
    /// interface name is looked up (ours sits on a utun and never matches a physical name).
    static func serviceDns(matching iface: String) -> [String] {
        guard let store,
              let keys = SCDynamicStoreCopyKeyList(store, "State:/Network/Service/[^/]+/IPv4" as CFString) as? [String]
        else { return [] }
        for key in keys {
            guard value(key)?["InterfaceName"] as? String == iface else { continue }
            let id = key.split(separator: "/")[3]
            return value("State:/Network/Service/\(id)/DNS")?["ServerAddresses"] as? [String] ?? []
        }
        return []
    }

    // MARK: Metered

    /// One monitor per physical interface type — `NWPathMonitor(requiredInterfaceType:)` reports the
    /// path *that link type* would give traffic, no matter what holds the default route: a plain
    /// monitor would report our utun's own path while the tunnel is up (`macOS` has no
    /// `prohibitedInterfaceTypes` to exclude it, and ignoring non-physical paths there would freeze
    /// the reading for the whole session). The latest "metered or not" of each satisfied link, plus
    /// the interface names its path listed — the primary service's interface name picks which one
    /// the tunnel's traffic actually rides (Android's per-transport read: the network underneath).
    nonisolated(unsafe) private static var meteredMonitors: [NWPathMonitor] = []
    private static let meteredLock = NSLock()
    nonisolated(unsafe) private static var meteredNow = false
    nonisolated(unsafe) private static var meteredByType: [NWInterface.InterfaceType: (ifaces: Set<String>, metered: Bool)] = [:]
    /// The link types we watch, in fallback order ([combinedMeteredLocked]).
    private static let meteredTypes: [NWInterface.InterfaceType] = [.wifi, .wiredEthernet, .cellular]

    /// Whether the primary physical path is metered now (false until the monitors' first answer).
    static var metered: Bool {
        meteredLock.lock()
        defer { meteredLock.unlock() }
        return meteredNow
    }

    /// The metered watch, started once at service start. `isExpensive` (a hotspot, tethering — iPhone
    /// USB included — or a cellular modem) or `isConstrained` (Low Data Mode) makes a link metered;
    /// [changed] gets every flip of the combined reading — the service wires it to
    /// `EngineBridge.meteredChanged`, which feeds `Power.stretch` (probes and scans spread out on a
    /// metered path) and `EngineStatus.metered`.
    static func watchMetered(_ changed: @escaping @Sendable (Bool) -> Void) {
        for type in meteredTypes {
            let monitor = NWPathMonitor(requiredInterfaceType: type)
            monitor.pathUpdateHandler = { path in
                meteredLock.lock()
                if path.status == .satisfied {
                    meteredByType[type] = (ifaces: Set(path.availableInterfaces.map(\.name)),
                                           metered: path.isExpensive || path.isConstrained)
                } else {
                    meteredByType.removeValue(forKey: type)
                }
                let now = combinedMeteredLocked()
                let was = meteredNow
                meteredNow = now
                meteredLock.unlock()
                if was != now {
                    log("the primary path is \(now ? "metered" : "no longer metered")")
                    changed(now)
                }
            }
            monitor.start(queue: .global())
            meteredMonitors.append(monitor)
        }
    }

    /// The combined reading under [meteredLock]: the satisfied link whose interfaces hold the primary
    /// service's interface, else the first satisfied link in [meteredTypes] order (a Mac with two live
    /// links whose primary can't be resolved errs toward the common Wi-Fi case).
    private static func combinedMeteredLocked() -> Bool {
        if let primary = primaryPhysicalInterface() {
            for type in meteredTypes {
                if let state = meteredByType[type], state.ifaces.contains(primary) { return state.metered }
            }
        }
        for type in meteredTypes {
            if let state = meteredByType[type] { return state.metered }
        }
        return false
    }

    /// The interface name of the primary network service (ServiceOrder's first service with a live
    /// IPv4 entry — our own service is never in ServiceOrder; the daemon writes `State:` keys only).
    private static func primaryPhysicalInterface() -> String? {
        let order = value("Setup:/Network/Global/IPv4")?["ServiceOrder"] as? [String] ?? []
        for id in order {
            if let name = value("State:/Network/Service/\(id)/IPv4")?["InterfaceName"] as? String {
                return name
            }
        }
        return nil
    }

    // MARK: Changing (write-ahead)

    /// Before the first change of a session: keep the state as it was.
    static func beginChanges() throws {
        guard UndoLog.records().isEmpty else { return }
        let data = try JSONEncoder().encode(snapshot())
        try AtomicFile.save(data, to: ServicePaths.snapshot)
    }

    /// A route through our utun. The kernel removes it with the utun; the log is the second line of defence.
    static func addRoute(_ family: UndoRecord.Family, _ dest: String, via utun: String) throws {
        let record = UndoRecord.route(family, dest, utun)
        guard UndoRecord(line: record.line) == record else { throw HostError.invalid(record.line) }
        try beginChanges()
        try UndoLog.append(record)
        let args = family == .inet
            ? ["-n", "add", "-net", dest, "-interface", utun]
            : ["-n", "add", "-inet6", "-net", dest, "-interface", utun]
        let r = run("/sbin/route", args)
        if r.status != 0 { throw HostError.command("route add \(dest): \(r.output)") }
    }

    /// Our tunnel as the primary service (`OverridePrimary`, as macOS's own tunnel services do): the
    /// system's IP monitor then routes by it and turns the physical interface's default route into a *scoped* one —
    /// without that, sockets bound to the primary physical interface (the core's) are looked up in the global table
    /// and loop back into the tunnel (seen 2026-10-22: the core could open nothing while en0 was primary). Its DNS
    /// becomes the system's. Temporary keys: configd drops them when this process ends.
    static func setPrimary(utun: String, local: String, peer: String, dns: [String]) throws {
        let ipv4 = "State:/Network/Service/\(NetworkPlan.serviceID)/IPv4"
        let dnsKey = "State:/Network/Service/\(NetworkPlan.serviceID)/DNS"
        try beginChanges()
        try UndoLog.append(.key(dnsKey))
        try UndoLog.append(.key(ipv4))
        guard let store else { throw HostError.command("no dynamic store") }
        let dnsValue: [String: Any] = ["ServerAddresses": dns, "InterfaceName": utun]
        guard SCDynamicStoreAddTemporaryValue(store, dnsKey as CFString, dnsValue as CFDictionary) else {
            throw HostError.command("DNS key not set")
        }
        let ipValue: [String: Any] = ["Addresses": [local], "DestAddresses": [peer], "SubnetMasks": ["255.255.255.255"],
                                      "Router": peer, "InterfaceName": utun, "OverridePrimary": 1]
        guard SCDynamicStoreAddTemporaryValue(store, ipv4 as CFString, ipValue as CFDictionary) else {
            throw HostError.command("IPv4 key not set")
        }
    }

    // MARK: Undoing

    /// Undo everything ours with the installed reset script (the same one that works by hand), then compare with the
    /// snapshot. Returns the report lines.
    static func restore() -> [String] {
        lock.lock()
        defer { lock.unlock() }
        var report: [String] = []
        let records = UndoLog.records()
        // The state before our changes, read now: the reset script removes the file once nothing of ours is left.
        let before = FileManager.default.contents(atPath: ServicePaths.snapshot)
            .flatMap { try? JSONDecoder().decode(NetworkSnapshot.self, from: $0) }
        if let problem = ScriptCheck.problem(ServicePaths.resetScript) {
            report.append("reset script not used: \(problem)")
        } else {
            let r = run("/bin/sh", [ServicePaths.resetScript, "--keep-service"])
            report += r.output.split(whereSeparator: \.isNewline).map(String.init)
        }
        if let before {
            let diff = snapshot().differences(from: before)
            report += diff.isEmpty ? ["network as before"] : diff.map { "differs: \($0)" }
            try? FileManager.default.removeItem(atPath: ServicePaths.snapshot)
        }
        if records.isEmpty { report.insert("nothing of ours was in effect", at: 0) }
        return report
    }

    enum HostError: Error { case invalid(String), command(String) }

    // MARK: Plumbing

    struct Result { var status: Int32; var output: String }

    /// Runs a system tool with fixed arguments (never through a shell), with a clean environment. These tools
    /// answer in milliseconds; one that is still around after 15 s is broken and would wedge its caller.
    static func run(_ path: String, _ args: [String]) -> Result {
        let p = Process()
        p.executableURL = URL(fileURLWithPath: path)
        p.arguments = args
        p.environment = ["PATH": "/usr/bin:/bin:/usr/sbin:/sbin", "LANG": "C"]
        let pipe = Pipe()
        p.standardOutput = pipe
        p.standardError = pipe
        do { try p.run() } catch { return Result(status: -1, output: "\(error)") }
        let watchdog = DispatchWorkItem { if p.isRunning { Darwin.kill(p.processIdentifier, SIGKILL) } }
        DispatchQueue.global().asyncAfter(deadline: .now() + 15, execute: watchdog)
        let data = pipe.fileHandleForReading.readDataToEndOfFile()
        p.waitUntilExit()
        watchdog.cancel()
        return Result(status: p.terminationStatus, output: String(decoding: data, as: UTF8.self))
    }
}

/// The undo log: appended and synced before each change, emptied by the reset.
enum UndoLog {
    static func records() -> [UndoRecord] {
        guard let data = FileManager.default.contents(atPath: ServicePaths.undoLog) else { return [] }
        return UndoRecord.parse(String(decoding: data, as: UTF8.self))
    }

    static func append(_ record: UndoRecord) throws {
        let fd = open(ServicePaths.undoLog, O_WRONLY | O_APPEND | O_CREAT | O_NOFOLLOW | O_CLOEXEC, 0o600)
        guard fd >= 0 else { throw NetworkHost.HostError.command("undo log: errno \(errno)") }
        defer { close(fd) }
        let line = Array((record.line + "\n").utf8)
        guard line.withUnsafeBytes({ writeAll($0, to: fd) }), fsync(fd) == 0 else {
            throw NetworkHost.HostError.command("undo log write: errno \(errno)")
        }
    }

    /// All of [raw] to [fd]: one `write` is not promised to take everything.
    static func writeAll(_ raw: UnsafeRawBufferPointer, to fd: Int32) -> Bool {
        guard var p = raw.baseAddress else { return true }
        var left = raw.count
        while left > 0 {
            let n = write(fd, p, left)
            if n < 0 && errno == EINTR { continue }
            if n <= 0 { return false }
            p += n
            left -= n
        }
        return true
    }
}

enum AtomicFile {
    /// tmp + fsync + rename: a crash leaves the old file or the new one, never half of one.
    static func save(_ data: Data, to path: String) throws {
        let tmp = path + ".tmp"
        let fd = open(tmp, O_WRONLY | O_CREAT | O_TRUNC | O_NOFOLLOW | O_CLOEXEC, 0o600)
        guard fd >= 0 else { throw NetworkHost.HostError.command("\(tmp): errno \(errno)") }
        let ok = data.withUnsafeBytes { UndoLog.writeAll($0, to: fd) } && fsync(fd) == 0
        close(fd)
        guard ok else {
            unlink(tmp)
            throw NetworkHost.HostError.command("\(path): errno \(errno)")
        }
        guard rename(tmp, path) == 0 else { throw NetworkHost.HostError.command("\(path): errno \(errno)") }
    }
}

/// Root runs only a script nobody else could have changed: owned by root, not writable by group or others, in a
/// directory with the same property.
enum ScriptCheck {
    static func problem(_ path: String) -> String? {
        for p in [path, (path as NSString).deletingLastPathComponent] {
            var st = stat()
            guard lstat(p, &st) == 0 else { return "\(p) missing" }
            if st.st_uid != 0 { return "\(p) not owned by root" }
            if st.st_mode & (S_IWGRP | S_IWOTH) != 0 { return "\(p) writable by others" }
            if p == path && (st.st_mode & S_IFMT) != S_IFREG { return "\(p) not a plain file" }
        }
        return nil
    }
}
