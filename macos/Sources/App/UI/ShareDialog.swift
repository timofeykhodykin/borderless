import AppKit
import LocalAuthentication
import SwiftUI

/// What is shared: one server, a group, a subscription, or everything.
enum ShareScope: Equatable {
    case server(String), group(String), subscription(String), all
}

/// Sharing (Android `share/ShareSheet.kt` + `data/ShareFormat.kt`): plain links any client understands (hidden
/// servers left out), or the Border(less) format keeping groups and hidden servers; copy, the system's share menu, or a
/// QR code on screen. With limited-network servers in the selection, this device's id can go along.
///
/// Links carry the servers' keys: while the dialog is up the window's `sharingType` keeps it out of screenshots and
/// recordings (Android's `SecureFlagPolicy.SecureOn`), dropping the flag only while the QR is shown — it is meant to be
/// passed on, often as a screenshot. With `confirmShare` on, the device owner's authentication gates the content
/// before anything sensitive renders (Android's `rememberUnlock`, F-SYSTEM-0106-1).
struct ShareDialog: View {
    var model: MainModel
    var scope: ShareScope
    @State var keep = false
    @State var qr = false
    @State private var device = false
    @State private var copied = false
    @State private var sendFrame: CGRect = .zero
    /// The packed payload, the limited flag and the scope's counts — rebuilt only when something that goes
    /// into them changes ([rebuild]); packing + deflating on every render was the audit's finding.
    @State private var text = ""
    @State private var limited = false
    @State private var serverCount = 0
    @State private var subCount = 0
    /// Nothing sensitive is drawn until the device owner has confirmed when `confirmShare` asks for it.
    @State private var unlocked = false
    /// Whether the gate applies at all (the setting on, and not a snapshot — a render must not wait on
    /// `.task`, so snapshots draw straight through).
    private var gated: Bool { model.settings.confirmShare && !snapshot && !unlocked }
    @Environment(MenuState.self) private var menus
    @Environment(\.snapshot) private var snapshot

    init(model: MainModel, scope: ShareScope, keep: Bool = false, qr: Bool = false) {
        self.model = model
        self.scope = scope
        _keep = State(initialValue: keep)
        _qr = State(initialValue: qr)
        // First paint is final too — a first render with an empty [text] would flash the "nothing to
        // share" state before `onAppear` rebuilt it.
        rebuild()
    }

    /// Android `ShareSheet`'s `allowKeep`: a single server shares its link only — group names and the
    /// hidden mark don't travel (`Repo.shareServer` packs an anonymous bundle).
    private var allowKeep: Bool {
        if case .server = scope { return false }
        return true
    }

    var body: some View {
        AppDialog(title: title, width: 480) {
            if gated {
                HStack(spacing: 7) {
                    Spinner().frame(width: 12, height: 12)
                    Text(L("unlock_title")).font(Fonts.body(11)).foregroundStyle(Palette.textSecondary).textSelection(.enabled)
                }
                .frame(maxWidth: .infinity, alignment: .center)
                .padding(.vertical, 30)
            } else {
                let counts = [serverCount > 0 ? P("n_servers", serverCount) : nil,
                              subCount > 0 ? P("n_subscriptions", subCount) : nil].compactMap { $0 }.joined(separator: " · ")
                if !counts.isEmpty {
                    Text(counts).font(Fonts.body(11)).foregroundStyle(Palette.textMuted).textSelection(.enabled)
                }
                if allowKeep || limited {
                    FormBox {
                        if allowKeep {
                            FormRow(label: L("share_keep")) { AppSwitch(isOn: $keep) }
                        }
                        if limited {
                            FormRow(label: macText("mac.share.device", "Pass this device's id")) { AppSwitch(isOn: $device) }
                        }
                    }
                }
                if allowKeep || limited {
                    VStack(alignment: .leading, spacing: 2) {
                        if allowKeep {
                            Text(L(keep ? "share_keep_sub" : "share_plain_sub"))
                        }
                        if limited {
                            Text(device ? macText("mac.share.device.on", "To move the server to your other computer or phone. Two devices with one id at the same time push each other off")
                                        : macText("mac.share.device.off", "Off: the other device gets its own id. A password tied to one device then won't work there"))
                        }
                    }
                    .font(Fonts.body(11)).foregroundStyle(Palette.textSecondary).textSelection(.enabled)
                    .padding(.horizontal, 2)
                }
                if text.isEmpty {
                    Text(L("share_empty")).font(Fonts.body(12)).foregroundStyle(Palette.danger).textSelection(.enabled)
                } else if qr {
                    if text.utf8.count > QR.maxChars {
                        Text(L("share_qr_too_big", text.count)).font(Fonts.body(12)).foregroundStyle(Palette.danger)
                            .fixedSize(horizontal: false, vertical: true).textSelection(.enabled)
                    } else if let image = QR.image(text, size: 220) {
                        // Dark modules on white whatever the theme: cameras read it best that way.
                        Image(nsImage: image).interpolation(.none).resizable().frame(width: 220, height: 220)
                            .padding(10).background(Color.white).clipShape(RoundedRectangle(cornerRadius: Dims.cornerRadius))
                            .frame(maxWidth: .infinity)
                    } else {
                        Text(macText("mac.share.qr.failed", "Couldn't draw the QR code — copy or send the text instead."))
                            .font(Fonts.body(12)).foregroundStyle(Palette.danger)
                            .fixedSize(horizontal: false, vertical: true).textSelection(.enabled)
                    }
                } else {
                    Group {
                        let body = Text(text).font(.system(size: 11, design: .monospaced)).foregroundStyle(Palette.text).textSelection(.enabled)
                            .frame(maxWidth: .infinity, alignment: .leading)
                        SnapshotScroll { body }
                    }
                    .frame(height: 120)
                    .padding(8)
                    .monoChrome(fill: Palette.card.opacity(0.6))
                }
                HStack(spacing: 6) {
                    if !text.isEmpty {
                        MacButton(title: L(copied ? "copied" : "share_copy"), symbol: "doc.on.doc") {
                            NSPasteboard.general.clearContents()
                            // Marked concealed, so clipboard managers don't keep it (Android `copySensitive`).
                            NSPasteboard.general.setString(text, forType: .string)
                            NSPasteboard.general.setString("", forType: NSPasteboard.PasteboardType("org.nspasteboard.ConcealedType"))
                            copied = true
                        }
                        MacButton(title: L("share_send"), symbol: "square.and.arrow.up") { send(text) }
                            .background(GeometryReader { g in Color.clear
                                .onAppear { sendFrame = g.frame(in: .named("shell")) }
                                .onChange(of: g.frame(in: .named("shell"))) { sendFrame = $1 } })
                        MacButton(title: L("share_qr"), symbol: "qrcode", accent: qr) { qr.toggle() }
                    }
                    Spacer()
                    MacButton(title: L("done"), accent: true) { menus.closeDialog() }.keyboardShortcut(.defaultAction)
                }
                .padding(.top, 4)
            }
        }
        .background(SharingGuard(expose: qr))
        .task { await unlock() }
        .onAppear { rebuild() }
        .onChange(of: keep) { _, _ in copied = false; rebuild() }
        .onChange(of: device) { _, _ in copied = false; rebuild() }
        // Everything the payload is made of: a refetch behind the dialog must change what is shared.
        .onChange(of: model.servers) { _, _ in rebuild() }
        .onChange(of: model.groups) { _, _ in rebuild() }
        .onChange(of: model.subscriptions) { _, _ in rebuild() }
        .onChange(of: model.defaultDeviceId) { _, _ in rebuild() }
    }

    /// The `confirmShare` gate (Android `rememberUnlock` before the sheet's content, SECURITY.md S13):
    /// the setting off or no usable device-owner authentication → straight through; a cancel closes the
    /// dialog — nothing was shown, nothing copied.
    private func unlock() async {
        guard model.settings.confirmShare, !snapshot else { unlocked = true; return }
        let context = LAContext()
        guard context.canEvaluatePolicy(.deviceOwnerAuthentication, error: nil) else { unlocked = true; return }
        let ok = (try? await context.evaluatePolicy(.deviceOwnerAuthentication,
                                                    localizedReason: L("unlock_title"))) == true
        await MainActor.run {
            if ok { unlocked = true } else { menus.closeDialog() }
        }
    }

    /// Re-packs the share text and the limited flag from the current selection and switches.
    private func rebuild() {
        let groups = bundle()
        limited = groups.contains { g in g.servers.contains { SharedCode.isLimited($0.link) } }
        serverCount = groups.reduce(0) { $0 + $1.servers.count }
        subCount = groups.reduce(0) { $0 + $1.subs.count }
        let shown = device ? ShareFormat.withDeviceIds(groups, model: model) : groups
        text = keep && allowKeep ? ShareFormat.encode(shown) : ShareFormat.plain(shown)
    }

    private var title: String {
        switch scope {
        case .server(let id): model.servers.first { $0.id == id }.map { model.displayName($0) } ?? L("share")
        case .group(let id): model.groupName(id)
        case .subscription(let id): model.subscriptions.first { $0.id == id }?.name ?? L("share")
        case .all: L("share_all")
        }
    }

    /// The hidden servers' keys of a subscription, as Android's `Repo.shareSub` carries them (`ShareFormat.key`
    /// of each hidden server's link).
    private func hiddenKeys(_ sub: SubscriptionItem) -> [String] {
        model.servers.filter { $0.subscriptionId == sub.id && $0.disabled }.map { SharedCode.shareKey($0.link) }
    }

    /// Android `Repo.shareSub`: the display name always travels (`Sub.name`), so the other side's title reads the same.
    private func shareSub(_ sub: SubscriptionItem) -> ShareFormat.Sub {
        ShareFormat.Sub(url: sub.url, name: sub.name.isEmpty ? nil : sub.name, hidden: hiddenKeys(sub))
    }

    private func bundle() -> [ShareFormat.Group] {
        func group(_ g: GroupItem, servers: [ServerItem], subs: [SubscriptionItem]) -> ShareFormat.Group {
            // Android `groupBundle`: a default or nameless group's name is not written — the first stays
            // nameless (importing names it by the locale), the second keeps naming itself by its
            // subscription instead of the resolved name freezing over it.
            ShareFormat.Group(name: g.isDefault || g.nameless ? nil : g.name, isDefault: g.isDefault,
                              servers: servers.map { .init(link: $0.link, off: $0.disabled) },
                              subs: subs.map(shareSub))
        }
        func own(_ gid: String) -> [ServerItem] { model.servers.filter { $0.groupId == gid && $0.subscriptionId == nil } }
        switch scope {
        case .server(let id):
            // Android `Repo.shareServer`: an anonymous group with the link alone — the source group and
            // the hidden mark don't travel (allowKeep is off for it anyway).
            guard let s = model.servers.first(where: { $0.id == id }) else { return [] }
            return [ShareFormat.Group(name: nil, isDefault: false, servers: [.init(link: s.link, off: false)], subs: [])]
        case .group(let id):
            guard let g = model.groups.first(where: { $0.id == id }) else { return [] }
            return [group(g, servers: own(id), subs: model.subscriptions.filter { $0.groupId == id })]
        case .subscription(let id):
            // Android `Repo.shareSubscription`: the group's name — the subscription's own for a
            // nameless/default group — never a `default` mark, so the other side gives it a group of its own.
            guard let sub = model.subscriptions.first(where: { $0.id == id }), let g = model.groups.first(where: { $0.id == sub.groupId })
            else { return [] }
            return [ShareFormat.Group(name: g.isDefault || g.nameless ? sub.name : g.name, isDefault: false,
                                      servers: [], subs: [shareSub(sub)])]
        case .all:
            return model.groups.map { g in group(g, servers: own(g.id), subs: model.subscriptions.filter { $0.groupId == g.id }) }
        }
    }

    /// The system's share menu under the button that asked for it (the same anchor as the log's share menu).
    private func send(_ text: String) {
        let at = CGPoint(x: sendFrame.midX, y: sendFrame.maxY)
        guard let view = NSApp.keyWindow?.contentView else { return }
        let rect = NSRect(x: at.x, y: view.isFlipped ? at.y : view.bounds.height - at.y, width: 1, height: 1)
        NSSharingServicePicker(items: [text]).show(relativeTo: rect, of: view, preferredEdge: .minY)
    }
}

/// Keeps the share links' window out of screenshots and recordings while it is attached
/// (`NSWindowSharingType.none` — Android's `SecureFlagPolicy.SecureOn`), releasing it while the QR is
/// shown: that one is meant to be passed on, often as a screenshot (Android drops the flag then too,
/// F-SHARE-0035-1). The window's previous setting is restored when the dialog leaves.
private struct SharingGuard: NSViewRepresentable {
    /// true while the QR is up: capture allowed again.
    var expose: Bool

    func makeNSView(context: Context) -> GuardView { GuardView() }

    func updateNSView(_ view: GuardView, context: Context) {
        view.expose = expose
        view.apply()
    }

    final class GuardView: NSView {
        var expose = false
        /// What the window had before this guard — restored on detach, so another dialog or the plain
        /// window isn't left hidden.
        private var previous: NSWindow.SharingType?
        /// `window` is already nil when the detach reaches `viewDidMoveToWindow`, so it is remembered.
        private weak var attached: NSWindow?

        override func viewDidMoveToWindow() {
            if window != nil {
                attached = window
                apply()
            } else {
                if let previous, let old = attached { old.sharingType = previous }
                attached = nil
                previous = nil
            }
        }

        func apply() {
            guard let w = window ?? attached else { return }
            if previous == nil { previous = w.sharingType }
            w.sharingType = expose ? previous ?? .readOnly : .none
        }
    }
}

/// The two share formats (Android `data/ShareFormat.kt`, byte-compatible through the shared code): plain links, and
/// `borderless://import?d=` + base64url(raw deflate(JSON)) with groups, subscriptions and hidden servers.
enum ShareFormat {
    struct Group: Encodable {
        var name: String?
        var isDefault: Bool
        var servers: [Server]
        var subs: [Sub]

        enum CodingKeys: String, CodingKey { case name, isDefault = "default", servers, subs }
    }

    struct Server: Encodable { var link: String; var off: Bool }
    struct Sub: Encodable { var url: String; var name: String?; var hidden: [String] }

    static let prefix = "borderless://import?d="

    /// Links of visible servers, then subscription URLs.
    static func plain(_ groups: [Group]) -> String {
        groups.flatMap { g in g.servers.filter { !$0.off }.map(\.link) + g.subs.map(\.url) }.joined(separator: "\n")
    }

    /// The bundle link: packed by the shared code so every Border(less) build reads it (audit A4 — Foundation's
    /// `compressed(.zlib)` wrote a zlib stream the shared decoder refuses).
    static func encode(_ groups: [Group]) -> String { SharedCode.encodeShare(groups) }

    /// [groups] with each limited-network server's link carrying the id this install sends for it (Android
    /// `Limited.withDeviceIds`): the server ties a password to one device id, so this moves the server to one's own
    /// other phone.
    @MainActor
    static func withDeviceIds(_ groups: [Group], model: MainModel) -> [Group] {
        let byLink = Dictionary(model.servers.map { ($0.link, $0) }, uniquingKeysWith: { a, _ in a })
        return groups.map { g in
            var g = g
            g.servers = g.servers.map { s in
                guard let item = byLink[s.link] else { return s }
                var s = s
                s.link = SharedCode.withDevice(s.link, deviceId(item, model: model))
                return s
            }
            return g
        }
    }

    /// The id a limited-network server sends: the user's, else the link's, else this install's
    /// (Android `Limited.deviceId`).
    @MainActor
    private static func deviceId(_ s: ServerItem, model: MainModel) -> String {
        if !s.limitedDevice.isEmpty { return s.limitedDevice }
        let own = SharedCode.limitedDevice(s.link)
        return own.isEmpty ? model.defaultDeviceId : own
    }
}
