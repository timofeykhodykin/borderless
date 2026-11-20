import BorderlessShared
import CoreGraphics
import Foundation
import IOKit.ps
import Shared

/// The shared engine inside the service (`MacEngine.kt`): the only file of the service that sees the Kotlin module
/// (its names would clash with ours elsewhere). It gives the engine the service's processes ([Processes]) and turns
/// its state and the app's lists into the protocol's types.
enum EngineBridge {
    /// Where Repo, the statistics and the logs live; the bundled files the shared code reads (zones, country names),
    /// copied there by the install script.
    static let repoDir = ServicePaths.dataDir + "/repo"
    static let assetsDir = ServicePaths.dataDir + "/assets"

    nonisolated(unsafe) private static var tunnel: TunnelController!
    private static let helper = HelperProcess()
    private static let limited = LimitedProcess()

    /// Once, as the service starts (before the control socket answers). [dataDir] / [listsDir] / [assets]: elsewhere
    /// only for `--engine-check`.
    static func setUp(tunnel: TunnelController, dataDir: String = ServicePaths.dataDir, listsDir: String = ServicePaths.listsDir,
                      assets: String = assetsDir) {
        self.tunnel = tunnel
        MacBridge.shared.setUp(assets: assets, language: { "en" }, text: nil)
        MacEngine.shared.setUp(processes: Processes(), dataDir: dataDir, listsDir: listsDir, version: borderlessVersion)
        if dataDir == ServicePaths.dataDir { migrate() }
    }

    /// One request to the helper (for `--engine-check`).
    static func helperRequest(_ json: String) -> String { helper.request(json) }

    static func turnOn(serverId: String?) { MacEngine.shared.turnOn(serverId: serverId) }
    static func turnOff() { MacEngine.shared.turnOff() }
    static func pingAll() { MacEngine.shared.pingAll() }
    static func networkChanged() { MacEngine.shared.networkChanged() }
    static func saverChanged() { MacEngine.shared.saverChanged() }
    static func stopHelper() { helper.stop() }

    /// The primary path's meteredness from the network watcher (NWPathMonitor `isExpensive || isConstrained`,
    /// pushed whenever it flips): feeds the engine's `Power.stretch` (spaced-out probes/scans on expensive
    /// networks) and `EngineStatus.metered`, the way Android's TRANSPORT_CELLULAR reading does.
    static func meteredChanged(_ metered: Bool) { MacEngine.shared.meteredChanged(metered: metered) }

    /// A daemon-side event into the app log ([key] is an `ev_*` strings key the app renders, [args] its
    /// arguments, [kind] INFO/GOOD/WARN/ERROR) — for happenings the engine can't see: a tunnel start failure,
    /// a foreign tunnel taking over, an autostart, a crash notice found at boot.
    static func event(_ key: String, args: [String] = [], kind: String = "INFO") {
        MacEngine.shared.event(name: key, args: args, kind: kind)
    }

    /// A line into the detailed log ([tag] like "core"/"tunnel"/"helper"): the cores' own output lines and
    /// daemon decisions land where the app's detailed-log screen and the export read them.
    static func debug(_ tag: String, _ line: String) { MacEngine.shared.debug(tag: tag, text: line) }

    /// The MTU the utun is configured with (the engine's settings MTU, [AppSettings.mtu]; the default is 1500).
    static func mtu() -> Int { Int(MacEngine.shared.currentMtu()) }

    /// Low Power Mode is read on demand ([Processes.power]); nothing pushes it, so the service checks now and then
    /// whether it changed and lets the battery saving mode follow, as the phone's saver does on Android. The same
    /// tick keeps the helper's sockets on the current network: a Wi-Fi ↔ Ethernet switch or another network on the
    /// same interface would otherwise leave subscriptions and geo lists dialing the dead interface until restart
    /// (found 2026-11-02: `networkChanged` had no caller at all). It also notices the display's sleep/wake for the
    /// engine's Screen events (Android's ACTION_SCREEN_ON/OFF) — a wake means someone may be waiting for the internet.
    nonisolated(unsafe) private static var saverTimer: DispatchSourceTimer?

    /// The tick's remembered readings, written from the timer and the tunnel's threads ([noteNetwork]): the last
    /// usable network the service saw ("" until then), whether there is one now, whether any reading came in, and
    /// the display's last polled state.
    private static let stateLock = NSLock()
    nonisolated(unsafe) private static var lastNetwork = ""
    nonisolated(unsafe) private static var networkUp = false
    nonisolated(unsafe) private static var networkSeen = false
    nonisolated(unsafe) private static var lastScreenOn: Bool?

    static func watchSaver() {
        let timer = DispatchSource.makeTimerSource(queue: .global())
        timer.schedule(deadline: .now() + 30, repeating: 30)
        timer.setEventHandler { [tunnel = tunnel!] in
            MacEngine.shared.saverChanged()
            checkNetwork(tunnel.network())
            checkScreen()
        }
        timer.resume()
        saverTimer = timer
    }

    /// Tells the engine about a network change (the helper rebinds, direct checks are forgotten) when the network
    /// the service knows differs from the one it sees now. An empty reading is remembered too: connectivity coming
    /// back then is what Android's NET_CAPABILITY_VALIDATED tells the engine — the same network passing again
    /// (`Validated`), a different one coming up (a change).
    static func checkNetwork(_ now: String) {
        stateLock.lock()
        let previous = lastNetwork, wasUp = networkUp, seen = networkSeen
        networkSeen = true
        networkUp = !now.isEmpty
        if !now.isEmpty { lastNetwork = now }
        stateLock.unlock()
        if !wasUp {
            // The first reading is just a reading; afterwards, a usable network after having none is worth telling.
            guard seen, !now.isEmpty else { return }
            if now == previous { MacEngine.shared.networkValidated(yes: true) }
            else { MacEngine.shared.networkChanged() }
            return
        }
        // A down transition also tells the engine (Android's onLost → Event.NetworkChanged): with no usable
        // network the checks it runs pause instead of booking failures against the current server.
        guard !now.isEmpty else { MacEngine.shared.networkChanged(); return }
        guard now != previous else { return }
        MacEngine.shared.networkChanged()
    }

    /// The display's sleep/wake (polled — nothing pushes it to a root daemon): a transition is the engine's
    /// [Engine.Event.ScreenOn]/[Engine.Event.ScreenOff].
    private static func checkScreen() {
        let on = CGDisplayIsAsleep(CGMainDisplayID()) == 0
        stateLock.lock()
        let was = lastScreenOn
        lastScreenOn = on
        stateLock.unlock()
        if let was, was != on { MacEngine.shared.screenChanged(on: on) }
    }

    /// What the tunnel started with, so the tick doesn't re-announce it ([TunnelController.startChange]).
    static func noteNetwork(_ now: String) {
        stateLock.lock()
        lastNetwork = now
        networkUp = !now.isEmpty
        networkSeen = true
        stateLock.unlock()
    }

    /// The engine's state for the app (JSON, see `MacEngine.Status`).
    static func status() -> String { MacEngine.shared.status() }

    static func servers() -> ServerList {
        (try? JSONDecoder().decode(ServerList.self, from: Data(MacEngine.shared.serverList().utf8))) ?? ServerList()
    }

    /// Stores the app's edited list; the refusal, if any.
    static func save(_ list: ServerList) -> String? {
        if let problem = list.problem { return problem }
        guard let data = try? JSONEncoder().encode(list) else { return "can't read the list" }
        return MacEngine.shared.saveServerList(text: String(decoding: data, as: UTF8.self))
    }

    /// The service's settings as JSON, and the app's edited fields (the refusal, if any).
    static func settings() -> String { MacEngine.shared.settings() }
    static func saveSettings(_ json: String) -> String? { MacEngine.shared.saveSettings(patchText: json) }

    /// The main screen's switches (the battery saving mode and the countries go through the shared bookkeeping).
    static func setEco(on: Bool) { MacEngine.shared.setEco(on: on) }
    static func setCountries(_ codes: [String]) { MacEngine.shared.setCountries(codes: codes) }

    /// The user's zones: the list as JSON, storing one and deleting one.
    static func zones() -> String { MacEngine.shared.zones() }
    static func saveZone(_ json: String) -> String? { MacEngine.shared.saveZone(zoneJson: json) }
    static func deleteZone(_ id: String) { MacEngine.shared.deleteZone(id: id) }

    /// The log screen: the events (language-neutral), the detailed lines, clearing both.
    static func events() -> [EventLine]? {
        let decoder = JSONDecoder()
        return (try? decoder.decode([EventLine].self, from: Data(MacEngine.shared.events().utf8)))
    }
    static func debugLog() -> [String]? {
        (try? JSONDecoder().decode([String].self, from: Data(MacEngine.shared.debugLog().utf8)))
    }
    static func clearLog() { MacEngine.shared.clearLog() }

    /// One statistics query (Android's loaders, over the service's database).
    static func stats(from: Int64, to: Int64, target: String?) -> String { MacEngine.shared.stats(from: from, to: to, target: target) }

    /// "update_subs" / "update_sub:<id>" / "update_geo" / "clear_stats" / "error_dismiss:<id>" / "scan_open" /
    /// "probe_one:<id>" / "imported:<n>:<m>:<p>:<q>" / "update_check" / "update_install" / "update_dismiss";
    /// the refusal, if any.
    static func action(_ kind: String) -> String? { MacEngine.shared.action(kind: kind) }

    /// The service's updater plugs into the engine's hooks (Sources/Service/Updater.swift): its [check] serves
    /// the due-work and `update_check` ([force] on a manual ask, the due path keeps the day's pace), [install]
    /// and [dismiss] the other `update_*` actions, and [status] becomes `EngineStatus.update`.
    static func installUpdater(check: @escaping (Bool) -> Void, install: @escaping () -> Void,
                               dismiss: @escaping () -> Void, status: @escaping () -> String) {
        MacUpdater.shared.install(host: UpdaterHost(
            check: check, install: install, dismiss: dismiss, status: status))
    }

    /// The updater's "through a working server" route (Android `Updater.anyRoute`): a throwaway SOCKS proxy
    /// through the next untried candidate — "port user pass", "" when none is left; [reset] starts a fresh
    /// route. [updateProxyStop] ends whatever is running.
    static func updateProxyStart(reset: Bool) -> String { MacUpdater.shared.proxyStart(reset: reset) }
    static func updateProxyStop() { MacUpdater.shared.proxyStop() }

    /// The project's repository, "owner/name" — the updater's release checks live under it (kept out of the
    /// binaries as plain text, `ListManifest`).
    static func projectSlug() -> String { MacUpdater.shared.projectSlug() }

    /// The hooks the shared bridge asks for (`MacUpdaterHost`): plain closures, so the updater never sees
    /// the Kotlin module's names.
    private final class UpdaterHost: NSObject, MacUpdaterHost {
        let checkFn: (Bool) -> Void, installFn: () -> Void, dismissFn: () -> Void
        let statusFn: () -> String
        init(check: @escaping (Bool) -> Void, install: @escaping () -> Void,
             dismiss: @escaping () -> Void, status: @escaping () -> String) {
            checkFn = check; installFn = install; dismissFn = dismiss; statusFn = status
        }
        func check(force: Bool) { checkFn(force) }
        func install() { installFn() }
        func dismiss() { dismissFn() }
        func status() -> String { statusFn() }
    }

    /// Every statistics table as CSV, for the export.
    static var storageLocked: Bool { MacEngine.shared.storageLocked() }

    static func errorReports() -> String { MacEngine.shared.errorReports() }

    static func exportStats() -> String { MacEngine.shared.exportStats() }

    /// The list the service kept before Repo (`servers.json`, one file) goes into Repo once, through the same merge as
    /// the app's edits (servers are rebuilt from their links, which also fills what the old file didn't keep).
    private static func migrate() {
        let fm = FileManager.default
        guard fm.fileExists(atPath: ServerStore.path), !fm.fileExists(atPath: repoDir + "/servers.json") else { return }
        let list = ServerStore.load()
        if let problem = save(list) {
            log("servers not moved to the shared store: \(problem)")
            return
        }
        try? fm.removeItem(atPath: ServerStore.path)
        log("servers moved to the shared store: \(list.servers.count) in \(list.groups.count) group(s)")
    }

    /// What the engine needs from the service, called on the engine's threads.
    private final class Processes: NSObject, MacProcesses {
        func helper(request: String) -> String { EngineBridge.helper.request(request) }
        func startCore(config: String, tunFd: Int32) -> String? { EngineBridge.tunnel.startCore(config: config, fd: tunFd) }
        func stopCore() { EngineBridge.tunnel.stopCore() }
        func coreRunning() -> Bool { EngineBridge.tunnel.coreRunning }
        func coreCommand(command: String) -> String { EngineBridge.tunnel.coreCommand(command) }
        func network() -> String { EngineBridge.tunnel.network() }
        func tunFd() -> Int32 { EngineBridge.tunnel.utunFd }
        func limitedAvailable() -> Bool { EngineBridge.limited.available }
        func limitedSpawn(argv: [String], env: [String: String]) -> Int32 { EngineBridge.limited.spawn(argv: argv, env: env) }
        func limitedAlive(handle: Int32) -> Bool { EngineBridge.limited.alive(handle: handle) }
        func limitedSend(handle: Int32, line: String) { EngineBridge.limited.send(handle: handle, line: line) }
        func limitedRead(handle: Int32) -> String { EngineBridge.limited.read(handle: handle) }
        func limitedStop(handle: Int32) { EngineBridge.limited.stop(handle: handle) }
        func power() -> String {
            // The display's sleep, Low Power Mode and the power source are read on demand (IOPowerSources asks the
            // system once and keeps it current afterwards). AppleSmartBattery's own capacity/voltage join
            // when it reports them (-1 on a desktop or a missing key — never estimated).
            let screen = CGDisplayIsAsleep(CGMainDisplayID()) == 0
            let save = ProcessInfo.processInfo.isLowPowerModeEnabled
            let (charging, percent) = Self.battery()
            let (capMah, mv) = EnergyProbe.battery()
            return "\(screen ? 1 : 0) \(save ? 1 : 0) \(charging ? 1 : 0) \(percent) \(capMah ?? -1) \(mv ?? -1)"
        }

        /// The four roles' process counters (service, helper, main core, limited cores) and the last
        /// heavy client stamp — the energy meter's `MacProcesses.resources()` line ([EnergyProbe]).
        func resources() -> String {
            EnergyProbe.resources(helper: EngineBridge.helper.currentPid,
                                  core: EngineBridge.tunnel.corePid,
                                  limited: EngineBridge.limited.livePids)
        }

        /// Whether the Mac runs on external power and its battery level (-1 without a battery).
        private static func battery() -> (Bool, Int) {
            guard let snapshot = IOPSCopyPowerSourcesInfo()?.takeRetainedValue(),
                  let list = IOPSCopyPowerSourcesList(snapshot)?.takeRetainedValue() as? [CFTypeRef] else { return (false, -1) }
            // The IOKit.ps keys as Swift sees them are just strings (the C macros don't import).
            for source in list {
                guard let d = IOPSGetPowerSourceDescription(snapshot, source)?.takeUnretainedValue() as? [String: Any],
                      (d["Type"] as? String) == "InternalBattery" else { continue }
                let onAC = (d["Power Source State"] as? String) == "AC Power"
                let percent: Int
                if let current = d["Current Capacity"] as? Int, let max = d["Max Capacity"] as? Int, max > 0 {
                    percent = Int((Double(current) / Double(max) * 100).rounded())
                } else { percent = -1 }
                return (onAC, percent)
            }
            // No battery at all (a desktop): on external power by definition.
            return (true, -1)
        }
    }
}
