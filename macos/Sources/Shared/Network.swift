import Foundation

/// What the service changes in the Mac's network settings, and how it is undone (local/macos/PLAN.md §1.0.4).
///
/// The rules that keep a failure from breaking anything:
/// - Every change is bound to the service's lifetime where macOS allows it: routes go only through our utun (the kernel
///   drops them when the utun closes, i.e. when the process dies) and DNS goes into *temporary* dynamic-store keys
///   (configd drops them when our session ends). Nothing is written to the user's network configuration (`Setup:`).
/// - Before a change is made, its undo record is appended to [ServicePaths.undoLog] and synced (write-ahead), and the
///   state before the first change is kept in [ServicePaths.snapshot].
/// - Undo = `reset-network.sh` (system tools only), run by the service itself and runnable by hand, so the same path is
///   exercised every time and works without the service, the app or this code.
public enum NetworkPlan {
    /// Our service ID in the dynamic store; the reset removes these fixed keys even without a log.
    public static let serviceID = "app.borderless.tunnel"
    public static let keys = ["DNS", "IPv4", "IPv6"].map { "State:/Network/Service/\(serviceID)/\($0)" }

    /// Two halves of each address space: more specific than any default route, so they win without deleting it, and
    /// less specific than the connected local networks, which stay on their interfaces.
    public static let routes: [(UndoRecord.Family, String)] = [
        (.inet, "0.0.0.0/1"), (.inet, "128.0.0.0/1"), (.inet6, "::/1"), (.inet6, "8000::/1"),
    ]

    /// The destinations a tunnel takes over: `default`, our half routes and netstat's short forms of the IPv4 ones
    /// (`0/1`, `128.0/1`). One set for everyone asking "does this route steer the traffic" ([RouteTable.wide],
    /// [NetworkSnapshot.otherTunnel]): spelled out twice before, the copies could drift apart.
    public static let wideDestinations: Set<String> =
        Set(["default", "0/1", "128.0/1"]).union(routes.map(\.1))
}

/// One line of the undo log: `utun utun7`, `route inet 0.0.0.0/1 utun7`, `key <dynamic-store key>`. Parsing is strict, so a damaged
/// or edited log can never make the reset touch anything that isn't ours.
public enum UndoRecord: Equatable, Sendable {
    public enum Family: String, Sendable { case inet, inet6 }

    case utun(String)
    case route(Family, String, String)
    /// One of our fixed dynamic-store keys ([NetworkPlan.keys]).
    case key(String)

    public var line: String {
        switch self {
        case .utun(let name): "utun \(name)"
        case .route(let family, let dest, let iface): "route \(family.rawValue) \(dest) \(iface)"
        case .key(let key): "key \(key)"
        }
    }

    public init?(line: String) {
        let f = line.split(separator: " ", omittingEmptySubsequences: true).map(String.init)
        switch f.first {
        case "utun" where f.count == 2 && UndoRecord.isUtun(f[1]):
            self = .utun(f[1])
        case "route" where f.count == 4:
            guard let family = Family(rawValue: f[1]), UndoRecord.isCIDR(f[2], family), UndoRecord.isUtun(f[3])
            else { return nil }
            self = .route(family, f[2], f[3])
        case "key" where f.count == 2 && NetworkPlan.keys.contains(f[1]):
            self = .key(f[1])
        default:
            return nil
        }
    }

    public static func parse(_ text: String) -> [UndoRecord] {
        text.split(whereSeparator: \.isNewline).compactMap { UndoRecord(line: String($0)) }
    }

    /// `utun` + one or more digits — the same shape the reset script accepts (`utun[0-9]+`); keep the two in
    /// step. The digit count is not capped (it was 3, which missed utun1000+): the kernel's numbering is not ours
    /// to limit, and a made-up name can only match nothing.
    static func isUtun(_ s: String) -> Bool {
        guard s.hasPrefix("utun") else { return false }
        let n = s.dropFirst(4)
        return !n.isEmpty && n.allSatisfy(\.isASCIIDigit)
    }

    static func isCIDR(_ s: String, _ family: Family) -> Bool {
        let parts = s.split(separator: "/", omittingEmptySubsequences: false)
        guard parts.count == 2, let bits = Int(parts[1]) else { return false }
        let addr = String(parts[0])
        switch family {
        case .inet:
            var a = in_addr()
            return bits >= 0 && bits <= 32 && inet_pton(AF_INET, addr, &a) == 1
        case .inet6:
            var a = in6_addr()
            return bits >= 0 && bits <= 128 && inet_pton(AF_INET6, addr, &a) == 1
        }
    }
}

extension Character {
    var isASCIIDigit: Bool { ("0"..."9").contains(self) }
}

/// The parts of the network state that matter for us, read before the first change and after an undo.
public struct NetworkSnapshot: Codable, Equatable, Sendable {
    public var takenAt: Date
    /// `State:/Network/Global/IPv4`: the interface and service macOS sends traffic through.
    public var primaryInterface: String?
    public var primaryService: String?
    public var router: String?
    /// `State:/Network/Global/DNS` → ServerAddresses.
    public var dnsServers: [String]
    /// Default and half-space routes (`default`, `0/1`, `128.0/1`, `::/1`, `8000::/1`) with their interfaces.
    public var defaultRoutes: [Route]
    /// Every service whose IPv4 state sits on a utun interface (other tunnels, ours included).
    public var tunnels: [Tunnel]

    public struct Route: Codable, Equatable, Sendable {
        public var destination: String
        public var gateway: String
        public var interface: String
        /// netstat's flags; `I` = scoped to its interface (macOS's own utun0…3 carry such IPv6 defaults), which
        /// steers nothing else.
        public var flags: String = ""
        public var scoped: Bool { flags.contains("I") }
        public init(destination: String, gateway: String, interface: String, flags: String = "") {
            self.destination = destination
            self.gateway = gateway
            self.interface = interface
            self.flags = flags
        }
    }

    public struct Tunnel: Codable, Equatable, Sendable {
        public var serviceID: String
        public var interface: String
        public init(serviceID: String, interface: String) {
            self.serviceID = serviceID
            self.interface = interface
        }
    }

    public init(takenAt: Date, primaryInterface: String?, primaryService: String?, router: String?,
                dnsServers: [String], defaultRoutes: [Route], tunnels: [Tunnel]) {
        self.takenAt = takenAt
        self.primaryInterface = primaryInterface
        self.primaryService = primaryService
        self.router = router
        self.dnsServers = dnsServers
        self.defaultRoutes = defaultRoutes
        self.tunnels = tunnels
    }

    /// Another tunnel carrying the Mac's traffic (a system tunnel app, another client's utun): its interface, or nil.
    /// We never start over one — two tunnels fighting over routes and DNS break both — and step aside when one appears.
    public func otherTunnel(ours: String?) -> String? {
        // Ours: the utun we were told about, and whatever our own service ID sits on.
        let mine = Set([ours].compactMap { $0 } + tunnels.filter { $0.serviceID == NetworkPlan.serviceID }.map(\.interface))
        if let p = primaryInterface, UndoRecord.isUtun(p), !mine.contains(p), primaryService != NetworkPlan.serviceID {
            return p
        }
        return defaultRoutes.first {
            NetworkPlan.wideDestinations.contains($0.destination) && UndoRecord.isUtun($0.interface)
                && !mine.contains($0.interface) && !$0.scoped
        }?.interface
    }

    /// The physical interface traffic leaves by: the primary one, or — while our tunnel is the primary service — the
    /// interface of the remaining (then scoped) default route.
    public func physicalInterface(ours: String?) -> String? {
        func physical(_ name: String) -> Bool {
            name.range(of: "^[a-z]+[0-9]+$", options: .regularExpression) != nil
                && !Self.virtual.contains(where: { name.hasPrefix($0) })
        }
        if let p = primaryInterface, physical(p) { return p }
        return defaultRoutes.first { $0.destination == "default" && physical($0.interface) && !$0.gateway.hasPrefix("fe80") }?.interface
    }

    /// Name prefixes no real (bindable) interface carries — `Interface.virtual`'s twin (Service/Tunnel.swift;
    /// the enum itself lives in the other module): the tunnels, tunnel and device-to-device shims, virtual NICs
    /// and the loopback. Keep the two in step.
    private static let virtual = ["lo", "utun", "ipsec", "ppp", "anpi", "awdl", "llw", "ap",
                                  "gif", "stf", "bridge", "vmnet", "vboxnet", "tap", "tun"]

    /// What differs from [before] in what we touch (the default path and DNS); empty = back as it was.
    public func differences(from before: NetworkSnapshot) -> [String] {
        var out: [String] = []
        if primaryInterface != before.primaryInterface {
            out.append("primary interface \(before.primaryInterface ?? "none") → \(primaryInterface ?? "none")")
        }
        if dnsServers != before.dnsServers {
            out.append("DNS \(before.dnsServers.joined(separator: ",")) → \(dnsServers.joined(separator: ","))")
        }
        let ours = Set(tunnels.filter { $0.serviceID == NetworkPlan.serviceID }.map(\.interface))
        let left = defaultRoutes.filter { ours.contains($0.interface) }
        if !left.isEmpty { out.append("our routes still present: \(left.map(\.destination).joined(separator: ", "))") }
        return out
    }
}

/// `netstat -rn` output → its route lines (destination, gateway, interface), for both families.
public enum RouteTable {
    public static func parse(_ text: String) -> [NetworkSnapshot.Route] {
        var out: [NetworkSnapshot.Route] = []
        var netifColumn: Int?
        for raw in text.split(whereSeparator: \.isNewline) {
            let f = raw.split(separator: " ", omittingEmptySubsequences: true).map(String.init)
            if f.first == "Destination" {
                netifColumn = f.firstIndex(of: "Netif")
                continue
            }
            guard let col = netifColumn, f.count > col, f.count >= 3 else { continue }
            out.append(.init(destination: f[0], gateway: f[1], interface: f[col], flags: f[2]))
        }
        return out
    }

    /// The wide routes only (default and the half spaces): the ones a tunnel takes over.
    public static func wide(_ routes: [NetworkSnapshot.Route]) -> [NetworkSnapshot.Route] {
        routes.filter { NetworkPlan.wideDestinations.contains($0.destination) }
    }
}
