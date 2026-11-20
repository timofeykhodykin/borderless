import Foundation
import BorderlessShared
import Shared

/// The `borderless://import?d=` bundle (shared `ShareFormat`: the JSON, raw deflate, base64url without padding —
/// the only form every Border(less) build reads; audit A4 was a zlib stream that decoded nowhere else). Both ways go
/// through `MacBridge` exactly as the app does: `encodeShare` writes (ShareDialog's `ShareFormat.encode`), `parse`
/// reads (`SharedCode.parse`). `SharedCode.Parsed` itself lives in the app target and can't be imported here; the
/// same JSON is checked field by field instead.
func shareChecks() {
    MacBridge.shared.setUp(assets: "../android/src/main/assets", language: { "en" }) { key, args in
        "\(key)(\(args.joined(separator: ",")))"
    }
    func parsed(_ text: String) -> [String: Any] {
        (try? JSONSerialization.jsonObject(with: Data(MacBridge.shared.parse(text: text).utf8))) as? [String: Any] ?? [:]
    }
    func groups(_ p: [String: Any]) -> [[String: Any]] { p["groups"] as? [[String: Any]] ?? [] }
    func problems(_ p: [String: Any]) -> [[String: Any]] { p["problems"] as? [[String: Any]] ?? [] }
    func servers(_ g: [String: Any]) -> [[String: Any]] { g["servers"] as? [[String: Any]] ?? [] }
    func subs(_ g: [String: Any]) -> [[String: Any]] { g["subscriptions"] as? [[String: Any]] ?? [] }

    // A bundle as Android's ShareFormat.encode writes it ({"groups":[…]}, the defaults left out; raw deflate —
    // zlib wbits -15 — then base64url without '='), produced outside the shared code so the Mac's decoder is
    // checked against the format, not against itself. It holds two groups: "Работа" with a visible server,
    // a hidden ("off") one and a subscription that keeps one hidden key; and the default group with one server.
    // Regenerate: base64url(deflateRaw(json)) of the JSON in ShareFormatTest's shape.
    let android = "borderless://import?d=vZFNSsRAEEbvUoFZTRucRIWGoDfQxewki5hUJoFOd9M_0WHIxpN4hPECniHeyAoJQzsgzMq37P6KelV1gJ1RXlvgzweQRYfAYfwYj-Pn-PX9Ph5hDRZNj2ZOCPruBVrL4_h6gW0IlhAsJdhNwAO-FZ0WeFWqjqdpcu_2GjNX6pXF0pvW7TMn7MrKNguS0aNEGNa_220Wzjqx24BTO2V2F7ajZLR9VTSnqmvgzngcchrav8wTezNJNM7pSSKoiilCVcvOnozq2woNvTRtVaGk4n9Qh3zIp0VVWBdeuNn_75MlC2cO7C7gJCLRXShCyWjbGKSjTT4_"

    /// The shared assertions on the decoded bundle, run for the foreign payload and the Mac-written one alike.
    func expectBundle(_ p: [String: Any], _ what: String) {
        let g = groups(p)
        expect(g.count == 2, "\(what): two groups: \(g)")
        guard g.count == 2 else { return }
        expect(g[0]["name"] as? String == "Работа" && g[0]["default"] as? Bool == false, "\(what): the named group")
        let s = servers(g[0])
        expect(s.count == 2, "\(what): two servers")
        expect(s[0]["title"] as? String == "One" && s[0]["hidden"] as? Bool == false, "\(what): the visible server")
        expect(s[1]["title"] as? String == "Two" && s[1]["hidden"] as? Bool == true, "\(what): 'off' reads as hidden")
        let u = subs(g[0])
        expect(u.count == 1 && u[0]["url"] as? String == "https://example.org/sub" && u[0]["name"] as? String == "Provider",
               "\(what): the subscription")
        expect(u[0]["hidden"] as? [String] == ["vless://22222222-3333-4444-5555-666666666666@example.org:443?type=tcp&security=tls&sni=example.org"],
               "\(what): the subscription's hidden keys")
        expect(g[1]["default"] as? Bool == true && servers(g[1]).count == 1, "\(what): the default group")
        expect(problems(p).isEmpty, "\(what): no problems: \(problems(p))")
    }

    check("a share bundle written by Android reads on the Mac") {
        expectBundle(parsed(android), "android bundle")
    }

    check("a share bundle written on the Mac reads back") {
        let groups = #"[{"name":"Работа","servers":[{"link":"vless://11111111-2222-3333-4444-555555555555@example.com:443?type=tcp&security=tls&sni=example.com#One"},{"link":"vless://22222222-3333-4444-5555-666666666666@example.org:443?type=tcp&security=tls&sni=example.org#Two","off":true}],"subs":[{"url":"https://example.org/sub","name":"Provider","hidden":["vless://22222222-3333-4444-5555-666666666666@example.org:443?type=tcp&security=tls&sni=example.org"]}]},{"default":true,"servers":[{"link":"vless://33333333-4444-5555-6666-777777777777@example.net:443?type=tcp&security=tls&sni=example.net#Three"}]}]"#
        let encoded = MacBridge.shared.encodeShare(groupsJson: groups)
        expect(encoded.hasPrefix("borderless://import?d="), "the prefix: \(encoded)")
        let d = String(encoded.dropFirst("borderless://import?d=".count))
        // Base64url without padding: the same alphabet Android's `base64UrlEncode` writes.
        expect(!d.isEmpty && d.allSatisfy { $0.isLetter || $0.isNumber || $0 == "-" || $0 == "_" }, "base64url, unpadded")
        // Byte for byte what the foreign payload above carries: same format, same bytes.
        expect(encoded == android, "the encoder writes the format's own bytes")
        expectBundle(parsed(encoded), "encoded bundle")
    }

    check("a share bundle keeps working past '&' and among plain links") {
        expectBundle(parsed(android + "&x=1"), "bundle with a trailing parameter")
        let loose = "vless://44444444-5555-6666-7777-888888888888@loose.example.net:443?type=tcp&security=tls&sni=loose.example.net#Loose"
        let g = groups(parsed(loose + "\n" + android))
        expect(g.count == 3, "the loose link makes its own group: \(g.count)")
        expect(g.first?["name"] is NSNull && servers(g.first ?? [:]).first?["title"] as? String == "Loose",
               "loose links land in the unnamed first group")
    }

    check("damaged share data is a problem, not a crash or a silent nothing") {
        for (what, text) in [
            ("not base64", "borderless://import?d=not-base64!"),
            // The same JSON through zlib's stream (wbits +15) — the form audit A4's encoder wrongly wrote:
            // raw inflate must refuse it.
            ("a zlib stream", "borderless://import?d=eNq9kU1KxEAQRu9SgVlNG5xEhYagN9DF7CSLmFQmgU530z_RYcjGk3iE8QKeId7ICglDOyDMyrfs_op6VXWAnVFeW-DPB5BFh8Bh_BiP4-f49f0-HmENFk2PZk4I-u4FWsvj-HqBbQiWECwl2E3AA74VnRZ4VaqOp2ly7_YaM1fqlcXSm9btMyfsyso2C5LRo0QY1r_bbRbOOrHbgFM7ZXYXtqNktH1VNKeqa-DOeBxyGtq_zBN7M0k0zulJIqiKKUJVy86ejOrbCg29NG1VoaTif1CHfMinRVVYF1642f_vkyULZw7sLuAkItFdKELJaNsYpKNNPj_2BcCt"),
            // Inflates fine but stops mid-JSON.
            ("a truncated bundle", "borderless://import?d=q1ZKL8ovLShWsooGAA"),
        ] {
            let p = parsed(text)
            let pr = problems(p)
            expect(groups(p).isEmpty, "\(what): no groups")
            expect(pr.count == 1 && pr[0]["text"] as? String == "imp_damaged()", "\(what): a damaged problem: \(pr)")
        }
        expect(MacBridge.shared.encodeShare(groupsJson: "nonsense").isEmpty, "unreadable JSON encodes as nothing")
    }
}
