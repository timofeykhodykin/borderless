import BorderlessShared
import Foundation

/// The code shared with Android, for the app (`MacBridge` in `shared/`): reading what the user adds. The only file of
/// the app that imports the Kotlin module (its names — `Server`, `Strategy`, `Phase`… — would clash with ours).
enum SharedCode {
    /// Once at start: the bundled files (country names, zones), the UI language, our texts for its explanations.
    /// The language is a supplier, not a string: shared code asks `Host.language` per use (country names, number
    /// formats), and the pick can change live (`AppLanguage`, R10) — a value captured here would pin the launch
    /// language into every later switch.
    static func setUp() {
        MacBridge.shared.setUp(assets: Bundle.main.resourcePath ?? "", language: { currentLanguage() }) { key, args in
            Ls(key, args)
        }
    }

    /// The UI language *of the moment*: the app's own AppleLanguages entry — `applyLanguage` writes it live (a
    /// `-AppleLanguages` launch argument lands in the same lookup) — else the bundle's resolution of the system
    /// list. `preferredLocalizations` alone can't report a post-start switch, so the pick is read from defaults.
    static func currentLanguage() -> String {
        let pick = (UserDefaults.standard.object(forKey: "AppleLanguages") as? [String])?.first
        let tag = pick ?? Bundle.main.preferredLocalizations.first
        return tag.flatMap { t in ["en", "ru"].first { t.hasPrefix($0) } } ?? "en"
    }

    /// Decoded leniently (a missing list = empty, one bad entry = a problem of its own, not a failed whole):
    /// Swift's synthesized decoding ignores default values and one bad field fails the array it is in.
    struct Parsed: Decodable {
        var groups: [Group] = []
        var problems: [Problem] = []

        init() {}

        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            // `decode` + `try?` rather than `decodeIfPresent`: a `groups`/`problems` that is present
            // but the wrong type must not take the rest down with it — the same leniency as the
            // per-entry [Lenient] below.
            let raw = (try? c.decode([Lenient<Group>].self, forKey: .groups)) ?? []
            groups = raw.compactMap(\.value)
            problems = (try? c.decode([Problem].self, forKey: .problems)) ?? []
            // Every entry that didn't decode is a dropped server or group: told like a damaged link, never silently.
            let dropped = raw.filter { $0.value == nil }.count + groups.reduce(0) { $0 + $1.dropped }
            for _ in 0..<dropped { problems.append(Problem(item: "?", text: L("imp_damaged"))) }
        }

        enum CodingKeys: String, CodingKey { case groups, problems }
    }

    struct Group: Decodable {
        var name: String?
        var `default`: Bool?
        var servers: [Server]?
        var subscriptions: [Subscription]?
        /// Entries of this group that didn't decode, counted for the report (each becomes a problem).
        var dropped = 0

        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            name = try c.decodeIfPresent(String.self, forKey: .name)
            `default` = try c.decodeIfPresent(Bool.self, forKey: .default)
            let servers = try c.decodeIfPresent([Lenient<Server>].self, forKey: .servers) ?? []
            let subs = try c.decodeIfPresent([Lenient<Subscription>].self, forKey: .subscriptions) ?? []
            self.servers = servers.compactMap(\.value)
            subscriptions = subs.compactMap(\.value)
            dropped = servers.count + subs.count - (self.servers?.count ?? 0) - (subscriptions?.count ?? 0)
        }

        enum CodingKeys: String, CodingKey { case name, `default`, servers, subscriptions }
    }

    /// One list entry that may not decode (nil then): a bad entry drops itself instead of failing the whole list.
    private struct Lenient<T: Decodable>: Decodable {
        var value: T?
        init(from decoder: Decoder) throws { value = try? T(from: decoder) }
    }

    struct Server: Decodable {
        var id: String
        var name: String
        var title: String
        var `protocol`: String
        var link: String
        var country: String?
        var limited: Bool?
        var hidden: Bool?
        /// `Insecurity.of`'s verdict as the wire names the service's `servers` reply uses ("no_encryption" /
        /// "no_cert_check") — a freshly imported insecure server is marked at once, not after a list poll.
        var insecure: [String]?
    }

    struct Subscription: Decodable {
        var url: String
        var name: String?
        /// Keys (the link without its #name) of the subscription's servers the bundle kept hidden.
        var hidden: [String]?
    }

    struct Problem: Decodable {
        var item: String
        var text: String
        var detail: String?
    }

    /// How a server's link name is shown: without a leading flag (Android `Repo.realName`).
    static func title(_ name: String) -> String { MacBridge.shared.title(name: name) }

    struct ServerInfo: Decodable {
        var host: String
        var port: Int
        var `protocol`: String
    }

    static func serverInfo(_ link: String) -> ServerInfo? {
        let json = MacBridge.shared.serverInfo(link: link)
        return try? JSONDecoder().decode(ServerInfo.self, from: Data(json.utf8))
    }

    /// A limited-network server's link (`wdtt://`, `qwdtt://`).
    static func isLimited(_ link: String) -> Bool { MacBridge.shared.isLimited(link: link) }

    /// The device id a limited-network link carries (empty = none).
    static func limitedDevice(_ link: String) -> String { MacBridge.shared.limitedDevice(link: link) }

    /// [link] with [device] as its device id (`LimitedLink.withDevice`); other links unchanged.
    static func withDevice(_ link: String, _ device: String) -> String { MacBridge.shared.withDevice(link: link, device: device) }

    /// The identity a subscription's hidden servers are shared by (the link without its #name).
    static func shareKey(_ link: String) -> String { MacBridge.shared.shareKey(link: link) }

    /// A log event's language-neutral text (the service stores it by name) in the UI language of the moment.
    static func renderText(_ json: String) -> String { MacBridge.shared.renderText(json: json) }

    /// A log for sharing: secrets always out, and with [hideServers] the servers' hosts and public IPv4s renamed.
    /// The hosts are the app's own list (the shared repo never runs in this process — audit A2).
    @MainActor
    static func scrub(_ text: String, hideServers: Bool) -> String {
        MacBridge.shared.scrub(text: text, hideServers: hideServers, links: AppModel.shared?.main.servers.map(\.link) ?? [])
    }

    /// The `borderless://import?d=` link of [groups], packed by the shared code the way Android writes it
    /// (`ShareFormat.encode`, raw deflate — Foundation's zlib stream reads nowhere else, audit A4).
    static func encodeShare(_ groups: [ShareFormat.Group]) -> String {
        guard let json = try? JSONEncoder().encode(groups), let text = String(data: json, encoding: .utf8) else { return "" }
        return MacBridge.shared.encodeShare(groupsJson: text)
    }

    /// What [text] holds (links, Border(less) bundles, subscription URLs, the problems explained), read as Android does.
    static func parse(_ text: String) -> Parsed {
        let json = MacBridge.shared.parse(text: text)
        return (try? JSONDecoder().decode(Parsed.self, from: Data(json.utf8))) ?? Parsed()
    }

    /// The core's version line ("Xray 26.10.21"), asked once of the bundled `borderless-core` the way the service
    /// asks its helper (a `version` request in JSON, the answer on its fd 3); "?" when it can't be asked. The
    /// service runs this bundle's copy, so the answer is the installed one's — the shared `CoreEnv` can't say it
    /// here: its Xray host is only ever set inside the service's process.
    static let coreVersion: String = askCore()

    private static func askCore() -> String {
        let path = Bundle.main.bundleURL.appendingPathComponent("Contents/Library/Service/borderless-core").path
        guard FileManager.default.isExecutableFile(atPath: path) else { return "?" }
        var req: [Int32] = [-1, -1], rep: [Int32] = [-1, -1]
        defer { for fd in req + rep where fd >= 0 { close(fd) } }
        guard pipe(&req) == 0, pipe(&rep) == 0 else { return "?" }
        let devNull = open("/dev/null", O_WRONLY)
        defer { if devNull >= 0 { close(devNull) } }
        var actions: posix_spawn_file_actions_t?
        posix_spawn_file_actions_init(&actions)
        defer { posix_spawn_file_actions_destroy(&actions) }
        // Requests on stdin, answers on fd 3 (the helper protocol, Sources/Service/Helper.swift), its log to /dev/null.
        posix_spawn_file_actions_adddup2(&actions, req[0], STDIN_FILENO)
        posix_spawn_file_actions_adddup2(&actions, rep[1], 3)
        if devNull >= 0 {
            posix_spawn_file_actions_adddup2(&actions, devNull, STDOUT_FILENO)
            posix_spawn_file_actions_adddup2(&actions, devNull, STDERR_FILENO)
        }
        var attrs: posix_spawnattr_t?
        posix_spawnattr_init(&attrs)
        defer { posix_spawnattr_destroy(&attrs) }
        posix_spawnattr_setflags(&attrs, Int16(POSIX_SPAWN_CLOEXEC_DEFAULT))
        var pid = pid_t()
        let spawned = withCStrings([path, "-helper"]) { argv in
            posix_spawn(&pid, path, &actions, &attrs, argv, nil)
        }
        guard spawned == 0 else { return "?" }
        // Our ends the child uses: closed so an early exit ends the reads instead of hanging them.
        for fd in [req[0], rep[1]] { close(fd) }
        req[0] = -1; rep[1] = -1
        defer {
            kill(pid, SIGKILL)
            var status = Int32(0)
            while waitpid(pid, &status, 0) < 0 && errno == EINTR {}
        }
        var request = Data(#"{"id":1,"op":"version"}"#.utf8)
        request.append(0x0A)
        let sent = request.withUnsafeBytes { raw -> Bool in
            guard var p = raw.baseAddress else { return true }
            var left = raw.count
            while left > 0 {
                let n = write(req[1], p, left)
                if n < 0 && errno == EINTR { continue }
                if n <= 0 { return false }
                p += n; left -= n
            }
            return true
        }
        guard sent else { return "?" }
        var buffer = Data()
        var chunk = [UInt8](repeating: 0, count: 4096)
        // The answer is one short line; a helper that never sends one is given 5 s, not the window's patience.
        while !buffer.contains(0x0A), buffer.count < 1 << 20 {
            var p = pollfd(fd: rep[0], events: Int16(POLLIN), revents: 0)
            guard poll(&p, 1, 5_000) > 0 else { return "?" }
            let n = read(rep[0], &chunk, chunk.count)
            if n <= 0 { return "?" }
            buffer.append(contentsOf: chunk[0..<n])
        }
        struct Reply: Decodable { var version: String? }
        let line = Data(buffer.prefix { $0 != 0x0A })
        return (try? JSONDecoder().decode(Reply.self, from: line))?.version ?? "?"
    }

    /// [strings] as a C argv/envp for `posix_spawn` (like `withCStrings` in the service's Tunnel.swift).
    private static func withCStrings<R>(_ strings: [String], _ body: (UnsafeMutablePointer<UnsafeMutablePointer<CChar>?>) -> R) -> R {
        var pointers: [UnsafeMutablePointer<CChar>?] = strings.map { strdup($0) }
        pointers.append(nil)
        defer { pointers.forEach { free($0) } }
        return pointers.withUnsafeMutableBufferPointer { body($0.baseAddress!) }
    }
}
