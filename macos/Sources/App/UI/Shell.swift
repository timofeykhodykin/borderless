import Shared
import SwiftUI

/// The window's sections: Android's screens, reached from a sidebar instead of the top bar's buttons.
enum Section: String, CaseIterable, Identifiable {
    case main, groups, stats, log, settings
    var id: Self { self }

    var title: String {
        switch self {
        case .main: String(localized: "nav.main")
        case .groups: L("groups")
        case .stats: L("stats")
        case .log: L("log_title")
        case .settings: L("settings")
        }
    }

    var symbol: String {
        switch self {
        case .main: "power"
        case .groups: "folder"
        case .stats: "chart.xyaxis.line"
        case .log: "list.bullet.rectangle"
        case .settings: "gearshape"
        }
    }
}

/// The window once the service runs: sidebar (title, the connection in one line, sections, "Add") + the section.
struct Shell: View {
    @Bindable var model: MainModel
    @State var menus: MenuState
    /// The section to open on — the window-shot and snapshot modes pick one; nil keeps the model's.
    /// Applied on appear, not in `init` (a view's constructor must not write the model); the shots
    /// wait long enough to draw it.
    private let initialSection: Section?
    /// `engine.update` for the banner over the content (nil = nothing to tell about).
    private let update: EngineStatus.UpdateStatus?
    /// The `.unreachable` service's repair offer under the sidebar's error line (nil = the service
    /// answers, or nothing can be done about it — the setup page owns the other states).
    private let repair: (() -> Void)?
    private let repairing: Bool

    init(model: MainModel, section: Section? = nil, menus: MenuState = MenuState(),
         update: EngineStatus.UpdateStatus? = nil, repair: (() -> Void)? = nil, repairing: Bool = false) {
        self.model = model
        initialSection = section
        self.update = update
        self.repair = repair
        self.repairing = repairing
        _menus = State(initialValue: menus)
    }

    var body: some View {
        ZStack(alignment: .topLeading) {
            content
            MenuLayer(menus: menus)
        }
        .coordinateSpace(name: "shell")
        .environment(menus)
        .onAppear {
            if let initialSection { model.section = initialSection }
        }
    }

    private var content: some View {
        ZStack(alignment: .top) {
            // The strip the window is dragged by: the header height across the whole top. The
            // sidebar's title row reaches deeper and carries a surface of its own below.
            WindowDrag(menus: menus).frame(height: Dims.headerH).frame(maxWidth: .infinity)
            HStack(spacing: 0) {
                Sidebar(model: model, section: $model.section, repair: repair, repairing: repairing)
                    .frame(width: Dims.sidebarW)
                Hairline(vertical: true)
                Group {
                    switch model.section {
                    case .main: MainView(model: model)
                    case .groups: GroupsView(model: model)
                    case .stats: StatsView(model: model)
                    case .log: LogView(model: model)
                    case .settings: SettingsView(model: model)
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
            .background(Palette.background)
            // The updater's word over the content area (nil, or an idle state, draws nothing at all).
            if let update, update.interesting {
                UpdateBanner(update: update, model: model)
                    .padding(.top, 8)
                    // Centred over the content, not the whole window: the sidebar is the left strip.
                    .padding(.leading, Dims.sidebarW)
                    .frame(maxWidth: .infinity)
                    .transition(.move(edge: .top).combined(with: .opacity))
            }
        }
        .animation(.easeInOut(duration: 0.2), value: update != nil && update?.interesting == true)
    }
}

private struct Sidebar: View {
    var model: MainModel
    @Binding var section: Section
    /// The dead-daemon repair path (nil = the service answers): a row under the "didn't answer" line.
    var repair: (() -> Void)?
    var repairing = false
    @State private var errorExpanded = false
    @Environment(MenuState.self) private var menus

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            AppTitle(size: 20)
                .padding(.top, 38) // below the window's traffic lights
                .padding(.horizontal, 18)
                .frame(maxWidth: .infinity, alignment: .leading)
                // The window's drag strip ends at the header height; the title's row is deeper
                // (its lower third sits below the strip) and drags by a surface of its own.
                .background { WindowDrag(menus: menus) }
            HStack(spacing: 6) {
                Circle().fill(model.stateDot).frame(width: 7, height: 7)
                Text(model.stateText).font(Fonts.body(12)).foregroundStyle(Palette.textSecondary).lineLimit(1).textSelection(.enabled)
            }
            .padding(.horizontal, 18)
            .padding(.top, 5)
            // Leave room between the status, a multiline warning and the navigation below it.
            if let error = model.commandError ?? model.engineError {
                VStack(alignment: .leading, spacing: 4) {
                    Button { errorExpanded.toggle() } label: {
                        HStack(alignment: .top, spacing: 4) {
                            Image(systemName: errorExpanded ? "chevron.down" : "chevron.right")
                            Text(errorExpanded ? L("err_title") : error).lineLimit(2)
                        }
                    }
                    .buttonStyle(.plain)
                    if errorExpanded {
                        Text(error).textSelection(.enabled)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                .font(Fonts.body(11)).foregroundStyle(Palette.danger)
                .lineSpacing(3)
                .padding(.horizontal, 18).padding(.top, 8).padding(.bottom, 8)
                .onChange(of: error) { errorExpanded = false }
            }
            if let repair {
                // ".unreachable": the service is installed but quiet — reinstalling is the only way
                // back (agent-tasks 2026-11-09). Confirmed first: the system's administrator prompt
                // follows, and it must not drop on the user unannounced.
                Button { confirmRepair() } label: {
                    HStack(spacing: 5) {
                        if repairing { ProgressView().controlSize(.mini) }
                        Text(macText("mac.service.repair", "Reinstall the service"))
                    }
                    .font(Fonts.body(11, bold: true)).foregroundStyle(Palette.accentStrong)
                }
                .buttonStyle(.plain)
                .disabled(repairing)
                .padding(.horizontal, 18)
                .padding(.top, 3)
            }
            if model.backend == nil {
                Text("nav.demo").font(Fonts.body(11)).foregroundStyle(Palette.textMuted).textSelection(.enabled)
                    .padding(.horizontal, 18).padding(.top, 1).padding(.bottom, 14)
            }
            VStack(spacing: 1) {
                ForEach(Section.allCases) { s in
                    NavItem(section: s, selected: s == section) {
                        // A tap that reached here with a menu/popup open leaked through the card's dead
                        // areas (the scrim lets the whole card rect through) — it's an outside click:
                        // dismiss, don't navigate (owner, 2026-11-09).
                        if menus.open { menus.close(); return }
                        // The sidebar's Statistics is the overview (a server's page opens from its menus).
                        if s == .stats, model.statsTarget != nil { model.statsTarget = nil }
                        // Re-clicking the open section is a no-op: don't write through SwiftUI's
                        // navigation binding or disturb the existing settings page and its focus.
                        guard section != s else { return }
                        section = s
                    }
                }
            }
            .padding(.horizontal, 10)
            .padding(.top, 16)
            Spacer()
            Button { menus.show(dialog: AnyView(AddDialog(model: model))) } label: {
                HStack(spacing: 5) {
                    Image(systemName: "plus").font(.system(size: 11, weight: .semibold)).foregroundStyle(Palette.textSecondary)
                    Text(L("add"))
                }
                .frame(maxWidth: .infinity)
            }
            .buttonStyle(MacButtonStyle(height: 28))
            .padding(12)
        }
        .frame(maxHeight: .infinity, alignment: .top)
        .background(Palette.card.opacity(0.7))
    }

    /// The confirm before the reinstall's administrator prompt — like the panel's quiet-reset alert,
    /// but in the window's own dialog layer the sidebar has.
    private func confirmRepair() {
        menus.show(dialog: AnyView(
            VStack(alignment: .leading, spacing: 8) {
                Text(macText("mac.service.repair.q", "Install the service again?"))
                    .font(Fonts.body(13, bold: true)).foregroundStyle(Palette.text).textSelection(.enabled)
                Text(macText("mac.service.repair.sub", "The installed service isn't answering. macOS will ask for the administrator password, and the network settings it changed are put back first."))
                    .font(Fonts.body(11)).foregroundStyle(Palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true).textSelection(.enabled)
                HStack(spacing: 6) {
                    Spacer()
                    MacButton(title: L("cancel")) { menus.closeDialog() }.keyboardShortcut(.cancelAction)
                    MacButton(title: macText("mac.service.repair", "Reinstall the service"), accent: true) {
                        menus.closeDialog()
                        repair?()
                    }.keyboardShortcut(.defaultAction)
                }
                .padding(.top, 4)
            }
            .padding(18)
            .frame(width: 320)
        ))
    }
}

private struct NavItem: View {
    var section: Section
    var selected: Bool
    var action: () -> Void
    @State private var hover = false
    @Environment(MenuState.self) private var menus

    var body: some View {
        Button { NavigationProbe.trace(section, selected: selected, action: action) } label: {
            HStack(spacing: 8) {
                Image(systemName: section.symbol).font(.system(size: 13, weight: .regular)).frame(width: 18)
                    .foregroundStyle(selected ? Palette.accentStrong : Palette.textSecondary)
                Text(section.title).font(Fonts.body(13, bold: selected))
                    .foregroundStyle(selected ? Palette.accentStrong : Palette.text)
                Spacer()
            }
            .padding(.horizontal, 8)
            .frame(height: 28)
            .background(RoundedRectangle(cornerRadius: 6).fill(selected ? Palette.selected : hover ? Palette.cardStrong.opacity(0.6) : .clear))
            .contentShape(RoundedRectangle(cornerRadius: 6))
            // With a menu or popup open the first click only dismisses it — the row is not
            // interactive then, so it doesn't get a hover look either (owner, 2026-11-09).
            .onHover { hover = menus.open ? false : $0 }
        }
        .buttonStyle(.plain)
    }
}
