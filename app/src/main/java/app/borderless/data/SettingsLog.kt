package app.borderless.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Records which settings were in effect over time (to `settings_log` in the stats DB): a full snapshot
 * when the app starts, then every change. The strategy in effect and everything that changes how often
 * the app goes to the network, so battery use can be read against it (later: zones on the charts).
 */
object SettingsLog {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + Errors.handler)

    /** The settings that matter for battery and behaviour, as text. */
    fun snapshot(s: AppSettings): Map<String, String> = linkedMapOf(
        "strategy" to s.strategy.name,
        app.borderless.core.Eco.KEY to s.ecoOn.toString(),
        "ecoWithSaver" to s.ecoWithSaver.toString(),
        "ecoStrategy" to s.ecoStrategy.toString(),
        // The strategy the mode took over (it comes back when the mode ends) and who turned it on.
        "strategyBeforeEco" to (s.strategyBeforeEco?.name ?: "-"),
        "ecoBySaver" to s.ecoBySaver.toString(),
        "saverSeen" to s.saverSeen.toString(),
        "defaultStrategy" to s.defaultStrategy.name,
        "autoActive" to s.autoActive.toString(),
        "routingEnabled" to s.routingEnabled.toString(),
        "routingMode" to s.routingMode.name,
        "adFilter" to s.adFilter.toString(),
        "healthIntervalSec" to s.healthIntervalSec.toString(),
        "latencyCheckMin" to s.latencyCheckMin.toString(),
        "rescanIntervalMin" to s.rescanIntervalMin.toString(),
        "switchGainPercent" to s.switchGainPercent.toString(),
        "switchGainMs" to s.switchGainMs.toString(),
        "minStayMin" to s.minStayMin.toString(),
        "failThreshold" to s.failThreshold.toString(),
        "retryIntervalSec" to s.retryIntervalSec.toString(),
        "pauseScansScreenOff" to s.pauseScansScreenOff.toString(),
        "probeTimeoutSec" to s.probeTimeoutSec.toString(),
        "concurrency" to s.effectiveConcurrency.toString(),
        "notify" to s.notify.name,
        "measureCurrent" to s.measureCurrent.toString(),
        "verboseLog" to s.verboseLog.toString(),
        "carefulScans" to s.carefulScans.toString(),
        "noServerAction" to s.noServerAction.name,
        "avoidInsecure" to s.avoidInsecure.toString(),
        "sendDeviceModel" to s.sendDeviceModel.toString(),
        "confirmShare" to s.confirmShare.toString(),
        "mux" to s.mux.toString(),
        "fragment" to s.fragment.toString(),
        "ipv6" to s.ipv6.toString(),
        "subUpdateHours" to s.subUpdateHours.toString(),
        "pingAllSwitches" to s.pingAllSwitches.toString(),
        "autoSort" to s.autoSort.toString(),
        "genericNames" to s.genericNames.toString(),
        "customOverGeneric" to s.customOverGeneric.toString(),
        "countrySort" to s.countrySort.name,
        // Null means the switch of older versions decides (see AppSettings.notify).
        "notifyMode" to (s.notifyMode?.name ?: "auto"),
        "limitedAuto" to s.limitedAuto.toString(),
        "autoUpdate" to s.autoUpdate.toString(),
        "directFirst" to s.directFirst.toString(),
        "geoUpdateDays" to s.geoUpdateDays.toString(),
        "probeConcurrency" to s.probeConcurrency.toString(),
        "mtu" to s.mtu.toString(),
        "remoteDns" to s.remoteDns,
        "directDns" to s.directDns,
        "userAgent" to s.userAgent,
        "testUrl" to s.testUrl,
        "statsDays" to s.statsDays.toString(),
        // Domain zones (their codes) and how many apps / domains go each way: the lists themselves can be long
        // (hundreds of package names), and every change of them is visible by these counts.
        "regions" to s.regions.sorted().joinToString(","),
        "appsDirect" to s.appsDirect.size.toString(),
        "appsTunnel" to s.appsTunnel.size.toString(),
        "domainsDirect" to s.directDomains.size.toString(),
        "domainsProxy" to s.proxyDomains.size.toString(),
        // Palette and light / dark / following the system (the dark schemes draw less on OLED screens).
        "theme" to s.palette + "/" + if (s.themeFollowsSystem) "system" else if (s.darkTheme) "dark" else "light",
        // Servers of these countries only (codes, "-" = unknown country, comma-separated); empty = any.
        "country" to s.countries.sorted().joinToString(","),
    )

    /** Pure part (unit-tested): what changed between two snapshots. */
    fun diff(before: Map<String, String>?, after: Map<String, String>): Map<String, String> =
        if (before == null) after else after.filter { (k, v) -> before[k] != v }

    fun start() {
        scope.launch {
            var last: Map<String, String>? = null
            Repo.settings.collect { s ->
                val now = snapshot(s)
                val changed = diff(last, now)
                if (changed.isNotEmpty()) {
                    StatsDb.settings(System.currentTimeMillis(), changed)
                    // The detailed log gets them too, so it tells the whole story on its own.
                    AppLog.debug("settings", (if (last == null) "in effect: " else "changed: ") + changed.entries.joinToString { (k, v) -> "$k=$v" })
                }
                last = now
            }
        }
    }
}
