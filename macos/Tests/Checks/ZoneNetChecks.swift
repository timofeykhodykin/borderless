import Foundation
import Shared

/// More of [ZoneFile] (the user-zone text format — audit A16/A17), [UndoRecord]'s strict grammar and the
/// [NetworkSnapshot] questions the service asks before touching the network. The zone JSON the service and the app
/// exchange (`ZoneJson`, `MacEngine.saveZone`/`zones`) is app- and service-side and can't be imported here; what is
/// checked is the file format both sides agree on.
func zoneNetChecks() {
    check("zone file: the other formats it reads") {
        let z = ZoneFile.parse("""
        name: Second
        - 'quoted.example.com'
        domain:prefixed.example.org
        DOMAIN,exact.example.net
        DOMAIN-KEYWORD,video
        IP-CIDR,203.0.113.0/24
        IP-CIDR6,2001:db8::/32
        kept.example.com # an inline note
        ! an adblock comment
        payload:
        [weird]
        after.example.org
        [APPS]
        com.example.anything goes here
        """)
        expect(z.name == "Second", "name")
        expect(z.direct == ["quoted.example.com", "prefixed.example.org", "full:exact.example.net", "keyword:video",
                            "203.0.113.0/24", "2001:db8::/32", "kept.example.com", "after.example.org"],
               "direct: \(z.direct)")
        expect(z.apps == ["com.example.anything goes here"], "[apps] lines are kept verbatim: \(z.apps)")
        expect(z.skipped.isEmpty, "nothing skipped: \(z.skipped)")
    }

    check("zone entries: the last label needs a letter, labels have limits") {
        expect(ZoneFile.entry("1.2.3.256") == nil, "an octet past 255")
        expect(ZoneFile.entry("example.123") == nil, "an all-digit last label is an address attempt, not a domain")
        expect(ZoneFile.entry("EXAMPLE.COM.") == "example.com", "case and a trailing dot are normalised")
        expect(ZoneFile.entry("a_b.example.com") == "a_b.example.com", "an underscore label")
        expect(ZoneFile.entry(String(repeating: "x", count: 64) + ".com") == nil, "a label past 63 characters")
        expect(ZoneFile.entry("::1 example.com") == "example.com", "a hosts line with an IPv6 address")
        expect(ZoneFile.problem("not a thing") == "not a site, an address or a rule", "a plain refusal explained")
        expect(ZoneFile.problem("example.com") == nil, "no problem for a good line")
        let z = ZoneFile.parse("regexp:(?=x)\nexample.com")
        expect(z.skipped == ["regexp:(?=x)"], "a refused rule is reported skipped")
    }

    check("zone entries: more of what RE2 can't and can compile") {
        expect(ZoneFile.entry("regexp:\\\\1") == "regexp:\\\\1", "an escaped backslash, then a literal digit")
        expect(ZoneFile.entry("regexp:(?<n>a)") == "regexp:(?<n>a)", "a named group both engines have")
        expect(ZoneFile.entry("regexp:(?P<n>a)") == nil, "the Python spelling the validator refuses")
        expect(ZoneFile.entry("regexp:a*+") == nil, "a possessive star")
        expect(ZoneFile.entry("regexp:a?+") == nil, "a possessive question mark")
        expect(ZoneFile.entry("regexp:(?x)a") == nil, "an extended-mode flag is not RE2's")
        expect(ZoneFile.entry("regexp:\\1") == nil, "a backreference")
        expect(ZoneFile.entry("regexp:(?=a)") == nil, "a lookahead")
        expect(ZoneFile.problem("regexp:(?=a)")?.contains("RE2") == true, "the lookahead refusal names the engine")
    }

    check("zone export writes [apps] only when there are apps") {
        expect(!ZoneFile.text(name: "N", direct: ["example.com"], server: []).contains("[apps]"), "no apps, no section")
        let withApps = ZoneFile.text(name: "N", direct: [], server: [], apps: ["com.example.app"])
        expect(withApps.contains("[apps]\ncom.example.app"), "the section and its line")
        let back = ZoneFile.parse(withApps)
        expect(back.apps == ["com.example.app"] && back.name == "N", "and it reads back")
    }

    check("undo records: more of the strict grammar") {
        expect(UndoRecord(line: "route inet6 ::/1 utun7") == .route(.inet6, "::/1", "utun7"), "an inet6 half route")
        expect(UndoRecord(line: "route inet6 fe80::1/64 utun7") == .route(.inet6, "fe80::1/64", "utun7"),
               "a link-local inet6 route")
        expect(UndoRecord(line: "utun utun07") == .utun("utun07"), "a leading-zero utun is still a utun")
        for line in ["route inet6 ::/129 utun7", "route inet 0.0.0.0/-1 utun7", "route inet 0.0.0.0/1 utun-7",
                     "key State:/Network/Service/app.borderless.tunnel/DHCP"] {
            expect(UndoRecord(line: line) == nil, "rejects \(line)")
        }
    }

    check("the wide destinations are exactly the tunnel's own") {
        expect(NetworkPlan.wideDestinations == ["default", "0/1", "128.0/1", "0.0.0.0/1", "128.0.0.0/1", "::/1", "8000::/1"],
               "the routes a tunnel takes over: \(NetworkPlan.wideDestinations)")
    }

    check("another tunnel: an unscoped foreign default counts, our own service doesn't") {
        let foreign = NetworkSnapshot(takenAt: Date(), primaryInterface: "en0", primaryService: "A", router: nil,
                                      dnsServers: [], defaultRoutes: [
                                          .init(destination: "default", gateway: "fe80::%utun2", interface: "utun2",
                                                flags: "UGcg"),
                                      ], tunnels: [])
        expect(foreign.otherTunnel(ours: nil) == "utun2", "a scoped-looking but unflagged default steers traffic")
        let ownPrimary = NetworkSnapshot(takenAt: Date(), primaryInterface: "utun4",
                                         primaryService: NetworkPlan.serviceID, router: nil, dnsServers: [],
                                         defaultRoutes: [], tunnels: [])
        expect(ownPrimary.otherTunnel(ours: nil) == nil, "the primary utun of our own service")
    }

    check("differences: the primary change is named, only our leftovers count") {
        let before = NetworkSnapshot(takenAt: Date(), primaryInterface: "en0", primaryService: "A",
                                     router: "192.0.2.1", dnsServers: ["192.0.2.53"], defaultRoutes: [], tunnels: [])
        var after = before
        after.primaryInterface = "utun9"
        expect(after.differences(from: before) == ["primary interface en0 → utun9"], "the primary change")
        after.defaultRoutes = [.init(destination: "0/1", gateway: "link#30", interface: "utun9")]
        expect(after.differences(from: before).count == 1,
               "a route on a utun that isn't ours isn't a leftover of ours")
        after.tunnels = [.init(serviceID: NetworkPlan.serviceID, interface: "utun9")]
        let d = after.differences(from: before)
        expect(d.count == 2 && d.contains { $0.contains("our routes still present: 0/1") }, "ours counts it: \(d)")
    }

    check("the physical interface skips the virtual names") {
        for name in ["awdl0", "anpi0", "llw0", "ipsec0", "ppp0", "bridge0", "utun6", "lo0"] {
            let s = NetworkSnapshot(takenAt: Date(), primaryInterface: name, primaryService: "A", router: nil,
                                    dnsServers: [], defaultRoutes: [
                                        .init(destination: "default", gateway: "192.0.2.1", interface: "en0",
                                              flags: "UGScg"),
                                    ], tunnels: [])
            expect(s.physicalInterface(ours: nil) == "en0", "\(name) is not physical")
        }
    }

    check("the route table needs its header, short rows are dropped") {
        expect(RouteTable.parse("no header here\n1.2.3.4 5.6.7.8 UGScg en0").isEmpty, "no header, no routes")
        let routes = RouteTable.parse("""
        Destination        Gateway            Flags               Netif
        default            192.0.2.1          UGScg                 en0
        short              gw
        """)
        expect(routes.count == 1 && routes[0].interface == "en0" && routes[0].flags == "UGScg",
               "one real row: \(routes)")
    }
}
