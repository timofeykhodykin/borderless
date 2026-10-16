package app.borderless.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import app.borderless.BuildConfig
import app.borderless.R
import app.borderless.data.AppLog
import app.borderless.data.Errors
import app.borderless.data.LText
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
 * Updates from the project's releases: once a day (and when asked) the latest release — never a pre-release — is
 * looked up; a newer one is offered at the top of the settings and next to "About" (no notification). Updating
 * downloads the APK for this phone's processor, checks it against the release's SHA-256 list and hands it to the
 * system installer, which accepts it only if it is signed with the same key as the installed app.
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

    fun init(context: Context) {
        val p = context.getSharedPreferences("update", Context.MODE_PRIVATE).also { prefs = it }
        _checkedAt.value = p.getLong("checkedAt", 0)
        _dismissed.value = p.getInt("dismissed", 0)
        // What the last check found, if still newer than this build (after an update it no longer is).
        val code = p.getInt("code", 0)
        if (code > BuildConfig.VERSION_CODE) {
            _available.value = Release(p.getString("version", "")!!, code, p.getString("apk", "")!!, p.getString("apkName", "")!!, p.getString("sums", null))
        }
    }

    fun dismiss() {
        val code = _available.value?.code ?: return
        _dismissed.value = code
        prefs?.edit()?.putInt("dismissed", code)?.apply()
    }

    /** Looks for a release if the last look is a day old ([force]: now). */
    suspend fun checkIfDue(force: Boolean = false) {
        if (!force && System.currentTimeMillis() - _checkedAt.value < CHECK_EVERY_MS) return
        check()
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
        val file = File(File(context.cacheDir, "share").apply { mkdirs() }, r.apkName)
        if (_state.value != State.Ready || !file.exists()) {
            _state.value = State.Downloading(0)
            val got = withContext(Dispatchers.IO) { anyRoute { proxy -> download(r, file, proxy) } }
            got.exceptionOrNull()?.let { e ->
                file.delete()
                AppLog.event(LText.of(R.string.ev_update_failed, e.message ?: e.javaClass.simpleName), AppLog.Kind.WARN)
                _state.value = State.Failed(e.message ?: e.javaClass.simpleName)
                return@withLock
            }
            AppLog.debug(TAG, "downloaded ${r.apkName}, ${file.length() / 1024} KB, checksum matches")
            _state.value = State.Ready
        }
        // The installer refuses an APK signed by someone else anyway; saying so here is clearer than its error.
        if (!sameSigner(context, file)) {
            file.delete()
            AppLog.event(LText.of(R.string.ev_update_failed, "signed with another key"), AppLog.Kind.WARN)
            _state.value = State.Failed("signed with another key")
            return@withLock
        }
        Errors.guard("installing the update", Unit) { install(context, file) }
    }

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
        AppLog.debug(TAG, "direct: ${direct.exceptionOrNull()?.message}")
        var last = direct
        for (server in ViaServer.candidates(2)) {
            last = runCatching { ViaServer.withProxy(server) { proxy -> use(proxy) } }
            if (last.isSuccess) return last
        }
        return last
    }

    /** The latest release (GitHub leaves pre-releases out of "latest") with an APK for this phone, or null. */
    private fun latest(proxy: Proxy): Release? {
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

    private fun download(r: Release, file: File, proxy: Proxy) {
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

    /** "owner/name" of the project, decoded (kept out of the app's strings). */
    internal fun project(): String {
        val key = byteArrayOf(0x5a, 0x13, 0xc7.toByte(), 0x2e, 0x91.toByte(), 0x44, 0x0b, 0xe8.toByte())
        val enc = byteArrayOf(46, 122, -86, 65, -9, 33, 114, -125, 50, 124, -93, 87, -6, 45, 101, -57, 56, 124, -75, 74, -12, 54, 103, -115, 41, 96)
        return String(ByteArray(enc.size) { i -> (enc[i].toInt() xor key[i % key.size].toInt()).toByte() })
    }
}
