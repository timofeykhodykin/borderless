package app.borderless.data

import kotlinx.serialization.Serializable

@Serializable
data class Server(
    val id: String,
    /** The name the server had when it was added (from the link); never changed afterwards. */
    val name: String,
    /** Original share link or a minified Xray JSON config. */
    val link: String,
    /** e.g. "VLESS · Reality" */
    val protocol: String,
    val host: String,
    val port: Int,
    val subscriptionId: String? = null,
    /** Group the server is shown in; subscription servers belong to their subscription's group. */
    val groupId: String? = null,
    /** ISO 3166 alpha-2 code, detected once from the name or by IP and then kept. */
    val country: String? = null,
    /** True when the user set the country by hand, so automatic detection never overwrites it. */
    val countryManual: Boolean = false,
    /** Hidden from the main screen and never pinged or chosen; still listed (greyed) in Groups. */
    val disabled: Boolean = false,
    val addedAt: Long = System.currentTimeMillis(),
    /**
     * Name given by the user (empty by default); shown instead of [name], which stays as it was when the
     * server was added (subscription refreshes don't change it either).
     */
    val customName: String? = null,
    /**
     * Limited-network servers (a limited-network link, [app.borderless.config.LimitedLink]): streams kept connected; 0 = automatic
     * (by strategy, screen, battery saving and traffic, see [LimitedPower]).
     */
    val limitedStreams: Int = 0,
    /** Limited-network servers: the device id sent to the server (empty = the link's, else the app's own id for that server). */
    val limitedDevice: String = "",
    /**
     * Limited-network servers: open more streams than the server's own limit (`max_workers`) if the user allows it.
     * Off by default: the app keeps within the limit, which the server enforces anyway.
     */
    val limitedIgnoreLimit: Boolean = false,
) {
    /** Reached through the core: used only when no ordinary server works (see [LimitedLink]). */
    val limited: Boolean get() = app.borderless.config.LimitedLink.matches(link)
}

/**
 * A user's folder of servers: it can hold servers added by hand and whole subscriptions.
 * [DEFAULT_ID] always exists.
 */
@Serializable
data class Group(
    val id: String,
    /** Null: the title of its first subscription, or "Default" for the default group. */
    val name: String? = null,
) {
    companion object {
        const val DEFAULT_ID = "own"
    }
}

@Serializable
data class Subscription(
    val id: String,
    val name: String,
    val url: String,
    /** Group its servers are shown in. */
    val groupId: String? = null,
    val updatedAt: Long = 0,
    val lastError: String? = null,
    val uploadBytes: Long? = null,
    val downloadBytes: Long? = null,
    val totalBytes: Long? = null,
    /** Unix seconds */
    val expire: Long? = null,
    /** How often the provider asks to update, in hours (`profile-update-interval`); null if it doesn't say. */
    val providerHours: Int? = null,
    /** The last successful download went through a server (the provider is not reachable directly). */
    val viaServer: Boolean = false,
    /**
     * Name given by the user (empty by default); shown instead of [name], which stays the title the
     * provider sends (an update doesn't change the user's name either).
     */
    val customName: String? = null,
    /**
     * The device id its provider gets (`x-hwid`): one per provider host ([Repo.deviceIdFor]), so providers can't link
     * one person across them (SECURITY.md S10). Empty for subscriptions added before: they keep the install's single
     * id, which their panel already counts as this device.
     */
    val hwid: String = "",
)

/** Latency history of a server. `ms == null` means the last probe failed. */
@Serializable
data class PingRecord(
    val ms: Int? = null,
    val at: Long = 0,
    /** Recent successful latencies, newest last. */
    val recent: List<Int> = emptyList(),
    val lastOkAt: Long = 0,
    val failStreak: Int = 0,
    /** When a check of all servers (or of this one) last measured it; the current server's own pings don't count. */
    val probedAt: Long = 0,
    /** How long the last successful health checks through the running core took in all, ms (newest last, ≤ 5). */
    val checks: List<Int> = emptyList(),
    /** The last results, success or not (newest last, ≤ 5): a server that failed lately isn't one to move to for speed. */
    val outcomes: List<Boolean> = emptyList(),
) {
    val ok: Boolean get() = ms != null

    /** Median of recent successes; smoother than a single sample for switching decisions. Null after a failure. */
    val typical: Int?
        get() = if (ms == null) null else usual

    /** Median of the last 3 successes whatever the last result was (a single failure doesn't make it unknown). */
    val usual: Int?
        get() = recent.takeLast(3).sorted().takeIf { it.isNotEmpty() }?.let { it[it.size / 2] }

    fun with(result: Int?, now: Long = System.currentTimeMillis()): PingRecord =
        if (result != null) copy(ms = result, at = now, recent = (recent + result).takeLast(5), lastOkAt = now, failStreak = 0, outcomes = (outcomes + true).takeLast(5))
        else copy(ms = null, at = now, failStreak = failStreak + 1, outcomes = (outcomes + false).takeLast(5))

    /** Good enough to move to for speed: ≥ 3 successes to take a median of and no failure among the last 5 results. */
    val steady: Boolean get() = ok && recent.size >= 3 && outcomes.takeLast(5).all { it }
}

/** The last check of servers among these results (data from before [PingRecord.probedAt]: the newest result). */
fun Collection<PingRecord>.lastProbe(): Long? =
    (maxOfOrNull { it.probedAt }?.takeIf { it > 0 } ?: maxOfOrNull { it.at })?.takeIf { it > 0 }

@Serializable
enum class RoutingMode {
    /** Everything through the proxy; sites and addresses of the chosen regions ([AppSettings.regions]) go direct. */
    REGION_DIRECT,

    /** Only the sites on the chosen zones' lists (and the user's own list) go through the proxy. */
    LISTED_ONLY,
}

/**
 * The tunnel's notification: none at all, only while traffic is not going through a server (searching, without a
 * server, paused, no network, limited network), or always (it also puts the state icon into the status bar).
 */
@Serializable
enum class NotifyMode { OFF, UNCLEAR, ALWAYS }

/** What happens while no server works: traffic goes direct, or is paused (nothing leaves the phone). */
@Serializable
enum class NoServerAction { DIRECT, PAUSE }

/** How eagerly the engine changes servers. */
@Serializable
enum class Strategy {
    /** Stay on a working server; move to a clearly faster one now and then (thresholds in settings). */
    STABLE,

    /** Look for a faster server often and move to it even for a modest gain. */
    FASTEST,

    /** Change the server only when the current one stops working. */
    FAILOVER,

    /** Never change the server automatically. */
    MANUAL,
}

/** Effective switching rules for the selected [Strategy]. */
data class SwitchPolicy(
    val failover: Boolean,
    val optimize: Boolean,
    val rescanMin: Int,
    val gainPercent: Int,
    val gainMs: Int,
    val minStayMin: Int,
)

/** State of the auto-visibility pool (see [app.borderless.core.AutoPool]). */
@Serializable
data class AutoState(
    /** Servers currently taking part. */
    val pool: Set<String> = emptySet(),
    /** When each pool member joined (new members stay for a while to avoid flapping). */
    val joined: Map<String, Long> = emptyMap(),
    /** How many working servers the pool should be expected to contain; adapts to how scans go. */
    val target: Int = 4,
    /** Scans in a row where almost the whole pool answered (lets the target shrink). */
    val calm: Int = 0,
)

@Serializable
data class AppSettings(
    // --- automatic server selection
    val strategy: Strategy = Strategy.STABLE,
    /**
     * Strategy the tunnel starts with when the user turns it on; [strategy] is the one in effect now
     * (changed from the main screen, or to MANUAL by picking a server).
     */
    val defaultStrategy: Strategy = Strategy.STABLE,
    /** Show servers inside groups (and on the heatmap) in scan order instead of the user's order. */
    val autoSort: Boolean = false,
    /**
     * Auto-visibility: [app.borderless.core.AutoPool] decides which servers take part (its pool replaces
     * the user's visibility while on; the user's `disabled` flags are kept and come back when it is off).
     */
    val autoActive: Boolean = false,
    /**
     * Servers of these countries only (the map button on the main screen): ISO codes, [Countries.UNKNOWN] for the
     * servers whose country isn't known; empty = any country. While set, their servers take part unless hidden by
     * hand; auto-visibility doesn't apply ([Repo.isActive]). Changed through `core.CountryFilter`.
     */
    val countries: Set<String> = emptySet(),
    /** "Ping all" with the connection on moves to the best server (else the strategy decides, as after a rescan). */
    val pingAllSwitches: Boolean = true,
    /** Servers without encryption on the way or without a certificate check take no part (SECURITY.md S7). */
    val avoidInsecure: Boolean = false,
    /**
     * Limited-network servers (limited-network links) are taken automatically when no ordinary server works (a limited network, or
     * all of them down); off: only when the user picks one.
     */
    val limitedAuto: Boolean = true,
    /** How the list of countries is sorted. */
    val countrySort: CountrySort = CountrySort.NAME,
    /** Internal: Manual was in effect when the countries were chosen (its server outside them); back when they end. */
    val strategyBeforeCountry: Strategy? = null,
    /** Internal: the server in use when the countries were chosen (On failure goes back to it when they end). */
    val serverBeforeCountry: String? = null,
    /** Show servers as "<group> #N" (position in the user's order within the group) instead of their names. */
    val genericNames: Boolean = false,
    /** With [genericNames]: servers the user renamed keep their own name instead of "Group #N". */
    val customOverGeneric: Boolean = true,
    /** Colour scheme of the interface (`ui.theme.Schemes`), by id. */
    val palette: String = "coral",
    /** The palette's dark scheme (while [themeFollowsSystem] is off). */
    val darkTheme: Boolean = false,
    /** Light or dark as the system's theme says (overrides [darkTheme]). */
    val themeFollowsSystem: Boolean = false,
    /** 1.0.0's "status bar icon" switch: read only to derive [notify] when [notifyMode] was never set. */
    val statusIcon: Boolean = false,
    /** The notification mode chosen by the user (null = never chosen: see [notify]). */
    val notifyMode: NotifyMode? = null,
    /** New versions are downloaded and installed by themselves (core.Updater); off: one notification per version. */
    val autoUpdate: Boolean = true,
    /**
     * On a limited network, with routing on: what the zones' lists let go without the tunnel is tried directly first
     * and goes through the server if that doesn't work; the zones' apps go into the tunnel meanwhile (core.DirectFirst).
     */
    val directFirst: Boolean = true,
    // --- battery saving mode (see app.borderless.core.Eco)
    /** The app's own battery saving mode is on (button on the main screen, or the phone's saver). */
    val ecoOn: Boolean = false,
    /** Turn the mode on and off together with the phone's battery saver. */
    val ecoWithSaver: Boolean = true,
    /** Move to the most frugal strategy while the mode is on, back to the previous one after. */
    val ecoStrategy: Boolean = true,
    /** Internal: the mode was turned on by the phone's saver (so the saver ending turns it off). */
    val ecoBySaver: Boolean = false,
    /** Internal: the current battery saver session was already handled (the user may have overridden it). */
    val saverSeen: Boolean = false,
    /** Internal: the strategy before the mode replaced it, restored when the mode ends. */
    val strategyBeforeEco: Strategy? = null,
    /** Diagnostics: sample the battery current and measure every action of the app (see CurrentMeter). */
    val measureCurrent: Boolean = false,
    /** How often traffic counters are looked at to see the server works (local, no network). */
    val healthIntervalSec: Int = 20,
    /** How often the current server is actually pinged while traffic shows it works (×3 with the screen off). */
    val latencyCheckMin: Int = 5,
    val failThreshold: Int = 2,
    val rescanIntervalMin: Int = 10,
    val switchGainPercent: Int = 30,
    val switchGainMs: Int = 60,
    val minStayMin: Int = 20,
    val retryIntervalSec: Int = 30,
    val noServerAction: NoServerAction = NoServerAction.DIRECT,
    val pauseScansScreenOff: Boolean = true,
    val probeTimeoutSec: Int = 6,
    /** 0 = automatic, by CPU cores: too many parallel probes starve the CPU and inflate latencies. */
    val probeConcurrency: Int = 0,
    val testUrl: String = "https://www.gstatic.com/generate_204",

    // --- routing
    val routingEnabled: Boolean = true,
    val routingMode: RoutingMode = RoutingMode.REGION_DIRECT,
    /** Regions whose lists apply (codes from [Regions]); none by default. */
    val regions: Set<String> = emptySet(),
    /**
     * Apps the user puts outside the tunnel, and apps always kept in it. An app is in one list at most; both win over
     * the domain zones' apps (which go outside the tunnel on their own, see [Regions]).
     */
    val appsDirect: Set<String> = emptySet(),
    val appsTunnel: Set<String> = emptySet(),
    val directDomains: List<String> = emptyList(),
    val proxyDomains: List<String> = emptyList(),
    /** Ad and tracker domains don't open for apps in the tunnel. */
    val adFilter: Boolean = false,

    // --- connection
    val remoteDns: String = "https://1.1.1.1/dns-query",
    /** Resolver for direct sites; empty = the chosen regions' own, else a public one. */
    val directDns: String = "",
    val ipv6: Boolean = false,
    val mtu: Int = 1500,
    val fragment: Boolean = false,
    val mux: Boolean = false,

    /**
     * Check for new routing lists (geoip / geosite) every this many days; 0 = keep the ones in the app.
     * A check is one small request per source unless something changed, so it can be daily.
     */
    val geoUpdateDays: Int = 1,

    // --- subscriptions
    val subUpdateHours: Int = 12,
    val userAgent: String = "v2rayNG/2.3.10",
    /**
     * Random id of this install, sent to subscription panels as `x-hwid` (panels with a device limit count
     * devices by it). Created on first use; empty until then.
     */
    val deviceId: String = "",
    /**
     * The limited-network servers added before 3.1.0 keep the per-server device id they used then (frozen into
     * [Server.limitedDevice] once, see `Limited.freezeOldDeviceIds`); new ones send "borderless-XXXX".
     */
    val limitedDevicesFrozen: Boolean = false,
    /** Subscription requests also say the phone's model and Android version (some panels list devices by them). */
    val sendDeviceModel: Boolean = false,
    /** Sharing servers / subscriptions and the statistics export ask for the phone's screen lock first. */
    val confirmShare: Boolean = false,
    /** Automatic scans check only the server in use and the next few; no hidden servers (SECURITY.md S9). */
    val carefulScans: Boolean = false,

    // --- state
    val lastServerId: String? = null,
    /** The server the user picked by hand last; the Manual strategy goes back to it. */
    val manualServerId: String? = null,

    // --- diagnostics
    /** Detailed log: every probe, every routing decision of the core. */
    val verboseLog: Boolean = false,
    /** When the detailed core log was turned on (0: unknown); it turns itself off a day later (SECURITY.md S12). */
    val verboseSince: Long = 0,
    /** How long statistics are kept. */
    val statsDays: Int = 180,
    /** The statistics screens' custom range (0, 0 = none chosen): kept until the user picks another period. */
    val statsFrom: Long = 0,
    val statsTo: Long = 0,
) {
    /** Whether the dark scheme is in use, given the system's current theme. */
    fun darkNow(systemDark: Boolean): Boolean = if (themeFollowsSystem) systemDark else darkTheme

    /** Switching rules in effect: the strategy's, made more patient in the battery saving mode. */
    val policy: SwitchPolicy
        get() = basePolicy.let { if (ecoOn) EcoMath.policy(it) else it }

    private val basePolicy: SwitchPolicy
        get() = when (strategy) {
            Strategy.STABLE -> SwitchPolicy(true, true, rescanIntervalMin, switchGainPercent, switchGainMs, minStayMin)
            Strategy.FASTEST -> SwitchPolicy(true, true, rescanIntervalMin.coerceAtMost(3), 15, 20, 1)
            Strategy.FAILOVER -> SwitchPolicy(true, false, rescanIntervalMin, switchGainPercent, switchGainMs, minStayMin)
            Strategy.MANUAL -> SwitchPolicy(false, false, rescanIntervalMin, switchGainPercent, switchGainMs, minStayMin)
        }

    companion object {
        /** Settings of a new install: every domain zone on. */
        /**
         * A new install. Traffic is paused while no server works (SECURITY.md S20: nothing leaves around the tunnel
         * unless the user chooses so; installs from before keep their setting, whose default stays DIRECT).
         */
        fun fresh() = AppSettings(
            regions = Regions.builtIn.map { it.code }.toSet(), notifyMode = NotifyMode.UNCLEAR, noServerAction = NoServerAction.PAUSE,
        )

    }

    /** The notification mode in effect: the chosen one, else what 1.0.0's switch meant. */
    val notify: NotifyMode get() = notifyMode ?: if (statusIcon) NotifyMode.ALWAYS else NotifyMode.OFF

    val effectiveConcurrency: Int
        get() = if (probeConcurrency > 0) probeConcurrency
        else (Runtime.getRuntime().availableProcessors() * 1.5).toInt().coerceIn(4, 12)
}

/** Orders of the list of countries: by name both ways, by how often each was chosen, by how reliably its servers answer. */
@Serializable
enum class CountrySort { NAME, NAME_DESC, USED, STABLE }
