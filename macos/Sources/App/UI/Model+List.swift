import Foundation

// MARK: The list's own helpers (APP-LIST): pure rules shared by the main list, the groups outline,
// the map and the countries popup — kept out of Model.swift so they can be checked on their own.

/// The country code of a server whose country isn't known (Android `Countries.UNKNOWN`): it joins the
/// countries filter and sorts after every real one.
let unknownCountryCode = "-"

/// A server's country as `AppSettings.countries` names it (Android `Countries.of`): its code, or the
/// unknown one.
func countryOf(_ s: ServerItem) -> String { s.country ?? unknownCountryCode }

/// A country's name as Android's `Countries.name` gives it: the bundled `country_short_names`
/// overrides first (US → "USA", GB → "UK"…), then the system's region name, then the code, then the
/// shared "unknown country" text. (`countryName` in Basics.swift is the shell's own helper — only the
/// localized-name step — so the list keeps its own, Android-equivalent one.)
func shortCountryName(_ code: String?) -> String {
    guard let code, !code.isEmpty, code != unknownCountryCode else { return L("country_unknown") }
    let tag = code.uppercased()
    let lang = Bundle.main.preferredLocalizations.first.flatMap { loc in ["en", "ru"].first { loc.hasPrefix($0) } } ?? "en"
    if let fixed = shortCountryOverrides[lang]?[tag] { return fixed }
    return Locale.current.localizedString(forRegionCode: tag) ?? tag
}

/// `country_short_names` of the Android resources, per UI language (values/strings.xml,
/// values-ru/strings.xml) — the names a flag label can't carry ("USA" over "United States").
private let shortCountryOverrides: [String: [String: String]] = [
    "en": ["US": "USA", "GB": "UK", "AE": "UAE", "KR": "South Korea", "HK": "Hong Kong"],
    "ru": ["US": "США", "GB": "Великобритания", "AE": "ОАЭ", "ZA": "ЮАР", "KR": "Южная Корея",
           "HK": "Гонконг", "CZ": "Чехия", "MD": "Молдова"],
]

/// A server's place in the main list's order (Android `sortByPing`): answering first (fastest first),
/// never checked next, answered-not last.
func pingRank(_ s: ServerItem) -> Int {
    if s.ms != nil { return 0 }
    return s.checked ? 2 : 1
}

/// A new group's id — eight lowercase hex characters, as Android's (`Importer.newId`).
func newGroupId() -> String {
    "g-\(UUID().uuidString.replacingOccurrences(of: "-", with: "").prefix(8).lowercased())"
}

/// Protocol names keep the same spelling for share links, JSON and stored labels from older imports.
func protocolLabel(_ label: String) -> String {
    let parts = label.components(separatedBy: " · ")
    let names = ["vless": "VLESS", "vmess": "VMess", "socks": "SOCKS", "socks5": "SOCKS",
                 "trojan": "Trojan", "shadowsocks": "Shadowsocks", "wireguard": "WireGuard",
                 "hysteria2": "Hysteria2", "wdtt": "WDTT"]
    guard let first = parts.first, let name = names[first.lowercased()] else { return label }
    return ([name] + parts.dropFirst()).joined(separator: " · ")
}

/// One country's row of the service's `target:"countries"` statistics answer: `chosen` = how often the
/// country joined the chosen set (Android `CountryStats.timesChosen` over the settings log), `answered`
/// and `checked` = the week's answering slices vs all slices of its servers — the sums
/// `CountryStats.stability` smooths (one success and one failure added, so a country checked twice
/// doesn't top one checked a thousand times).
struct CountryStat: Equatable {
    var chosen = 0
    var answered = 0
    var checked = 0

    /// The smoothed answer share, nil when the country's servers were never checked — such a country
    /// sorts under "used" like an unchosen one and has no stability to sort by.
    var stable: Double? {
        checked > 0 ? (Double(answered) + 1) / (Double(checked) + 2) : nil
    }
}

/// The `{"<code>": {chosen, answered, checked}}` the service writes for `target:"countries"`, read
/// leniently: a missing or damaged row drops itself, a bad whole reads as none.
func countryStats(json: String) -> [String: CountryStat]? {
    struct Row: Decodable { var chosen: Int?; var answered: Int?; var checked: Int? }
    guard let raw = try? JSONDecoder().decode([String: Row].self, from: Data(json.utf8)) else { return nil }
    return raw.mapValues { CountryStat(chosen: $0.chosen ?? 0, answered: $0.answered ?? 0, checked: $0.checked ?? 0) }
}

/// Hours between automatic updates of a subscription (Android `SubMath.intervalHours`): the provider's
/// own wish (never under `minProviderHours`) if it gave one, else the user's setting; 0 = manual only.
/// Doubled in the battery saving mode.
func intervalHours(_ userHours: Int, _ providerHours: Int?, _ eco: Bool) -> Int {
    if userHours <= 0 { return 0 }
    let base = (providerHours ?? 0) > 0 ? max(providerHours ?? 0, minProviderHours) : userHours
    return base * (eco ? 2 : 1)
}

/// The provider's own interval is followed, but never more often than this (Android
/// `SubMath.MIN_PROVIDER_HOURS`; the battery saving factor is `EcoMath.SUB_FACTOR` = 2).
let minProviderHours = 3

/// What the countries popup sorts by (Android `CountrySort`): its name in the settings JSON.
enum CountrySortJson: String, CaseIterable {
    case name = "NAME"
    case nameDesc = "NAME_DESC"
    case used = "USED"
    case stable = "STABLE"
}

/// The issues of Android's `Insecurity.issues`, read off the link instead of the parsed outbound —
/// the same answers for every link form the app takes: traffic unencrypted (no TLS/Reality and no
/// cipher of the protocol's own) or TLS whose certificate isn't checked (`allowInsecure` without a
/// pinned certificate). Only the fallback for a `StoredServer` without an `insecure` field (an older
/// service): the link read can't see what the parser defaults in, so a server the service judges
/// clean could still look flagged here — the service's field always wins when it is there.
func linkInsecureIssues(_ link: String) -> Set<InsecureIssue> {
    let s = link.trimmingCharacters(in: .whitespacesAndNewlines)
    guard let schemeEnd = s.range(of: "://") else { return [] }
    let proto = s[..<schemeEnd.lowerBound].lowercased()
    let rest = String(s[schemeEnd.upperBound...]).components(separatedBy: "#").first ?? ""
    let query = rest.split(separator: "?", maxSplits: 1).dropFirst().first.map(String.init) ?? ""
    var params: [String: String] = [:]
    for pair in query.split(separator: "&") {
        let kv = pair.split(separator: "=", maxSplits: 1).map(String.init)
        if kv.count == 2, let key = kv[0].removingPercentEncoding {
            params[key.lowercased()] = kv[1].removingPercentEncoding
        }
    }
    func param(_ names: String...) -> String? { names.lazy.compactMap { params[$0] }.first }
    let security = (param("security") ?? "none").lowercased()
    let transportEncrypted = security == "tls" || security == "reality"
    func truthy(_ v: String?) -> Bool { v?.lowercased() == "1" || v?.lowercased() == "true" }
    var out: Set<InsecureIssue> = []
    let ownCipher: Bool
    switch proto {
    case "hysteria", "hysteria2", "hy2", "wireguard", "wg":
        ownCipher = true
    case "vless":
        // The link's `encryption` (v2ray's `encryption` field), absent = none.
        ownCipher = (param("encryption") ?? "none").lowercased() != "none"
    case "vmess":
        ownCipher = !["none", "zero"].contains((param("scy", "security") ?? "auto").lowercased())
    case "ss", "shadowsocks":
        // ss://base64(method:password)@… or ss://method:password@… (the userinfo is the credential).
        let userinfo = rest.components(separatedBy: "@").first ?? ""
        let decoded = Data(base64Encoded: userinfo + String(repeating: "=", count: (4 - userinfo.count % 4) % 4))
            .flatMap { String(data: $0, encoding: .utf8) } ?? userinfo
        let method = (decoded.components(separatedBy: ":").first ?? "").lowercased()
        ownCipher = !["none", "plain", ""].contains(method)
    case "wdtt", "qwdtt":
        ownCipher = true   // the limited-network transport is its own encrypted session
    default:
        ownCipher = false  // trojan, socks, http and unknown schemes: no cipher of their own
    }
    if !transportEncrypted && !ownCipher && proto != "hysteria" { out.insert(.noEncryption) }
    if security == "tls", truthy(param("allowinsecure", "allow_insecure")),
       param("pinnedpeercertsha256", "pinned_peer_cert_sha256") == nil { out.insert(.noCertCheck) }
    return out
}
