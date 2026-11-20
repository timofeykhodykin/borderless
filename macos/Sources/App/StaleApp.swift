import AppKit
import Foundation
import Security
import Shared

/// Detects a process that keeps running a bundle after the bundle was replaced on disk: the menu-bar app never
/// quits by itself, and `open` on the new bundle only reactivates the old process instead of starting the new
/// file. The running image's code-directory hash is then not the file's — and the service, which checks every
/// peer's signature, refuses it ("peer's signature can't be checked"), so the app would sit on the setup page
/// forever installing over and over. Seen 2026-11-06: a bundle replaced under a three-day-old instance.
@MainActor
enum StaleApp {
    /// The last verdict and when it was computed: a full SecStaticCode validation of the bundle is far
    /// too heavy for the refresh loop's 1–3 s pace, and the bundle can't be replaced twice in seconds,
    /// so a verdict that fresh still stands.
    private static var checked: (stale: Bool, at: Date)?

    /// The running image's code-directory hash is not the bundle's on disk. A bundle without a readable
    /// signature — half-copied or absent — is never relaunched into.
    static func replacedOnDisk() -> Bool {
        if let checked, Date().timeIntervalSince(checked.at) < 10 { return checked.stale }
        let stale = compareHashes()
        checked = (stale, Date())
        return stale
    }

    private static func compareHashes() -> Bool {
        var staticCode: SecStaticCode?
        guard SecStaticCodeCreateWithPath(Bundle.main.bundleURL as CFURL, [], &staticCode) == errSecSuccess,
              let staticCode, let disk = Signing.cdhash(of: staticCode) else { return false }
        // An image whose own signature can't be checked fails the service's peer check too — relaunch it.
        guard let running = selfCDHash() else { return true }
        return running != disk
    }

    /// Ends this process and opens the on-disk bundle once it is gone (`open` reactivates a still-running
    /// instance, so a detached shell waits on our pid first). At most once a minute, so a misfiring check
    /// can't spin a relaunch loop.
    static func relaunch() {
        let now = Date().timeIntervalSince1970
        guard now - UserDefaults.standard.double(forKey: "staleRelaunchAt") > 60 else { return }
        UserDefaults.standard.set(now, forKey: "staleRelaunchAt")
        let task = Process()
        task.executableURL = URL(fileURLWithPath: "/bin/sh")
        // `open`, never `-n`: a forced new instance races with any other launch of the app (a click on it, a
        // login item, another `open`) and leaves two copies running — seen 2026-11-07. The bundle's path
        // goes as an argument (`"$1"`), not into the script's text: quoted into the command a "$" or a
        // backtick in it would still expand — a bundle path holding "$(cmd)" would run it as this user.
        // The wait on our pid is bounded (~60 s of 0.2 s ticks): if this process somehow stays, a stray
        // shell must not wait on it forever.
        task.arguments = ["-c",
                          "i=0; while kill -0 \(getpid()) 2>/dev/null; do i=$((i + 1)); [ $i -le 300 ] || exit 1; sleep 0.2; done; exec /usr/bin/open \"$1\"",
                          "borderless-stale", Bundle.main.bundlePath]
        do {
            try task.run()
        } catch {
            // No watcher, no relaunch: keep this process — quitting now would leave no running app at all.
            NSLog("Border(less): the stale-app relaunch watcher could not start: \(error)")
            return
        }
        AppDelegate.quit()
    }

    /// This process's code-directory hash (hex), nil when its signature can't be checked — as the service's
    /// `PeerCheck` does for its end of the socket.
    private static func selfCDHash() -> String? {
        var code: SecCode?
        guard SecCodeCopySelf([], &code) == errSecSuccess, let code else { return nil }
        guard SecCodeCheckValidity(code, [], nil) == errSecSuccess else { return nil }
        var staticCode: SecStaticCode?
        guard SecCodeCopyStaticCode(code, [], &staticCode) == errSecSuccess, let staticCode else { return nil }
        return Signing.cdhash(of: staticCode)
    }
}
