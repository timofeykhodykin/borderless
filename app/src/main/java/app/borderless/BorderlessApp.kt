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
        AppLog.debug("Device", DeviceProfile.info.describe())
        Crypto.initError?.let { Errors.report(it, "encryption key", getString(R.string.err_crypto)) }
        // Files handed to other apps for sharing (logs) are not kept longer than needed.
        runCatching { java.io.File(cacheDir, "share").deleteRecursively() }
        Repo.init(this)
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
        scope.launch(Dispatchers.IO) { Errors.guard("Xray core", Unit) { CoreEnv.ensure() } }
        scope.launch(Dispatchers.Default) { Errors.guard("energy bookkeeping", Unit, show = false) { Energy.calibrate() } }
        Errors.guard("notification channel", Unit, show = false) { Notifications.ensureChannel(this) }
        // The tile mirrors the tunnel status.
        // The chosen colour scheme, from the first frame on and whenever it changes.
        Palette.scheme = Schemes.byId(Repo.settings.value.palette)
        AppIcon.applyIfIdle(this, Repo.settings.value.palette)
        scope.launch {
            // The launcher icon follows later, when the app goes to the background (MainActivity.onStop):
            // switching it while the app is open makes Android close the app.
            Repo.settings.map { it.palette }.distinctUntilChanged().collect { Palette.scheme = Schemes.byId(it) }
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
