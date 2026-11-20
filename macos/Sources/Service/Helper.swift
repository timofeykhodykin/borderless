import Foundation
import Shared

/// The helper (`borderless-core -helper`): the service's own network work — HTTP for checks, subscriptions and lists,
/// probes of servers, local proxies through a server — in one unprivileged sandboxed process, every connection bound
/// to the physical interface. The shared engine talks to it in JSON lines (`MacEngine.kt`): requests on its stdin,
/// answers on its fd 3, matched by the request's id. Started on first use and again after it ended.
final class HelperProcess: @unchecked Sendable {
    private final class Waiter {
        let done = DispatchSemaphore(value: 0)
        var reply: String?
    }

    private let lock = NSLock()
    private var pid: pid_t = 0
    /// The pid the reaper thread last collected (under [lock]): after a reap the number can be another process's,
    /// so the watchdog below never kills one it recorded.
    private var reaped: pid_t = 0
    private var input: Int32 = -1
    private var lifeline: Int32 = -1
    private var waiting: [Int64: Waiter] = [:]
    /// One writer at a time: two interleaved `write`s to the requests pipe corrupt the JSON lines (the pipe's
    /// atomicity ends at 512 bytes, and probe configs are kilobytes; the engine runs probes concurrently).
    private let sendLock = NSLock()

    /// One request (a JSON object with an "id"), its answer; a failure answer when the helper can't be had.
    func request(_ json: String) -> String {
        guard let id = Self.id(of: Data(json.utf8)) else { return Self.failure(0, "bad request") }
        let waiter = Waiter()
        lock.lock()
        if input < 0, let problem = startLocked() {
            lock.unlock()
            return Self.failure(id, problem)
        }
        waiting[id] = waiter
        // Our own descriptor for the write: [stop] and a helper ending close [input] at any moment, and the
        // recycled number could then take the command bytes to a different file entirely.
        let fd = fcntl(input, F_DUPFD_CLOEXEC, 0)
        let helperPid = pid
        lock.unlock()
        guard fd >= 0 else {
            forget(id)
            return Self.failure(id, "the helper is gone")
        }
        sendLock.lock()
        let sent = Wire.send(Data((json + "\n").utf8), to: fd)
        sendLock.unlock()
        close(fd)
        guard sent else {
            forget(id)
            return Self.failure(id, "the helper is gone")
        }
        // Long enough for a slow download (the request's own timeouts end it sooner).
        if waiter.done.wait(timeout: .now() + 180) == .timedOut {
            forget(id)
            // A live but unresponsive helper would wedge every probe, download and proxy for good (each request
            // then burns its 180 s): end it; its other waiters fail through the end path and the next request
            // starts a fresh helper. Only the very process this request went to ([pid] may already be a fresh
            // helper) and only while the reaper hasn't collected it — after that the number can be anyone's.
            // The reaper's waitpid finishes before it can take this lock, so a child collected in that gap
            // reads as live while its number may already serve another process; the same WNOHANG waitpid as
            // [stop]'s closes it (ECHILD: the reaper won).
            lock.lock()
            var stuck: pid_t = 0
            if pid == helperPid, pid != reaped {
                var r = waitpid(pid, nil, WNOHANG)
                while r < 0 && errno == EINTR { r = waitpid(pid, nil, WNOHANG) }
                if r == pid || (r < 0 && errno == ECHILD) { reaped = pid }
                if reaped != pid { stuck = pid }
            }
            if stuck > 0 {
                logd("helper", "didn't answer in 180 s: killing it (pid \(stuck))")
                kill(stuck, SIGKILL)
            }
            lock.unlock()
            return Self.failure(id, "the helper didn't answer", kind: "unreachable")
        }
        return waiter.reply ?? Self.failure(id, "the helper stopped")
    }

    private func forget(_ id: Int64) {
        lock.lock()
        waiting[id] = nil
        lock.unlock()
    }

    func stop() {
        lock.lock()
        let pid = self.pid
        if lifeline >= 0 { close(lifeline) }
        if input >= 0 { close(input) }
        lifeline = -1
        input = -1
        self.pid = 0
        // A request in flight must not sit out its 180 s: the same failure the end-of-stream path gives it.
        let left = waiting
        waiting.removeAll()
        // The same recycled-pid rule as the watchdog's: the reaper records a collected pid under this
        // lock, but its waitpid finishes before it can take it — a pid reaped in that gap reads as live
        // while the number may already serve another process. A WNOHANG waitpid closes the gap (ECHILD:
        // the reaper won); only what is still our child gets the signal.
        if pid > 0, pid != reaped {
            var r = waitpid(pid, nil, WNOHANG)
            while r < 0 && errno == EINTR { r = waitpid(pid, nil, WNOHANG) }
            if r == pid || (r < 0 && errno == ECHILD) { reaped = pid }
            if reaped != pid { kill(pid, SIGTERM) }
        }
        lock.unlock()
        for (id, w) in left {
            w.reply = Self.failure(id, "the helper stopped")
            w.done.signal()
        }
    }

    /// The live helper's pid while one runs (the energy probe reads its `proc_pid_rusage`; nil while down).
    var currentPid: pid_t? {
        lock.lock()
        defer { lock.unlock() }
        return pid > 0 ? pid : nil
    }

    /// Development (`--engine-check`): this core binary, run as the current user without the sandbox.
    nonisolated(unsafe) static var devCore: String?

    private func startLocked() -> String? {
        var argv: [String]
        if let dev = Self.devCore {
            argv = [dev, "-helper"]
        } else {
            guard let account = CoreAccount.lookup() else { return "the core's account is missing (reinstall the service)" }
            guard let path = CoreProcess.installedPath() else { return "the core is missing (reinstall the service)" }
            argv = CoreProcess.sandboxed(path: path) + ["-helper", "-uid", "\(account.uid)", "-gid", "\(account.gid)", "-lifeline", "4"]
        }
        var req: [Int32] = [-1, -1], rep: [Int32] = [-1, -1], out: [Int32] = [-1, -1], life: [Int32] = [-1, -1]
        guard pipe(&req) == 0, pipe(&rep) == 0, pipe(&out) == 0, pipe(&life) == 0 else {
            // A late failure leaves the ends the earlier `pipe`s opened.
            for fd in req + rep + out + life where fd >= 0 { close(fd) }
            return "pipes: errno \(errno)"
        }
        for fd in [req[1], rep[0], out[0], life[1]] { _ = fcntl(fd, F_SETFD, FD_CLOEXEC) }
        // The same lists dir the main core gets: a probe or proxy config referencing a geo category fails inside
        // the helper without it.
        let env = ["XRAY_LOCATION_ASSET=\(ServicePaths.listsDir)", "PATH=/usr/bin:/bin"]
        let spawned = CoreProcess.spawn(argv: argv, env: env,
                                        fds: [(req[0], 0), (out[1], 1), (out[1], 2), (rep[1], 3), (life[0], 4)])
        close(req[0]); close(out[1]); close(rep[1]); close(life[0])
        guard case .success(let child) = spawned else {
            close(req[1]); close(rep[0]); close(out[0]); close(life[1])
            if case .failure(let e) = spawned { return "\(e)" }
            return "spawn helper"
        }
        pid = child
        // [reaped] described the last helper's end: left standing it could equal this one's (recycled)
        // pid and shield it from the watchdog forever.
        reaped = 0
        input = req[1]
        lifeline = life[1]
        let replies = rep[0], logs = out[0]
        Thread.detachNewThread { [weak self] in self?.readReplies(replies, from: child) }
        Thread.detachNewThread {
            Self.readLines(logs, cap: Self.lineCap) { line in logd("helper", line) }
        }
        Thread.detachNewThread { [weak self] in
            var status: Int32 = 0
            while waitpid(child, &status, 0) < 0 && errno == EINTR {}
            // The watchdog kills only a pid this hasn't recorded: afterwards the number can be anyone's.
            self?.lock.lock()
            self?.reaped = child
            self?.lock.unlock()
            logd("helper", "ended: status \(status)")
        }
        logd("helper", "started (pid \(child))")
        return nil
    }

    /// The most [readLines] keeps of a log line that has no newline yet (replies get far more — see
    /// [readReplies]).
    private static let lineCap = 256 << 10

    /// Lines from [fd], like [CoreProcess.readLines] but bounded by [cap]: a line that never ends (a broken helper
    /// sending more than the cap without a newline) can't grow the buffer forever — the rest of that line is
    /// dropped, logged once. POSIX `read`, not `FileHandle.availableData` — a read error there is an uncatchable
    /// exception that would take the service down; here it is just the stream's end. Closes [fd] at the end.
    private static func readLines(_ fd: Int32, cap: Int, _ handle: (String) -> Void) {
        defer { close(fd) }
        var buffer = Data()
        var chunk = [UInt8](repeating: 0, count: 65536)
        var dropping = false, warned = false
        while true {
            let n = read(fd, &chunk, chunk.count)
            if n < 0, errno == EINTR { continue }
            if n <= 0 { break }
            var piece = chunk[0..<n]
            if dropping {
                // Skip to the newline ending the over-long line; whatever follows is a fresh line.
                guard let nl = piece.firstIndex(of: 0x0A) else { continue }
                piece = piece[piece.index(after: nl)...]
                dropping = false
            }
            buffer.append(contentsOf: piece)
            for line in Wire.lines(&buffer) { handle(String(decoding: line, as: UTF8.self)) }
            if buffer.count > cap {
                if !warned { warned = true; logd("helper", "a line over \(cap >> 10) KB without an end: dropping it") }
                buffer.removeAll(keepingCapacity: true)
                dropping = true
            }
        }
    }

    private func readReplies(_ fd: Int32, from child: pid_t) {
        // Replies carry whole downloads (an `http` answer embeds its body base64 — callers ask for up to
        // ~10 MB): the log reader's cap would drop such an answer whole, the waiter would sit out its
        // 180 s and the watchdog would kill the helper under everyone else's requests.
        Self.readLines(fd, cap: Wire.maxLine) { [weak self] line in
            guard let self, let id = Self.id(of: Data(line.utf8)) else { return }
            self.lock.lock()
            let waiter = self.waiting.removeValue(forKey: id)
            self.lock.unlock()
            waiter?.reply = line
            waiter?.done.signal()
        }
        // Ended: whoever still waits gets a failure; the next request starts a new helper.
        lock.lock()
        let left = waiting
        waiting.removeAll()
        if pid == child {
            if input >= 0 { close(input) }
            if lifeline >= 0 { close(lifeline) }
            input = -1
            lifeline = -1
            pid = 0
        }
        lock.unlock()
        for (id, w) in left {
            w.reply = Self.failure(id, "the helper stopped")
            w.done.signal()
        }
    }

    private static func id(of data: Data) -> Int64? {
        guard let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return nil }
        return (object["id"] as? NSNumber)?.int64Value
    }

    private static func failure(_ id: Int64, _ text: String, kind: String = "other") -> String {
        let object: [String: Any] = ["id": id, "error": text, "kind": kind]
        let data = (try? JSONSerialization.data(withJSONObject: object)) ?? Data("{}".utf8)
        return String(decoding: data, as: UTF8.self)
    }
}
