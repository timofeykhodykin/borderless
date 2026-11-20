import AppKit
import Shared
import UserNotifications

/// System notifications through UNUserNotificationCenter (ruling R17; Android `service/Notifications.kt`).
/// The permission is asked in context — the first post asks macOS, never the app's start.
///
/// What is posted follows the "Notification" setting (`notifyMode` of the wire `settings` JSON,
/// Android `NotifyMode`): OFF posts nothing; UNCLEAR posts when traffic stops going through a server —
/// the states Android keeps its status notification up for (searching, direct, paused, no network,
/// limited, and a hand-picked server that stopped answering); ALWAYS posts on every state change.
/// Android's is one persistent notification that updates in place and leaves on a healthy state; the
/// Mac analogue is a banner on the edge into such a state — the same state doesn't banner twice, a
/// different state tells again, and coming back to a bad state after a healthy one tells once more.
///
/// Update-ready and service-crash posts are events, not the status icon's job — they post whatever the
/// mode is (Android's update channel is likewise separate).
@MainActor
enum Notifications {
    /// The title of the state the last post was about: a new post is made only for a different one
    /// (Android's `dismissed` — a swiped-away state is not told again either, and the Mac's banners
    /// dismiss themselves anyway).
    private static var lastState: String?
    /// Crashes and updates already posted, so a poll re-reading the same facts doesn't banner again
    /// (the pending error stays queued until the user dismisses its dialog).
    private static var postedCrashes: Set<Int64> = []
    private static var postedUpdates: Set<String> = []
    /// The permission answer once known; nil = not asked yet.
    private static var permitted: Bool?
    private static let delegate = NotificationDelegate()

    /// The development modes (snapshots, the icon render, a terminal request, the demo) never post:
    /// they run with made-up data or no service at all.
    private static let devMode = ["BORDERLESS_DEMO", "BORDERLESS_SNAPSHOT", "BORDERLESS_REQUEST",
                                  "BORDERLESS_ICON", "BORDERLESS_WINDOW_SHOT", "BORDERLESS_PANEL_SHOT"]
        .contains { ProcessInfo.processInfo.environment[$0] != nil }

    /// Once at start: the delegate that lands a notification's tap in the app (the window opens, like
    /// the panel's "Open Border(less)").
    static func install() {
        guard !devMode else { return }
        UNUserNotificationCenter.current().delegate = delegate
    }

    /// The tunnel's state for the "Notification" setting, called on each engine answer
    /// ([phase] as shown, [unanswered], [noServers] and [paused] from `EngineStatus`,
    /// [connectedCountry] the connected server's country name — Android names the country in
    /// `notif_connected`, not the server). Only a real edge posts.
    static func state(phase: Phase, unanswered: Bool, noServers: Bool, paused: Bool,
                      connectedCountry: String?, mode: NotifyMode) {
        guard !devMode else { return }
        let title = stateTitle(phase: phase, unanswered: unanswered, noServers: noServers, paused: paused,
                               connectedCountry: connectedCountry, mode: mode)
        guard let title else {
            // A quiet state (off, or connected under UNCLEAR): the marker goes — the NEXT bad
            // state is a new edge and posts, like Android's notification returning after it left.
            lastState = nil
            return
        }
        guard title != lastState else { return }
        lastState = title
        post(id: "state", title: title, body: nil)
    }

    /// The state post's title (Android `Notifications.title`), nil when the mode tells nothing.
    private static func stateTitle(phase: Phase, unanswered: Bool, noServers: Bool, paused: Bool,
                                   connectedCountry: String?, mode: NotifyMode) -> String? {
        // The setting's own gate first (Android `NotifyMode.OFF`): nothing at all while it is off.
        guard mode != .off else { return nil }
        switch phase {
        case .off:
            // A quiet tunnel is told about in no mode — also not to an "always" user.
            return nil
        case .connected:
            if unanswered { return L("status_unanswered") }
            // Connected is only an ALWAYS state — UNCLEAR is "while not through a server".
            guard mode == .always else { return nil }
            if let country = connectedCountry, !country.isEmpty { return L("notif_connected", country) }
            return L("notif_connected_plain")
        case .searching:
            return L("notif_searching")
        case .direct:
            return noServers ? L("notif_no_servers") : L("notif_direct")
        case .paused:
            return noServers ? L("notif_no_servers_paused") : L("notif_paused")
        case .noNetwork:
            return L("status_no_network")
        case .limited:
            return paused ? L("notif_limited_paused") : L("notif_limited")
        }
    }

    /// The service's queued failures meant for the user (`EngineStatus.pendingErrors`): a
    /// crash-flagged one gets a banner — the window's error dialog only reaches a user who opens the
    /// window, and a dead daemon is exactly when nobody is looking (ruling R16).
    static func crashes(_ errors: [EngineStatus.PendingError]?) {
        guard !devMode else { return }
        for error in errors ?? [] where error.crash && !postedCrashes.contains(error.id) {
            postedCrashes.insert(error.id)
            post(id: "crash-\(error.id)", title: L("err_crash_title"), body: error.message)
        }
    }

    /// `engine.update` on each status answer: state "ready" = the staged build is downloaded and
    /// checked and waits for the user's install tap (PLAN §2). Posts once per staged build; nil or
    /// any other state posts nothing, so an old service without the field never fires this.
    static func update(_ update: EngineStatus.UpdateStatus?) {
        guard !devMode, let update, update.state == "ready" else { return }
        updateReady(version: update.version ?? "?", at: update.readyAt)
    }

    /// The "update ready" post itself — the seam the updater's own path (or a manual trigger) calls
    /// when a staged build is confirmed, outside the status poll. The wording is Android's update
    /// channel's own (`update_notif_*`), not the in-app install prompt's.
    static func updateReady(version: String, at readyAt: Int64? = nil) {
        let key = "\(version)@\(readyAt ?? 0)"
        guard !devMode, !postedUpdates.contains(key) else { return }
        postedUpdates.insert(key)
        post(id: "update-\(key)", title: L("update_available", version), body: L("update_notif_ready"))
    }

    /// Another tunnel carries the traffic (`NetworkStatus.otherTunnel` — another tunnel app, not ours):
    /// the service refuses to start over it, and a user looking elsewhere never learns why connecting
    /// does nothing. One banner per tunnel, only while notifications aren't OFF — it is the same
    /// "not through a server" class the state post covers; the name gone is a quiet state again.
    static func foreignTunnel(_ name: String?, mode: NotifyMode) {
        guard !devMode else { return }
        guard let name, !name.isEmpty, mode != .off else {
            foreignSeen = nil
            return
        }
        guard name != foreignSeen else { return }
        foreignSeen = name
        post(id: "foreign-tunnel", title: macText("mac.notif.foreign_tunnel", "Another tunnel is on"),
             body: String(format: macText("mac.notif.foreign_tunnel_sub",
                                          "%@ carries the traffic — Border(less) can't run its tunnel over it."), name))
    }

    /// The foreign tunnel the last banner was about (nil = traffic is ours again, the next one tells anew).
    private static var foreignSeen: String?

    /// One banner: asks the permission first (in context — the thing wanting to post is the context),
    /// then adds the request. Silent, like Android's status notification.
    private static func post(id: String, title: String, body: String?) {
        Task {
            guard await ask() else { return }
            let content = UNMutableNotificationContent()
            content.title = title
            if let body, !body.isEmpty { content.body = body }
            let request = UNNotificationRequest(identifier: id, content: content, trigger: nil)
            // A notification the center still shows is replaced (the "state" id), one already
            // delivered and gone just posts again — both are what the dedup above means.
            try? await UNUserNotificationCenter.current().add(request)
        }
    }

    /// The permission, asked once and remembered: the first post asks macOS, a denied answer isn't
    /// asked again (the system's own settings panel is where the user can change it back).
    private static func ask() async -> Bool {
        if let permitted { return permitted }
        let center = UNUserNotificationCenter.current()
        let settings = await center.notificationSettings()
        switch settings.authorizationStatus {
        case .authorized, .provisional, .ephemeral:
            permitted = true
        case .denied:
            permitted = false
        case .notDetermined:
            permitted = (try? await center.requestAuthorization(options: [.alert])) ?? false
        @unknown default:
            permitted = false
        }
        return permitted ?? false
    }
}

/// What a notification's tap does: the app comes forward and the window opens — the update banner
/// with its Install, the crash's error dialog, the state it was about. `willPresent` shows the
/// banner even while the app is frontmost (Android's notification is always visible too).
final class NotificationDelegate: NSObject, UNUserNotificationCenterDelegate {
    func userNotificationCenter(_ center: UNUserNotificationCenter,
                                didReceive response: UNNotificationResponse) async {
        await MainActor.run {
            NSApp.setActivationPolicy(.regular)
            NSApp.activate()
            AppModel.shared?.openWindow?.callAsFunction(id: "main")
        }
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter,
                                willPresent notification: UNNotification) async -> UNNotificationPresentationOptions {
        [.banner]
    }
}
