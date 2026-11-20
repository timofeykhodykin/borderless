import Foundation
import Shared

// The Border(less) system service. launchd starts it as root (`/Library/LaunchDaemons/app.borderless.service.plist`,
// KeepAlive) and it stays up whether the app is open or not. It keeps the user's servers, runs the shared engine
// (choice of server, checks, failover, statistics — EngineBridge.swift) and the tunnel it uses (Tunnel.swift), and
// answers the app on its socket — see local/macos/PLAN.md §1.0.

setvbuf(stdout, nil, _IOLBF, 0)
signal(SIGPIPE, SIG_IGN)

// `--print-network`: what the service sees (read-only, works as any user) — for diagnosis.
if CommandLine.arguments.dropFirst().first == "--print-network" {
    let encoder = JSONEncoder()
    encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
    encoder.dateEncodingStrategy = .iso8601
    let snap = NetworkHost.snapshot()
    print(String(decoding: try encoder.encode(snap), as: UTF8.self))
    print("other tunnel: \(snap.otherTunnel(ours: nil) ?? "none")")
    exit(0)
}

// `--engine-check <core binary> <empty dir> [limited binary]`: the shared engine with the helper, as the current
// user, no tunnel — a local SOCKS server made by the helper and an address that answers nothing are checked by
// "Ping all" (development). With the third path the limited core runs too, unsandboxed.
if CommandLine.arguments.dropFirst().first == "--engine-check", CommandLine.arguments.count >= 4 {
    // A dev tool that execs an arbitrary binary unsandboxed — meant for the development user. As root it gains
    // nothing (root can exec anything anyway) and only makes the run more dangerous, so refuse.
    guard geteuid() != 0 else {
        FileHandle.standardError.write(
            Data("--engine-check is a development tool: run it as the development user, not as root\n".utf8))
        exit(1)
    }
    EngineCheck.run(core: CommandLine.arguments[2], dir: CommandLine.arguments[3],
                    limited: CommandLine.arguments.count > 4 ? CommandLine.arguments[4] : nil)
}

guard getuid() == 0 else {
    FileHandle.standardError.write(Data("app.borderless.service must run as root (it is started by launchd)\n".utf8))
    exit(1)
}

try? FileManager.default.createDirectory(
    atPath: ServicePaths.dataDir, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
// createDirectory won't fix an existing dir's mode: it holds the user's data — root-only either way.
chmod(ServicePaths.dataDir, 0o700)
// The clean-exit mark is read before this run's first log line lands: a missing mark after a run that
// opened the log means the last service died (crash, kill, power loss) — recorded once the engine is up.
let lastExit = ExitMark.take()
ServiceLog.open()

// `--reset-network`: the same restore the app asks for, from a root shell; prints what it did and exits.
if CommandLine.arguments.dropFirst().first == "--reset-network" {
    NetworkHost.restore().forEach { print($0) }
    ExitMark.mark()
    exit(0)
}

// One instance only: a second run by hand (sudo) would take the live instance's undo log for leftover damage,
// restore the network under its tunnel and then steal the socket path. A live instance answers a connect on
// the socket; a stale file from a dead one doesn't, and start() unlinks it anyway.
if let probe = connectToService(timeoutSec: 1) {
    close(probe)
    log("a live service already holds \(ServicePaths.socket); exiting")
    ExitMark.mark()
    exit(1)
}

// Changes left by a previous run that ended without undoing them (crash, kill, power loss): undo first, before
// anything else. A fresh start connects nothing by itself.
if !UndoLog.records().isEmpty {
    log("leftover network changes from the last run; restoring")
    NetworkHost.restore().forEach { log("restore: \($0)") }
}

// An update the user confirmed is put in place now, before anything of this build starts — the staged build
// was downloaded and signature-checked ahead; the swap runs only while the service isn't carrying the tunnel
// (ruling R2: apply at a service start). On success it never returns — the service hands over to the new
// build through the reload job (Updater.swift).
Updater.applyStaged()

let tunnel = TunnelController()
EngineBridge.setUp(tunnel: tunnel)
EngineBridge.watchSaver()
// The metered watch feeds the engine (Power.stretch spaces its checks, EngineStatus.metered hints it):
// NWPathMonitor on the physical path — the tunnel's own utun is left out — pushed on every flip.
NetworkHost.watchMetered { EngineBridge.meteredChanged($0) }

// The updater plugs into the engine once it exists (the events and the detailed log are its): the kept
// state is loaded, `ev_updated` / a kept apply failure are told, and the first due check is queued.
Updater.setUp(tunnel: tunnel)

// The previous run's end, recorded where the app reads it — after the engine is up (the events and the
// detailed log are its): a dead run gets `ev_crashed` and the details, and the panel keeps the problem
// until the next start clears it.
if lastExit == false {
    ExitMark.noteUnclean()
    tunnel.noteServiceProblem("the previous service run ended without a clean shutdown")
}

let server = ControlSocket(path: ServicePaths.socket) { request in
    func reply(report: [String]? = nil, error: String? = nil, servers: ServerList? = nil, settings: String? = nil,
               zones: String? = nil, events: [EventLine]? = nil, lines: [String]? = nil, stats: String? = nil,
               tables: String? = nil) -> Reply {
        let engine = try? JSONDecoder().decode(EngineStatus.self, from: Data(EngineBridge.status().utf8))
        let statsBytes = (try? FileManager.default.attributesOfItem(
            atPath: ServicePaths.dataDir + "/stats.db"))?[.size] as? Int64
        return Reply(version: borderlessVersion, tunnel: tunnel.state, network: NetworkHost.status(ours: tunnel.utunName),
                     report: report, error: error, problem: tunnel.lastProblem,
                     serverId: engine?.serverId ?? engine?.selectedId, servers: servers, engine: engine,
                     settings: settings, zones: zones, events: events, lines: lines, stats: stats, tables: tables,
                     statsBytes: statsBytes)
    }
    // Everything beyond the ambient hello/status poll means the app's screens are at work (the
    // menubar polls status forever): the energy meter's "the UI was quiet" reads this stamp.
    if request.kind != .hello && request.kind != .status { EnergyProbe.noteClientActivity() }
    let needsStorage: Set<Request.Kind> = [.connect, .saveServers, .saveSettings,
        .saveZone, .deleteZone, .setEco, .setCountries, .pingAll, .clearLog, .exportStats]
    if EngineBridge.storageLocked && (needsStorage.contains(request.kind) ||
        (request.kind == .serviceAction && request.target?.hasPrefix("update_sub") == true)) {
        return reply(error: "Stored data is locked because its encryption key or a data file could not be read. Your servers and settings are preserved. Restart the service after secure storage becomes available.")
    }
    switch request.kind {
    case .hello, .status:
        return reply()
    case .log:
        return reply(report: ServiceLog.tail(400))
    case .diagnose:
        return reply(report: tunnel.diagnose())
    case .connect:
        return reply(error: tunnel.connect(serverId: request.serverId))
    case .servers:
        return reply(servers: EngineBridge.servers())
    case .pingAll:
        EngineBridge.pingAll()
        // The scanner starts on the engine's threads; answering the same instant shows the app scanTotal 0.
        // Give the scan a moment to appear in the status — bounded, a missing start must not hold the request.
        for _ in 0..<10 {
            let engine = try? JSONDecoder().decode(EngineStatus.self, from: Data(EngineBridge.status().utf8))
            if let engine, engine.scanTotal > 0 || engine.scanDone > 0 { break }
            Thread.sleep(forTimeInterval: 0.05)
        }
        return reply()
    case .saveServers:
        guard let list = request.servers else { return reply(error: "no servers") }
        let error = EngineBridge.save(list)
        log(error.map { "servers not stored: \($0)" } ?? "servers stored: \(list.servers.count) in \(list.groups.count) group(s)")
        return reply(error: error)
    case .settings:
        return reply(settings: EngineBridge.settings())
    case .saveSettings:
        guard let json = request.json else { return reply(error: "no settings") }
        let error = EngineBridge.saveSettings(json)
        log(error.map { "settings not stored: \($0)" } ?? "settings changed")
        return reply(error: error)
    case .setEco:
        // A request without `on` is malformed: refuse it rather than toggling the saver off silently.
        guard let on = request.on else { return reply(error: "no eco state") }
        EngineBridge.setEco(on: on)
        return reply()
    case .setCountries:
        EngineBridge.setCountries(request.countries ?? [])
        return reply()
    case .zones:
        return reply(zones: EngineBridge.zones())
    case .saveZone:
        guard let json = request.json else { return reply(error: "no zone") }
        let error = EngineBridge.saveZone(json)
        log(error.map { "zone not stored: \($0)" } ?? "zone stored")
        return reply(error: error)
    case .deleteZone:
        EngineBridge.deleteZone(request.target ?? "")
        return reply()
    case .events:
        return reply(events: EngineBridge.events())
    case .debugLog:
        return reply(lines: EngineBridge.debugLog())
    case .errorReports:
        return reply(report: [EngineBridge.errorReports()])
    case .clearLog:
        EngineBridge.clearLog()
        log("log cleared by the app")
        return reply()
    case .stats:
        guard let from = request.fromMs, let to = request.toMs else { return reply(error: "no period") }
        return reply(stats: EngineBridge.stats(from: from, to: to, target: request.target))
    case .serviceAction:
        let error = EngineBridge.action(request.target ?? "")
        log("action \(request.target ?? "?")\(error.map { ": \($0)" } ?? "")")
        return reply(error: error)
    case .exportStats:
        return reply(tables: EngineBridge.exportStats())
    case .disconnect, .restoreNetwork:
        let report = tunnel.disconnect()
        return reply(report: report)
    }
}

do {
    try server.start()
} catch {
    FileHandle.standardError.write(Data("control socket: \(error)\n".utf8))
    ExitMark.mark()
    exit(1)
}

// Connected before this start (the service was updated, restarted or the Mac rebooted): connect again — by
// itself, as Android's AutoStart does after a reboot or an update, and logged as such. The wish may be gone
// by the time this runs (the app's disconnect during the second), so it is checked once more.
if TunnelController.wanted {
    EngineBridge.event("ev_autostart")
    log("reconnect at start (\(ProcessInfo.processInfo.systemUptime < 15 * 60 ? "after a reboot" : "a service restart"))")
    DispatchQueue.global().asyncAfter(deadline: .now() + 1) {
        guard TunnelController.wanted else { return }
        if let problem = tunnel.start() { logd("tunnel", "reconnect at start failed: \(problem)") }
        else { logd("tunnel", "reconnected at start") }
    }
}

for sig in [SIGTERM, SIGINT] {
    signal(sig, SIG_IGN)
    let source = DispatchSource.makeSignalSource(signal: sig, queue: .main)
    source.setEventHandler {
        server.stop()
        _ = tunnel.stop()
        EngineBridge.stopHelper()
        // The mark is written only after the cleanup it vouches for: a death mid-way stays "unclean".
        ExitMark.mark()
        exit(0)
    }
    source.resume()
    signalSources.append(source)
}

dispatchMain()
