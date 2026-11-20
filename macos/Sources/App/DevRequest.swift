import AppKit
import Shared

/// `BORDERLESS_REQUEST=<request kind>`: sends that one request to the service as this app (so the
/// service's checks are the real ones), prints the reply as JSON and quits — for trying the service
/// from a terminal while there is no full UI.
///
/// Which kinds it accepts depends on the build: a debug build takes every `Request.Kind`, a release
/// build only the allowed requests below (`hello`, `status`, `connect`, `disconnect`, `restoreNetwork`,
/// `pingAll`). The mode runs with the app's identity and signature (`.prohibited`, invisible), and a
/// same-uid process can put any value into the environment: an unrestricted one would read every
/// stored credential link (`servers`), the settings, the zones, the events, the statistics or the
/// debug log out of the service — or silently rewrite them (`saveServers`, `saveSettings`,
/// `serviceAction`, `clearLog` …). What stays returns no stored data and only does what the app's own
/// buttons do anyway: state, the tunnel on/off, a ping run, the loud network reset. Debug builds keep
/// the whole set — this mode exists to exercise the service from a terminal.
@MainActor
enum DevRequest {
    static func runIfAsked() {
        guard let name = ProcessInfo.processInfo.environment["BORDERLESS_REQUEST"] else { return }
        guard let kind = Request.Kind(rawValue: name) else {
            print("unknown request \(name)")
            exit(2)
        }
        #if !DEBUG
        let allowed: Set<Request.Kind> = [.hello, .status, .connect, .disconnect, .restoreNetwork, .pingAll]
        guard allowed.contains(kind) else {
            print("request \(name) is not available in a release build")
            exit(2)
        }
        #endif
        NSApp.windows.forEach { $0.orderOut(nil) }
        Task {
            let reply = await ServiceClient.send(Request(kind), timeoutSec: 60)
            let encoder = JSONEncoder()
            encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
            if let reply, let data = try? encoder.encode(reply) {
                print(String(decoding: data, as: UTF8.self))
            } else {
                print("no answer from the service")
            }
            exit(reply == nil ? 1 : 0)
        }
    }
}
