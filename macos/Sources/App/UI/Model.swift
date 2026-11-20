import Foundation
import Observation
import Shared

/// What the screens show: the Android demo build's made-up servers (`Demo.kt`: the same cities, pings and groups) with
/// `BORDERLESS_DEMO=1`, else the user's servers kept by the service (no checks or statistics until the engine runs
/// there: see [MainModel.backend]).
enum Phase: Equatable {
    case off, searching, connected, direct, paused, noNetwork, limited
}

enum Strategy: CaseIterable, Identifiable {
    case stable, fastest, failover, manual
    var id: Self { self }

    /// From the engine's name (Android's `Strategy`: STABLE, FASTEST, FAILOVER, MANUAL).
    init?(engine name: String) {
        switch name {
        case "STABLE": self = .stable
        case "FASTEST": self = .fastest
        case "FAILOVER": self = .failover
        case "MANUAL": self = .manual
        default: return nil
        }
    }

    var title: String {
        switch self {
        case .stable: L("strategy_stable")
        case .fastest: L("strategy_fastest")
        case .failover: L("strategy_failover")
        case .manual: L("strategy_manual")
        }
    }

    var description: String {
        switch self {
        case .stable: L("strategy_stable_desc")
        case .fastest: L("strategy_fastest_desc")
        case .failover: L("strategy_failover_desc")
        case .manual: L("strategy_manual_desc")
        }
    }
}

struct ServerItem: Identifiable, Equatable {
    let id: String
    var name: String
    var country: String?
    var groupId: String
    var proto: String
    /// The latest result: a ping, nil = no reply; `checked == false` = never checked.
    var ms: Int?
    var checked = true
    var limited = false
    var subscriptionId: String? = nil
    /// Hidden by the user (Android `Server.disabled`).
    var disabled = false
    /// The name in the link (`Server.name`); `name` is what is shown, the user's own name when set.
    var linkName = ""
    /// The user's own name, nil = the link's (`Server.customName`; the generic-names "custom wins" rule reads it).
    var customName: String? = nil
    /// The country was set by the user, not detected.
    var countryManual = false
    /// Limited-network servers: fixed stream count (0 = automatic), going over the server's limit, the device id typed
    /// by the user (empty = the one the app sends), the server's own stream limit if it has one.
    var limitedStreams = 0
    var limitedIgnoreLimit = false
    var limitedDevice = ""
    var limitedMax: Int? = nil
    /// What the link leaves unprotected on the way to the server (Android `Insecurity.of`, computed by the
    /// service off the built outbound and carried as `StoredServer.insecure`; the app's own `linkInsecureIssues`
    /// read of the link is only the fallback for an older service): unencrypted traffic, a TLS certificate
    /// that isn't checked.
    var insecure: Set<InsecureIssue> = []
    /// The connection endpoint read by the shared link/config parser.
    var host = ""
    var port = 0
    /// The share link it was added from (made-up in the demo).
    var link = ""
}

/// A link's unprotected side (Android `Insecurity.Issue`).
enum InsecureIssue: Int {
    case noEncryption, noCertCheck
    /// The row's word for it (Android's `ins_*` texts).
    var label: String {
        switch self {
        case .noEncryption: L("ins_no_encryption")
        case .noCertCheck: L("ins_no_cert_check")
        }
    }

    /// The service's wire name for it (`StoredServer.insecure`, written by `MacEngine`'s `insecure`);
    /// an unknown name drops itself rather than failing the row.
    init?(wireName: String) {
        switch wireName {
        case "no_encryption": self = .noEncryption
        case "no_cert_check": self = .noCertCheck
        default: return nil
        }
    }
}

struct GroupItem: Identifiable, Equatable {
    let id: String
    /// What is shown — for a nameless group the name it takes from its subscription (Android
    /// `Repo.groupName`); what is stored is nil then.
    var name: String
    /// Made for a subscription by an import with no name of its own (Android `Group.name == nil`): it
    /// takes the subscription's name, is written to the service without one, and goes away with it
    /// (`Repo.removeSubscription`, `moveSubscription`). A rename makes it an ordinary named group.
    var nameless = false
    /// The default group: always there, can't be renamed or deleted.
    var isDefault = false
}

struct SubscriptionItem: Identifiable, Equatable {
    let id: String
    var name: String
    var groupId: String
    var updatedAt: Date?
    var usedBytes: Int64?
    var totalBytes: Int64?
    var expire: Date?
    /// The provider's wish (`profile-update-interval`), hours; nil = none.
    var providerHours: Int?
    var viaServer = false
    /// The provider's title; `name` is what is shown, the user's own name when set.
    var providerName = ""
    /// The last update's failure the service reported (Android `Subscription.lastError` — the row's red line).
    var lastError: String?
    var url = ""
}

enum NoServerAction: CaseIterable { case direct, pause }
enum RoutingMode: CaseIterable { case regionDirect, listedOnly }
enum NotifyMode: CaseIterable { case off, unclear, always }

/// The settings (Android `AppSettings`, the fields the Mac shows), with a new install's values (`AppSettings.fresh`).
/// The service will own them; for now the window keeps them.
struct SettingsState: Equatable {
    var defaultStrategy: Strategy = .stable
    var avoidInsecure = false
    var pingAllSwitches = true
    var limitedAuto = true
    var failThreshold = 2
    var noServerAction: NoServerAction = .direct
    var retryIntervalSec = 30
    var rescanIntervalMin = 10
    var switchGainPercent = 30
    var switchGainMs = 60
    var minStayMin = 20
    var healthIntervalSec = 20
    var latencyCheckMin = 5
    var pauseScansScreenOff = true
    var carefulScans = false
    var probeTimeoutSec = 6
    var probeConcurrency = 0
    var testUrl = SettingsState.defaultTestUrl
    var ecoWithSaver = true
    var ecoStrategy = true
    var routingEnabled = true
    var routingMode: RoutingMode = .regionDirect
    /// No zones by default, as Android's `AppSettings` (the zone switches ask to be turned on).
    var regions: Set<String> = []
    var adFilter = false
    var directFirst = true
    var directDomains = ""
    var proxyDomains = ""
    var geoUpdateDays = 1
    var subUpdateHours = 12
    var userAgent = SettingsState.defaultUserAgent
    var sendDeviceModel = false
    var remoteDns = SettingsState.defaultRemoteDns
    var directDns = ""
    var ipv6 = false
    var fragment = false
    var mux = false
    var mtu = 1500
    /// The colour palette's id (`AppSettings.palette`): one of `Schemes` — "coral", "powder", "sage", "lavender",
    /// "sky", "mono".
    var palette = "coral"
    var darkTheme = false
    /// On the Mac it follows the system's appearance from the start (a platform convention; Android starts light).
    var themeFollowsSystem = true
    var language = ""
    var genericNames = false
    var customOverGeneric = true
    var confirmShare = false
    var notifyMode: NotifyMode = .unclear
    var verboseLog = false
    var statsDays = 180
    var autoUpdate = true
    /// Servers of these countries only (Android `AppSettings.countries` — the globe button's set; "-"
    /// stands for the unknown country). The engine echoes it in every status too.
    var countries: Set<String> = []
    /// The countries popup's order (`AppSettings.countrySort`: NAME / NAME_DESC / USED / STABLE).
    var countrySort = "NAME"

    static let defaultUserAgent = "v2rayNG/2.3.10"
    static let defaultTestUrl = "https://www.gstatic.com/generate_204"
    static let defaultRemoteDns = "https://1.1.1.1/dns-query"
}

/// A field left blank means its default, as Android's `TextRow` (`v.ifBlank { AppSettings().x }`): the service never
/// sees an empty User-Agent, test URL or remote DNS.
private func orDefault(_ v: String, _ d: String) -> String {
    v.trimmingCharacters(in: .whitespaces).isEmpty ? d : v
}

/// A domain zone of the user's own (Android `CustomZone`): sites and addresses going without the tunnel or through the
/// server; chosen like the built-in zones (`on` stands for its code in `AppSettings.regions`). The apps and the `.dat`
/// categories' sizes aren't edited on the Mac, but are carried so a load–save round-trip doesn't lose them.
struct CustomZone: Identifiable, Equatable {
    let id: String
    var name: String
    var on = true
    var direct: [String] = []
    var server: [String] = []
    /// Apps kept outside the tunnel (package names) — the Mac has no per-app routing, so they're shown but not edited.
    var apps: [String] = []
    /// How many entries each `ext:` item of the lists holds (a category of a `.dat` file), for showing.
    var listSizes: [String: Int] = [:]
}

/// One entry of the events log (Android `AppLog.Event`): when, how it went, the text.
struct LogEvent: Identifiable, Equatable {
    enum Kind { case good, info, warn, error }
    /// Made from the service's time and kind (plus the row's place), so a refetch that brings the same events
    /// doesn't rebuild the whole list (a `UUID()` per poll did: every row lost its identity every 5 s).
    let id: String
    var time: Date
    var kind: Kind
    var text: String
}

/// A check of servers in progress.
struct ScanProgress: Equatable {
    var done: Int
    var total: Int
    var probing: Set<String>
    /// Each scan starts a fresh sky on the orbits.
    var id = UUID()
    /// What this scan found so far: a ping, nil = no reply.
    var results: [String: Int?] = [:]
    /// The service's scan: when it started (ms since 1970), so the same scan keeps its [id] across refreshes.
    var startedAtMs: Int64 = 0
}

/// The real tunnel behind the window (the service); nil in the demo, whose actions are simulated.
@MainActor
protocol ServiceBackend: AnyObject {
    func toggle()
    func connect(_ serverId: String)
    func pingAll()
    /// The main screen's switches: the strategy, the routing, the battery saving mode, the countries.
    func setStrategy(_ s: Strategy)
    func setRouting(_ on: Bool)
    func setEco(_ on: Bool)
    func setCountries(_ codes: Set<String>)
    /// The log screen's "clear".
    func clearLog()
    /// "Update subscriptions" / "Update routing lists" / "Clear statistics" (the service's `serviceAction`).
    func runAction(_ kind: String)
    /// The groups screen's auto-visibility (the service rebuilds its pool) and scan-order switches.
    func setAutoActive(_ on: Bool)
    func setAutoSort(_ on: Bool)
    /// The statistics' custom range (ms since 1970; 0, 0 = none) — kept on the service like on Android.
    func setStatsRange(_ fromMs: Int64, _ toMs: Int64)
    /// A fresh statistics request outside the refresh loop's pace (a new period picked, a "try again").
    func statsRefresh()
    func screenChanged()
}

@MainActor @Observable
final class MainModel {
    @ObservationIgnored weak var backend: ServiceBackend?
    /// Parse each configuration once; unchanged list refreshes reuse its metadata.
    @ObservationIgnored private var linkInfoCache: [String: SharedCode.ServerInfo] = [:]
    /// Servers can be checked (pinged): the service's engine does it, the demo makes results up. Nothing to
    /// check when every server is hidden or limited-network (scans skip both — `startScan` filters them),
    /// so the button greys out (as `toggle` refuses an empty list).
    var canProbe: Bool { servers.contains { isActive($0) && !$0.limited } }
    var phase: Phase = .connected
    var servers: [ServerItem] = [] {
        // displayName's per-group order map depends on it (rebuilt lazily, not on every cell's call).
        didSet { groupIndex = nil }
    }
    var groups: [GroupItem] = []
    var subscriptions: [SubscriptionItem] = []
    /// Groups screen: what is selected (a server's or a subscription's id), folded groups, the top bar's toggles.
    var selectedId: String?
    /// Groups the user opened (Android `GroupsScreen.expanded`, in-memory only): every group starts
    /// folded — a subscription's hundreds of rows otherwise fill the screen on opening the page.
    var unfolded: Set<String> = []
    /// The folded subscriptions (Android `GroupsScreen`'s `folded`): open by default, the ids of the
    /// ones the user put away — a subscription's rows live inside its group's fold too. Session-only,
    /// like [unfolded]: a relaunch opens them all again.
    var foldedSubs: Set<String> = []
    /// The folded groups as a set, kept writable for the snapshots' `folded = […]` setup: reading it
    /// gives every group not in [unfolded], writing it flips that.
    var folded: Set<String> {
        get { Set(groups.map(\.id)).subtracting(unfolded) }
        set { unfolded = Set(groups.map(\.id)).subtracting(newValue) }
    }
    var autoActive = false
    var autoSort = false
    var currentId: String?
    var strategy: Strategy = .stable
    var routing = true
    var eco = false
    var ecoSaving = 46
    /// The countries filter in effect (Android `AppSettings.countries`): kept on the settings so it
    /// saves with them; the engine echoes it in every status, and [setCountries] sends it live too.
    var countries: Set<String> {
        get { settings.countries }
        set { settings.countries = newValue }
    }
    /// The engine's active subset (`EngineStatus.activeIds`, Android `Repo.isActive`): the countries
    /// filter, the auto-visibility pool, hidden and insecure servers applied service-side. nil = an
    /// older service — [isActive] falls back to `!disabled`, the pre-R5 behaviour.
    var activeIds: Set<String>?
    /// What the engine reports besides the phase ([apply]): a hand-picked server that stopped
    /// answering, no working servers at all, the next try's moment, its own error text.
    var engineUnanswered = false
    var engineNoServers = false
    var nextRetryAt: Date?
    var engineError: String?
    /// `EngineStatus.paused` (traffic held while no server works — the paused subtitle variants and
    /// the pause card) and when the tunnel last carried traffic (nil = never).
    var enginePaused = false
    var lastConnected: Date?
    /// The network underneath is metered (the engine stretches its work on it, Android `metered`).
    var metered = false
    /// A subscription update is in flight (`EngineStatus.subsUpdating` — the rows' busy state).
    var subsUpdating = false
    /// Live limited-network session counters of the server in use, nil when not on a limited server
    /// (`EngineStatus.limitedActive/Wanted/Limit/Known`): streams connected, wanted, the limit in
    /// effect, the limit the server declared.
    var limitedLive: (active: Int, wanted: Int, limit: Int?, known: Int?)?
    /// Queued for the scan in progress (`EngineStatus.pending`): a stale result dims until its turn.
    var pendingIds: Set<String> = []
    /// Checks actually in flight, also when no full scan is running.
    var probingIds: Set<String> = []
    /// The countries' metrics of the service's `target:"countries"` statistics ([refreshCountryStats]);
    /// nil until the first answer — the popup's used/stable sorts then fall back to local figures.
    var countryStats: [String: CountryStat]?
    /// The pause card's "Got it", remembered app-side (Android's `pause_card_seen` preference).
    var pauseCardSeen: Bool {
        get { UserDefaults.standard.bool(forKey: "pauseCardSeen") }
        set { UserDefaults.standard.set(newValue, forKey: "pauseCardSeen") }
    }
    var scan: ScanProgress?
    /// When the shown results were taken.
    var checkedAt = Date()
    /// Battery use of the app (the Mac measures it): % per hour and its error.
    var batteryRate: Double? = 0.1
    var batteryError: Double? = 0.2
    var batteryMinutes = 50
    var settings = SettingsState()
    var zones: [CustomZone] = []
    /// The zone whose page is open (a dialog over the settings), and what its file had that wasn't understood.
    var openZone: String?
    // The skipped-lines hand-off lives keyed per zone in `zoneLoadNotes` (SettingsView) — a single slot
    // here went to whichever dialog opened next.
    /// The last day's statistics, for the details of a picked server (the service will give its own figures).
    var dayStats: StatsData?
    /// What the last day-figures request asked for and when — apart from the statistics screen's own clock
    /// (a failed try counts too, or a quiet service would be asked every refresh tick).
    var dayStatsKey: String?
    var dayStatsAt = Date.distantPast
    /// The statistics the service answered for the period on screen (real mode; the demo makes its own up).
    var stats: StatsData?
    /// What the last statistics request asked for ("from|to|target") and when — also a failed one — so the
    /// screen's data refreshes at a pace and not every tick.
    var statsKey: String?
    var statsAt = Date.distantPast
    /// The statistics are being loaded (the first time: nothing drawn yet).
    var statsLoading = false
    /// The last statistics request's failure (the screen offers a retry).
    var statsError: String?
    /// The last command's failure (the service's error text, or "didn't answer" while it is unreachable).
    var commandError: String?
    /// The statistics database's size as the service reports it (the settings' "Statistics size").
    var statsBytes: Int64?
    /// Errors kept for reporting (Android `Errors.unreported`).
    var errorsToReport = 0
    /// The bundled core's own version line ("Xray 26.10.21"), asked of `borderless-core` ([fetchCoreVersion]);
    /// "…" until the answer — it spawns the helper, which can take seconds.
    var coreVersion = "…"
    @ObservationIgnored private var coreVersionAsked = false

    /// Asks the bundled core its version once (SharedCode.coreVersion), off the main actor: the helper's
    /// answer can take seconds, and the settings' About page must not freeze for it.
    func fetchCoreVersion() {
        guard !coreVersionAsked else { return }
        coreVersionAsked = true
        Task { coreVersion = await Task.detached { SharedCode.coreVersion }.value }
    }
    /// Routing lists: when they were built and last checked (Android `GeoLists.state`), plus the last
    /// download failure — mirrored out of the unobserved `lastEngine` so a failed update repaints.
    var listsDate = Date().addingTimeInterval(-3 * 86_400)
    var listsChecked = Date().addingTimeInterval(-2 * 3600)
    var listsError: String?
    /// Battery saving's expected share (Android `Eco.estimate`), nil until known.
    var ecoEstimate: Int? = 46
    /// The log (Android `AppLog`): events in the UI language, the detailed log verbatim.
    var events: [LogEvent] = []
    var debugLines: [String] = []

    /// What stays open when coming back to a section: the settings page and search, the log's segment.
    var settingsPage: SettingsPage = .server
    var settingsQuery = ""
    @ObservationIgnored var pendingCountrySelection: Set<String>?
    var countriesRequestPending = false
    var showCountries = false
    var logTab = 0
    var statsTab = 0
    /// The statistics' period, shared by all their pages (Android `StatsPeriod`), and the custom range once chosen.
    var statsPeriod: Period = .day
    var statsRange: ClosedRange<Date>?
    /// A server's or a subscription's statistics in place of the overview; nil = the overview. A new target on the
    /// screen asks for its figures at once (as [section] does).
    var statsTarget: StatsTarget? {
        didSet { if section == .stats, statsTarget != oldValue { statsChanged() } }
    }

    func openStats(_ target: StatsTarget) {
        statsTarget = target
        section = .stats
    }

    /// Groups with this server or subscription selected in the inspector (its settings).
    func openSettings(of id: String) {
        selectedId = id
        section = .groups
    }

    /// The section shown in the window, so other screens can open one (Settings → Log). Opening Statistics asks for
    /// its figures at once — the refresh loop's next tick would be up to 3 s late (owner, 2026-11-07).
    var section: Section = .main {
        didSet { if section != oldValue { backend?.screenChanged() } }
    }

    /// The server picked on the main screen (a click on its cell or its row): selected in the list, ringed on the map.
    var pickedId: String?
    /// Set by the list's own tap ([pickInList]): its row is already under the pointer, so that pick asks for no
    /// scroll — opening the details must not shift the list. The list's scroll handler reads and clears it; a pick
    /// made anywhere else (the map's cells, which write `pickedId` directly) is scrolled into view.
    var pickedFromList = false

    /// The server list's own tap: picks like any other but asks for no scroll into view (see `pickedFromList`).
    /// A tap that changes nothing sets no flag either, so it can't linger into the next pick.
    func pickInList(_ id: String?) {
        pickedFromList = pickedId != id
        pickedId = id
    }

    var current: ServerItem? { servers.first { $0.id == currentId } }

    func groupName(_ id: String) -> String { groups.first { $0.id == id }?.name ?? "" }

    /// displayName's "place in the group" map, group id → server id → index; rebuilt lazily once per `servers`
    /// change. Before it, every row and cell filtered the whole list for its index — O(n²) on the map.
    @ObservationIgnored private var groupIndex: [String: [String: Int]]?

    private var orderInGroup: [String: [String: Int]] {
        if let groupIndex { return groupIndex }
        var built: [String: [String: Int]] = [:]
        for s in servers where built[s.groupId]?[s.id] == nil {
            built[s.groupId, default: [:]][s.id] = built[s.groupId]?.count ?? 0
        }
        groupIndex = built
        return built
    }

    /// What the screens show for a server (Android `Repo.displayName`): its name, or "<group> #N" with the
    /// generic-names setting — N = its place in the user's order within the group, from 1. A name the user
    /// chose wins over "Group #N" when they asked for that.
    func displayName(_ s: ServerItem) -> String {
        guard settings.genericNames else { return s.name }
        if settings.customOverGeneric, let custom = s.customName { return custom }
        guard let n = orderInGroup[s.groupId]?[s.id] else { return s.name }
        let g = groupName(s.groupId)
        return "\(g.isEmpty ? L("own_group") : g) #\(n + 1)"
    }

    /// The server search, shared by the window's list and the menu bar panel: a hit on any name the server can
    /// be known by, whatever the naming mode — the link's own name, the user's custom one, the shown (maybe the
    /// generic "Group #N") one — and on its group, its country (the code and the localized name) and its
    /// protocol. `q` is the query; surrounding whitespace doesn't count, an empty one matches everything.
    func matches(_ s: ServerItem, _ q: String) -> Bool {
        let q = q.trimmingCharacters(in: .whitespaces)
        if q.isEmpty { return true }
        func has(_ v: String?) -> Bool { v?.localizedCaseInsensitiveContains(q) == true }
        return has(s.name) || has(s.linkName) || has(s.customName) || has(displayName(s))
            || has(groupName(s.groupId)) || has(s.country) || has(countryName(s.country))
            || has(s.country.map { shortCountryName($0) }) || has(s.proto)
    }

    /// The statistics screens ask for a fresh answer (a new period picked, a retry after an error).
    func statsChanged() { backend?.statsRefresh() }

    /// Whether the server takes part on the main surfaces — the list, the map, the figures, the
    /// groups' "off" marks (Android `Repo.isActive`, ruling R5): the service's active subset when it
    /// sends one ([activeIds]), `!disabled` when it doesn't (older service → the old behaviour).
    func isActive(_ s: ServerItem) -> Bool { activeIds?.contains(s.id) ?? !s.disabled }

    /// The servers shown on the main screen and checked: the active subset (auto-visibility and the
    /// countries filter are the engine's own exclusions when it reports them).
    var visible: [ServerItem] { servers.filter { isActive($0) } }

    /// The list under the map: answering first (fastest first), never checked next, no reply last
    /// (Android `sortByPing` — the old code lumped untested rows with the failed ones). Inactive
    /// servers don't take part on the main screen (Android `Repo.visible`) — the Groups screen is
    /// where they live.
    var sorted: [ServerItem] {
        visible.sorted { a, b in
            let ra = pingRank(a), rb = pingRank(b)
            if ra != rb { return ra < rb }
            // Equal pings order by the shown name: an unstable sort may swap equal rows between
            // refreshes, and the list visibly jumped. The id is the last resort — two servers can
            // carry the same name.
            guard ra == 0, let x = a.ms, let y = b.ms else { return tiebreak(a, b) }
            return x != y ? x < y : tiebreak(a, b)
        }
    }

    private func tiebreak(_ a: ServerItem, _ b: ServerItem) -> Bool {
        let an = displayName(a), bn = displayName(b)
        return an != bn ? an < bn : a.id < b.id
    }

    /// Servers by group in the user's order, each group a row of cells on the map — the active subset
    /// only, as on Android's heatmap (the same `visible` the list draws).
    var grouped: [[ServerItem]] {
        groups.map { g in visible.filter { $0.groupId == g.id } }.filter { !$0.isEmpty }
    }

    // MARK: Actions (simulated until the service runs the engine)

    func toggle() {
        if let backend {
            userSet["phase"] = Date()
            backend.toggle()
            return
        }
        if phase == .off {
            phase = .searching
            startScan { [weak self] in
                guard let self else { return }
                self.currentId = self.sorted.first?.id
                self.phase = .connected
            }
        } else {
            phase = .off
            scan = nil
        }
    }

    func connect(to server: ServerItem) {
        if let backend {
            strategy = .manual
            userSet["strategy"] = Date()
            // Not optimistic about the phase: the backend marks it "searching" once the service accepts
            // the connect; the hold only keeps a stale engine answer from flipping it back meanwhile.
            userSet["phase"] = Date()
            backend.connect(server.id)
            return
        }
        currentId = server.id
        strategy = .manual
        phase = .connected
    }

    // MARK: Settings of a server / a subscription (Android `ServerSettingsDialog`: only what changed is saved)

    /// Empty restores the link's name (as does typing it back exactly — Android `Repo.renameServer`).
    func rename(server id: String, to name: String) {
        guard let i = servers.firstIndex(where: { $0.id == id }) else { return }
        let link = servers[i].linkName.isEmpty ? servers[i].name : servers[i].linkName
        servers[i].linkName = link
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        servers[i].customName = trimmed.isEmpty || trimmed == SharedCode.title(link) ? nil : trimmed
        servers[i].name = trimmed.isEmpty ? SharedCode.title(link) : trimmed
    }

    /// nil with `manual == false` = detect it again (automatic): the stored country is dropped so the
    /// service resolves it fresh — keeping the old one made "automatic" look chosen (F-MAIN-0025).
    func setCountry(server id: String, _ code: String?, manual: Bool) {
        guard let i = servers.firstIndex(where: { $0.id == id }) else { return }
        servers[i].country = code
        servers[i].countryManual = manual
    }

    func setLimited(server id: String, streams: Int, ignoreLimit: Bool, device: String) {
        guard let i = servers.firstIndex(where: { $0.id == id }) else { return }
        servers[i].limitedStreams = streams
        servers[i].limitedIgnoreLimit = ignoreLimit
        servers[i].limitedDevice = device.trimmingCharacters(in: .whitespaces)
    }

    func rename(subscription id: String, to name: String) {
        guard let i = subscriptions.firstIndex(where: { $0.id == id }) else { return }
        let provider = subscriptions[i].providerName.isEmpty ? subscriptions[i].name : subscriptions[i].providerName
        subscriptions[i].providerName = provider
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        subscriptions[i].name = trimmed.isEmpty ? provider : trimmed
    }

    /// The id a limited-network server sends when the user typed none (Android `Limited.defaultDeviceId`); the real one
    /// comes from the service.
    var defaultDeviceId = "borderless-3f9a1c"

    /// A bundle's hidden-server keys of a subscription ([SharedCode.Subscription.hidden]), still waiting for
    /// the servers they name: those only exist once the service downloaded the subscription (Android hides
    /// them right after `Importer.update` — there the same process owns both sides). Subscription id → keys.
    @ObservationIgnored private var pendingHidden: [String: Set<String>] = [:]

    func pingAll() {
        if let backend {
            backend.pingAll()
            return
        }
        startScan(then: nil)
    }

    /// What the service's engine reports (`EngineStatus`): the phase, the server in use, the pings, the scan running.
    /// When the user last set each of these themselves (2 s): the service's answer that is still on its way must not
    /// flip the button back before it arrives (the connect button visibly bounced Off → Searching → Off). And the
    /// status arrives every 1–3 s, mostly unchanged: every field is written only when it really differs, or each
    /// tick would invalidate every reader of the model (U1.5).
    @ObservationIgnored private var userSet: [String: Date] = [:]
    /// The last status the engine gave ([apply]); the list's refetch restores its pings from it when needed.
    @ObservationIgnored private(set) var lastEngine: EngineStatus?

    private func held(_ key: String) -> Bool {
        Date().timeIntervalSince(userSet[key] ?? .distantPast) < 2
    }

    func apply(_ e: EngineStatus) {
        lastEngine = e
        if !held("phase") {
            let phase: Phase = switch e.phase {
            case "SEARCHING": .searching
            case "CONNECTED": .connected
            case "DIRECT": .direct
            case "PAUSED": .paused
            case "NO_NETWORK": .noNetwork
            case "LIMITED": .limited
            default: .off
            }
            if self.phase != phase { self.phase = phase }
        }
        let current = e.serverId ?? e.selectedId
        if currentId != current { currentId = current }
        if let s = Strategy(engine: e.strategy), !held("strategy"), strategy != s { strategy = s }
        // A service installed from an older build sends none of these: keep what is known.
        if let r = e.routing, !held("routing"), routing != r { routing = r }
        if let x = e.eco, !held("eco"), eco != x { eco = x }
        if ecoEstimate != e.ecoEstimate { ecoEstimate = e.ecoEstimate }
        if let estimate = e.ecoEstimate, ecoSaving != estimate { ecoSaving = estimate }
        if let u = e.listsUpdated, u > 0 {
            let date = Date(timeIntervalSince1970: Double(u) / 1000)
            if listsDate != date { listsDate = date }
        }
        if let c = e.listsChecked, c > 0 {
            let date = Date(timeIntervalSince1970: Double(c) / 1000)
            if listsChecked != date { listsChecked = date }
        }
        if listsError != e.listsError { listsError = e.listsError }
        if let unreported = e.unreported, errorsToReport != unreported { errorsToReport = unreported }
        if let id = e.deviceId, !id.isEmpty, defaultDeviceId != id { defaultDeviceId = id }
        // The rest of the status the screens read (every field optional-safe: an older service's nil
        // keeps the previous behaviour — R5's fallback lives in `isActive` and in `?? false` below).
        if let a = e.activeIds, activeIds != Set(a) { activeIds = Set(a) }
        if let c = e.countries, !countriesRequestPending {
            let echoed = Set(c)
            if pendingCountrySelection == nil || pendingCountrySelection == echoed {
                pendingCountrySelection = nil
                if countries != echoed { countries = echoed }
            }
        }
        if engineUnanswered != e.unanswered { engineUnanswered = e.unanswered }
        if engineNoServers != e.noServers { engineNoServers = e.noServers }
        let retryAt = e.nextRetryAt > 0 ? Date(timeIntervalSince1970: Double(e.nextRetryAt) / 1000) : nil
        if nextRetryAt != retryAt { nextRetryAt = retryAt }
        if engineError != e.error { engineError = e.error }
        let paused = e.paused ?? false
        if enginePaused != paused { enginePaused = paused }
        let connected = (e.lastConnected ?? 0) > 0 ? Date(timeIntervalSince1970: Double(e.lastConnected!) / 1000) : nil
        if lastConnected != connected { lastConnected = connected }
        let metered = e.metered ?? false
        if self.metered != metered { self.metered = metered }
        let subsUpdating = e.subsUpdating ?? false
        if self.subsUpdating != subsUpdating { self.subsUpdating = subsUpdating }
        let live = e.limitedActive.map { (active: $0, wanted: e.limitedWanted ?? 0, limit: e.limitedLimit, known: e.limitedKnown) }
        if live?.active != limitedLive?.active || live?.wanted != limitedLive?.wanted
            || live?.limit != limitedLive?.limit || live?.known != limitedLive?.known { limitedLive = live }
        let pending = Set(e.pending)
        if pendingIds != pending { pendingIds = pending }
        let probing = Set(e.probing)
        if probingIds != probing { probingIds = probing }
        for i in servers.indices {
            let p = e.pings[servers[i].id]
            let checked = p != nil
            if servers[i].ms != p?.ms || servers[i].checked != checked {
                servers[i].ms = p?.ms
                servers[i].checked = checked
            }
        }
        if let newest = e.pings.values.map(\.at).max(), newest > 0 {
            let at = Date(timeIntervalSince1970: Double(newest) / 1000)
            if checkedAt != at { checkedAt = at }
        }
        guard e.scanTotal > 0 else {
            if scan != nil { scan = nil }
            return
        }
        var results: [String: Int?] = [:]
        for (id, p) in e.pings where p.at >= e.scanStartedAt && !pending.contains(id) { results[id] = .some(p.ms) }
        let next = ScanProgress(done: e.scanDone, total: e.scanTotal, probing: Set(e.probing),
                                id: scan?.startedAtMs == e.scanStartedAt ? scan?.id ?? UUID() : UUID(),
                                results: results, startedAtMs: e.scanStartedAt)
        // A new struct per tick; an equal one isn't written or the orbits and every scan reader would
        // re-render over the same numbers (the same scan keeps its orbits' id, decided above).
        if scan != next { scan = next }
    }

    // MARK: Switches (the demo changes its own state; the real one goes to the service, which answers back)

    func setStrategy(_ s: Strategy) {
        backend?.setStrategy(s)
        strategy = s
        userSet["strategy"] = Date()
    }

    func setRouting(_ on: Bool) {
        if settings.routingEnabled != on { settings.routingEnabled = on }
        backend?.setRouting(on)
        routing = on
        userSet["routing"] = Date()
    }

    func setEco(_ on: Bool) {
        backend?.setEco(on)
        eco = on
        userSet["eco"] = Date()
    }

    func setCountries(_ codes: Set<String>) {
        countries = codes
        if backend != nil { pendingCountrySelection = codes }
        backend?.setCountries(codes)
        userSet["countries"] = Date()
    }

    func clearLog() {
        backend?.clearLog()
        events = []
        debugLines = []
    }

    /// The groups screen's auto-visibility (the service rebuilds its pool) and scan-order switches.
    func setAutoActive(_ on: Bool) {
        backend?.setAutoActive(on)
        autoActive = on
    }

    func setAutoSort(_ on: Bool) {
        backend?.setAutoSort(on)
        autoSort = on
    }

    /// A custom range picked on the statistics (nil = another period chosen, the saved one is let go).
    func setStatsRange(_ range: ClosedRange<Date>?) {
        statsRange = range
        if range == nil && statsPeriod == .custom { statsPeriod = .day }
        backend?.setStatsRange(range.map { Int64($0.lowerBound.timeIntervalSince1970 * 1000) } ?? 0,
                               range.map { Int64($0.upperBound.timeIntervalSince1970 * 1000) } ?? 0)
    }

    /// The demo's scan (Android's `Prober.standIn`): four probes at a time, each answering with a made-up ping near the
    /// server's own after a delay that grows with it; a server that gave no reply waits out its timeout.
    @ObservationIgnored private var scanQueue: [String] = []

    private func startScan(then done: (() -> Void)?) {
        let run = ScanProgress(done: 0, total: 0, probing: [])
        scanQueue = sorted.filter { !$0.limited && isActive($0) }.map(\.id)
        scan = run
        scan?.total = scanQueue.count
        Task { @MainActor in
            await withTaskGroup(of: Void.self) { group in
                for _ in 0..<4 { group.addTask { await self.probeWorker(scan: run.id) } }
            }
            guard scan?.id == run.id else { return }
            try? await Task.sleep(for: .milliseconds(300))
            scan = nil
            checkedAt = Date()
            done?()
        }
    }

    private func probeWorker(scan id: UUID) async {
        while scan?.id == id, !scanQueue.isEmpty {
            let sid = scanQueue.removeFirst()
            scan?.probing.insert(sid)
            let usual = servers.first { $0.id == sid }?.ms
            let answer = usual.map { max(8, $0 + Int.random(in: -6...12)) }
            let wait = answer.map { Double($0) * 6 + Double.random(in: 200...900) } ?? 2500
            try? await Task.sleep(for: .milliseconds(Int(wait)))
            guard scan?.id == id else { return }
            scan?.probing.remove(sid)
            scan?.results.updateValue(answer, forKey: sid)
            scan?.done += 1
            if let i = servers.firstIndex(where: { $0.id == sid }) {
                servers[i].ms = answer
                servers[i].checked = true
            }
        }
    }

    /// The window over the real service: nothing made up — the servers come from the service ([load]).
    static func real() -> MainModel {
        let m = MainModel()
        m.phase = .off
        m.groups = [GroupItem(id: StoredGroup.defaultId, name: L("own_group"), isDefault: true)]
        m.batteryRate = nil
        m.batteryError = nil
        m.ecoEstimate = nil
        m.settings.language = languageAtStart
        return m
    }

    static func demo() -> MainModel {
        let m = MainModel()
        m.groups = [GroupItem(id: "own", name: L("own_group"), isDefault: true), GroupItem(id: "work", name: "Work"),
                    GroupItem(id: "sub", name: "Example provider")]
        m.subscriptions = [SubscriptionItem(
            id: "demo-sub", name: "Example provider", groupId: "sub", updatedAt: Date().addingTimeInterval(-42 * 60),
            usedBytes: 22_300_000_000, totalBytes: 100_000_000_000, expire: Date().addingTimeInterval(74 * 86_400),
            providerHours: 12, url: "https://sub.example.com/s/examplePass123")]
        let cities: [(String, String?, Int)] = [
            ("Frankfurt", "DE", 64), ("Amsterdam", "NL", 71), ("Helsinki", "FI", 83), ("Stockholm", "SE", 92),
            ("Warsaw", "PL", 104), ("Paris", "FR", 118), ("London", "GB", 126), ("Zurich", "CH", 139),
            ("Vienna", "AT", 151), ("Istanbul", "TR", 188), ("New York", "US", 214), ("Toronto", "CA", 236),
            ("Tokyo", "JP", 312), ("Singapore", "SG", 344), ("Sydney", "AU", 0),
        ]
        func proto(_ i: Int) -> String { ["VLESS · Reality", "Trojan · WS TLS", "VLESS · XHTTP TLS"][i % 3] }
        var list: [ServerItem] = []
        for (i, c) in cities.prefix(4).enumerated() {
            list.append(ServerItem(id: "s\(i)", name: c.0, country: c.1, groupId: "own", proto: proto(i), ms: c.2 + [3, -2, 5, 1][i]))
        }
        list.append(ServerItem(id: "s5", name: "Office", country: "DE", groupId: "work", proto: proto(5), ms: 97))
        list.append(ServerItem(id: "s6", name: "Backup", country: "NL", groupId: "work", proto: proto(6), ms: 132))
        for (i, c) in cities.dropFirst(4).enumerated() {
            list.append(ServerItem(id: "s\(i + 7)", name: c.0, country: c.1, groupId: "sub", proto: proto(i + 7),
                                   ms: c.2 == 0 ? nil : c.2 + (i % 5) - 2, subscriptionId: "demo-sub"))
        }
        for i in list.indices { list[i].link = demoLink(list[i]) }
        m.servers = list
        m.currentId = "s0"
        m.settings.language = languageAtStart
        m.fillDemoLog()
        m.dayStats = StatsData.demo(m, period: .day)
        m.checkedAt = Date().addingTimeInterval(-120)
        return m
    }
}

extension MainModel {
    /// A day of the log, like the demo's statistics: on at night, a failover in the afternoon, back to the faster one.
    func fillDemoLog() {
        let day = Calendar.current.startOfDay(for: Date())
        func at(_ h: Int, _ m: Int) -> Date { day.addingTimeInterval(TimeInterval(h * 3600 + m * 60)) }
        func ev(_ h: Int, _ m: Int, _ kind: LogEvent.Kind, _ text: String) -> LogEvent {
            LogEvent(id: "demo-\(h):\(m)-\(events.count)", time: at(h, m), kind: kind, text: text)
        }
        events = [
            ev(0, 18, .info, L("ev_tunnel_on")),
            ev(0, 18, .good, L("ann_connected") + " Frankfurt · 64 ms"),
            ev(7, 0, .info, L("ev_tunnel_off")),
            ev(9, 0, .info, L("ev_tunnel_on")),
            ev(9, 1, .info, L("ev_scan", 4, 16, 17, "Frankfurt", 64)),
            ev(9, 1, .good, L("ann_connected") + " Frankfurt · 64 ms"),
            ev(12, 30, .info, L("ev_sub_updated", "Example provider", 11)),
            ev(15, 0, .warn, L("ev_check_failed", "Frankfurt", 1, 2)),
            ev(15, 0, .error, L("ev_lost", "Frankfurt")),
            ev(15, 9, .good, L("ann_switched") + " Amsterdam · 71 ms"),
            ev(18, 0, .good, L("ann_faster", 71, 64) + " Frankfurt"),
            ev(20, 12, .info, L("ev_geo_checked")),
        ].reversed()
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "MM-dd"
        let d = f.string(from: day)
        debugLines = [
            "\(d) 09:00:00.412 [event] \(L("ev_tunnel_on"))",
            "\(d) 09:00:00.418 [Engine] start: strategy Stable, 17 servers, routing on (ru)",
            "\(d) 09:00:00.431 [Tunnel] utun6 172.19.0.1 up, primary service, DNS 172.19.0.2",
            "\(d) 09:00:00.506 [core] Xray 26.10.21 started (pid 4127)",
            "\(d) 09:00:00.913 [probe] Frankfurt: 64 ms",
            "\(d) 09:00:00.977 [probe] Amsterdam: 71 ms",
            "\(d) 09:00:01.204 [probe] Sydney: timeout after 6 s",
            "\(d) 09:00:04.110 [Engine] scan done in 4 s: 16 of 17, best Frankfurt 64 ms",
            "\(d) 12:30:02.640 [Importer] Example provider: 11 servers, 0 new, 0 removed (example.com)",
            "\(d) 15:00:12.004 [Engine] health: no reply in 8 s (usual 70 ms), suspicion at 4.1 s",
            "\(d) 15:00:12.006 [xray] [Warning] proxy/vless/outbound: failed to dial 203.0.113.24:443 > i/o timeout",
            "\(d) 15:09:40.552 [Engine] failover: Amsterdam (fresh answer 71 ms), place 4 of 17",
            "\(d) 18:00:03.318 [Engine] faster: Frankfurt 64 ms vs Amsterdam 71 ms in two rescans, switching",
            "\(d) 20:12:09.770 [Engine] routing lists: no changes (checked 2 sources)",
        ]
    }
}

/// A made-up share link for a demo server (documentation names and ids only).
func demoLink(_ s: ServerItem) -> String {
    let host = s.name.lowercased().replacingOccurrences(of: " ", with: "-") + ".example.com"
    let name = s.name.addingPercentEncoding(withAllowedCharacters: .urlFragmentAllowed) ?? s.name
    switch s.proto {
    case let p where p.hasPrefix("Trojan"): return "trojan://examplePass123@\(host):443?type=ws&security=tls&path=%2Fws#\(name)"
    case let p where p.contains("XHTTP"):
        return "vless://11111111-2222-3333-4444-555555555555@\(host):443?type=xhttp&security=tls&path=%2Fx#\(name)"
    default: return "vless://11111111-2222-3333-4444-555555555555@\(host):443?security=reality&sni=www.example.com&fp=chrome#\(name)"
    }
}

/// What adding text did (Android `Importer.Result`): how many servers and subscriptions came in, and each line that
/// didn't with the reason in plain words.
struct ImportResult: Equatable {
    var servers = 0
    var subscriptions = 0
    var problems: [Problem] = []

    struct Problem: Equatable, Hashable {
        var item: String
        var text: String
    }

    var message: String {
        var parts: [String] = []
        if servers > 0 { parts.append(L("import_added", servers)) }
        if subscriptions > 0 { parts.append(L("import_subs", subscriptions)) }
        return parts.isEmpty ? L("import_nothing") : parts.joined(separator: ", ")
    }
}

extension MainModel {
    /// Adds the links, Border(less) bundles and subscriptions in `text`, read by the code shared with Android (no
    /// download yet: a subscription waits for the engine in the service). `groupId` nil = automatic: links to the
    /// default group, each subscription to a new group of its own, a bundle's groups by their names.
    func importText(_ text: String, into groupId: String?) -> ImportResult {
        var r = ImportResult()
        let parsed = SharedCode.parse(text)
        r.problems = parsed.problems.map { .init(item: $0.item, text: $0.text) }
        let defaultId = groups.first(where: \.isDefault)?.id ?? StoredGroup.defaultId
        // The dedup set, not a `contains` per parsed server (O(n·m)); `insert` catches doubles inside the text too.
        var known = Set(servers.map(\.id))
        for g in parsed.groups {
            let target: String = groupId ?? {
                if g.default == true { return defaultId }
                guard let name = g.name else { return defaultId }
                if let same = groups.first(where: { $0.name == name }) { return same.id }
                let new = GroupItem(id: newGroupId(), name: name)
                groups.append(new)
                return new.id
            }()
            for s in g.servers ?? [] where known.insert(s.id).inserted {
                let info = linkInfo(s.link)
                servers.append(ServerItem(id: s.id, name: s.title, country: s.country, groupId: target,
                                          proto: protocolLabel(info?.protocol ?? s.protocol),
                                          ms: nil, checked: false, limited: s.limited == true, disabled: s.hidden == true,
                                          linkName: s.name, limitedMax: s.limited == true ? limitedMaxWorkers(s.link) : nil,
                                          // The parser's `insecure` is `Insecurity.of` itself — the same verdict
                                          // the service's `servers` reply sends (not a read of the raw link).
                                          insecure: Set((s.insecure ?? []).compactMap { InsecureIssue(wireName: $0) }),
                                          host: info?.host ?? "", port: info?.port ?? 0,
                                          link: s.link))
                r.servers += 1
            }
            for sub in g.subscriptions ?? [] {
                if subscriptions.contains(where: { $0.url == sub.url }) {
                    r.problems.append(.init(item: URL(string: sub.url)?.host ?? "?", text: L("imp_sub_exists")))
                    continue
                }
                let host = URL(string: sub.url)?.host ?? sub.url
                var into = groupId ?? (g.name == nil && g.default != true ? nil : target)
                if into == nil {
                    // A group of its own, nameless like Android's (`Group(name = null)`): it takes the
                    // subscription's name and is removed with it (the stale-group findings).
                    let new = GroupItem(id: newGroupId(), name: sub.name ?? host, nameless: true)
                    groups.append(new)
                    into = new.id
                }
                let name = sub.name ?? host
                let id = String(UUID().uuidString.prefix(8)).lowercased()
                subscriptions.append(SubscriptionItem(id: id, name: name,
                                                      groupId: into!, providerName: name, url: sub.url))
                // The bundle's keys of the subscription's hidden servers: applied once the service's
                // download brings the servers ([hidePendingKeys]), as `Repo.hideByKeys` does on Android.
                if let hidden = sub.hidden, !hidden.isEmpty { pendingHidden[id] = Set(hidden) }
                r.subscriptions += 1
            }
        }
        return r
    }
}

extension MainModel {
    /// What the service stores (Android's field names): the link's name and the user's own one apart;
    /// a nameless group is written without a name so the subscription keeps naming it (Android
    /// `Group.name == nil`) instead of the resolved name freezing over it.
    var serverList: ServerList {
        ServerList(
            groups: groups.map { StoredGroup(id: $0.id, name: $0.isDefault || $0.nameless ? nil : $0.name) },
            servers: servers.map { s in
                let link = s.linkName.isEmpty ? s.name : s.linkName
                return StoredServer(id: s.id, name: link, link: s.link, proto: s.proto, groupId: s.groupId,
                                    subscriptionId: s.subscriptionId, country: s.country, countryManual: s.countryManual,
                                    disabled: s.disabled, customName: s.customName ?? "",
                                    limitedStreams: s.limitedStreams, limitedDevice: s.limitedDevice,
                                    limitedIgnoreLimit: s.limitedIgnoreLimit)
            },
            subscriptions: subscriptions.map { sub in
                let provider = sub.providerName.isEmpty ? sub.name : sub.providerName
                return StoredSubscription(id: sub.id, name: provider, url: sub.url, groupId: sub.groupId,
                                          customName: sub.name == provider ? nil : sub.name, lastError: sub.lastError,
                                          // A bundle's hidden marks ride the save ([pendingHidden], drained by
                                          // `hidePendingKeys`): the service keeps them root-only until the
                                          // subscription's first download — they survive an app quit in between.
                                          hiddenKeys: pendingHidden[sub.id].map { Array($0) })
            })
    }

    /// Shows the stored list (the default group is always there).
    func load(_ list: ServerList) {
        // A list the service still carries hidden marks for (a save landed, the download hasn't):
        // keep them pending here too, so `hidePendingKeys` applies them as well.
        for sub in list.subscriptions where sub.hiddenKeys?.isEmpty == false {
            pendingHidden[sub.id] = Set(sub.hiddenKeys!)
        }
        let shown = items(from: list)
        groups = shown.groups
        servers = shown.servers
        subscriptions = shown.subscriptions
    }

    private func linkInfo(_ link: String) -> SharedCode.ServerInfo? {
        if let cached = linkInfoCache[link] { return cached }
        guard let info = SharedCode.serverInfo(link) else { return nil }
        linkInfoCache[link] = info
        return info
    }

    /// The stored list as the shown items: [load] assigns them whole, the 15 s refetch builds them first
    /// to compare — a byte-identical answer must not rebuild every row.
    func items(from list: ServerList) -> (groups: [GroupItem], servers: [ServerItem], subscriptions: [SubscriptionItem]) {
        let links = Set(list.servers.map(\.link))
        linkInfoCache = linkInfoCache.filter { links.contains($0.key) }
        var g = list.groups.map { stored in
            // A stored group without a name is a nameless one (Android `Group.name == nil`): it shows
            // its first subscription's name and stays nameless on write-back.
            GroupItem(id: stored.id, name: stored.id == StoredGroup.defaultId ? L("own_group")
                      : stored.name ?? list.subscriptions.first { $0.groupId == stored.id }.map { $0.customName ?? $0.name } ?? L("own_group"),
                      nameless: stored.id != StoredGroup.defaultId && stored.name == nil,
                      isDefault: stored.id == StoredGroup.defaultId)
        }
        if !g.contains(where: \.isDefault) { g.insert(GroupItem(id: StoredGroup.defaultId, name: L("own_group"), isDefault: true), at: 0) }
        let s = list.servers.map { s in
            let limited = SharedCode.isLimited(s.link)
            let whereabouts = linkInfo(s.link)
            return ServerItem(id: s.id, name: s.customName.isEmpty ? SharedCode.title(s.name) : s.customName, country: s.country,
                              groupId: s.groupId, proto: protocolLabel(whereabouts?.protocol ?? s.proto), ms: nil, checked: false, limited: limited,
                              subscriptionId: s.subscriptionId, disabled: s.disabled, linkName: s.name,
                              customName: s.customName.isEmpty ? nil : s.customName,
                              countryManual: s.countryManual, limitedStreams: s.limitedStreams,
                              limitedIgnoreLimit: s.limitedIgnoreLimit, limitedDevice: s.limitedDevice,
                              limitedMax: limited ? limitedMaxWorkers(s.link) : nil,
                              // The service's own `Insecurity.of` verdict is the truth (it reads the
                              // built outbound, as Android does — the link read flags servers Android
                              // doesn't); `linkInsecureIssues` stands in only when an older service
                              // sends no `insecure` field.
                              insecure: s.insecure.map { Set($0.compactMap { InsecureIssue(wireName: $0) }) }
                                  ?? linkInsecureIssues(s.link),
                              host: whereabouts?.host ?? "",
                              port: whereabouts?.port ?? 0, link: s.link)
        }
        let subs = list.subscriptions.map { sub in
            let used = (sub.uploadBytes ?? 0) + (sub.downloadBytes ?? 0)
            return SubscriptionItem(id: sub.id, name: sub.customName ?? sub.name, groupId: sub.groupId,
                                    // The service writes 0 for "never updated" — a real epoch date it is not.
                                    updatedAt: (sub.updatedAt ?? 0) > 0 ? Date(timeIntervalSince1970: Double(sub.updatedAt!) / 1000) : nil,
                                    usedBytes: used > 0 ? used : nil,
                                    totalBytes: sub.totalBytes,
                                    expire: sub.expire.map { Date(timeIntervalSince1970: Double($0)) },
                                    providerHours: sub.providerHours, viaServer: sub.viaServer ?? false,
                                    providerName: sub.name, lastError: sub.lastError?.isEmpty == false ? sub.lastError : nil,
                                    url: sub.url)
        }
        return (g, s, subs)
    }

    /// Deletes the subscription and its servers, and — Android `Repo.removeSubscription` — a nameless
    /// group made just for it that has nothing left (a named or still-used group stays).
    func removeSubscription(_ id: String) {
        let groupId = subscriptions.first { $0.id == id }?.groupId
        servers.removeAll { $0.subscriptionId == id }
        subscriptions.removeAll { $0.id == id }
        cleanOrphanGroup(groupId)
    }

    /// A nameless group with no members left goes away with what it was made for (Android's cleanup in
    /// `removeSubscription`/`moveSubscription`); the default group and any named one stay.
    private func cleanOrphanGroup(_ id: String?) {
        guard let id, id != StoredGroup.defaultId, let g = groups.first(where: { $0.id == id }), g.nameless,
              !servers.contains(where: { $0.groupId == id }), !subscriptions.contains(where: { $0.groupId == id }) else { return }
        groups.removeAll { $0.id == id }
        unfolded.remove(id)
    }

    /// A nameless group keeps its shown name as a real one before the subscription that names it
    /// leaves (Android `Repo.freezeGroupName`) — then the group survives the move as a named one.
    private func freezeGroupName(_ id: String) {
        guard let i = groups.firstIndex(where: { $0.id == id }), groups[i].nameless, !groups[i].isDefault else { return }
        groups[i].nameless = false
    }

    /// Moves a subscription and all its servers to another group (Android `Repo.moveSubscription`):
    /// the group it leaves keeps its name if other members remain (or goes away when empty), the group
    /// it joins gains a name first if its own one is borrowed.
    func moveSubscription(_ id: String, to groupId: String) {
        guard let i = subscriptions.firstIndex(where: { $0.id == id }),
              subscriptions[i].groupId != groupId, groups.contains(where: { $0.id == groupId }) else { return }
        let from = subscriptions[i].groupId
        freezeGroupName(groupId)
        if subscriptions.contains(where: { $0.groupId == from && $0.id != id })
            || servers.contains(where: { $0.groupId == from && $0.subscriptionId != id }) {
            freezeGroupName(from)
        }
        subscriptions[i].groupId = groupId
        for j in servers.indices where servers[j].subscriptionId == id { servers[j].groupId = groupId }
        cleanOrphanGroup(from)
    }

    /// Asks the service for the countries' metrics (`target:"countries"` — the popup's used/stable
    /// sorts): a nil answer — an older service without the target — leaves [countryStats] untouched
    /// and the popup falls back to its local figures.
    func refreshCountryStats() {
        guard backend != nil else { return }
        Task { @MainActor in
            if let json = await ServiceClient.send(Request(.stats, fromMs: 0, toMs: 0, target: "countries"), timeoutSec: 15)?.stats,
               let stats = App.countryStats(json: json), countryStats != stats {
                countryStats = stats
            }
        }
    }

    /// Hides the subscription servers a bundle's keys name ([pendingHidden]) — run after a service list
    /// lands, as an edit of `servers`, so the usual save carries the marks back to the service.
    func hidePendingKeys() {
        guard !pendingHidden.isEmpty else { return }
        // A subscription that went away takes its keys along.
        pendingHidden = pendingHidden.filter { id, _ in subscriptions.contains { $0.id == id } }
        // One key can stand for several servers (a subscription that lists the same endpoint twice):
        // every match hides, so the key leaves the pending set only once all its servers are marked.
        for i in servers.indices {
            guard let sub = servers[i].subscriptionId, let keys = pendingHidden[sub] else { continue }
            let key = SharedCode.shareKey(servers[i].link)
            if keys.contains(key) { servers[i].disabled = true }
        }
        for (sub, keys) in pendingHidden {
            let marked = Set(servers.filter { $0.subscriptionId == sub && $0.disabled }.map { SharedCode.shareKey($0.link) })
            let left = keys.subtracting(marked)
            pendingHidden[sub] = left.isEmpty ? nil : left
        }
    }
}

/// The server's own stream limit a limited-network link declares — `LimitedLink.parse`'s `maxWorkers`: the query
/// forms' `max_workers` / `maxWorkers` / `max-workers`, or the base64-JSON form's same field (the colon form has
/// none). The engine reports no live limit on the Mac, so the link is the row's only source (Android shows
/// `Limited.maxWorkers(server) ?? live serverLimit`).
func limitedMaxWorkers(_ link: String) -> Int? {
    let s = link.trimmingCharacters(in: .whitespacesAndNewlines)
    guard let at = s.range(of: "://"), ["wdtt", "qwdtt"].contains(s[..<at.lowerBound].lowercased()) else { return nil }
    let payload = String(s[at.upperBound...]).components(separatedBy: "#").first ?? ""
    let keys = ["max_workers", "maxWorkers", "max-workers"]
    func positive(_ raw: String?) -> Int? {
        guard let n = raw.flatMap({ Int($0.trimmingCharacters(in: .whitespaces)) }), n > 0 else { return nil }
        return n
    }
    if let query = payload.split(separator: "?", maxSplits: 1).dropFirst().first {
        for pair in query.split(separator: "&") {
            let kv = pair.split(separator: "=", maxSplits: 1).map(String.init)
            if kv.count == 2, keys.contains(kv[0].removingPercentEncoding ?? kv[0]), let n = positive(kv[1].removingPercentEncoding) {
                return n
            }
        }
        return nil  // a link with a query holds the limit there or nowhere
    }
    // The base64-JSON form (wdtt://<base64>): its payload never holds a '?'.
    var b64 = payload.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
    while b64.count % 4 != 0 { b64 += "=" }
    guard let data = Data(base64Encoded: b64),
          let o = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return nil }
    for k in keys {
        if let n = positive((o[k] as? String) ?? (o[k] as? Int).map { String($0) }) { return n }
    }
    return nil
}

// MARK: The service's settings and zones (real mode; Android `AppSettings` fields the Mac shows)

/// What the service keeps (`AppSettings` as JSON): only what the Mac's settings screen shows. The language and
/// "open at login" stay the app's own.
struct ServiceSettings: Decodable {
    var strategy: String?
    var defaultStrategy: String?
    var autoActive: Bool?
    var autoSort: Bool?
    var avoidInsecure: Bool?
    var pingAllSwitches: Bool?
    var limitedAuto: Bool?
    var failThreshold: Int?
    var rescanIntervalMin: Int?
    var switchGainPercent: Int?
    var switchGainMs: Int?
    var minStayMin: Int?
    var healthIntervalSec: Int?
    var latencyCheckMin: Int?
    var noServerAction: String?
    var retryIntervalSec: Int?
    var pauseScansScreenOff: Bool?
    var carefulScans: Bool?
    var probeTimeoutSec: Int?
    var probeConcurrency: Int?
    var testUrl: String?
    var ecoWithSaver: Bool?
    var ecoStrategy: Bool?
    var routingEnabled: Bool?
    var routingMode: String?
    var regions: [String]?
    var adFilter: Bool?
    var directFirst: Bool?
    var directDomains: [String]?
    var proxyDomains: [String]?
    var geoUpdateDays: Int?
    var subUpdateHours: Int?
    var userAgent: String?
    var sendDeviceModel: Bool?
    var remoteDns: String?
    var directDns: String?
    var ipv6: Bool?
    var fragment: Bool?
    var mux: Bool?
    var mtu: Int?
    var genericNames: Bool?
    var customOverGeneric: Bool?
    var confirmShare: Bool?
    var notifyMode: String?
    var verboseLog: Bool?
    var statsDays: Int?
    var autoUpdate: Bool?
    /// The palette's id (`AppSettings.palette`), e.g. "coral"; travels like the theme.
    var palette: String?
    /// The theme travels like on Android (the service owns the settings): the dark variant and whether the
    /// system's appearance decides.
    var darkTheme: Bool?
    var themeFollowsSystem: Bool?
    var statsFrom: Int64?
    var statsTo: Int64?
    /// The countries filter as the settings keep it (`AppSettings.countries`; the engine echoes it in
    /// its status too — both channels read it back, F-MAIN-0034).
    var countries: [String]?
    /// The countries popup's order (`AppSettings.countrySort`).
    var countrySort: String?
}

extension Strategy {
    var jsonName: String {
        switch self {
        case .stable: "STABLE"
        case .fastest: "FASTEST"
        case .failover: "FAILOVER"
        case .manual: "MANUAL"
        }
    }
}

/// The fields edited through the settings screen, as they go to the service and come back.
enum SettingsJson {
    static func strategy(_ name: String?) -> Strategy? { Strategy(engine: name ?? "") }

    @MainActor
    static func apply(_ s: ServiceSettings, to m: MainModel) {
        var v = m.settings
        // The live `strategy` is not read here: it comes with the engine's status — writing it into the
        // default would make every Manual pick the new default.
        if let x = strategy(s.defaultStrategy) { v.defaultStrategy = x }
        if let x = s.autoActive { m.autoActive = x }
        if let x = s.autoSort { m.autoSort = x }
        if let x = s.avoidInsecure { v.avoidInsecure = x }
        if let x = s.pingAllSwitches { v.pingAllSwitches = x }
        if let x = s.limitedAuto { v.limitedAuto = x }
        if let x = s.failThreshold { v.failThreshold = x }
        if let x = s.rescanIntervalMin { v.rescanIntervalMin = x }
        if let x = s.switchGainPercent { v.switchGainPercent = x }
        if let x = s.switchGainMs { v.switchGainMs = x }
        if let x = s.minStayMin { v.minStayMin = x }
        if let x = s.healthIntervalSec { v.healthIntervalSec = x }
        if let x = s.latencyCheckMin { v.latencyCheckMin = x }
        if let x = s.noServerAction { v.noServerAction = x == "DIRECT" ? .direct : .pause }
        if let x = s.retryIntervalSec { v.retryIntervalSec = x }
        if let x = s.pauseScansScreenOff { v.pauseScansScreenOff = x }
        if let x = s.carefulScans { v.carefulScans = x }
        if let x = s.probeTimeoutSec { v.probeTimeoutSec = x }
        if let x = s.probeConcurrency { v.probeConcurrency = x }
        if let x = s.testUrl { v.testUrl = orDefault(x, SettingsState.defaultTestUrl) }
        if let x = s.ecoWithSaver { v.ecoWithSaver = x }
        if let x = s.ecoStrategy { v.ecoStrategy = x }
        if let x = s.routingEnabled { v.routingEnabled = x }
        if let x = s.routingMode { v.routingMode = x == "LISTED_ONLY" ? .listedOnly : .regionDirect }
        if let x = s.regions { v.regions = Set(x) }
        if let x = s.adFilter { v.adFilter = x }
        if let x = s.directFirst { v.directFirst = x }
        if let x = s.directDomains { v.directDomains = x.joined(separator: "\n") }
        if let x = s.proxyDomains { v.proxyDomains = x.joined(separator: "\n") }
        if let x = s.geoUpdateDays { v.geoUpdateDays = x }
        if let x = s.subUpdateHours { v.subUpdateHours = x }
        if let x = s.userAgent { v.userAgent = orDefault(x, SettingsState.defaultUserAgent) }
        if let x = s.sendDeviceModel { v.sendDeviceModel = x }
        if let x = s.remoteDns { v.remoteDns = orDefault(x, SettingsState.defaultRemoteDns) }
        if let x = s.directDns { v.directDns = x }
        if let x = s.ipv6 { v.ipv6 = x }
        if let x = s.fragment { v.fragment = x }
        if let x = s.mux { v.mux = x }
        if let x = s.mtu { v.mtu = x }
        if let x = s.genericNames { v.genericNames = x }
        if let x = s.customOverGeneric { v.customOverGeneric = x }
        if let x = s.confirmShare { v.confirmShare = x }
        if let x = s.notifyMode { v.notifyMode = x == "OFF" ? .off : x == "ALWAYS" ? .always : .unclear }
        if let x = s.verboseLog { v.verboseLog = x }
        if let x = s.statsDays { v.statsDays = x }
        if let x = s.autoUpdate { v.autoUpdate = x }
        if let x = s.darkTheme { v.darkTheme = x }
        if let x = s.themeFollowsSystem { v.themeFollowsSystem = x }
        if let x = s.palette { v.palette = x }
        if let x = s.countries { v.countries = Set(x) }
        if let x = s.countrySort, CountrySortJson(rawValue: x) != nil { v.countrySort = x }
        if let f = s.statsFrom, let t = s.statsTo, t > f {
            m.statsRange = Date(timeIntervalSince1970: Double(f) / 1000)...Date(timeIntervalSince1970: Double(t) / 1000)
            m.statsPeriod = .custom
        }
        m.settings = v
    }

    private static func notifyName(_ v: NotifyMode) -> String {
        switch v {
        case .off: "OFF"
        case .unclear: "UNCLEAR"
        case .always: "ALWAYS"
        }
    }

    /// The fields the settings screen edits, as the service keeps them (names of Android's `AppSettings`).
    static let fieldNames: Set<String> = [
        "defaultStrategy", "avoidInsecure", "pingAllSwitches", "limitedAuto", "failThreshold", "rescanIntervalMin",
        "switchGainPercent", "switchGainMs", "minStayMin", "healthIntervalSec", "latencyCheckMin", "noServerAction",
        "retryIntervalSec", "pauseScansScreenOff", "carefulScans", "probeTimeoutSec", "probeConcurrency", "testUrl",
        "ecoWithSaver", "ecoStrategy", "routingEnabled", "routingMode", "regions", "adFilter", "directFirst",
        "directDomains", "proxyDomains", "geoUpdateDays", "subUpdateHours", "userAgent", "sendDeviceModel",
        "remoteDns", "directDns", "ipv6", "fragment", "mux", "mtu", "genericNames", "customOverGeneric",
        "confirmShare", "notifyMode", "verboseLog", "statsDays", "autoUpdate", "darkTheme", "themeFollowsSystem",
        "palette", "statsFrom", "statsTo", "countries", "countrySort",
    ]

    /// The settings as a whole, in the service's JSON (a fresh load compares its fields against this); the statistics'
    /// custom range travels with them, as Android keeps it in the settings.
    static func full(_ v: SettingsState, range: ClosedRange<Date>?) -> [String: Any] {
        var o: [String: Any] = [
            "defaultStrategy": v.defaultStrategy.jsonName,
            "avoidInsecure": v.avoidInsecure,
            "pingAllSwitches": v.pingAllSwitches,
            "limitedAuto": v.limitedAuto,
            "failThreshold": v.failThreshold,
            "rescanIntervalMin": v.rescanIntervalMin,
            "switchGainPercent": v.switchGainPercent,
            "switchGainMs": v.switchGainMs,
            "minStayMin": v.minStayMin,
            "healthIntervalSec": v.healthIntervalSec,
            "latencyCheckMin": v.latencyCheckMin,
            "noServerAction": v.noServerAction == .direct ? "DIRECT" : "PAUSE",
            "retryIntervalSec": v.retryIntervalSec,
            "pauseScansScreenOff": v.pauseScansScreenOff,
            "carefulScans": v.carefulScans,
            "probeTimeoutSec": v.probeTimeoutSec,
            "probeConcurrency": v.probeConcurrency,
            "testUrl": orDefault(v.testUrl, SettingsState.defaultTestUrl),
            "ecoWithSaver": v.ecoWithSaver,
            "ecoStrategy": v.ecoStrategy,
            "routingEnabled": v.routingEnabled,
            "routingMode": v.routingMode == .listedOnly ? "LISTED_ONLY" : "REGION_DIRECT",
            // Sorted: a Set's order isn't stable, and the diff below compares rendered text — an unsorted
            // array would look changed on its own and re-send the patch.
            "regions": v.regions.sorted(),
            "adFilter": v.adFilter,
            "directFirst": v.directFirst,
            "directDomains": v.directDomains.split(whereSeparator: \.isNewline).map(String.init).filter { !$0.isEmpty },
            "proxyDomains": v.proxyDomains.split(whereSeparator: \.isNewline).map(String.init).filter { !$0.isEmpty },
            "geoUpdateDays": v.geoUpdateDays,
            "subUpdateHours": v.subUpdateHours,
            "userAgent": orDefault(v.userAgent, SettingsState.defaultUserAgent),
            "sendDeviceModel": v.sendDeviceModel,
            "remoteDns": orDefault(v.remoteDns, SettingsState.defaultRemoteDns),
            "directDns": v.directDns,
            "ipv6": v.ipv6,
            "fragment": v.fragment,
            "mux": v.mux,
            "mtu": v.mtu,
            "genericNames": v.genericNames,
            "customOverGeneric": v.customOverGeneric,
            "confirmShare": v.confirmShare,
            "notifyMode": notifyName(v.notifyMode),
            "verboseLog": v.verboseLog,
            "statsDays": v.statsDays,
            "autoUpdate": v.autoUpdate,
            "darkTheme": v.darkTheme,
            "themeFollowsSystem": v.themeFollowsSystem,
            "palette": v.palette,
            "countries": v.countries.sorted(),
            "countrySort": v.countrySort,
        ]
        if let r = range {
            o["statsFrom"] = Int64(r.lowerBound.timeIntervalSince1970 * 1000)
            o["statsTo"] = Int64(r.upperBound.timeIntervalSince1970 * 1000)
        }
        return o
    }
}

extension MainModel {
    /// The service's settings (once at start; the app's own fields — language, login — stay).
    func loadSettings(_ json: String) {
        guard let s = try? JSONDecoder().decode(ServiceSettings.self, from: Data(json.utf8)) else { return }
        SettingsJson.apply(s, to: self)
        if settings.language.isEmpty { settings.language = languageAtStart }
        // The theme came with the settings: before this the system's appearance ruled (the fresh default).
        applyAppearance(settings)
        // The palette came with them too (Android `BorderlessApp.applyScheme` on the settings flow).
        PaletteStore.shared.select(settings.palette)
    }

}

// MARK: The service's statistics (the same shapes the demo's `StatsData.demo` fills)

/// One row of what the target subscription's provider reported.
struct UsageRow {
    var at: Date
    var used: Int64?
    var total: Int64?
}

extension StatsData {
    @MainActor
    init?(json: String, model: MainModel) {
        struct Raw: Decodable {
            var from: Int64
            var to: Int64
            var dataStart: Int64?
            var phases: [Span] = []
            var scans: [Scan] = []
            var best: [Point] = []
            var pings: [String: [Point]] = [:]
            var servers: [Stat] = []
            var trafficProxy: Int64 = 0
            var trafficDirect: Int64 = 0
            var trafficByServer: [String: Int64] = [:]
            var losses = 0
            var failover = 0
            var faster = 0
            var manual = 0
            var foundPlace: Double?
            var foundOf: Int?
            var foundCount = 0
            var healthMedian: Int?
            var healthP95: Int?
            var healthJitter: Int?
            var states: [State] = []
            var histograms: [String: [Int]] = [:]
            var usage: [Usage] = []

            /// Only the envelope (`from`, `to`) is required; every other field reads as missing. A reply
            /// missing an additive field — one a newer or older service build doesn't send — used to fail
            /// the whole decode and report "didn't answer". A "{}" — the service's own "statistics failed"
            /// answer — still fails here, and the screen tells a bad reply apart from none.
            init(from decoder: Decoder) throws {
                let c = try decoder.container(keyedBy: CodingKeys.self)
                from = try c.decode(Int64.self, forKey: .from)
                to = try c.decode(Int64.self, forKey: .to)
                dataStart = try c.decodeIfPresent(Int64.self, forKey: .dataStart)
                phases = try c.decodeIfPresent([Span].self, forKey: .phases) ?? []
                scans = try c.decodeIfPresent([Scan].self, forKey: .scans) ?? []
                best = try c.decodeIfPresent([Point].self, forKey: .best) ?? []
                pings = try c.decodeIfPresent([String: [Point]].self, forKey: .pings) ?? [:]
                servers = try c.decodeIfPresent([Stat].self, forKey: .servers) ?? []
                trafficProxy = try c.decodeIfPresent(Int64.self, forKey: .trafficProxy) ?? 0
                trafficDirect = try c.decodeIfPresent(Int64.self, forKey: .trafficDirect) ?? 0
                trafficByServer = try c.decodeIfPresent([String: Int64].self, forKey: .trafficByServer) ?? [:]
                losses = try c.decodeIfPresent(Int.self, forKey: .losses) ?? 0
                failover = try c.decodeIfPresent(Int.self, forKey: .failover) ?? 0
                faster = try c.decodeIfPresent(Int.self, forKey: .faster) ?? 0
                manual = try c.decodeIfPresent(Int.self, forKey: .manual) ?? 0
                foundPlace = try c.decodeIfPresent(Double.self, forKey: .foundPlace)
                foundOf = try c.decodeIfPresent(Int.self, forKey: .foundOf)
                foundCount = try c.decodeIfPresent(Int.self, forKey: .foundCount) ?? 0
                healthMedian = try c.decodeIfPresent(Int.self, forKey: .healthMedian)
                healthP95 = try c.decodeIfPresent(Int.self, forKey: .healthP95)
                healthJitter = try c.decodeIfPresent(Int.self, forKey: .healthJitter)
                states = try c.decodeIfPresent([State].self, forKey: .states) ?? []
                histograms = try c.decodeIfPresent([String: [Int]].self, forKey: .histograms) ?? [:]
                usage = try c.decodeIfPresent([Usage].self, forKey: .usage) ?? []
            }

            enum CodingKeys: String, CodingKey {
                case from, to, dataStart, phases, scans, best, pings, servers
                case trafficProxy, trafficDirect, trafficByServer, losses, failover, faster, manual
                case foundPlace, foundOf, foundCount, healthMedian, healthP95, healthJitter
                case states, histograms, usage
            }

            struct Span: Decodable { var start: Int64; var end: Int64; var phase: String; var server: String? }
            struct Scan: Decodable { var t: Int64; var total: Int; var answered: Int }
            struct Point: Decodable { var t: Int64; var ms: Int? }
            /// `id` names the server; the numbers read as missing so an additive field can't fail the row.
            struct Stat: Decodable {
                var id: String
                var availability: Double?
                var avg: Int?
                var samples = 0
                var usage: Int64 = 0
                var chosen = 0

                init(from decoder: Decoder) throws {
                    let c = try decoder.container(keyedBy: CodingKeys.self)
                    id = try c.decode(String.self, forKey: .id)
                    availability = try c.decodeIfPresent(Double.self, forKey: .availability)
                    avg = try c.decodeIfPresent(Int.self, forKey: .avg)
                    samples = try c.decodeIfPresent(Int.self, forKey: .samples) ?? 0
                    usage = try c.decodeIfPresent(Int64.self, forKey: .usage) ?? 0
                    chosen = try c.decodeIfPresent(Int.self, forKey: .chosen) ?? 0
                }

                enum CodingKeys: String, CodingKey { case id, availability, avg, samples, usage, chosen }
            }
            struct State: Decodable { var start: Int64; var end: Int64; var state: String }
            struct Usage: Decodable { var t: Int64; var used: Int64?; var total: Int64?; var expire: Int64? }
        }
        guard let raw = try? JSONDecoder().decode(Raw.self, from: Data(json.utf8)) else { return nil }
        func date(_ ms: Int64) -> Date { Date(timeIntervalSince1970: Double(ms) / 1000) }
        func phase(_ name: String) -> Phase {
            switch name {
            case "SEARCHING": .searching
            case "CONNECTED": .connected
            case "DIRECT": .direct
            case "PAUSED": .paused
            case "NO_NETWORK": .noNetwork
            case "LIMITED": .limited
            default: .off
            }
        }
        self.init()
        from = date(raw.from)
        to = date(raw.to)
        phases = raw.phases.map { PhaseSpan(from: date($0.start), to: date($0.end), phase: phase($0.phase), server: $0.server) }
        scans = raw.scans.map { ScanPoint(at: date($0.t), total: $0.total, answered: $0.answered) }
        best = raw.best.map { PingPoint(at: date($0.t), ms: $0.ms) }
        pings = raw.pings.mapValues { $0.map { PingPoint(at: date($0.t), ms: $0.ms) } }
        var items: [ServerStat] = []
        for s in raw.servers {
            guard let server = model.servers.first(where: { $0.id == s.id }) else { continue }
            items.append(ServerStat(server: server, availability: s.availability ?? 0, avg: s.avg, samples: s.samples, usage: Double(s.usage) / 1000))
        }
        servers = items
        // uniqueKeysWithValues would trap on a duplicated server id in a malformed reply — keep the first.
        chosen = Dictionary(raw.servers.map { ($0.id, $0.chosen) }, uniquingKeysWith: { first, _ in first })
        trafficProxy = raw.trafficProxy
        trafficDirect = raw.trafficDirect
        trafficByServer = raw.trafficByServer
        losses = raw.losses
        switches = (raw.failover, raw.faster, raw.manual)
        found = raw.foundPlace.map { ($0, raw.foundOf ?? 0, raw.foundCount) }
        health = (raw.healthMedian ?? 0, raw.healthP95 ?? 0, raw.healthJitter ?? 0)
        // A reply that doesn't say where records start reads as "they cover the window".
        dataStart = raw.dataStart.map(date) ?? date(raw.from)
        states = raw.states.map {
            StateSpan(from: date($0.start), to: date($0.end),
                      state: $0.state == "IN_USE" ? .inUse : $0.state == "ANSWERED" ? .answered : .noReply)
        }
        histograms = raw.histograms
        usageRows = raw.usage.map { UsageRow(at: date($0.t), used: $0.used, total: $0.total) }
    }
}

/// A user's zone as the service keeps it (`Repo.zones`, Android `CustomZone`); `on` stands for its id being among
/// the enabled regions. `apps` and `listSizes` travel too — dropping them would truncate a zone on save.
struct ZoneJson: Codable, Equatable {
    var id: String?
    var name: String
    var on: Bool
    var direct: [String]
    var server: [String]
    var apps: [String] = []
    var listSizes: [String: Int] = [:]
}

extension ZoneJson {
    /// The app-side zone as the service's JSON.
    init(_ z: CustomZone) {
        self.init(id: z.id, name: z.name, on: z.on, direct: z.direct, server: z.server,
                  apps: z.apps, listSizes: z.listSizes)
    }

    /// Read as empty what a JSON from an older service doesn't carry (as `StoredServer` reads Android's files).
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decodeIfPresent(String.self, forKey: .id)
        name = try c.decode(String.self, forKey: .name)
        on = try c.decodeIfPresent(Bool.self, forKey: .on) ?? false
        direct = try c.decodeIfPresent([String].self, forKey: .direct) ?? []
        server = try c.decodeIfPresent([String].self, forKey: .server) ?? []
        apps = try c.decodeIfPresent([String].self, forKey: .apps) ?? []
        listSizes = try c.decodeIfPresent([String: Int].self, forKey: .listSizes) ?? [:]
    }
}

extension MainModel {
    /// The service's zones (real mode): replaces what was shown.
    func loadZones(_ json: String) {
        guard let list = try? JSONDecoder().decode([ZoneJson].self, from: Data(json.utf8)) else { return }
        zones = list.map { z in
            CustomZone(id: z.id ?? "u-\(UUID().uuidString.prefix(6))", name: z.name, on: z.on,
                       direct: z.direct, server: z.server, apps: z.apps, listSizes: z.listSizes)
        }
    }
}
