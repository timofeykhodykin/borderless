import Foundation
import Shared

/// The limited-network cores (`borderless-limited`, `native/limited/cmd/limitedd`): the "limited" servers' transport
/// and the "directly first" dialer, spawned like the main core — `sandbox-exec` with the same profile, dropped to
/// the core's own account, a lifeline fd (its end-of-file means this service is gone). The shared engine holds them
/// by handle: [spawn] gives it one, [send] writes a command line to the process's stdin, [read] returns the next
/// line of its merged stdout+stderr (the event protocol and, with `-verbose`, its log), [stop] ends it.
/// Several may run at once (the connected core, a throwaway probe, the dialer). Their start/end lines go to both
/// logs through [logd] — the app's detailed log sees a limited core's lifecycle like the main core's.
final class LimitedProcess: @unchecked Sendable {
    private final class Entry: @unchecked Sendable {
        let pid: pid_t
        let input: Int32
        let lifeline: Int32
        /// Lines read so far, or ended; [lines] wakes a reader waiting for one.
        let lines = DispatchSemaphore(value: 0)
        let lock = NSLock()
        var queue: [String] = []
        var ended = false
        var exited = false
        /// The reader thread reached the stream's end: after this no line can still arrive, so an empty
        /// [queue] means every last line was offered to a reader ([exited] alone can't tell that).
        var readerDone = false
        /// [input] was closed (a stop, or the reaper cleaning up a self-ended core): a send after it
        /// would write into a recycled descriptor.
        var inputClosed = false
        /// Same for [lifeline], so a stop and the reaper's cleanup can't close it twice.
        var lifelineClosed = false
        /// Output was dropped once for [maxQueue] (the marker line is in the queue): drop silently from then on.
        var dropped = false

        /// The most output kept for a lagging reader: a core flooding its verbose log can't grow the queue
        /// without end — further lines are dropped and the reader learns it from a marker line.
        static let maxQueue = 10_000

        init(pid: pid_t, input: Int32, lifeline: Int32) {
            self.pid = pid
            self.input = input
            self.lifeline = lifeline
        }

        func push(_ line: String) {
            lock.lock()
            if queue.count < Entry.maxQueue {
                queue.append(line)
            } else if !dropped {
                dropped = true
                queue.append("… output dropped")
            } else {
                lock.unlock()
                return
            }
            lock.unlock()
            lines.signal()
        }

        func end() {
            lock.lock()
            ended = true
            lock.unlock()
            lines.signal()
        }

        /// Our ends of its pipes, each closed exactly once — called under [lock] (where [send] writes:
        /// a send in flight sees [inputClosed] and skips the descriptor).
        func closeEnds() {
            if !inputClosed { close(input); inputClosed = true }
            if !lifelineClosed { close(lifeline); lifelineClosed = true }
        }

        /// [sig] to the child only while it is certainly still ours — called under [lock]: the reaper
        /// marks [exited] under it, but its waitpid finishes before it can take the lock, so a child
        /// already collected in that gap reads as live while its number may already serve another
        /// process. A WNOHANG waitpid closes the gap: == [pid] reaps it here (the reaper's own waitpid
        /// then answers ECHILD, which is fine), ECHILD means the reaper won; EINTR is retried, never
        /// taken as "reaped".
        func signalUnlessReaped(_ sig: Int32) {
            if exited { return }
            var r = waitpid(pid, nil, WNOHANG)
            while r < 0 && errno == EINTR { r = waitpid(pid, nil, WNOHANG) }
            if r == pid || (r < 0 && errno == ECHILD) { exited = true }
            if !exited { kill(pid, sig) }
        }
    }

    private let lock = NSLock()
    private var next: Int32 = 0
    private var entries: [Int32: Entry] = [:]

    /// Whether the installed core is there and safe to run as root (the same check as [CoreProcess.installedPath]).
    var available: Bool { installedPath() != nil }

    /// Development (`--engine-check`): this binary, run as the current user without the sandbox or the account.
    nonisolated(unsafe) static var devBinary: String?

    /// The installed limited core (root-owned, path written by the install script); nil if missing or not safe.
    private func installedPath() -> String? {
        if let dev = Self.devBinary { return dev }
        guard let raw = try? String(contentsOfFile: ServicePaths.dataDir + "/limited", encoding: .utf8) else { return nil }
        let path = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard path.hasPrefix(ServicePaths.limitedPrefix), ScriptCheck.problem(path) == nil else { return nil }
        return path
    }

    /// Starts a core with the engine's arguments (the account, the lifeline and the binary are ours); its handle,
    /// or 0 when it couldn't start. [env] is added to the child's environment — secrets (passwords, the SOCKS
    /// credentials of the dialer) travel there, never in argv: on macOS any local user reads another process's
    /// command line (`ps auxww`), another account's environment not.
    func spawn(argv: [String], env: [String: String]) -> Int32 {
        guard let path = installedPath() else { logd("limited", "core binary missing or unsafe"); return 0 }
        var full = Self.devBinary != nil ? [path] + argv
            : CoreProcess.sandboxed(path: path) + argv
        if Self.devBinary == nil {
            guard let account = CoreAccount.lookup() else { logd("limited", "no core account"); return 0 }
            full += ["-uid", "\(account.uid)", "-gid", "\(account.gid)", "-lifeline", "4"]
        }
        var cmd: [Int32] = [0, 0], out: [Int32] = [0, 0], life: [Int32] = [0, 0]
        guard pipe(&cmd) == 0 else { return 0 }
        guard pipe(&out) == 0 else { close(cmd[0]); close(cmd[1]); return 0 }
        guard pipe(&life) == 0 else { close(cmd[0]); close(cmd[1]); close(out[0]); close(out[1]); return 0 }
        for fd in [cmd[1], out[0], life[1]] { _ = fcntl(fd, F_SETFD, FD_CLOEXEC) }
        let spawned = CoreProcess.spawn(argv: full, env: ["PATH=/usr/bin:/bin"] + env.map { "\($0.key)=\($0.value)" },
                                        fds: [(cmd[0], 0), (out[1], 1), (out[1], 2), (life[0], 4)])
        close(cmd[0]); close(out[1]); close(life[0])
        guard case .success(let child) = spawned else {
            close(cmd[1]); close(out[0]); close(life[1])
            if case .failure(let e) = spawned { logd("limited", "spawn failed: \(e)") }
            return 0
        }
        let outRead = out[0]
        let entry = Entry(pid: child, input: cmd[1], lifeline: life[1])
        lock.lock()
        next += 1
        let handle = next
        entries[handle] = entry
        lock.unlock()
        Thread.detachNewThread {
            CoreProcess.readLines(outRead) { line in entry.push(line) }
            entry.lock.lock()
            entry.readerDone = true
            entry.lock.unlock()
            entry.end()
        }
        Thread.detachNewThread {
            var status: Int32 = 0
            while waitpid(child, &status, 0) < 0 && errno == EINTR {}
            entry.lock.lock()
            entry.exited = true
            entry.closeEnds()
            entry.lock.unlock()
            entry.end()
            logd("limited", "core \(handle) ended: status \(status)")
            // A core that ended by itself gets no [stop] from the engine (its reader just sees the
            // stream's end and drops the handle), so without this the entry and its pipe ends would
            // leak for the life of the service. The entry is dropped once the reader is done pushing
            // and its last lines were taken (a [stop] removes it sooner — this thread then exits at
            // once), or after a minute even if nothing ever drained it.
            for _ in 0..<600 {
                usleep(100_000)
                self.lock.lock()
                let gone = self.entries[handle] == nil
                self.lock.unlock()
                if gone { return }
                entry.lock.lock()
                let drained = entry.readerDone && entry.queue.isEmpty
                entry.lock.unlock()
                if drained { break }
            }
            self.lock.lock()
            self.entries.removeValue(forKey: handle)
            self.lock.unlock()
        }
        logd("limited", "core \(handle) started (pid \(child))")
        return handle
    }

    /// A command line to the core's stdin; a dead or unknown handle does nothing.
    func send(handle: Int32, line: String) {
        lock.lock()
        let entry = entries[handle]
        lock.unlock()
        guard let entry else { return }
        // Under the entry's lock, where stop closes the descriptor: a write after close could land on a
        // recycled fd of something unrelated.
        entry.lock.lock()
        defer { entry.lock.unlock() }
        guard !entry.inputClosed else { return }
        _ = Wire.send(Data((line + "\n").utf8), to: entry.input)
    }

    /// The next line of the core's output; "" when it ended (or the handle is unknown). Blocks until a line
    /// or the end — after the end every call returns "" at once.
    func read(handle: Int32) -> String {
        lock.lock()
        let entry = entries[handle]
        lock.unlock()
        guard let entry else { return "" }
        while true {
            entry.lock.lock()
            if !entry.queue.isEmpty {
                let line = entry.queue.removeFirst()
                entry.lock.unlock()
                return line
            }
            if entry.ended {
                entry.lock.unlock()
                return ""
            }
            entry.lock.unlock()
            entry.lines.wait()
        }
    }

    /// The pids of the cores that still run (the energy probe sums their `proc_pid_rusage` into the
    /// limited role; a dead-but-not-drained entry's pid stays out through its `exited` mark).
    var livePids: [pid_t] {
        lock.lock()
        let list = Array(entries.values)
        lock.unlock()
        return list.filter { e in e.lock.lock(); defer { e.lock.unlock() }; return !e.exited }.map(\.pid)
    }

    /// Whether the core still runs (false once it was reaped); an unknown handle is dead. Lets the engine
    /// not reuse the endpoint of a process that died before its reader noticed.
    func alive(handle: Int32) -> Bool {
        lock.lock()
        let entry = entries[handle]
        lock.unlock()
        guard let entry else { return false }
        entry.lock.lock()
        defer { entry.lock.unlock() }
        return !entry.exited
    }

    /// Ends the core: stdin and the lifeline closed (it stops by itself), TERM, then KILL after 3 s.
    func stop(handle: Int32) {
        lock.lock()
        let entry = entries.removeValue(forKey: handle)
        lock.unlock()
        guard let entry else { return }
        // Under the entry's lock, where send writes and the reaper marks: inputClosed makes a send in
        // flight skip the closed fd, and the signals go only to a pid still ours (see signalUnlessReaped).
        entry.lock.lock()
        entry.closeEnds()
        entry.signalUnlessReaped(SIGTERM)
        entry.lock.unlock()
        Thread.detachNewThread {
            usleep(3_000_000)
            entry.lock.lock()
            entry.signalUnlessReaped(SIGKILL)
            entry.lock.unlock()
        }
    }
}
