import Foundation
import Shared

/// What the app knows about the system service.
enum ServiceStatus: Equatable {
    case checking
    /// Not installed (the launch daemon's plist isn't there).
    case missing
    /// Installed but not answering: busy (a disconnect can hold it for seconds), stuck or crashed. The app
    /// keeps its screens on the last known state instead of offering the install again.
    case unreachable
    /// Answers, but was installed from another version of the app (or refuses this copy of it): install
    /// again — the setup page words it as an "update" only when the app is the newer side.
    case outdated(installed: String)
    case running(Reply)
}

extension ServiceStatus {
    /// `.running` proves the service accepted this peer's signature — a stale image ([StaleApp]) can't
    /// answer as one, so the bundle's on-disk validation is skipped while this holds.
    var isRunning: Bool {
        if case .running = self { return true }
        return false
    }
}

enum ServiceClient {
    /// Asks the service for its state over the control socket (off the main thread).
    static func status() async -> ServiceStatus {
        guard let reply = await send(Request(.hello), timeoutSec: 4) else {
            // The daemon's plist tells "installed but quiet" (a busy service can hold a reply past the
            // timeout) from "not installed at all" (the install page).
            return FileManager.default.fileExists(atPath: ServicePaths.launchdPlist) ? .unreachable : .missing
        }
        // Only a peer refusal means "reinstall": the service sends it as "refused: …" (ControlSocket)
        // when it can't accept this copy's signature. Any other error on a hello — "bad request", a busy
        // service's own — is transient and must not flicker the update screen up for it.
        if let error = reply.error {
            return error.hasPrefix("refused:") ? .outdated(installed: reply.version) : .unreachable
        }
        // The installed binary decides, not the version string: a UI-only rebuild bumps the version without
        // touching the daemon (owner, 2026-11-07) — and a same-version rebuild with a changed daemon still has
        // to reach this screen or a stale daemon runs forever (the "locked: palette" case, 2026-11-09).
        if !sameServiceBuild() {
            return .outdated(installed: reply.version)
        }
        return .running(reply)
    }

    /// Is [a] a higher version than [b], compared by numbers ("0.10.0" outranks "0.9.0")? Only for the
    /// setup page's wording: a service newer than this app still gets reinstalled, it just isn't an "update".
    static func isNewer(_ a: String, than b: String) -> Bool {
        let pa = a.split(separator: ".").map { Int($0) ?? 0 }
        let pb = b.split(separator: ".").map { Int($0) ?? 0 }
        for i in 0..<max(pa.count, pb.count) {
            let x = i < pa.count ? pa[i] : 0, y = i < pb.count ? pb[i] : 0
            if x != y { return x > y }
        }
        return false
    }

    /// One request, one reply; nil when the service can't be reached or answers nonsense.
    static func send(_ request: Request, timeoutSec: Int) async -> Reply? {
        await Task.detached { () -> Reply? in
            // [timeoutSec] is the budget for the whole exchange: every read gets at most what is left
            // of it (below), so a service trickling a byte per slice can't hold this forever — and a
            // read interrupted by a signal retries rather than dying as "no answer".
            let deadline = Date().addingTimeInterval(TimeInterval(timeoutSec))
            guard let fd = connectToService(timeoutSec: timeoutSec) else { return nil }
            defer { close(fd) }
            guard let data = try? Wire.encode(request), Wire.send(data, to: fd) else { return nil }
            var buffer = Data()
            var chunk = [UInt8](repeating: 0, count: 65536)
            while buffer.count < Wire.maxLine {
                // The socket's per-read timeout is the deadline's remainder, recomputed each pass:
                // a read started late can't sit out a whole [timeoutSec] on top of the time spent.
                let left = deadline.timeIntervalSinceNow
                guard left > 0 else { return nil }
                var tv = timeval(tv_sec: Int(left), tv_usec: Int32((left - left.rounded(.down)) * 1_000_000))
                if tv.tv_sec == 0 && tv.tv_usec == 0 { tv.tv_usec = 1 }
                setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, socklen_t(MemoryLayout<timeval>.size))
                let n = read(fd, &chunk, chunk.count)
                if n < 0, errno == EINTR { continue }
                if n <= 0 { return nil }
                buffer.append(contentsOf: chunk[0..<n])
                // The first complete line that reads as a Reply, not simply the first line: the service
                // will later push `state` lines on this same connection (Shared/Service.swift), and one
                // arriving before the answer must not be mistaken for it. A line that doesn't decode is
                // dropped quietly — this side of the socket has no log to put it in.
                for line in Wire.lines(&buffer) {
                    if let reply = try? Wire.decode(Reply.self, line: line) {
                        return reply
                    }
                }
            }
            return nil
        }.value
    }

    /// The daemon installed in the system is the same build as the one inside this app bundle: the launchd job's
    /// program is `<binaryPrefix><16 hex of its cdhash>` (install-service.sh), matched against the bundled
    /// binary's own cdhash. No match (or no readable name — an old fixed-name install) = "changed". The plist
    /// half is read on every call (a reinstall while the app runs shows at once); the bundled binary's cdhash
    /// is computed once — it can't change under a running app, and `codesign` is too heavy for the poll's pace.
    // `nonisolated(unsafe)`: written once on the first call, only ever read afterwards — same pattern as
    // the other cached values in the service client.
    nonisolated(unsafe) private static var bundledCdhash: String?
    private static func sameServiceBuild() -> Bool {
        guard let plist = try? PropertyListSerialization.propertyList(
                from: Data(contentsOf: URL(fileURLWithPath: ServicePaths.launchdPlist)), format: nil) as? [String: Any],
              let args = plist["ProgramArguments"] as? [String],
              let program = args.first, program.hasPrefix(ServicePaths.binaryPrefix)
        else { return false }
        let installed = String(program.dropFirst(ServicePaths.binaryPrefix.count))
        guard installed.count == 16, installed.allSatisfy(\.isHexDigit) else { return false }
        let bundled = Bundle.main.bundleURL.appendingPathComponent("Contents/Library/Service/app.borderless.service").path
        if bundledCdhash == nil { bundledCdhash = Signing.cdhash(atPath: bundled) }
        return bundledCdhash?.hasPrefix(installed) == true
    }

    /// Undo our network changes: through the service when it answers, else with the standalone reset (password).
    @MainActor
    static func restoreNetwork() async -> Bool {
        if case .running = await status(),
           let reply = await send(Request(.restoreNetwork), timeoutSec: 30), reply.error == nil {
            return true
        }
        return await ServiceInstaller.resetNetwork() == .done
    }
}
