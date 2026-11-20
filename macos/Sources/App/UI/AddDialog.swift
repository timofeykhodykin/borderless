import AppKit
import SwiftUI
import UniformTypeIdentifiers

/// "Add servers" (Android `AddSheet`) as a dialog over the window: links, a subscription address or an Xray JSON typed,
/// pasted, dropped (text, a file, a picture with a QR code), scanned with the camera or read from a picture; the group
/// they go to; what was added and what wasn't, explained.
///
/// The working state is the per-model [AddDraft], not `@State`: a `borderless://` link that arrives while the dialog
/// is open is handed to `MenuState.show(dialog:)` (BorderlessApp, APP-SHELL's file), which rebuilds the whole view —
/// with the draft kept outside, the link replaces the text while the group pick and the import report survive
/// (F-IMPORT-0004-1, Android's open-intent behaviour).
struct AddDialog: View {
    @Bindable var model: MainModel
    @Bindable var draft: AddDraft
    @Environment(MenuState.self) private var menus
    @Environment(\.snapshot) private var snapshot

    /// `text`: what to start with (a `borderless://` or server link opened from elsewhere, a snapshot). A non-empty
    /// one replaces the draft's text; the rest of the draft — the group pick, the report — stays (F-IMPORT-0004-1).
    init(model: MainModel, text: String = "", result: ImportResult? = nil) {
        self.model = model
        let draft = AddDrafts.of(model)
        self.draft = draft
        if !text.isEmpty { draft.text = text }
        if let result {
            draft.report = ImportReport(result)
            draft.importedText = text
        }
    }

    var body: some View {
        AppDialog(title: L("add_title"), width: 540) {
            Text(L("add_hint")).font(Fonts.body(11.5)).foregroundStyle(Palette.textSecondary)
                .fixedSize(horizontal: false, vertical: true).textSelection(.enabled)
            editor
            if draft.decoding {
                HStack(spacing: 7) {
                    Spinner().frame(width: 12, height: 12)
                    Text(macText("mac.add.decoding", "Reading the image…")).font(Fonts.body(11))
                        .foregroundStyle(Palette.textSecondary).textSelection(.enabled)
                }
            }
            if let notice = draft.notice { Text(notice).font(Fonts.body(11)).foregroundStyle(Palette.danger).textSelection(.enabled) }
            if !draft.preview.isEmpty { BundleInfo(groups: draft.preview, model: model) }
            if draft.scanning {
                QRCameraPane(
                    found: { text in append(text); draft.scanning = false },
                    close: { draft.scanning = false },
                    snapshot: snapshot)
            }
            if let report = draft.report { Report(result: report) }
            HStack(spacing: 8) {
                Text(L("add_to_group")).font(Fonts.body(13)).foregroundStyle(Palette.text).textSelection(.enabled)
                groupChoice
                if let name = draft.newGroup {
                    PlainField(placeholder: L("group_new"), text: Binding(get: { name }, set: { draft.newGroup = $0 }), snapshot: snapshot,
                               onCommit: createGroup)
                        .modifier(FieldBezel())
                        .frame(width: 150)
                    MacButton(title: L("add")) { createGroup() }
                }
                Spacer()
            }
            if draft.target == nil {
                Text(L("add_group_auto_hint", model.groupName("own"))).font(Fonts.body(11)).foregroundStyle(Palette.textSecondary).textSelection(.enabled)
            }
            HStack(spacing: 6) {
                MacButton(title: L("paste"), symbol: "doc.on.clipboard") {
                    if let s = NSPasteboard.general.string(forType: .string), !s.isEmpty { append(s) }
                }
                // The camera can't run under ImageRenderer — snapshots keep the image-QR path only.
                if !snapshot {
                    MacButton(title: L("scan_qr"), symbol: "camera.viewfinder", accent: draft.scanning) { draft.scanning.toggle() }
                }
                MacButton(title: L("qr_from_image"), symbol: "qrcode.viewfinder") { pickImage() }
                Spacer()
                MacButton(title: L("cancel")) { menus.closeDialog() }.keyboardShortcut(.cancelAction)
                if draft.busy {
                    MacButton(title: L("add"), accent: true) {}.disabled(true).opacity(0.6)
                } else {
                    MacButton(title: L(done ? "done" : "add"), accent: true) { add() }
                        .disabled(draft.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                        .keyboardShortcut(.defaultAction)
                }
            }
            .padding(.top, 4)
        }
        // A message seen is a message done with: typing again clears it (it answered the previous text).
        .onChange(of: draft.text) { _, _ in
            draft.notice = nil
            model.scheduleBundlePreview()
        }
        .onAppear { model.scheduleBundlePreview() }
        // Replaced (a link handed the dialog a new view) is not closed — the draft carries over; a real
        // close clears it so the next open starts fresh, like Android's sheet.
        .onDisappear { if menus.dialog == nil { draft.reset() } }
    }

    private var done: Bool { draft.importedText == draft.text && (draft.report?.added ?? 0) > 0 }

    private var editor: some View {
        ZStack(alignment: .topLeading) {
            if snapshot {
                Text(draft.text).frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading).textSelection(.enabled)
            } else {
                TextEditor(text: $draft.text).scrollContentBackground(.hidden)
            }
            if draft.text.isEmpty {
                Text(L("add_placeholder")).foregroundStyle(Palette.textMuted).padding(.leading, 5).allowsHitTesting(false)
            }
        }
        .font(.system(size: 12, design: .monospaced))
        .padding(6)
        .frame(height: 150)
        .background(RoundedRectangle(cornerRadius: 6).fill(Palette.background))
        .overlay(RoundedRectangle(cornerRadius: 6).strokeBorder(draft.dropping ? Palette.accent : Palette.textMuted.opacity(0.55),
                                                                lineWidth: draft.dropping ? 2 : 1))
        // Text, a file of links or a picture with a QR code dropped onto the field.
        .onDrop(of: [.fileURL, .plainText], isTargeted: $draft.dropping) { providers in
            for p in providers {
                if p.canLoadObject(ofClass: URL.self) {
                    _ = p.loadObject(ofClass: URL.self) { url, _ in
                        guard let url else { return }
                        Task { @MainActor in
                            // A dragged link is the text itself (a subscription's address), not a file:
                            // only file: URLs go to the reader.
                            if url.isFileURL { readFile(url) } else { append(url.absoluteString) }
                        }
                    }
                } else {
                    _ = p.loadObject(ofClass: String.self) { s, _ in
                        guard let s else { return }
                        Task { @MainActor in append(s) }
                    }
                }
            }
            return true
        }
    }

    private var groupChoice: some View {
        let title = draft.target.map { model.groupName($0) } ?? L("add_group_auto")
        return HStack(spacing: 5) {
            Text(title).lineLimit(1)
            Image(systemName: "chevron.up.chevron.down").font(.system(size: 9, weight: .semibold)).foregroundStyle(Palette.textSecondary)
        }
        .padding(.horizontal, 8)
        .modifier(MacButtonLook())
        .fixedSize()
        .appMenuOnClick(menus) {
            [MenuItem(title: L("add_group_auto"), selected: draft.target == nil) { draft.target = nil }]
                + model.groups.map { g in MenuItem(title: g.name, selected: draft.target == g.id) { draft.target = g.id } }
                + [MenuItem(title: L("add_group_new"), symbol: "folder.badge.plus") { draft.newGroup = "" }]
        }
    }

    private func append(_ more: String) {
        draft.notice = nil
        draft.text = draft.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            ? more : draft.text.trimmingCharacters(in: .newlines) + "\n" + more
    }

    /// The longest a dropped or picked file may be: a file of server links is lines, never megabytes.
    private static let maxFileBytes = 8 << 20

    private enum FileRead: Sendable { case ok(String), tooBig, unreadable }

    private func readFile(_ url: URL) {
        let isImage = UTType(filenameExtension: url.pathExtension)?.conforms(to: .image) == true
        Task {
            // The extension is a hint, not the truth — a link list saved as .png and a photo renamed .txt
            // both happen — so the likely kind is tried first and the other one is the fallback: a text
            // read fails on a picture (it isn't UTF-8), image detection fails fast on text.
            if isImage {
                if let found = await decodeQR(url) { append(found); return }
                if case .ok(let s) = await Task.detached { Self.readText(url) }.value { append(s) } else { draft.notice = L("qr_not_found") }
            } else {
                // Bounded and off the caller's thread: a huge or slow file must not stall the window.
                switch await Task.detached { Self.readText(url) }.value {
                case .ok(let s): append(s)
                case .tooBig, .unreadable:
                    if let found = await decodeQR(url) { append(found) }
                    else { draft.notice = macText("mac.add.file_unreadable", "The file can't be read or is too big to add.") }
                }
            }
        }
    }

    /// The picture's QR codes, with the busy line up while detection runs.
    @MainActor
    private func decodeQR(_ url: URL) async -> String? {
        draft.decoding = true
        defer { draft.decoding = false }
        return await QR.decode(url)
    }

    /// The file's text at most [maxFileBytes] (read off the caller's thread).
    nonisolated private static func readText(_ url: URL) -> FileRead {
        guard let handle = try? FileHandle(forReadingFrom: url) else { return .unreadable }
        defer { try? handle.close() }
        guard let data = try? handle.read(upToCount: maxFileBytes + 1) else { return .unreadable }
        if data.count > maxFileBytes { return .tooBig }
        guard let text = String(data: data, encoding: .utf8) else { return .unreadable }
        return .ok(text)
    }

    private func pickImage() {
        let panel = NSOpenPanel()
        panel.allowedContentTypes = [.image]
        panel.allowsMultipleSelection = false
        guard panel.runModal() == .OK, let url = panel.url else { return }
        readFile(url)
    }

    private func createGroup() {
        guard let name = draft.newGroup?.trimmingCharacters(in: .whitespaces), !name.isEmpty else { draft.newGroup = nil; return }
        // Two groups with one name would read the same in every list — an existing one is picked instead.
        if let same = model.groups.first(where: { $0.name.compare(name, options: .caseInsensitive) == .orderedSame }) {
            draft.target = same.id
        } else {
            let g = GroupItem(id: "g-\(UUID().uuidString.prefix(6))", name: name)
            model.groups.append(g)
            draft.target = g.id
        }
        draft.newGroup = nil
    }

    private func add() {
        if done { menus.closeDialog(); return }
        guard !draft.busy else { return }
        draft.busy = true
        let text = draft.text, target = draft.target
        Task { @MainActor in
            // A turn for the busy face before the parse/apply work (Android adds off the UI path; the
            // parse itself is pure text, so a yield is what a background hop would buy here).
            await Task.yield()
            let r = model.importReport(text, into: target)
            draft.busy = false
            if r.added > 0 { model.pingAll() }
            if r.problems.isEmpty && r.added > 0 {
                draft.report = nil
                menus.closeDialog()
            } else {
                // Problems stay on screen until read; what worked is already added.
                draft.report = r
                draft.importedText = text
            }
        }
    }
}

/// What the last "Add" did (Android `AddSheet`'s `ImportReport`): the totals with the pending-download
/// note, then each problem once — reason, how many lines had it, the parser's technical detail under it.
private struct Report: View {
    var result: ImportReport

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(result.message).font(Fonts.body(13, bold: true)).foregroundStyle(Palette.text).textSelection(.enabled)
            if !result.problems.isEmpty {
                Text(L("import_problems", result.problems.count)).font(Fonts.body(12)).foregroundStyle(Palette.textSecondary).textSelection(.enabled)
                let grouped = Dictionary(grouping: result.problems, by: \.text).sorted { $0.key < $1.key }
                ForEach(grouped, id: \.key) { text, problems in
                    HStack(alignment: .top, spacing: 8) {
                        Circle().fill(Palette.danger).frame(width: 6, height: 6).padding(.top, 5)
                        VStack(alignment: .leading, spacing: 1) {
                            Text(problems[0].item + (problems.count > 1 ? " " + L("import_and_more", problems.count - 1) : ""))
                                .font(.system(size: 11, design: .monospaced)).foregroundStyle(Palette.textMuted).lineLimit(1).textSelection(.enabled)
                            Text(text).font(Fonts.body(12)).foregroundStyle(Palette.text).fixedSize(horizontal: false, vertical: true).textSelection(.enabled)
                            if let detail = problems[0].detail, !detail.isEmpty {
                                Text(detail).font(.system(size: 10.5, design: .monospaced)).foregroundStyle(Palette.textMuted)
                                    .lineLimit(2).textSelection(.enabled)
                            }
                        }
                    }
                }
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 8).fill(Palette.card.opacity(0.6)))
    }
}

/// What a `borderless://import?d=` link in the field holds, before anything is added (SECURITY.md S15):
/// the unreadable base64 may come from anywhere — groups, their servers and the subscriptions' hosts.
private struct BundleInfo: View {
    var groups: [BundlePreview.Group]
    var model: MainModel

    var body: some View {
        let defaultName = model.groups.first(where: \.isDefault).map { model.groupName($0.id) } ?? L("own_group")
        VStack(alignment: .leading, spacing: 4) {
            Text(L("bundle_inside")).font(Fonts.body(12.5, bold: true)).foregroundStyle(Palette.text).textSelection(.enabled)
            ForEach(groups, id: \.self) { g in
                Text((g.isDefault ? defaultName : g.name ?? g.firstSubName ?? "—") + " · " + L("bundle_counts", g.servers, g.subs))
                    .font(Fonts.body(11.5, bold: true)).foregroundStyle(Palette.textSecondary).textSelection(.enabled)
                ForEach(g.lines, id: \.self) { line in
                    Text("• " + line.text).font(Fonts.body(11.5)).foregroundStyle(Palette.text).lineLimit(1).textSelection(.enabled)
                }
                if g.more > 0 {
                    Text(L("import_and_more", g.more)).font(Fonts.body(11)).foregroundStyle(Palette.textMuted).textSelection(.enabled)
                }
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 8).fill(Palette.card.opacity(0.6)))
    }
}
