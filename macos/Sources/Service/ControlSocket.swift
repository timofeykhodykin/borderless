import Foundation
import Security
import Shared
@preconcurrency import SystemConfiguration

/// Kept alive for the life of the process.
nonisolated(unsafe) var signalSources: [DispatchSourceSignal] = []

/// The service's only way in: a Unix stream socket. Anyone on the Mac may connect (the file is 0666), so every
/// connection is checked before a single request is read: the peer must be the installed copy of the app (its
/// code-directory hash equals the one the install script wrote) run by the user at the console.
final class ControlSocket: @unchecked Sendable {
    enum Failure: Error { case socket(Int32), bind(Int32), listen(Int32) }

    private let path: String
    private let handle: @Sendable (Request) -> Reply
    /// The listening fd and the file it was bound to (dev + inode): the watchdog knows a deleted or replaced
    /// socket path by the file's identity changing, and stop()/rebind() swap the fd under the same lock.
    private let fdLock = NSLock()
    private var fd: Int32 = -1
    private var boundFile: (dev: dev_t, ino: ino_t)?
    private var watchdog: DispatchSourceTimer?
    private let queue = DispatchQueue(label: "control", attributes: .concurrent)
    /// At most this many clients at once; more are closed at once (nothing local can tie the service up).
    private let slots = DispatchSemaphore(value: 8)

    init(path: String, handle: @escaping @Sendable (Request) -> Reply) {
        self.path = path
        self.handle = handle
    }

    func start() throws {
        let listener = try Self.bindListener(path)
        fdLock.lock()
        fd = listener
        boundFile = Self.fileIdentity(path)
        fdLock.unlock()
        acceptLoop(on: listener)
        startWatchdog()
    }

    func stop() {
        watchdog?.cancel()
        watchdog = nil
        // -1 at once: a second stop() must not close a number some other fd may have been given since.
        fdLock.lock()
        let listener = fd
        fd = -1
        fdLock.unlock()
        if listener >= 0 { close(listener) }
        unlink(path)
    }

    /// Socket + bind + listen on [path], replacing whatever file sits there. Close-on-exec: helpers the service
    /// spawns (Process / posix_spawn) must not inherit the listener — a child holding it would keep the socket
    /// bound and unanswered after stop().
    private static func bindListener(_ path: String) throws -> Int32 {
        unlink(path)
        let fd = socket(AF_UNIX, SOCK_STREAM, 0)
        guard fd >= 0 else { throw Failure.socket(errno) }
        _ = fcntl(fd, F_SETFD, fcntl(fd, F_GETFD) | FD_CLOEXEC)
        var addr = unixAddress(path)
        let bound = withUnsafePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                bind(fd, $0, socklen_t(MemoryLayout<sockaddr_un>.size))
            }
        }
        guard bound == 0 else { let e = errno; close(fd); throw Failure.bind(e) }
        chmod(path, 0o666)
        guard listen(fd, 8) == 0 else { let e = errno; close(fd); throw Failure.listen(e) }
        return fd
    }

    /// dev + inode of the file at [path], nil when it is gone.
    private static func fileIdentity(_ path: String) -> (dev: dev_t, ino: ino_t)? {
        var st = stat()
        guard lstat(path, &st) == 0 else { return nil }
        return (st.st_dev, st.st_ino)
    }

    /// The accept loop for one listener fd (a rebind starts a fresh one for the new fd; the old ends when the
    /// swap makes its accept fail). Everything inside runs on this one thread — no locking there.
    private func acceptLoop(on listener: Int32) {
        Thread.detachNewThread { [self] in
            var accepted: [DispatchTime] = []
            while true {
                let client = accept(listener, nil, nil)
                if client < 0 {
                    // Only a dead listener ends the loop. A client that vanished before accept (ECONNABORTED) or a
                    // momentary fd shortage must not: one aborted connection used to kill this thread, and every
                    // later connection was then closed unanswered until the service restarted (found 2026-11-02).
                    switch errno {
                    case EINTR, ECONNABORTED, EPROTO: continue
                    case EMFILE, ENFILE, ENOMEM: Thread.sleep(forTimeInterval: 0.2); continue
                    case EBADF, ENOTSOCK, EINVAL:
                        // A watchdog rebind swaps the fd and closes this one: a quiet exit, not a dead service.
                        fdLock.lock(); let current = fd; fdLock.unlock()
                        if current == listener { log("control socket listener closed") }
                        return
                    default: continue
                    }
                }
                // Connect rate: the eight slots and the peer refusal already bound what a client can do — this
                // only keeps a connect storm from churning the worker queue.
                let now = DispatchTime.now()
                accepted.removeAll { now.uptimeNanoseconds - $0.uptimeNanoseconds > 1_000_000_000 }
                guard accepted.count < 32 else { close(client); continue }
                accepted.append(now)
                // Close-on-exec like the listener: a spawned helper that inherited a client fd would keep the
                // connection (and what the client wrote) alive past our close.
                _ = fcntl(client, F_SETFD, fcntl(client, F_GETFD) | FD_CLOEXEC)
                guard slots.wait(timeout: .now()) == .success else {
                    close(client)
                    continue
                }
                queue.async {
                    self.serve(client)
                    self.slots.signal()
                }
            }
        }
    }

    /// Every few seconds: the socket file is the app's only way in and nothing watches it — deleted or replaced
    /// while the service runs, the working listener stays deaf until launchd restarts the service.
    private func startWatchdog() {
        let timer = DispatchSource.makeTimerSource(queue: .global())
        timer.schedule(deadline: .now() + 5, repeating: 5)
        timer.setEventHandler { [weak self] in self?.checkSocketFile() }
        timer.resume()
        watchdog = timer
    }

    private func checkSocketFile() {
        fdLock.lock()
        let alive = fd >= 0, bound = boundFile
        fdLock.unlock()
        guard alive else { return }
        let now = Self.fileIdentity(path)
        if let now, let bound, now == bound { return }
        log("control socket path \(now == nil ? "deleted" : "replaced"); rebinding")
        rebind()
    }

    /// The path went away or points at another file: bind anew and swap the listener under the lock. The old
    /// accept thread's accept() then fails and it leaves — its EBADF branch compares against `fd` for this.
    private func rebind() {
        let listener: Int32
        do {
            listener = try Self.bindListener(path)
        } catch {
            log("control socket rebind failed: \(error)")
            return
        }
        fdLock.lock()
        guard fd >= 0 else {
            // stop() ran while we bound: undo the fresh file and leave.
            fdLock.unlock()
            close(listener)
            unlink(path)
            return
        }
        let old = fd
        fd = listener
        boundFile = Self.fileIdentity(path)
        fdLock.unlock()
        if old >= 0 {
            shutdown(old, SHUT_RDWR)
            close(old)
        }
        acceptLoop(on: listener)
    }

    private func serve(_ client: Int32) {
        defer { close(client) }
        var tv = timeval(tv_sec: 30, tv_usec: 0)
        setsockopt(client, SOL_SOCKET, SO_RCVTIMEO, &tv, socklen_t(MemoryLayout<timeval>.size))
        // A send deadline too: a trusted client that never reads would otherwise pin one of the eight slots for
        // good on a big reply (exportStats, the server list).
        setsockopt(client, SOL_SOCKET, SO_SNDTIMEO, &tv, socklen_t(MemoryLayout<timeval>.size))
        if let refusal = PeerCheck.refusal(client) {
            log("refused a client: \(refusal)")
            if let data = try? Wire.encode(Reply(version: borderlessVersion, tunnel: .off, error: "refused: \(refusal)")) {
                _ = Wire.send(data, to: client)
            }
            return
        }
        // The console-user check inside refusal vouched only for who sat at the screen at accept time; a
        // connection kept open across a fast-user switch would keep answering for whoever logged in next. The
        // peer's uid can't change — the console side is re-checked per request below.
        guard let peer = PeerCheck.peerUid(client) else {
            log("refused a client: no peer token")
            if let data = try? Wire.encode(
                Reply(version: borderlessVersion, tunnel: .off, error: "refused: no peer token")) {
                _ = Wire.send(data, to: client)
            }
            return
        }
        var buffer = Data()
        var chunk = [UInt8](repeating: 0, count: 65536)
        var interrupts = 0
        while true {
            let n = read(client, &chunk, chunk.count)
            // An interrupted read retries, like Wire.send's write — bounded, so a signal storm still ends it.
            if n < 0, errno == EINTR, interrupts < 8 { interrupts += 1; continue }
            if n <= 0 { return }
            buffer.append(contentsOf: chunk[0..<n])
            // The longest request is the server list (`saveServers`). Answer before closing: a silent
            // disconnect would only show up in the app as "the service didn't answer".
            if buffer.count > Wire.maxLine {
                if let data = try? Wire.encode(
                    Reply(version: borderlessVersion, tunnel: .off, error: "request too large")) {
                    _ = Wire.send(data, to: client)
                }
                return
            }
            for line in Wire.lines(&buffer) {
                // Someone else at the console now: this connection's trust ended with the switch (root stays
                // trusted — it could do anything anyway). Requests are rare, so asking the dynamic store per
                // line is fine.
                if peer != 0, peer != PeerCheck.consoleUid() {
                    log("console user changed; closing uid \(peer)'s connection")
                    if let data = try? Wire.encode(
                        Reply(version: borderlessVersion, tunnel: .off,
                              error: "refused: no longer the console user")) {
                        _ = Wire.send(data, to: client)
                    }
                    return
                }
                let reply: Reply
                if let request = try? Wire.decode(Request.self, line: line) {
                    reply = handle(request)
                } else {
                    reply = Reply(version: borderlessVersion, tunnel: .off, error: "bad request")
                }
                var data = try? Wire.encode(reply)
                if data?.count ?? 0 > Wire.maxLine {
                    // The client stops reading at maxLine: a longer reply arrives as silence, an error fits.
                    log("a reply over \(Wire.maxLine) bytes answered with an error")
                    data = try? Wire.encode(
                        Reply(version: borderlessVersion, tunnel: .off, error: "reply too large"))
                }
                guard let data, Wire.send(data, to: client) else { return }
            }
        }
    }
}

/// Who is on the other end of the socket.
enum PeerCheck {
    /// nil when the peer may give commands, else why not (for the service's own log; the app shows a generic text).
    static func refusal(_ fd: Int32) -> String? {
        // Only the user logged in at the screen (root itself is fine too: it could do anything anyway).
        guard let uid = peerUid(fd) else { return "no peer token" }
        if uid != 0 && uid != consoleUid() { return "uid \(uid) is not the console user" }

        var token = audit_token_t()
        var len = socklen_t(MemoryLayout<audit_token_t>.size)
        guard getsockopt(fd, SOL_LOCAL, LOCAL_PEERTOKEN, &token, &len) == 0 else { return "no peer token" }

        guard let trusted = try? String(contentsOfFile: ServicePaths.trustedClient, encoding: .utf8)
            .trimmingCharacters(in: .whitespacesAndNewlines), !trusted.isEmpty
        else { return "no trusted client recorded" }
        guard let cdhash = cdhash(of: token) else { return "peer's signature can't be checked" }
        let pid = auditTokenPid(token)
        if cdhash == trusted || signed(cdhash: cdhash, pid: pid) {
            // A trusted peer is the app: where it runs from is remembered so an update replaces it
            // where it actually is, not only in /Applications.
            Updater.noteAppPath(pid: pid)
            return nil
        }
        return "peer \(cdhash) is not the installed app"
    }

    /// A rebuilt app carries the owner's signature of its own cdhash in the bundle's `app.borderless.client-sig`
    /// xattr ([ClientTrust]; `build.sh` signs it with the routing-lists key when it's there). A valid signature is
    /// trusted like the hash recorded at install — and recorded over it, so later connections take the fast path
    /// and a UI-only rebuild needs no service reinstall (owner, 2026-11-07).
    static func signed(cdhash: String, pid: pid_t) -> Bool {
        var path = [CChar](repeating: 0, count: Int(MAXPATHLEN))
        guard proc_pidpath(pid, &path, UInt32(path.count)) > 0 else { return false }
        let exe = String(decoding: path.prefix { $0 != 0 }.map { UInt8(bitPattern: $0) }, as: UTF8.self)
        // The app runs as <bundle>/Contents/MacOS/<name>; a peer outside that shape has no bundle to check.
        guard let macos = exe.range(of: "/Contents/MacOS/") else { return false }
        let bundle = String(exe[..<macos.lowerBound])
        var raw = [UInt8](repeating: 0, count: 1024)
        let n = getxattr(bundle, ClientTrust.signatureXattr, &raw, raw.count, 0, 0)
        guard n > 0, let b64 = String(bytes: raw[..<n], encoding: .utf8),
              let signature = Data(base64Encoded: b64.trimmingCharacters(in: .whitespacesAndNewlines)),
              let spki = Data(base64Encoded: ClientTrust.publicKey), spki.count >= 65,
              let key = SecKeyCreateWithData(
                spki.suffix(65) as CFData,
                [kSecAttrKeyType: kSecAttrKeyTypeECSECPrimeRandom,
                 kSecAttrKeyClass: kSecAttrKeyClassPublic] as CFDictionary, nil)
        else { return false }
        let message = Data((ClientTrust.messagePrefix + cdhash).utf8)
        guard SecKeyVerifySignature(key, .ecdsaSignatureMessageX962SHA256, message as CFData,
                                    signature as CFData, nil) else { return false }
        log("trusted a signed client build \(cdhash.prefix(16))")
        try? (cdhash + "\n").write(toFile: ServicePaths.trustedClient, atomically: true, encoding: .utf8)
        return true
    }

    /// The peer's effective uid from its audit token; nil when the token can't be read (fail closed, like a refusal).
    static func peerUid(_ fd: Int32) -> uid_t? {
        var token = audit_token_t()
        var len = socklen_t(MemoryLayout<audit_token_t>.size)
        guard getsockopt(fd, SOL_LOCAL, LOCAL_PEERTOKEN, &token, &len) == 0 else { return nil }
        return auditTokenEuid(token)
    }

    /// `audit_token_to_pid` without linking libbsm: the pid is the token's sixth word.
    static func auditTokenPid(_ token: audit_token_t) -> pid_t {
        pid_t(token.val.5)
    }

    /// The peer's code-directory hash (hex) after a validity check of its code, by audit token (no pid reuse race).
    static func cdhash(of token: audit_token_t) -> String? {
        var token = token
        let tokenData = Data(bytes: &token, count: MemoryLayout<audit_token_t>.size)
        var code: SecCode?
        let attrs = [kSecGuestAttributeAudit: tokenData] as CFDictionary
        guard SecCodeCopyGuestWithAttributes(nil, attrs, [], &code) == errSecSuccess, let code else { return nil }
        guard SecCodeCheckValidity(code, [], nil) == errSecSuccess else { return nil }
        var staticCode: SecStaticCode?
        guard SecCodeCopyStaticCode(code, [], &staticCode) == errSecSuccess, let staticCode else { return nil }
        return Signing.cdhash(of: staticCode)
    }

    /// `audit_token_to_euid` without linking libbsm: the effective uid is the token's second word.
    static func auditTokenEuid(_ token: audit_token_t) -> uid_t {
        token.val.1
    }

    static func consoleUid() -> uid_t? {
        var uid: uid_t = 0
        guard SCDynamicStoreCopyConsoleUser(nil, &uid, nil) != nil else { return nil }
        return uid
    }
}

func log(_ line: String) {
    FileHandle.standardError.write(Data("\(Date()) \(line)\n".utf8))
    ServiceLog.wrote()
}

/// A line for both logs: the service's own (root-only, [log]) and, through the engine, the app's
/// detailed log — Android pipes the core's output into AppLog the same way. [tag] is the detailed
/// log's tag ("xray" / "helper" / "tunnel"); the service log gets it as "<tag>: <line>".
func logd(_ tag: String, _ line: String) {
    log("\(tag): \(line)")
    EngineBridge.debug(tag, line)
}

/// The service's own log: stderr goes into a root-only file in the data dir (launchd would drop it). Cut to its last
/// half when it passes 1 MB at a start. Read through the `log` request (trusted app only).
enum ServiceLog {
    static let path = ServicePaths.dataDir + "/service.log"

    static func open() {
        if let attrs = try? FileManager.default.attributesOfItem(atPath: path),
           let size = attrs[.size] as? Int, size > 1 << 20,
           let data = FileManager.default.contents(atPath: path) {
            try? AtomicFile.save(data.suffix(512 << 10), to: path)
        }
        let fd = Darwin.open(path, O_WRONLY | O_APPEND | O_CREAT | O_NOFOLLOW, 0o600)
        guard fd >= 0 else { return }
        dup2(fd, 2)
        Darwin.close(fd)
        log("service \(borderlessVersion) started, pid \(getpid())")
    }

    nonisolated(unsafe) private static var lines = 0
    private static let rotateLock = NSLock()

    /// Every 500 lines: past 4 MB the file moves to `service.log.1` (one old file kept) and a new one starts — a
    /// verbose core can't fill the disk.
    static func wrote() {
        rotateLock.lock()
        defer { rotateLock.unlock() }
        lines += 1
        guard lines % 500 == 0 else { return }
        var st = stat()
        guard fstat(2, &st) == 0, st.st_size > 4 << 20 else { return }
        _ = rename(path, path + ".1")
        let fd = Darwin.open(path, O_WRONLY | O_APPEND | O_CREAT | O_NOFOLLOW, 0o600)
        guard fd >= 0 else { return }
        dup2(fd, 2)
        Darwin.close(fd)
    }

    static func tail(_ lines: Int) -> [String] {
        // The file plus the rotated `.1` half: right after a rotation almost everything is in `.1`.
        // Only the end of each: the files can be several MB and `log` requests are frequent. The
        // throwing calls, not `availableData` — a read error there is an ObjC exception Swift can't catch.
        let text = [path + ".1", path].compactMap { p -> String? in
            guard let file = FileHandle(forReadingAtPath: p) else { return nil }
            defer { try? file.close() }
            let cap: UInt64 = 256 << 10
            guard let end = try? file.seekToEnd(),
                  (try? file.seek(toOffset: end > cap ? end - cap : 0)) != nil,
                  let data = try? file.readToEnd()
            else { return nil }
            return String(decoding: data, as: UTF8.self)
        }.joined(separator: "\n")
        return text.split(whereSeparator: \.isNewline).suffix(lines).map(String.init)
    }
}

/// The mark of a deliberate exit: [main]'s signal path and every early refusal writes it, and a start
/// removes it again. A start that finds no mark after a run that opened the log means the last service
/// died — a crash, a kill, power loss — and launchd restarted it silently: recorded once for the app
/// ([noteUnclean] → `ev_crashed`, like Android's crash file on the next start).
enum ExitMark {
    /// `<dataDir>/clean-exit`: "ok" = the last run exited deliberately, "unclean" = this boot's record of
    /// a dead run is still pending (an early exit carries it forward — a `--reset-network` run or a
    /// refused second instance ends before [noteUnclean] could run).
    private static let path = ServicePaths.dataDir + "/clean-exit"
    /// The crash report's name last told ([crashReport]): a later death by another cause must not blame
    /// the same report again.
    private static let reportedPath = ServicePaths.dataDir + "/crash-reported"
    /// Whether this boot's crash record is still to be told: [take] sets it, [noteUnclean] consumes it,
    /// and [mark] carries it back into the mark when the run ends first.
    nonisolated(unsafe) private static var pending = false

    /// How the last run ended; nil on the first ever start. Reads the mark's contents, then removes it —
    /// this run rewrites it only when it exits deliberately — and sets [pending] for [mark]/[noteUnclean].
    /// Must run before the service log's first line of this run lands (the "ran before" check is the
    /// log's existence).
    static func take() -> Bool? {
        let fm = FileManager.default
        let result: Bool?
        if fm.fileExists(atPath: path) {
            // The content decides; it must be read before the mark goes. A file that vanishes in between
            // reads as nil → not "ok" → the safe direction (a record, not a silent clean).
            result = (try? String(contentsOfFile: path, encoding: .utf8))?
                .trimmingCharacters(in: .whitespacesAndNewlines) == "ok"
            try? fm.removeItem(atPath: path)
        } else {
            // No mark: dead only when a run actually happened — every run writes the service log at start.
            result = fm.fileExists(atPath: ServiceLog.path) ? false : nil
        }
        pending = result == false
        return result
    }

    /// A deliberate exit (the signal path, an early refusal): a still-pending record is written back so
    /// the next start still tells it, everything else is a plain "clean".
    static func mark() {
        try? AtomicFile.save(Data((pending ? "unclean" : "ok").utf8), to: path)
    }

    /// A dead previous run, recorded where the app reads it: an `ev_crashed` event (Android's next-start
    /// crash surfacing), the details and the system's crash report in the detailed log. After
    /// `EngineBridge.setUp` — the events and the log are the engine's.
    static func noteUnclean() {
        pending = false
        EngineBridge.event("ev_crashed", kind: "ERROR")
        var detail = "the previous service run ended without a clean shutdown " +
            "(a crash, a kill or a power loss); launchd restarted it"
        if let report = crashReport() { detail += "\nsystem crash report: \(report)" }
        EngineBridge.debug("service", detail)
    }

    /// The newest crash report the system wrote for the service binary (`*.ips` under the system
    /// diagnostics folder — the native-crash record Android's historical exit reasons give): one
    /// compact line of it, once per report.
    private static func crashReport() -> String? {
        let dir = "/Library/Logs/DiagnosticReports"
        let fm = FileManager.default
        guard let all = try? fm.contentsOfDirectory(atPath: dir) else { return nil }
        let newest = all.filter { $0.hasPrefix("app.borderless.service-") && $0.hasSuffix(".ips") }
            .compactMap { name -> (String, Date)? in
                let p = dir + "/" + name
                return ((try? fm.attributesOfItem(atPath: p))?[.modificationDate] as? Date).map { (name, $0) }
            }.max { $0.1 < $1.1 }
        guard let (name, _) = newest,
              (try? String(contentsOfFile: reportedPath, encoding: .utf8))?
                .trimmingCharacters(in: .whitespacesAndNewlines) != name,
              let raw = try? String(contentsOfFile: dir + "/" + name, encoding: .utf8),
              let nl = raw.firstIndex(of: "\n"),
              let payload = try? JSONSerialization.jsonObject(
                    with: Data(raw[raw.index(after: nl)...].utf8)) as? [String: Any]
        else { return nil }
        // Remembered before the summary: a truncated or odd report is still told about once.
        try? name.write(toFile: reportedPath, atomically: true, encoding: .utf8)
        var out = name
        if let t = payload["captureTime"] as? String { out += " (\(t))" }
        if let e = payload["exception"] as? [String: Any] {
            let what = [e["type"], e["signal"]].compactMap { $0 as? String }.joined(separator: " ")
            if !what.isEmpty { out += ": \(what)" }
        }
        if let term = payload["termination"] as? [String: Any] {
            let why = [term["indicator"], term["namespace"]].compactMap { $0 as? String }.joined(separator: " ")
            if !why.isEmpty { out += " — \(why)" }
        }
        if let t = payload["faultingThread"] as? Int { out += ", thread \(t)" }
        return out
    }
}
