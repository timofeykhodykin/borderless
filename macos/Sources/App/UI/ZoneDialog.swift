import AppKit
import Shared
import SwiftUI

/// A zone of the user's own (Android `ZoneScreen.kt`) as a dialog over the settings: its name and switch, the sites and
/// addresses that go without the tunnel and through the server (one per line, each checked as the core would), the `.dat`
/// categories and apps it carries (shown, not edited — the Mac has neither), what in it doesn't apply because something
/// else wins, sharing it as a file, deleting it. The lists save by themselves: Done, Esc or a click outside all commit
/// what the fields hold, like Android's per-field saves — a dismissal never loses data.
struct ZoneDialog: View {
    @Bindable var model: MainModel
    let id: String
    /// Lines of this zone's file that weren't understood, handed over with the zone's id by the
    /// loader (the settings' `zoneLoadNotes`): a single slot in the model went to whichever zone
    /// dialog loaded first, so one zone could show the lines another's file had refused.
    var loadSkipped: [String] = []
    /// The file's lists hit the entry cap and only the first 50 000 came in (Android's
    /// `zone_loaded_truncated`): said once, on the first open after loading.
    var loadTruncated = false
    @State private var name = ""
    @State private var direct = ""
    @State private var server = ""
    /// The `.dat` categories the zone carries (`ext:` entries): `ZoneFile.entry` refuses them, so they are split
    /// out of the editable text — left in the editors, a save would land them in `skipped` and drop them from the
    /// zone. Android's `isList` split is the same; the rows below are its `ListRows`.
    @State private var directLists: [String] = []
    @State private var serverLists: [String] = []
    /// A loaded list longer than [editableLines] isn't put into a TextEditor at all; it is kept verbatim here
    /// and a save writes it back unchanged.
    @State private var directOver: [String]?
    @State private var serverOver: [String]?
    @State private var skipped: [String] = []
    /// What in the zone doesn't apply (the last committed state — the pending text isn't looked at yet).
    @State private var conflicts: [ZoneConflicts.Conflict] = []
    /// The load runs once (also against the `confirming` branch swap re-running `onAppear`).
    @State private var loaded = false
    /// The delete's question inside this same card: a dialog of its own (`confirmDelete`) would replace the
    /// zone's, and Cancel would lose the edits' state and close the page with it.
    @State private var confirming = false
    /// A `.dat` category's removal asking its second step first (Android `zone_list_remove_q`, R4):
    /// the `ext:` entry and the list it sits in. Same in-card reason as `confirming`.
    @State private var removingList: (entry: String, direct: Bool)?
    /// A failed share write, named on screen instead of failing silently.
    @State private var shareProblem: String?
    @Environment(MenuState.self) private var menus
    @Environment(\.snapshot) private var snapshot

    /// Past this many lines a text field turns sluggish — Android's EDITABLE_LINES: the file is the way to
    /// change such a list.
    private static let editableLines = 3000

    var body: some View {
        let zone = model.zones.first { $0.id == id }
        Group {
            if let removing = removingList {
            // Removing a `.dat` category asks its own question first (Android `zone_list_remove_q`
            // with the lowercased category name): Cancel keeps it, Delete drops just this category.
            confirmCard(question: L("zone_list_remove_q", removing.entry.components(separatedBy: ":").last?.lowercased() ?? removing.entry),
                        cancel: { removingList = nil }) {
                if removing.direct {
                    directLists.removeAll { $0 == removing.entry }
                } else {
                    serverLists.removeAll { $0 == removing.entry }
                }
                removingList = nil
            }
        } else if confirming {
            // The stored name: the field's edits may not be committed while the question is up.
            confirmCard(question: L("zone_delete_q", zone?.name ?? name),
                        cancel: { confirming = false }) {
                model.zones.removeAll { $0.id == id }
                model.openZone = nil
                menus.closeDialog()
            }
        } else {
            AppDialog(title: L("zone_section"), width: 520) {
                FormBox {
                    FormField(label: L("zone_name"), text: $name, placeholder: zone?.name ?? "", onCommit: { _ = apply() })
                    FormRow(label: L("zone_on")) {
                        AppSwitch(isOn: Binding(get: { zone?.on ?? false },
                                                set: { v in if let i = index { model.zones[i].on = v } }))
                    }
                }
                if model.settings.routingMode == .listedOnly {
                    Text(L("zone_listed_note")).font(Fonts.body(11)).foregroundStyle(Palette.textSecondary).textSelection(.enabled)
                }
                list(L("zone_direct"), $direct, over: directOver, lists: directLists, direct: true)
                list(L("zone_server"), $server, over: serverOver, lists: serverLists, direct: false)
                Text(L("zone_sites_hint")).font(Fonts.body(11)).foregroundStyle(Palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true).textSelection(.enabled)
                if let zone, !zone.apps.isEmpty {
                    apps(zone.apps)
                }
                if !skipped.isEmpty {
                    let shown = skipped.prefix(10).joined(separator: ", ")
                        + (skipped.count > 10 ? " " + L("zone_c_more", skipped.count - 10) : "")
                    Text(L("zone_skipped", shown)).font(Fonts.body(11)).foregroundStyle(Palette.danger)
                        .fixedSize(horizontal: false, vertical: true).textSelection(.enabled)
                }
                if loadTruncated {
                    // The load kept the first 50 000 entries, as Android's `zone_loaded_truncated` says.
                    Text(L("zone_loaded_truncated", 50_000)).font(Fonts.body(11)).foregroundStyle(Palette.danger)
                        .fixedSize(horizontal: false, vertical: true).textSelection(.enabled)
                }
                if !conflicts.isEmpty {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(L("zone_conflicts")).font(Fonts.body(12, bold: true)).foregroundStyle(Palette.textSecondary).textSelection(.enabled)
                        ForEach(Array(conflicts.prefix(50).enumerated()), id: \.offset) { _, c in
                            Text(conflictText(c)).font(Fonts.body(11)).foregroundStyle(Palette.textSecondary)
                                .fixedSize(horizontal: false, vertical: true).textSelection(.enabled)
                        }
                        if conflicts.count > 50 {
                            Text(L("zone_c_more", conflicts.count - 50)).font(Fonts.body(11))
                                .foregroundStyle(Palette.textSecondary).textSelection(.enabled)
                        }
                    }
                }
                if let shareProblem {
                    Text(shareProblem).font(Fonts.body(11)).foregroundStyle(Palette.danger)
                        .fixedSize(horizontal: false, vertical: true).textSelection(.enabled)
                }
                HStack(spacing: 6) {
                    MacButton(title: L("zone_share"), symbol: "square.and.arrow.up") { share() }
                        .help(macText("mac.zone.share.sub", "Someone else can load it as a zone (categories of .dat files stay on this Mac)"))
                    Text(L("zone_delete")).foregroundStyle(Palette.danger).padding(.horizontal, 10).modifier(MacButtonLook()).fixedSize()
                        .onTapGesture { confirming = true }
                    Spacer()
                    MacButton(title: L("done"), accent: true) { save() }.keyboardShortcut(.defaultAction)
                }
                .padding(.top, 4)
            }
            }
        }
        .onAppear { load() }
        // Done, Esc, a click outside, a replace — every way the dialog goes away commits what the fields hold
        // (ServerInspector saves names the same way). Lines the core would refuse are dropped at this last
        // moment, as Android drops them on a field's commit — "Done" names them in `skipped` first.
        .onDisappear { _ = apply() }
        // The conflicts follow the stored zone: they refresh with each commit of this dialog or a change
        // elsewhere, not on every keystroke (a big list's check is not cheap enough for that).
        .onChange(of: model.zones) { _, _ in conflicts = findConflicts() }
        .onChange(of: model.settings.directDomains) { _, _ in conflicts = findConflicts() }
        .onChange(of: model.settings.proxyDomains) { _, _ in conflicts = findConflicts() }
    }

    private var index: Int? { model.zones.firstIndex { $0.id == id } }

    private func load() {
        guard !loaded else { return }
        loaded = true
        let zone = model.zones.first { $0.id == id }
        name = zone?.name ?? ""
        // Editable text vs `.dat` categories (Android's `isList` split): the categories go to the rows, the
        // rest to the editors — so a save can re-attach them instead of dropping them as refused lines.
        func split(_ list: [String]) -> (editable: [String], lists: [String]) {
            (list.filter { !$0.hasPrefix("ext:") }, list.filter { $0.hasPrefix("ext:") })
        }
        let (d, dLists) = split(zone?.direct ?? [])
        let (s, sLists) = split(zone?.server ?? [])
        directLists = dLists
        serverLists = sLists
        if d.count > Self.editableLines { directOver = d } else { direct = d.joined(separator: "\n") }
        if s.count > Self.editableLines { serverOver = s } else { server = s.joined(separator: "\n") }
        skipped = loadSkipped
        conflicts = findConflicts()
    }

    /// The destructive question both second steps share (R4): what is asked, the "can't be undone"
    /// line, and the safe way out as the default — the destructive commit is the red button, not Return.
    private func confirmCard(question: String, cancel: @escaping () -> Void, run: @escaping () -> Void) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(question).font(Fonts.body(13, bold: true)).foregroundStyle(Palette.text).textSelection(.enabled)
            Text(L("cannot_undo")).font(Fonts.body(11)).foregroundStyle(Palette.danger).textSelection(.enabled)
            HStack(spacing: 6) {
                Spacer()
                MacButton(title: L("cancel"), action: cancel).keyboardShortcut(.cancelAction)
                DangerButton(title: L("delete"), action: run)
            }
            .padding(.top, 4)
        }
        .padding(Dims.dialogPad)
        .frame(width: 300)
    }

    private func list(_ title: String, _ text: Binding<String>, over: [String]?, lists: [String],
                      direct: Bool) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title).font(Fonts.body(12, bold: true)).foregroundStyle(Palette.textSecondary).textSelection(.enabled)
            Group {
                if let over {
                    // Too long for a text field (Android's "too long" row): read-only, with a note —
                    // the file is the way to change it.
                    VStack(alignment: .leading, spacing: 4) {
                        Text("\(over.count)").foregroundStyle(Palette.text).textSelection(.enabled)
                        Text(L("zone_too_long")).font(Fonts.body(11)).foregroundStyle(Palette.textSecondary).textSelection(.enabled)
                    }
                    .font(.system(size: 12, design: .monospaced))
                    .padding(8)
                    .frame(maxWidth: .infinity, alignment: .leading)
                } else if snapshot {
                    Text(text.wrappedValue).frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                        .font(.system(size: 12, design: .monospaced))
                        .padding(4)
                        .frame(height: 96)
                } else {
                    TextEditor(text: text).scrollContentBackground(.hidden)
                        .font(.system(size: 12, design: .monospaced))
                        .padding(4)
                        .frame(height: 96)
                }
            }
            .monoChrome()
            if !lists.isEmpty { listRows(lists, direct: direct) }
        }
    }

    /// The `.dat` categories of one list, a row each (Android's `ListRows`): the category's name and how
    /// much it holds. A tap doesn't remove it — it opens the second-step question (`removingList`,
    /// Android's `zone_list_remove_q` dialog).
    private func listRows(_ lists: [String], direct: Bool) -> some View {
        FormBox {
            ForEach(Array(lists.enumerated()), id: \.offset) { _, e in
                let category = e.components(separatedBy: ":").last?.lowercased() ?? e
                FormRow(label: category) {
                    Text(model.zones.first { $0.id == id }?.listSizes[e].map { L("zone_list_size", $0) } ?? L("zone_list"))
                        .font(Fonts.body(11)).foregroundStyle(Palette.textSecondary)
                }
                .contentShape(Rectangle())
                .onTapGesture { removingList = (e, direct) }
            }
        }
    }

    /// The zone's apps (package names) in the same look as the lists, but read-only: the Mac has no per-app
    /// routing, so they're carried with the zone, not edited here.
    private func apps(_ apps: [String]) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(L("zone_apps")).font(Fonts.body(12, bold: true)).foregroundStyle(Palette.textSecondary).textSelection(.enabled)
            Group {
                if snapshot {
                    Text(apps.joined(separator: "\n")).frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                } else {
                    ScrollView {
                        Text(apps.joined(separator: "\n")).frame(maxWidth: .infinity, alignment: .leading).textSelection(.enabled)
                    }
                }
            }
            .font(.system(size: 12, design: .monospaced))
            .padding(4)
            .frame(height: 96)
            .monoChrome()
            Text(String(localized: "mac.zone.apps.sub")).font(Fonts.body(11)).foregroundStyle(Palette.textSecondary)
                .fixedSize(horizontal: false, vertical: true).textSelection(.enabled)
        }
    }

    /// [text]'s lines as routing entries; the second list is every line that isn't one.
    private func entries(_ text: String) -> (ok: [String], bad: [String]) {
        var ok: [String] = [], bad: [String] = []
        for line in text.split(whereSeparator: \.isNewline).map({ $0.trimmingCharacters(in: .whitespaces) }) where !line.isEmpty {
            if let e = ZoneFile.entry(line) { ok.append(e) } else { bad.append(line) }
        }
        return (ok, bad)
    }

    /// The fields as they stand, written to the stored zone: name, entries the core would take, the `.dat`
    /// categories re-attached after them (Android: `ok + zone.list.filter(::isList)`), an over-long list
    /// verbatim. Returns the refused lines per field — the callers decide whether they stay on screen.
    @discardableResult
    private func apply() -> (direct: [String], server: [String]) {
        guard let i = index else { return ([], []) }
        let (d, badD): ([String], [String]) = directOver.map { ($0, []) } ?? entries(direct)
        let (s, badS): ([String], [String]) = serverOver.map { ($0, []) } ?? entries(server)
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        if !trimmed.isEmpty { model.zones[i].name = trimmed }
        model.zones[i].direct = d + directLists
        model.zones[i].server = s + serverLists
        // A category removed in this dialog takes its remembered size with it.
        let kept = Set(directLists + serverLists)
        model.zones[i].listSizes = model.zones[i].listSizes.filter { kept.contains($0.key) }
        return (badD, badS)
    }

    /// Done: everything that parses is saved; the refused lines stay on screen to be fixed (Android drops
    /// them at the field's commit — naming them and keeping them is the kinder half of the same rule).
    private func save() {
        let (badD, badS) = apply()
        if badD.isEmpty && badS.isEmpty {
            model.openZone = nil
            menus.closeDialog()
        } else {
            skipped = badD + badS
            if directOver == nil { direct = (entries(direct).ok + badD).joined(separator: "\n") }
            if serverOver == nil { server = (entries(server).ok + badS).joined(separator: "\n") }
        }
    }

    /// What in the stored zone doesn't apply because something else wins, for the "What doesn't apply"
    /// section (Android's `ZoneScreen` shows the same).
    private func findConflicts() -> [ZoneConflicts.Conflict] {
        guard let zone = model.zones.first(where: { $0.id == id }) else { return [] }
        return ZoneConflicts.of(zone: zone, chosen: model.zones.filter(\.on),
                                ownDirect: model.settings.directDomains, ownServer: model.settings.proxyDomains)
    }

    private func conflictText(_ c: ZoneConflicts.Conflict) -> String {
        let e = ZoneFile.display(c.entry)
        return switch c.reason {
        case .ownServer: L("zone_c_own_server", e)
        case .ownDirect: L("zone_c_own_direct", e)
        case .zoneServer: L("zone_c_zone_server", e, c.by ?? "")
        }
    }

    private func share() {
        let panel = NSSavePanel()
        // Android's file name: the zone's name with everything but letters, numbers and ._- as "_".
        let cleaned = String(name.map { $0.isLetter || $0.isNumber || "._ -".contains($0) ? $0 : "_" })
        panel.nameFieldStringValue = (cleaned.trimmingCharacters(in: .whitespaces).isEmpty ? "zone" : cleaned) + ".zone.txt"
        guard panel.runModal() == .OK, let url = panel.url else { return }
        let (d, _): ([String], [String]) = directOver.map { ($0, []) } ?? entries(direct)
        let (s, _): ([String], [String]) = serverOver.map { ($0, []) } ?? entries(server)
        // `.dat` categories go only as comments — their file stays on this Mac (Android `ZoneFile.write`).
        func commented(_ list: [String]) -> [String] {
            list.map { e in
                e.hasPrefix("ext:")
                    ? "# not included (categories of a .dat file): \(e.components(separatedBy: ":").last?.lowercased() ?? e)"
                    : e
            }
        }
        // The zone's apps travel with it — a share without them would drop them for the receiver.
        let apps = model.zones.first(where: { $0.id == id })?.apps ?? []
        let text = ZoneFile.text(name: name, direct: d + commented(directLists), server: s + commented(serverLists), apps: apps)
        do {
            try text.write(to: url, atomically: true, encoding: .utf8)
            shareProblem = nil
        } catch {
            shareProblem = String(format: macText("mac.zone.share_failed", "Couldn't write the zone file: %@"),
                                  locale: Locale.current, error.localizedDescription)
        }
    }
}

/// Where the zone's entries don't apply because something else wins — Android's `ZoneConflicts` (shared
/// `CustomZones.kt`) as a display-only port: the order in the core is the user's own lists first, then every
/// on-zone's "through the server" entries, then the "without the tunnel" ones. The own-app-tunnel reason is
/// not checked: the Mac doesn't fetch `appsTunnel` (no per-app routing), so that kind of conflict can only
/// be seen on Android.
private enum ZoneConflicts {
    enum Reason {
        /// The user's own list of sites through the server / without the tunnel decides instead.
        case ownServer, ownDirect
        /// Another on zone (or this one) sends it through the server.
        case zoneServer
    }

    /// [entry] of the zone doesn't apply because of [reason]; [by] = the zone that wins (zoneServer).
    struct Conflict {
        var entry: String
        var reason: Reason
        var by: String?
    }

    /// Conflicts of [zone] with the user's lists (as typed, newline-separated) and the other zones in
    /// [chosen] (the zones that are on, this one included or not).
    static func of(zone: CustomZone, chosen: [CustomZone], ownDirect: String, ownServer: String) -> [Conflict] {
        func own(_ text: String) -> [String] {
            text.split(whereSeparator: \.isNewline).compactMap { ZoneFile.entry(String($0)) }
        }
        let ownD = own(ownDirect)
        let ownS = own(ownServer)
        var out: [Conflict] = []
        let serverOthers = (chosen.filter { $0.id != zone.id } + [zone]).flatMap { z in z.server.map { ($0, z.name) } }
        func check(_ e: String, direct: Bool) {
            // The user's own lists come first, whichever way they send it (a same-way listing is harmless).
            if direct, ownS.contains(where: { covers($0, e) }) { out.append(Conflict(entry: e, reason: .ownServer)); return }
            if !direct, ownD.contains(where: { covers($0, e) }) { out.append(Conflict(entry: e, reason: .ownDirect)); return }
            if direct, let hit = serverOthers.first(where: { covers($0.0, e) }) {
                out.append(Conflict(entry: e, reason: .zoneServer, by: hit.1))
            }
        }
        zone.direct.forEach { check($0, direct: true) }
        zone.server.forEach { check($0, direct: false) }
        return out
    }

    /// Whether rule [a] matches everything rule [b] does (same rule, a domain above it, a range holding it).
    private static func covers(_ a: String, _ b: String) -> Bool {
        if a == b { return true }
        // The Mac's `ZoneFile.entry` stores a plain domain BARE ("example.com") where Android keeps
        // "domain:example.com" — a bare, non-address `a` is the domain rule ("example.com" covers
        // "sub.example.com"), a bare, non-address `b` the host a domain or keyword rule is matched
        // against. The "domain:" prefix is still read: zones coming from the service carry it.
        let bHost: String? = b.hasPrefix("domain:") ? String(b.dropFirst(7))
            : b.hasPrefix("full:") ? String(b.dropFirst(5))
            : b.contains(":") || ZoneFile.isIp(b) ? nil : b
        let aDomain: String? = a.hasPrefix("domain:") ? String(a.dropFirst(7))
            : a.contains(":") || ZoneFile.isIp(a) ? nil : a
        if let aDomain, let bHost {
            return bHost == aDomain || bHost.hasSuffix("." + aDomain)
        }
        if a.hasPrefix("keyword:"), let bHost { return bHost.contains(String(a.dropFirst(8))) }
        if ZoneFile.isIp(a), ZoneFile.isIp(b) { return holds(a, b) }
        return false
    }

    /// IPv4 range [a] holds address / range [b].
    private static func holds(_ a: String, _ b: String) -> Bool {
        func parse(_ s: String) -> (UInt32, Int)? {
            let parts = s.split(separator: "/", omittingEmptySubsequences: false)
            guard (1...2).contains(parts.count) else { return nil }
            let octets = parts[0].split(separator: ".", omittingEmptySubsequences: false)
            guard octets.count == 4, octets.allSatisfy({ (1...3).contains($0.count) && $0.allSatisfy { $0.isASCII && $0.isNumber } }),
                  let bits = parts.count == 2 ? Int(parts[1]) : 32
            else { return nil }
            let v = octets.reduce(UInt32(0)) { $0 * 256 + UInt32($1)! }
            return (v, bits)
        }
        guard let (av, ab) = parse(a), let (bv, bb) = parse(b), bb >= ab else { return false }
        let mask: UInt32 = ab == 0 ? 0 : UInt32.max << (32 - ab)
        return (av & mask) == (bv & mask)
    }
}
