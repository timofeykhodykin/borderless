package app.borderless.ui.settings

import androidx.compose.ui.unit.IntOffset
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.AnimatedContent
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import app.borderless.ui.theme.Palette
import androidx.compose.material.icons.rounded.RssFeed
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.automirrored.rounded.AltRoute
import androidx.compose.material.icons.Icons
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.background
import app.borderless.data.launchSafe
import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.borderless.BuildConfig
import app.borderless.R
import app.borderless.core.CoreEnv
import app.borderless.data.AppSettings
import app.borderless.data.NoServerAction
import app.borderless.data.Repo
import app.borderless.core.Eco
import app.borderless.data.RoutingMode
import app.borderless.data.Regions
import app.borderless.data.Strategy
import app.borderless.data.StatsDb
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.borderless.service.TunnelService

/** Fields that change the generated config or the TUN interface; editing them restarts the tunnel. */
private fun AppSettings.tunnelKey() = listOf(
    routingEnabled, routingMode, regions, appsDirect, appsTunnel, directDomains, proxyDomains, adFilter,
    remoteDns, directDns, ipv6, mtu, fragment, mux, noServerAction, verboseLog,
)

/** What the project is (a research proof of concept), folded under a row of the About section. */
@Composable
private fun AboutProjectRow() {
    var open by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    ExpandRow(stringResource(R.string.about_project), null, open) { open = !open }
    androidx.compose.animation.AnimatedVisibility(open) {
        androidx.compose.material3.Text(
            stringResource(R.string.about_project_text), fontSize = 13.5.sp, color = Palette.textSecondary,
            modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 14.dp),
        )
    }
}

/** 600 s as "10 min", 90 s as "1 min 30 s". */
private fun secondsText(v: Int): String = app.borderless.data.Units.durationText(v.toLong())

/** 90 min as "1 h 30 min". */
private fun minutesText(v: Int): String = app.borderless.data.Units.durationText(v * 60L)

/** Pages of the settings: the root lists them, each opens as its own screen. */
enum class SettingsPage { SERVER, CHECKS, ECO, ROUTING, SUBS, CONNECTION, INTERFACE, DIAGNOSTICS }

@Composable
fun SettingsScreen(
    page: SettingsPage?,
    onPage: (SettingsPage?) -> Unit,
    onBack: () -> Unit,
    onApps: () -> Unit,
    onLog: () -> Unit,
) {
    val s by Repo.settings.collectAsStateWithLifecycle()
    val subs by Repo.subscriptions.collectAsStateWithLifecycle()
    val initial = remember { Repo.settings.value.tunnelKey() }
    DisposableEffect(Unit) {
        onDispose { if (Repo.settings.value.tunnelKey() != initial) TunnelService.reconfigure() }
    }
    fun set(f: (AppSettings) -> AppSettings) = Repo.updateSettings(f)

    // A page slides in from the right over the list of categories, and back out to the right.
    AnimatedContent(
        targetState = page,
        transitionSpec = {
            val spec = tween<IntOffset>(300, easing = FastOutSlowInEasing)
            val dir = if (targetState != null) 1 else -1
            (slideInHorizontally(spec) { dir * it } + fadeIn(tween(300))) togetherWith
                (slideOutHorizontally(spec) { -dir * it } + fadeOut(tween(300)))
        },
        label = "settings",
    ) { current ->
        if (current == null) {
            ScreenScaffold(stringResource(R.string.settings), onBack) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Spacer(Modifier.height(8.dp))
                    SettingsRoot(s, subs.size, onPage)
                    SupportSection()
                    Section(stringResource(R.string.sec_about)) {
                        InfoRow(stringResource(R.string.set_version), BuildConfig.VERSION_NAME)
                        InfoRow(stringResource(R.string.set_core), remember { CoreEnv.version().substringBefore(" (").ifEmpty { BuildConfig.XRAY_CORE_VERSION } })
                        AboutProjectRow()
                    }
                    Spacer(Modifier.height(40.dp))
                }
            }
        } else {
            ScreenScaffold(stringResource(current.title), onBack = { onPage(null) }) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    when (current) {
                        SettingsPage.SERVER -> ServerPage(s, ::set)
                        SettingsPage.CHECKS -> ChecksPage(s, ::set)
                        SettingsPage.ECO -> EcoPage(s)
                        SettingsPage.ROUTING -> RoutingPage(s, ::set, onApps)
                        SettingsPage.SUBS -> SubsPage(s, subs)
                        SettingsPage.CONNECTION -> ConnectionPage(s, ::set)
                        SettingsPage.INTERFACE -> InterfacePage(s, ::set)
                        SettingsPage.DIAGNOSTICS -> DiagnosticsPage(s, ::set, onLog)
                    }
                    Spacer(Modifier.height(40.dp))
                }
            }
        }
    }
}

private val SettingsPage.title: Int
    get() = when (this) {
        SettingsPage.SERVER -> R.string.set_cat_server
        SettingsPage.CHECKS -> R.string.set_cat_checks
        SettingsPage.ECO -> R.string.eco_title
        SettingsPage.ROUTING -> R.string.sec_routing
        SettingsPage.SUBS -> R.string.subs_title
        SettingsPage.CONNECTION -> R.string.sec_connection
        SettingsPage.INTERFACE -> R.string.set_cat_interface
        SettingsPage.DIAGNOSTICS -> R.string.set_cat_diagnostics
    }

private val SettingsPage.icon: ImageVector
    get() = when (this) {
        SettingsPage.SERVER -> Icons.Rounded.Dns
        SettingsPage.CHECKS -> Icons.Rounded.BatteryChargingFull
        SettingsPage.ECO -> Icons.Rounded.BatterySaver
        SettingsPage.ROUTING -> Icons.AutoMirrored.Rounded.AltRoute
        SettingsPage.SUBS -> Icons.Rounded.RssFeed
        SettingsPage.CONNECTION -> Icons.Rounded.Lan
        SettingsPage.INTERFACE -> Icons.Rounded.Palette
        SettingsPage.DIAGNOSTICS -> Icons.Rounded.Insights
    }

/** The root: one row per page, each with a short summary of what is set there. */
@Composable
private fun SettingsRoot(s: AppSettings, subscriptions: Int, onPage: (SettingsPage) -> Unit) {
    val ecoEstimate by Eco.estimate.collectAsStateWithLifecycle()
    val context = LocalContext.current
    fun strategy(v: Strategy) = context.getString(
        when (v) {
            Strategy.STABLE -> R.string.strategy_stable
            Strategy.FASTEST -> R.string.strategy_fastest
            Strategy.FAILOVER -> R.string.strategy_failover
            Strategy.MANUAL -> R.string.strategy_manual
        }
    )
    val summaries = mapOf(
        SettingsPage.SERVER to stringResource(R.string.set_sum_server, strategy(s.defaultStrategy), s.rescanIntervalMin),
        SettingsPage.CHECKS to stringResource(R.string.set_sum_checks, s.latencyCheckMin, s.probeTimeoutSec),
        SettingsPage.ECO to (ecoEstimate?.percent?.let { stringResource(if (s.ecoOn) R.string.set_sum_eco_on else R.string.set_sum_eco_off, it) }
            ?: stringResource(if (s.ecoOn) R.string.set_sum_eco_on_plain else R.string.set_sum_eco_off_plain)),
        SettingsPage.ROUTING to if (!s.routingEnabled) stringResource(R.string.set_sum_off)
        else listOf(
            stringResource(if (s.routingMode == RoutingMode.REGION_DIRECT) R.string.mode_region_direct else R.string.mode_listed),
            Regions.of(s.regions).joinToString(" ") { it.label }.ifEmpty { stringResource(R.string.zones_none) },
        ).joinToString(" · "),
        SettingsPage.SUBS to if (s.subUpdateHours == 0) stringResource(R.string.set_sum_subs_manual, subscriptions)
        else stringResource(R.string.set_sum_subs, subscriptions, s.subUpdateHours),
        SettingsPage.CONNECTION to stringResource(R.string.set_sum_connection, s.remoteDns.substringAfter("://").substringBefore('/'), s.mtu),
        SettingsPage.INTERFACE to listOfNotNull(
            stringResource(paletteName(s.palette)), languageLabel(context), stringResource(R.string.set_generic_names).takeIf { s.genericNames },
        ).joinToString(" · "),
        SettingsPage.DIAGNOSTICS to listOfNotNull(
            stringResource(R.string.set_sum_stats, s.statsDays),
            stringResource(R.string.set_measure_current).takeIf { s.measureCurrent },
        ).joinToString(" · "),
    )
    Column(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Palette.card),
    ) {
        SettingsPage.entries.forEach { p -> CategoryRow(p.icon, stringResource(p.title), summaries[p].orEmpty()) { onPage(p) } }
    }
}

@Composable
private fun ServerPage(s: AppSettings, set: ((AppSettings) -> AppSettings) -> Unit) {
    val sec = stringResource(R.string.unit_s)
    val min = stringResource(R.string.unit_min)
    val ms = stringResource(R.string.unit_ms)
    Section(stringResource(R.string.set_sub_choice)) {
        ChoiceRow(
            stringResource(R.string.strategy_default), s.defaultStrategy,
            listOf(
                Strategy.STABLE to stringResource(R.string.strategy_stable),
                Strategy.FASTEST to stringResource(R.string.strategy_fastest),
                Strategy.FAILOVER to stringResource(R.string.strategy_failover),
                Strategy.MANUAL to stringResource(R.string.strategy_manual),
            ),
        ) { v ->
            // Unless a server was picked by hand (Manual), the strategy in effect follows the new default
            // (the frugal counterpart while the battery saving mode is on).
            set { val n = it.copy(defaultStrategy = v); if (n.strategy == Strategy.MANUAL) n else app.borderless.data.EcoMath.startStrategy(n) }
        }
    }
    Section(stringResource(R.string.set_sub_failure)) {
        NumberRow(stringResource(R.string.set_fails), null, s.failThreshold, 1..10) { v -> set { it.copy(failThreshold = v) } }
        ChoiceRow(
            stringResource(R.string.set_no_server), s.noServerAction,
            listOf(
                NoServerAction.DIRECT to stringResource(R.string.no_server_direct),
                NoServerAction.PAUSE to stringResource(R.string.no_server_pause),
            ),
        ) { v -> set { it.copy(noServerAction = v) } }
        NumberRow(stringResource(R.string.set_retry), null, s.retryIntervalSec, 5..600, sec, display = ::secondsText) { v -> set { it.copy(retryIntervalSec = v) } }
    }
    Section(stringResource(R.string.set_sub_stable)) {
        InfoNote(stringResource(R.string.set_stable_params))
        NumberRow(stringResource(R.string.set_rescan), stringResource(R.string.set_rescan_sub), s.rescanIntervalMin, 1..240, min, display = ::minutesText) { v -> set { it.copy(rescanIntervalMin = v) } }
        NumberRow(stringResource(R.string.set_gain_pct), stringResource(R.string.set_gain_pct_sub), s.switchGainPercent, 0..95, "%") { v -> set { it.copy(switchGainPercent = v) } }
        NumberRow(stringResource(R.string.set_gain_ms), null, s.switchGainMs, 0..2000, ms) { v -> set { it.copy(switchGainMs = v) } }
        NumberRow(stringResource(R.string.set_min_stay), stringResource(R.string.set_min_stay_sub), s.minStayMin, 0..240, min, display = ::minutesText) { v -> set { it.copy(minStayMin = v) } }
    }
}

@Composable
private fun ChecksPage(s: AppSettings, set: ((AppSettings) -> AppSettings) -> Unit) {
    val sec = stringResource(R.string.unit_s)
    val min = stringResource(R.string.unit_min)
    Section(stringResource(R.string.set_sub_current)) {
        NumberRow(stringResource(R.string.set_health), stringResource(R.string.set_health_sub), s.healthIntervalSec, 5..600, sec, display = ::secondsText) { v -> set { it.copy(healthIntervalSec = v) } }
        NumberRow(stringResource(R.string.set_latency), stringResource(R.string.set_latency_sub), s.latencyCheckMin, 1..60, min, display = ::minutesText) { v -> set { it.copy(latencyCheckMin = v) } }
    }
    Section(stringResource(R.string.set_sub_scans)) {
        SwitchRow(stringResource(R.string.set_pause_screen), stringResource(R.string.set_pause_screen_sub), s.pauseScansScreenOff) { v -> set { it.copy(pauseScansScreenOff = v) } }
        NumberRow(stringResource(R.string.set_timeout), null, s.probeTimeoutSec, 2..30, sec, display = ::secondsText) { v -> set { it.copy(probeTimeoutSec = v) } }
        NumberRow(
            stringResource(R.string.set_concurrency),
            if (s.probeConcurrency == 0) stringResource(R.string.set_concurrency_auto, app.borderless.core.Scanner.concurrencyNow)
            else stringResource(R.string.set_concurrency_manual),
            s.probeConcurrency, 0..64,
        ) { v -> set { it.copy(probeConcurrency = v) } }
        TextRow(stringResource(R.string.set_test_url), s.testUrl) { v -> set { it.copy(testUrl = v.ifBlank { AppSettings().testUrl }) } }
    }
}

@Composable
private fun EcoPage(s: AppSettings) {
    val estimate by Eco.estimate.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { Eco.refreshEstimate() }
    Section(stringResource(R.string.eco_title)) {
        // Through Eco, so the strategy swap and the log entries happen as with the main-screen button.
        SwitchRow(stringResource(R.string.set_eco_on), stringResource(R.string.set_eco_on_sub), s.ecoOn) { v -> Eco.set(v) }
        SwitchRow(stringResource(R.string.set_eco_with_saver), stringResource(R.string.set_eco_with_saver_sub), s.ecoWithSaver) { v ->
            Repo.updateSettings { it.copy(ecoWithSaver = v) }
        }
        SwitchRow(
            stringResource(R.string.set_eco_strategy),
            stringResource(R.string.set_eco_strategy_sub, stringResource(R.string.strategy_failover)), s.ecoStrategy,
        ) { v -> Repo.updateSettings { it.copy(ecoStrategy = v) } }
        estimate?.let { e ->
            InfoRow(stringResource(R.string.set_eco_estimate), stringResource(R.string.set_eco_estimate_value, e.percent))
            InfoNote(stringResource(if (e.ownData) R.string.set_eco_estimate_own else R.string.set_eco_estimate_typical))
        }
    }
    Section(stringResource(R.string.set_eco_what_title)) {
        InfoNote(stringResource(R.string.set_eco_what))
    }
}

@Composable
private fun RoutingPage(s: AppSettings, set: ((AppSettings) -> AppSettings) -> Unit, onApps: () -> Unit) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    Section(stringResource(R.string.set_sub_general)) {
        SwitchRow(stringResource(R.string.routing), stringResource(R.string.set_routing_sub), s.routingEnabled) { v -> set { it.copy(routingEnabled = v) } }
        ChoiceRow(
            stringResource(R.string.set_mode), s.routingMode,
            listOf(
                RoutingMode.REGION_DIRECT to stringResource(R.string.mode_region_direct),
                RoutingMode.LISTED_ONLY to stringResource(R.string.mode_listed),
            ),
        ) { v -> set { it.copy(routingMode = v) } }
        SwitchRow(stringResource(R.string.set_ads_filter), stringResource(R.string.set_ads_filter_sub), s.adFilter) { v -> set { it.copy(adFilter = v) } }
    }
    Section(stringResource(R.string.set_zones)) {
        val geo by app.borderless.core.GeoLists.state.collectAsStateWithLifecycle()
        val geoBusy by app.borderless.core.GeoLists.busy.collectAsStateWithLifecycle()
        val geoWork by app.borderless.core.GeoLists.work.collectAsStateWithLifecycle()
        Regions.all.forEach { region ->
            // A zone takes part only once its lists are here (some are downloaded): until then its switch is off and
            // locked, and the row says what is going on.
            val ready = remember(geo, region) { Regions.ready(region) }
            val sites = region.zones.joinToString(", ")
            val wait = ((geo.retryAt - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
            SwitchRow(
                region.code,
                when {
                    ready -> stringResource(R.string.zone_sites_apps, sites, region.code)
                    geoWork == app.borderless.core.GeoLists.Work.VIA_SERVER -> stringResource(R.string.zone_lists_via_server)
                    geoBusy -> stringResource(R.string.zone_lists_downloading)
                    geo.lastError != null -> stringResource(R.string.zone_lists_failed, geo.lastError!!, app.borderless.data.Units.durationText(wait))
                    else -> stringResource(R.string.zone_lists_pending)
                },
                checked = ready && region.code in s.regions,
                enabled = ready,
            ) { on ->
                set { it.copy(regions = if (on) it.regions + region.code else it.regions - region.code) }
            }
            // Failed: a try right now (otherwise it comes by itself, and at once when a server connects).
            if (!ready && !geoBusy && geo.lastError != null) {
                ActionRow(stringResource(R.string.zone_lists_retry)) { scope.launchSafe("routing lists") { app.borderless.core.GeoLists.updateIfDue(force = true) } }
            }
        }
        Text(
            stringResource(if (s.routingMode == RoutingMode.REGION_DIRECT) R.string.zones_note_direct else R.string.zones_note_listed),
            fontSize = 13.5.sp, color = Palette.textMuted,
            modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 14.dp),
        )
    }
    GeoListsSection(s, set)
    Section(stringResource(R.string.set_sub_exceptions)) {
        NavRow(stringResource(R.string.set_apps), stringResource(R.string.set_apps_sub, s.appsDirect.size, s.appsTunnel.size), onApps)
        TextRow(
            stringResource(R.string.set_direct_sites), s.directDomains.joinToString("\n"), multiline = true,
            hint = stringResource(R.string.set_direct_sites_hint),
        ) { v -> set { it.copy(directDomains = v.lines().map(String::trim).filter(String::isNotEmpty)) } }
        TextRow(
            stringResource(R.string.set_proxy_sites), s.proxyDomains.joinToString("\n"), multiline = true,
            hint = stringResource(R.string.set_proxy_sites_hint),
        ) { v -> set { it.copy(proxyDomains = v.lines().map(String::trim).filter(String::isNotEmpty)) } }
    }
}

/** The routing lists: how fresh they are, a "check now" row and how often they are checked by themselves. */
@Composable
private fun GeoListsSection(s: AppSettings, set: ((AppSettings) -> AppSettings) -> Unit) {
    val state by app.borderless.core.GeoLists.state.collectAsStateWithLifecycle()
    val busy by app.borderless.core.GeoLists.busy.collectAsStateWithLifecycle()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    Section(stringResource(R.string.set_geo_title)) {
        val date = if (state.updatedAt > 0) java.text.SimpleDateFormat("d MMM yyyy", app.borderless.Res.locale).format(java.util.Date(state.updatedAt)) else "—"
        val checked = if (state.checkedAt > 0) android.text.format.DateUtils.getRelativeTimeSpanString(state.checkedAt).toString().lowercase()
        else stringResource(R.string.set_geo_never)
        ActionRow(
            stringResource(R.string.set_geo_update),
            when {
                busy -> stringResource(R.string.subs_updating)
                state.lastError != null -> stringResource(R.string.set_geo_state, date, checked) + "\n" + stringResource(R.string.set_geo_failed, state.lastError!!)
                else -> stringResource(R.string.set_geo_state, date, checked)
            },
        ) { if (!busy) scope.launchSafe("updating routing lists") { app.borderless.core.GeoLists.updateIfDue(force = true) } }
        NumberRow(stringResource(R.string.set_geo_days), stringResource(R.string.set_geo_days_sub), s.geoUpdateDays, 0..60, stringResource(R.string.unit_days)) { v ->
            set { it.copy(geoUpdateDays = v) }
        }
    }
}

/**
 * What applies to every subscription (each one is managed on the groups screen): updating them all now,
 * how often they update by themselves and the User-Agent the provider is asked with.
 */
@Composable
private fun SubsPage(s: AppSettings, subs: List<app.borderless.data.Subscription>) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var busy by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    Section(stringResource(R.string.subs_title)) {
        if (subs.isEmpty()) InfoNote(stringResource(R.string.subs_empty))
        else ActionRow(
            stringResource(R.string.update_all),
            if (busy) stringResource(R.string.subs_updating) else stringResource(R.string.set_subs_count, subs.size),
        ) {
            if (!busy) {
                busy = true
                scope.launchSafe("updating subscriptions", finally = { busy = false }) {
                    subs.forEach { app.borderless.data.Importer.update(it) }
                    app.borderless.data.Importer.resolveCountries()
                    app.borderless.core.Scanner.start()
                }
            }
        }
        NumberRow(
            stringResource(R.string.set_sub_update), stringResource(R.string.set_sub_update_sub), s.subUpdateHours, 0..168,
            stringResource(R.string.unit_h), display = { v -> if (v == 0) "0" else app.borderless.data.Units.durationText(v * 3600L) },
        ) { v -> Repo.updateSettings { it.copy(subUpdateHours = v) } }
        TextRow("User-Agent", s.userAgent, hint = stringResource(R.string.set_ua_hint)) { v ->
            Repo.updateSettings { it.copy(userAgent = v.ifBlank { AppSettings().userAgent }) }
        }
        // A custom one may get another format from some panels (then the default is tried anyway).
        if (s.userAgent != AppSettings().userAgent) {
            NavRow(stringResource(R.string.ua_reset), AppSettings().userAgent) {
                Repo.updateSettings { it.copy(userAgent = AppSettings().userAgent) }
            }
        }
    }
}

@Composable
private fun ConnectionPage(s: AppSettings, set: ((AppSettings) -> AppSettings) -> Unit) {
    Section("DNS") {
        TextRow(stringResource(R.string.set_remote_dns), s.remoteDns) { v -> set { it.copy(remoteDns = v.ifBlank { AppSettings().remoteDns }) } }
        TextRow(
            stringResource(R.string.set_direct_dns), s.directDns, hint = stringResource(R.string.set_direct_dns_hint),
            emptyText = stringResource(R.string.dns_auto, app.borderless.config.XrayConfig.RegionRules(s).dns.joinToString(", ")),
        ) { v -> set { it.copy(directDns = v.trim()) } }
    }
    Section(stringResource(R.string.set_sub_tunnel)) {
        SwitchRow("IPv6", stringResource(R.string.set_ipv6_sub), s.ipv6) { v -> set { it.copy(ipv6 = v) } }
        SwitchRow(stringResource(R.string.set_fragment), stringResource(R.string.set_fragment_sub), s.fragment) { v -> set { it.copy(fragment = v) } }
        SwitchRow("Mux", stringResource(R.string.set_mux_sub), s.mux) { v -> set { it.copy(mux = v) } }
        NumberRow("MTU", null, s.mtu, 1280..9000) { v -> set { it.copy(mtu = v) } }
    }
}

@Composable
private fun InterfacePage(s: AppSettings, set: ((AppSettings) -> AppSettings) -> Unit) {
    Section(stringResource(R.string.set_palette)) {
        PaletteChooser(s.palette) { id -> set { it.copy(palette = id) } }
    }
    Section(stringResource(R.string.set_cat_interface)) {
        LanguageRow()
        SwitchRow(stringResource(R.string.set_generic_names), stringResource(R.string.set_generic_names_sub), s.genericNames) { v ->
            set { it.copy(genericNames = v) }
        }
        if (s.genericNames) {
            SwitchRow(stringResource(R.string.set_custom_over_generic), stringResource(R.string.set_custom_over_generic_sub), s.customOverGeneric) { v ->
                set { it.copy(customOverGeneric = v) }
            }
        }
        SwitchRow(stringResource(R.string.set_status_icon), stringResource(R.string.set_status_icon_sub), s.statusIcon) { v ->
            set { it.copy(statusIcon = v) }
        }
        AddTileRow()
    }
}

@Composable
private fun DiagnosticsPage(s: AppSettings, set: ((AppSettings) -> AppSettings) -> Unit, onLog: () -> Unit) {
    Section(stringResource(R.string.log_title)) {
        NavRow(stringResource(R.string.log_title), null, onLog)
        SwitchRow(stringResource(R.string.set_verbose), stringResource(R.string.set_verbose_sub), s.verboseLog) { v -> set { it.copy(verboseLog = v) } }
    }
    Section(stringResource(R.string.stats)) {
        // How much space the statistics take (database with its write-ahead log).
        val context = LocalContext.current
        val size = remember { context.getDatabasePath("stats.db").let { db -> db.length() + java.io.File(db.path + "-wal").length() } }
        InfoRow(stringResource(R.string.set_stats_size), app.borderless.ui.stats.formatBytes(context, size))
        NumberRow(stringResource(R.string.set_stats_days), null, s.statsDays, 1..3650, stringResource(R.string.unit_days)) { v ->
            set { it.copy(statsDays = v) }
            StatsDb.prune(v)
        }
        SwitchRow(stringResource(R.string.set_measure_current), stringResource(R.string.set_measure_current_sub), s.measureCurrent) { v ->
            set { it.copy(measureCurrent = v) }
        }
        ExportStatsRow()
        ClearStatsRow()
    }
}

private fun languageLabel(context: Context): String =
    appLanguage(context).takeIf { it.isNotEmpty() }?.let(::languageName) ?: context.getString(R.string.set_sum_lang_system)

/** A language's own name ("Deutsch", "Русский"), capitalised. */
private fun languageName(tag: String): String {
    val locale = java.util.Locale.forLanguageTag(tag)
    return locale.getDisplayName(locale).replaceFirstChar { it.titlecase(locale) }
}

@Composable
private fun ClearStatsRow() {
    var ask by remember { mutableStateOf(false) }
    NavRow(stringResource(R.string.clear_stats)) { ask = true }
    if (ask) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { ask = false },
            containerColor = app.borderless.ui.theme.Palette.background,
            title = { androidx.compose.material3.Text(stringResource(R.string.clear_stats_q)) },
            confirmButton = {
                androidx.compose.material3.TextButton({ StatsDb.clear(); ask = false }) {
                    androidx.compose.material3.Text(stringResource(R.string.clear), color = app.borderless.ui.theme.Palette.danger)
                }
            },
            dismissButton = { androidx.compose.material3.TextButton({ ask = false }) { androidx.compose.material3.Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** Per-app language (Android 13+). Older systems simply follow the system language. */
@Composable
private fun LanguageRow() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    val current = remember { appLanguage(context) }
    ChoiceRow(
        stringResource(R.string.set_language), current,
        // Every translation the app has (BuildConfig.UI_LANGUAGES is generated from the res/values-* folders).
        listOf("" to stringResource(R.string.lang_system)) + BuildConfig.UI_LANGUAGES.map { it to languageName(it) },
    ) { tag -> setAppLanguage(context, tag) }
}

/** The per-app language as one of [BuildConfig.UI_LANGUAGES] ("" = follow the system). */
private fun appLanguage(context: Context): String {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return ""
    val set = context.getSystemService(LocaleManager::class.java).applicationLocales.takeIf { !it.isEmpty } ?: return ""
    val locale = set[0]
    return BuildConfig.UI_LANGUAGES.firstOrNull { it.equals(locale.toLanguageTag(), ignoreCase = true) }
        ?: BuildConfig.UI_LANGUAGES.firstOrNull { java.util.Locale.forLanguageTag(it).language == locale.language }
        ?: ""
}

/** Applying a new locale recreates the activity, so the UI switches immediately. */
private fun setAppLanguage(context: Context, tag: String) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    context.getSystemService(LocaleManager::class.java).applicationLocales =
        if (tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
}

/** Builds the statistics ZIP and hands it to the Android share sheet. */
@Composable
private fun ExportStatsRow() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    NavRow(
        stringResource(R.string.set_export_stats),
        stringResource(if (busy) R.string.set_export_busy else R.string.set_export_stats_sub),
    ) {
        if (busy) return@NavRow
        busy = true
        scope.launchSafe("exporting statistics", finally = { busy = false }) {
            val file = app.borderless.data.StatsExport.build(context)
            val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).setType("application/zip")
                .putExtra(android.content.Intent.EXTRA_STREAM, uri)
                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(android.content.Intent.createChooser(intent, context.getString(R.string.set_export_stats)))
        }
    }
}

private fun paletteName(id: String): Int = when (id) {
    "mono" -> R.string.palette_mono
    else -> R.string.palette_coral
}

/** One card per colour scheme: its name and a strip of the colours it uses; the chosen one is outlined. */
@Composable
private fun PaletteChooser(current: String, onPick: (String) -> Unit) {
    Column(Modifier.padding(12.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
        app.borderless.ui.theme.Schemes.all.forEach { scheme ->
            val selected = scheme.id == current
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Palette.background)
                    .border(if (selected) 2.dp else 1.dp, if (selected) Palette.accent else Palette.outline, RoundedCornerShape(16.dp))
                    .clickable { onPick(scheme.id) }
                    .padding(12.dp),
            ) {
                androidx.compose.material3.Text(
                    stringResource(paletteName(scheme.id)), fontSize = 15.sp,
                    fontWeight = if (selected) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal,
                    color = Palette.text,
                )
                Spacer(Modifier.height(8.dp))
                androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().height(22.dp).clip(RoundedCornerShape(6.dp)).border(1.dp, Palette.outline, RoundedCornerShape(6.dp))) {
                    scheme.preview.forEach { c -> androidx.compose.foundation.layout.Box(Modifier.weight(1f).fillMaxHeight().background(c)) }
                }
            }
        }
    }
}

/** Asks Android to put the on/off tile into Quick Settings (Android 13+ shows a system dialog). */
@Composable
private fun AddTileRow() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    NavRow(stringResource(R.string.set_add_tile), stringResource(R.string.set_add_tile_sub)) {
        app.borderless.data.Errors.guard("adding the tile", Unit) {
            val sbm = context.getSystemService(android.app.StatusBarManager::class.java)
            sbm.requestAddTileService(
                android.content.ComponentName(context, app.borderless.service.ToggleTileService::class.java),
                context.getString(R.string.app_name),
                android.graphics.drawable.Icon.createWithResource(context, R.drawable.ic_stat_connected),
                context.mainExecutor,
            ) { }
        }
    }
}
