import CryptoKit
import Foundation

/// Where the system service lives and how it is reached. The install script (`Support/install-service.sh`) uses the
/// same paths; change both together.
public enum ServicePaths {
    public static let label = "app.borderless.service"
    /// The binary is `/Library/PrivilegedHelperTools/app.borderless.service-<first 16 hex of its cdhash>`: a fresh
    /// path per build, because Gatekeeper keeps rejecting a path it once rejected (install-service.sh).
    public static let binaryPrefix = "/Library/PrivilegedHelperTools/app.borderless.service-"
    public static let launchdPlist = "/Library/LaunchDaemons/app.borderless.service.plist"
    /// Root-only (0700): servers, statistics, logs, routing lists, the trusted client and the network journal.
    public static let dataDir = "/Library/Application Support/app.borderless.service"
    /// The code-directory hash of the one app allowed to give commands, written at install time.
    public static let trustedClient = dataDir + "/client"
    public static let socket = "/var/run/app.borderless.service.sock"
    /// The core (`native/core`), placed like the service binary: `<prefix><16 hex of its cdhash>`, its path in
    /// `dataDir/core`.
    public static let corePrefix = "/Library/PrivilegedHelperTools/app.borderless.core-"
    /// The limited-network core (`native/limited`), placed like the core: `<prefix><16 hex of its cdhash>`, its path
    /// in `dataDir/limited`.
    public static let limitedPrefix = "/Library/PrivilegedHelperTools/app.borderless.limited-"
    /// Routing lists the core reads (root-owned, readable by the core's account; the data dir is not).
    public static let listsDir = "/Library/Application Support/app.borderless.lists"
    /// The network reset (`Support/reset-network.sh`), root-owned; the service runs only this copy, never the app's.
    public static let resetScript = "/Library/PrivilegedHelperTools/app.borderless.service-reset"
    /// Write-ahead undo records ([UndoRecord]), one per line, synced before each change.
    public static let undoLog = dataDir + "/network.undo"
    /// The network state before the first change of a session ([NetworkSnapshot] as JSON).
    public static let snapshot = dataDir + "/network.snapshot.json"
}

/// How a rebuilt app keeps the installed service's trust without a reinstall (every build has a new cdhash, so the
/// one the install script recorded no longer matches): `build.sh`, when the owner's key is there
/// (`~/.borderless/lists-signing-key.pem` — the one that approves the routing lists), signs the bundle's cdhash and
/// writes the signature to the bundle's `app.borderless.client-sig` xattr — outside the code seal, so the app's own
/// signature stays valid. The service checks a signed hash the way it checks the recorded one ([PeerCheck]).
public enum ClientTrust {
    /// The bundle xattr carrying the signature (base64, DER ECDSA P-256 / SHA-256).
    public static let signatureXattr = "app.borderless.client-sig"
    /// What is signed: this prefix + the cdhash in hex (no manifest can collide — it starts with `{"payload"`).
    public static let messagePrefix = "borderless-client "
    /// Public half of `~/.borderless/lists-signing-key.pem` (X.509 SubjectPublicKeyInfo, P-256): the same key as
    /// `ListManifest.PUBLIC_KEY` in the Kotlin module, duplicated so the service never calls Kotlin for this.
    public static let publicKey =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE+agQxdoC3SCbQUI1Jp51CqjQxxWcrYl7r3OTspHXp+1IpB1gYpe2/nPdM5zxUxvP9ViNlQVOzpV6zj8aNfsYlA=="
}

/// The updater's trust anchor (`Sources/Service/Updater.swift`; `local/macos/PLAN.md` §1.0.3 "Updates" — S11's Mac
/// half): every release's checksum file is Ed25519-signed with a key only the developer holds
/// (`~/.borderless/update-signing-key.pem`, never in the repo — `openssl pkeyutl -sign -rawin -inkey …` over the
/// sums file; the `.sig` asset is the raw 64-byte signature, base64). A staged build is trusted only while the
/// chain checks out: the sums' signature → the payload's SHA-256 inside the sums → the bundle's own code seal.
/// Android checks the same sums plus the platform's same-signer rule — ad-hoc macOS builds can't offer one, so
/// this key stands in for it.
public enum UpdateTrust {
    /// Public half of the update-signing key (Ed25519, raw 32 bytes, base64).
    public static let publicKey = "i/N2scZVJ+UJo1CqrZd+5IDNScOeON/wZViTOU4x2l0="

    /// Whether [signatureB64] is [key]'s Ed25519 signature over [payload] (the sums file's bytes). [key] takes
    /// the pinned public half by default — another one only in the unit checks, where no real key exists.
    public static func verify(payload: Data, signatureB64: String, key: String = publicKey) -> Bool {
        guard let raw = Data(base64Encoded: key), raw.count == 32,
              let pub = try? Curve25519.Signing.PublicKey(rawRepresentation: raw),
              let sig = Data(base64Encoded: signatureB64.trimmingCharacters(in: .whitespacesAndNewlines)),
              sig.count == 64
        else { return false }
        return pub.isValidSignature(sig, for: payload)
    }
}

/// The release check's pure parts — Android `Updater`'s rules ported (its `codeOf`, `isApk`, the sums-line
/// lookup): the version's code, the Mac asset's name, this Mac's processor, the wire's state word.
public enum UpdateCheck {
    /// `MAJOR.MINOR.PATCH` → that release's code ((M*10000+m*100+p)*100+99 — the release rules' versionCode,
    /// Android `Updater.codeOf`); nil for any other shape — betas and release candidates never compare, and
    /// GitHub's `/releases/latest` already leaves pre-releases out.
    public static func code(of version: String) -> Int? {
        guard version.range(of: #"^\d+\.\d+\.\d+$"#, options: .regularExpression) != nil else { return nil }
        let p = version.split(separator: ".").compactMap { Int($0) }
        guard p.count == 3 else { return nil }
        return (p[0] * 10_000 + p[1] * 100 + p[2]) * 100 + 99
    }

    /// `borderless-<version>-macos-<arch>.zip` — the release's Mac build for this processor (Android's `isApk`:
    /// only names the release process gives — the name becomes a file name here).
    public static func isMacAsset(_ name: String, arch: String) -> Bool {
        name.range(of: "^borderless-[0-9][0-9a-z.-]*-macos-\(NSRegularExpression.escapedPattern(for: arch))\\.zip$",
                   options: .regularExpression) != nil
    }

    /// This Mac's processor as the release names it ("arm64" / "x86_64" — `uname -m`).
    public static var arch: String {
        var u = utsname()
        uname(&u)
        return withUnsafeBytes(of: &u.machine) {
            String(decoding: $0.prefix { $0 != 0 }, as: UTF8.self)
        }
    }

    /// The SHA-256 a SHA256SUMS file lists for [asset] — "<64 hex> <name>" lines, `*` for binary mode (Android's
    /// lookup: the line's last word against the file name, the first word lowercased).
    public static func checksum(for asset: String, in sums: String) -> String? {
        for line in sums.split(separator: "\n") {
            let fields = line.split(whereSeparator: { $0.isWhitespace })
            guard fields.count >= 2, fields[0].count == 64, fields[0].allSatisfy(\.isHexDigit) else { continue }
            let last = fields.last!.hasPrefix("*") ? fields.last!.dropFirst() : fields.last!
            if last == asset { return String(fields[0]).lowercased() }
        }
        return nil
    }

    /// The wire's `EngineStatus.update.state` from what is kept and what runs now ([UpdateState] plus the
    /// updater's in-flight op): work in progress first, then what waits for the user, then a kept failure,
    /// then the still-open offer — else "idle".
    public static func wireState(running: String?, lastError: String?, _ s: UpdateState) -> String {
        if let running { return running }
        if s.approved, s.staged { return "installing" }
        if s.staged, s.dismissed != s.code { return "ready" }
        if lastError != nil { return "error" }
        if s.code > 0, s.dismissed != s.code { return "available" }
        return "idle"
    }
}

/// What the updater keeps between service runs (`dataDir/update-state.json`; Android `Updater`'s `update`
/// shared-prefs): the offered release, the staged payload and the user's answers. Every field decodes with a
/// default — a file from another build or a partial write reads as "nothing kept".
public struct UpdateState: Codable, Equatable, Sendable {
    /// The offered release's version and code ([UpdateCheck.code]); code 0 = nothing offered.
    public var version: String
    public var code: Int
    /// Its asset name and download addresses — kept so "Update" can stage it without a fresh check.
    public var asset: String
    public var zipUrl: String
    public var sumsUrl: String
    public var sigUrl: String
    /// The last check's time, and when this offer was first seen (Android `foundAt`) — ms since 1970.
    public var checkedAt: Int64
    public var foundAt: Int64
    /// The staged build's time (ms; 0 = nothing staged).
    public var readyAt: Int64
    /// The staged payload's SHA-256 as the signed sums listed it.
    public var sha256: String
    /// The payload is downloaded, signature-checked and unpacked in the staging dir.
    public var staged: Bool
    /// The user confirmed the install (`update_install`): applied at the next service start.
    public var approved: Bool
    /// The offer's version code the user closed (Android `dismissed`).
    public var dismissed: Int
    /// The version code the previous run started as — a bigger one now means an update just went through.
    public var lastCode: Int
    /// A boot-time apply's failure, told once to the app log.
    public var applyError: String

    public init(version: String = "", code: Int = 0, asset: String = "", zipUrl: String = "",
                sumsUrl: String = "", sigUrl: String = "", checkedAt: Int64 = 0, foundAt: Int64 = 0,
                readyAt: Int64 = 0, sha256: String = "", staged: Bool = false, approved: Bool = false,
                dismissed: Int = 0, lastCode: Int = 0, applyError: String = "") {
        self.version = version; self.code = code
        self.asset = asset; self.zipUrl = zipUrl; self.sumsUrl = sumsUrl; self.sigUrl = sigUrl
        self.checkedAt = checkedAt; self.foundAt = foundAt; self.readyAt = readyAt
        self.sha256 = sha256
        self.staged = staged; self.approved = approved
        self.dismissed = dismissed; self.lastCode = lastCode; self.applyError = applyError
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        func str(_ k: CodingKeys) -> String { (try? c.decodeIfPresent(String.self, forKey: k)) ?? "" }
        func int(_ k: CodingKeys) -> Int { (try? c.decodeIfPresent(Int.self, forKey: k)) ?? 0 }
        func ms(_ k: CodingKeys) -> Int64 { (try? c.decodeIfPresent(Int64.self, forKey: k)) ?? 0 }
        func flag(_ k: CodingKeys) -> Bool { (try? c.decodeIfPresent(Bool.self, forKey: k)) ?? false }
        self.init(version: str(.version), code: int(.code), asset: str(.asset), zipUrl: str(.zipUrl),
                  sumsUrl: str(.sumsUrl), sigUrl: str(.sigUrl), checkedAt: ms(.checkedAt),
                  foundAt: ms(.foundAt), readyAt: ms(.readyAt), sha256: str(.sha256), staged: flag(.staged),
                  approved: flag(.approved), dismissed: int(.dismissed), lastCode: int(.lastCode),
                  applyError: str(.applyError))
    }
}

/// One JSON object per line in both directions. The app sends a [Request], the service answers with a [Reply]; later
/// the service will also push state on the same connection.
public struct Request: Codable, Equatable, Sendable {
    public enum Kind: String, Codable, Sendable {
        case hello, status
        /// Undo everything the service changed in the network settings (the same reset that works by hand).
        case restoreNetwork
        case connect, disconnect
        /// The service's last log lines (in `report`).
        case log
        /// Read-only state for debugging (in `report`): the core's sockets, our utun, the routes.
        case diagnose
        /// The user's servers (in `servers`), and storing the list the app edited (`servers` of the request).
        case servers, saveServers
        /// Check every server now ("Ping all"): with the connection on the engine then decides as its strategy says.
        case pingAll
        /// The service's settings (in `settings`) and the app's edited fields (in `json`, only what changed).
        case settings, saveSettings
        /// The main screen's battery saving switch and the countries picked on the map (in `on` / `countries`).
        case setEco, setCountries
        /// The user's zones (in `zones`), storing one (`json`) and deleting one (`id`).
        case zones, saveZone, deleteZone
        /// The log screen: the events (language-neutral, in `events`), the detailed lines (in `lines`), clearing both.
        case events, debugLog, clearLog, errorReports
        /// The statistics screens: one query (`fromMs`…`toMs`, `target` "s:<id>" / "sub:<id>" /
        /// "countries" / "activity" / nil) in `stats`.
        case stats
        /// "update_subs" / "update_sub:<id>" / "update_geo" / "clear_stats" / "error_dismiss:<id>" /
        /// "scan_open" / "update_check" / "update_install" / "update_dismiss" / "probe_one:<id>" /
        /// "imported:<servers>:<subs>:<pending>:<problems>" (in `target`).
        case serviceAction
        /// Every statistics table as CSV (in `tables`).
        case exportStats
    }
    public var kind: Kind
    /// `connect`: the server to connect to (nil = the one of the last connection).
    public var serverId: String?
    /// `saveServers`: the whole list.
    public var servers: ServerList?
    /// `saveSettings`: the changed fields as a JSON object; `saveZone`: the zone as a JSON object.
    public var json: String?
    /// `setEco`: the switch's new position.
    public var on: Bool?
    /// `setCountries`: the countries picked on the map.
    public var countries: [String]?
    /// `stats`: the period asked for.
    public var fromMs: Int64?
    public var toMs: Int64?
    /// `stats`: one server ("s:<id>"), one subscription ("sub:<id>"), or "countries" (per-country
    /// chosen/answered/checked); nil = the overview.
    /// `serviceAction`: "update_subs" / "update_sub:<id>" / "update_geo" / "clear_stats" / "error_dismiss:<id>"
    /// / "scan_open" / "update_check" / "update_install" / "update_dismiss" / "probe_one:<id>" /
    /// "imported:<servers>:<subs>:<pending>:<problems>". `deleteZone`: the zone's id.
    public var target: String?
    public init(_ kind: Kind, serverId: String? = nil, servers: ServerList? = nil, json: String? = nil, on: Bool? = nil,
                countries: [String]? = nil, fromMs: Int64? = nil, toMs: Int64? = nil, target: String? = nil) {
        self.kind = kind
        self.serverId = serverId
        self.servers = servers
        self.json = json
        self.on = on
        self.countries = countries
        self.fromMs = fromMs
        self.toMs = toMs
        self.target = target
    }
}

public struct Reply: Codable, Equatable, Sendable {
    /// The service's version (the app's `CFBundleShortVersionString` it was installed from).
    public var version: String
    public var tunnel: TunnelState
    public var network: NetworkStatus?
    /// What a restore did and anything that still differs from the state before (for the log and the app).
    public var report: [String]?
    /// Set when the request was refused or failed.
    public var error: String?
    /// Why the tunnel isn't up when it should be (the last start failed, or the core stopped by itself).
    public var problem: String?
    /// The server the tunnel runs through, or the last one when it is off.
    public var serverId: String?
    /// `servers`: the stored list.
    public var servers: ServerList?
    /// The engine's state (phase, server, pings, the scan in progress) as the shared code writes it ([EngineStatus]).
    public var engine: EngineStatus?
    /// `settings`: the service's settings as JSON (`AppSettings`, every field).
    public var settings: String?
    /// `zones`: the user's zones as JSON.
    public var zones: String?
    /// `events`: the log's events (language-neutral texts) for the log screen.
    public var events: [EventLine]?
    /// `debugLog`: the detailed log's lines.
    public var lines: [String]?
    /// `stats`: one statistics query's answer as JSON.
    public var stats: String?
    /// `exportStats`: {table: csv}.
    public var tables: String?
    /// The statistics database's size in bytes, nil until there is one (the settings screen's stats row).
    public var statsBytes: Int64?
    public init(version: String, tunnel: TunnelState, network: NetworkStatus? = nil, report: [String]? = nil,
                error: String? = nil, problem: String? = nil, serverId: String? = nil, servers: ServerList? = nil,
                engine: EngineStatus? = nil, settings: String? = nil, zones: String? = nil, events: [EventLine]? = nil,
                lines: [String]? = nil, stats: String? = nil, tables: String? = nil, statsBytes: Int64? = nil) {
        self.version = version
        self.tunnel = tunnel
        self.network = network
        self.report = report
        self.error = error
        self.problem = problem
        self.serverId = serverId
        self.servers = servers
        self.engine = engine
        self.settings = settings
        self.zones = zones
        self.events = events
        self.lines = lines
        self.stats = stats
        self.tables = tables
        self.statsBytes = statsBytes
    }
}

/// One event of the log screen: when, how it went, and the language-neutral text (an [LText] as JSON) the app renders.
public struct EventLine: Codable, Equatable, Sendable {
    public var time: Int64
    /// INFO / GOOD / WARN / ERROR.
    public var kind: String
    public var text: String
    public init(time: Int64, kind: String, text: String) {
        self.time = time
        self.kind = kind
        self.text = text
    }
}

/// The shared engine's state (`MacEngine.Status` in the Kotlin module): what the main screen and the menu bar show.
public struct EngineStatus: Codable, Equatable, Sendable {
    /// Android's `Phase` names: OFF, SEARCHING, CONNECTED, DIRECT, PAUSED, NO_NETWORK, LIMITED.
    public var phase: String
    public var serverId: String?
    public var selectedId: String?
    public var error: String?
    public var unanswered: Bool
    public var noServers: Bool
    public var nextRetryAt: Int64
    public var scanDone: Int
    public var scanTotal: Int
    public var scanStartedAt: Int64
    public var probing: [String]
    public var pending: [String]
    public var pings: [String: Ping]
    public var strategy: String
    /// The routing switch and the battery saving mode as the service keeps them (the buttons' state). Optional: a
    /// service installed from an older build of the app doesn't send them, and its answer must still read.
    public var routing: Bool?
    public var eco: Bool?
    /// The battery saving mode's expected saving in percent, when the service's statistics can say.
    public var ecoEstimate: Int?
    /// The routing lists: when they were built, last checked, and what went wrong (the settings' geo row).
    public var listsUpdated: Int64?
    public var listsChecked: Int64?
    public var listsError: String?
    /// Failures kept for reporting since the last statistics export (Android `Errors.unreported`).
    public var unreported: Int?
    /// Failures queued for the user to see (Android `Errors.pending`): the app shows them one at a time and
    /// dismisses each back through `serviceAction` "error_dismiss:<id>". Optional: an older service sends none.
    public var pendingErrors: [PendingError]?
    /// The device id a limited-network server sends when neither the user nor its link names one.
    public var deviceId: String?
    /// Server ids in the active subset (Android `Repo.isActive`): the countries filter, the auto-visibility
    /// pool, hidden and insecure servers are all applied — the main list, map and counts should show/filter by
    /// it, not by `!disabled`. nil = an older service; the app falls back to `!disabled`.
    public var activeIds: [String]?
    /// The network underneath is metered (NWPathMonitor `isExpensive || isConstrained`, pushed by the service):
    /// the engine's work stretches on it (`Power.stretch`), and a metered hint can show.
    public var metered: Bool?
    /// Live limited-network session counters of the server in use (nil when not on a limited server): streams
    /// actually connected, streams the core is told to keep, the stream limit in effect and the limit the
    /// server declared (kept even while ignored).
    public var limitedActive: Int?
    public var limitedWanted: Int?
    public var limitedLimit: Int?
    public var limitedKnown: Int?
    /// The update checker's state; nil while no updater exists in the service (the `update_*` actions answer
    /// "not implemented" until the updater lands).
    public var update: UpdateStatus?
    /// The countries filter in effect (the globe button's state; the settings JSON carries it too — this is the
    /// direct status channel Android's status row reads).
    public var countries: [String]?
    /// `TunnelStatus.paused`: traffic is held while no server works (with phase PAUSED, and the paused variant
    /// of the LIMITED subtitle Android shows).
    public var paused: Bool?
    /// The current server's latest delay measured through the running core (ms).
    public var ping: Int?
    /// A routing-list update is running and what it is doing ("DOWNLOADING" / "VIA_SERVER"), plus when a
    /// missing list will be tried again (the zone-lists status note Android shows).
    public var listsBusy: Bool?
    public var listsWork: String?
    public var listsRetryAt: Int64?
    /// A subscription update (manual or scheduled) is in flight: the rows' busy state (Android spins until
    /// `Importer.update` returns; the socket's short timeouts can't block that long).
    public var subsUpdating: Bool?
    /// When the tunnel last carried traffic (ms since 1970; nil = never): the "Last connected" subtitle.
    public var lastConnected: Int64?

    /// The service-side update checker's state (Android `Updater`): what the update banner and the settings'
    /// update row show. `state`: "idle" | "checking" | "available" | "downloading" | "ready" | "installing" |
    /// "error".
    public struct UpdateStatus: Codable, Equatable, Sendable {
        public var state: String
        /// The version the check found / staged (nil while nothing is offered).
        public var version: String?
        /// The last check's time (ms since 1970).
        public var checkedAt: Int64?
        /// When the staged update was made ready to install.
        public var readyAt: Int64?
        /// Why the last check / download / install failed.
        public var error: String?
        public init(state: String, version: String? = nil, checkedAt: Int64? = nil, readyAt: Int64? = nil,
                    error: String? = nil) {
            self.state = state
            self.version = version
            self.checkedAt = checkedAt
            self.readyAt = readyAt
            self.error = error
        }
    }

    /// One queued failure: the plain-words [message], the full [details] for copying, [crash] for the title,
    /// [bug] = it points at a bug in the app (the dialog then offers reporting it on GitHub).
    public struct PendingError: Codable, Equatable, Sendable {
        public var id: Int64
        public var message: String
        public var details: String
        public var crash: Bool
        public var bug: Bool

        /// A locally-made record — the app synthesizes the dead-previous-run entry ([Errors.crashEntry]);
        /// everything the service queues itself arrives decoded.
        public init(id: Int64, message: String, details: String, crash: Bool, bug: Bool) {
            self.id = id
            self.message = message
            self.details = details
            self.crash = crash
            self.bug = bug
        }

        // Android leaves defaults out of its JSON: read them as missing.
        public init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            id = try c.decode(Int64.self, forKey: .id)
            message = try c.decode(String.self, forKey: .message)
            details = try c.decode(String.self, forKey: .details)
            crash = try c.decodeIfPresent(Bool.self, forKey: .crash) ?? false
            bug = try c.decodeIfPresent(Bool.self, forKey: .bug) ?? false
        }
    }

    /// The latest result of a server: [ms] nil = no reply; [at] = when (ms since 1970).
    public struct Ping: Codable, Equatable, Sendable {
        public var ms: Int?
        public var at: Int64
    }
}

/// The Mac's network as the service sees it.
public struct NetworkStatus: Codable, Equatable, Sendable {
    public var primaryInterface: String?
    public var dnsServers: [String]
    /// Another tunnel carries the traffic (its interface): the service won't start over it.
    public var otherTunnel: String?
    /// Undo records waiting (our changes in effect).
    public var changes: Int
    public init(primaryInterface: String?, dnsServers: [String], otherTunnel: String?, changes: Int) {
        self.primaryInterface = primaryInterface
        self.dnsServers = dnsServers
        self.otherTunnel = otherTunnel
        self.changes = changes
    }
}

public enum TunnelState: String, Codable, Sendable {
    case off
    case connecting
    case connected
}

/// Line framing shared by both sides: a message is its JSON on one line.
public enum Wire {
    /// The longest line either side reads (the server list is the longest).
    public static let maxLine = 16 << 20

    /// Writes all of [data] to a blocking socket; false when it broke off.
    public static func send(_ data: Data, to fd: Int32) -> Bool {
        data.withUnsafeBytes { raw -> Bool in
            guard var p = raw.baseAddress else { return true }
            var left = raw.count
            while left > 0 {
                let n = write(fd, p, left)
                if n < 0 && errno == EINTR { continue }
                if n <= 0 { return false }
                p += n
                left -= n
            }
            return true
        }
    }

    public static func encode<T: Encodable>(_ value: T) throws -> Data {
        var data = try JSONEncoder().encode(value)
        data.append(0x0A)
        return data
    }

    public static func decode<T: Decodable>(_ type: T.Type, line: Data) throws -> T {
        try JSONDecoder().decode(type, from: line)
    }

    /// Splits [buffer] at newlines: returns the complete lines and leaves the unfinished rest in it.
    public static func lines(_ buffer: inout Data) -> [Data] {
        var out: [Data] = []
        while let nl = buffer.firstIndex(of: 0x0A) {
            out.append(buffer[buffer.startIndex..<nl])
            buffer.removeSubrange(buffer.startIndex...nl)
        }
        return out
    }
}

/// A connected Unix stream socket to [ServicePaths.socket], or nil when nothing listens there.
public func connectToService(timeoutSec: Int = 2) -> Int32? {
    let fd = socket(AF_UNIX, SOCK_STREAM, 0)
    guard fd >= 0 else { return nil }
    // Close-on-exec: a child this process spawns (Process / posix_spawn) must not keep a service connection it
    // doesn't know about — the service counts it as one of its few client slots.
    _ = fcntl(fd, F_SETFD, fcntl(fd, F_GETFD) | FD_CLOEXEC)
    var tv = timeval(tv_sec: timeoutSec, tv_usec: 0)
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, socklen_t(MemoryLayout<timeval>.size))
    setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &tv, socklen_t(MemoryLayout<timeval>.size))
    var addr = unixAddress(ServicePaths.socket)
    func tryConnect() -> Int32 {
        withUnsafePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                Darwin.connect(fd, $0, socklen_t(MemoryLayout<sockaddr_un>.size))
            }
        }
    }
    var ok = tryConnect()
    if ok != 0 && errno == EINTR {
        // An interrupted connect on a local socket usually simply wasn't done yet; try once more.
        ok = tryConnect()
    }
    if ok != 0 {
        close(fd)
        return nil
    }
    return fd
}

public func unixAddress(_ path: String) -> sockaddr_un {
    var addr = sockaddr_un()
    addr.sun_family = sa_family_t(AF_UNIX)
    let bytes = Array(path.utf8)
    // A cut sun_path is a *different* address: connecting or binding to it silently is worse than stopping here.
    // Both callers pass fixed short paths, so an overflow is a bug, not user input — trap, don't truncate.
    precondition(bytes.count < MemoryLayout.size(ofValue: addr.sun_path),
                 "unix socket path doesn't fit sockaddr_un: \(path)")
    withUnsafeMutableBytes(of: &addr.sun_path) { raw in
        raw.copyBytes(from: bytes)
        raw[bytes.count] = 0
    }
    return addr
}
