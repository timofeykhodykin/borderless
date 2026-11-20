import Foundation

/// The text form of a user's own domain zone (Android `data/CustomZones.kt` `ZoneFile`): `#` comments, `name:`,
/// `[direct]` / `[server]` / `[apps]` sections, one entry per line. Plain domain lists, hosts files and the common rule
/// formats (Clash / Surge `DOMAIN-SUFFIX,…`, `+.x`, AdBlock `||x^`) load too; every entry is checked, because the core
/// refuses a config with a bad rule — what isn't understood is left out and reported.
public enum ZoneFile {
    public struct Parsed: Equatable {
        public var name: String?
        public var direct: [String] = []
        public var server: [String] = []
        public var apps: [String] = []
        /// Lines that are not an entry of any kind, as written.
        public var skipped: [String] = []
        /// Whether the file declared a […] section at all — one that can't say where its entries go is asked
        /// about on Android (a "where do the entries go" import prompt keys on this).
        public var declaresSections = false
    }

    public static func parse(_ text: String) -> Parsed {
        var out = Parsed()
        var section = "direct"
        for raw in text.split(whereSeparator: \.isNewline) {
            var line = raw.trimmingCharacters(in: .whitespaces)
            if line.isEmpty || line.hasPrefix("#") || line.hasPrefix("!") || line == "payload:" { continue }
            if line.lowercased().hasPrefix("name:") {
                out.name = String(line.dropFirst(5)).trimmingCharacters(in: .whitespaces)
                continue
            }
            if line.hasPrefix("["), line.hasSuffix("]") {
                section = String(line.dropFirst().dropLast()).lowercased()
                out.declaresSections = true
                continue
            }
            if let hash = line.range(of: " #") { line = String(line[..<hash.lowerBound]).trimmingCharacters(in: .whitespaces) }
            if section == "apps" {
                out.apps.append(line)
                continue
            }
            guard let e = entry(line) else {
                out.skipped.append(line)
                continue
            }
            if section == "server" { out.server.append(e) } else { out.direct.append(e) }
        }
        return out
    }

    /// One line as a routing entry, normalised; nil when it isn't one.
    public static func entry(_ line: String) -> String? {
        var s = line.trimmingCharacters(in: .whitespaces)
        if s.hasPrefix("- ") { s = String(s.dropFirst(2)).trimmingCharacters(in: CharacterSet(charactersIn: " '\"")) }
        // hosts: "0.0.0.0 example.com"
        let parts = s.split(separator: " ")
        if parts.count == 2, isIPv4(String(parts[0])) || parts[0] == "::" || parts[0] == "::1" { s = String(parts[1]) }
        // Clash / Surge rules
        let rule = s.split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }
        if rule.count >= 2 {
            switch rule[0].uppercased() {
            case "DOMAIN-SUFFIX": s = rule[1]
            case "DOMAIN": s = "full:" + rule[1]
            case "DOMAIN-KEYWORD": s = "keyword:" + rule[1]
            case "IP-CIDR", "IP-CIDR6": s = rule[1]
            default: return nil
            }
        }
        if s.hasPrefix("+.") { s = String(s.dropFirst(2)) }
        if s.hasPrefix("||") { s = String(s.dropFirst(2)).trimmingCharacters(in: CharacterSet(charactersIn: "^")) }
        if s.hasPrefix("domain:") { s = String(s.dropFirst(7)) }
        for prefix in ["full:", "keyword:", "geosite:", "geoip:"] where s.hasPrefix(prefix) {
            let rest = String(s.dropFirst(prefix.count))
            guard !rest.isEmpty, rest.allSatisfy({ $0.isLetter || $0.isNumber || "-._@!".contains($0) }) else { return nil }
            return prefix == "full:" && !isDomain(rest) ? nil : s
        }
        if s.hasPrefix("regexp:") {
            return regexpProblem(String(s.dropFirst(7))) == nil ? s : nil
        }
        if isIPv4(s) || isCIDR(s) || isIPv6(s) { return s }
        let domain = s.lowercased().trimmingCharacters(in: CharacterSet(charactersIn: "."))
        return isDomain(domain) ? domain : nil
    }

    /// Why [line] can't be a routing entry, nil when [entry] accepts it — for showing next to a refused line
    /// (a bare "skipped" leaves the user guessing why a rule was dropped).
    public static func problem(_ line: String) -> String? {
        let s = line.trimmingCharacters(in: .whitespaces)
        if s.hasPrefix("regexp:") { return regexpProblem(String(s.dropFirst(7))) }
        return entry(line) == nil ? "not a site, an address or a rule" : nil
    }

    /// Why a `regexp:` pattern is refused, nil when it may go to the core. Validity is checked with ICU
    /// ([NSRegularExpression]), but the core compiles these with Go's RE2, which has no look-around, atomic
    /// groups or backreferences: ICU accepts all of them, so those spellings are refused by hand — a rule that
    /// passed here and then failed in the core would take down the whole routing config.
    static func regexpProblem(_ pattern: String) -> String? {
        if pattern.isEmpty { return "empty regexp" }
        if let bad = pattern.re2Unsupported {
            return "\"\(bad)\" isn't supported by the core's regexp engine (RE2)"
        }
        return (try? NSRegularExpression(pattern: pattern)) == nil ? "not a valid regexp" : nil
    }

    /// An entry as the user thinks of it: "domain:" is how it is stored, not how it is written
    /// (Kotlin `ZoneFile.display`).
    public static func display(_ entry: String) -> String {
        entry.hasPrefix("domain:") ? String(entry.dropFirst(7)) : entry
    }

    /// Whether [s] is an address entry — an IPv4 / IPv6 literal or a range (Kotlin's `ZoneFile.ip` non-null).
    /// The zone-conflict check needs it: only address rules can cover each other by containment.
    public static func isIp(_ s: String) -> Bool {
        isIPv4(s) || isCIDR(s) || isIPv6(s)
    }

    /// The zone written back as a file someone else can load.
    public static func text(name: String, direct: [String], server: [String], apps: [String] = []) -> String {
        var lines = ["# Border(less) zone", "name: \(name)", "", "[direct]"] + direct
        lines += ["", "[server]"] + server
        if !apps.isEmpty { lines += ["", "[apps]"] + apps }
        return lines.joined(separator: "\n") + "\n"
    }

    static func isDomain(_ s: String) -> Bool {
        let labels = s.split(separator: ".", omittingEmptySubsequences: false)
        guard labels.count >= 1, s.count <= 253 else { return false }
        return labels.allSatisfy { l in
            !l.isEmpty && l.count <= 63 && !l.hasPrefix("-") && !l.hasSuffix("-")
                && l.allSatisfy { $0.isLetter || $0.isNumber || $0 == "-" || $0 == "_" }
        } && labels.last!.contains { $0.isLetter }
    }

    static func isIPv4(_ s: String) -> Bool {
        let p = s.split(separator: ".", omittingEmptySubsequences: false)
        // An octet is 1–3 digits without a leading zero and ≤ 255 ("0.0.0.01" is text, not an address).
        return p.count == 4 && p.allSatisfy {
            (1...3).contains($0.count) && $0.allSatisfy(\.isASCIIDigit)
                && ($0.count == 1 || !$0.hasPrefix("0")) && Int($0).map { $0 <= 255 } == true
        }
    }

    static func isIPv6(_ s: String) -> Bool {
        var addr = in6_addr()
        return s.contains(":") && inet_pton(AF_INET6, s, &addr) == 1
    }

    static func isCIDR(_ s: String) -> Bool {
        let p = s.split(separator: "/", omittingEmptySubsequences: false)
        // The mask is plain digits only ("1.2.3.0/+24" and "…/024" are texts the core refuses, not prefixes).
        guard p.count == 2, !p[1].isEmpty, p[1].allSatisfy(\.isASCIIDigit),
              p[1] == "0" || !p[1].hasPrefix("0"), let bits = Int(p[1]) else { return false }
        if isIPv4(String(p[0])) { return (0...32).contains(bits) }
        if isIPv6(String(p[0])) { return (0...128).contains(bits) }
        return false
    }
}

private extension String {
    /// The first construct the core's engine (RE2) doesn't have, nil when there is none. Deliberately
    /// conservative: the spellings are looked for wherever they appear — inside a character class or behind
    /// another backslash they might be literal text, and the rule is refused anyway.
    var re2Unsupported: String? {
        // Look-around and atomic groups, (?#… comments, and the \k \C \K escapes (the longer spellings
        // first, only so the message names them right).
        for construct in ["(?<=", "(?<!", "(?=", "(?!", "(?>", "(?#", "\\k<", "\\C", "\\K"] where contains(construct) {
            return construct
        }
        // Backreferences \1…\9 and the escapes RE2 never had (\Z \G \R \X \h — \z \A \b \B it has). A "+"
        // right behind a quantifier (* + ? or a {m,n} repetition's '}') is a possessive quantifier, and a
        // (?flag group may hold only RE2's i m s U — the rest ((?d (?w (?x…) is ICU's own. A
        // "\\\\"-escaped backslash can't start any of it, so the scan moves pairwise.
        var i = startIndex
        var quantifier: Character? = nil
        var openBrace = false
        while i < endIndex {
            let c = self[i]
            if c == "\\" {
                let j = index(after: i)
                guard j < endIndex else { return nil }
                if ("1"..."9").contains(self[j]) || "ZGRXh".contains(self[j]) { return "\\\(self[j])" }
                quantifier = nil
                i = index(after: j)
                continue
            }
            if c == "+", let q = quantifier { return "\(q)+" }
            if c == "(", index(after: i) < endIndex, self[index(after: i)] == "?" {
                // A flag group (?flags) or (?flags:…): named groups don't reach the check (the letter scan
                // stops at '<'), look-arounds and (?#… were refused above.
                var j = index(after: index(after: i))
                var flags = ""
                while j < endIndex, self[j].isLetter || self[j] == "-" {
                    flags.append(self[j])
                    j = index(after: j)
                }
                if j < endIndex, self[j] == ")" || self[j] == ":",
                   let bad = flags.first(where: { !"imsU-".contains($0) }) {
                    return "(?\(bad)"
                }
            }
            if c == "{" {
                // {m} / {m,} / {m,n} is a repetition — its '}' is the quantifier a '+' could follow; any
                // other '{' is a literal brace (\p{…}'s one included, its \p was consumed above).
                var j = index(after: i)
                while j < endIndex, self[j].isASCIIDigit { j = index(after: j) }
                if j > index(after: i), j < endIndex {
                    if self[j] == "," {
                        j = index(after: j)
                        while j < endIndex, self[j].isASCIIDigit { j = index(after: j) }
                    }
                    openBrace = j < endIndex && self[j] == "}"
                }
            }
            quantifier = "*+?".contains(c) ? c : (c == "}" && openBrace ? "}" : nil)
            if c == "}" { openBrace = false }
            i = index(after: i)
        }
        return nil
    }
}
