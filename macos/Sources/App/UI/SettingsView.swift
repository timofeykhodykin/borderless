import ServiceManagement
import Shared
import SwiftUI

/// The settings (Android `SettingsScreen.kt`): the same pages, sections, rows and words, laid out for a wide window —
/// the pages listed on the left with their one-line summaries, the open page on the right as grouped forms, a search
/// over every row in the bar. The pages are data (`SettingsPage.sections`), drawn by one view, so the search is a
/// filter and every page looks alike.
struct SettingsView: View {
    @Bindable var model: MainModel
    @Environment(MenuState.self) private var menus
    @Environment(\.snapshot) private var snapshot

    var body: some View {
        VStack(spacing: 0) {
            ScreenHeader(title: L("settings")) {
                SearchField(text: $model.settingsQuery, placeholder: L("set_search")).frame(width: 200)
            }
            HStack(spacing: 0) {
                PageList(model: model, page: $model.settingsPage, searching: !model.settingsQuery.isEmpty) { model.settingsQuery = "" }
                    .frame(width: 236)
                Hairline(vertical: true)
                Group {
                    if snapshot {
                        // Measured at its width first, or ImageRenderer lets wrapped lines spill into the next row.
                        GeometryReader { g in content.frame(width: g.size.width, alignment: .top).fixedSize(horizontal: false, vertical: true) }
                            .clipped()
                    } else {
                        ScrollView { content }.scrollIndicators(.automatic)
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .background(Palette.background)
        // Esc clears an active search (Android's Back/✕): an open menu or the zone dialog swallows
        // it on their own layer instead.
        .onExitCommand {
            guard menus.dialog == nil, !menus.open else { return }
            if !model.settingsQuery.isEmpty { model.settingsQuery = "" }
        }
        // A zone opens as a dialog over the settings (a new one, one loaded from a file, or a click on its row).
        .onChange(of: model.openZone) { _, id in
            if let id {
                let notes = zoneLoadNotes.removeValue(forKey: id) ?? ZoneLoadNotes()
                menus.show(dialog: AnyView(ZoneDialog(model: model, id: id,
                                                      loadSkipped: notes.skipped, loadTruncated: notes.truncated)))
            }
        }
        .onChange(of: menus.dialog == nil) { _, closed in if closed { model.openZone = nil } }
        // The core's version is asked of the bundled `borderless-core` itself — seconds the first time,
        // so the asking waits for the About page to open; `sections()` is a data builder (a settings
        // search builds every page's rows) and must not carry the side effect.
        .task(id: model.settingsPage) {
            if model.settingsPage == .about { model.fetchCoreVersion() }
        }
    }

    @ViewBuilder private var content: some View {
        let q = SettingsQuery(model.settingsQuery)
        let page = model.settingsPage
        VStack(alignment: .leading, spacing: 14) {
            if let q {
                let found = SettingsPage.allCases.compactMap { p -> (SettingsPage, [SettingsSection])? in
                    let sections = p.sections(model, menus: menus).compactMap { $0.filtered(q, page: p.title) }
                    return sections.isEmpty ? nil : (p, sections)
                }
                if found.isEmpty {
                    Text(L("set_search_none")).font(Fonts.body(13)).foregroundStyle(Palette.textSecondary)
                        .textSelection(.enabled).frame(maxWidth: .infinity).padding(.top, 40)
                }
                ForEach(found, id: \.0) { p, sections in
                    // A heading per page, which opens it.
                    Text(p.title).font(Fonts.display(16, bold: true)).foregroundStyle(Palette.text)
                        .onTapGesture { model.settingsQuery = ""; model.settingsPage = p }
                    ForEach(sections) { SectionView(section: $0, query: q) }
                }
            } else {
                Text(page.title).font(Fonts.display(17, bold: true)).foregroundStyle(Palette.text).textSelection(.enabled)
                if page == .support { SupportView(model: model) }
                ForEach(page.sections(model, menus: menus)) { SectionView(section: $0) }
            }
        }
        .padding(.horizontal, 20)
        .padding(.top, 16)
        .padding(.bottom, 24)
        .padding(.trailing, 6)
    }
}

// MARK: Pages

enum SettingsPage: CaseIterable, Identifiable {
    case server, checks, eco, routing, subs, connection, interface, diagnostics, support, about
    var id: Self { self }

    var title: String {
        switch self {
        case .server: L("set_cat_server")
        case .checks: L("set_cat_checks")
        case .eco: L("eco_title")
        case .routing: L("sec_routing")
        case .subs: L("subs_title")
        case .connection: L("sec_connection")
        case .interface: L("set_cat_interface")
        case .diagnostics: L("set_cat_diagnostics")
        case .support: L("sup_title")
        case .about: L("sec_about")
        }
    }

    var symbol: String {
        switch self {
        case .server: "server.rack"
        case .checks: "bolt.horizontal"
        case .eco: "leaf"
        case .routing: "arrow.triangle.branch"
        case .subs: "dot.radiowaves.up.forward"
        case .connection: "network"
        case .interface: "paintpalette"
        case .diagnostics: "waveform.path.ecg"
        case .support: "puzzlepiece.extension"
        case .about: "info.circle"
        }
    }

    /// The line under the page's name in the list (Android's summaries on the settings root).
    @MainActor
    func summary(_ m: MainModel) -> String {
        let s = m.settings
        switch self {
        case .server: return L("set_sum_server", s.defaultStrategy.title, s.rescanIntervalMin)
        case .checks: return L("set_sum_checks", s.latencyCheckMin, s.probeTimeoutSec)
        case .eco: return L(m.eco ? "set_sum_eco_on_plain" : "set_sum_eco_off_plain")
        case .routing:
            guard s.routingEnabled else { return L("set_sum_off") }
            // The zones by what the user calls them (Android `Regions.of(…).label`): the built-in's
            // label (".ru"), a custom zone's name — the raw ids ("ru", "u-3f9a1c") never show, and an
            // id the model no longer knows (a deleted zone's leftover) drops out.
            let chosen = s.regions.sorted().compactMap { id -> String? in
                if id == builtinZone { return builtinInfo?.label ?? "." + id }
                return m.zones.first { $0.id == id }?.name
            }
            return [s.regions.contains(builtinZone) && !listsReady(m) ? L("set_sum_lists_pending") : nil,
                    L(s.routingMode == .regionDirect ? "mode_region_direct" : "mode_listed"),
                    chosen.isEmpty ? L("zones_none") : chosen.joined(separator: " ")]
                .compactMap { $0 }.joined(separator: " · ")
        case .subs:
            return s.subUpdateHours == 0 ? L("set_sum_subs_manual", m.subscriptions.count)
                : L("set_sum_subs", m.subscriptions.count, s.subUpdateHours)
        case .connection:
            let host = s.remoteDns.components(separatedBy: "://").last?.components(separatedBy: "/").first ?? s.remoteDns
            return L("set_sum_connection", host, s.mtu)
        case .interface:
            return [paletteName(s.palette), s.themeFollowsSystem ? nil : (s.darkTheme ? L("set_sum_dark") : nil),
                    languageTitle(s.language), s.genericNames ? L("set_generic_names") : nil]
                .compactMap { $0 }.joined(separator: " · ")
        case .diagnostics:
            return [m.errorsToReport > 0 ? P("set_sum_errors", m.errorsToReport) : nil, L("set_sum_stats", s.statsDays)]
                .compactMap { $0 }.joined(separator: " · ")
        case .support:
            // The protocols in use, most used first.
            let counts = SupportView.count(m.servers.filter { !$0.limited })
            return SupportView.protocols.compactMap { key, name in counts["p:" + key].map { (name, $0) } }
                .sorted { $0.1 > $1.1 }.prefix(3).map { "\($0.0) \($0.1)" }.joined(separator: " · ")
        case .about: return L("set_version") + " " + appVersion
        }
    }

    // swiftlint:disable:next function_body_length
    @MainActor
    func sections(_ m: MainModel, menus: MenuState) -> [SettingsSection] {
        func bind<T>(_ key: WritableKeyPath<SettingsState, T>) -> Binding<T> {
            Binding(get: { m.settings[keyPath: key] }, set: { m.settings[keyPath: key] = $0 })
        }
        let s = m.settings
        switch self {
        case .server:
            var choice: [SettingRow] = [
                .choice("strategy_default", L("strategy_default"), current: s.defaultStrategy.title,
                        options: Strategy.allCases.map { v in (v.title, v == s.defaultStrategy, { m.settings.defaultStrategy = v }) }),
                // Android counts the insecure servers here (`Insecurity.of`, not exposed to the Mac): the same
                // text without the "Yours: N" claim rather than a made-up 0.
                .toggle("avoid_insecure", L("set_avoid_insecure"),
                        sub: macText("mac.avoid.insecure.sub", "Servers whose traffic isn’t encrypted on the way or whose certificate isn’t checked take no part."),
                        bind(\.avoidInsecure)),
                .toggle("ping_all_best", L("set_ping_all_best"), sub: L("set_ping_all_best_sub"), bind(\.pingAllSwitches)),
            ]
            if m.servers.contains(where: \.limited) {
                choice.append(.toggle("limited_auto", L("set_limited_auto"), sub: L("set_limited_auto_sub"), bind(\.limitedAuto)))
            }
            return [
                SettingsSection(id: "choice", title: L("set_sub_choice"), rows: choice),
                SettingsSection(id: "failure", title: L("set_sub_failure"), rows: [
                    .number("fails", L("set_fails"), sub: nil, bind(\.failThreshold), 1...10, unit: nil),
                    .choice("no_server", L("set_no_server"), current: L(s.noServerAction == .direct ? "no_server_direct" : "no_server_pause"),
                            options: [(L("no_server_direct"), s.noServerAction == .direct, { m.settings.noServerAction = .direct }),
                                      (L("no_server_pause"), s.noServerAction == .pause, { m.settings.noServerAction = .pause })]),
                    .number("retry", L("set_retry"), sub: L("set_retry_sub"), bind(\.retryIntervalSec), 5...600, unit: L("unit_s")),
                ]),
                SettingsSection(id: "stable", title: L("set_sub_stable"), note: L("set_stable_weights_note"), rows: [
                    .note("stable_params", L("set_stable_params")),
                    .number("rescan", L("set_rescan"), sub: L("set_rescan_sub"), bind(\.rescanIntervalMin), 1...240, unit: L("unit_min")),
                    .number("gain_pct", L("set_gain_pct"), sub: nil, bind(\.switchGainPercent), 0...95, unit: "%"),
                    .number("gain_ms", L("set_gain_ms"), sub: L("set_gain_ms_sub"), bind(\.switchGainMs), 0...2000, unit: L("unit_ms")),
                    .number("min_stay", L("set_min_stay"), sub: L("set_min_stay_sub"), bind(\.minStayMin), 0...240, unit: L("unit_min")),
                ]),
            ]
        case .checks:
            return [
                SettingsSection(id: "current", title: L("set_sub_current"), rows: [
                    .number("health", L("set_health"), sub: L("set_health_sub"), bind(\.healthIntervalSec), 5...600, unit: L("unit_s")),
                    .number("latency", L("set_latency"),
                            sub: macText("mac.set.latency.sub", "While traffic shows the server works. Less often while the display is asleep."),
                            bind(\.latencyCheckMin), 1...60, unit: L("unit_min")),
                ]),
                SettingsSection(id: "scans", title: L("set_sub_scans"), rows: [
                    .toggle("pause_screen", L("set_pause_screen"), sub: L("set_pause_screen_sub"), bind(\.pauseScansScreenOff)),
                    .toggle("careful", L("set_careful"), sub: L("set_careful_sub"), bind(\.carefulScans)),
                    .number("timeout", L("set_timeout"), sub: nil, bind(\.probeTimeoutSec), 2...30, unit: L("unit_s")),
                    // The service reports no live concurrency (Android's `Scanner.concurrencyNow` has no Mac
                    // counterpart): the same text without the "now N" claim rather than a made-up number.
                    .number("concurrency", L("set_concurrency"),
                            sub: s.probeConcurrency == 0
                                ? macText("mac.concurrency.auto.plain", "Auto: adapts during scans to how fast this Mac answers. 0 = auto")
                                : L("set_concurrency_manual"),
                            bind(\.probeConcurrency), 0...64, unit: nil),
                    .text("test_url", L("set_test_url"), bind(\.testUrl), hint: nil),
                ]),
            ]
        case .eco:
            var rows: [SettingRow] = [
                .toggle("eco_on", L("set_eco_on"), sub: String(localized: "mac.eco.on.sub"),
                        Binding(get: { m.eco }, set: { m.setEco($0) })),
                .toggle("eco_saver", String(localized: "mac.eco.saver"), sub: String(localized: "mac.eco.saver.sub"), bind(\.ecoWithSaver)),
                .toggle("eco_strategy", L("set_eco_strategy"), sub: L("set_eco_strategy_sub", L("strategy_failover")), bind(\.ecoStrategy)),
            ]
            if let e = m.ecoEstimate {
                rows.append(.info("eco_estimate", L("set_eco_estimate"), value: L("set_eco_estimate_value", e)))
                rows.append(.note("eco_estimate_note", String(localized: "mac.eco.estimate.typical")))
            }
            return [
                SettingsSection(id: "eco", title: L("eco_title"), rows: rows),
                // Android's `set_eco_what` talks of the screen and mobile data — the Mac's engine has
                // no metered network (`Power.metered` is always false), so its own list.
                SettingsSection(id: "eco_what", title: L("set_eco_what_title"), rows: [.note("eco_what",
                    macText("mac.set.eco.what", "• the current server is checked 3× less often; a failure is still noticed at once\n• a faster server is looked for 3× less often, only with the display on and traffic flowing; a switch needs a bigger gain\n• a failover scan stops as soon as a server is chosen\n• among working servers, ones that cost less power are preferred if not much slower\n• hidden servers aren't explored; subscriptions update half as often\n• less logging and statistics writing"))]),
            ]
        case .routing:
            // Readiness as far as the app can see it: the engine reports one state for all lists
            // (`listsUpdated`/`listsError`) — per-zone readiness, a busy flag and the next retry
            // (Android's `Regions.ready` / `GeoLists.busy` / `retryAt`) never reach the Mac, so the
            // gate below is the whole-list truth and the zone row can't say "downloading…".
            let ready = listsReady(m)
            let f = RowFeedback.shared
            return [
                SettingsSection(id: "general", title: L("set_sub_general"), rows: [
                    .toggle("routing", L("routing"), sub: L("set_routing_sub"),
                            Binding(get: { m.settings.routingEnabled }, set: { m.setRouting($0) })),
                    .choice("mode", L("set_mode"), current: L(s.routingMode == .regionDirect ? "mode_region_direct" : "mode_listed"),
                            options: [(L("mode_region_direct"), s.routingMode == .regionDirect, { m.settings.routingMode = .regionDirect }),
                                      (L("mode_listed"), s.routingMode == .listedOnly, { m.settings.routingMode = .listedOnly })]),
                    // Its list comes with the geo download (the app ships none): until then the filter
                    // has nothing to go by — the note Android's `set_ads_filter_wait` adds.
                    .toggle("ads", L("set_ads_filter"),
                            sub: L("set_ads_filter_sub") + (ready ? "" : " " + L("set_ads_filter_wait")), bind(\.adFilter)),
                    .toggle("direct_first", L("set_direct_first"), sub: L("set_direct_first_sub"), bind(\.directFirst),
                            enabled: s.routingEnabled),
                ]),
                SettingsSection(id: "zones", title: L("set_zones"),
                                note: L(s.routingMode == .regionDirect ? "zones_note_direct" : "zones_note_listed"), rows: [
                    // The zone takes part only once its lists are here (some are downloaded): until
                    // then its switch is off and locked, and the row says what is going on.
                    .toggle("zone_\(builtinZone)", builtinZone, sub: zoneRowSub(m, ready: ready),
                            Binding(get: { ready && m.settings.regions.contains(builtinZone) },
                                    set: { on in if on { m.settings.regions.insert(builtinZone) } else { m.settings.regions.remove(builtinZone) } }),
                            enabled: ready),
                ]),
                SettingsSection(id: "own", title: L("zones_own"), note: L("zones_order_note"), rows: m.zones.map { z in
                    .toggleNav("zone-" + z.id, z.name, sub: zoneSummary(z),
                               Binding(get: { m.zones.first { $0.id == z.id }?.on ?? false },
                                       set: { v in if let i = m.zones.firstIndex(where: { $0.id == z.id }) { m.zones[i].on = v } })) {
                        m.openZone = z.id
                    }
                } + [
                    .nav("zone_new", L("zone_new"), sub: L("zone_new_sub")) {
                        let z = CustomZone(id: "u-\(UUID().uuidString.prefix(6))", name: L("zone_default_name", m.zones.count + 1))
                        m.zones.append(z)
                        m.openZone = z.id
                    },
                    .nav("zone_load", L("zone_load"), sub: String(localized: "mac.zone.load.sub")) { loadZone(m, menus: menus) },
                ]),
                SettingsSection(id: "geo", title: L("set_geo_title"), rows: [
                    // Busy while this row's request runs OR the service's own update is in flight —
                    // Android's GeoLists.busy covers both (`listsBusy` on the status).
                    .action("geo_update", L("set_geo_update"),
                            sub: geoSub(m, busy: f.busy.contains("geo_update") || m.lastEngine?.listsBusy == true),
                            button: L("update"), enabled: !f.busy.contains("geo_update") && m.lastEngine?.listsBusy != true) {
                        runServiceAction(m, row: "geo_update", "update_geo")
                    },
                    .number("geo_days", L("set_geo_days"),
                            sub: macText("mac.set.geo.days.sub", "Only changed lists are downloaded. Half as often in battery saving. 0 = never"),
                            bind(\.geoUpdateDays), 0...60, unit: L("unit_days")),
                ]),
                SettingsSection(id: "exceptions", title: L("set_sub_exceptions"), rows: [
                    .text("direct_sites", L("set_direct_sites"), bind(\.directDomains), hint: L("set_direct_sites_hint"), multiline: true),
                    .text("proxy_sites", L("set_proxy_sites"), bind(\.proxyDomains), hint: L("set_proxy_sites_hint"), multiline: true),
                ]),
            ]
        case .subs:
            let f = RowFeedback.shared
            var rows: [SettingRow] = []
            // With nothing to update Android shows the explanatory `subs_empty` note where the
            // row would be, not a dead Update-all (F-SETTINGS-0100).
            if m.subscriptions.isEmpty {
                rows.append(.note("subs_empty", L("subs_empty")))
            } else {
                let updating = f.busy.contains("update_all") || m.lastEngine?.subsUpdating == true
                rows.append(.action("update_all", L("update_all"),
                        sub: updating ? L("subs_updating")
                            : [L("set_subs_count", m.subscriptions.count), f.result["update_all"]].compactMap { $0 }.joined(separator: "\n"),
                        button: L("update"), enabled: !updating) {
                    runServiceAction(m, row: "update_all", "update_subs")
                })
            }
            rows += [
                .number("sub_update", L("set_sub_update"), sub: L("set_sub_update_sub"), bind(\.subUpdateHours), 0...168, unit: L("unit_h")),
                .text("ua", "User-Agent", bind(\.userAgent), hint: L("set_ua_hint")),
            ]
            if s.userAgent != SettingsState.defaultUserAgent {
                rows.append(.action("ua_reset", L("ua_reset"), sub: SettingsState.defaultUserAgent, button: L("ua_reset")) {
                    m.settings.userAgent = SettingsState.defaultUserAgent
                })
            }
            rows.append(.toggle("send_model", String(localized: "mac.send.model"), sub: String(localized: "mac.send.model.sub"), bind(\.sendDeviceModel)))
            return [SettingsSection(id: "subs", title: L("subs_title"), rows: rows)]
        case .connection:
            return [
                // The Mac's counterpart of Android's "Always on": the app starts with the session and the service
                // reconnects by itself (`TunnelController.wanted`).
                SettingsSection(id: "start", title: L("set_sub_start"), rows: [
                    .toggle("login", String(localized: "mac.login"),
                            sub: RowFeedback.shared.result["login"] ?? String(localized: "mac.login.sub"),
                            Binding(get: { SMAppService.mainApp.status == .enabled },
                                    set: { setLaunchAtLogin($0) })),
                ]),
                SettingsSection(id: "dns", title: "DNS", rows: [
                    .text("remote_dns", L("set_remote_dns"), bind(\.remoteDns), hint: nil),
                    // The "auto" the empty field stands for: the chosen zones' resolvers, not made-up numbers.
                    .text("direct_dns", L("set_direct_dns"), bind(\.directDns), hint: L("set_direct_dns_hint"),
                          placeholder: L("dns_auto", autoDns(s))),
                ]),
                SettingsSection(id: "tunnel", title: L("set_sub_tunnel"), rows: [
                    .toggle("ipv6", "IPv6", sub: L("set_ipv6_sub"), bind(\.ipv6)),
                    .toggle("fragment", L("set_fragment"), sub: L("set_fragment_sub"), bind(\.fragment)),
                    .toggle("mux", "Mux", sub: L("set_mux_sub"), bind(\.mux)),
                    // The service applies it (`currentMtu` → `Tunnel.configure`, mtu in the reconfigure
                    // key); Android has no sub here either — the row's range line is the hint.
                    .number("mtu", "MTU", sub: nil, bind(\.mtu), 1280...9000, unit: nil),
                ]),
            ]
        case .interface:
            return [
                SettingsSection(id: "palette", title: L("set_palette"), rows: [
                    .paletteChooser("palette", current: s.palette) { id in
                        m.settings.palette = id
                        PaletteStore.shared.select(id)
                    },
                    // One pick instead of Android's two switches: the system's theme, or the palette's own.
                    .choice("theme", macText("mac.theme", "Theme"),
                            current: s.themeFollowsSystem ? L("set_theme_system")
                                : s.darkTheme ? macText("mac.theme.dark", "Dark") : macText("mac.theme.light", "Light"),
                            options: [
                                (L("set_theme_system"), s.themeFollowsSystem, {
                                    m.settings.themeFollowsSystem = true
                                    applyAppearance(m.settings)
                                }),
                                (macText("mac.theme.light", "Light"), !s.themeFollowsSystem && !s.darkTheme, {
                                    m.settings.themeFollowsSystem = false
                                    m.settings.darkTheme = false
                                    applyAppearance(m.settings)
                                }),
                                (macText("mac.theme.dark", "Dark"), !s.themeFollowsSystem && s.darkTheme, {
                                    m.settings.themeFollowsSystem = false
                                    m.settings.darkTheme = true
                                    applyAppearance(m.settings)
                                }),
                            ],
                            sub: s.themeFollowsSystem ? String(localized: "mac.theme.system.sub")
                                : s.darkTheme ? L("set_dark_theme_sub") : macText("mac.theme.light.sub", "The palette's light version")),
                ]),
                SettingsSection(id: "interface", title: L("set_cat_interface"), rows: [
                    // The pick applies at once (Android's restartless switch): `applyLanguage`
                    // redirects the localized lookups and repaints — no restart note.
                    .choice("language", L("set_language"), current: languageTitle(s.language),
                            options: ["", "en", "ru"].map { tag in (languageTitle(tag), tag == s.language, { m.applyLanguage(tag) }) }),
                    .toggle("generic", L("set_generic_names"), sub: L("set_generic_names_sub"), bind(\.genericNames)),
                    // Live like on Android: it is stored and sent; the share flow (APP-IMPORT)
                    // is the consumer that asks before exporting.
                    .toggle("confirm_share", L("set_confirm_share"), sub: L("set_confirm_share_sub"), bind(\.confirmShare)),
                ] + (s.genericNames ? [.toggle("custom_over", L("set_custom_over_generic"), sub: L("set_custom_over_generic_sub"),
                                                bind(\.customOverGeneric))] : []) + [
                    // Stored and sent as on Android; the Mac's notification surface (APP-SHELL)
                    // reads it — the row itself is no longer a placeholder.
                    .choice("notify", L("set_notify"), current: notifyTitle(s.notifyMode),
                            options: NotifyMode.allCases.map { v in (notifyTitle(v), v == s.notifyMode, { m.settings.notifyMode = v }) }),
                ]),
            ]
        case .diagnostics:
            return [
                SettingsSection(id: "log", title: L("log_title"), rows: [
                    .nav("open_log", L("log_title"), sub: nil) { m.section = .log },
                    .toggle("verbose", L("set_verbose"), sub: L("set_verbose_sub"), bind(\.verboseLog)),
                    .nav("errors", L("set_errors") + " (\(m.errorsToReport))", sub: L("set_errors_sub")) {
                        menus.show(dialog: AnyView(ErrorReportsDialog()))
                    },
                    .action("clear_log", L("set_clear_log"), sub: L("set_clear_log_sub"), button: L("clear"), danger: true,
                            confirm: L("clear_log_q")) { m.clearLog() },
                ]),
                SettingsSection(id: "stats", title: L("stats"), rows: [
                    .info("stats_size", L("set_stats_size"), value: m.statsBytes.map { bytesText($0) } ?? "—"),
                    .number("stats_days", L("set_stats_days"), sub: nil, bind(\.statsDays), 1...3650, unit: L("unit_days")),
                    .action("export", L("set_export_stats"),
                            sub: RowFeedback.shared.busy.contains("export") ? macText("mac.export.working", "Exporting…")
                                : RowFeedback.shared.result["export"] ?? L("set_export_stats_sub"),
                            button: L("set_export_stats"), enabled: !RowFeedback.shared.busy.contains("export")) {
                        exportStats(m)
                    },
                    .action("clear_stats", L("clear_stats"), sub: nil, button: L("clear"), danger: true, confirm: L("clear_stats_q")) {
                        m.backend?.runAction("clear_stats")
                    },
                ]),
            ]
        case .support:
            return []
        case .about:
            // Android's `UpdateRows` ("Updates" under About): the automatic-update switch; the offer
            // as an action when the checker has a release, the check row else — over `EngineStatus.update`
            // and the `update_*` service actions. A service without an updater answers nil / "not
            // implemented" — the check row then just shows its outcome under it.
            let u = m.lastEngine?.update
            var updateRows: [SettingRow] = [
                .toggle("auto_update", L("set_auto_update"),
                        sub: macText("mac.auto.update.sub",
                            "New versions are downloaded and verified in the background; the install waits for your OK. Off: checked by hand here"),
                        bind(\.autoUpdate)),
            ]
            if let u, ["available", "ready", "downloading", "installing"].contains(u.state) {
                updateRows += [
                    .action("update_install", L("update_to", u.version ?? ""), sub: updateSub(u),
                            button: L("update_now"), enabled: u.state == "available" || u.state == "ready") {
                        runServiceAction(m, row: "update_install", "update_install")
                    },
                    // "skip the offered version for this session" (the contract's `update_dismiss`).
                    .action("update_dismiss", L("update_dismiss"), sub: nil, button: L("update_dismiss"),
                            enabled: u.state == "available" || u.state == "ready") {
                        runServiceAction(m, row: "update_dismiss", "update_dismiss")
                    },
                ]
            } else {
                let checking = u?.state == "checking" || RowFeedback.shared.busy.contains("update_check")
                updateRows.append(.action("update_check", L("update_check"),
                        sub: updateCheckSub(u, checking: checking), button: L("update_check"), enabled: !checking) {
                    runServiceAction(m, row: "update_check", "update_check")
                })
            }
            return [
                SettingsSection(id: "updates", title: L("update_channel"), rows: updateRows),
                SettingsSection(id: "about", title: L("sec_about"), rows: [
                    .info("version", L("set_version"), value: appVersion),
                    // The fetch is kicked off by the view's `.task` when the page opens; the row shows
                    // "…" until the bundled core answers.
                    .info("core", L("set_core"), value: m.coreVersion),
                ]),
                SettingsSection(id: "project", title: L("about_project"), rows: [.note("project", L("about_project_text"))]),
                SettingsSection(id: "service", title: macText("mac.service", "System service"), rows: [
                    .popup("service_remove", macText("mac.service.remove", "Remove the service"),
                           sub: macText("mac.service.remove.sub", "The tunnel and its checks stop; the app then asks to install it again"),
                           button: macText("mac.service.remove.button", "Remove…"), danger: true) {
                        AnyView(RemoveServiceConfirm(model: m))
                    },
                ]),
            ]
        }
    }
}

/// "without the tunnel: 12 · through the server: 3", or "empty". A `.dat` category (`ext:`) counts with all it
/// holds (Android `CustomZone.count`), or a zone made only of lists would read "empty".
private func zoneSummary(_ z: CustomZone) -> String {
    func count(_ list: [String]) -> Int { list.reduce(0) { $0 + ($1.hasPrefix("ext:") ? (z.listSizes[$1] ?? 1) : 1) } }
    let (d, s) = (count(z.direct), count(z.server))
    let parts = [d > 0 ? L("zone_part_direct", d) : nil, s > 0 ? L("zone_part_server", s) : nil]
    let text = parts.compactMap { $0 }.joined(separator: " · ")
    return text.isEmpty ? L("zone_none") : text
}

/// The live bit of a settings' action row — work in flight, the last outcome — kept apart from the
/// rows themselves: they are data rebuilt on every render, so nothing can stay on them. `@Observable`
/// so a write repaints the rows that read it.
@MainActor @Observable
private final class RowFeedback {
    static let shared = RowFeedback()
    /// Row ids with work on the wire (the row dims and refuses until it ends).
    var busy: Set<String> = []
    /// Row id → the line under it: the failure the last run left; cleared at the next start.
    var result: [String: String] = [:]
}

/// A settings' action row's call to the service (`serviceAction`): the request's own reply decides
/// the row's outcome — the app's busy counter and `commandError` belong to every command together,
/// so a command started elsewhere used to keep this row spinning to the cap and left its failure
/// under the wrong row. `runAction` isn't asked (its answer isn't handed back); of what it does
/// after a reply only the refresh is repeated — the sidebar's `commandError` is not the row's place.
@MainActor
private func runServiceAction(_ m: MainModel, row id: String, _ target: String) {
    guard m.backend != nil else { return }
    let f = RowFeedback.shared
    f.busy.insert(id)
    f.result[id] = nil
    Task {
        defer { f.busy.remove(id) }
        // The row spins until THIS call's reply is in; the wire's timeout is the cap.
        let answer = await ServiceClient.send(Request(.serviceAction, target: target), timeoutSec: 60)
        f.result[id] = answer == nil ? String(localized: "main.no.answer") : answer?.error.map { ServiceError.friendly($0) }
        // The action can change what the screens show (updated lists, a subscription's new
        // servers): refresh now instead of waiting for the poll loop's next tick.
        await (m.backend as? AppModel)?.refresh()
    }
}

/// Whether the routing lists are in, as far as the app can tell: the engine reports one flag for all
/// lists (`EngineStatus.listsUpdated`) — a zone's own readiness (Android's `Regions.ready`) isn't
/// exposed to the Mac. The demo has no engine answer and counts as ready (its lists' date is made up).
@MainActor
private func listsReady(_ m: MainModel) -> Bool {
    m.lastEngine?.listsUpdated.map { $0 > 0 } ?? (m.backend == nil)
}

/// The built-in zone row's second line (Android's `zone_sites_apps` / `zone_lists_*` there): what it
/// covers once its lists are in; until then what the engine reports it is doing — a download in flight
/// ("downloading" / "looking for a server to fetch through"), the last failure with its next retry,
/// else "in a moment".
@MainActor
private func zoneRowSub(_ m: MainModel, ready: Bool) -> String {
    if ready {
        return String(format: macText("mac.zone.sites.apps", "Sites: %@ and others · its apps work without the tunnel"),
                      builtinInfo?.sites ?? ".ru")
    }
    let e = m.lastEngine
    if e?.listsBusy == true {
        return e?.listsWork == "VIA_SERVER" ? L("zone_lists_via_server") : L("zone_lists_downloading")
    }
    if let err = e?.listsError, !err.isEmpty {
        if let retryAt = e?.listsRetryAt, retryAt > 0 {
            return L("zone_lists_failed", err, retryText(retryAt))
        }
        return L("set_geo_failed", err)
    }
    return L("zone_lists_pending")
}

/// "in 5 min / 2 h / 1 d 3 h" — the duration Android's `zone_lists_failed` shows until the next try.
private func retryText(_ atMs: Int64) -> String {
    let min = max(0, Int((Double(atMs) / 1000 - Date().timeIntervalSince1970) + 59) / 60)
    if min < 1 { return L("dur_lt_min") }
    if min < 90 { return L("dur_m", min) }
    let h = min / 60
    if h < 36 { return L("dur_h_short", h) }
    return L("dur_dh", h / 24, h % 24)
}

/// The "Update the routing lists" row's second line (Android `GeoListsSection`): "Updating…" while the
/// engine's download runs (manual or scheduled — `listsBusy` covers both); "not downloaded yet" when
/// the engine reports none; the download's last error and the last command's failure appended.
@MainActor
private func geoSub(_ m: MainModel, busy: Bool) -> String {
    var sub = busy ? L("subs_updating")
        : m.lastEngine != nil && (m.lastEngine?.listsUpdated ?? 0) <= 0 ? L("set_geo_none")
        : L("set_geo_state", dateText(m.listsDate), relativeText(m.listsChecked))
    if !busy {
        if let e = m.lastEngine?.listsError, !e.isEmpty { sub += "\n" + L("set_geo_failed", e) }
        if let r = RowFeedback.shared.result["geo_update"] { sub += "\n" + r }
    }
    return sub
}

/// The "Update to X" row's second line (Android `UpdateRows` states): why the button waits / what runs.
private func updateSub(_ u: EngineStatus.UpdateStatus) -> String {
    switch u.state {
    case "available": return L("update_offer_sub")
    case "ready":
        // The staged release is verified; the contract applies it on confirm or next service start.
        return macText("mac.update.ready", "Downloaded and verified — install it now or it installs when the service next starts.")
    case "downloading": return macText("mac.update.downloading", "Downloading…")
    case "installing": return L("ev_update_installing", u.version ?? "")
    default:
        if let e = u.error, !e.isEmpty { return L("update_failed", e) }
        return L("update_offer_sub")
    }
}

/// The "Check for updates" row's second line (Android `UpdateRow`): "Checking…" in flight; the last
/// check's verdict — "latest version · checked X" when nothing was offered, the failure when it went
/// wrong; this run's own outcome under it.
@MainActor
private func updateCheckSub(_ u: EngineStatus.UpdateStatus?, checking: Bool) -> String? {
    if checking { return L("update_checking") }
    var lines: [String] = []
    if u?.state == "error", let e = u?.error, !e.isEmpty {
        lines.append(L("update_failed", e))
    } else if let checkedAt = u?.checkedAt, checkedAt > 0 {
        lines.append(L("update_latest", relativeText(Date(timeIntervalSince1970: Double(checkedAt) / 1000))))
    }
    if let r = RowFeedback.shared.result["update_check"] { lines.append(r) }
    return lines.isEmpty ? nil : lines.joined(separator: "\n")
}

/// What `loadZone` hands to the new zone's dialog (Android `Loaded`): the lines the parser refused
/// and whether the lists hit the entry cap.
struct ZoneLoadNotes: Sendable {
    var skipped: [String] = []
    var truncated = false
}

/// The loading notes of a zone file `loadZone` just read, by the new zone's id: the dialog opened
/// for that zone takes them out (`ZoneDialog.loadSkipped`/`loadTruncated`). A single slot in the
/// model was consumed by whichever zone dialog loaded next — a different zone's page inherited the
/// lines it never saw.
@MainActor private var zoneLoadNotes: [String: ZoneLoadNotes] = [:]

/// "Load a zone from a file": a zone file or a list of sites in one of the known formats becomes a new
/// zone, opened. Android's `ImportDialog` choices are kept: a file without `[direct]`/`[server]` markers
/// can't say where its entries go, so it is asked (F-ROUTING-0026); entries are capped at
/// `ZoneFile.MAX_ENTRIES` like Android's parse, the overflow reported (F-ROUTING-0029). Xray's binary
/// `.dat` is recognised and refused by name until a `DatFile` bridge exists (F-ROUTING-0023, SEAM).
@MainActor
private func loadZone(_ m: MainModel, menus: MenuState) {
    let panel = NSOpenPanel()
    panel.allowsMultipleSelection = false
    guard panel.runModal() == .OK, let url = panel.url else { return }
    Task {
        // Read and parsed off the main actor (a big list on a slow disk would freeze the window for
        // it), capped like Android's ZONE_FILE_MAX: bigger files aren't zones. The zone itself is
        // built there too — `ZoneFile.Parsed` isn't Sendable and can't cross the actor boundary.
        let loaded = await Task.detached { () -> ZoneRead in
            guard let size = try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize, size <= 30_000_000,
                  let data = try? Data(contentsOf: url) else { return .unreadable }
            // Xray's .dat opens with a protobuf length byte (0x0A) — the same test Android's `read` does.
            if data.first == 0x0A { return .dat }
            guard let text = String(data: data, encoding: .utf8) else { return .unreadable }
            let parsed = ZoneFile.parse(text)
            // Android `Parsed.sectioned`: the file's own [direct]/[server] markers said where the
            // entries go; everything else lands in "direct" and the user is asked instead.
            let sectioned = text.split(whereSeparator: \.isNewline).contains { line in
                let t = line.trimmingCharacters(in: .whitespaces).lowercased()
                return t == "[direct]" || t == "[server]"
            }
            var notes = ZoneLoadNotes(skipped: parsed.skipped)
            var (d, s, a) = (parsed.direct, parsed.server, parsed.apps)
            // Android caps at ZoneFile.MAX_ENTRIES over all lists taken together and tells the user
            // only the first were taken; here, without the wire's own count, the same total in
            // direct/server/apps order.
            let total = d.count + s.count + a.count
            if total > 50_000 {
                notes.truncated = true
                d = Array(d.prefix(50_000))
                s = Array(s.prefix(max(0, 50_000 - d.count)))
                a = Array(a.prefix(max(0, 50_000 - d.count - s.count)))
            }
            return .zone(CustomZone(id: "u-\(UUID().uuidString.prefix(6))",
                                    name: parsed.name ?? url.deletingPathExtension().lastPathComponent,
                                    direct: d, server: s, apps: a),
                         notes: notes, sectioned: sectioned)
        }.value
        switch loaded {
        case .unreadable:
            m.commandError = L("zone_load_unreadable")
        case .dat:
            m.commandError = macText("mac.zone.dat", "An Xray “.dat” list can't be loaded as a zone on the Mac yet.")
        case .zone(let zone, let notes, let sectioned):
            if zone.direct.isEmpty && zone.server.isEmpty && zone.apps.isEmpty {
                m.commandError = L("zone_load_nothing")
                return
            }
            func take(_ z: CustomZone) {
                Task { @MainActor in
                    m.zones.append(z)
                    zoneLoadNotes[z.id] = notes
                    m.openZone = z.id
                }
            }
            if sectioned {
                take(zone)
            } else {
                // The entries all sit in `direct` (the parser's default section): the pick moves them.
                menus.show(dialog: AnyView(ZoneWhereDialog { through in
                    var z = zone
                    if through { z.server = z.direct; z.direct = [] }
                    take(z)
                }))
            }
        }
    }
}

/// What `loadZone`'s background read came back with.
private enum ZoneRead {
    case unreadable, dat
    case zone(CustomZone, notes: ZoneLoadNotes, sectioned: Bool)
}

/// The "where do the entries go" pick Android's import dialog asks for a file that can't say (no
/// `[direct]`/`[server]` markers): every parsed entry lands in the chosen list.
private struct ZoneWhereDialog: View {
    /// true = "through the server".
    var pick: (Bool) -> Void
    @State private var server = false
    @Environment(MenuState.self) private var menus

    var body: some View {
        AppDialog(title: L("zone_import_title"), width: 320) {
            Text(macText("mac.zone.where", "The file doesn’t say where its entries go — pick:"))
                .font(Fonts.body(12)).foregroundStyle(Palette.textSecondary)
                .fixedSize(horizontal: false, vertical: true).textSelection(.enabled)
            FormBox {
                side(L("zone_direct"), on: !server) { server = false }
                side(L("zone_server"), on: server) { server = true }
            }
            HStack(spacing: 6) {
                Spacer()
                MacButton(title: L("cancel")) { menus.closeDialog() }.keyboardShortcut(.cancelAction)
                MacButton(title: L("zone_import"), accent: true) { menus.closeDialog(); pick(server) }
                    .keyboardShortcut(.defaultAction)
            }
            .padding(.top, 4)
        }
    }

    private func side(_ title: String, on: Bool, _ tap: @escaping () -> Void) -> some View {
        HStack(spacing: 8) {
            Image(systemName: on ? "checkmark.circle.fill" : "circle")
                .font(.system(size: 13)).foregroundStyle(on ? Palette.accent : Palette.textMuted)
            Text(title).font(Fonts.body(13)).foregroundStyle(Palette.text)
            Spacer()
        }
        .padding(.horizontal, 12).padding(.vertical, 8)
        .contentShape(Rectangle())
        .overlay(alignment: .top) { RowDivider() }
        .onTapGesture(perform: tap)
    }
}

/// "Open at login": the app's own login item (ServiceManagement); the connection comes back by itself — the service
/// keeps the wish (`TunnelController.wanted`). A refusal (a dev build outside /Applications the system won't take)
/// used to flip the switch back in silence — it now reads as a note under the row.
@MainActor
private func setLaunchAtLogin(_ on: Bool) {
    do {
        if on { try SMAppService.mainApp.register() } else { try SMAppService.mainApp.unregister() }
        RowFeedback.shared.result["login"] = nil
    } catch {
        RowFeedback.shared.result["login"] = macText("mac.login.item", "macOS refused to open the app at login.")
    }
}

/// The entry's name inside the zip (Android `StatsExport`): the prose entries are `.txt`, the
/// dumped tables `.csv` — the service's answer carries only the key, the suffix is decided here.
private func exportName(_ table: String) -> String {
    "\(table).\(table == "info" || table == "errors" ? "txt" : "csv")"
}

/// The statistics as a zip of CSVs (Android's `StatsExport`): the service dumps its tables, the app packs them and
/// asks where to save. Not for the demo (it keeps no statistics). Failures land under the row — an export
/// that writes nothing in silence was the bug behind `try?` everywhere.
@MainActor
private func exportStats(_ m: MainModel) {
    guard m.backend != nil else { return }
    let f = RowFeedback.shared
    f.busy.insert("export")
    f.result["export"] = nil
    Task {
        defer { f.busy.remove("export") }
        func fail(_ detail: String) {
            // Local, under the row: a failed export isn't a command refusal and doesn't belong in
            // the sidebar's `commandError`. The `Task` hop: `f` is main-actor state, this closure isn't.
            let text = macText("mac.export.failed", "Couldn't export the statistics:") + " " + detail
            Task { @MainActor in f.result["export"] = text }
        }
        guard let answer = await ServiceClient.send(Request(.exportStats), timeoutSec: 60) else {
            fail(String(localized: "main.no.answer"))
            return
        }
        if let error = answer.error {
            // A refused export is not a quiet service: say what the service said.
            fail(ServiceError.friendly(error))
            return
        }
        guard let tables = answer.tables,
              let raw = try? JSONSerialization.jsonObject(with: Data(tables.utf8)) as? [String: String] else {
            fail(macText("mac.err.badreply", "The service's answer couldn't be read."))
            return
        }
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("borderless-stats-\(Int(Date().timeIntervalSince1970))", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let zip = dir.appendingPathComponent("borderless-statistics.zip")
        do {
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            for (name, csv) in raw {
                try Data(csv.utf8).write(to: dir.appendingPathComponent(exportName(name)))
            }
        } catch {
            fail(error.localizedDescription)
            return
        }
        let inputs = raw.keys.map { dir.appendingPathComponent(exportName($0)).path }
        // Packing waits for a process: off the main actor, or the window freezes for it.
        let ok = await Task.detached(priority: .utility) { () -> Bool in
            let process = Process()
            process.executableURL = URL(fileURLWithPath: "/usr/bin/zip")
            process.arguments = ["-q", "-j", zip.path] + inputs
            guard (try? process.run()) != nil else { return false }
            process.waitUntilExit()
            return process.terminationStatus == 0
        }.value
        guard ok else {
            fail(macText("mac.export.zip", "packing the archive failed"))
            return
        }
        let panel = NSSavePanel()
        panel.nameFieldStringValue = "borderless-statistics.zip"
        guard panel.runModal() == .OK, let url = panel.url else { return }
        do {
            // `copyItem` can't overwrite: a target the panel's "Replace" already confirmed goes first.
            try? FileManager.default.removeItem(at: url)
            try FileManager.default.copyItem(at: zip, to: url)
        } catch {
            fail(error.localizedDescription)
        }
    }
}

/// The one built-in zone's id (`Regions` on Android reads the list from `regions.json`; the Mac keeps only this one).
private let builtinZone = "ru"

/// The built-in zones of the bundled `regions.json` (the file `Regions` reads on Android), reduced to
/// what the settings show: the domain zones (".ru" — the label is the first) and the resolvers.
private struct BuiltinZone {
    var code: String
    var zones: [String]
    var dns: [String]
    var label: String { zones.first ?? "." + code }
    var sites: String { zones.joined(separator: ", ") }
}

private let builtinZones: [BuiltinZone] = {
    struct ZonesFile: Decodable {
        struct Region: Decodable { var code: String; var zones: [String]?; var dns: [String]? }
        var regions: [Region]?
    }
    guard let url = Bundle.main.url(forResource: "regions", withExtension: "json"),
          let data = try? Data(contentsOf: url),
          let file = try? JSONDecoder().decode(ZonesFile.self, from: data) else { return [] }
    return (file.regions ?? []).map { BuiltinZone(code: $0.code, zones: $0.zones ?? [], dns: $0.dns ?? []) }
}()

private var builtinInfo: BuiltinZone? { builtinZones.first { $0.code == builtinZone } }

/// The resolvers an empty "Direct DNS" stands for (the field's "auto: …" placeholder): the chosen
/// zones' own from `regions.json`, else the public pair `XrayConfig.PUBLIC_DNS` falls back to.
private func autoDns(_ s: SettingsState) -> String {
    let zoned = builtinZones.filter { s.regions.contains($0.code) }.flatMap(\.dns)
    return (zoned.isEmpty ? ["1.1.1.1", "8.8.8.8"] : zoned).joined(separator: ", ")
}

// `macText` (the macOS-only text helper this file introduced) lives in Basics.swift now.

/// Light, dark or the system's, for the whole app (Palette's colours follow the appearance).
@MainActor
func applyAppearance(_ s: SettingsState) {
    NSApp.appearance = s.themeFollowsSystem ? nil : NSAppearance(named: s.darkTheme ? .darkAqua : .aqua)
}

private let appVersion = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "—"
/// The language this app was told to use (its own defaults, not the system's list): "" = the system's.
let languageAtStart: String = {
    let own = UserDefaults.standard.persistentDomain(forName: Bundle.main.bundleIdentifier ?? "")?["AppleLanguages"] as? [String]
    return own?.first.flatMap { tag in ["en", "ru"].first { tag.hasPrefix($0) } } ?? ""
}()

/// A language's own name ("English", "Русский"), or "System language" for the empty tag.
private func languageTitle(_ tag: String) -> String {
    guard !tag.isEmpty else { return L("set_sum_lang_system") }
    let locale = Locale(identifier: tag)
    let name = locale.localizedString(forLanguageCode: tag) ?? tag
    return name.prefix(1).uppercased() + name.dropFirst()
}

private func notifyTitle(_ v: NotifyMode) -> String {
    switch v {
    case .off: L("set_notify_off")
    case .unclear: L("set_notify_unclear")
    case .always: L("set_notify_always")
    }
}

/// A palette's name (`palette_<id>`; an unknown id reads Coral's, as Android's `paletteName`).
private func paletteName(_ id: String) -> String {
    L("palette_" + (Schemes.all.contains { $0.id == id } ? id : "coral"))
}

// MARK: Rows as data

struct SettingsSection: Identifiable {
    let id: String
    var title: String
    /// A grey line under the box (Android's InfoNote at the end of a section).
    var note: String? = nil
    var rows: [SettingRow]

    /// The rows the search finds (Android `SettingsQuery.matches`: every word in the row or its page / section title,
    /// at least one in the row itself); nil when none.
    func filtered(_ q: SettingsQuery, page: String) -> SettingsSection? {
        let found = rows.filter { q.matches(row: $0.searchText, context: [page, title]) }
        return found.isEmpty ? nil : SettingsSection(id: id, title: title, rows: found)
    }
}

struct SettingRow: Identifiable {
    enum Kind {
        case toggle(Binding<Bool>, enabled: Bool)
        case choice(current: String, options: [(String, Bool, () -> Void)])
        case number(Binding<Int>, range: ClosedRange<Int>, unit: String?)
        case text(Binding<String>, hint: String?, placeholder: String?, multiline: Bool)
        case info(String, tone: Color?)
        case action(button: String, danger: Bool, confirm: String?, () -> Void)
        /// A button that opens a popup of its own content (a confirm offering more than yes/no).
        case popup(button: String, danger: Bool, popup: () -> AnyView)
        /// The whole row opens something (Android's NavRow with its arrow).
        case nav(() -> Void)
        /// A switch, and the rest of the row opens it (a zone: on / off, and its page).
        case toggleNav(Binding<Bool>, () -> Void)
        /// The palette cards of Android's `PaletteChooser` (a whole block, not a label + control row).
        case paletteChooser(current: String, pick: (String) -> Void)
        case note
    }

    let id: String
    var title: String
    var sub: String?
    var kind: Kind
    /// false = the row shows but its control can't be used (a feature the Mac doesn't have yet, work
    /// already running); `sub` then carries the "not on the Mac yet" badge or the reason.
    var enabled = true

    var searchText: [String] {
        var all = [title, sub].compactMap { $0 }
        switch kind {
        case .choice(let current, let options): all.append(current); all += options.map(\.0)
        case .info(let value, _): all.append(value)
        // Searching: found by the section's title or a scheme's name, as on Android.
        case .paletteChooser: all += Schemes.all.map { paletteName($0.id) }
        default: break
        }
        return all
    }

    static func toggle(_ id: String, _ title: String, sub: String?, _ value: Binding<Bool>, enabled: Bool = true) -> SettingRow {
        // `enabled` goes to both: the switch's own flag dims and stills it, the row's makes the
        // whole row inert (hit-testing, taps) like every other kind's disabled row.
        SettingRow(id: id, title: title, sub: sub, kind: .toggle(value, enabled: enabled), enabled: enabled)
    }

    static func choice(_ id: String, _ title: String, current: String, options: [(String, Bool, () -> Void)], sub: String? = nil,
                       enabled: Bool = true) -> SettingRow {
        SettingRow(id: id, title: title, sub: sub, kind: .choice(current: current, options: options), enabled: enabled)
    }

    static func number(_ id: String, _ title: String, sub: String?, _ value: Binding<Int>, _ range: ClosedRange<Int>, unit: String?,
                       enabled: Bool = true) -> SettingRow {
        // Android's NumberRow dialog puts the "from X to Y" hint under its field; the field being
        // inline here, the range rides the row's second line (R10 — shown, then enforced).
        let rangeSub = L("range", range.lowerBound, range.upperBound)
        let full = sub?.isEmpty == false ? sub! + " (" + rangeSub + ")" : rangeSub
        return SettingRow(id: id, title: title, sub: full, kind: .number(value, range: range, unit: unit), enabled: enabled)
    }

    static func text(_ id: String, _ title: String, _ value: Binding<String>, hint: String?, placeholder: String? = nil,
                     multiline: Bool = false) -> SettingRow {
        SettingRow(id: id, title: title, sub: hint, kind: .text(value, hint: hint, placeholder: placeholder, multiline: multiline))
    }

    static func info(_ id: String, _ title: String, value: String, sub: String? = nil, tone: Color? = nil) -> SettingRow {
        SettingRow(id: id, title: title, sub: sub, kind: .info(value, tone: tone))
    }

    static func action(_ id: String, _ title: String, sub: String?, button: String, danger: Bool = false, confirm: String? = nil,
                       enabled: Bool = true, _ run: @escaping () -> Void) -> SettingRow {
        SettingRow(id: id, title: title, sub: sub, kind: .action(button: button, danger: danger, confirm: confirm, run), enabled: enabled)
    }

    static func popup(_ id: String, _ title: String, sub: String?, button: String, danger: Bool = false,
                      _ popup: @escaping () -> AnyView) -> SettingRow {
        SettingRow(id: id, title: title, sub: sub, kind: .popup(button: button, danger: danger, popup: popup))
    }

    static func nav(_ id: String, _ title: String, sub: String?, _ open: @escaping () -> Void) -> SettingRow {
        SettingRow(id: id, title: title, sub: sub, kind: .nav(open))
    }

    static func toggleNav(_ id: String, _ title: String, sub: String?, _ value: Binding<Bool>, _ open: @escaping () -> Void) -> SettingRow {
        SettingRow(id: id, title: title, sub: sub, kind: .toggleNav(value, open))
    }

    static func paletteChooser(_ id: String, current: String, pick: @escaping (String) -> Void) -> SettingRow {
        SettingRow(id: id, title: L("set_palette"), sub: nil, kind: .paletteChooser(current: current, pick: pick))
    }

    static func note(_ id: String, _ text: String) -> SettingRow {
        SettingRow(id: id, title: text, sub: nil, kind: .note)
    }
}

/// The search's words (Android `SettingsQuery`).
struct SettingsQuery {
    let words: [String]

    init?(_ text: String) {
        let words = Self.normalize(text).split(whereSeparator: \.isWhitespace).map(String.init)
        guard !words.isEmpty else { return nil }
        self.words = words
    }

    /// Android `SettingsQuery.normalize`: lower case, "ё" as "е", typographic quotes and dots dropped —
    /// what is typed matches what is shown.
    static func normalize(_ text: String) -> String {
        text.lowercased()
            .replacingOccurrences(of: "ё", with: "е")
            .components(separatedBy: CharacterSet(charactersIn: "«»“”\"„…")).joined()
    }

    /// Every word of the query is found in the row or its context (page + section title), and at least
    /// one in the row itself (Android `SettingsQuery.matches` — a word naming the page must not bring
    /// up every row).
    func matches(row: [String], context: [String]) -> Bool {
        let own = Self.normalize(row.joined(separator: "\n"))
        let all = Self.normalize(context.joined(separator: "\n")) + "\n" + own
        return words.allSatisfy { all.contains($0) } && words.contains { own.contains($0) }
    }

    /// [text] with each found query word's span marked (Android `marked`): the highlight searches a
    /// lower-cased, ё-folded copy — those transforms keep the offsets, so the ranges land on the
    /// original (quotes/dots are NOT stripped here on purpose — stripping would shift the indices).
    func marked(_ text: String) -> AttributedString {
        var out = AttributedString(text)
        let lower = (text.lowercased() as NSString).replacingOccurrences(of: "ё", with: "е") as NSString
        for word in words where !word.isEmpty {
            var at = 0
            while at < lower.length {
                let found = lower.range(of: word, range: NSRange(at..<lower.length))
                if found.location == NSNotFound { break }
                if let span = Range(found, in: out) { out[span].backgroundColor = Palette.accentContainer }
                at = found.location + max(found.length, 1)
            }
        }
        return out
    }
}

// MARK: Drawing

private struct PageList: View {
    var model: MainModel
    @Binding var page: SettingsPage
    var searching: Bool
    var onPick: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 1) {
            ForEach(SettingsPage.allCases) { p in
                PageItem(page: p, summary: p.summary(model), selected: !searching && p == page) {
                    guard searching || page != p else { return }
                    onPick()
                    if page != p { page = p }
                }
            }
            Spacer(minLength: 0)
        }
        .padding(10)
        .frame(maxHeight: .infinity, alignment: .top)
        .background(Palette.card.opacity(0.35))
    }
}

private struct PageItem: View {
    var page: SettingsPage
    var summary: String
    var selected: Bool
    var action: () -> Void
    @State private var hover = false

    var body: some View {
        Button(action: action) {
            HStack(alignment: .top, spacing: 9) {
                Image(systemName: page.symbol).font(.system(size: 12.5))
                    .foregroundStyle(selected ? Palette.accentStrong : Palette.textSecondary)
                    .frame(width: 18, height: 16)
                VStack(alignment: .leading, spacing: 1) {
                    Text(page.title).font(Fonts.body(13, bold: selected)).foregroundStyle(selected ? Palette.accentStrong : Palette.text)
                        .lineLimit(1)
                    Text(summary).font(Fonts.body(11)).foregroundStyle(Palette.textSecondary).lineLimit(1)
                }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 8).padding(.vertical, 5)
            .background(RoundedRectangle(cornerRadius: 6).fill(selected ? Palette.selected : hover ? Palette.cardStrong.opacity(0.6) : .clear))
            .contentShape(RoundedRectangle(cornerRadius: 6))
            .onHover { hover = $0 }
        }
        .buttonStyle(.plain)
    }
}

private struct SectionView: View {
    var section: SettingsSection
    /// The active search, for marking hits in the rows (Android `LocalSettingsQuery`).
    var query: SettingsQuery? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(section.title).font(Fonts.body(12, bold: true)).foregroundStyle(Palette.textSecondary).padding(.leading, 2).textSelection(.enabled)
            FormBox {
                ForEach(section.rows) { RowView(row: $0, query: query) }
            }
            if let note = section.note {
                Text(note).font(Fonts.body(11)).foregroundStyle(Palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true).padding(.horizontal, 2).textSelection(.enabled)
            }
        }
    }
}

private struct RowView: View {
    var row: SettingRow
    var query: SettingsQuery? = nil
    @Environment(MenuState.self) private var menus
    @Environment(\.snapshot) private var snapshot

    var body: some View {
        switch row.kind {
        case .note:
            markedText(row.title).font(Fonts.body(12)).foregroundStyle(Palette.textSecondary)
                .fixedSize(horizontal: false, vertical: true).textSelection(.enabled)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 12).padding(.vertical, 8)
                .overlay(alignment: .top) { divider }
        case .text(let value, _, let placeholder, true):
            VStack(alignment: .leading, spacing: 6) {
                labels
                TextBox(text: value, placeholder: placeholder ?? "", snapshot: snapshot)
            }
            .padding(.horizontal, 12).padding(.vertical, 8)
            .overlay(alignment: .top) { divider }
        case .paletteChooser(let current, let pick):
            PaletteChooser(current: current, pick: pick)
                .padding(10)
                .overlay(alignment: .top) { divider }
        default:
            // The labels first, so a long grey line wraps and the row grows (a flexible frame let it spill over the next row).
            HStack(alignment: .center, spacing: 12) {
                labels.layoutPriority(1)
                Spacer(minLength: 0)
                // A disabled row keeps its labels readable (the sub says why) — only the control dims and goes quiet.
                HStack(spacing: 6) { control }.fixedSize()
                    .opacity(row.enabled ? 1 : 0.45)
                    .allowsHitTesting(row.enabled)
            }
            .padding(.horizontal, 12).padding(.vertical, 7)
            .frame(minHeight: 34)
            .contentShape(Rectangle())
            .onTapGesture {
                guard row.enabled else { return }
                if case .nav(let open) = row.kind { open() }
                if case .toggleNav(_, let open) = row.kind { open() }
            }
            .overlay(alignment: .top) { divider }
        }
    }

    private var divider: some View { RowDivider() }

    /// The label marked where the search's words hit (Android `marked` — accentContainer spans).
    private func markedText(_ text: String) -> Text {
        query.map { Text($0.marked(text)) } ?? Text(text)
    }

    private var labels: some View {
        // A nav row's tap opens its page — selectable labels would fight it; everywhere else they are
        // plain information (the .info value itself is selectable at its own site).
        let opens = { if case .nav = row.kind { return true }; if case .toggleNav = row.kind { return true }; return false }()
        let texts = VStack(alignment: .leading, spacing: 2) {
            markedText(row.title).font(Fonts.body(13)).foregroundStyle(Palette.text)
                .fixedSize(horizontal: false, vertical: true)
            if let sub = row.sub {
                markedText(sub).font(Fonts.body(11)).foregroundStyle(Palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        return Group { if opens { texts } else { texts.textSelection(.enabled) } }
    }

    @ViewBuilder private var control: some View {
        switch row.kind {
        case .toggle(let value, let enabled):
            AppSwitch(isOn: value)
                .disabled(!enabled || !row.enabled)
                .opacity(enabled && row.enabled ? 1 : 0.45)
        case .choice(let current, let options):
            HStack(spacing: 5) {
                Text(current).lineLimit(1)
                Image(systemName: "chevron.up.chevron.down").font(.system(size: 9, weight: .semibold)).foregroundStyle(Palette.textSecondary)
            }
            .padding(.horizontal, 8)
            .modifier(MacButtonLook())
            .frame(maxWidth: 260, alignment: .trailing)
            .fixedSize()
            .appMenuOnClick(menus) {
                options.map { title, selected, pick in MenuItem(title: title, selected: selected, action: pick) }
            }
        case .number(let value, let range, let unit):
            NumberField(value: value, range: range, snapshot: snapshot, enabled: row.enabled)
            if let unit { Text(unit.trimmingCharacters(in: .whitespaces)).font(Fonts.body(12)).foregroundStyle(Palette.textSecondary).textSelection(.enabled) }
        case .text(let value, _, let placeholder, _):
            PlainField(placeholder: placeholder ?? "", text: value, snapshot: snapshot)
                .modifier(FieldBezel())
                .frame(width: 230)
        case .info(let value, let tone):
            Text(value).font(Fonts.body(13, bold: tone != nil)).foregroundStyle(tone ?? Palette.text).textSelection(.enabled)
        case .action(let button, let danger, let confirm, let run):
            ConfirmButton(title: button, danger: danger, confirm: confirm, run: run)
        case .popup(let button, let danger, let popup):
            Text(button).foregroundStyle(danger ? Palette.danger : Palette.text)
                .padding(.horizontal, 10)
                .modifier(MacButtonLook())
                .fixedSize()
                .appPopupOnClick(menus) { popup() }
        case .nav:
            Image(systemName: "chevron.right").font(.system(size: 11, weight: .semibold)).foregroundStyle(Palette.textMuted)
        case .toggleNav(let value, _):
            AppSwitch(isOn: value)
            Image(systemName: "chevron.right").font(.system(size: 11, weight: .semibold)).foregroundStyle(Palette.textMuted)
        case .note, .paletteChooser:
            EmptyView()
        }
    }
}

/// A number typed in its own unit (Android `NumberRow`): the row's second line shows the valid range;
/// a commit (Return, focus lost or the row gone) takes the value only when it parses and is in range —
/// out-of-range or unreadable text is REJECTED like Android's disabled OK, never silently clamped:
/// the field keeps the typed text flagged in red while the stored value stays the old one.
private struct NumberField: View {
    @Binding var value: Int
    var range: ClosedRange<Int>
    var snapshot: Bool
    var enabled: Bool
    @State private var text = ""
    /// The field's content can't become the value (Android marks its field `isError` live the same way).
    @State private var invalid = false
    @FocusState private var focused: Bool

    var body: some View {
        PlainField(placeholder: "", text: $text, snapshot: snapshot, onCommit: commit)
            .multilineTextAlignment(.trailing)
            .focused($focused)
            .onChange(of: text) { invalid = parse() == nil }
            .onChange(of: focused) { _, on in if !on { commit() } }
            .modifier(FieldBezel())
            .overlay(RoundedRectangle(cornerRadius: 5)
                .strokeBorder(Palette.danger, lineWidth: 1).opacity(invalid ? 1 : 0))
            .frame(width: 62)
            .onAppear { text = "\(value)" }
            .onChange(of: value) { text = "\($1)" }
            .onDisappear(perform: commit)
    }

    /// The field as a value the row may keep: nil while it isn't one (unreadable or out of range).
    private func parse() -> Int? {
        guard let n = Int(text.trimmingCharacters(in: .whitespaces)), range.contains(n) else { return nil }
        return n
    }

    private func commit() {
        // A disabled row's field can't be typed into, but `onDisappear` still runs (the page left,
        // the row rebuilt): nothing may commit a value nothing could have changed.
        guard enabled else { return }
        if let n = parse() {
            value = n
            text = "\(n)"
            invalid = false
        } else {
            invalid = true
        }
    }
}

/// A list typed one entry per line (the sites lists); the placeholder is drawn while empty, as `PlainField`'s.
private struct TextBox: View {
    @Binding var text: String
    var placeholder: String
    var snapshot: Bool

    var body: some View {
        ZStack(alignment: .topLeading) {
            Group {
                if snapshot {
                    Text(text.isEmpty ? " " : text).frame(maxWidth: .infinity, minHeight: 60, alignment: .topLeading)
                } else {
                    TextEditor(text: $text).scrollContentBackground(.hidden).frame(height: 70)
                }
            }
            if text.isEmpty, !placeholder.isEmpty {
                Text(placeholder).foregroundStyle(Palette.textMuted).padding(.leading, 5).padding(.top, 8)
                    .allowsHitTesting(false)
            }
        }
        .font(.system(size: 12, design: .monospaced))
        .padding(4)
        .monoChrome()
    }
}

// `DangerButton`/`DangerButtonStyle` moved to Model+Settings.swift — the zone dialog needs them too.

/// A row's button; a destructive one asks first in the system's confirmation dialog ("This can't be
/// undone") — `.confirmationDialog`, the R4 pattern, safe because settings rows never sit inside
/// `menus.dialog`.
private struct ConfirmButton: View {
    var title: String
    var danger: Bool
    var confirm: String?
    var run: () -> Void
    @State private var asking = false

    var body: some View {
        if let confirm {
            Text(title).foregroundStyle(danger ? Palette.danger : Palette.text)
                .padding(.horizontal, 10)
                .modifier(MacButtonLook())
                .fixedSize()
                .contentShape(Rectangle())
                .onTapGesture { asking = true }
                .confirmationDialog(confirm, isPresented: $asking, titleVisibility: .visible) {
                    Button(title, role: danger ? .destructive : nil) { run() }
                    Button(L("cancel"), role: .cancel) {}
                } message: {
                    Text(L("cannot_undo"))
                }
        } else {
            MacButton(title: title, action: run).fixedSize()
        }
    }
}

/// "Remove the service" asks first, offering the keep/with-data choice `uninstall-service.sh` has: the service's
/// data — servers, settings, statistics — survives a reinstall or goes with it. On "done" the popup just closes:
/// the status answer turns "missing" and the install page comes up by itself (the `.outdated`/setup flow).
private struct RemoveServiceConfirm: View {
    var model: MainModel
    @Environment(MenuState.self) private var menus
    /// The admin dialog runs a while — the card stays up saying so instead of vanishing with no trace.
    @State private var removing = false
    @State private var failure: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(macText("mac.service.remove.q", "Remove the system service?"))
                .font(Fonts.body(13, bold: true)).foregroundStyle(Palette.text).textSelection(.enabled)
            Text(macText("mac.service.remove.what", "The tunnel and the background checks stop; Border(less) then asks to install it again, like on the first start."))
                .font(Fonts.body(11)).foregroundStyle(Palette.textSecondary).textSelection(.enabled)
            Text(macText("mac.service.remove.data", "Its data — servers, settings, statistics — can be kept for a reinstall or removed with it."))
                .font(Fonts.body(11)).foregroundStyle(Palette.danger).textSelection(.enabled)
            if let failure {
                Text(failure).font(Fonts.body(11)).foregroundStyle(Palette.danger)
                    .fixedSize(horizontal: false, vertical: true).textSelection(.enabled)
            }
            HStack(spacing: 6) {
                Spacer()
                if removing {
                    ProgressView().controlSize(.small)
                    Text(macText("mac.remove.working", "Removing the service…"))
                        .font(Fonts.body(12)).foregroundStyle(Palette.textSecondary).textSelection(.enabled)
                } else {
                    MacButton(title: L("cancel"), accent: true) { menus.close() }
                    MacButton(title: macText("mac.service.remove.keep", "Keep data")) { remove(withData: false) }
                    DangerButton(title: macText("mac.service.remove.with.data", "With data")) { remove(withData: true) }
                }
            }
        }
        .padding(12)
        .frame(width: 300)
    }

    private func remove(withData: Bool) {
        removing = true
        failure = nil
        // The card stays up behind the system's password dialog — the buttons give way to the note.
        Task { @MainActor in
            switch await ServiceInstaller.remove(withData: withData) {
            case .done:
                // The status answer turns "missing" and the install page comes up by itself.
                menus.close()
            case .cancelled:
                removing = false
            case .failed(let message):
                removing = false
                failure = message
            }
        }
    }
}

/// One card per palette (Android `PaletteChooser`): its name and a strip of the colours it uses, in the light or
/// dark version now in effect; the chosen one is outlined and checked.
private struct PaletteChooser: View {
    var current: String
    var pick: (String) -> Void
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        VStack(spacing: 8) {
            ForEach(Schemes.all, id: \.id) { light in
                let scheme = Schemes.byId(light.id, dark: colorScheme == .dark)
                let selected = scheme.id == current
                VStack(alignment: .leading, spacing: 7) {
                    HStack {
                        Text(paletteName(scheme.id)).font(Fonts.body(13, bold: selected)).foregroundStyle(Palette.text)
                        Spacer()
                        if selected { CheckMark(color: Palette.accentStrong) }
                    }
                    HStack(spacing: 0) {
                        // By place, not colour: two equal swatches would collide in identity.
                        ForEach(scheme.preview.indices, id: \.self) { Rectangle().fill(Color(nsColor: Palette.rgb(scheme.preview[$0]))) }
                    }
                    .frame(height: 18)
                    .clipShape(RoundedRectangle(cornerRadius: 5))
                    .overlay(RoundedRectangle(cornerRadius: 5).strokeBorder(Palette.outline, lineWidth: 1))
                }
                .padding(10)
                .background(RoundedRectangle(cornerRadius: 8).fill(Palette.card))
                .overlay(RoundedRectangle(cornerRadius: 8).strokeBorder(
                    selected ? Palette.accent : Palette.outline, lineWidth: selected ? 1.5 : 1))
                .contentShape(RoundedRectangle(cornerRadius: 8))
                .onTapGesture { pick(scheme.id) }
            }
        }
    }
}
