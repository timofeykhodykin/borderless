package app.borderless

import app.borderless.ui.AppIcon
import app.borderless.core.Eco
import app.borderless.data.DeviceProfile
import app.borderless.data.SettingsLog
import app.borderless.ui.theme.Schemes
import app.borderless.ui.theme.Palette
import app.borderless.data.Energy
import kotlinx.coroutines.flow.map
import app.borderless.data.CurrentMeter
import app.borderless.core.Power
import kotlinx.coroutines.flow.distinctUntilChanged
import app.borderless.data.Crypto
import app.borderless.data.Errors
import android.app.Application
import app.borderless.core.CoreEnv
import app.borderless.core.Scanner
import app.borderless.core.TunnelState
import app.borderless.data.AppLog
import app.borderless.data.Repo
import app.borderless.data.StatsDb
import app.borderless.service.Notifications
import app.borderless.service.ToggleTileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BorderlessApp : Application() {
    /** The palette's light or dark scheme, as the settings and (when they follow it) the system's theme say. */
    private fun applyScheme(config: android.content.res.Configuration = resources.configuration) {
        val night = (config.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val s = Repo.settings.value
        Palette.scheme = Schemes.byId(s.palette, s.darkNow(night))
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        applyScheme(newConfig)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + Errors.handler)

    override fun onCreate() {
        super.onCreate()
        Res.init(this)
        Power.init(this)
        // The phone's own power figures, before any energy is estimated.
        Errors.guard("power profile", Unit, show = false) { DeviceProfile.load(Power.voltageMv?.let { it / 1000.0 }) }
        CurrentMeter.init(this)
        // The data key first: logs and data files are stored encrypted.
        Crypto.init(this)
        AppLog.init(this)
        // From here on crashes are saved and shown on the next start; other failures as a dialog.
        Errors.install(this)
        AppLog.debug(
            "app", "process started: Border(less) ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}${if (BuildConfig.DEBUG) ", debug" else ""}), " +
                "Android ${android.os.Build.VERSION.RELEASE}, pid ${android.os.Process.myPid()}",
        )
        AppLog.debug("Device", DeviceProfile.info.describe())
        Crypto.initError?.let { Errors.report(it, "encryption key", getString(if (Crypto.locked) R.string.err_crypto_locked else R.string.err_crypto)) }
        // Files handed to other apps for sharing (logs) are not kept longer than needed.
        runCatching { java.io.File(cacheDir, "share").deleteRecursively() }
        Repo.init(this)
        Errors.guard("detailed log", Unit, show = false) { Repo.expireVerbose() }
        app.borderless.core.Updater.init(this)
        Errors.guard("statistics database", Unit) {
            StatsDb.init(this)
            StatsDb.prune(Repo.settings.value.statsDays)
        }
        Scanner.refreshRanking()
        // Screenshot build only (see src/demo): made-up servers and statistics.
        if (BuildConfig.DEMO) runCatching { Class.forName("app.borderless.demo.Demo").getMethod("seed", android.content.Context::class.java).invoke(Class.forName("app.borderless.demo.Demo").getField("INSTANCE").get(null), this) }
        SettingsLog.start()
        Errors.guard("battery saving", Unit, show = false) { Eco.init(this) }
        Errors.guard("Xray core", Unit) { CoreEnv.attach(this) }
        Errors.guard("limited-network servers", Unit, show = false) { app.borderless.core.Limited.attach(this) }
        // Android's own battery accounting for the app: what it offers here, and the CPU power learned from it (hourly).
        Errors.guard("system power", Unit, show = false) {
            app.borderless.data.SystemPower.init(this)
            AppLog.debug("Energy", app.borderless.data.SystemPower.take()?.let { "Android's accounting for the app (since its last reset): ${it.describe()}" } ?: "Android's accounting for the app: not available")
        }
        scope.launch(Dispatchers.IO) {
            while (true) {
                Errors.guard("system power", Unit, show = false) { app.borderless.data.SystemPower.recalibrate() }
                kotlinx.coroutines.delay(3_600_000L)
            }
        }
        scope.launch(Dispatchers.IO) { Errors.guard("Xray core", Unit) { CoreEnv.ensure() } }
        Errors.guard("notification channel", Unit, show = false) { Notifications.ensureChannel(this) }
        // The tile mirrors the tunnel status.
        // The chosen colour scheme, from the first frame on and whenever it changes (also the system's theme:
        // onConfigurationChanged).
        applyScheme()
        AppIcon.applyIfIdle(this, Repo.settings.value.palette)
        scope.launch {
            // The launcher icon follows later, when the app goes to the background (MainActivity.onStop):
            // switching it while the app is open makes Android close the app.
            Repo.settings.map { Triple(it.palette, it.darkTheme, it.themeFollowsSystem) }.distinctUntilChanged().collect { applyScheme() }
        }
        // Diagnostic current measurement follows its setting.
        scope.launch {
            Repo.settings.map { it.measureCurrent }.distinctUntilChanged().collect { on -> if (on) CurrentMeter.start() else CurrentMeter.stop() }
        }
        // The Quick Settings tile is redrawn only when what it shows changes.
        scope.launch {
            TunnelState.status.distinctUntilChanged { a, b -> a.phase == b.phase && a.serverId == b.serverId }
                .collect { Errors.guard("quick settings tile", Unit, show = false) { ToggleTileService.refresh(this@BorderlessApp) } }
        }
    }
}
