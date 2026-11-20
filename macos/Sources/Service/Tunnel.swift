import Foundation
import Shared
@preconcurrency import SystemConfiguration

/// The tunnel: our utun and the routes and DNS that send the Mac's traffic into it, bound to the life of the processes
/// (see [NetworkPlan]); stopping it, or any failure along the way, ends in [NetworkHost.restore]. What runs behind it —
/// which server, the core process, checks, failover — is the shared engine's (`EngineBridge` → `MacEngine`), which
/// starts the core here ([startCore]) on our utun.
final class TunnelController: @unchecked Sendable {
    private let lock = NSLock()
    private var utun: Utun?
    /// The core process, guarded by [coreLock] (the engine starts and stops it from its own threads).
    private let coreLock = NSLock()
    private var core: CoreProcess?
    /// The interface the core's connections leave by and that network's resolver, taken at start.
    private var physical: String?
    private var resolver: String?
    /// [physical]'s kind for the stats ("wifi" / "ethernet" / "other" — Android's words), taken with it.
    private var netKind = "other"
    private var watch: DispatchSourceTimer?
    /// Network changes and undoes never overlap: a start's changes, a stop's restore, another stop and the
    /// engine's turnOn/turnOff run one at a time under it ([lock] is held only to change fields, so a status
    /// read never waits for either's seconds).
    private let changesMutex = NSLock()

    /// Our utun's name while it exists (so status never calls our own tunnel "another one").
    var utunName: String? {
        lock.lock()
        defer { lock.unlock() }
        return utun?.name
    }
    /// The utun's descriptor while it exists (the engine hands it to the core), else 0.
    var utunFd: Int32 {
        lock.lock()
        defer { lock.unlock() }
        return utun?.fd ?? 0
    }
    private var _state: TunnelState = .off
    /// The tunnel's state; written only under [lock], read from anywhere.
    var state: TunnelState {
        lock.lock()
        defer { lock.unlock() }
        return _state
    }
    /// Why the last start failed or the tunnel stopped by itself (for the app); same locking.
    private var _lastProblem: String?
    var lastProblem: String? {
        lock.lock()
        defer { lock.unlock() }
        return _lastProblem
    }
    private func setProblem(_ text: String?) {
        lock.lock()
        _lastProblem = text
        lock.unlock()
    }

    /// A service-level problem the app shows as the tunnel's ([lastProblem], like a start failure's)
    /// until the next start clears it — a dead previous run, which nothing else surfaces.
    func noteServiceProblem(_ text: String) { setProblem(text) }

    /// Point-to-point addresses of the utun (sing-box's default pair) and a ULA /126 for IPv6. The system resolver is
    /// pointed at the peer address: queries go into the tunnel, where the core answers port 53. Not link-local
    /// 169.254.x: macOS keeps 169.254/16 on the Wi-Fi interface, so the peer's address went out there (2026-10-22).
    static let local4 = "172.19.0.1", peer4 = "172.19.0.2"
    static let local6 = "fd62:6c73:6e65::1"

    /// The user's wish, kept across restarts of the service (an update, a crash, a reboot): on start the service
    /// connects again if it was on — like Android's AutoStart — unless another tunnel is in use by then.
    static let wantedPath = ServicePaths.dataDir + "/wanted"

    static var wanted: Bool {
        get { FileManager.default.fileExists(atPath: wantedPath) }
        set {
            if newValue {
                FileManager.default.createFile(atPath: wantedPath, contents: Data("1\n".utf8),
                                               attributes: [.posixPermissions: 0o600])
            } else {
                try? FileManager.default.removeItem(atPath: wantedPath)
            }
        }
    }

    /// The user turned it on (remembered) — see [start]; with [serverId] the engine moves to that server (and the
    /// strategy becomes Manual, as a pick by hand on Android).
    func connect(serverId: String?) -> String? {
        Self.wanted = true
        // Engine on/off is serialized with the tunnel's changes under [changesMutex]: a turnOn can never land
        // between a stop's turnOff and its teardown (that left the engine up on a torn-down tunnel). And only a
        // .connected tunnel gets it: a .connecting one has no utun fd for the core yet (its start's own turnOn
        // comes when it does).
        changesMutex.lock()
        let up = state == .connected
        if up { EngineBridge.turnOn(serverId: serverId) }
        changesMutex.unlock()
        guard !up else { return nil }
        let problem = start(serverId: serverId)
        // A deliberate disconnect during the slow start already clears the wish; this covers the
        // failure path — a tunnel that never connected must not auto-reconnect at the service's
        // next launch ([check]'s auto-restart is a different case: its tunnel worked before).
        if problem != nil { Self.wanted = false }
        return problem
    }

    /// The user turned it off (forgotten).
    func disconnect() -> [String] {
        Self.wanted = false
        return stop()
    }

    /// Same shape as [stop]: the fields under [lock] (microseconds — a `hello` read never waits), the changes
    /// (a snapshot, the utun, ifconfig, routes, the primary service — seconds) outside it under [changesMutex].
    func start(serverId: String? = nil) -> String? {
        // Serialized with a stop's restore: an undo running over a start's own changes would take them back.
        changesMutex.lock()
        lock.lock()
        guard _state == .off else {
            lock.unlock()
            changesMutex.unlock()
            return nil
        }
        _state = .connecting
        _lastProblem = nil
        lock.unlock()
        let problem = startChange()
        lock.lock()
        if let problem {
            let (tun, wasOn) = takeDownLocked()
            _lastProblem = problem
            lock.unlock()
            // Emitted outside `lock`: these go through the bridge into Kotlin (AppLog) — nothing calls
            // back into the tunnel today, and the lock is kept free of bridge calls on principle. The
            // event names what the user sees: no link to ride is its own text (Android's ev_no_network),
            // a foreign tunnel's takeover reads like the takeover event, and ev_no_permission stays the
            // designated stand-in for the tunnel-build refusals Android emits it for.
            logd("tunnel", "start failed: \(problem)")
            let key = problem.hasPrefix("no network") ? "ev_no_network"
                : problem.hasPrefix("another tunnel") ? "ev_revoked" : "ev_no_permission"
            EngineBridge.event(key, kind: "ERROR")
            _ = finishStop(tun, wasOn: wasOn)
            changesMutex.unlock()
            return problem
        }
        _state = .connected
        startWatch()
        lock.unlock()
        // Inside [changesMutex]: a stop's turnOff can't slip in between the tunnel's setup and the engine's
        // start (that left the engine running on a torn-down tunnel — a stray helper proxy). `wanted` is
        // re-checked: a disconnect during the slow part already queued its stop behind this one, so the
        // engine isn't lit just to be put out.
        if Self.wanted { EngineBridge.turnOn(serverId: serverId) }
        changesMutex.unlock()
        return nil
    }

    /// Read-only look at what the tunnel is doing (sockets of the core, our interface, the routes).
    func diagnose() -> [String] {
        lock.lock()
        let name = utun?.name, physical = self.physical
        lock.unlock()
        coreLock.lock()
        let pid = core?.pid
        coreLock.unlock()
        var out: [String] = ["state \(state), utun \(name ?? "none"), core pid \(pid.map(String.init) ?? "none"), out by \(physical ?? "none")"]
        if let pid {
            out += NetworkHost.run("/usr/sbin/lsof", ["-nP", "-a", "-p", "\(pid)", "-i"]).output.split(separator: "\n").map { "lsof: \($0)" }
        }
        if let name {
            out += NetworkHost.run("/sbin/ifconfig", [name]).output.split(separator: "\n").map { "ifconfig: \($0)" }
        }
        out += NetworkHost.run("/usr/sbin/netstat", ["-rn"]).output.split(separator: "\n").prefix(40).map { "route: \($0)" }
        return out
    }

    /// Every 5 s while on: another tunnel appeared → step aside (stop, restore); the network underneath changed →
    /// start again on the new one (the core is bound to the old interface).
    private func startWatch() {
        let timer = DispatchSource.makeTimerSource(queue: .global())
        timer.schedule(deadline: .now() + 5, repeating: 5)
        timer.setEventHandler { [weak self] in self?.check() }
        timer.resume()
        watch = timer
    }

    /// The store's own values at the last check ([NetworkHost.glance], guarded by [lock]): while they stand still
    /// the route table is not read — a `netstat` fork every 5 s for an answer that can't have changed.
    private var lastGlance = ""
    /// A check's restart waits at least this long after the last one: a flapping interface would otherwise run
    /// the ~10 s stop+start (reset script included) in an endless loop (guarded by [lock]).
    private static let autoRestartGap: TimeInterval = 60
    private var nextAutoRestartAt = Date.distantPast

    private func check() {
        lock.lock()
        guard _state == .connected, let ours = utun?.name else {
            lock.unlock()
            return
        }
        // The route table is read only when the dynamic store's own values moved (the primary service, DNS, a
        // tunnel's or a physical interface's addresses); a stop+start in between makes the read stale, so the
        // decision is taken under [lock] again against the state as it is now.
        let glance = NetworkHost.glance()
        if glance == lastGlance {
            lock.unlock()
            return
        }
        lock.unlock()
        let snap = NetworkHost.snapshot()
        lock.lock()
        guard _state == .connected, utun?.name == ours else {
            lock.unlock()
            return
        }
        if let other = snap.otherTunnel(ours: ours) {
            lastGlance = glance
            lock.unlock()
            // The user chose the other tunnel: don't come back by ourselves. The wish is cleared before the
            // stop, not after — a crash in the middle of it must not keep it.
            Self.wanted = false
            logd("tunnel", "another tunnel (\(other)) appeared: stepping aside")
            EngineBridge.event("ev_revoked", kind: "WARN")
            _ = stop()
            setProblem("another tunnel started (\(other)); the connection stepped aside")
            return
        }
        let now = snap.physicalInterface(ours: ours)
        if let now, now != physical, Interface.isPhysical(now) {
            let wait = nextAutoRestartAt.timeIntervalSinceNow
            guard wait <= 0 else {
                // Postponed, not skipped: lastGlance keeps its old value so every tick looks again until the
                // gap has passed (each look costs one snapshot — only inside the gap).
                lock.unlock()
                logd("tunnel", "network changed (\(physical ?? "none") → \(now)), the last restart too recent: waiting \(Int(wait)) s")
                return
            }
            lastGlance = glance
            nextAutoRestartAt = Date().addingTimeInterval(Self.autoRestartGap)
            lock.unlock()
            logd("tunnel", "network changed (\(physical ?? "none") → \(now)): starting again")
            _ = stop()
            // A disconnect may have landed during the stop's seconds: its wish cleared, we don't come back.
            guard Self.wanted else { return }
            _ = start()
            return
        }
        lastGlance = glance
        lock.unlock()
    }

    func stop() -> [String] {
        // The engine first — inside [changesMutex], so it serializes with a start's turnOn (an engine off/on
        // never lands between the other's half-done change). turnOff stops the core (through [stopCore]) and
        // records the end.
        changesMutex.lock()
        EngineBridge.turnOff()
        // The fields change under [lock] (microseconds — a `hello` read never waits); the core's stop, the utun's
        // close and the restore are seconds and an outside script, so they run without it, under [changesMutex].
        lock.lock()
        let (tun, wasOn) = takeDownLocked()
        lock.unlock()
        let report = finishStop(tun, wasOn: wasOn)
        changesMutex.unlock()
        return report
    }

    /// The slow half of a start, under [changesMutex] and never under [lock] (the snapshot alone forks `netstat`,
    /// the routes and the primary service are system tools — seconds). The fields it changes go under [lock] so a
    /// `hello`, [network] or a stop's [takeDownLocked] always sees them whole.
    private func startChange() -> String? {
        let before = NetworkHost.snapshot()
        if let other = before.otherTunnel(ours: nil) { return "another tunnel carries the traffic (\(other))" }
        guard let physical = before.primaryInterface, Interface.isPhysical(physical) else {
            return "no network to go out by"
        }
        guard CoreAccount.lookup() != nil else { return "the core's account is missing (reinstall the service)" }
        guard CoreProcess.installedPath() != nil else { return "the core is missing (reinstall the service)" }
        // Servers' names are looked up first at the network's own resolver (as Android asks the phone's), taken before
        // anything of ours is in place; a public one if the network has none. Only a literal IPv4 address goes in.
        let resolver = Self.ipv4(before.dnsServers) ?? "1.1.1.1"
        let kind = Interface.kind(physical)
        lock.lock()
        self.physical = physical
        self.resolver = resolver
        netKind = kind
        lock.unlock()
        do {
            try NetworkHost.beginChanges()
            let tun = try Utun.create()
            // From here the caller's [takeDownLocked] undoes whatever follows (the utun's close included), and
            // a mid-start `hello` can already tell our utun from "another tunnel".
            lock.lock()
            utun = tun
            lock.unlock()
            try UndoLog.append(.utun(tun.name))
            try tun.configure(local4: Self.local4, peer4: Self.peer4, local6: Self.local6, mtu: EngineBridge.mtu())
            for (family, dest) in NetworkPlan.routes {
                try NetworkHost.addRoute(family, dest, via: tun.name)
            }
            try NetworkHost.setPrimary(utun: tun.name, local: Self.local4, peer: Self.peer4, dns: [Self.peer4])
            logd("tunnel", "tunnel up: \(tun.name), out by \(physical)")
            EngineBridge.noteNetwork("\(physical) \(resolver) \(kind)")
            return nil
        } catch {
            return "\(error)"
        }
    }

    static func ipv4(_ servers: [String]) -> String? {
        servers.first { addr in
            var a = in_addr()
            return inet_pton(AF_INET, addr, &a) == 1 && addr != peer4
        }
    }

    /// The fast half of a stop, under [lock] only: the fields come back to off and the utun is handed out (closed
    /// by the caller, so [utunFd] already answers 0 while the descriptor still closes).
    private func takeDownLocked() -> (utun: Utun?, wasOn: Bool) {
        watch?.cancel()
        watch = nil
        lastGlance = ""
        // A deliberate off/on is not a flap: the auto-restart gap must not carry over it (a manual
        // disconnect + connect would otherwise wait out the last check's minute for no reason).
        nextAutoRestartAt = .distantPast
        let wasOn = _state != .off || utun != nil
        let tun = utun
        utun = nil
        _state = .off
        return (tun, wasOn)
    }

    /// The slow half of a stop, called under [changesMutex] and never under [lock]: the core's stop (SIGTERM, then
    /// up to 3 s before SIGKILL), the utun's close and the network's restore (the reset script and a snapshot).
    private func finishStop(_ tun: Utun?, wasOn: Bool) -> [String] {
        stopCore()
        tun?.close()
        guard wasOn || !UndoLog.records().isEmpty else { return ["nothing to stop"] }
        let report = NetworkHost.restore()
        report.forEach { log("restore: \($0)") }
        return report
    }

    // MARK: the core, for the engine

    /// Starts the core with [config] on our utun (a running one stops first); the failure, or nil.
    func startCore(config: String, fd: Int32) -> String? {
        coreLock.lock()
        defer { coreLock.unlock() }
        core?.stop()
        core = nil
        guard fd > 0, fd == utunFd else { return "no tunnel" }
        guard let account = CoreAccount.lookup(), let path = CoreProcess.installedPath() else { return "the core is missing" }
        do {
            let process = try CoreProcess.start(path: path, account: account, utunFd: fd, config: config) { reason in
                // The engine notices (its checks fail) and starts it again.
                logd("xray", "ended: \(reason)")
            }
            do {
                try process.waitReady(seconds: 15)
            } catch {
                // A core that never reached READY still runs and still holds the dup'd utun fd 3 (and our ends
                // of its pipes): end it, else every engine retry leaks the process and the descriptors.
                process.stop()
                throw error
            }
            core = process
            return nil
        } catch {
            return "\(error)"
        }
    }

    func stopCore() {
        coreLock.lock()
        let c = core
        core = nil
        coreLock.unlock()
        c?.stop()
    }

    var coreRunning: Bool {
        coreLock.lock()
        defer { coreLock.unlock() }
        return core.map { !$0.exited } ?? false
    }

    /// The main core's pid while it runs (the energy probe reads its `proc_pid_rusage`; nil while down).
    var corePid: pid_t? {
        coreLock.lock()
        defer { coreLock.unlock() }
        return core.flatMap { $0.exited ? nil : $0.pid }
    }

    func coreCommand(_ line: String) -> String {
        coreLock.lock()
        let c = core
        coreLock.unlock()
        return c?.command(line) ?? ""
    }

    /// "<interface> <resolver> <kind>" of the network the core's connections leave by: the one taken at start while
    /// the tunnel is up (the system's resolver points into it then), else the system's primary one.
    func network() -> String {
        lock.lock()
        let up = utun != nil, iface = physical, dns = resolver, kind = netKind
        lock.unlock()
        if up, let iface, let dns {
            // The interface is the one the core is bound to — a live move ends in [check]'s rebuild —
            // but the resolver moves on the same interface too: another Wi-Fi network keeps en0 and
            // replaces the DHCP DNS. Report the live one ([serviceDns]; the global DNS is ours while
            // the tunnel holds it) so the engine's tick sees the change (NetCheck forget, a fresh
            // bootstrap resolver) instead of comparing a value that never moved.
            return "\(iface) \(TunnelController.ipv4(NetworkHost.serviceDns(matching: iface)) ?? dns) \(kind)"
        }
        // Off: the network the system uses now (under another tunnel too), looked at no more than every 5 s.
        lock.lock()
        if Date().timeIntervalSince(offNetworkAt) < 5 {
            lock.unlock()
            return offNetwork
        }
        lock.unlock()
        // The snapshot forks `netstat` — seconds it must not hold [lock] for (a `hello` shares it), like
        // [check]'s. The cache is filled under [lock] again, and a fresher answer that landed meanwhile wins.
        let snap = NetworkHost.snapshot()
        lock.lock()
        defer { lock.unlock() }
        if Date().timeIntervalSince(offNetworkAt) < 5 { return offNetwork }
        let iface2 = snap.physicalInterface(ours: nil)
        offNetwork = iface2.flatMap {
            Interface.isPhysical($0) ? "\($0) \(Self.ipv4(snap.dnsServers) ?? "1.1.1.1") \(Interface.kind($0))" : nil
        } ?? ""
        offNetworkAt = Date()
        return offNetwork
    }
    private var offNetwork = ""
    private var offNetworkAt = Date.distantPast
}

enum Interface {
    /// Name prefixes no real (bindable) interface carries: the tunnels (utun, tap, tun, the gif/stf transition
    /// ones), tunnel and device-to-device shims (ipsec, ppp, anpi, awdl, llw, ap), virtual NICs (bridge, vmnet,
    /// vboxnet) and the loopback. `^[a-z]+[0-9]+$` keeps names without the en-style shape out too.
    private static let virtual = ["lo", "utun", "ipsec", "ppp", "anpi", "awdl", "llw", "ap",
                                  "gif", "stf", "bridge", "vmnet", "vboxnet", "tap", "tun"]

    /// A real network interface the core may bind to: letters then digits, never a virtual one.
    static func isPhysical(_ name: String) -> Bool {
        name.range(of: "^[a-z]+[0-9]+$", options: .regularExpression) != nil
            && !virtual.contains(where: { name.hasPrefix($0) })
    }

    /// The stats' kind of an interface, in Android's words ("wifi" / "cell" / "ethernet" / "other"), from its
    /// SystemConfiguration type — asked rarely (at a start, or at most once per 5 s while off).
    static func kind(_ name: String) -> String {
        guard let all = SCNetworkInterfaceCopyAll() as? [SCNetworkInterface] else { return "other" }
        for interface in all where (SCNetworkInterfaceGetBSDName(interface) as? String) == name {
            switch SCNetworkInterfaceGetInterfaceType(interface) as? String {
            case "IEEE80211": return "wifi"
            case "Ethernet": return "ethernet"
            case "WWAN": return "cell"
            default: return "other"
            }
        }
        return "other"
    }
}

/// A utun interface, created through the kernel control socket. It exists exactly as long as [fd] is open (here and in
/// the core, which gets a duplicate), and the kernel removes its addresses and every route through it with it.
final class Utun {
    let fd: Int32
    let name: String

    private init(fd: Int32, name: String) {
        self.fd = fd
        self.name = name
    }

    /// `_IOWR('N', 3, struct ctl_info)` — not imported into Swift.
    private static let CTLIOCGINFO: UInt = 0xC064_4E03

    static func create() throws -> Utun {
        let fd = socket(PF_SYSTEM, SOCK_DGRAM, SYSPROTO_CONTROL)
        guard fd >= 0 else { throw TunnelError("utun socket: errno \(errno)") }
        var info = ctl_info()
        withUnsafeMutableBytes(of: &info.ctl_name) { raw in
            let name = Array("com.apple.net.utun_control".utf8)
            raw.copyBytes(from: name)
        }
        guard withUnsafeMutablePointer(to: &info, { ioctl(fd, CTLIOCGINFO, $0) }) == 0 else {
            Darwin.close(fd)
            throw TunnelError("utun control: errno \(errno)")
        }
        var addr = sockaddr_ctl()
        addr.sc_len = UInt8(MemoryLayout<sockaddr_ctl>.size)
        addr.sc_family = UInt8(AF_SYSTEM)
        addr.ss_sysaddr = UInt16(AF_SYS_CONTROL)
        addr.sc_id = info.ctl_id
        addr.sc_unit = 0 // the kernel picks the next free utunN
        let connected = withUnsafePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                connect(fd, $0, socklen_t(MemoryLayout<sockaddr_ctl>.size))
            }
        }
        guard connected == 0 else {
            Darwin.close(fd)
            throw TunnelError("utun connect: errno \(errno)")
        }
        _ = fcntl(fd, F_SETFD, FD_CLOEXEC)
        var buf = [CChar](repeating: 0, count: 32)
        var len = socklen_t(buf.count)
        guard getsockopt(fd, SYSPROTO_CONTROL, UTUN_OPT_IFNAME, &buf, &len) == 0 else {
            Darwin.close(fd)
            throw TunnelError("utun name: errno \(errno)")
        }
        let name = String(decoding: buf.prefix { $0 != 0 }.map { UInt8(bitPattern: $0) }, as: UTF8.self)
        guard name.hasPrefix("utun") else {
            Darwin.close(fd)
            throw TunnelError("utun name: \(name)")
        }
        return Utun(fd: fd, name: name)
    }

    func configure(local4: String, peer4: String, local6: String, mtu: Int) throws {
        let v4 = NetworkHost.run("/sbin/ifconfig", [name, "inet", local4, peer4, "netmask", "255.255.255.255",
                                                    "mtu", "\(mtu)", "up"])
        guard v4.status == 0 else { throw TunnelError("ifconfig inet: \(v4.output)") }
        let v6 = NetworkHost.run("/sbin/ifconfig", [name, "inet6", local6, "prefixlen", "126"])
        guard v6.status == 0 else { throw TunnelError("ifconfig inet6: \(v6.output)") }
    }

    func close() {
        Darwin.close(fd)
    }
}

/// The unprivileged account the core runs as (created by install-service.sh, removed by uninstall-service.sh).
struct CoreAccount {
    static let name = "_borderless"
    let uid: uid_t
    let gid: gid_t

    static func lookup() -> CoreAccount? {
        guard let pw = getpwnam(name) else { return nil }
        let uid = pw.pointee.pw_uid, gid = pw.pointee.pw_gid
        // Never root, never a shared account.
        guard uid > 0, gid > 0, uid != UInt32(bitPattern: -2) else { return nil }
        return CoreAccount(uid: uid, gid: gid)
    }
}

/// The core process: `sandbox-exec` with our profile → `borderless-core`, which drops to [CoreAccount] before it reads
/// its config. Inherits only: fd 0 = config and commands, 1 / 2 = its output (log lines, READY, answers), 3 = the utun,
/// 4 = the lifeline (its end-of-file means this service is gone, so the core can't outlive it and keep the utun alive).
/// After READY it takes commands from the engine ([command]: a ping through it, its traffic counters).
final class CoreProcess: @unchecked Sendable {
    let pid: pid_t
    /// The pipe ends we hold open, guarded by [fdLock]: a write and a close of one fd never race (a write to a
    /// number the kernel already recycled writes into whatever took it), and each closes exactly once. -1 = closed.
    private let fdLock = NSLock()
    private var lifeline: Int32
    private var commands: Int32
    private let ready = DispatchSemaphore(value: 0)
    /// The child's life: [stopping] set by [stop] (the reaper then stays quiet), [_exited] by the reaper when
    /// waitpid returns (the pid is free to be recycled then). Both under [stateLock]; read from anywhere.
    private let stateLock = NSLock()
    private var stopping = false
    private var _exited = false
    /// The child was reaped (its pid may already serve another process); read from anywhere.
    var exited: Bool {
        stateLock.lock(); defer { stateLock.unlock() }
        return _exited
    }
    private let lock = NSLock()
    private var nextId = 0
    private var answers: [Int: (DispatchSemaphore, Box)] = [:]

    private final class Box { var text = "" }

    /// The sandbox: nothing but its own binary, its lists, sockets and the system libraries; no writes, no other
    /// programs. `system.sb` is Apple's base profile for what every process needs to load. No `system-socket`:
    /// the utun arrives pre-opened as fd 3, so no PF_SYSTEM/PF_ROUTE socket is ever made (interface binding is a
    /// setsockopt on an already-open socket). Every listener is localhost, as `network-inbound` already is.
    static let profile = """
        (version 1)
        (deny default)
        (import "system.sb")
        (allow process-exec (literal (param "CORE")))
        (allow file-read* (literal (param "CORE")))
        (allow file-read* (subpath (param "ASSETS")))
        (allow file-read-metadata)
        (allow network-outbound)
        (allow network-inbound (local ip "localhost:*"))
        (allow network-bind (local ip "localhost:*"))
        (allow sysctl-read)
        """

    /// The installed core (root-owned, path written by the install script); nil if missing or not safe to run as root.
    static func installedPath() -> String? {
        guard let raw = try? String(contentsOfFile: ServicePaths.dataDir + "/core", encoding: .utf8) else { return nil }
        let path = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard path.hasPrefix(ServicePaths.corePrefix), ScriptCheck.problem(path) == nil else { return nil }
        return path
    }

    /// `sandbox-exec` with the profile, then the core: the start of every core's argv (main or helper).
    static func sandboxed(path: String) -> [String] {
        let assets = ServicePaths.listsDir
        try? FileManager.default.createDirectory(atPath: assets, withIntermediateDirectories: true,
                                                 attributes: [.posixPermissions: 0o755])
        return ["/usr/bin/sandbox-exec", "-D", "CORE=\(path)", "-D", "ASSETS=\(assets)", "-p", profile, path]
    }

    /// Spawns [argv] with only [fds] (parent's fd → child's number) crossing over. Every setup call's result is
    /// checked (they return errno values, not -1 + errno).
    static func spawn(argv: [String], env: [String], fds: [(Int32, Int32)]) -> Result<pid_t, TunnelError> {
        var actions: posix_spawn_file_actions_t?
        var rc = posix_spawn_file_actions_init(&actions)
        guard rc == 0 else { return .failure(TunnelError("spawn actions: \(rc)")) }
        defer { posix_spawn_file_actions_destroy(&actions) }
        for (from, to) in fds {
            rc = posix_spawn_file_actions_adddup2(&actions, from, to)
            guard rc == 0 else { return .failure(TunnelError("spawn dup2 \(from) → \(to): \(rc)")) }
        }
        var attrs: posix_spawnattr_t?
        rc = posix_spawnattr_init(&attrs)
        guard rc == 0 else { return .failure(TunnelError("spawn attrs: \(rc)")) }
        defer { posix_spawnattr_destroy(&attrs) }
        rc = posix_spawnattr_setflags(&attrs, Int16(POSIX_SPAWN_CLOEXEC_DEFAULT))
        guard rc == 0 else { return .failure(TunnelError("spawn attr flags: \(rc)")) }
        var pid: pid_t = 0
        rc = withCStrings(argv) { a in withCStrings(env) { e in posix_spawn(&pid, argv[0], &actions, &attrs, a, e) } }
        return rc == 0 ? .success(pid) : .failure(TunnelError("spawn: \(rc)"))
    }

    /// A buffered line never grows past this: a child printing one endless line would grow the service's memory
    /// forever — the rest of such a line is dropped instead (logged once per reader).
    private static let maxLine = 256 << 10

    /// Xray prefixes lines with its own "2026/10/06 00:50:11.064450 "; the logs stamp them themselves
    /// (Android's `stripXrayTimestamp` — its [xray] lines look the same).
    private static let xrayStamp = try! NSRegularExpression(
        pattern: #"^\d{4}/\d{2}/\d{2} \d{2}:\d{2}:\d{2}(\.\d+)? "#)

    /// [line] without Xray's own timestamp prefix, for the detailed log ([logd] "xray").
    static func stripXrayTimestamp(_ line: String) -> String {
        let range = NSRange(line.startIndex..., in: line)
        guard let m = xrayStamp.firstMatch(in: line, range: range),
              let r = Range(m.range, in: line) else { return line }
        return String(line[r.upperBound...])
    }

    /// Calls [handle] with each line read from [fd] until it ends (then closes it). POSIX `read`, not
    /// `FileHandle.availableData`: a read error there is an uncatchable NSFileHandleOperationException —
    /// thrown on a reader thread it would still take the whole service down; here it ends the stream.
    static func readLines(_ fd: Int32, _ handle: (String) -> Void) {
        defer { close(fd) }
        var buffer = Data()
        var chunk = [UInt8](repeating: 0, count: 65536)
        var dropping = false, logged = false
        while true {
            let n = read(fd, &chunk, chunk.count)
            if n < 0, errno == EINTR { continue }
            if n <= 0 { break }
            buffer.append(contentsOf: chunk[0..<n])
            for line in Wire.lines(&buffer) {
                // The first newline after a drop still ends the same overlong line, not a new one.
                if dropping { dropping = false } else { handle(String(decoding: line, as: UTF8.self)) }
            }
            if buffer.count > maxLine {
                buffer.removeAll(keepingCapacity: true)
                dropping = true
                if !logged { logd("xray", "a child wrote a line over \(maxLine) bytes: dropping it"); logged = true }
            }
        }
    }

    private init(pid: pid_t, lifeline: Int32, commands: Int32) {
        self.pid = pid
        self.lifeline = lifeline
        self.commands = commands
    }

    /// pipe() into [pair]; on failure the fds already in [opened] are closed, so a partial start leaks nothing.
    private static func openPipe(_ pair: inout [Int32], closing opened: [Int32]) -> Bool {
        if pipe(&pair) == 0 { return true }
        opened.forEach { close($0) }
        return false
    }

    /// All of [data] to [fd] within [seconds] — [Wire.send], but a child that never reads its stdin can't hold
    /// a caller's lock in a blocking write forever (the config once could wedge [coreLock] that way).
    private static func send(_ data: Data, to fd: Int32, seconds: Int) -> Bool {
        let flags = fcntl(fd, F_GETFL)
        // If it can't be made non-blocking the write loop below could block forever — fail instead.
        guard flags >= 0, fcntl(fd, F_SETFL, flags | O_NONBLOCK) == 0 else { return false }
        defer { _ = fcntl(fd, F_SETFL, flags) }
        let deadline = Date().addingTimeInterval(TimeInterval(seconds))
        return data.withUnsafeBytes { raw -> Bool in
            guard var p = raw.baseAddress else { return true }
            var left = raw.count
            while left > 0 {
                let n = write(fd, p, left)
                if n > 0 { p += n; left -= n; continue }
                if n < 0, errno == EINTR { continue }
                if n < 0, errno == EAGAIN, Date() < deadline {
                    var pfd = pollfd(fd: fd, events: Int16(POLLOUT), revents: 0)
                    _ = poll(&pfd, 1, 500)
                    continue
                }
                return false
            }
            return true
        }
    }

    static func start(path: String, account: CoreAccount, utunFd: Int32, config: String,
                      onExit: @escaping @Sendable (String) -> Void) throws -> CoreProcess {
        var cfg: [Int32] = [0, 0], out: [Int32] = [0, 0], life: [Int32] = [0, 0]
        guard openPipe(&cfg, closing: []),
              openPipe(&out, closing: [cfg[0], cfg[1]]),
              openPipe(&life, closing: [cfg[0], cfg[1], out[0], out[1]]) else {
            throw TunnelError("pipes: errno \(errno)")
        }
        for fd in [cfg[1], out[0], life[1]] { _ = fcntl(fd, F_SETFD, FD_CLOEXEC) }
        let argv = sandboxed(path: path) + ["-uid", "\(account.uid)", "-gid", "\(account.gid)", "-lifeline", "4"]
        let env = ["XRAY_TUN_FD=3", "XRAY_LOCATION_ASSET=\(ServicePaths.listsDir)", "PATH=/usr/bin:/bin"]
        let spawned = spawn(argv: argv, env: env, fds: [(cfg[0], 0), (out[1], 1), (out[1], 2), (utunFd, 3), (life[0], 4)])
        close(cfg[0]); close(out[1]); close(life[0])
        guard case .success(let pid) = spawned else {
            close(cfg[1]); close(out[0]); close(life[1])
            if case .failure(let e) = spawned { throw e }
            throw TunnelError("spawn core")
        }
        let process = CoreProcess(pid: pid, lifeline: life[1], commands: cfg[1])
        let outRead = out[0], child = pid

        // Its output: "READY" once, answers to commands ("CTL <id> …"), Xray's log lines (warnings and up).
        Thread.detachNewThread {
            readLines(outRead) { text in
                if text == "READY" {
                    process.ready.signal()
                } else if text.hasPrefix("CTL ") {
                    process.answered(text.dropFirst(4))
                } else {
                    logd("xray", Self.stripXrayTimestamp(text))
                }
            }
        }
        Thread.detachNewThread {
            var status: Int32 = 0
            while waitpid(child, &status, 0) < 0 && errno == EINTR {}
            process.stateLock.lock()
            process._exited = true
            let quiet = process.stopping
            process.stateLock.unlock()
            process.ready.signal()
            process.failWaiting()
            if !quiet { onExit("exit status \(status)") }
        }

        // The config: its length on a line, then the bytes; stdin stays open for commands.
        var framed = Data("\(config.utf8.count)\n".utf8)
        framed.append(Data(config.utf8))
        guard Self.send(framed, to: cfg[1], seconds: 10) else {
            process.stop()
            throw TunnelError("the core didn't take its config")
        }
        return process
    }

    func waitReady(seconds: Int) throws {
        if ready.wait(timeout: .now() + .seconds(seconds)) == .timedOut { throw TunnelError("the core didn't start") }
        if exited { throw TunnelError("the core ended at start") }
    }

    /// A command ("DELAY <url>", "STATS"), its answer; "" when the core is gone or didn't answer in 20 s.
    func command(_ line: String) -> String {
        let done = DispatchSemaphore(value: 0), box = Box()
        lock.lock()
        nextId += 1
        let id = nextId
        answers[id] = (done, box)
        lock.unlock()
        // Under [fdLock]: never a write to a number [stop] already closed and the kernel may have recycled;
        // bounded, so a core that stopped reading can't hold the lock (and [stop] behind it) forever.
        fdLock.lock()
        let sent = commands >= 0 && !exited && Self.send(Data("\(id) \(line)\n".utf8), to: commands, seconds: 10)
        fdLock.unlock()
        guard sent else {
            lock.lock(); answers[id] = nil; lock.unlock()
            return ""
        }
        if done.wait(timeout: .now() + 20) == .timedOut {
            lock.lock(); answers[id] = nil; lock.unlock()
            return ""
        }
        return box.text
    }

    private func answered(_ rest: Substring) {
        let parts = rest.split(separator: " ", maxSplits: 1, omittingEmptySubsequences: false)
        guard let id = Int(parts.first ?? "") else { return }
        lock.lock()
        let waiter = answers.removeValue(forKey: id)
        lock.unlock()
        waiter?.1.text = parts.count > 1 ? String(parts[1]) : ""
        waiter?.0.signal()
    }

    private func failWaiting() {
        lock.lock()
        let left = answers
        answers.removeAll()
        lock.unlock()
        left.values.forEach { $0.0.signal() }
    }

    /// Lifeline closed (the core stops by itself), then SIGTERM, then SIGKILL after 3 s. Safe to call again:
    /// the second call returns at once — the fds are already -1, and a double close of a recycled number is
    /// the bug shape this avoids.
    func stop() {
        stateLock.lock()
        if stopping {
            stateLock.unlock()
            return
        }
        stopping = true
        stateLock.unlock()
        fdLock.lock()
        if lifeline >= 0 { close(lifeline); lifeline = -1 }
        if commands >= 0 { close(commands); commands = -1 }
        fdLock.unlock()
        signalUnlessReaped(SIGTERM)
        for _ in 0..<30 {
            if exited { return }
            usleep(100_000)
        }
        signalUnlessReaped(SIGKILL)
    }

    /// [sig] to the child only while it is certainly still ours, under [stateLock]: the reaper marks
    /// [_exited] under it, but its waitpid finishes before it can take the lock — a child already
    /// collected in that gap reads as live here while its pid may already serve another process. A
    /// WNOHANG waitpid closes the gap: == [pid] reaps it here (the reaper's own waitpid then answers
    /// ECHILD, which is fine), ECHILD means the reaper won, and EINTR is just retried. What is left is
    /// our child, running or a zombie — the signal lands on it or on nobody.
    private func signalUnlessReaped(_ sig: Int32) {
        stateLock.lock()
        if !_exited {
            var r = waitpid(pid, nil, WNOHANG)
            while r < 0 && errno == EINTR { r = waitpid(pid, nil, WNOHANG) }
            if r == pid || (r < 0 && errno == ECHILD) { _exited = true }
        }
        if !_exited { kill(pid, sig) }
        stateLock.unlock()
    }
}

struct TunnelError: Error, CustomStringConvertible {
    let description: String
    init(_ description: String) { self.description = description }
}

func withCStrings<R>(_ strings: [String], _ body: (UnsafePointer<UnsafeMutablePointer<CChar>?>) -> R) -> R {
    var pointers: [UnsafeMutablePointer<CChar>?] = strings.map { strdup($0) }
    pointers.append(nil)
    defer { pointers.forEach { free($0) } }
    return pointers.withUnsafeBufferPointer { body($0.baseAddress!) }
}
