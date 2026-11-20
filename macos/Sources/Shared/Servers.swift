import Foundation

/// The user's servers, groups and subscriptions as the service keeps them (`servers.json` in its data dir, root only,
/// 0600). The field names are Android's (`Server`, `Group`, `Subscription` in the shared module), so the shared `Repo`
/// can take the file over when it moves into the service (local/macos/PLAN.md §10, phase 2).
public struct ServerList: Codable, Equatable, Sendable {
    public var groups: [StoredGroup]
    public var servers: [StoredServer]
    public var subscriptions: [StoredSubscription]

    public init(groups: [StoredGroup] = [], servers: [StoredServer] = [], subscriptions: [StoredSubscription] = []) {
        self.groups = groups
        self.servers = servers
        self.subscriptions = subscriptions
    }

    /// The limits the service accepts (Android: ≤ 2000 servers per subscription).
    public static let maxServers = 5000
    public static let maxLinkBytes = 64 << 10

    /// Why the service refuses this list, nil when it is fine.
    public var problem: String? {
        if servers.count > Self.maxServers || groups.count > Self.maxServers || subscriptions.count > Self.maxServers {
            return "too many servers"
        }
        if servers.contains(where: { $0.link.utf8.count > Self.maxLinkBytes || $0.id.isEmpty }) { return "a server is damaged" }
        if Set(servers.map(\.id)).count != servers.count { return "two servers with one id" }
        return nil
    }

    /// The list stored at [path], or a fresh empty one. A file that is there but won't decode is first copied to
    /// `<path>.broken-<ms>` — like the shared store's `keepAside` — because the next save writes a fresh list over
    /// whatever sits at [path], and without the copy the user's servers would be gone for good. The kept-aside
    /// path (nil when nothing went aside) comes back for the caller to log.
    public static func load(at path: String) -> (list: ServerList, keptAside: String?) {
        guard FileManager.default.fileExists(atPath: path) else { return (ServerList(), nil) }
        guard let data = FileManager.default.contents(atPath: path), data.count < Wire.maxLine,
              let list = try? JSONDecoder().decode(ServerList.self, from: data)
        else {
            let aside = path + ".broken-" + String(Int64(Date().timeIntervalSince1970 * 1000))
            try? FileManager.default.copyItem(atPath: path, toPath: aside)
            return (ServerList(), aside)
        }
        return (list, nil)
    }
}

public struct StoredServer: Codable, Equatable, Sendable {
    public var id: String
    /// The name in the link when it was added; `customName` (empty = none) is the user's own.
    public var name: String
    public var link: String
    public var proto: String
    public var groupId: String
    public var subscriptionId: String?
    public var country: String?
    public var countryManual: Bool
    public var disabled: Bool
    public var customName: String
    /// Limited-network servers only (Android `Server`, the field names match): streams kept connected (0 = by the
    /// power plan), the device id sent (empty = the install's default), and whether the link's own limit is ignored.
    public var limitedStreams: Int
    public var limitedDevice: String
    public var limitedIgnoreLimit: Bool
    /// The issues the service computed with the shared `Insecurity.of` (the built outbound's verdict — what
    /// Android shows), as wire names: "no_encryption" / "no_cert_check". The service fills it; the app never
    /// writes one (nil/absent = an older service, and the app falls back to reading the link itself).
    public var insecure: [String]?

    enum CodingKeys: String, CodingKey {
        case id, name, link, proto = "protocol", groupId, subscriptionId, country, countryManual, disabled, customName
        case limitedStreams, limitedDevice, limitedIgnoreLimit, insecure
    }

    public init(id: String, name: String, link: String, proto: String, groupId: String, subscriptionId: String? = nil,
                country: String? = nil, countryManual: Bool = false, disabled: Bool = false, customName: String = "",
                limitedStreams: Int = 0, limitedDevice: String = "", limitedIgnoreLimit: Bool = false,
                insecure: [String]? = nil) {
        self.id = id
        self.name = name
        self.link = link
        self.proto = proto
        self.groupId = groupId
        self.subscriptionId = subscriptionId
        self.country = country
        self.countryManual = countryManual
        self.disabled = disabled
        self.customName = customName
        self.limitedStreams = limitedStreams
        self.limitedDevice = limitedDevice
        self.limitedIgnoreLimit = limitedIgnoreLimit
        self.insecure = insecure
    }

    // Android leaves defaults out of its files: read them as missing.
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        name = try c.decodeIfPresent(String.self, forKey: .name) ?? ""
        link = try c.decode(String.self, forKey: .link)
        proto = try c.decodeIfPresent(String.self, forKey: .proto) ?? ""
        groupId = try c.decodeIfPresent(String.self, forKey: .groupId) ?? StoredGroup.defaultId
        subscriptionId = try c.decodeIfPresent(String.self, forKey: .subscriptionId)
        country = try c.decodeIfPresent(String.self, forKey: .country)
        countryManual = try c.decodeIfPresent(Bool.self, forKey: .countryManual) ?? false
        disabled = try c.decodeIfPresent(Bool.self, forKey: .disabled) ?? false
        customName = try c.decodeIfPresent(String.self, forKey: .customName) ?? ""
        limitedStreams = try c.decodeIfPresent(Int.self, forKey: .limitedStreams) ?? 0
        limitedDevice = try c.decodeIfPresent(String.self, forKey: .limitedDevice) ?? ""
        limitedIgnoreLimit = try c.decodeIfPresent(Bool.self, forKey: .limitedIgnoreLimit) ?? false
        insecure = try c.decodeIfPresent([String].self, forKey: .insecure)
    }
}

public struct StoredGroup: Codable, Equatable, Sendable {
    /// Android's `Group.DEFAULT_ID`: always there, can't be renamed or deleted.
    public static let defaultId = "own"
    public var id: String
    /// nil = named after its first subscription (Android).
    public var name: String?
    public init(id: String, name: String?) {
        self.id = id
        self.name = name
    }
}

public struct StoredSubscription: Codable, Equatable, Sendable {
    public var id: String
    public var name: String
    public var url: String
    public var groupId: String
    public var customName: String?
    /// What the provider said at the last update (Android `Subscription`): when it was, its traffic figures and
    /// expiry, its update wish and whether the last download went through a server. The service fills these; the app
    /// only shows them.
    public var updatedAt: Int64?
    public var lastError: String?
    public var uploadBytes: Int64?
    public var downloadBytes: Int64?
    public var totalBytes: Int64?
    public var expire: Int64?
    public var providerHours: Int?
    public var viaServer: Bool?
    /// A bundle import's hidden-server marks for a subscription not downloaded yet (Android's `pendingHidden`,
    /// which `Repo.hideByKeys` applies once the servers exist). The service persists them root-only and applies
    /// them on the first download, so they survive an app quit; nil/absent = none. (Wire addition flagged for
    /// the PLAN §2 amendment list — Optional, an older service/app ignores it both ways.)
    public var hiddenKeys: [String]?
    public init(id: String, name: String, url: String, groupId: String, customName: String? = nil,
                updatedAt: Int64? = nil, lastError: String? = nil, uploadBytes: Int64? = nil, downloadBytes: Int64? = nil,
                totalBytes: Int64? = nil, expire: Int64? = nil, providerHours: Int? = nil, viaServer: Bool? = nil,
                hiddenKeys: [String]? = nil) {
        self.id = id
        self.name = name
        self.url = url
        self.groupId = groupId
        self.customName = customName
        self.updatedAt = updatedAt
        self.lastError = lastError
        self.uploadBytes = uploadBytes
        self.downloadBytes = downloadBytes
        self.totalBytes = totalBytes
        self.expire = expire
        self.providerHours = providerHours
        self.viaServer = viaServer
        self.hiddenKeys = hiddenKeys
    }
}
