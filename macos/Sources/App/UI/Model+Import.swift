import Foundation
import Observation
import Shared

// APP-IMPORT's model surface. Model.swift belongs to APP-LIST, so everything the import/QR/share fixes
// need on `MainModel` lives here as extensions: the richer add report (parser `detail`, the `pending`
// note), the service's "imported" event, the per-model Add draft that survives the dialog being rebuilt
// (F-IMPORT-0004-1), the journal that replays an add the first `.servers` answer would otherwise wipe
// (F-IMPORT-0054-1), and the Border(less) bundle preview (F-IMPORT-0018-2, SECURITY.md S15).

/// What adding text did (Android `Importer.Result`): the counts, each line that gave nothing with the
/// reason in plain words and the parser's technical detail under it, and `pending` — the subscriptions
/// that are in but whose first download hasn't happened (on the Mac every added subscription is:
/// `importText` saves it, the service fetches it after; F-SUBS-0050-1).
struct ImportReport: Equatable {
    var servers = 0
    var subscriptions = 0
    var pending = 0
    var problems: [Problem] = []

    struct Problem: Equatable, Hashable {
        var item: String
        var text: String
        /// The parser's technical detail (Android `ImportErrors.Issue.detail`), nil for app-side
        /// problems like "this subscription is already added".
        var detail: String? = nil
    }

    var added: Int { servers + subscriptions }

    /// `Importer.Result.message`: "Servers added: N, subscriptions: M (not downloaded yet: P, will
    /// retry later)" — the parenthesised pending note is part of it, as Android concatenates it.
    var message: String {
        var parts: [String] = []
        if servers > 0 { parts.append(L("import_added", servers)) }
        if subscriptions > 0 { parts.append(L("import_subs", subscriptions)) }
        var text = parts.isEmpty ? L("import_nothing") : parts.joined(separator: ", ")
        if pending > 0 { text += " (" + L("import_pending", pending) + ")" }
        return text
    }

    init(servers: Int, subscriptions: Int, pending: Int, problems: [Problem]) {
        self.servers = servers
        self.subscriptions = subscriptions
        self.pending = pending
        self.problems = problems
    }

    /// For callers that only have the plain `ImportResult` (the snapshot's canned one).
    init(_ r: ImportResult) {
        self.init(servers: r.servers, subscriptions: r.subscriptions, pending: 0,
                  problems: r.problems.map { .init(item: $0.item, text: $0.text) })
    }
}

/// The Add dialog's working state, kept per model outside the view (Model.swift is APP-LIST's, and
/// `@State` can't outlive its view anyway): `MenuState.show(dialog:)` replaces the whole `AnyView`,
/// so a `borderless://` link arriving while Add is open builds the dialog anew — without this the
/// group pick, the import report and the rest of @State would be gone (F-IMPORT-0004-1). With the
/// draft kept here the link only replaces the text, exactly Android's open-intent behaviour.
/// All fields are written on the main actor only (the dialog's views); the class stays nonisolated
/// so `AddDialog.init` — which runs wherever the `AnyView` is built — can adopt it.
@Observable
final class AddDraft {
    var text = ""
    /// nil = automatic: links to the default group, each subscription to a group of its own.
    var target: String?
    var newGroup: String?
    var report: ImportReport?
    var importedText: String?
    var notice: String?
    var dropping = false
    /// A QR read (image file or camera) is running — a big photo's detection can take a moment.
    var decoding = false
    /// The add is running — the button holds still until the result is written (Android `busy`).
    var busy = false
    /// The camera scanner pane is showing.
    var scanning = false
    /// What `borderless://import?d=` links in the text hold ([BundlePreview]); empty while there are none.
    var preview: [BundlePreview.Group] = []
    /// Adds that landed before the first `.servers` answer and could still be wiped by it
    /// ([ImportJournal]); the texts replay in order if the wipe is seen.
    var journalTexts: [(text: String, target: String?)] = []
    var journalIds: Set<String> = []
    @ObservationIgnored var journalWatching = false
    @ObservationIgnored var previewTask: Task<Void, Never>?

    /// Back to a fresh sheet (the dialog was closed, not replaced): nothing carries into the next open.
    func reset() {
        text = ""
        target = nil
        newGroup = nil
        report = nil
        importedText = nil
        notice = nil
        dropping = false
        decoding = false
        busy = false
        scanning = false
        preview = []
        journalTexts = []
        journalIds = []
        previewTask?.cancel()
        previewTask = nil
    }
}

/// The per-model drafts (`MainModel` can't gain stored properties from an extension). Weak keys: the
/// draft dies with its model; strong values: the dialog's own binding holds it meanwhile. Called from
/// `AddDialog.init` — not necessarily the main actor — so the table sits behind a lock.
enum AddDrafts {
    private static let lock = NSLock()
    private nonisolated(unsafe) static let all = NSMapTable<AnyObject, AddDraft>.weakToStrongObjects()

    static func of(_ model: MainModel) -> AddDraft {
        lock.lock()
        defer { lock.unlock() }
        if let draft = all.object(forKey: model) { return draft }
        let draft = AddDraft()
        all.setObject(draft, forKey: model)
        return draft
    }
}

extension MainModel {
    /// The whole add: apply the text (`importText`, whose own parse maps the head of `problems` 1:1 —
    /// this parse is only to recover each problem's `detail`, dropped by `ImportResult`), journal the
    /// added ids against the first-load wipe, and write the service's event. The parse is pure text
    /// work — running it twice keeps [importText] the single source of apply truth.
    /// `note` is off only for [journalCheck]'s replay: the original add already wrote its event.
    @MainActor @discardableResult
    func importReport(_ text: String, into groupId: String?, note: Bool = true) -> ImportReport {
        let parsed = SharedCode.parse(text)
        let before = (servers: Set(servers.map(\.id)), groups: Set(groups.map(\.id)),
                      subs: Set(subscriptions.map(\.id)))
        let r = importText(text, into: groupId)
        var problems = parsed.problems.map { ImportReport.Problem(item: $0.item, text: $0.text, detail: $0.detail) }
        problems += r.problems.dropFirst(parsed.problems.count).map { .init(item: $0.item, text: $0.text) }
        // Every added subscription is pending its first download — the service fetches it after the
        // save lands (Android counts the ones that couldn't be reached; here all of them haven't been
        // fetched yet at report time, and the service retries a failed one on its interval).
        let report = ImportReport(servers: r.servers, subscriptions: r.subscriptions,
                                  pending: r.subscriptions, problems: problems)
        if r.servers + r.subscriptions > 0 {
            let added = Set(servers.map(\.id)).subtracting(before.servers)
                .union(Set(groups.map(\.id)).subtracting(before.groups))
                .union(Set(subscriptions.map(\.id)).subtracting(before.subs))
            journalAdd(text, target: groupId, addedIds: added)
        }
        if note { Self.noteImport(report) }
        return report
    }

    /// The add-result event Android's `Importer` writes ("Servers added: N / subscriptions: M / not
    /// downloaded yet: P"): a whole-list save can't tell an import from a manual add, so the app
    /// reports the counts and the service formats and logs the event itself (`serviceAction`
    /// "imported:<servers>:<subs>:<pending>:<problems>"; the problems' details stay in the dialog,
    /// only their count goes, so nothing private reaches the daemon's log).
    @MainActor static func noteImport(_ r: ImportReport) {
        let target = "imported:\(r.servers):\(r.subscriptions):\(r.pending):\(r.problems.count)"
        Task { _ = await ServiceClient.send(Request(.serviceAction, target: target), timeoutSec: 5) }
    }

    // ----------------------------------------------------------------- F-IMPORT-0054-1 journal

    /// Remembers an add until the first `.servers` answer lands. `importText` writes into the
    /// in-memory list, but the save watcher only runs once `loaded` — an add before that answer is
    /// wiped by `load(_)` before it is ever sent (F-IMPORT-0054-1). Each change then decides: every
    /// journaled id gone at once = the wipe — replay the adds; a change that takes only some of them
    /// is the user's own edit, so those ids leave the journal and the watch goes on. Ids still there
    /// mean nothing decided yet (the watch only retires early at [AddDraft.reset]).
    @MainActor private func journalAdd(_ text: String, target: String?, addedIds: Set<String>) {
        let draft = AddDrafts.of(self)
        draft.journalTexts.append((text, target))
        draft.journalIds.formUnion(addedIds)
        guard !draft.journalWatching else { return }
        draft.journalWatching = true
        withObservationTracking {
            _ = (servers, groups, subscriptions)
        } onChange: { [weak self] in
            Task { @MainActor in self?.journalCheck() }
        }
    }

    @MainActor private func journalCheck() {
        let draft = AddDrafts.of(self)
        let texts = draft.journalTexts
        let ids = draft.journalIds
        let present = Set(servers.map(\.id)).union(groups.map(\.id)).union(subscriptions.map(\.id))
        if !texts.isEmpty, !ids.isEmpty, ids.isDisjoint(with: present) {
            // The wipe: every journaled id vanished in one change — the first `.servers` answer
            // replaced the list before the add could be saved. Run the adds again against the list
            // the service actually has; a target group that went with the wipe falls back to
            // automatic placement.
            draft.journalTexts = []
            draft.journalIds = []
            for (text, target) in texts {
                _ = importReport(text, into: target.flatMap { t in groups.contains { $0.id == t } ? t : nil },
                                 note: false)
            }
        } else {
            // Not the wipe: the journaled ids the change itself removed leave the journal (a delete
            // the user meant is not the wipe's business) and the watch goes on for the rest.
            draft.journalIds.formIntersection(present)
            // Every added item went by the user's hand — nothing is left for a wipe to take.
            if draft.journalIds.isEmpty { draft.journalTexts = [] }
        }
        // Watching ends only when nothing journaled remains; a replay journals anew through
        // `importReport` → `journalAdd` (the wipe could come in more than one piece).
        if draft.journalTexts.isEmpty {
            draft.journalWatching = false
        } else {
            withObservationTracking {
                _ = (servers, groups, subscriptions)
            } onChange: { [weak self] in
                Task { @MainActor in self?.journalCheck() }
            }
        }
    }

    /// Refreshes the bundle preview for the Add field's current text (Android `AddSheet`'s
    /// `BundlePreview`): only when a `borderless://` link is in it, decoded off the caller's turn.
    @MainActor func scheduleBundlePreview() {
        let draft = AddDrafts.of(self)
        draft.previewTask?.cancel()
        let text = draft.text
        guard text.contains(ShareFormat.prefix) else { draft.preview = []; return }
        draft.previewTask = Task { @MainActor [weak self, weak draft] in
            try? await Task.sleep(for: .milliseconds(300))
            guard let self, let draft, !Task.isCancelled else { return }
            let preview = await Task.detached { BundlePreview.decode(text) }.value
            guard !Task.isCancelled, draft.text == text else { return }
            draft.preview = preview
        }
    }
}

/// What a `borderless://import?d=` link holds, before anything is added (Android's `BundlePreview`):
/// groups, their servers (name and address) and subscriptions (the host only — the rest of the URL is
/// the provider's key).
enum BundlePreview {
    struct Line: Equatable, Hashable {
        var text: String
    }

    struct Group: Equatable, Hashable {
        /// nil resolves to the default group's name on screen (`default` mark), then to the first
        /// subscription's name, then to "—" — Android's order.
        var isDefault: Bool
        var name: String?
        var firstSubName: String?
        var servers: Int
        var subs: Int
        var lines: [Line]
        var more: Int
    }

    /// One group's preview lines at most, like Android's `PREVIEW_LINES`.
    static let previewLines = 8

    /// The bundles' groups, decoded leniently by the shared parser (a bundle that doesn't decode drops
    /// out of the preview; the report's problems will say why on Add).
    nonisolated static func decode(_ text: String) -> [Group] {
        guard text.contains(ShareFormat.prefix) else { return [] }
        // The parser's per-bundle problems are the report's business; the preview only lists groups.
        return SharedCode.parse(text).groups.compactMap { g in
            let servers = g.servers ?? []
            let subs = g.subscriptions ?? []
            guard !servers.isEmpty || !subs.isEmpty else { return nil }
            var lines = servers.prefix(previewLines).map { s -> Line in
                let shown = s.title.isEmpty ? s.name : s.title
                var t = shown.isEmpty ? fallback(s.link) : "\(shown) — \(host(s.link))"
                if s.hidden == true { t += " · " + L("bundle_hidden") }
                return Line(text: t)
            }
            lines += subs.prefix(previewLines).map {
                Line(text: L("bundle_sub", URL(string: $0.url)?.host ?? "?"))
            }
            let more = max(servers.count - previewLines, 0) + max(subs.count - previewLines, 0)
            return Group(isDefault: g.default == true, name: g.name, firstSubName: subs.first?.name,
                         servers: servers.count, subs: subs.count, lines: lines, more: more)
        }
    }

    /// `LinkParser.parse`'s "host" for the preview line. The Kotlin call can throw, which on this side
    /// is a crash (no Swift `try` reaches it), so the address is taken out of the link text instead:
    /// the base64-JSON forms (vmess://, wdtt://, qwdtt://) get a JSON lookup, everything else the part
    /// after "://" (past any user-info, before the path).
    nonisolated private static func host(_ link: String) -> String {
        guard let at = link.range(of: "://") else { return "?" }
        let payload = link[at.upperBound...]
        if let fromJson = jsonHost(scheme: String(link[..<at.lowerBound]).lowercased(), payload: String(payload)) {
            return fromJson
        }
        // The wdtt query form carries it as a parameter (`wdtt://connect?v=1&host=…`).
        if let h = URLComponents(string: link)?.queryItems?.first(where: { $0.name == "host" })?.value, !h.isEmpty {
            return h
        }
        let noFragment = payload.prefix { $0 != "#" }
        let noPath = noFragment.prefix { $0 != "/" && $0 != "?" }
        let afterAt = noPath.split(separator: "@").last.map(String.init) ?? String(noPath)
        return afterAt.isEmpty ? "?" : afterAt
    }

    /// The base64-JSON link forms' address field (vmess "add"/"port", wdtt "peer"/"server"/"host").
    nonisolated private static func jsonHost(scheme: String, payload: String) -> String? {
        guard ["vmess", "wdtt", "qwdtt"].contains(scheme) else { return nil }
        let b64 = String(payload.prefix { $0 != "#" && $0 != "/" && $0 != "?" })
            .replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        guard let data = Data(base64Encoded: b64, options: [.ignoreUnknownCharacters]),
              let o = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return nil }
        if let add = o["add"] as? String, !add.isEmpty {
            return add + (o["port"].map { ":\($0)" } ?? "")
        }
        for key in ["peer", "server", "host"] {
            if let v = o[key] as? String, !v.isEmpty { return v }
        }
        return nil
    }

    /// Android's `getOrDefault(s.l.substringBefore("://") + "://…")` — the scheme when even the address
    /// can't be read.
    nonisolated private static func fallback(_ link: String) -> String {
        (link.components(separatedBy: "://").first ?? "?") + "://…"
    }
}
