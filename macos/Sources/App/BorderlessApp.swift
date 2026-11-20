import AppKit
import Shared
import SwiftUI

/// The app: one fixed-size window (about a Finder window, local/macos/PLAN.md §3) and a menu bar item. Closing the
/// window keeps the app in the menu bar; the connection itself belongs to the system service, not to the app.
@main
struct BorderlessApp: App {
    @NSApplicationDelegateAdaptor private var delegate: AppDelegate
    @State private var model = AppModel()

    init() {
        SharedCode.setUp()
        MenuBarClickFix.install()
        Notifications.install()
        ThemedCaret.install()
    }

    var body: some Scene {
        Window("Border(less)", id: "main") {
            FullWindow { RootView(model: model) }
        }
        .windowResizability(.contentSize)
        .windowStyle(.hiddenTitleBar)

        MenuBarExtra {
            MenuBarPanel(app: model, choosing: WindowShot.choosing)
        } label: {
            MenuBarIcon(phase: model.main.phase)
        }
        .menuBarExtraStyle(.window)
    }
}

/// The window is 980 × 640 in all, the content under the hidden title bar (the traffic lights sit over the sidebar).
/// SwiftUI keeps the title bar's height as a safe area: laid out plainly the content started 32 pt down, and with
/// `ignoresSafeArea` alone the window grew by that much. So the window is sized 640 minus the bar, and the content,
/// 640 tall, is pinned to its bottom and reaches up under the bar.
struct FullWindow<Content: View>: View {
    @ViewBuilder var content: Content
    @State private var bar: CGFloat = 0

    var body: some View {
        Color.clear
            .frame(width: 980, height: 640 - bar)
            .overlay(alignment: .bottom) { content.frame(width: 980, height: 640) }
            .background(GeometryReader { g in
                Color.clear.onAppear { bar = g.safeAreaInsets.top }.onChange(of: g.safeAreaInsets.top) { bar = $1 }
            })
            .background(WindowSetup())
            .tint(Palette.accent)
    }
}

/// Nothing is focused at the start (macOS put the cursor into the first text field — the list's search). The window
/// moves only by its title strip (`WindowDrag`), not by any empty part of it (owner, 2026-10-23). Also watches the
/// window's life for [AppModel.windowVisible]: with the window closed the screens' requests stop (the menu bar only
/// needs the status the refresh loop brings).
private struct WindowSetup: NSViewRepresentable {
    /// The window's observers: registered once per window (a rebuilt view mustn't stack a second pair of
    /// tokens on the same window) and removed with it, so a closed window's tokens don't pile up in the
    /// center while the app keeps running in the menu bar.
    final class Coordinator {
        var watched: NSWindow?
        var observers: [NSObjectProtocol] = []

        @MainActor
        func watch(_ window: NSWindow) {
            guard watched !== window else { return }
            unwatch()
            watched = window
            window.makeFirstResponder(nil)
            // A hidden title bar still leaves the top strip draggable wherever the hit view allows it —
            // the SwiftUI hosting view is "transparent" (`mouseDownCanMoveWindow` true), so presses on
            // header controls dragged the window (owner, 2026-11-09); `isMovableByWindowBackground` alone
            // didn't stop that title-region drag. The window goes non-movable: only `WindowDrag`'s own
            // `performDrag` moves it now (that call is app-initiated, `isMovable` doesn't gate it).
            window.isMovableByWindowBackground = false
            window.isMovable = false
            AppModel.shared?.windowVisible = window.isVisible && !window.isMiniaturized
            let center = NotificationCenter.default
            observers = [
                center.addObserver(forName: NSWindow.willCloseNotification, object: window, queue: .main) { _ in
                    // `.main` queue: the note lands on the main actor, as the model needs.
                    MainActor.assumeIsolated { AppModel.shared?.windowVisible = false }
                },
                center.addObserver(forName: NSWindow.didBecomeKeyNotification, object: window, queue: .main) { _ in
                    MainActor.assumeIsolated { AppModel.shared?.windowVisible = true }
                },
                // Minimized is off-screen like closed: the screens' statistics/log polling stops too.
                center.addObserver(forName: NSWindow.didMiniaturizeNotification, object: window, queue: .main) { _ in
                    MainActor.assumeIsolated { AppModel.shared?.windowVisible = false }
                },
                center.addObserver(forName: NSWindow.didDeminiaturizeNotification, object: window, queue: .main) { _ in
                    MainActor.assumeIsolated { AppModel.shared?.windowVisible = true }
                },
            ]
        }

        @MainActor
        func unwatch() {
            observers.forEach { NotificationCenter.default.removeObserver($0) }
            observers = []
            watched = nil
        }
    }

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeNSView(context: Context) -> NSView {
        let view = WindowLifeView()
        view.movedToWindow = { [coordinator = context.coordinator] window in
            guard let window else {
                // Detached with its window, or not attached at all yet: report shut — a true left here
                // would keep the screens' polling alive over no window (the old async hop did exactly
                // that when `view.window` was still nil).
                coordinator.unwatch()
                AppModel.shared?.windowVisible = false
                return
            }
            coordinator.watch(window)
        }
        return view
    }

    func updateNSView(_ nsView: NSView, context: Context) {}

    static func dismantleNSView(_ nsView: NSView, coordinator: Coordinator) {
        // Representable teardown runs inside the view update, on the main thread like the attach did.
        MainActor.assumeIsolated { coordinator.unwatch() }
    }
}

/// An NSView that reports each change of its window: `viewDidMoveToWindow` covers both the first attach
/// (which can come later than the representable's creation) and the detach when the window closes.
private final class WindowLifeView: NSView {
    /// View-hierarchy changes happen on the main thread, so the callback is one too — the model it
    /// reports to is main-actor.
    var movedToWindow: (@MainActor (NSWindow?) -> Void)?

    override func viewDidMoveToWindow() {
        MainActor.assumeIsolated { movedToWindow?(window) }
    }
}

final class AppDelegate: NSObject, NSApplicationDelegate {
    /// The development modes (a request, snapshots or the icon from a terminal) show nothing: no Dock icon, no window.
    @MainActor func applicationWillFinishLaunching(_ notification: Notification) {
        let env = ProcessInfo.processInfo.environment
        if env["BORDERLESS_REQUEST"] != nil || env["BORDERLESS_SNAPSHOT"] != nil || env["BORDERLESS_ICON"] != nil {
            NSApp.setActivationPolicy(.prohibited)
        }
    }

    @MainActor func applicationDidFinishLaunching(_ notification: Notification) {
        // A menu-bar instance outlives its bundle being replaced (and `open` reactivates it instead of starting
        // the new file): its signature can't be checked by the service any more, so relaunch into the file on
        // disk. The development modes keep their stale binary — a snapshot or a request doesn't need the service.
        let env = ProcessInfo.processInfo.environment
        if env["BORDERLESS_DEMO"] == nil, env["BORDERLESS_REQUEST"] == nil, env["BORDERLESS_SNAPSHOT"] == nil,
           env["BORDERLESS_ICON"] == nil, StaleApp.replacedOnDisk() {
            StaleApp.relaunch()
            return
        }
        AppIcon.runIfAsked()
        Snapshots.runIfAsked()
        DevRequest.runIfAsked()
        WindowShot.runIfAsked()
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { false }

    /// Links opened on the app (its URL schemes in Info.plist — a browser, `open`, a chat). Handled
    /// here rather than on a scene: the window's scene may not exist — the app lives in the menu bar
    /// with the window closed — and the delegate gets what no scene claims.
    @MainActor func application(_ application: NSApplication, open urls: [URL]) {
        if let model = AppModel.shared {
            for url in urls { model.openLink(url) }
        } else {
            // The app was launched by the link itself and its model isn't up yet — [AppModel.init]
            // picks these up once it registers.
            Self.pendingLinks += urls
        }
    }

    /// Links that arrived before the model did (above).
    @MainActor private static var pendingLinks: [URL] = []

    /// The queued links, taken once — the first model owns them.
    @MainActor static func takePendingLinks() -> [URL] {
        defer { pendingLinks = [] }
        return pendingLinks
    }

    /// Set by [quit]: the menu bar's "Quit" ends the app for real.
    @MainActor private static var quitting = false

    /// Quits for real (the menu bar's "Quit", the service page's).
    @MainActor static func quit() {
        quitting = true
        NSApp.terminate(nil)
    }

    /// ⌘Q (the app menu's Quit) only closes the window: the app stays in the menu bar (owner, 2026-10-23) — and
    /// leaves the Dock and the app switcher with it (owner, 2026-11-02: ⌘Q kept the app in ⌘-Tab): an accessory app
    /// isn't in either, while the service, the menu bar item and the polling go on. Opening the window again (the
    /// menu bar's "Open Border(less)") makes it a regular app, with its Dock icon and ⌘-Tab place back. The system's
    /// own quit — logging out, restarting, shutting down, a script — still ends it (it comes as an Apple event), and
    /// so does the menu bar's "Quit". The difference between ⌘Q and Dock-Quit is intended, not a bug: the
    /// first means "close the window", the second "quit the app" (the owner's rulings above).
    @MainActor func applicationShouldTerminate(_ sender: NSApplication) -> NSApplication.TerminateReply {
        if Self.quitting { return .terminateNow }
        if let event = NSAppleEventManager.shared().currentAppleEvent,
           event.eventClass == AEEventClass(kCoreEventClass), event.eventID == AEEventID(kAEQuitApplication) {
            return .terminateNow
        }
        for window in sender.windows where window.isVisible && window.styleMask.contains(.titled) { window.close() }
        NSApp.setActivationPolicy(.accessory)
        return .terminateCancel
    }
}

@MainActor @Observable
final class AppModel: ServiceBackend {
    /// The one real model ([WindowSetup] reports the window's life to it; the demo's copy never registers).
    nonisolated(unsafe) static var shared: AppModel?

    var service: ServiceStatus = .checking
    /// The setup page's wording, decided by the first state that needs it (missing → install, outdated →
    /// update — or install again when the service is the newer side, a downgrade of this copy) and kept
    /// until the service runs: while the installer replaces the daemon the socket's answer flips
    /// between missing, unreachable and outdated, and the page must neither reword itself nor flicker to the
    /// main screens mid-install (owner, 2026-11-06).
    var serviceSetup: Bool?
    /// `BORDERLESS_DEMO=1`: the screens with made-up data, whatever the service's state.
    let demo: Bool
    /// What the screens show: the demo, or the user's servers kept by the service.
    let main: MainModel
    /// The window's menu/dialog layer — created here, not inside the Shell, so a link opened from
    /// outside the app (a browser's `borderless://`) can put its dialog up even while the window
    /// is closed; the Shell renders whatever is on it when it comes.
    let windowMenus = MenuState()
    /// The window's `openWindow`, stashed by the RootView: a link that arrives with the window closed
    /// must be able to bring it back (the menu bar's "Open Border(less)" calls the same action).
    @ObservationIgnored var openWindow: OpenWindowAction?

    /// Commands in flight: a count, not a flag — a second command started while one is still out must not
    /// re-enable the controls when the first finishes.
    var busyCommands = 0
    var busy: Bool { busyCommands > 0 }
    /// The last failure of a command (refused, or the service gave an error). Written here, read by the
    /// screens as `main.commandError` — one field, not a mirrored pair (the refresh loop's own "didn't
    /// answer" note writes the same store on the main model).
    var commandError: String? {
        get { main.commandError }
        // Compare before writing, as the refresh loop does: an equal write still invalidates every reader.
        set { if main.commandError != newValue { main.commandError = newValue } }
    }
    /// The service's list has been shown (edits are sent back from then on), what it last accepted, and when
    /// it was last read again (it changes by itself: a subscription update adds and removes servers).
    @ObservationIgnored private var loaded = false
    @ObservationIgnored private var saved: ServerList?
    @ObservationIgnored private var serversAt = Date.distantPast
    /// The "didn't answer" note on the main model came from us (cleared when the service answers again).
    @ObservationIgnored private var unreachableNoted = false
    /// The debounce task an edit schedules ([watchServers]) — cancelling it stops only the wait, not a
    /// save that already left for the wire. That in-flight part is [saveInFlight].
    @ObservationIgnored private var saving: Task<Void, Never>?
    /// The saveServers currently on the wire. Every save chains behind it, so two lists can never
    /// interleave and push the [saved] baseline back to an older one.
    @ObservationIgnored private var saveInFlight: Task<Bool, Never>?
    /// The settings and zones as the service last knew them (edits are diffed against this).
    @ObservationIgnored private var settingsSent: [String: Any]?
    @ObservationIgnored private var zonesSent: [ZoneJson]?
    @ObservationIgnored private var savingSettings: Task<Void, Never>?
    @ObservationIgnored private var savingZones: Task<Void, Never>?
    /// The saves currently on the wire, chaining like [saveInFlight] does for the server list: a second
    /// call waits for the first, then diffs what the settings / zones are at that moment — two patches
    /// computed off the same baseline could apply in either order and leave the service disagreeing
    /// with [settingsSent] / [zonesSent].
    @ObservationIgnored private var settingsInFlight: Task<Void, Never>?
    @ObservationIgnored private var zonesInFlight: Task<Void, Never>?
    /// Increased when the statistics are cleared: a statistics reply that was already on the wire then
    /// describes data the service no longer has and must not resurrect it.
    @ObservationIgnored private var statsGeneration = 0
    /// The service's updater state (`engine.update` of the last engine answer — PLAN §2), nil while a
    /// service that doesn't send it is installed. Drives the window's banner and the panel's row.
    var update: EngineStatus.UpdateStatus?
    /// A service reinstall is on the wire (the `.unreachable` repair path): the affordance greys out.
    var repairingService = false
    /// The main window is on screen (the menu bar alone needs no statistics or log fetching). A
    /// false→true edge is the window's open — Android's app-open moment, which asks the engine for a
    /// stale-data rescan (`scan_open`, rulings R6; the engine decides what is old enough).
    @ObservationIgnored var windowVisible = true {
        didSet { if windowVisible, !oldValue { scanOpen() } }
    }
    /// When the last `scan_open` left: window edges and activations can fire together (a dock click
    /// activates and shows), and one open is one trigger, not two.
    @ObservationIgnored private var scanOpenAt = Date.distantPast
    /// The first `scan_open` that waited for the service's first answer (the open edges can all fire
    /// while the status is still `.checking`); subsequent opens are the edges' own.
    @ObservationIgnored private var openedScanSent = false
    /// The `.unreachable` repair's failure message while it is what [commandError] shows — kept
    /// so the generic "didn't answer" the refresh loop writes doesn't bury it (verify fix).
    @ObservationIgnored private var repairFailed: String?
    /// The log and the statistics are loaded while their screens are open; what they were loaded for.
    @ObservationIgnored private var logAt = Date.distantPast
    /// One statistics request on the wire; a refresh asked for while it runs is kept ([statsWanted])
    /// and run when it finishes — before, it was silently dropped (a period picked mid-fetch never came).
    @ObservationIgnored private var statsBusy = false
    @ObservationIgnored private var statsWanted = false
    /// One refresh at a time: the poll loop, the window's activations, the menu bar and every command can
    /// ask while a run is still on the wire. Two parallel runs would both pass the `!loaded` check (an
    /// `await` sits between it and `loaded = true`) and stack duplicate `withObservationTracking` chains
    /// that then live forever. A request that arrives mid-run folds into one repeat at the end.
    @ObservationIgnored private var refreshing = false
    @ObservationIgnored private var refreshAgain = false
    /// The engine-reported error the dialog last showed (`pendingErrors`): a poll must not re-open the same one.
    @ObservationIgnored private var lastPendingError: Int64 = 0
    /// … and the texts it already showed this run: a service restart re-queues its boot reports with
    /// fresh ids, so the id alone would let one boot's dialog fire again and again (2026-11 — a rotated
    /// data key queued one report per unreadable file at every service start).
    @ObservationIgnored private var shownPendingTexts = Set<String>()
    /// When the user last asked for a phase change (toggle, connect): [MainModel] holds engine answers
    /// for 2 s from then (its `held`), and the engine-less fallback in [refreshOnce] must keep the same
    /// hold — its own clock, as the model's `userSet` isn't reachable here.
    @ObservationIgnored private var phaseAskedAt = Date.distantPast

    init(demo: Bool = ProcessInfo.processInfo.environment["BORDERLESS_DEMO"] != nil) {
        self.demo = demo
        main = demo ? MainModel.demo() : MainModel.real()
        main.selectedId = WindowShot.selected
        if demo, let tab = Int(ProcessInfo.processInfo.environment["BORDERLESS_LOG_TAB"] ?? ""), (0...1).contains(tab) {
            main.logTab = tab
        }
        if !demo {
            main.backend = self
            Self.shared = self
            // Links that launched the app and arrived before this model existed.
            for url in AppDelegate.takePendingLinks() { openLink(url) }
        }
        // The state can change by itself (the core stopped, another tunnel appeared): look every few seconds, also
        // with the window closed (the menu bar icon shows it).
        Task { [weak self] in
            while let self {
                await self.refresh()
                // Every second while servers are checked or a connection is being made (the orbits follow the scan).
                let busy = self.main.scan != nil || self.main.phase == .searching
                try? await Task.sleep(for: .seconds(busy ? 1 : 3))
            }
        }
    }

    func refresh() async {
        if refreshing {
            refreshAgain = true
            return
        }
        refreshing = true
        repeat {
            refreshAgain = false
            await refreshOnce()
        } while refreshAgain
        refreshing = false
    }

    private func refreshOnce() async {
        // Compare before writing: an observable field written with an equal value still invalidates
        // every reader (the 1–3 s tick used to re-render the whole window over nothing, U1.5).
        let status = await ServiceClient.status()
        if service != status { service = status }
        guard !demo else { return }
        // Anything but a good answer can be a stale image the service can't check (the bundle was replaced
        // while this process sat in the menu bar): relaunch into the file on disk rather than error forever.
        // `.running` itself can't be stale — the service verified this peer's signature to answer at all —
        // so the full bundle validation is skipped for it; the rest reuse [StaleApp]'s cached verdict.
        if service != .checking, !service.isRunning, StaleApp.replacedOnDisk() {
            StaleApp.relaunch()
            return
        }
        switch service {
        case .missing: serviceSetup = serviceSetup ?? false
        // A service newer than the app (this copy is a downgrade) is still just a reinstall, not an "update".
        case .outdated(let installed): serviceSetup = serviceSetup ?? !ServiceClient.isNewer(installed, than: borderlessVersion)
        case .running: if serviceSetup != nil { serviceSetup = nil }
        case .checking, .unreachable: break
        }
        if case .unreachable = service {
            // Installed but quiet: the screens keep what they know (the servers, the last phase) — the
            // service comes back on its own, and nothing the user did is lost meanwhile.
            unreachableNoted = true
            // A repair that just failed left its own note in [commandError]: keep it — it explains the
            // still-quiet daemon better than the generic "didn't answer" it would be buried under
            // on this and every next poll. Anything else that lands there gets the generic wording.
            if main.commandError != repairFailed {
                let text = String(localized: "main.no.answer")
                if main.commandError != text { main.commandError = text }
            }
            return
        }
        if unreachableNoted {
            unreachableNoted = false
            // Answered again (or really absent): the "didn't answer" note is stale — unless something else
            // replaced it meanwhile. A repair message goes with it — the daemon it spoke about answers now.
            if let failed = repairFailed {
                repairFailed = nil
                if main.commandError == failed { main.commandError = nil }
            }
            if main.commandError == String(localized: "main.no.answer") { main.commandError = nil }
        }
        guard case .running(let reply) = service else { return }
        // The engine's first answer while the window is up counts as "the app opened" too: the
        // launch-time open edges (the window's show, the app's activation) can fire while the
        // status is still `.checking` — this is the open that reaches a late-answering service.
        if windowVisible, !openedScanSent {
            openedScanSent = true
            scanOpen()
        }
        // Another tunnel carrying the traffic is the "not through a server" case Android banners too
        // (the service then refuses to start ours over it — `NetworkStatus.otherTunnel`). It rides
        // the network status, so it is told even while the engine's own answer can't be read.
        Notifications.foreignTunnel(reply.network?.otherTunnel, mode: main.settings.notifyMode)
        if main.statsBytes != reply.statsBytes { main.statsBytes = reply.statsBytes }
        // The list: once at the start, then again about every 15 s while the window is open — and right
        // after each command ([serversAt] is reset there) — because the service changes it itself.
        if !loaded || (windowVisible && Date().timeIntervalSince(serversAt) > 15) {
            // The try counts, not only the answer: a service that says hello but fails `.servers`
            // would otherwise be asked again every 1–3 s. The first load is the exception — it may
            // reasonably retry each tick until something is there to show.
            if loaded { serversAt = Date() }
            let listReply = await ServiceClient.send(Request(.servers), timeoutSec: 5)
            if let error = listReply?.error { commandError = ServiceError.friendlyLogged(error) }
            if let list = listReply?.servers {
                serversAt = Date()
                if !loaded {
                    main.load(list)
                    saved = main.serverList
                    loaded = true
                    watchServers()
                    watchSettings()
                    watchZones()
                    main.hidePendingKeys()
                } else if main.serverList == saved {
                    // Edits not sent yet win over a refetch. The new items keep nothing: the pings and checks
                    // the engine reported are put back by id (its status fills the rest on the next refresh).
                    var shown = main.items(from: list)
                    // `uniqueKeysWithValues` would trap on a duplicate id: upstream dedups, but a
                    // malformed list must not crash the app — the first entry wins.
                    let pings = Dictionary(main.servers.map { ($0.id, ($0.ms, $0.checked)) },
                                           uniquingKeysWith: { first, _ in first })
                    for i in shown.servers.indices {
                        if let p = pings[shown.servers[i].id] {
                            shown.servers[i].ms = p.0
                            shown.servers[i].checked = p.1
                        }
                    }
                    // A byte-identical answer must not rebuild every row (each 15 s re-rendered the whole
                    // window, U1.5): only a real difference is assigned, and with it the gone-item clean-up.
                    if shown.groups != main.groups || shown.servers != main.servers || shown.subscriptions != main.subscriptions {
                        main.groups = shown.groups
                        main.servers = shown.servers
                        main.subscriptions = shown.subscriptions
                        // A server or a subscription that went away can't stay picked. The statistics'
                        // subscription target goes too — a gone server's page stays: the service still
                        // reports its figures and the phantom row carries them (F-STATS-0073, Android's
                        // `ServerStatsScreen` with `server == null`).
                        if let id = main.pickedId, !main.servers.contains(where: { $0.id == id }) { main.pickedId = nil }
                        if let id = main.selectedId,
                           !main.servers.contains(where: { $0.id == id }), !main.subscriptions.contains(where: { $0.id == id }) {
                            main.selectedId = nil
                        }
                        if case .subscription(let id) = main.statsTarget,
                           !main.subscriptions.contains(where: { $0.id == id }) {
                            main.statsTarget = nil
                        }
                    }
                    saved = main.serverList
                    // After `saved`: an imported bundle's hidden-server keys edit `servers` as a local
                    // change, so the save watcher sends them back to the service.
                    main.hidePendingKeys()
                }
            }
        }
        if loaded {
            // The settings and zones once (their edits go back as patches; the rest comes with the engine's status).
            if settingsSent == nil, let json = await ServiceClient.send(Request(.settings), timeoutSec: 5)?.settings {
                main.loadSettings(json)
                settingsSent = SettingsJson.full(main.settings, range: main.statsRange)
            }
            if zonesSent == nil, let json = await ServiceClient.send(Request(.zones), timeoutSec: 5)?.zones {
                main.loadZones(json)
                zonesSent = main.zones.map { ZoneJson($0) }
            }
            await loadScreens()
        }
        if let engine = reply.engine {
            main.apply(engine)
            // The updater's word rides the engine status: whatever state it reports flows to the
            // banner/row — an "idle" the service settles into hides them again. nil = a service
            // without an updater: the last one reported stays rather than flickering away.
            if let u = engine.update, update != u { update = u }
            // A failure the engine wants the user to see (Android's Errors.pending → its error dialog): one
            // at a time; closing the dialog dismisses it back in the engine. A poll re-shows only a new one.
            if let first = engine.pendingErrors?.first, first.id != lastPendingError,
               !shownPendingTexts.contains(first.message) {
                lastPendingError = first.id
                shownPendingTexts.insert(first.message)
                windowMenus.show(dialog: AnyView(ErrorDialog(error: first) { [weak self] in
                    self?.dismissPendingError(first.id)
                }))
            }
            // The status bar's own word of the state (notifyMode), a crash the dialog waits out,
            // and a staged update that became ready — each is a banner when the user isn't looking.
            Notifications.state(phase: main.phase, unanswered: engine.unanswered,
                                noServers: engine.noServers, paused: engine.paused ?? false,
                                connectedCountry: main.current.map { countryName($0.country) },
                                mode: main.settings.notifyMode)
            Notifications.crashes(engine.pendingErrors)
            Notifications.update(update)
        } else {
            // No engine status to read (it didn't decode): the tunnel's word alone decides, as [MainModel.apply]
            // does — including its 2 s user hold on the phase ([phaseAskedAt]), or a decode failure while a
            // toggle/connect is on the wire would flip the button back.
            let phase: Phase = switch reply.tunnel {
            case .off: .off
            case .connecting: .searching
            case .connected: .connected
            }
            if Date().timeIntervalSince(phaseAskedAt) >= 2, main.phase != phase { main.phase = phase }
            let current = reply.tunnel == .off ? nil : reply.serverId
            if main.currentId != current { main.currentId = current }
        }
        // A dead previous service run is no engine error and never reaches `pendingErrors` — the
        // daemon can't queue one (APP-LOG's seam); `reply.problem` carries its line until the next
        // start clears it. [Errors.crashEntry] turns the sighting into Android's crash dialog +
        // banner, deduped per app run by `Errors.shown`. Outside the engine branch on purpose:
        // `.problem` still arrives when the engine answer itself failed to decode. `{}` for
        // dismiss — the service knows no `error_dismiss` for a synthesized entry.
        if let crash = Errors.crashEntry(problem: reply.problem) {
            windowMenus.show(dialog: AnyView(ErrorDialog(error: crash) {}))
        }
    }

    /// What the open screens need fresh: the log while it is shown, the statistics while theirs are. Nothing runs
    /// with the window closed (the menu bar icon only needs the status the refresh loop already brings).
    private func loadScreens() async {
        guard windowVisible else { return }
        if main.section == .log, Date().timeIntervalSince(logAt) > 5 {
            logAt = Date()
            let events = await ServiceClient.send(Request(.events), timeoutSec: 5)?.events
            let lines = await ServiceClient.send(Request(.debugLog), timeoutSec: 5)?.lines
            if let events {
                // Identity from the event itself plus its place among equal ones — not `hashValue` (random
                // per process, and identical events in the same millisecond collided). This way a refetch
                // bringing the same events keeps the rows' identity and doesn't rebuild the whole list.
                var seen: [String: Int] = [:]
                let mapped = events.reversed().map { e -> LogEvent in
                    let base = "\(e.time)-\(e.kind)-\(e.text)"
                    let n = seen[base, default: 0]
                    seen[base] = n + 1
                    return LogEvent(id: "\(base)#\(n)",
                                    time: Date(timeIntervalSince1970: Double(e.time) / 1000),
                                    kind: kind(of: e.kind), text: SharedCode.renderText(e.text))
                }
                if main.events != mapped { main.events = mapped }
            }
            if let lines, main.debugLines != lines { main.debugLines = lines }
        }
        // The statistics: the period on screen, or the last day for the main screen's server details. `to`
        // is cut to the minute so the request's window holds still between ticks. "All time" starts at
        // zero, as Android's first record does.
        guard main.section == .main || main.section == .stats else { return }
        let forMain = main.section != .stats
        let target: String?
        var from: Date, to: Date
        if !forMain {
            switch main.statsTarget {
            case .server(let id): target = "s:\(id)"
            case .subscription(let id): target = "sub:\(id)"
            case nil: target = nil
            }
            if main.statsPeriod == .custom, let range = main.statsRange {
                (from, to) = (range.lowerBound, range.upperBound)
            } else {
                to = Date(timeIntervalSince1970: (Date().timeIntervalSince1970 / 60).rounded(.down) * 60)
                from = main.statsPeriod == .all ? Date(timeIntervalSince1970: 0) : to.addingTimeInterval(-main.statsPeriod.seconds)
            }
        } else {
            target = nil
            to = Date(timeIntervalSince1970: (Date().timeIntervalSince1970 / 60).rounded(.down) * 60)
            from = to.addingTimeInterval(-86_400)
        }
        // Freshness is the window asked for (the period, an explicit range, the target), not the
        // timestamps it lands on: `to` in the key used to turn over once a minute and flash the whole
        // page into loading for ~identical data (U1.5d).
        let windowKey: String
        if forMain {
            windowKey = "day"
        } else if main.statsPeriod == .custom, let range = main.statsRange {
            windowKey = "custom:\(range.lowerBound.timeIntervalSince1970)-\(range.upperBound.timeIntervalSince1970)"
        } else {
            windowKey = String(describing: main.statsPeriod)
        }
        let key = "\(windowKey)|\(target ?? "-")"
        let due = forMain ? 30 : 10
        // The day figures of the main screen and the statistics' own share neither the key nor the clock:
        // one must not throttle the other. A new key asks for an answer at once, not on the next tick.
        let shownKey = forMain ? main.dayStatsKey : main.statsKey
        let fetchedAt = forMain ? main.dayStatsAt : main.statsAt
        guard shownKey != key || Date().timeIntervalSince(fetchedAt) > TimeInterval(due) else {
            // A re-queued run ([statsWanted], [statsRefresh]) can land here right after the fetch it
            // waited on filed fresh figures: the spinner it raised must not linger over them.
            main.statsLoading = false
            return
        }
        // A request asked for while one is still on the wire must not drop silently ([statsWanted]).
        guard !statsBusy else { statsWanted = true; return }
        statsBusy = true
        defer {
            statsBusy = false
            if statsWanted {
                statsWanted = false
                Task { await self.loadScreens() }
            }
        }
        let fresh = shownKey != key
        if fresh { main.statsError = nil }
        // The old figures stay under the spinner until the new ones are filed.
        main.statsLoading = forMain ? false : main.stats == nil || fresh
        let generation = statsGeneration
        let json = await ServiceClient.send(Request(.stats, fromMs: Int64(from.timeIntervalSince1970 * 1000),
                                                    toMs: Int64(to.timeIntervalSince1970 * 1000), target: target), timeoutSec: 15)?.stats
        // A "clear statistics" that landed while the request was on the wire wins: the answer describes
        // data the service no longer has, so it is dropped instead of resurrecting the cleared figures.
        if generation == statsGeneration {
            if let json, var d = StatsData(json: json, model: main) {
                // The activity tab's own reply (ENERGY-IMPL, `stats` target "activity"): its own
                // request, asked only while the tab is on — the overview's payload stays as it was.
                if !forMain && main.statsTab == 1 && main.statsTarget == nil {
                    let a = await ServiceClient.send(
                        Request(.stats, fromMs: Int64(from.timeIntervalSince1970 * 1000),
                                toMs: Int64(to.timeIntervalSince1970 * 1000), target: "activity"),
                        timeoutSec: 15)?.stats
                    if let a { d.activity = ActivityData(json: a) }
                }
                // Filed where the request was made, not where the user is by the time the answer comes.
                if forMain {
                    main.dayStats = d
                    main.dayStatsKey = key
                    main.dayStatsAt = Date()
                } else {
                    main.stats = d
                    main.statsKey = key
                    main.statsAt = Date()
                    main.statsError = nil
                }
            } else {
                // A failed try counts as a try, or a quiet service would be asked again every 1–3 s.
                if forMain {
                    main.dayStatsKey = key
                    main.dayStatsAt = Date()
                } else {
                    main.statsKey = key
                    main.statsAt = Date()
                    // Stayed quiet is a different failure than an answer that didn't read (a "{}" — the
                    // service's own "statistics failed" fallback — is the second, U3).
                    main.statsError = json == nil ? String(localized: "main.no.answer")
                        : macText("mac.err.badreply", "The service's answer couldn't be read.")
                }
            }
        }
        main.statsLoading = false
    }

    /// A statistics request outside the refresh loop's pace (a new period picked, a "try again"): forced
    /// past the throttle. The spinner is set at once — between the pick and the request reaching the
    /// wire (or waiting on one already out, [statsWanted]) the page would show old numbers looking done.
    func statsRefresh() {
        if main.section == .stats { main.statsLoading = true }
        Task { main.statsAt = .distantPast; main.dayStatsAt = .distantPast; await loadScreens() }
    }

    func screenChanged() {
        Task { await loadScreens() }
    }

    private func kind(of name: String) -> LogEvent.Kind {
        switch name {
        case "GOOD": .good
        case "WARN": .warn
        case "ERROR": .error
        default: .info
        }
    }

    /// Edits anywhere in the window (add, rename, move, hide, delete) go to the service a moment later, as one list.
    private func watchServers() {
        withObservationTracking {
            _ = (main.servers, main.groups, main.subscriptions)
        } onChange: { [weak self] in
            Task { @MainActor in
                guard let self else { return }
                self.watchServers()
                self.saving?.cancel()
                self.saving = Task {
                    try? await Task.sleep(for: .milliseconds(400))
                    guard !Task.isCancelled else { return }
                    _ = await self.save()
                }
            }
        }
    }

    /// Every edit of the settings screen becomes a patch of the changed fields only, a moment later.
    private func watchSettings() {
        withObservationTracking {
            _ = main.settings
        } onChange: { [weak self] in
            Task { @MainActor in
                guard let self else { return }
                self.watchSettings()
                self.savingSettings?.cancel()
                self.savingSettings = Task {
                    try? await Task.sleep(for: .milliseconds(400))
                    guard !Task.isCancelled else { return }
                    await self.saveSettings()
                }
            }
        }
    }

    /// Serialized like [save] for the server list: a call made while a patch is still out waits for it,
    /// then diffs the settings as they are at that moment — two patches off the same baseline could
    /// apply in either order and leave the service disagreeing with [settingsSent].
    private func saveSettings() async {
        let prior = settingsInFlight
        let run = Task {
            _ = await prior?.value
            let now = SettingsJson.full(main.settings, range: main.statsRange)
            var patch: [String: Any] = [:]
            if let sent = settingsSent {
                for key in SettingsJson.fieldNames {
                    let a = now[key], b = sent[key]
                    if "\(a)" == "\(b)" { continue }
                    // A range that is gone goes as zeros (the service keeps it in the settings; null it can't read).
                    if a == nil { patch[key] = key == "statsFrom" || key == "statsTo" ? 0 : NSNull() } else { patch[key] = a as Any }
                }
            } else { patch = now }
            patch.removeValue(forKey: "countries")
            guard !patch.isEmpty else { return }
            guard let data = try? JSONSerialization.data(withJSONObject: patch),
                  let answer = await ServiceClient.send(Request(.saveSettings, json: String(decoding: data, as: UTF8.self)), timeoutSec: 10) else {
                commandError = String(localized: "main.no.answer")
                return
            }
            // Only a stored change becomes the baseline: a refused one is retried by the next edit.
            guard let error = answer.error else {
                settingsSent = now
                commandError = nil
                return
            }
            guard error.hasPrefix("locked:") else {
                commandError = ServiceError.friendlyLogged(error)
                return
            }
            // A daemon that still locks a field — "locked: palette" of a daemon built before
            // 2026-11-08 — refuses the whole patch; the baseline staying behind then offered the
            // key in every later patch too, and one refused field jammed the whole settings
            // channel (U1.1). Mark the named keys as sent (the service keeps its own values)
            // and retry the rest once, so it can't.
            let keys = error.dropFirst("locked:".count)
                .split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }
            NSLog("Border(less): the service locks settings (\(keys.joined(separator: ", "))); dropped from patches")
            var sent = settingsSent ?? [:]
            for key in keys { sent[key] = now[key] }
            settingsSent = sent
            let rest = patch.filter { !keys.contains($0.key) }
            guard !rest.isEmpty,
                  let restData = try? JSONSerialization.data(withJSONObject: rest),
                  let retry = await ServiceClient.send(Request(.saveSettings, json: String(decoding: restData, as: UTF8.self)), timeoutSec: 10) else {
                // Nothing left after the locked keys, or the retry never reached the service.
                commandError = rest.isEmpty ? nil : String(localized: "main.no.answer")
                return
            }
            if let retryError = retry.error {
                commandError = ServiceError.friendlyLogged(retryError)
            } else {
                // The rest landed; the locked keys' marks from [sent] agree with `now` here
                // (their diff can't come up again either way).
                settingsSent = now
                commandError = nil
            }
        }
        settingsInFlight = run
        await run.value
    }

    /// Zones are sent whole when one is added, edited or switched; a removed one is deleted on the service.
    private func watchZones() {
        withObservationTracking {
            _ = main.zones
        } onChange: { [weak self] in
            Task { @MainActor in
                guard let self else { return }
                self.watchZones()
                self.savingZones?.cancel()
                self.savingZones = Task {
                    try? await Task.sleep(for: .milliseconds(400))
                    guard !Task.isCancelled else { return }
                    await self.saveZones()
                }
            }
        }
    }

    /// Serialized like [save]: edits made while a save is on the wire used to be dropped by a busy flag;
    /// now a second call waits for the first, then sends what the zones are at that moment.
    private func saveZones() async {
        let prior = zonesInFlight
        let run = Task {
            _ = await prior?.value
            let now = main.zones.map { ZoneJson($0) }
            for (i, z) in main.zones.enumerated() {
                guard zonesSent?.first(where: { $0.id == z.id }) != now[i] else { continue }
                guard let data = try? JSONEncoder().encode(now[i]),
                      let answer = await ServiceClient.send(Request(.saveZone, json: String(decoding: data, as: UTF8.self)), timeoutSec: 10) else {
                    commandError = String(localized: "main.no.answer")
                    return  // the baseline stays, the next zone edit (or this one's repeat) retries it
                }
                guard answer.error == nil else {
                    commandError = ServiceError.friendlyLogged(answer.error)
                    return
                }
            }
            for old in zonesSent ?? [] where old.id != nil && !now.contains(where: { $0.id == old.id }) {
                guard let answer = await ServiceClient.send(Request(.deleteZone, target: old.id), timeoutSec: 10) else {
                    commandError = String(localized: "main.no.answer")
                    return
                }
                guard answer.error == nil else {
                    commandError = ServiceError.friendlyLogged(answer.error)
                    return
                }
            }
            zonesSent = now
        }
        zonesInFlight = run
        await run.value
    }

    /// Stores the current list unless the service already has it; true when the service's list matches.
    /// Calls serialize through [saveInFlight]: each run waits for the previous one, then sends what the
    /// list is at that moment — so a newer edit rides the same flush instead of racing it.
    @discardableResult
    private func save() async -> Bool {
        let prior = saveInFlight
        let run = Task<Bool, Never> {
            _ = await prior?.value
            // Nothing is sent until the service's own list has been read: before the first `.servers`
            // answer `saved` is still nil and the shown list empty — a `.disconnect` from the menu-bar
            // panel in that window would push an empty list over what the service keeps. The command
            // behind the save can still go: the service's stored list stands.
            guard loaded else { return true }
            let list = main.serverList
            guard list != saved else { return true }
            let answer = await ServiceClient.send(Request(.saveServers, servers: list), timeoutSec: 10)
            if let answer, answer.error == nil {
                saved = list
                return true
            }
            commandError = answer == nil ? String(localized: "main.no.answer") : ServiceError.friendlyLogged(answer?.error)
            return false
        }
        saveInFlight = run
        return await run.value
    }

    /// On: the engine chooses as the strategy says (as Android's button, which picks no server); off: off.
    func toggle() {
        // Like [MainModel]'s `userSet["phase"]` — the engine-less fallback honours the same 2 s hold.
        phaseAskedAt = Date()
        guard case .running(let reply) = service else {
            // The switch is reachable while the service is unreachable or still checking (the menu bar's
            // panel is always up): say why nothing happened instead of silently doing nothing.
            commandError = String(localized: "main.no.answer")
            return
        }
        if reply.tunnel != .off {
            send(Request(.disconnect))
            return
        }
        guard !main.visible.isEmpty else {
            commandError = L("empty_title")
            return
        }
        send(Request(.connect))
    }

    /// A `borderless://import?d=…`, `wdtt://…` or another server link opened on us (Info.plist's URL
    /// types — a browser, `open`, a chat app). It lands where pasted text does: the "Add servers"
    /// dialog, prefilled — as Android's `incoming` opens `AddSheet`. Nothing is imported without the
    /// user's "Add", and a malformed link is then reported by the dialog like a pasted one.
    func openLink(_ url: URL) {
        NSApp.setActivationPolicy(.regular)
        NSApp.activate()
        openWindow?.callAsFunction(id: "main")
        windowMenus.show(dialog: AnyView(AddDialog(model: main, text: url.absoluteString)))
    }

    func pingAll() {
        send(Request(.pingAll))
    }

    /// The window came up or the app became active: ask the service to rescan what is stale
    /// (`serviceAction "scan_open"`, rulings R6 — Android's app-open check, where the engine decides:
    /// a fresh map costs nothing, a stale one gets its scan; the same trigger is where Android runs
    /// its due-work — subscriptions, geo lists, the update check — for the service to do the same).
    /// Quiet: a refused or lost answer is a no-op — the next open asks again; and a show that follows
    /// an activation by a moment is one "open", not two. The activation edge covers the menu-bar
    /// panel too — it carries the live server list and scan progress, so the user is looking at the
    /// same data the window would show: a "servers on screen" moment either way.
    func scanOpen() {
        guard !demo, service.isRunning, Date().timeIntervalSince(scanOpenAt) > 15 else { return }
        scanOpenAt = Date()
        Task { _ = await ServiceClient.send(Request(.serviceAction, target: "scan_open"), timeoutSec: 10) }
    }

    /// The `.unreachable` service's repair path (agent-tasks 2026-11-09 — a dead daemon has no way back
    /// from the UI): reinstall over it, the same script and password prompt the setup page runs. The
    /// screens stay up meanwhile — unreachable keeps the last state — and the wait for it to answer
    /// is the setup page's own patience.
    func repairService() {
        guard !repairingService, !demo else { return }
        repairingService = true
        repairFailed = nil
        Task {
            defer { repairingService = false }
            switch await ServiceInstaller.install() {
            case .done:
                for _ in 0..<20 where !Task.isCancelled {
                    if case .running = await ServiceClient.status() { break }
                    try? await Task.sleep(for: .milliseconds(500))
                }
            case .cancelled:
                break
            case .failed(let message):
                // Shown in the sidebar's error line and remembered — the refresh loop's generic
                // "didn't answer" must not overwrite it while the daemon stays quiet.
                repairFailed = message
                commandError = message
            }
            await refresh()
        }
    }

    func connect(_ serverId: String) {
        phaseAskedAt = Date()
        send(Request(.connect, serverId: serverId))
    }

    func setStrategy(_ s: Strategy) {
        main.strategy = s
        guard let data = try? JSONSerialization.data(withJSONObject: ["strategy": s.jsonName]) else { return }
        sendSettings(String(decoding: data, as: UTF8.self))
    }

    func setRouting(_ on: Bool) {
        main.routing = on
        // The settings screen's own switch binds this field, filled only at the first load: without the
        // sync it showed the pre-toggle value all session (its watcher also carries the change through
        // [saveSettings]'s baseline, which the ad-hoc send below bypasses).
        if main.settings.routingEnabled != on { main.settings.routingEnabled = on }
        guard let data = try? JSONSerialization.data(withJSONObject: ["routingEnabled": on]) else { return }
        sendSettings(String(decoding: data, as: UTF8.self))
    }

    func setEco(_ on: Bool) {
        main.eco = on
        Task {
            report(await ServiceClient.send(Request(.setEco, on: on), timeoutSec: 10))
            serversAt = .distantPast
            await refresh()
        }
    }

    @ObservationIgnored private var countriesWanted: Set<String>?
    @ObservationIgnored private var countriesSending = false

    func setCountries(_ codes: Set<String>) {
        main.countries = codes
        main.countriesRequestPending = true
        countriesWanted = codes
        guard !countriesSending else { return }
        countriesSending = true
        Task {
            while let wanted = countriesWanted {
                countriesWanted = nil
                let answer = await ServiceClient.send(Request(.setCountries, countries: wanted.sorted()), timeoutSec: 10)
                report(answer)
                if (answer == nil || answer?.error != nil), countriesWanted == nil {
                    main.pendingCountrySelection = nil
                }
            }
            countriesSending = false
            main.countriesRequestPending = false
            serversAt = .distantPast
            await refresh()
        }
    }

    func clearLog() {
        Task { report(await ServiceClient.send(Request(.clearLog), timeoutSec: 10)) }
    }

    func setAutoActive(_ on: Bool) {
        main.autoActive = on
        guard let data = try? JSONSerialization.data(withJSONObject: ["autoActive": on]) else { return }
        sendSettings(String(decoding: data, as: UTF8.self))
    }

    func setAutoSort(_ on: Bool) {
        main.autoSort = on
        guard let data = try? JSONSerialization.data(withJSONObject: ["autoSort": on]) else { return }
        sendSettings(String(decoding: data, as: UTF8.self))
    }

    func setStatsRange(_ fromMs: Int64, _ toMs: Int64) {
        guard let data = try? JSONSerialization.data(withJSONObject: ["statsFrom": fromMs, "statsTo": toMs]) else { return }
        sendSettings(String(decoding: data, as: UTF8.self))
    }

    func runAction(_ kind: String) {
        Task {
            busyCommands += 1
            defer { busyCommands -= 1 }
            let answer = await ServiceClient.send(Request(.serviceAction, target: kind), timeoutSec: 30)
            report(answer)
            if kind == "clear_stats", let answer, answer.error == nil {
                // The generation first: a statistics request still on the wire must not refill these
                // with the data the service just dropped. Then the data and the `*At`/`*Key` bookkeeping
                // go, or the throttle would keep showing the cleared figures for up to 30 s — the screens
                // refetch (and find emptiness) instead.
                statsGeneration += 1
                main.stats = nil; main.statsKey = nil; main.statsAt = .distantPast
                main.dayStats = nil; main.dayStatsKey = nil; main.dayStatsAt = .distantPast
            }
            serversAt = .distantPast  // a subscription update changes the list itself
            await refresh()
        }
    }

    /// A settings change made outside the settings screen (the main screen's switches) goes as its own patch.
    private func sendSettings(_ json: String) {
        Task {
            report(await ServiceClient.send(Request(.saveSettings, json: json), timeoutSec: 10))
            serversAt = .distantPast
            await refresh()
        }
    }

    /// An engine error the dialog was closed on: the service forgets it (Android's `Errors.dismiss`). Quiet —
    /// closing a dialog must not busy the controls or write a command error; the next refresh re-shows a miss.
    private func dismissPendingError(_ id: Int64) {
        Task {
            _ = await ServiceClient.send(Request(.serviceAction, target: "error_dismiss:\(id)"), timeoutSec: 10)
        }
    }

    /// Every command's outcome lands in [commandError] the same way: the service's error translated at
    /// the boundary (its replies are internal English — "locked: …", "bad request", [ServiceError]),
    /// the generic "didn't answer" when it stayed quiet, and the note cleared once a command goes through.
    private func report(_ answer: Reply?) {
        commandError = answer == nil ? String(localized: "main.no.answer") : answer?.error.map { ServiceError.friendlyLogged($0) }
    }

    private func send(_ request: Request) {
        Task {
            busyCommands += 1
            defer { busyCommands -= 1 }
            // The list first: a server added a moment ago must be there when the service looks for it.
            // Cancelling [saving] stops only the debounce's sleep — a save already on the wire is awaited
            // inside [save]. And a list the service refused or never received aborts the command: running
            // it against a list the service doesn't have was never the deal.
            saving?.cancel()
            guard await save() else { return }
            let answer = await ServiceClient.send(request, timeoutSec: 40)
            report(answer)
            // The switch's "searching" only once the service accepted the connect — a refused request
            // (or a dead service) keeps the last phase, and the note above says why.
            if request.kind == .connect, let answer, answer.error == nil, main.phase != .searching { main.phase = .searching }
            serversAt = .distantPast
            await refresh()
        }
    }
}

struct RootView: View {
    var model: AppModel
    @Environment(\.openWindow) private var openWindow

    var body: some View {
        Group {
            if ProcessInfo.processInfo.environment["BORDERLESS_DEMO"] != nil {
                // Development: the screens with demo data, whatever the service's state — the update
                // banner's stand-in shows its ready form there too.
                Shell(model: model.main, section: WindowShot.section, update: MainModel.demoUpdate)
            } else {
            if let update = model.serviceSetup {
                ServiceSetupView(update: update) { Task { await model.refresh() } }
            } else {
            switch model.service {
            case .running, .unreachable:
                // Installed but not answering: the screens stay up on the last known state (the state line
                // carries the "didn't answer" note via the model's commandError) — with a repair
                // path this time (a dead daemon can't revive itself).
                Shell(model: model.main, menus: model.windowMenus, update: model.update,
                      repair: model.service == .unreachable ? { model.repairService() } : nil,
                      repairing: model.repairingService)
            case .checking, .missing, .outdated:
                // .missing / .outdated always come with serviceSetup latched above; anything else is the
                // blank first moment before the first status answer.
                Palette.background
            }
            }
            }
        }
        .onAppear {
            model.openWindow = openWindow
            NavigationProbe.runIfAsked(model.main)
        }
        .onReceive(NotificationCenter.default.publisher(for: NSApplication.didBecomeActiveNotification)) { _ in
            // Android's app-open moment: refresh, and let the engine rescan what went stale meanwhile.
            model.scanOpen()
            Task { await model.refresh() }
        }
    }
}
