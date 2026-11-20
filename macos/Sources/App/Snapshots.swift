import AppKit
import SwiftUI

/// `BORDERLESS_SNAPSHOT=<dir>` renders the app's pages into PNGs (light and dark) and quits — for checking the UI
/// without a screen recording permission and, later, for the README's screenshots.
@MainActor
enum Snapshots {
    static func runIfAsked() {
        guard let dir = ProcessInfo.processInfo.environment["BORDERLESS_SNAPSHOT"] else { return }
        // The dir is made (not assumed to exist): with it missing every write below failed silently.
        do {
            try FileManager.default.createDirectory(atPath: dir, withIntermediateDirectories: true)
        } catch {
            NSLog("Border(less): the snapshot dir \(dir) can't be created: \(error)")
            exit(1)
        }
        for dark in [false, true] {
            let suffix = dark ? "-dark" : ""
            render(ServiceSetupView(update: false) {}, dark: dark, to: "\(dir)/setup\(suffix).png")
            render(ServiceSetupView(update: true) {}, dark: dark, to: "\(dir)/setup-update\(suffix).png")
            render(Shell(model: MainModel.demo()), dark: dark, to: "\(dir)/main\(suffix).png")
            let bar = AppModel(demo: true)
            render(MenuBarPanel(app: bar), dark: dark, to: "\(dir)/menubar\(suffix).png", size: nil)
            render(MenuBarPanel(app: bar, choosing: .server), dark: dark, to: "\(dir)/menubar-servers\(suffix).png", size: nil)
            let off = MainModel.demo()
            off.phase = .off
            render(Shell(model: off), dark: dark, to: "\(dir)/main-off\(suffix).png")
            let scan = MainModel.demo()
            scan.phase = .searching
            scan.scan = ScanProgress(done: 7, total: scan.servers.count, probing: ["s9", "s10", "s11"])
            render(Shell(model: scan), dark: dark, to: "\(dir)/main-scan\(suffix).png")
            let peek = MainModel.demo()
            peek.pickedId = "s1"
            peek.eco = true
            render(Shell(model: peek), dark: dark, to: "\(dir)/main-peek\(suffix).png")
            let named = MainModel.demo()
            named.settings.genericNames = true
            named.pickedId = "s1"
            if let i = named.servers.firstIndex(where: { $0.id == "s1" }) {
                named.servers[i].host = "203.0.113.18"
                named.servers[i].port = 443
                named.servers[i].linkName = "Amsterdam, Netherlands, Extra"
            }
            render(Shell(model: named), dark: dark, to: "\(dir)/main-generic-peek\(suffix).png")
            let m1 = MainModel.demo()
            let serverMenuState = MenuState()
            // The demo list decides how many servers there are — index it only when it's long enough.
            if m1.servers.count > 1 {
                serverMenuState.show(serverMenu(m1, m1.servers[1], serverMenuState), at: CGPoint(x: 700, y: 360))
            }
            render(Shell(model: m1, menus: serverMenuState), dark: dark, to: "\(dir)/menu-server\(suffix).png")
            let m2 = MainModel.demo()
            let strategyMenu = MenuState()
            strategyMenu.show(Strategy.allCases.map { s in MenuItem(title: s.title, sub: s.description, selected: s == m2.strategy) }, at: CGPoint(x: 322, y: 452))
            render(Shell(model: m2, menus: strategyMenu), dark: dark, to: "\(dir)/menu-strategy\(suffix).png")
            let g1 = MainModel.demo()
            g1.selectedId = "s1"
            g1.folded = ["work"]
            if g1.servers.count > 3 { g1.servers[3].disabled = true }
            render(Shell(model: g1, section: .groups), dark: dark, to: "\(dir)/groups\(suffix).png")
            let g2 = MainModel.demo()
            g2.selectedId = "demo-sub"
            g2.folded = ["own", "work"]
            render(Shell(model: g2, section: .groups), dark: dark, to: "\(dir)/groups-sub\(suffix).png")
            let g3 = MainModel.demo()
            if g3.servers.count > 5 {
                g3.servers[5].limited = true
                g3.servers[5].proto = "WDTT · WireGuard"
                g3.servers[5].limitedMax = 6
                g3.servers[5].limitedStreams = 3
                g3.selectedId = g3.servers[5].id
            }
            g3.folded = ["own", "sub"]
            render(Shell(model: g3, section: .groups), dark: dark, to: "\(dir)/groups-limited\(suffix).png")
            let g4 = MainModel.demo()
            g4.selectedId = "s1"
            if g4.servers.count > 1 { g4.servers[1].countryManual = true }
            let countryMenu = MenuState()
            countryMenu.show(popup: AnyView(CountryPicker(manual: "NL") { _ in }), at: CGPoint(x: 820, y: 262))
            render(Shell(model: g4, section: .groups, menus: countryMenu), dark: dark, to: "\(dir)/groups-country\(suffix).png")
            render(Shell(model: MainModel.demo(), section: .stats), dark: dark, to: "\(dir)/stats\(suffix).png")
            for (name, target) in [("server", StatsTarget.server("s0")), ("sub", .subscription("demo-sub"))] {
                let m = MainModel.demo()
                m.statsTarget = target
                render(Shell(model: m, section: .stats), dark: dark, to: "\(dir)/stats-\(name)\(suffix).png")
            }
            let a1 = MainModel.demo()
            let add = MenuState()
            let text = "vless://11111111-2222-3333-4444-555555555555@new.example.com:443?security=tls#New\ntuic://example.com:443\nhello"
            add.show(dialog: AnyView(AddDialog(model: a1, text: text, result: a1.importText(text, into: nil))))
            render(Shell(model: a1, menus: add), dark: dark, to: "\(dir)/add\(suffix).png")
            for (name, keep, qr) in [("plain", false, false), ("keep", true, false), ("qr", false, true)] {
                let m = MainModel.demo()
                let share = MenuState()
                share.show(dialog: AnyView(ShareDialog(model: m, scope: .group("own"), keep: keep, qr: qr)))
                render(Shell(model: m, section: .groups, menus: share), dark: dark, to: "\(dir)/share-\(name)\(suffix).png")
            }
            let c1 = MainModel.demo()
            c1.countries = ["DE"]
            let countries = MenuState()
            countries.show(popup: AnyView(CountriesPopup(model: c1)), at: CGPoint(x: 375, y: 622), placement: .trailing)
            render(Shell(model: c1, menus: countries), dark: dark, to: "\(dir)/countries\(suffix).png")
            let z1 = MainModel.demo()
            z1.settingsPage = .routing
            z1.zones = [CustomZone(id: "u-1", name: "Work sites", direct: ["intranet.example.com", "203.0.113.0/24"],
                                   server: ["keyword:example"])]
            let zone = MenuState()
            zone.show(dialog: AnyView(ZoneDialog(model: z1, id: "u-1")))
            render(Shell(model: z1, section: .settings, menus: zone), dark: dark, to: "\(dir)/zone\(suffix).png")
            let r1 = MainModel.demo()
            let range = MenuState()
            range.show(dialog: AnyView(RangeDialog(from: Date().addingTimeInterval(-30 * 3600), to: Date()) { _ in }))
            render(Shell(model: r1, section: .stats, menus: range), dark: dark, to: "\(dir)/range\(suffix).png")
            let st = MainModel.demo()
            st.statsTab = 1
            render(Shell(model: st, section: .stats), dark: dark, to: "\(dir)/stats-activity\(suffix).png")
            render(Shell(model: MainModel.demo(), section: .log), dark: dark, to: "\(dir)/log\(suffix).png")
            let l2 = MainModel.demo()
            l2.logTab = 1
            render(Shell(model: l2, section: .log), dark: dark, to: "\(dir)/log-detailed\(suffix).png")
            for page in SettingsPage.allCases {
                let m = MainModel.demo()
                m.settingsPage = page
                render(Shell(model: m, section: .settings), dark: dark, to: "\(dir)/settings-\(page)\(suffix).png")
            }
            let sq = MainModel.demo()
            sq.settingsQuery = "dns"
            render(Shell(model: sq, section: .settings), dark: dark, to: "\(dir)/settings-search\(suffix).png")
        }
        exit(0)
    }

    private static func render(_ view: some View, dark: Bool, to path: String, size: CGSize? = CGSize(width: 980, height: 640)) {
        let appearance = NSAppearance(named: dark ? .darkAqua : .aqua)
        let content = view
            .frame(width: size?.width, height: size?.height)
            .environment(\.colorScheme, dark ? .dark : .light)
            .environment(\.snapshot, true)
        let renderer = ImageRenderer(content: content)
        renderer.scale = 2
        var image: CGImage?
        appearance?.performAsCurrentDrawingAppearance { image = renderer.cgImage }
        guard let image else {
            NSLog("Border(less): the snapshot render of \(path) gave no image")
            return
        }
        let rep = NSBitmapImageRep(cgImage: image)
        guard let data = rep.representation(using: .png, properties: [:]) else {
            NSLog("Border(less): the snapshot of \(path) gave no PNG data")
            return
        }
        do {
            try data.write(to: URL(fileURLWithPath: path))
        } catch {
            NSLog("Border(less): the snapshot \(path) couldn't be written: \(error)")
        }
    }
}
