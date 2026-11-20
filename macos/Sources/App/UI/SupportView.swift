import SwiftUI

/// Settings → "Protocols and formats" (Android `SupportSection`): what the app runs, each category in a dashed frame of
/// its own colour, with how many of the user's configs use each item (0 dimmed). Counted from the servers' protocol
/// lines for now — the shared module's `SupportStats` counts from the configs themselves; wiring it needs a
/// `MacBridge`/`SharedCode` seam (it isn't exported yet).
struct SupportView: View {
    var model: MainModel
    /// The counted features (empty first, as Android's `produceState(emptyMap)` shows): the word-splitting runs
    /// off the window's thread — Android does the count on `Dispatchers.Default`.
    @State private var counts: [String: Int] = [:]

    static let protocols = [("vless", "VLESS"), ("vmess", "VMess"), ("trojan", "Trojan"), ("shadowsocks", "Shadowsocks"),
                            ("hysteria", "Hysteria2"), ("wireguard", "WireGuard"), ("socks", "SOCKS")]
    static let transports = [("tcp", "TCP"), ("ws", "WebSocket"), ("grpc", "gRPC"), ("xhttp", "XHTTP"),
                             ("httpupgrade", "HTTPUpgrade"), ("h2", "HTTP/2"), ("kcp", "mKCP")]
    static let security = [("reality", "Reality"), ("tls", "TLS"), ("vision", "XTLS Vision")]
    /// The limited-network protocol's name (shared `LimitedLink.PROTOCOL`, not exported to the Mac yet).
    static let limitedProtocol = "WDTT"

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(L("sup_legend")).font(Fonts.body(12)).foregroundStyle(Palette.textSecondary).textSelection(.enabled)
            Frame(title: L("sup_protocols"), color: SeriesColors.all[0], items: Self.protocols.map { ($1, counts["p:" + $0] ?? 0) })
            Frame(title: L("sup_transports"), color: SeriesColors.all[2], items: Self.transports.map { ($1, counts["t:" + $0] ?? 0) })
            Frame(title: L("sup_security"), color: SeriesColors.all[6], items: Self.security.map { ($1, counts["s:" + $0] ?? 0) })
            // Limited-network servers stand apart, in the yellow that marks them everywhere; the words in its dark tone.
            Frame(title: L("sup_limited"), color: StateTone.limited.strong,
                  items: [(Self.limitedProtocol, model.servers.filter(\.limited).count)],
                  text: Palette.warningIcon)
        }
        .task { await recount() }
        .onChange(of: model.servers) { _, _ in Task { await recount() } }
    }

    /// The count anew, off the window's thread (the servers' list changes with edits and refetches).
    private func recount() async {
        let servers = model.servers.filter { !$0.limited }
        counts = await Task.detached { Self.count(servers) }.value
    }

    /// Feature keys as Android's: `p:` protocol, `t:` transport, `s:` security; each server counts once per key.
    nonisolated static func count(_ servers: [ServerItem]) -> [String: Int] {
        var out: [String: Int] = [:]
        for s in servers {
            let words = s.proto.lowercased().components(separatedBy: CharacterSet(charactersIn: " ·+")).filter { !$0.isEmpty }
            var keys: Set<String> = []
            for (key, name) in protocols where words.contains(name.lowercased()) || words.contains(key) { keys.insert("p:" + key) }
            if words.contains("ws") || words.contains("websocket") { keys.insert("t:ws") }
            for t in ["grpc", "xhttp", "httpupgrade", "h2", "kcp"] where words.contains(t) { keys.insert("t:" + t) }
            // UDP protocols carry no transport and get no "t:" key at all (shared `SupportStats.features`
            // does the same) — they must not fall into "TCP".
            if !keys.contains("p:hysteria"), !keys.contains("p:wireguard"),
               !keys.contains(where: { $0.hasPrefix("t:") }) { keys.insert("t:tcp") }
            if words.contains("reality") { keys.insert("s:reality") }
            if words.contains("tls") { keys.insert("s:tls") }
            if words.contains("vision") { keys.insert("s:vision") }
            for k in keys { out[k, default: 0] += 1 }
        }
        return out
    }

    private struct Frame: View {
        var title: String
        var color: Color
        var items: [(String, Int)]
        var text: Color? = nil

        var body: some View {
            VStack(alignment: .leading, spacing: 8) {
                Text(title).font(Fonts.body(13, bold: true)).foregroundStyle(text ?? color).textSelection(.enabled)
                FlowCells(spacing: 16, lineSpacing: 6) {
                    ForEach(items, id: \.0) { name, n in
                        HStack(spacing: 5) {
                            Text(name).font(Fonts.body(13)).foregroundStyle(n == 0 ? Palette.textMuted : Palette.text).textSelection(.enabled)
                            Text("\(n)").font(Fonts.display(13, bold: true)).foregroundStyle(n == 0 ? Palette.textMuted : text ?? color).textSelection(.enabled)
                        }
                        .padding(.vertical, 2)
                    }
                }
            }
            .padding(.horizontal, 14).padding(.vertical, 12)
            .frame(maxWidth: .infinity, alignment: .leading)
            // The frame a pastel of the category's colour; the text keeps the colour.
            .overlay(RoundedRectangle(cornerRadius: 10).strokeBorder(color.opacity(0.5), style: StrokeStyle(lineWidth: 1.5, dash: [6, 5])))
        }
    }
}
