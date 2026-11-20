import SwiftUI

/// The country of a server, chosen from a list (the Mac's form of Android's country field, where a code, a name or a
/// city is typed): a search field, "automatic" (detected from the name or the address) first, then every country with a
/// flag, by name in the UI language. Opens in the menu's card (`MenuState.show(popup:)`).
struct CountryPicker: View {
    /// The country set by the user, nil = automatic.
    var manual: String?
    var onPick: (String?) -> Void
    @State private var query = ""
    @FocusState private var searchFocused: Bool
    static let rowHeight: CGFloat = 28
    @Environment(\.snapshot) private var snapshot

    var body: some View {
        let rows = Self.countries.filter { matches($0) }
        VStack(alignment: .leading, spacing: 4) {
            searchField
                .padding(.horizontal, 3).padding(.top, 3).padding(.bottom, 2)
            if query.isEmpty {
                Row(code: nil, name: capitalized(L("country_auto")), on: manual == nil) { onPick(nil) }
                Rectangle().fill(Palette.outline).frame(height: 1).padding(.horizontal, 6).padding(.vertical, 2)
            }
            if rows.isEmpty {
                // A search that matched nothing says so instead of leaving a blank list area.
                Text(L("set_search_none")).font(Fonts.body(12)).foregroundStyle(Palette.textSecondary)
                    .padding(.horizontal, 8).padding(.vertical, 10).textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .leading)
            } else if snapshot {
                VStack(spacing: 0) { ForEach(Array(rows.prefix(9).enumerated()), id: \.element.code) { row($1) } }
            } else {
                ScrollViewReader { proxy in
                    // Hugs the content up to 260 and scrolls past it — a fixed 260 left a void.
                    HugScroll(max: 260) {
                        LazyVStack(spacing: 0) {
                            ForEach(rows, id: \.code) { c in row(c).id(c.code) }
                        }
                    }
                    .onAppear {
                        // scrollTo on a code the list doesn't have is a silent no-op: the top then.
                        if let hit = manual.flatMap({ m in rows.first { $0.code.caseInsensitiveCompare(m) == .orderedSame }?.code }) {
                            proxy.scrollTo(hit, anchor: .center)
                        } else if let first = rows.first {
                            proxy.scrollTo(first.code, anchor: .top)
                        }
                    }
                }
            }
        }
        .padding(5)
        .frame(width: 250)
        .onAppear {
            // Opens ready to type; a run later the field's view is in the window and can take the
            // focus — a plain window needs none of the panel machinery the menu-bar panel does
            // (`MenuBarPanel.focusSearch`), just the beat.
            DispatchQueue.main.async { searchFocused = true }
        }
    }

    /// `SearchField`'s own field, inlined: `.focused` has to sit on the field itself and can't be
    /// handed in through the wrapper (the menu-bar panel builds the same HStack for the same reason).
    private var searchField: some View {
        let stripped = Binding(get: { query }, set: { query = String($0.drop(while: \.isWhitespace)) })
        return HStack(spacing: 5) {
            Image(systemName: "magnifyingglass").font(.system(size: 11)).foregroundStyle(Palette.textSecondary)
            PlainField(placeholder: L("country_search"), text: stripped, snapshot: snapshot)
                .font(Fonts.body(12))
                .focused($searchFocused)
            if !query.isEmpty {
                Image(systemName: "xmark.circle.fill").font(.system(size: 11)).foregroundStyle(Palette.textMuted)
                    .onTapGesture { query = "" }
            }
        }
        .padding(.horizontal, 7)
        .frame(height: Dims.searchH)
        .background(RoundedRectangle(cornerRadius: Dims.cornerRadius).fill(Palette.cardStrong.opacity(0.7)))
    }

    private func row(_ c: (code: String, name: String)) -> some View {
        Row(code: c.code, name: c.name, on: manual == c.code) { onPick(c.code) }
    }

    private func matches(_ c: (code: String, name: String)) -> Bool {
        let q = query.trimmingCharacters(in: .whitespaces)
        if q.isEmpty { return true }
        if c.code.caseInsensitiveCompare(q) == .orderedSame { return true }
        let english = Locale(identifier: "en").localizedString(forRegionCode: c.code) ?? ""
        return [c.name, english].contains { $0.range(of: q, options: [.caseInsensitive, .diacriticInsensitive]) != nil }
    }

    private func capitalized(_ s: String) -> String { s.prefix(1).uppercased() + s.dropFirst() }

    /// Every country with a flag in the app, by name in the UI language.
    @MainActor static let countries: [(code: String, name: String)] = {
        let codes = (Bundle.main.urls(forResourcesWithExtension: "webp", subdirectory: "Flags") ?? [])
            .map { $0.deletingPathExtension().lastPathComponent.uppercased() }
            .filter { $0.count == 2 }
        return codes.map { ($0, shortCountryName($0)) }
            .sorted { $0.1.localizedStandardCompare($1.1) == .orderedAscending }
    }()

    private struct Row: View {
        var code: String?
        var name: String
        var on: Bool
        var action: () -> Void
        @State private var hover = false

        var body: some View {
            HStack(spacing: 8) {
                // Chosen the way the countries popup marks it: the icon in a pale accent box, the name bold.
                Group {
                    if let code { FlagView(code: code, height: 12) } else {
                        Image(systemName: "wand.and.stars").font(.system(size: 11)).foregroundStyle(Palette.textSecondary)
                    }
                }
                .frame(width: 24, height: 22)
                .background(RoundedRectangle(cornerRadius: 5).fill(on ? Palette.selected : Palette.cardStrong.opacity(0.6)))
                .overlay(RoundedRectangle(cornerRadius: 5).strokeBorder(on ? Palette.accent : Palette.outline, lineWidth: on ? 1.5 : 1))
                Text(name).font(Fonts.body(13, bold: on)).foregroundStyle(Palette.text).lineLimit(1)
                Spacer(minLength: 4)
            }
            .padding(.horizontal, 8)
            .frame(height: CountryPicker.rowHeight)
            // A quiet grey highlight (owner, 2026-10-23), not the menus' accent.
            .background(RoundedRectangle(cornerRadius: 5).fill(hover ? Palette.cardStrong : .clear))
            .contentShape(RoundedRectangle(cornerRadius: 5))
            .onHover { hover = $0 }
            .onTapGesture(perform: action)
        }
    }
}
