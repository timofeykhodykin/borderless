package app.borderless.core

import android.content.Context
import app.borderless.R
import app.borderless.data.Activity
import app.borderless.data.AppLog
import app.borderless.data.Energy
import app.borderless.data.Errors
import app.borderless.data.GeoFile
import app.borderless.data.LText
import app.borderless.data.Regions
import app.borderless.data.Repo
import app.borderless.data.Units
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL

/**
 * Keeps the routing lists (geoip.dat / geosite.dat) fresh. The APK carries a snapshot without the lists that change
 * daily (`notBundled` in `regions.json`), which are downloaded as soon as a zone that uses them is on
 * (first start, or turning the zone on). Every few days
 * ([app.borderless.data.AppSettings.geoUpdateDays]) the app asks each source in `regions.json` for the hash of its
 * current files (a few bytes) and, if they changed, downloads only the categories it uses: the remote file is read in
 * ranges, the entries' small headers are walked and only the wanted bodies are fetched ([GeoFile.pick]) — about a
 * tenth of the full files. Directly, or through one of the user's servers if the source can't be reached. The new
 * files replace the old ones atomically and the core is restarted to load them ([version]).
 */
object GeoLists {
    private const val TAG = "Geo"
    private const val STATE = "lists.json"
    private val names = listOf("geoip.dat", "geosite.dat")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** [updatedAt]: when the lists in use were published / downloaded; [sha]: hash of the source file each came from. */
    @Serializable
    data class State(
        val updatedAt: Long = 0,
        val checkedAt: Long = 0,
        val sha: Map<String, String> = emptyMap(),
        val lastError: String? = null,
        /** Failed attempts in a row (sets how soon the next one comes while lists are missing). */
        val failures: Int = 0,
    ) {
        /** When to try again while a chosen zone's lists are missing: soon at first, then less often (1…30 min). */
        val retryAt: Long get() = if (failures == 0) 0 else checkedAt + RETRY_STEPS_MIN[minOf(failures, RETRY_STEPS_MIN.size) - 1] * 60_000L
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** What a running update is doing: downloading directly, or looking for / going through a server. */
    enum class Work { DOWNLOADING, VIA_SERVER }

    private val _work = MutableStateFlow<Work?>(null)
    val work: StateFlow<Work?> = _work.asStateFlow()

    private val _version = MutableStateFlow(0)
    /** Changes when new lists were written: the running core should restart to load them. */
    val version: StateFlow<Int> = _version.asStateFlow()

    private val mutex = Mutex()
    private lateinit var dir: File

    /** Files written by [install] and by an update are never touched at the same time. */
    val fileLock = Any()

    /**
     * Puts the lists where the core reads them: the APK's snapshot, unless the lists downloaded earlier are newer
     * (an app update must not roll them back). Called by [CoreEnv.init].
     */
    fun install(context: Context, geoDir: File, apkChanged: Boolean) = synchronized(fileLock) {
        dir = geoDir
        val bundled = runCatching {
            json.decodeFromString(State.serializer(), context.assets.open("geo.json").bufferedReader().use { it.readText() })
        }.getOrDefault(State())
        val saved = readState()
        val keep = saved.updatedAt > bundled.updatedAt && names.all { File(geoDir, it).exists() }
        if (apkChanged && !keep || names.any { !File(geoDir, it).exists() }) {
            for (name in names) context.assets.open(name).use { input -> File(geoDir, name).outputStream().use { input.copyTo(it) } }
            writeState(bundled.copy(checkedAt = saved.checkedAt))
        } else {
            _state.value = saved
        }
        scanInstalled()
    }

    /** Which categories the files have now ([GeoFile.installed]: rules naming others are left out of the config). */
    private fun scanInstalled() {
        GeoFile.installed = Errors.guard("routing lists", null, show = false) {
            names.associate { name ->
                val f = File(dir, name)
                java.io.RandomAccessFile(f, "r").use { raf ->
                    name.removeSuffix(".dat") to GeoFile.codes({ o, n -> ByteArray(n).also { raf.seek(o); raf.readFully(it) } }, raf.length())
                }
            }
        }
    }

    /**
     * Categories the zones (all of them: a zone can only be switched on once its lists are here) and the common lists
     * use, by file, that the files don't have yet.
     */
    fun missing(): Map<String, Set<String>> {
        val have = GeoFile.installed ?: return emptyMap()
        val want = wanted()
        return names.associateWith { name -> want[name].orEmpty() - have[name.removeSuffix(".dat")].orEmpty() }.filterValues { it.isNotEmpty() }
    }

    /** By file: the categories of the common lists and of every zone. */
    private fun wanted(): Map<String, Set<String>> {
        val sources = Regions.geoSources(Regions.all.map { it.code })
        return mapOf(
            "geoip.dat" to sources.flatMap { it.geoip.keys }.map { it.uppercase() }.toSet(),
            "geosite.dat" to sources.flatMap { it.geosite.keys }.map { it.uppercase() }.toSet(),
        )
    }

    /**
     * Checks for new lists if it's time (or now, if [force]); returns null when nothing went wrong. [connected]: a
     * server has just connected — missing lists are tried at once (through it, if the source isn't reachable directly).
     */
    suspend fun updateIfDue(force: Boolean = false, connected: Boolean = false): String? = withContext(Dispatchers.IO) {
        if (!::dir.isInitialized) Errors.guard("core", Unit, show = false) { CoreEnv.ensure() }
        if (!::dir.isInitialized) return@withContext null
        val s = Repo.settings.value
        val now = System.currentTimeMillis()
        // Lists a chosen zone needs and doesn't have yet (some are not shipped): now, whatever the interval; after a
        // failure again in 1, 2, 5, 10, then every 30 minutes (and at once when a server connects, see Engine).
        val needed = missing().isNotEmpty() && (connected || now >= _state.value.retryAt)
        if (!force && !needed && !GeoFile.due(_state.value.checkedAt, s.geoUpdateDays, Power.metered, s.ecoOn, now)) return@withContext null
        if (!mutex.tryLock()) return@withContext null
        _busy.value = true
        AppLog.debug(TAG, "checking for updates: " + when {
            force -> "asked by the user"
            needed -> "missing ${missing().values.sumOf { it.size }} categories"
            else -> "scheduled (every ${s.geoUpdateDays} d)"
        })
        try {
            Activity.count(Activity.Kind.GEO_UPDATE)
            val span = Energy.begin(Activity.Kind.GEO_UPDATE)
            this@GeoLists.span = span
            try { run() } finally { this@GeoLists.span = null; Energy.end(span) }
        } finally {
            _busy.value = false
            _work.value = null
            mutex.unlock()
        }
    }

    private suspend fun run(): String? {
        val now = System.currentTimeMillis()
        _work.value = Work.DOWNLOADING
        var via = false
        val direct = update(Proxy.NO_PROXY)
        val result = direct.exceptionOrNull()?.let { e ->
            _work.value = Work.VIA_SERVER
            // Neither the source nor its mirrors answer directly: try through working servers (up to two).
            AppLog.debug(TAG, "direct download failed: ${e.message}")
            var last: Result<Map<String, String>?> = direct
            for (server in ViaServer.candidates(2)) {
                last = runCatching { ViaServer.withProxy(server) { proxy -> update(proxy).getOrThrow() } }
                if (last.isSuccess) { via = true; break }
                AppLog.debug(TAG, "through ${server.name}: ${last.exceptionOrNull()?.message}")
            }
            last
        } ?: direct
        return result.fold(
            onSuccess = { changed ->
                writeState(_state.value.copy(checkedAt = now, lastError = null, failures = 0, updatedAt = if (changed != null) now else _state.value.updatedAt).let {
                    if (changed != null) it.copy(sha = it.sha + changed) else it
                })
                val how = if (via) LText.of(R.string.ev_through_server) else ""
                if (changed != null) {
                    AppLog.event(LText.concat(LText.of(R.string.ev_geo_updated, fetched.second, Units.bytes(fetched.first)), how))
                    _version.value++
                } else AppLog.event(LText.concat(LText.of(R.string.ev_geo_checked), how))
                null
            },
            onFailure = { e ->
                val msg = e.message ?: e.javaClass.simpleName
                AppLog.debug(TAG, "update failed: $msg")
                val failed = _state.value.copy(checkedAt = now, lastError = msg, failures = _state.value.failures + 1)
                writeState(failed)
                // The user should know when a chosen zone's lists couldn't be fetched (not for a routine refresh).
                if (missing().isNotEmpty()) {
                    AppLog.event(LText.of(R.string.ev_geo_failed, Units.durationL((failed.retryAt - now) / 1000)), AppLog.Kind.WARN)
                } else AppLog.event(LText.of(R.string.ev_geo_check_failed, msg), AppLog.Kind.WARN)
                msg
            },
        )
    }

    /**
     * One pass over every source through [proxy]. Returns the new hashes (by source + file) if anything was
     * written, null if every file was unchanged; throws [IOException] if a source can't be reached.
     */
    /** Bytes and categories the last [update] downloaded (for the event). */
    @Volatile
    private var fetched = 0L to 0

    /** The running update's energy span: its requests and bytes size its Wi-Fi radio time. */
    @Volatile
    private var span: Energy.Span? = null

    private fun update(proxy: Proxy): Result<Map<String, String>?> = runCatching {
        var downloaded = 0L
        val newSha = HashMap<String, String>()
        val picked = HashMap<String, MutableMap<String, ByteArray>>() // file -> code -> entry
        // One pass per source (the common lists and a region's usually come from the same files).
        val missing = missing()
        val sources = Regions.geoSources(Regions.all.map { it.code }).groupBy { it.source }.map { (url, parts) ->
            app.borderless.data.GeoSource(
                url, parts.fold(emptyMap()) { m, p -> m + p.geoip }, parts.fold(emptyMap()) { m, p -> m + p.geosite },
                mirrors = parts.flatMap { it.mirrors }.distinct(),
            )
        }
        for (src in sources) {
            // The source, else its mirrors (same files): the first one that answers is used for this source.
            val base = (listOf(src.source) + src.mirrors).firstNotNullOfOrNull { url ->
                runCatching { resolve(url, proxy).also { text(it + names.first() + ".sha256sum", proxy) } }
                    .onFailure { AppLog.debug(TAG, "${URL(url).host}: ${it.message}") }.getOrNull()
            } ?: throw IOException("no source answers (${(listOf(src.source) + src.mirrors).joinToString { URL(it).host }})")
            for (name in names) {
                // Local name → hash of the source's name; looked up by hash, written under the local name.
                val byLocal = if (name == "geoip.dat") src.geoip else src.geosite
                val wanted = byLocal.keys.map { it.uppercase() }.toSet()
                if (wanted.isEmpty()) continue
                val key = src.source + name
                val sha = text(base + name + ".sha256sum", proxy).trim().substringBefore(' ').lowercase()
                // Unchanged and nothing of it missing here: no download.
                if (sha.isEmpty() || sha == _state.value.sha[key] && wanted.none { it in missing[name].orEmpty() }) continue
                val reader = RangeReader(base + name, proxy)
                val entries = GeoFile.pickHashed(reader, reader.size, byLocal.entries.associate { (local, h) -> h to local })
                downloaded += reader.bytes
                span?.let { it.exchanges += reader.requests; it.bytes += reader.bytes }
                AppLog.debug(TAG, "$name from ${URL(base).host}: ${entries.size} of ${wanted.size} categories, ${reader.bytes / 1024} KB in ${reader.requests} requests")
                val target = picked.getOrPut(name) { LinkedHashMap() }
                entries.forEach { (code, raw) -> target.putIfAbsent(code, raw) }
                newSha[key] = sha
            }
        }
        Activity.count(Activity.Kind.GEO_UPDATE, 0, downloaded)
        fetched = downloaded to picked.values.sumOf { it.size }
        if (newSha.isEmpty()) return@runCatching null
        synchronized(fileLock) {
            for ((name, fresh) in picked) {
                // Categories a source no longer has stay as they were.
                val current = GeoFile.entries(File(dir, name).readBytes())
                val merged = LinkedHashMap<String, ByteArray>()
                current.forEach { merged[it.code.uppercase()] = fresh[it.code.uppercase()] ?: it.raw }
                fresh.forEach { (code, raw) -> merged.putIfAbsent(code, raw) }
                val tmp = File(dir, "$name.tmp")
                tmp.writeBytes(GeoFile.write(merged.values))
                // Make sure it reads back before it replaces the file the core uses.
                GeoFile.entries(tmp.readBytes())
                if (!tmp.renameTo(File(dir, name))) throw IOException("cannot replace $name")
            }
            scanInstalled()
        }
        newSha
    }.recoverCatching { e -> throw if (e is IOException) e else IOException(e.message ?: e.javaClass.simpleName, e) }

    /** GitHub's "latest release" folder → the folder of that release, so every request reads the same files. */
    private fun resolve(source: String, proxy: Proxy): String {
        val m = Regex("^(https://github\\.com/[^/]+/[^/]+)/releases/latest/download/$").find(source) ?: return source
        val c = open(m.groupValues[1] + "/releases/latest", proxy).apply { instanceFollowRedirects = false; requestMethod = "HEAD" }
        try {
            val tag = c.getHeaderField("Location")?.substringAfter("/releases/tag/", "")?.takeIf { it.isNotEmpty() } ?: return source
            return m.groupValues[1] + "/releases/download/" + tag + "/"
        } finally {
            c.disconnect()
        }
    }

    private fun text(url: String, proxy: Proxy): String {
        val c = open(url, proxy)
        try {
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode} for ${URL(url).path}")
            return c.inputStream.use { it.readNBytes(4096) }.toString(Charsets.UTF_8)
        } finally {
            c.disconnect()
        }
    }

    private fun open(url: String, proxy: Proxy) = (URL(url).openConnection(proxy) as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 20_000
        setRequestProperty("User-Agent", "Border(less)")
    }

    /**
     * A remote file read in ranges through a window (headers between wanted entries are close together, one
     * request covers many). After the first answer the final (redirected) address is used directly.
     */
    private class RangeReader(private val origin: String, private val proxy: Proxy) : GeoFile.Source {
        private var url = origin
        var size = -1L
            private set
        var bytes = 0L
            private set
        var requests = 0
            private set
        private var winStart = 0L
        private var win = ByteArray(0)

        init {
            fetch(0, WINDOW)
        }

        override fun read(offset: Long, len: Int): ByteArray {
            if (offset >= winStart && offset + len <= winStart + win.size) {
                val from = (offset - winStart).toInt()
                return win.copyOfRange(from, from + len)
            }
            if (len > WINDOW) return fetchExact(offset, len)
            fetch(offset, WINDOW)
            return read(offset, minOf(len, win.size))
        }

        private fun fetch(offset: Long, len: Int) {
            win = fetchExact(offset, if (size > 0) minOf(len.toLong(), size - offset).toInt() else len)
            winStart = offset
        }

        private fun fetchExact(offset: Long, len: Int, retry: Boolean = true): ByteArray {
            val c = (URL(url).openConnection(proxy) as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("Range", "bytes=$offset-${offset + len - 1}")
            }
            try {
                requests++
                val code = c.responseCode
                // The redirected address is signed for a few minutes: when it expires, start over from the original.
                if (code in 400..499 && url != origin && retry) {
                    url = origin
                    return fetchExact(offset, len, retry = false)
                }
                if (code != 206) throw IOException("the source doesn't serve parts of files (HTTP $code)")
                if (size < 0) size = c.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: throw IOException("no file size")
                url = c.url.toString()
                val expected = minOf(len.toLong(), size - offset).toInt()
                val data = c.inputStream.use { it.readNBytes(expected) }
                bytes += data.size
                if (data.size != expected) throw IOException("short read at $offset")
                return data
            } finally {
                c.disconnect()
            }
        }

        companion object {
            const val WINDOW = 64 * 1024
        }
    }

    private val RETRY_STEPS_MIN = longArrayOf(1, 2, 5, 10, 30)

    private fun readState(): State =
        runCatching { json.decodeFromString(State.serializer(), File(dir, STATE).readText()) }.getOrDefault(State())

    private fun writeState(s: State) {
        _state.value = s
        Errors.guard("routing lists", Unit, show = false) { File(dir, STATE).writeText(json.encodeToString(State.serializer(), s)) }
    }
}
