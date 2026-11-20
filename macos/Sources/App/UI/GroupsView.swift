import Shared
import SwiftUI

/// One level of the outline's indent — a row's icon column starts this far right of its parent's.
private let outlineIndent: CGFloat = 22

/// The most streams a limited-network server can be given by hand (`LimitedPower.MAX_STREAMS` of the shared
/// code — the Kotlin module isn't imported here, only `SharedCode` reads it).
private let maxManualStreams = 36

/// The Groups screen (Android `GroupsScreen.kt`) laid out for a wide window: the groups as an outline (folding rows,
/// hand-added servers, each subscription with its servers indented under it) and, on the right, an inspector for the
/// selected server or subscription — its figures, then its settings (Android's settings dialog, edited in place), then
/// its actions. Same rows, same menus, same words.
struct GroupsView: View {
    @Bindable var model: MainModel
    @Environment(MenuState.self) private var menus
    @State private var frames: [String: CGRect] = [:]
    @Environment(\.snapshot) private var snapshot

    var body: some View {
        VStack(spacing: 0) {
            ScreenHeader(title: L("groups")) {
                ToolbarIcon(symbol: "wand.and.stars", on: model.autoActive,
                            help: L("auto_active") + "\n" + L("auto_active_hint", model.visible.count, model.servers.count)) {
                    model.setAutoActive(!model.autoActive)
                }
                ToolbarIcon(symbol: "arrow.up.arrow.down", on: model.autoSort, help: L("auto_sort") + "\n" + L("auto_sort_hint")) {
                    model.setAutoSort(!model.autoSort)
                }
                Hairline(vertical: true).frame(height: 16).padding(.horizontal, 4)
                ToolbarIcon(symbol: "square.and.arrow.up", help: L("share_all")) {
                    menus.show(dialog: AnyView(ShareDialog(model: model, scope: .all)))
                }
                ToolbarIcon(symbol: "folder.badge.plus", help: L("group_new"), action: nil)
                    .appPopupOnClick(menus) { AnyView(NameField(title: L("group_new"), initial: "") { name in
                        menus.close()
                        // Eight hex chars, Android's group ids; made folded like every group.
                        model.groups.append(GroupItem(id: newGroupId(), name: name))
                    }) }
            }
            HStack(spacing: 0) {
                Group {
                    if snapshot {
                        // Takes the space it is given and cuts the rest (a plain frame would grow with the list).
                        SnapshotClip { list(nil) }
                    } else {
                        // Hover from the pointer and the rows' frames, in step with the scrolling (`HoverScroll`).
                        HoverScroll { pointer in
                            // As on the main list: rows overlap while a group unfolds — the dictionary's
                            // order isn't stable, so the upper of two candidates always wins.
                            let hovered = pointer.flatMap { p in
                                frames.filter { $0.value.minY <= p && p < $0.value.maxY }.min { $0.value.minY < $1.value.minY }?.key
                            }
                            list(hovered)
                                .coordinateSpace(name: "groups")
                                .onPreferenceChange(RowFrames.self) { frames = $0 }
                                .padding(.trailing, 14)
                        }
                        .scrollIndicators(.automatic)
                        // The scroller sits in the margin below, outside the rows (the main list's
                        // trick): it covered the trailing buttons and pings.
                        .padding(.trailing, -14)
                    }
                }
                .frame(maxWidth: .infinity)
                // The margin the scroller is outdented into, between the rows and the divider.
                .padding(.trailing, 14)
                Hairline(vertical: true)
                Inspector(model: model)
                    .frame(width: 276)
                    .frame(maxHeight: .infinity, alignment: .top)
                    .background(Palette.card.opacity(0.45))
            }
        }
        .background(Palette.background)
    }

    private func list(_ hovered: String?) -> some View {
        // Lazy: a subscription can carry hundreds of rows; only the visible ones are built (the snapshot's flat
        // `SnapshotClip` renders them all anyway).
        LazyVStack(alignment: .leading, spacing: 2) {
            // The modes' explanation sits in the list itself (Android's `Hint` cards), not only in
            // the toolbar icons' tooltips.
            if model.autoActive {
                HintCard(text: L("auto_active_hint", model.visible.count, model.servers.count))
            }
            if model.autoSort {
                HintCard(text: L("auto_sort_hint"))
            }
            ForEach(model.groups) { g in GroupBlock(model: model, group: g, hovered: hovered) }
        }
        .padding(10)
    }
}

/// Android's `Hint`: the always-visible explanation card a mode leaves in the list.
private struct HintCard: View {
    var text: String

    var body: some View {
        Text(text).font(Fonts.body(12)).foregroundStyle(Palette.textSecondary)
            .fixedSize(horizontal: false, vertical: true).textSelection(.enabled)
            .padding(.horizontal, 12).padding(.vertical, 10)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: Dims.cardRadius).fill(Palette.card.opacity(0.55)))
            .overlay(RoundedRectangle(cornerRadius: Dims.cardRadius).strokeBorder(Palette.outline.opacity(0.7), lineWidth: 1))
            .padding(.bottom, 6)
    }
}

private struct GroupBlock: View {
    @Bindable var model: MainModel
    var group: GroupItem
    var hovered: String?
    @Environment(MenuState.self) private var menus

    var body: some View {
        let members = model.servers.filter { $0.groupId == group.id }
        let subs = model.subscriptions.filter { $0.groupId == group.id }
        // Groups start folded (Android `GroupsScreen.expanded` begins empty); `unfolded` holds what
        // the user opened — a subscription's hundreds of rows stay put away until asked for.
        let open = model.unfolded.contains(group.id)
        // With auto-sort on the rows follow the scan's order, not the user's (Android's `plan` /
        // `rank`): fastest first, never checked next, no reply last.
        let ordered = model.autoSort
            ? members.sorted { a, b in
                let ra = pingRank(a), rb = pingRank(b)
                if ra != rb { return ra < rb }
                guard ra == 0, let x = a.ms, let y = b.ms else { return a.id < b.id }
                return x != y ? x < y : a.id < b.id
            }
            : members
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 6) {
                FoldChevron(open: open)
                    .frame(width: 12)
                Text(group.name).font(Fonts.body(13, bold: true)).foregroundStyle(Palette.text).lineLimit(1)
                Text(countLine(members, subs)).font(Fonts.body(12)).foregroundStyle(Palette.textSecondary).lineLimit(1)
                Spacer()
                MoreButton().opacity(hovered == "group:" + group.id ? 1 : 0)
                    .allowsHitTesting(hovered == "group:" + group.id)
                    .appMenuOnClick(menus) { groupMenu() }
            }
            .padding(.leading, 6).padding(.trailing, 4)
            .frame(height: Dims.rowH)
            .contentShape(Rectangle())
            .rowFrame("group:" + group.id, in: "groups")
            .onTapGesture {
                withAnimation(.easeInOut(duration: 0.2)) {
                    if open { model.unfolded.remove(group.id) } else { model.unfolded.insert(group.id) }
                }
            }
            .appContextMenu(menus) { groupMenu() }
            // The members stay in the tree at height 0, clipped (the server cards' reveal): folding
            // never draws over the header, and a fast run of clicks still lands right.
            VStack(alignment: .leading, spacing: 0) {
                ForEach(ordered.filter { $0.subscriptionId == nil }) { s in GroupServerRow(model: model, server: s, indent: outlineIndent, hover: hovered == s.id, open: open) }
                ForEach(subs) { sub in
                    // Per-subscription fold (Android `GroupsScreen`'s `folded` set): open by default,
                    // the chevron puts a subscription's rows away inside its already-open group.
                    let subOpen = !model.foldedSubs.contains(sub.id)
                    SubscriptionRow(model: model, sub: sub, hover: hovered == sub.id, open: open, subOpen: subOpen) {
                        withAnimation(.easeInOut(duration: 0.2)) {
                            if subOpen { model.foldedSubs.insert(sub.id) } else { model.foldedSubs.remove(sub.id) }
                        }
                    }
                    if subOpen {
                        ForEach(ordered.filter { $0.subscriptionId == sub.id }) { s in GroupServerRow(model: model, server: s, indent: 2 * outlineIndent, hover: hovered == s.id, open: open) }
                    }
                }
                if members.isEmpty && subs.isEmpty {
                    Text(L("group_empty")).font(Fonts.body(12)).foregroundStyle(Palette.textSecondary)
                        .padding(.leading, 28).frame(height: 26).textSelection(.enabled)
                }
            }
            .padding(.bottom, 6)
            .frame(height: open ? nil : 0, alignment: .top)
            .clipped()
            .allowsHitTesting(open)
            .accessibilityHidden(!open)
        }
    }

    private func countLine(_ members: [ServerItem], _ subs: [SubscriptionItem]) -> String {
        let kind = subs.isEmpty ? L("group_kind_own") : L("group_kind_subs", subs.map(\.name).joined(separator: ", "))
        // "n, m off" counts the inactive (Android `off = members.count { !isActive }`): under
        // auto-visibility or the countries filter that includes enabled servers out of the pool.
        let off = members.filter { !model.isActive($0) }.count
        return off == 0 ? L("group_count", members.count, kind) : L("group_count_off", members.count, off, kind)
    }

    private func groupMenu() -> [MenuItem] {
        var items = [MenuItem(title: L("share"), symbol: "square.and.arrow.up") {
            menus.show(dialog: AnyView(ShareDialog(model: model, scope: .group(group.id))))
        }]
        if !group.isDefault {
            items.append(MenuItem(title: L("group_rename"), symbol: "pencil") {
                menus.show(dialog: AnyView(NameField(title: L("group_rename"), initial: group.name) { name in
                    menus.closeDialog()
                    if let i = model.groups.firstIndex(where: { $0.id == group.id }) {
                        // A rename gives a nameless group a name of its own (Android's groupName edit).
                        model.groups[i].name = name
                        model.groups[i].nameless = false
                    }
                }.padding(8)))
            })
            items.append(MenuItem(title: L("delete"), symbol: "trash", danger: true) {
                // Its servers and subscriptions move to the default group (Android `Repo.deleteGroup`),
                // which the dialog names.
                let home = model.groupName(StoredGroup.defaultId)
                confirmDelete(menus, L("group_delete_q"), detail: L("group_delete_text2", home)) {
                    for i in model.servers.indices where model.servers[i].groupId == group.id { model.servers[i].groupId = StoredGroup.defaultId }
                    for i in model.subscriptions.indices where model.subscriptions[i].groupId == group.id { model.subscriptions[i].groupId = StoredGroup.defaultId }
                    model.groups.removeAll { $0.id == group.id }
                    model.unfolded.remove(group.id)
                }
            })
        }
        return items
    }
}

/// "⋯": the row's menu (Android's "⋮"), shown on hover; right click opens the same menu. Its opacity is
/// hover-driven only: while the menu it opened is up, the pointer may sit over the menu and the button
/// fades out mid-use. `MenuState` knows one open menu for the window, not which row opened it, so there
/// is no flag to hold it visible on — that needs a per-opener identity in the menus' state first.
private struct MoreButton: View {
    var light = false
    @State private var hover = false

    var body: some View {
        Image(systemName: "ellipsis")
            .font(.system(size: 12, weight: .semibold)).foregroundStyle(light ? Palette.onAccent : Palette.textSecondary)
            .frame(width: 22, height: 20)
            .background(RoundedRectangle(cornerRadius: 5).fill(hover ? (light ? Palette.onAccent.opacity(0.2) : Palette.cardStrong) : .clear))
            .contentShape(Rectangle())
            .onHover { hover = $0 }
            .help(L("more"))
    }
}

/// Reports a row's frame to the hover map only while its group is unfolded (`rowFrame` can't be attached
/// conditionally in a chain, so the switch lives in a modifier).
private struct FrameReporter: ViewModifier {
    var open: Bool
    var id: String
    func body(content: Content) -> some View {
        if open { content.rowFrame(id, in: "groups") } else { content }
    }
}

/// A server in the outline: flag, name, protocol in grey, hidden mark, ping. Selected: the accent fill (a Mac list's
/// selection); a click selects it for the inspector (no double click — Connect lives in the row's menu).
private struct GroupServerRow: View {
    @Bindable var model: MainModel
    var server: ServerItem
    var indent: CGFloat
    var hover: Bool
    /// False while the group is folded: a clipped member keeps reporting a real frame otherwise, and
    /// its phantom rect sits over the rows of the group below, stealing their hover.
    var open = true
    @Environment(MenuState.self) private var menus

    var body: some View {
        // As on the main screen: the server in use filled with the accent, the selected one (shown in the inspector) light.
        let selected = model.selectedId == server.id
        let current = model.phase == .connected && server.id == model.currentId
        // The row dims on inactivity, not only on the hidden flag (Android `off = !isActive`): under
        // auto-visibility the pool decides and the eye only shows it.
        let inactive = !model.isActive(server)
        let dim = inactive && !selected
        HStack(spacing: 8) {
            ServerLine(model: model, server: server, detail: server.proto,
                       light: current, fade: dim ? .dim : .none, mark: server.limited)
            Spacer(minLength: 6)
            if inactive {
                // eye.slash for hidden by the user, a plain eye when the pool or the countries
                // filter set it aside (the menu's Enable/Disable offers to put it back).
                Image(systemName: server.disabled ? "eye.slash" : "eye")
                    .font(.system(size: 11)).foregroundStyle(current ? Palette.onAccent : Palette.textMuted)
            }
            MoreButton(light: current).opacity(hover || selected ? 1 : 0)
                .allowsHitTesting(hover || selected)
                .appMenuOnClick(menus) { serverMenu(model, server, menus) }
            if server.ms != nil { PingDot(ms: server.ms).opacity(dim ? 0.4 : 1) }
            PingText(server: server, light: current, dim: dim)
        }
        .padding(.leading, indent).padding(.trailing, 10)
        .frame(height: Dims.rowH)
        .background(RoundedRectangle(cornerRadius: Dims.cornerRadius).fill(current ? Palette.accent : selected ? Palette.selected : hover ? Palette.card : .clear))
        .overlay(current && selected ? RoundedRectangle(cornerRadius: Dims.cornerRadius).strokeBorder(Palette.accentStrong, lineWidth: 1.5) : nil)
        .contentShape(RoundedRectangle(cornerRadius: Dims.cornerRadius))
        .modifier(FrameReporter(open: open, id: server.id))
        .onTapGesture {
            // As the main list's pick: a click on the selected row lets it go.
            model.selectedId = selected ? nil : server.id
        }
        .appContextMenu(menus) { serverMenu(model, server, menus) }
    }
}

/// A subscription in the outline: its name, the lines of Android's row (count and update, traffic, schedule) and a
/// bar of the traffic used; its servers follow, indented.
private struct SubscriptionRow: View {
    @Bindable var model: MainModel
    var sub: SubscriptionItem
    var hover: Bool
    /// See `GroupServerRow.open`.
    var open = true
    /// The subscription's own fold (its server rows under it); the chevron toggles it.
    var subOpen = true
    var onFold: () -> Void = {}
    @Environment(MenuState.self) private var menus

    var body: some View {
        let selected = model.selectedId == sub.id
        let grey = Palette.textSecondary
        HStack(alignment: .top, spacing: 8) {
            FoldChevron(open: subOpen)
                .frame(width: 12, height: 16)
                .contentShape(Rectangle())
                .onTapGesture(perform: onFold)
            Image(systemName: "dot.radiowaves.up.forward").font(.system(size: 12, weight: .semibold))
                .foregroundStyle(Palette.accent)
                .frame(width: 20, height: 16)
            VStack(alignment: .leading, spacing: 1) {
                Text(sub.name).font(Fonts.body(13, bold: true)).foregroundStyle(Palette.text)
                ForEach(subscriptionLines(model, sub), id: \.self) { line in
                    Text(line).font(Fonts.body(11.5)).foregroundStyle(grey).lineLimit(1)
                }
                Text(scheduleText(model, sub)).font(Fonts.body(11.5)).foregroundStyle(Palette.textMuted)
                // The last update's failure, as Android's red line (the service reports it in the list).
                if let error = sub.lastError {
                    ExpandableError(message: L("sub_error", error))
                }
                if let used = sub.usedBytes, let total = sub.totalBytes, total > 0 {
                    UsageBar(share: Double(used) / Double(total)).frame(width: 140).padding(.top, 3)
                }
            }
            Spacer()
            // A subscription update in flight spins where the menu sits (Android's busy state).
            if model.subsUpdating {
                Spinner(lineWidth: 1.5).frame(width: 14, height: 14).padding(3)
            } else {
                MoreButton().opacity(hover || selected ? 1 : 0)
                    .allowsHitTesting(hover || selected)
                    .appMenuOnClick(menus) { subscriptionMenu(model, sub, menus) }
            }
        }
        .padding(.leading, outlineIndent).padding(.trailing, 10).padding(.vertical, 6)
        .background(RoundedRectangle(cornerRadius: Dims.cornerRadius).fill(selected ? Palette.selected : hover ? Palette.card : .clear))
        .contentShape(RoundedRectangle(cornerRadius: Dims.cornerRadius))
        .modifier(FrameReporter(open: open, id: sub.id))
        .onTapGesture { model.selectedId = selected ? nil : sub.id }
        .appContextMenu(menus) { subscriptionMenu(model, sub, menus) }
    }
}

/// A thin bar of a share (traffic used, availability).
struct UsageBar: View {
    var share: Double
    var light = false

    var body: some View {
        GeometryReader { g in
            ZStack(alignment: .leading) {
                Capsule().fill(light ? Palette.onAccent.opacity(0.3) : Palette.cardStrong)
                Capsule().fill(light ? Palette.onAccent : Palette.bar).frame(width: g.size.width * CGFloat(min(max(share, 0), 1)))
            }
        }
        .frame(height: 4)
    }
}

/// "Servers: 11 · updated 42 minutes ago", "Traffic: 20.8 GB of 93.1 GB · until 4 Jan 2027" (Android's lines).
@MainActor
func subscriptionLines(_ model: MainModel, _ sub: SubscriptionItem) -> [String] {
    let count = model.servers.filter { $0.subscriptionId == sub.id }.count
    let updated = sub.updatedAt.map { L("sub_updated", relativeText($0)) } ?? L("sub_never")
    var lines = [L("sub_line", count, updated) + (sub.viaServer ? " · " + L("sub_via_server") : "")]
    var traffic: [String] = []
    if let used = sub.usedBytes {
        traffic.append(sub.totalBytes.map { L("traffic_of", bytesText(used), bytesText($0)) } ?? L("traffic_used", bytesText(used)))
    }
    if let expire = sub.expire { traffic.append(L("until", dateText(expire))) }
    if !traffic.isEmpty { lines.append(L("sub_traffic_line", traffic.joined(separator: " · "))) }
    return lines
}

/// When the subscription is updated automatically (Android `GroupsScreen.schedule` over
/// `SubMath.intervalHours`): the provider's own wish — floored at three hours and doubled in battery
/// saving — if it gave one, else the "Update subscriptions" setting (0 = manual only).
@MainActor
func scheduleText(_ model: MainModel, _ sub: SubscriptionItem) -> String {
    let hours = intervalHours(model.settings.subUpdateHours, sub.providerHours, model.eco)
    let provider = sub.providerHours ?? 0
    if hours == 0 { return L("sub_sched_manual") }
    if provider <= 0 { return L("sub_sched", hours) }
    // Below the floor the caption says so, not just "provider" (the actual interval is the floored one).
    return provider >= minProviderHours ? L("sub_sched_provider", hours) : L("sub_sched_provider_min", provider, hours)
}

@MainActor
func subscriptionMenu(_ model: MainModel, _ sub: SubscriptionItem, _ menus: MenuState) -> [MenuItem] {
    var items = [
        MenuItem(title: L("update"), symbol: "arrow.clockwise") {
            model.backend?.runAction("update_sub:\(sub.id)")
        },
        MenuItem(title: L("action_stats"), symbol: "chart.xyaxis.line") { model.openStats(.subscription(sub.id)) },
        MenuItem(title: L("action_server_settings"), symbol: "slider.horizontal.3") { model.openSettings(of: sub.id) },
        MenuItem(title: L("share"), symbol: "square.and.arrow.up") {
            menus.show(dialog: AnyView(ShareDialog(model: model, scope: .subscription(sub.id))))
        },
    ]
    // Moves with all its servers (Android `Repo.moveSubscription`): every other group is offered.
    let others = model.groups.filter { $0.id != sub.groupId }
    if !others.isEmpty {
        items.append(MenuItem(title: L("action_move_group"), symbol: "folder", submenu: others.map { g in
            MenuItem(title: g.name, symbol: "folder") { model.moveSubscription(sub.id, to: g.id) }
        }))
    }
    items.append(MenuItem(title: L("delete"), symbol: "trash", danger: true) {
        // The dialog names what goes: the subscription and all its servers (Android's wording).
        confirmDelete(menus, L("delete_sub_q"), detail: L("delete_sub_text", sub.name)) {
            model.removeSubscription(sub.id)
        }
    })
    return items
}

/// A name typed in a small popup (a new group, a group's new name); Return or the button takes it.
struct NameField: View {
    var title: String
    var initial: String
    var done: (String) -> Void
    @State private var name = ""
    @Environment(\.snapshot) private var snapshot

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title).font(Fonts.body(13, bold: true)).foregroundStyle(Palette.text).textSelection(.enabled)
            PlainField(placeholder: L("settings_name"), text: $name, snapshot: snapshot, onCommit: take).modifier(FieldBezel())
            HStack {
                Spacer()
                MacButton(title: L("save"), accent: true) { take() }.disabled(name.trimmingCharacters(in: .whitespaces).isEmpty)
            }
        }
        .padding(Dims.popupPad)
        .frame(width: 240)
        .onAppear { name = initial }
    }

    private func take() {
        let n = name.trimmingCharacters(in: .whitespaces)
        if !n.isEmpty { done(n) }
    }
}

// MARK: The inspector

/// macOS gives the first text field of a window the focus, so opening a server selected its name. Nothing is focused
/// until the user clicks a field.
@MainActor
private func dropFocus() {
    DispatchQueue.main.async { NSApp.keyWindow?.makeFirstResponder(nil) }
}

private struct Inspector: View {
    @Bindable var model: MainModel

    var body: some View {
        if let s = model.servers.first(where: { $0.id == model.selectedId }) {
            ServerInspector(model: model, server: s).id(s.id)
        } else if let sub = model.subscriptions.first(where: { $0.id == model.selectedId }) {
            SubscriptionInspector(model: model, sub: sub).id(sub.id)
        } else {
            Text(String(localized: "groups.pick")).font(Fonts.body(12)).foregroundStyle(Palette.textSecondary)
                .multilineTextAlignment(.center).textSelection(.enabled)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }
}

/// A heading over a block of the inspector, with an optional live figure on the right (the streams open).
private struct InspectorHeading: View {
    var title: String
    var trailing: String? = nil

    var body: some View {
        HStack {
            Text(title).font(Fonts.body(12, bold: true)).foregroundStyle(Palette.textSecondary).textSelection(.enabled)
            Spacer()
            if let trailing { Text(trailing).font(Fonts.body(11)).foregroundStyle(Palette.accentStrong).textSelection(.enabled) }
        }
        .padding(.top, 6)
        .padding(.leading, 2)
    }
}

/// A grey line under a block (one short sentence, the grey-text rule).
private struct InspectorNote: View {
    var text: String
    var body: some View {
        Text(text).font(Fonts.body(11)).foregroundStyle(Palette.textSecondary)
            .fixedSize(horizontal: false, vertical: true).padding(.horizontal, 2).textSelection(.enabled)
    }
}

/// The selected server: figures, then its settings edited in place (Android's "Server settings" dialog: name, country
/// from a list, and for a limited-network server the streams and the device id — saved when a field is committed or the
/// selection moves, only what changed), then its actions.
private struct ServerInspector: View {
    @Bindable var model: MainModel
    var server: ServerItem
    @Environment(\.snapshot) private var snapshot
    @Environment(MenuState.self) private var menus

    @State private var name = ""
    @State private var auto = true
    @State private var streams = 4
    @State private var ignoreLimit = false
    @State private var device = ""
    @State private var loaded = false

    var body: some View {
        let content = VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 10) {
                FlagView(code: server.country, height: 20)
                VStack(alignment: .leading, spacing: 1) {
                    Text(model.displayName(server)).font(Fonts.display(17, bold: true)).foregroundStyle(Palette.text).lineLimit(1).textSelection(.enabled)
                    Text([server.country.map { shortCountryName($0) } ?? "", model.groupName(server.groupId)].filter { !$0.isEmpty }.joined(separator: " · "))
                        .font(Fonts.body(12)).foregroundStyle(Palette.textSecondary).lineLimit(1).textSelection(.enabled)
                }
            }
            .padding(.bottom, 2)
            FormBox {
                FormRow(label: L("info_ping")) {
                    HStack(spacing: 6) {
                        if server.ms != nil { PingDot(ms: server.ms) }
                        Text(model.pendingIds.contains(server.id) ? L("action_probing") : pingText(server))
                            .font(Fonts.display(13)).textSelection(.enabled)
                    }
                }
                FormRow(label: L("info_protocol")) { Text(server.proto).lineLimit(1).textSelection(.enabled) }
            }

            InspectorHeading(title: L("server_settings"))
            FormBox {
                FormField(label: L("settings_name"), text: $name, placeholder: linkName, onCommit: saveName)
                FormRow(label: L("settings_country")) {
                    // The list opens under the value: automatic first, then every country.
                    PickerButton(spacing: 6, pad: 8, chevron: .upDown) {
                        if server.countryManual { FlagView(code: server.country, height: 11) }
                        Text(server.countryManual ? (server.country.map { shortCountryName($0) } ?? L("country_auto")) : L("country_auto"))
                            .foregroundStyle(server.countryManual ? Palette.text : Palette.textSecondary).lineLimit(1)
                    }
                    .appPopupOnClick(menus) {
                        AnyView(CountryPicker(manual: server.countryManual ? server.country : nil) { code in
                            menus.close()
                            model.setCountry(server: server.id, code, manual: code != nil)
                        })
                    }
                }
            }

            if server.limited { limitedSection }

            FlowCells(spacing: 6) {
                MacButton(title: L("connect"), symbol: "power", accent: true) { saveName(); model.connect(to: server) }
                if model.backend != nil {
                    ServerProbeButton(model: model, serverId: server.id)
                }
                MacButton(title: L("action_stats"), symbol: "chart.xyaxis.line") { model.openStats(.server(server.id)) }
                MacButton(symbol: "square.and.arrow.up", help: L("share")) {
                    menus.show(dialog: AnyView(ShareDialog(model: model, scope: .server(server.id))))
                }
            }
            .padding(.top, 6)
        }
        .padding(16)
        .onAppear {
            load()
            dropFocus()
        }
        .onDisappear {
            saveName()
            saveLimited()
        }

        SnapshotScroll { content }.scrollIndicators(.automatic)
    }

    @ViewBuilder private var limitedSection: some View {
        InspectorHeading(title: L("limited_streams"))
        InspectorNote(text: L("limited_about_short"))
        FormBox {
            FormRow(label: L("limited_auto")) {
                AppSwitch(isOn: $auto)
                    .onChange(of: auto) { saveLimited() }
            }
            if !auto {
                FormRow(label: L("limited_n_streams", streams)) {
                    HStack(spacing: 4) {
                        MacButton(symbol: "minus") { if streams > 1 { streams -= 1; saveLimited() } }
                        MacButton(symbol: "plus") { if streams < maxManualStreams { streams += 1; saveLimited() } }
                    }
                }
            }
            if let max = server.limitedMax {
                FormRow(label: L("limited_over_limit", max)) {
                    AppSwitch(isOn: $ignoreLimit)
                        .onChange(of: ignoreLimit) { saveLimited() }
                }
            }
        }
        InspectorNote(text: L(auto ? "limited_auto_short" : "limited_manual_short"))
        if server.limitedMax != nil { InspectorNote(text: L("limited_over_limit_note")) }

        InspectorHeading(title: L("limited_device_title"))
        PlainField(placeholder: "", text: $device, snapshot: snapshot, onCommit: saveLimited).modifier(FieldBezel())
        if device.trimmingCharacters(in: .whitespaces).isEmpty {
            // What is sent while the field is empty: the link's own device id if it carries one,
            // else the service's install id (Android `Limited.effectiveDevice`).
            let sent = SharedCode.limitedDevice(server.link)
            Text(L("limited_device_now", sent.isEmpty ? model.defaultDeviceId : sent))
                .font(Fonts.body(11)).foregroundStyle(Palette.textSecondary)
                .textSelection(.enabled).padding(.horizontal, 2)
        }
        InspectorNote(text: L("limited_device_short"))
    }

    private var linkName: String { server.linkName.isEmpty ? server.name : server.linkName }

    private func load() {
        guard !loaded else { return }
        loaded = true
        name = server.name
        auto = server.limitedStreams == 0
        streams = server.limitedStreams > 0 ? server.limitedStreams : 4
        ignoreLimit = server.limitedIgnoreLimit
        device = server.limitedDevice
    }

    /// The name, saved on its own: only a field commit, a click on Connect, the selection moving or the
    /// inspector leaving takes it — the limited controls write their values on every change and must
    /// not drag a half-typed name along (they used to share one `save`).
    private func saveName() {
        guard loaded, let now = model.servers.first(where: { $0.id == server.id }),
              name.trimmingCharacters(in: .whitespaces) != now.name else { return }
        model.rename(server: server.id, to: name)
    }

    /// The limited-network fields, saved on every change (a switch, a step of the stepper, the device
    /// id's commit). Only what changed is written (opening and leaving changes nothing).
    private func saveLimited() {
        guard loaded, let now = model.servers.first(where: { $0.id == server.id }), now.limited else { return }
        let n = auto ? 0 : streams
        if n != now.limitedStreams || ignoreLimit != now.limitedIgnoreLimit || device.trimmingCharacters(in: .whitespaces) != now.limitedDevice {
            model.setLimited(server: server.id, streams: n, ignoreLimit: ignoreLimit, device: device)
        }
    }
}

/// The selected subscription: its lines and traffic, then its settings (the name, as on Android), then its actions.
private struct SubscriptionInspector: View {
    @Bindable var model: MainModel
    var sub: SubscriptionItem
    @Environment(MenuState.self) private var menus
    @State private var name = ""
    @State private var loaded = false

    var body: some View {
        let content = VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 10) {
                Image(systemName: "dot.radiowaves.up.forward").font(.system(size: 15, weight: .semibold)).foregroundStyle(Palette.accent)
                    .frame(width: 30, height: 20)
                VStack(alignment: .leading, spacing: 1) {
                    Text(sub.name).font(Fonts.display(17, bold: true)).foregroundStyle(Palette.text).lineLimit(1).textSelection(.enabled)
                    Text(model.groupName(sub.groupId)).font(Fonts.body(12)).foregroundStyle(Palette.textSecondary).textSelection(.enabled)
                }
            }
            .padding(.bottom, 2)
            // One fact per row (the outline row writes them as lines joined by "·").
            FormBox {
                FormRow(label: L("subst_server_count")) {
                    Text("\(model.servers.filter { $0.subscriptionId == sub.id }.count)").textSelection(.enabled)
                }
                FormRow(label: L("subst_updated")) {
                    VStack(alignment: .trailing, spacing: 1) {
                        Text(sub.updatedAt.map(relativeText) ?? L("sub_never")).textSelection(.enabled)
                        if sub.viaServer { Text(L("sub_via_server")).font(Fonts.body(11)).foregroundStyle(Palette.textSecondary).textSelection(.enabled) }
                    }
                    .fixedSize()
                }
                FormRow(label: L("subst_provider")) {
                    VStack(alignment: .trailing, spacing: 1) {
                        if let used = sub.usedBytes {
                            Text(sub.totalBytes.map { L("traffic_of", bytesText(used), bytesText($0)) } ?? L("traffic_used", bytesText(used))).textSelection(.enabled)
                        } else {
                            Text(L("subst_provider_silent")).foregroundStyle(Palette.textSecondary).textSelection(.enabled)
                        }
                        if let expire = sub.expire {
                            Text(L("until", dateText(expire))).font(Fonts.body(11)).foregroundStyle(Palette.textSecondary).textSelection(.enabled)
                        }
                    }
                    .multilineTextAlignment(.trailing)
                    .fixedSize()
                }
                FormRow(label: scheduleText(model, sub)) { EmptyView() }
            }
            if let used = sub.usedBytes, let total = sub.totalBytes, total > 0 {
                UsageBar(share: Double(used) / Double(total)).padding(.horizontal, 2)
            }

            if let error = sub.lastError { ExpandableError(message: L("sub_error", error)) }

            InspectorHeading(title: L("subscription_settings"))
            FormBox {
                FormField(label: L("settings_name"), text: $name, placeholder: sub.providerName.isEmpty ? sub.name : sub.providerName, onCommit: save)
            }

            FlowCells(spacing: 6) {
                MacButton(title: L("update"), symbol: "arrow.clockwise", accent: true) {
                    model.backend?.runAction("update_sub:\(sub.id)")
                }
                MacButton(title: L("action_stats"), symbol: "chart.xyaxis.line") { model.openStats(.subscription(sub.id)) }
                MacButton(symbol: "square.and.arrow.up", help: L("share")) {
                    menus.show(dialog: AnyView(ShareDialog(model: model, scope: .subscription(sub.id))))
                }
            }
            .padding(.top, 6)
            Spacer(minLength: 0)
        }
        .padding(16)
        .onAppear {
            dropFocus()
            guard !loaded else { return }
            loaded = true
            name = sub.name
        }
        .onDisappear(perform: save)

        SnapshotScroll { content }.scrollIndicators(.automatic)
    }

    private func save() {
        guard loaded, let now = model.subscriptions.first(where: { $0.id == sub.id }),
              name.trimmingCharacters(in: .whitespaces) != now.name else { return }
        model.rename(subscription: sub.id, to: name)
    }
}
