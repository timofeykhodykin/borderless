package app.borderless.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import app.borderless.BuildConfig
import app.borderless.R
import app.borderless.Res
import app.borderless.data.AppLog
import app.borderless.data.Errors
import app.borderless.data.LText
import app.borderless.data.Repo
import app.borderless.ui.MainActivity
import app.borderless.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.security.MessageDigest

/**
 * Updates from the project's releases: once a day (on opening the app and from the engine's hourly check; and when
 * asked) the latest release — never a pre-release — is looked up; a newer one is offered at the top of the settings
 * and next to "About". Updating downloads the APK for this phone's processor, checks it against the release's SHA-256
 * list and hands it to the system installer, which accepts it only if it is signed with the same key as the installed
 * app.
 *
 * With [AppSettings.autoUpdate] (on by default) a new version is downloaded in the background over an unmetered
 * network and installed through [PackageInstaller] without asking, once the screen is off (Android 12+ lets an app
 * update itself that way when it may install apps). Where that isn't possible — the setting off, Android 11 or older,
 * installing from this app not allowed, the system wanting a confirmation, three days on mobile data only — one
 * notification per version offers the update instead; swiped away, it doesn't come back for that version.
 *
 * Requests go direct (the app's own traffic is outside the tunnel), else through a working server. The project's
 * address is kept encoded, not as plain text.
 */
object Updater {
    private const val TAG = "Updater"
    private const val CHECK_EVERY_MS = 24 * 3_600_000L
    private const val MAX_APK = 150L * 1024 * 1024

    /** A newer release: its version, its code (see [codeOf]) and the APK to take. */
    data class Release(val version: String, val code: Int, val apkUrl: String, val apkName: String, val sumsUrl: String?)

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data class Downloading(val percent: Int) : State
        /** Downloaded and checked; installing is up to the system's dialog. */
        data object Ready : State
        /** Downloaded and checked; installs by itself when the screen turns off (automatic updates). */
        data object Waiting : State
        data class Failed(val reason: String) : State
    }

    private val _available = MutableStateFlow<Release?>(null)
    /** A release newer than this build, if one was found. */
    val available: StateFlow<Release?> = _available.asStateFlow()

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _checkedAt = MutableStateFlow(0L)
    val checkedAt: StateFlow<Long> = _checkedAt.asStateFlow()

    private val _dismissed = MutableStateFlow(0)
    /** The release code whose offer at the top of the settings was closed (the row next to "About" stays). */
    val dismissed: StateFlow<Int> = _dismissed.asStateFlow()

    private val lock = Mutex()
    private var prefs: android.content.SharedPreferences? = null
    private lateinit var app: Context

    /** A quiet install waits for the screen to turn off (one receiver while waiting). */
    private var screenOffWatch: BroadcastReceiver? = null

    fun init(context: Context) {
        app = context.applicationContext
        val p = context.getSharedPreferences("update", Context.MODE_PRIVATE).also { prefs = it }
        // The first start of a newer version than last time: an update went through.
        val last = p.getInt("lastCode", 0)
        if (last in 1 until BuildConfig.VERSION_CODE) AppLog.event(LText.of(R.string.ev_updated, BuildConfig.VERSION_NAME))
        if (last != BuildConfig.VERSION_CODE) p.edit().putInt("lastCode", BuildConfig.VERSION_CODE).apply()
        _checkedAt.value = p.getLong("checkedAt", 0)
        _dismissed.value = p.getInt("dismissed", 0)
        // What the last check found, if still newer than this build (after an update it no longer is).
        val code = p.getInt("code", 0)
        if (code > BuildConfig.VERSION_CODE) {
            _available.value = Release(p.getString("version", "")!!, code, p.getString("apk", "")!!, p.getString("apkName", "")!!, p.getString("sums", null))
        } else runCatching { File(context.cacheDir, "update").deleteRecursively() } // installed, or nothing to install
    }

    fun dismiss() {
        val code = _available.value?.code ?: return
        _dismissed.value = code
        prefs?.edit()?.putInt("dismissed", code)?.apply()
    }

    /**
     * Looks for a release if the last look is a day old ([force]: now), then goes on with a newer one found now or
     * earlier ([proceed]: background download and install, or the notification).
     */
    suspend fun checkIfDue(force: Boolean = false) {
        if (force || System.currentTimeMillis() - _checkedAt.value >= CHECK_EVERY_MS) check()
        _available.value?.let { proceed(it) }
    }

    private suspend fun check() = lock.withLock {
        _state.value = State.Checking
        val result = withContext(Dispatchers.IO) { anyRoute { proxy -> latest(proxy) } }
        result.onSuccess { r ->
            val now = System.currentTimeMillis()
            _checkedAt.value = now
            val newer = r?.takeIf { it.code > BuildConfig.VERSION_CODE }
            if (newer != null && newer.code != _available.value?.code) {
                AppLog.event(LText.of(R.string.ev_update_found, newer.version))
            }
            AppLog.debug(TAG, "latest release: ${r?.version ?: "none"}" + if (newer == null) " (this build is up to date)" else "")
            if (newer != null && newer.code != _available.value?.code) prefs?.edit()?.putLong("foundAt", now)?.apply()
            _available.value = newer
            prefs?.edit()?.apply {
                putLong("checkedAt", now)
                putInt("code", newer?.code ?: 0)
                putString("version", newer?.version); putString("apk", newer?.apkUrl); putString("apkName", newer?.apkName); putString("sums", newer?.sumsUrl)
            }?.apply()
            _state.value = State.Idle
        }.onFailure { e ->
            AppLog.debug(TAG, "check failed: ${e.message}")
            _state.value = State.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Downloads the offered release and opens the system installer. The first time Android asks to allow installing
     * from this app: the settings page opens, and tapping "Update" again installs the file already downloaded.
     */
    suspend fun update(context: Context) = lock.withLock {
        val r = _available.value ?: return@withLock
        val file = obtain(r) ?: return@withLock
        Errors.guard("installing the update", Unit) { install(context, file) }
    }

    /** Not in `cache/share` (emptied at every process start): a download waiting for the screen to turn off stays. */
    private fun apkFile(r: Release) = File(File(app.cacheDir, "update").apply { mkdirs() }, r.apkName)

    /** The release's APK downloaded, checked and signed with our key ([State.Ready]), or null (then [State.Failed]). */
    private suspend fun obtain(r: Release): File? {
        val file = apkFile(r)
        val ready = prefs?.getInt("readyCode", 0) == r.code && file.exists()
        if (!ready) {
            // An older version's download is no longer needed.
            file.parentFile?.listFiles()?.filter { it != file }?.forEach { it.delete() }
            _state.value = State.Downloading(0)
            val got = try {
                withContext(Dispatchers.IO) { anyRoute { proxy -> download(r, file, proxy) } }
            } catch (e: kotlinx.coroutines.CancellationException) {
                file.delete()
                _state.value = State.Idle
                throw e
            }
            got.exceptionOrNull()?.let { e ->
                file.delete()
                AppLog.event(LText.of(R.string.ev_update_failed, e.message ?: e.javaClass.simpleName), AppLog.Kind.WARN)
                _state.value = State.Failed(e.message ?: e.javaClass.simpleName)
                return null
            }
            AppLog.debug(TAG, "downloaded ${r.apkName}, ${file.length() / 1024} KB, checksum matches")
        }
        // The installer refuses an APK signed by someone else anyway; saying so here is clearer than its error.
        if (!sameSigner(app, file)) {
            file.delete()
            prefs?.edit()?.remove("readyCode")?.apply()
            AppLog.event(LText.of(R.string.ev_update_failed, "signed with another key"), AppLog.Kind.WARN)
            _state.value = State.Failed("signed with another key")
            return null
        }
        prefs?.edit()?.putInt("readyCode", r.code)?.apply()
        _state.value = State.Ready
        return file
    }

    /**
     * A newer release is known: with auto-update, download it (unmetered networks only) and install it quietly once
     * the screen is off; otherwise, or when that can't be done, offer it once in a notification.
     */
    private suspend fun proceed(r: Release) {
        if (!Repo.settings.value.autoUpdate) return notifyOnce(r)
        val why = when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S -> "Android 11 or older asks before every install"
            !app.packageManager.canRequestPackageInstalls() -> "installing from this app isn't allowed yet"
            prefs?.getInt("quietRefused", 0) == r.code -> "the system asked for a confirmation"
            else -> null
        }
        if (why != null) {
            AppLog.debug(TAG, "no automatic update to ${r.version}: $why")
            return notifyOnce(r)
        }
        val ready = prefs?.getInt("readyCode", 0) == r.code && apkFile(r).exists()
        if (!ready && Power.metered) {
            // 40-odd MB: waits for Wi-Fi, but not for ever.
            val found = prefs?.getLong("foundAt", 0L) ?: 0L
            AppLog.debug(TAG, "update ${r.version} waits for an unmetered network")
            if (found > 0 && System.currentTimeMillis() - found > METERED_WAIT_MS) notifyOnce(r)
            return
        }
        val file = lock.withLock { if (_available.value?.code == r.code) obtain(r) else null } ?: return
        installWhenIdle(r, file)
    }

    /** Installs at once if the screen is off, else when it turns off (the tunnel comes back by itself after the update). */
    private fun installWhenIdle(r: Release, file: File) {
        if (!Power.screenOn) return installQuietly(r, file)
        synchronized(this) {
            _state.value = State.Waiting
            if (screenOffWatch != null) return
            AppLog.debug(TAG, "update ${r.version} is ready, installing when the screen turns off")
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    synchronized(this@Updater) { runCatching { app.unregisterReceiver(this) }; screenOffWatch = null }
                    val now = _available.value ?: return
                    if (now.code == r.code && file.exists() && Repo.settings.value.autoUpdate) installQuietly(r, file)
                }
            }
            screenOffWatch = receiver
            ContextCompat.registerReceiver(app, receiver, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
        }
    }

    /** Hands [file] to the system as an update of this app that needs no confirmation; the answer comes to [InstallResult]. */
    private fun installQuietly(r: Release, file: File) = Errors.guard("installing the update", Unit, show = false) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return@guard
        AppLog.event(LText.of(R.string.ev_update_installing, r.version))
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("base.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val result = Intent(app, InstallResult::class.java).putExtra(EXTRA_CODE, r.code)
            // Mutable: the installer adds its status to the intent.
            val pending = PendingIntent.getBroadcast(app, id, result, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            session.commit(pending.intentSender)
        }
    }

    /** The installer's answer to [installQuietly]. Success replaces this process, so mostly the other cases arrive. */
    class InstallResult : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
            val code = intent.getIntExtra(EXTRA_CODE, 0)
            if (status == PackageInstaller.STATUS_SUCCESS) return AppLog.debug(TAG, "update installed")
            // Asking is the user's part: leave the quiet way for this version and offer it in a notification.
            val id = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                // E.g. a second quiet update of the same app within half a minute (the system's own limit).
                AppLog.debug(TAG, "the system wants a confirmation for the update")
                abandoned += id
                if (id >= 0) runCatching { context.packageManager.packageInstaller.abandonSession(id) }
            } else {
                // Abandoning the session above reports a failure of its own.
                if (id in abandoned) return
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "status $status"
                AppLog.event(LText.of(R.string.ev_update_not_installed, msg), AppLog.Kind.WARN)
            }
            prefs?.edit()?.putInt("quietRefused", code)?.apply()
            _available.value?.takeIf { it.code == code }?.let { notifyOnce(it) }
        }
    }

    /** Sessions given up after the system asked for a confirmation. */
    private val abandoned = java.util.Collections.synchronizedSet(HashSet<Int>())

    /** One notification per version (swiped away or opened, it is not posted again for that version). */
    private fun notifyOnce(r: Release) {
        val p = prefs ?: return
        if (p.getInt("notified", 0) == r.code) return
        p.edit().putInt("notified", r.code).apply()
        AppLog.debug(TAG, "offering ${r.version} in a notification")
        Errors.guard("showing the update notification", Unit, show = false) {
            val nm = app.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, Res.s(R.string.update_channel), NotificationManager.IMPORTANCE_DEFAULT))
            }
            val open = PendingIntent.getActivity(
                app, 3, Intent(app, MainActivity::class.java).setAction(ACTION_UPDATE).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val ready = p.getInt("readyCode", 0) == r.code
            nm.notify(
                NOTIFICATION_ID,
                NotificationCompat.Builder(app, CHANNEL)
                    .setSmallIcon(R.drawable.ic_stat_connected)
                    .setColor(Palette.accent.toArgb())
                    .setContentTitle(Res.s(R.string.update_available, r.version))
                    .setContentText(Res.s(if (ready) R.string.update_notif_ready else R.string.update_notif_text))
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build(),
            )
        }
    }

    /** The update notification was opened: it is no longer needed. */
    fun cancelNotification() = runCatching { app.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID) }

    const val ACTION_UPDATE = "app.borderless.action.UPDATE"
    private const val CHANNEL = "update"
    private const val NOTIFICATION_ID = 7
    private const val EXTRA_CODE = "code"
    private const val METERED_WAIT_MS = 3 * 24 * 3_600_000L
    private const val TEST_URL = "test"

    /** Whether [apk] is signed with the same certificates as this app (SECURITY.md S11). */
    private fun sameSigner(context: Context, apk: File): Boolean = Errors.guard("checking the update", false, show = false) {
        val pm = context.packageManager
        fun certs(info: android.content.pm.PackageInfo?): Set<String>? {
            info ?: return null
            val sigs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.signingInfo?.apkContentsSigners
            else @Suppress("DEPRECATION") info.signatures
            return sigs?.map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } }?.toSet()
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
        else @Suppress("DEPRECATION") android.content.pm.PackageManager.GET_SIGNATURES
        val theirs = certs(pm.getPackageArchiveInfo(apk.path, flags))
        val ours = certs(pm.getPackageInfo(context.packageName, flags))
        !theirs.isNullOrEmpty() && theirs == ours
    }

    private fun install(context: Context, file: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** [use] directly, else through up to two working servers. */
    private suspend fun <T> anyRoute(use: suspend (Proxy) -> T): Result<T> {
        val direct = runCatching { use(Proxy.NO_PROXY) }
        if (direct.isSuccess) return direct
        // A cancelled caller is not a failed route.
        (direct.exceptionOrNull() as? kotlinx.coroutines.CancellationException)?.let { throw it }
        AppLog.debug(TAG, "direct: ${direct.exceptionOrNull()?.message}")
        var last = direct
        for (server in ViaServer.candidates(2)) {
            last = runCatching { ViaServer.withProxy(server) { proxy -> use(proxy) } }
            if (last.isSuccess) return last
            (last.exceptionOrNull() as? kotlinx.coroutines.CancellationException)?.let { throw it }
        }
        return last
    }

    /** The latest release (GitHub leaves pre-releases out of "latest") with an APK for this phone, or null. */
    private fun latest(proxy: Proxy): Release? {
        testRelease()?.let { return it }
        val json = Json.parseToJsonElement(text("https://api.github.com/repos/${project()}/releases/latest", proxy)) as JsonObject
        val version = (json["tag_name"] as? JsonPrimitive)?.content?.removePrefix("v") ?: return null
        val code = codeOf(version) ?: return null
        val assets = (json["assets"] as? JsonArray).orEmpty().mapNotNull { a ->
            val o = a as? JsonObject ?: return@mapNotNull null
            val name = (o["name"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            val url = (o["browser_download_url"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            name to url
        }.toMap()
        // The first of the phone's processor kinds (best first) that the release has an APK for.
        // Only names the release process gives (the name becomes a file name here).
        val apk = Build.SUPPORTED_ABIS.firstNotNullOfOrNull { abi -> assets.entries.firstOrNull { isApk(it.key, abi) } } ?: return null
        // A release without its checksums is not offered (SECURITY.md S11).
        val sums = assets.entries.firstOrNull { it.key.startsWith("SHA256SUMS") }?.value
            ?: return null.also { AppLog.debug(TAG, "release $version has no checksum file") }
        return Release(version, code, apk.value, apk.key, sums)
    }

    /**
     * Debug builds only: `files/update.test` holds a version and `files/update.test.apk` its APK, so the whole path
     * (offer, notification, quiet install) can be tried without a release.
     */
    private fun testRelease(): Release? {
        if (!BuildConfig.DEBUG) return null
        val version = File(app.filesDir, "update.test").takeIf { it.exists() }?.readText()?.trim() ?: return null
        return Release(version, codeOf(version) ?: return null, TEST_URL, "borderless-$version-${Build.SUPPORTED_ABIS[0]}.apk", TEST_URL)
    }

    private fun download(r: Release, file: File, proxy: Proxy) {
        if (BuildConfig.DEBUG && r.apkUrl == TEST_URL) return File(app.filesDir, "update.test.apk").copyTo(file, overwrite = true).let { }
        val expected = (r.sumsUrl ?: throw IOException("no checksum file")).let { url ->
            text(url, proxy).lines().map { it.trim().split(Regex("\\s+")) }
                .firstOrNull { it.size >= 2 && it.last().removePrefix("*") == r.apkName }?.first()?.lowercase()
                ?: throw IOException("no checksum for ${r.apkName}")
        }
        val c = open(r.apkUrl, proxy)
        try {
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            val total = c.contentLengthLong
            if (total > MAX_APK) throw IOException("too big: $total bytes")
            val digest = MessageDigest.getInstance("SHA-256")
            var done = 0L
            c.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        done += n
                        if (done > MAX_APK) throw IOException("too big")
                        if (total > 0) _state.value = State.Downloading((done * 100 / total).toInt())
                    }
                }
            }
            val got = digest.digest().joinToString("") { "%02x".format(it) }
            if (got != expected) throw IOException("checksum does not match")
        } finally {
            c.disconnect()
        }
    }

    private fun text(url: String, proxy: Proxy): String {
        val c = open(url, proxy)
        try {
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            return c.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (out.size() < 1_000_000) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
                out.toString(Charsets.UTF_8.name())
            }
        } finally {
            c.disconnect()
        }
    }

    private fun open(url: String, proxy: Proxy) = (URL(url).openConnection(proxy) as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        setRequestProperty("User-Agent", "Border(less)/${BuildConfig.VERSION_NAME}")
        setRequestProperty("Accept", "application/vnd.github+json")
    }

    /** A release asset named the way the release process names APKs (`borderless-<version>-<abi>.apk`). */
    fun isApk(name: String, abi: String): Boolean = Regex("^borderless-[0-9][0-9a-z.-]*-${Regex.escape(abi)}\\.apk$").matches(name)

    /** `MAJOR.MINOR.PATCH` → the version code of its final release (see the release rules); null for others. */
    fun codeOf(version: String): Int? {
        val m = Regex("^(\\d+)\\.(\\d+)\\.(\\d+)$").find(version) ?: return null
        val (a, b, c) = m.destructured
        return (a.toInt() * 10000 + b.toInt() * 100 + c.toInt()) * 100 + 99
    }

    /**
     * The project's page for a new issue with [title] and [body] filled in — what the error dialog offers for a
     * failure that points at a bug in the app. The address stays encoded, like everywhere (see [project]).
     */
    fun issuesUrl(title: String, body: String): String =
        "https://github.com/${project()}/issues/new?labels=bug&title=${Uri.encode(title)}&body=${Uri.encode(body)}"

    /** "owner/name" of the project, decoded (kept out of the app's strings). */
    internal fun project(): String {
        val key = byteArrayOf(0x5a, 0x13, 0xc7.toByte(), 0x2e, 0x91.toByte(), 0x44, 0x0b, 0xe8.toByte())
        val enc = byteArrayOf(46, 122, -86, 65, -9, 33, 114, -125, 50, 124, -93, 87, -6, 45, 101, -57, 56, 124, -75, 74, -12, 54, 103, -115, 41, 96)
        return String(ByteArray(enc.size) { i -> (enc[i].toInt() xor key[i % key.size].toInt()).toByte() })
    }
}
