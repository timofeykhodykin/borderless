package app.borderless.bridge

import app.borderless.config.LinkParser
import app.borderless.config.XrayConfig
import app.borderless.core.CoreEnv
import app.borderless.core.ViaServer
import app.borderless.core.XrayHost
import app.borderless.core.xrayAccessLog
import app.borderless.core.xrayLogLevel
import app.borderless.data.AppLog
import app.borderless.data.ListManifest
import app.borderless.data.Repo
import app.borderless.net.Net
import app.borderless.platform.Lock
import app.borderless.platform.base64UrlEncode
import app.borderless.platform.secureRandom
import app.borderless.platform.withLock
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true }

/**
 * What the service's updater (`Sources/Service/Updater.swift`, ruling R2) gives the engine — and the
 * little it needs back. Once [MacUpdater.install] ran, the engine's due-work and the `update_*` service
 * actions reach the updater through [MacEngine.updateHooks], and its state goes to the app as
 * `Status.update`. The engine's part is the release repository's name ([MacUpdater.projectSlug]) and the
 * "else through a working server" route ([MacUpdater.proxyStart] — Android `Updater.anyRoute`'s second
 * leg, `ViaServer`).
 */
interface MacUpdaterHost {
    /** Android's `checkIfDue` — the work runs on the updater's own queue, this returns at once.
     *  [force]: the settings' button asks now, the due-work path keeps the day's pace. */
    fun check(force: Boolean)

    /** The user's confirmed install (`update_install` — Android `Updater.update`). */
    fun install()

    /** The offered version's offer is hidden (`update_dismiss` — Android `Updater.dismiss`). */
    fun dismiss()

    /** The wire's `UpdateStatus` as JSON; "" while there is nothing to tell. */
    fun status(): String
}

object MacUpdater {
    /** Plugs the service's updater into the engine's hooks ([MacEngine.updateHooks]); once, at start. */
    fun install(host: MacUpdaterHost) {
        MacEngine.updateHooks = MacEngine.UpdateHooks(
            check = { force -> host.check(force) },
            install = { host.install() },
            dismiss = { host.dismiss() },
            status = {
                host.status().takeIf { it.isNotEmpty() }?.let {
                    runCatching { json.decodeFromString<MacEngine.UpdateStatus>(it) }.getOrNull()
                }
            },
        )
    }

    /** The project's repository, "owner/name" (kept out of the binaries as plain text, like everywhere). */
    fun projectSlug(): String = ListManifest.project()

    private val proxyLock = Lock()
    private var proxy: XrayHost.Controller? = null
    private val tried = HashSet<String>()

    /**
     * A throwaway local SOCKS proxy through the next untried working server — Android `Updater.anyRoute`'s
     * second leg ([ViaServer.candidates], best first; on the Mac the instance runs inside the helper,
     * bound to the physical interface). Returns "port user pass" for the caller, "" when no candidate is
     * left. [reset] starts a fresh route (the tried set clears); every later call stops the previous
     * proxy and tries the next server. [proxyStop] ends whatever is running.
     */
    fun proxyStart(reset: Boolean): String = runCatching {
        proxyLock.withLock {
            stopLocked()
            if (reset) tried.clear()
            val s = Repo.settings.value
            for (server in ViaServer.candidates(2)) {
                if (!tried.add(server.id)) continue
                val creds = runCatching {
                    val port = Net.host.freePort()
                    val user = base64UrlEncode(secureRandom(18))
                    val pass = base64UrlEncode(secureRandom(18))
                    val config = XrayConfig.localProxy(
                        LinkParser.parse(server.link).outbounds, s, port, user, pass,
                        xrayLogLevel(s), xrayAccessLog(s))
                    val controller = CoreEnv.xray.controller { }
                    CoreEnv.ensure()
                    runBlocking { CoreEnv.startLock.withLock { controller.start(config, 0) } }
                    // The port must answer our password and no other — a stranger's listener on it would
                    // pass the updater's requests somewhere unknown (ViaServer's `ours` rule).
                    if (!Net.host.socksAccepts(port, user, pass) ||
                        Net.host.socksAccepts(port, user, base64UrlEncode(secureRandom(18)))) {
                        runCatching { controller.stop() }
                        throw IllegalStateException("local port $port is not our proxy")
                    }
                    AppLog.debug("update", "local proxy through ${server.name} on 127.0.0.1:$port")
                    proxy = controller
                    "$port $user $pass"
                }.getOrNull()
                if (creds != null) return@withLock creds
            }
            ""
        }
    }.getOrDefault("")

    /** The proxy [proxyStart] left running, if any. */
    fun proxyStop() = proxyLock.withLock { stopLocked() }

    private fun stopLocked() {
        proxy?.let { runCatching { it.stop() } }
        proxy = null
    }
}
