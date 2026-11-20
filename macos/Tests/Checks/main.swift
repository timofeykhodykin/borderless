import Foundation
import BorderlessShared
import Shared

nonisolated(unsafe) var failures = 0

func expect(_ ok: Bool, _ what: String, line: Int = #line) {
    if !ok {
        failures += 1
        print("FAIL line \(line): \(what)")
    }
}

func check(_ name: String, _ body: () throws -> Void) {
    do { try body() } catch {
        failures += 1
        print("FAIL \(name): \(error)")
    }
}

check("lines keep the unfinished rest") {
    var buffer = try Wire.encode(Request(.hello))
    buffer.append(contentsOf: Array(#"{"kind":"sta"#.utf8))
    let lines = Wire.lines(&buffer)
    expect(lines.count == 1, "one complete line")
    expect(try Wire.decode(Request.self, line: lines[0]) == Request(.hello), "the line decodes")
    expect(String(decoding: buffer, as: UTF8.self) == #"{"kind":"sta"#, "the rest stays")
}

check("menu bar: the press that closed the panel is dropped") {
    // Times from a real click at the top edge of the screen (mac-claude-usage): closed 6 ms after the press.
    expect(MenuBarClick.closedWithPress(pressAt: 947648.102, closedAt: 947648.108, dropdownVisible: false), "closed by this press")
    expect(!MenuBarClick.closedWithPress(pressAt: 947646.994, closedAt: 947645.772, dropdownVisible: false), "closed a second before")
    expect(!MenuBarClick.closedWithPress(pressAt: 10, closedAt: 10, dropdownVisible: true), "still open")
    expect(!MenuBarClick.closedWithPress(pressAt: 10, closedAt: -.infinity, dropdownVisible: false), "never closed")
}

check("zone file: sections, formats, bad lines") {
    let z = ZoneFile.parse("""
    # comment
    name: My zone
    example.com
    full:exact.example.org
    0.0.0.0 ads.example.net
    DOMAIN-SUFFIX,suffix.example.com
    ||adblock.example.com^
    +.plus.example.com
    203.0.113.0/24
    2001:db8::/32
    not a domain at all
    [server]
    keyword:video
    geosite:ru
    regexp:^api\\.
    [apps]
    com.example.app
    """)
    expect(z.name == "My zone", "name")
    expect(z.direct == ["example.com", "full:exact.example.org", "ads.example.net", "suffix.example.com", "adblock.example.com",
                        "plus.example.com", "203.0.113.0/24", "2001:db8::/32"], "direct: \(z.direct)")
    expect(z.server == ["keyword:video", "geosite:ru", "regexp:^api\\."], "server: \(z.server)")
    expect(z.apps == ["com.example.app"], "apps")
    expect(z.skipped == ["not a domain at all"], "skipped: \(z.skipped)")
    expect(ZoneFile.entry("regexp:(") == nil, "a broken regexp is refused")
    expect(ZoneFile.entry("10.0.0.0/33") == nil, "a prefix too long is refused")
    expect(ZoneFile.entry("203.0.113.04") == nil, "a leading-zero octet is refused")
    expect(ZoneFile.entry("203.0.113.0/+24") == nil, "a signed mask is refused")
    expect(ZoneFile.entry("203.0.113.0/024") == nil, "a leading-zero mask is refused")
    expect(ZoneFile.entry("203.0.113.0/24/") == nil, "a trailing slash is refused")
    expect(ZoneFile.entry("203.0.113.0/0") == "203.0.113.0/0", "a zero mask is fine")
    expect(ZoneFile.entry("regexp:a++") == nil, "a possessive quantifier is refused")
    expect(ZoneFile.entry("regexp:a{2,3}+") == nil, "a possessive repetition is refused")
    expect(ZoneFile.entry("regexp:(?#note)a") == nil, "a comment group is refused")
    expect(ZoneFile.entry("regexp:\\Z") == nil, "\\Z is not RE2")
    expect(ZoneFile.entry("regexp:\\G") == nil, "\\G is not RE2")
    expect(ZoneFile.entry("regexp:\\h") == nil, "\\h is not RE2")
    expect(ZoneFile.entry("regexp:(?d)a") == nil, "an ICU-only flag is refused")
    expect(ZoneFile.entry("regexp:(?w)a") == nil, "an ICU-only flag group is refused")
    expect(ZoneFile.problem("regexp:a++")?.contains("RE2") == true, "the refusal names the engine")
    expect(ZoneFile.entry("regexp:(?i)a") == "regexp:(?i)a", "an RE2 flag group")
    expect(ZoneFile.entry("regexp:\\p{L}+") == "regexp:\\p{L}+", "a quantified class")
    expect(ZoneFile.entry("regexp:\\*+") == "regexp:\\*+", "a quantified literal")
    let again = ZoneFile.parse(ZoneFile.text(name: "Z", direct: z.direct, server: z.server))
    expect(again.direct == z.direct && again.server == z.server && again.name == "Z", "round trip")
    let withApps = ZoneFile.parse(ZoneFile.text(name: "Z", direct: z.direct, server: z.server, apps: z.apps))
    expect(withApps.apps == z.apps, "the [apps] section survives the round trip")
}

check("the Kotlin shared module answers from Swift") {
    expect(MacBridge.shared.title(name: "\u{1F1E9}\u{1F1EA} Frankfurt") == "Frankfurt", "a flag is taken off the name")
    MacBridge.shared.setUp(assets: "../android/src/main/assets", language: { "en" }) { key, args in "\(key)(\(args.joined(separator: ",")))" }
    let link = "vless://11111111-2222-3333-4444-555555555555@example.com:443?type=tcp&security=tls&sni=example.com#%F0%9F%87%B3%F0%9F%87%B1%20Amsterdam"
    let parsed = MacBridge.shared.parse(text: link + "\ntuic://a@b.example:1")
    let obj = try JSONSerialization.jsonObject(with: Data(parsed.utf8)) as? [String: Any]
    let server = ((obj?["groups"] as? [[String: Any]])?.first?["servers"] as? [[String: Any]])?.first
    expect(server?["title"] as? String == "Amsterdam" && server?["country"] as? String == nil, "parsed: \(parsed)")
    let problem = (obj?["problems"] as? [[String: Any]])?.first?["text"] as? String
    expect(problem == "imp_unsupported(TUIC)", "the explanation comes from our texts: \(problem ?? "none")")
    let built = MacBridge.shared.config(link: link, bindInterface: "en0", resolver: "192.0.2.53")
    expect(built.contains("\\\"interface\\\":\\\"en0\\\"") && built.contains("192.0.2.53"), "config bound to en0")
    expect(MacBridge.shared.config(link: "nonsense", bindInterface: "en0", resolver: "192.0.2.53").contains("\"error\""), "a bad link")
}

check("reply round-trips") {
    let reply = Reply(version: "0.1.0", tunnel: .off, error: nil)
    var data = try Wire.encode(reply)
    let lines = Wire.lines(&data)
    expect(try Wire.decode(Reply.self, line: lines[0]) == reply, "same reply")
    expect(data.isEmpty, "nothing left")
}

check("socket address fits") {
    var addr = unixAddress(ServicePaths.socket)
    let path = withUnsafeBytes(of: &addr.sun_path) { String(cString: $0.bindMemory(to: CChar.self).baseAddress!) }
    expect(path == ServicePaths.socket, "path survives sockaddr_un")
}

check("undo records are strict") {
    let good: [UndoRecord] = [
        .utun("utun0"), .utun("utun7"), .utun("utun9"), .utun("utun12345"),
        .route(.inet, "0.0.0.0/1", "utun7"), .route(.inet6, "8000::/1", "utun12"),
        .key("State:/Network/Service/app.borderless.tunnel/DNS"), .key("State:/Network/Service/app.borderless.tunnel/IPv4"),
    ]
    for r in good { expect(UndoRecord(line: r.line) == r, "round trip \(r.line)") }
    let bad = [
        "route inet 0.0.0.0/1 en0",                 // not our kind of interface
        "route inet 0.0.0.0/33 utun7",              // impossible prefix
        "route inet 300.0.0.0/1 utun7",
        "route inet ::/1 utun7",                    // wrong family
        "route inet 0.0.0.0/1 utun7 extra",
        "key State:/Network/Global/DNS",            // not our key
        "dns State:/Network/Service/app.borderless.tunnel/DNS", // no such kind
        "utun utun",
        "utun utun7x",
        "utun utunx7",
        "rm -rf /",
    ]
    for line in bad { expect(UndoRecord(line: line) == nil, "rejects \(line)") }
    expect(UndoRecord.parse("utun utun3\ngarbage\nroute inet 128.0.0.0/1 utun3\n").count == 2, "skips garbage")
}

let netstat = """
Routing tables

Internet:
Destination        Gateway            Flags               Netif Expire
default            link#22            UCSg                utun4
default            192.0.2.1          UGScIg                en0
0/1                link#30            UCS                 utun9
192.0.2            link#14            UCS                   en0      !
192.0.2.53         link#22            UHWIig              utun4

Internet6:
Destination                             Gateway                                 Flags               Netif Expire
default                                 fe80::%utun0                            UGcIg               utun0
::/1                                    link#30                                 UCS                 utun9
"""

check("route table") {
    let routes = RouteTable.parse(netstat)
    expect(routes.count == 7, "seven routes, got \(routes.count)")
    let wide = RouteTable.wide(routes)
    expect(wide.map(\.interface) == ["utun4", "en0", "utun9", "utun0", "utun9"], "wide routes \(wide.map(\.interface))")
}

check("another tunnel is noticed, ours is not") {
    let wide = RouteTable.wide(RouteTable.parse(netstat))
    let other = NetworkSnapshot(takenAt: Date(), primaryInterface: "utun4", primaryService: "03D60333",
                                router: "240.0.0.2", dnsServers: ["192.0.2.53"], defaultRoutes: wide, tunnels: [])
    expect(other.otherTunnel(ours: "utun9") == "utun4", "primary utun of another service")
    let plain = NetworkSnapshot(takenAt: Date(), primaryInterface: "en0", primaryService: "AAAA", router: "192.0.2.1",
                                dnsServers: [], defaultRoutes: [
                                    .init(destination: "default", gateway: "192.0.2.1", interface: "en0", flags: "UGScg"),
                                    // macOS's own utun0…3: scoped IPv6 defaults, not a tunnel of anyone's
                                    .init(destination: "default", gateway: "fe80::%utun0", interface: "utun0",
                                          flags: "UGcIg"),
                                ], tunnels: [])
    expect(plain.otherTunnel(ours: nil) == nil, "plain Wi-Fi with the system's scoped utun routes")
    let other6 = NetworkSnapshot(takenAt: Date(), primaryInterface: "en0", primaryService: "AAAA", router: nil,
                                 dnsServers: [], defaultRoutes: [
                                     .init(destination: "::/1", gateway: "link#30", interface: "utun9", flags: "UCS"),
                                 ], tunnels: [])
    expect(other6.otherTunnel(ours: nil) == "utun9", "another client's unscoped half route")
    let mine = NetworkSnapshot(takenAt: Date(), primaryInterface: "utun9", primaryService: NetworkPlan.serviceID,
                               router: nil, dnsServers: [], defaultRoutes: [
                                   .init(destination: "default", gateway: "192.0.2.1", interface: "en0"),
                                   .init(destination: "0/1", gateway: "link#30", interface: "utun9"),
                               ], tunnels: [])
    expect(mine.otherTunnel(ours: "utun9") == nil, "our own tunnel")
}

check("the physical interface while we are primary") {
    let ours = NetworkSnapshot(takenAt: Date(), primaryInterface: "utun9", primaryService: NetworkPlan.serviceID,
                               router: "172.19.0.2", dnsServers: [], defaultRoutes: [
                                   .init(destination: "default", gateway: "link#30", interface: "utun9", flags: "UCSg"),
                                   .init(destination: "default", gateway: "192.0.2.1", interface: "en0", flags: "UGScIg"),
                                   .init(destination: "default", gateway: "fe80::%utun0", interface: "utun0", flags: "UGcIg"),
                               ], tunnels: [])
    expect(ours.physicalInterface(ours: "utun9") == "en0", "the scoped default's interface")
    expect(ours.otherTunnel(ours: "utun9") == nil, "our own primary tunnel is not another one")
    var known = ours
    known.tunnels = [.init(serviceID: NetworkPlan.serviceID, interface: "utun9")]
    expect(known.otherTunnel(ours: nil) == nil, "ours by its service ID, without being told")
    let plain = NetworkSnapshot(takenAt: Date(), primaryInterface: "en0", primaryService: "A", router: nil,
                                dnsServers: [], defaultRoutes: [], tunnels: [])
    expect(plain.physicalInterface(ours: nil) == "en0", "the primary one")
    let shim = NetworkSnapshot(takenAt: Date(), primaryInterface: "ipsec0", primaryService: "A", router: nil,
                               dnsServers: [], defaultRoutes: [
                                   .init(destination: "default", gateway: "192.0.2.1", interface: "bridge0",
                                         flags: "UGScg"),
                                   .init(destination: "default", gateway: "192.0.2.1", interface: "en0",
                                         flags: "UGScg"),
                               ], tunnels: [])
    expect(shim.physicalInterface(ours: nil) == "en0", "Tunnel shims and virtual NICs are not physical")
}

check("differences after a restore") {
    let before = NetworkSnapshot(takenAt: Date(), primaryInterface: "en0", primaryService: "A", router: "192.0.2.1",
                                 dnsServers: ["192.0.2.53"], defaultRoutes: [], tunnels: [])
    var after = before
    expect(after.differences(from: before).isEmpty, "same state")
    after.dnsServers = ["198.51.100.53"]
    after.tunnels = [.init(serviceID: NetworkPlan.serviceID, interface: "utun9")]
    after.defaultRoutes = [.init(destination: "0/1", gateway: "link#30", interface: "utun9")]
    let d = after.differences(from: before)
    expect(d.count == 2, "DNS and a leftover route: \(d)")
}

shareChecks()
wireChecks()
zoneNetChecks()
signingChecks()
svcDaemonChecks()
energyChecks()
updateChecks()

// Probe markers follow the rendered polyline, including corners, steps and log-axis coordinates.
let trend = ChartProbe.medianTrend((0..<9).map { CGPoint(x: CGFloat($0), y: CGFloat($0 * 10)) }, chunkSize: 4)
expect(trend.count == 3 && trend[0].y == 15 && trend[1].y == 55 && trend[2].y == 80,
       "dense chart medians handle later chunks and a short tail")
expect(trend[1].x == 6, "later median keeps its own central timestamp")
expect(ChartProbe.medianTrend([], chunkSize: 4).isEmpty, "empty chart trend")
let probeLine = [CGPoint(x: 0, y: 10), CGPoint(x: 10, y: 0), CGPoint(x: 20, y: 20)]
expect(ChartProbe.point(at: 9, on: probeLine)?.y == 1, "probe before a corner")
expect(ChartProbe.point(at: 11, on: probeLine)?.y == 2, "probe after a corner")
expect(ChartProbe.point(at: 9, on: probeLine, stepped: true)?.y == 10, "probe on a step")
expect(ChartProbe.point(at: 10, on: probeLine, stepped: true)?.y == 0, "probe at step boundary")
expect(ChartProbe.point(at: 22, on: probeLine) == nil, "probe never crosses a gap")
expect(ChartProbe.point(at: 22, on: probeLine, stepped: true, tail: 4)?.y == 20, "probe on step tail")

print(failures == 0 ? "all checks passed" : "\(failures) failed")
exit(failures == 0 ? 0 : 1)
