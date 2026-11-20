import SwiftUI

/// Servers of chosen countries only (Android `CountryScreen.kt`), as a popup from the globe button: search, sort, the
/// countries with their servers; the flag toggles selection, the row unfolds its servers. Chosen countries sit on top.
/// Several may be chosen; none = any country.
/// The working state — query, sort, the empty-countries switch — is `MenuState`'s (`countriesQuery` and
/// friends), not @State here: the Sort button's menu flies out of this popup, which used to destroy it —
/// and the pick wrote into a dead view's @State. Hoisted, it survives the menu and the reopen. The sort
/// itself is the settings' (`AppSettings.countrySort`), persisted through the service like on Android.
struct CountriesPopup: View {
    @Bindable var model: MainModel
    @Environment(MenuState.self) private var menus
    @Environment(\.snapshot) private var snapshot
    /// Countries the user unfolded to see their servers (in-memory, like the groups' folding).
    @State private var unfolded: Set<String> = []

    enum Sort: String, CaseIterable {
        case name = "NAME"
        case nameDesc = "NAME_DESC"
        case used = "USED"
        case stable = "STABLE"
    }
    static let rowHeight: CGFloat = 46

    struct Country: Identifiable {
        var id: String { code }
        let code: String
        let name: String
        let servers: [ServerItem]
        /// The service's `target:"countries"` figures for it, nil when it sent none — the used and
        /// stable sorts then fall back to the row's own numbers.
        var stat: CountryStat?
        var best: Int? { servers.compactMap(\.ms).min() }
        /// Share of its servers that answer, of the ones ever checked — the local stand-in for the
        /// service's week of answering slices (used only while [stat] is absent). A never-checked
        /// server (`checked == false`) isn't a failed answer, so it's out of the count entirely.
        var localStable: Double {
            let tried = servers.filter(\.checked)
            return tried.isEmpty ? 0 : Double(tried.filter { $0.ms != nil }.count) / Double(tried.count)
        }
        /// How reliably it answers: the service's smoothed share if it said, else the local one.
        var stable: Double? { stat?.stable ?? (servers.isEmpty ? nil : localStable) }
        /// How often it was chosen (the service's settings-log count, 0 while unknown).
        var used: Int { stat?.chosen ?? 0 }
    }

    var body: some View {
        @Bindable var menus = menus
        let rows = countries
        let chosen = rows.filter { model.countries.contains($0.code) }
        let rest = rows.filter { !model.countries.contains($0.code) }
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 6) {
                Text(L("countries_title")).font(Fonts.body(13, bold: true)).foregroundStyle(Palette.text).textSelection(.enabled)
                Spacer()
                sortButton
            }
            .padding(.horizontal, 4).padding(.top, 2)
            SearchField(text: $menus.countriesQuery, placeholder: L("country_search"))
            // Snapshots lay the list plainly (the popup sizes to the content), the first rows only;
            // live it hugs the content up to 340 and scrolls past it — a fixed 340 left a blank card
            // under a short list.
            if snapshot {
                VStack(spacing: 0) { list(chosen, rest, limit: 7) }
            } else {
                HugScroll(max: 340) {
                    LazyVStack(spacing: 0) { list(chosen, rest, limit: nil) }
                }
            }
            Hairline()
            HStack {
                Text(L("country_show_empty")).font(Fonts.body(12)).foregroundStyle(Palette.text).textSelection(.enabled)
                Spacer()
                AppSwitch(isOn: $menus.countriesShowEmpty)
            }
            .padding(.horizontal, 4)
        }
        .padding(8)
        .frame(width: 300)
        .onAppear {
            // The sort is persisted in the settings JSON (AppSettings.countrySort): the hoisted
            // MenuState is the live copy the Sort menu binds to.
            menus.countriesSort = Sort(rawValue: model.settings.countrySort) ?? .name
            // The service's country metrics arrive with the popup's first draw.
            model.refreshCountryStats()
        }
    }

    /// The chosen countries, the note and a line under them, then the rest.
    @ViewBuilder private func list(_ chosen: [Country], _ rest: [Country], limit: Int?) -> some View {
        let visible = chosen + Array(rest.prefix(limit ?? rest.count))
        ForEach(visible) { c in
            row(c, chosen: model.countries.contains(c.code))
            if c.code == chosen.last?.code {
                Text(L("country_note")).font(Fonts.body(11)).foregroundStyle(Palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true).textSelection(.enabled)
                    .padding(.horizontal, 8).padding(.vertical, 6)
                Hairline().padding(.horizontal, 6)
            }
        }
        if chosen.isEmpty && rest.isEmpty {
            Text(L("set_search_none")).font(Fonts.body(12)).foregroundStyle(Palette.textSecondary).padding(12).textSelection(.enabled)
        }
    }

    private func row(_ c: Country, chosen: Bool) -> some View {
        // A row with servers unfolds to them (the country's members); a serverless one doesn't open.
        let open = unfolded.contains(c.code)
        return VStack(alignment: .leading, spacing: 0) {
            CountryRow(c: c, chosen: chosen, sub: sub(c), open: open, toggleOpen: {
                if open { unfolded.remove(c.code) } else { unfolded.insert(c.code) }
            }) {
                var next = model.countries
                if next.contains(c.code) { next.remove(c.code) } else { next.insert(c.code) }
                model.setCountries(next)
            }
            if open {
                // The country's members as an inset drawer: the pale card under the name column says
                // "inside the country" where bare names read as more country rows; each line is a
                // small muted server line — flag, name, the protocol detail a server row carries —
                // a disabled one dims further.
                VStack(alignment: .leading, spacing: 0) {
                    ForEach(c.servers) { s in
                        HStack(spacing: 6) {
                            FlagView(code: s.country, height: 9)
                            if s.limited {
                                Image(systemName: "antenna.radiowaves.left.and.right").font(.system(size: 8, weight: .bold))
                                    .foregroundStyle(StateTone.limited.strong)
                            }
                            Text(model.displayName(s)).font(Fonts.body(11))
                                .foregroundStyle(s.disabled ? Palette.textMuted.opacity(0.6) : Palette.textMuted).lineLimit(1)
                            Text(s.proto).font(Fonts.body(10))
                                .foregroundStyle(Palette.textMuted.opacity(0.7)).lineLimit(1)
                            Spacer(minLength: 4)
                            PingText(server: s)
                        }
                        .padding(.leading, 8).padding(.trailing, 4).frame(height: 19)
                    }
                }
                .padding(.vertical, 3)
                .background(RoundedRectangle(cornerRadius: Dims.cornerRadius).fill(Palette.card.opacity(0.5)))
                .padding(.leading, 52).padding(.trailing, 6).padding(.bottom, 4)
            }
        }
    }

    private func sub(_ c: Country) -> String {
        guard !c.servers.isEmpty else { return L("country_no_servers") }
        // The line's second part follows the sort's metric (Android `CountryScreen`'s row extra):
        // the week's answer share under STABLE, the all-times-chosen count under USED, the best
        // ping under the name sorts.
        let extra: String?
        switch menus.countriesSort {
        case .stable: extra = c.stable.map { L("country_answers", Int($0 * 100)) }
        case .used: extra = P("country_used_n", c.used)
        case .name, .nameDesc: extra = c.best.map { L("country_best", $0) }
        }
        return [P("country_servers", c.servers.count), extra].compactMap { $0 }.joined(separator: " · ")
    }

    private var sortButton: some View {
        // The arrows say it opens a list: no chevron.
        PickerButton(spacing: 4, pad: 7, chevron: nil) {
            Image(systemName: "arrow.up.arrow.down").font(.system(size: 10, weight: .medium)).foregroundStyle(Palette.textSecondary)
            Text(L("country_sort")).font(Fonts.body(12))
        }
        .fixedSize()
        .appMenuOnClick(menus) {
            Sort.allCases.map { s in MenuItem(title: title(s), selected: s == menus.countriesSort) {
                menus.countriesSort = s
                // The settings carry it through the service (Android `AppSettings.countrySort`).
                model.settings.countrySort = s.rawValue
            } }
        }
    }

    private func title(_ s: Sort) -> String {
        switch s {
        case .name: L("country_sort_name")
        case .nameDesc: L("country_sort_name_desc")
        case .used: L("country_sort_used")
        case .stable: L("country_sort_stable")
        }
    }

    private var countries: [Country] {
        // `Countries.of` on Android: a serverless code is the unknown country, never dropped.
        let byCode = Dictionary(grouping: model.servers, by: { countryOf($0) })
        var codes = Set(byCode.keys)
        // With the empty countries on, the unknown one is among them too (Android `CountryScreen`
        // unions `Countries.UNKNOWN` with the empty set) — it always sorts last.
        if menus.countriesShowEmpty { codes.formUnion(CountryPicker.countries.map(\.code) + [unknownCountryCode]) }
        let q = menus.countriesQuery.trimmingCharacters(in: .whitespaces)
        var list = codes.map { code in
            Country(code: code, name: code == unknownCountryCode ? L("country_unknown") : shortCountryName(code),
                    servers: byCode[code] ?? [], stat: model.countryStats?[code])
        }
            .filter { q.isEmpty || $0.name.range(of: q, options: [.caseInsensitive, .diacriticInsensitive]) != nil || $0.code.caseInsensitiveCompare(q) == .orderedSame }
        let byName: (Country, Country) -> Bool = { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
        // The unknown country is never a country — it sorts after every real one in every order.
        func unknownLast(_ a: Country, _ b: Country, _ order: (Country, Country) -> Bool) -> Bool {
            if a.code == unknownCountryCode { return false }
            if b.code == unknownCountryCode { return true }
            return order(a, b)
        }
        switch menus.countriesSort {
        case .name: list.sort { unknownLast($0, $1, byName) }
        case .nameDesc: list.sort { unknownLast($0, $1) { byName($1, $0) } }
        case .used:
            // All-time choices from the settings log (the service's `chosen`), never-checked
            // countries together at 0; by name inside a tie.
            list.sort { unknownLast($0, $1) { $0.used != $1.used ? $0.used > $1.used : byName($0, $1) } }
        case .stable:
            // The week's answering share by time (`stable`, smoothed service-side); a country never
            // checked has none and lands at the end, by name.
            list.sort { unknownLast($0, $1) {
                ($0.stable ?? -1) != ($1.stable ?? -1) ? ($0.stable ?? -1) > ($1.stable ?? -1) : byName($0, $1)
            } }
        }
        return list
    }
}

/// A country line in the popup: flag box, name and the grey line under it, highlighted under the pointer.
private struct CountryRow: View {
    var c: CountriesPopup.Country
    var chosen: Bool
    var sub: String
    var open: Bool
    var toggleOpen: () -> Void
    var action: () -> Void
    @State private var hover = false

    var body: some View {
        ZStack(alignment: .leading) {
            // The row owns every point around the flag too; only the flag button above it selects.
            Button(action: toggleOpen) {
                HStack(spacing: 0) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(c.name).font(Fonts.body(13, bold: chosen)).foregroundStyle(Palette.text).lineLimit(1)
                        Text(sub).font(Fonts.body(11)).foregroundStyle(Palette.textSecondary).lineLimit(1)
                    }
                    Spacer(minLength: 0)
                    if !c.servers.isEmpty {
                        FoldChevron(open: open).frame(width: 36, height: CountriesPopup.rowHeight)
                    }
                }
                .padding(.leading, 52).padding(.trailing, 2)
                .frame(maxWidth: .infinity, minHeight: CountriesPopup.rowHeight)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .disabled(c.servers.isEmpty)
            .help(macText(open ? "mac.countries.hide_servers" : "mac.countries.show_servers",
                          open ? "Hide servers" : "Show servers"))
            .accessibilityLabel(c.name)
            .accessibilityValue(open ? "expanded" : "collapsed")
            Button(action: action) {
                FlagView(code: c.code == unknownCountryCode ? nil : c.code, height: 13)
                    .frame(width: 32, height: 30)
                    .background(RoundedRectangle(cornerRadius: Dims.cornerRadius)
                        .fill(chosen ? Palette.selected : Palette.cardStrong.opacity(0.6)))
                    .overlay(RoundedRectangle(cornerRadius: Dims.cornerRadius)
                        .strokeBorder(chosen ? Palette.accent : Palette.outline, lineWidth: chosen ? 1.5 : 1))
                    .contentShape(RoundedRectangle(cornerRadius: Dims.cornerRadius))
            }
            .buttonStyle(.plain)
            .help(L(chosen ? "country_unchoose" : "country_use"))
            .accessibilityLabel(c.name + ", " + L(chosen ? "country_unchoose" : "country_use"))
            .padding(.leading, 8)
        }
        .background(RoundedRectangle(cornerRadius: Dims.cornerRadius)
            .fill(chosen ? Palette.selected : hover ? Palette.cardStrong : .clear))
        .contentShape(Rectangle())
        .onHover { hover = $0 }
    }
}
