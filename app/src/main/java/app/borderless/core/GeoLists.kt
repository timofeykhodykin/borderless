package app.borderless.core

import app.borderless.R
import app.borderless.data.AppLog
import app.borderless.data.Errors
import app.borderless.data.GeoCheck
import app.borderless.data.GeoFile
import app.borderless.data.LText
import app.borderless.data.ListManifest
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
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL

/**
 * Keeps the routing lists (geoip.dat / geosite.dat). The APK carries none: on the first start every category the
 * zones and the common rules use ([wanted]) is downloaded, and until a zone's categories are here it takes no part
 * (its switch is locked, the main screen says so). Every few days ([app.borderless.data.AppSettings.geoUpdateDays])
 * the app asks each source in `regions.json` for the hash of its current files (a few bytes) and, if they changed,
 * downloads only the categories it uses: the remote file is read in ranges, the entries' small headers are walked
 * and only the wanted bodies are fetched ([GeoFile.pick]) — about a tenth of the full files. Directly, or through
 * one of the user's servers if the source can't be reached. The new files replace the old ones atomically and the
 * core is restarted to load them ([version]).
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
        /** By source + file: categories of its current [sha] that [GeoCheck] refused (not downloaded again for it). */
        val refused: Map<String, List<String>> = emptyMap(),
        /** The files here passed [GeoCheck] (files of versions before it are checked once at start). */
        val verified: Boolean = false,
        /** [ListManifest.Manifest.signed] of the last manifest used: an older one is refused (no rollback). */
        val manifestSigned: Long = 0,
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
     * Makes sure the core finds both files: empty ones (no categories) until the first download fills them, so
     * [missing] sees everything as missing and the download starts. Lists downloaded earlier are kept, also over an
     * app update (versions up to 1.0.0 copied a snapshot from the APK here: it stays and is updated as usual).
     * Called by [CoreEnv.init].
     */
    fun install(geoDir: File) = synchronized(fileLock) {
        dir = geoDir
        // An empty file is a valid list file without categories.
        for (name in names) File(geoDir, name).let { if (!it.exists()) it.createNewFile() }
        _state.value = readState()
        if (!_state.value.verified) Errors.guard("routing lists", Unit, show = false) { verifyInstalled() }
        scanInstalled()
    }

    /** Runs [GeoCheck] once over the files already here (downloaded before it existed); what fails is left out. */
    private fun verifyInstalled() {
        for (name in names) {
            val file = File(dir, name)
            val entries = GeoFile.entries(file.readBytes())
            val notes = ArrayList<String>()
            val out = entries.mapNotNull { e -> checked(name, e.code.uppercase(), e.raw, null, notes) }
            if (notes.isEmpty()) continue
            AppLog.debug(TAG, "$name as installed: " + notes.joinToString("; "))
            val tmp = File(dir, "$name.tmp")
            tmp.writeBytes(GeoFile.write(out))
            GeoFile.entries(tmp.readBytes())
            if (!tmp.renameTo(file)) throw IOException("cannot replace $name")
        }
        writeState(_state.value.copy(verified = true))
    }

    /**
     * [fresh] as it may be installed: a category that goes direct passes [GeoCheck] against the one here now ([old]);
     * other categories as they are. Null = not taken. What was left out or refused is described in [notes] and, as an
     * event, in the user's log.
     */
    private fun checked(name: String, code: String, fresh: ByteArray, old: ByteArray?, notes: MutableList<String>): ByteArray? {
        if (code !in Regions.directCategories()[name].orEmpty()) return fresh
        val local = code == Regions.LOCAL.uppercase()
        val r = if (name == "geoip.dat") GeoCheck.addresses(fresh, old, local)
        else GeoCheck.sites(fresh, old) { if (local) it in GeoCheck.LOCAL_TLDS else Regions.bareTld(it) }
        val list = "${name.removeSuffix(".dat")}:${code.lowercase()}"
        r.refused?.let {
            notes += "$list refused ($it)"
            AppLog.event(LText.of(R.string.ev_geo_refused, list, it), AppLog.Kind.WARN)
            return old
        }
        if (r.dropped.isNotEmpty()) {
            val shown = r.dropped.take(5).joinToString(", ") + if (r.dropped.size > 5) ", …" else ""
            notes += "$list: ${r.dropped.size} left out: $shown"
            AppLog.event(LText.of(R.string.ev_geo_dropped, list, shown), AppLog.Kind.WARN)
        }
        return r.raw
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
        // A retry is due later: make sure this process has it scheduled (the one that failed may be gone).
        if (missing().isNotEmpty() && !needed && retryJob?.isActive != true) scheduleRetry(_state.value.retryAt)
        if (!force && !needed && !GeoFile.due(_state.value.checkedAt, s.geoUpdateDays, Power.metered, s.ecoOn, now)) return@withContext null
        if (!mutex.tryLock()) return@withContext null
        _busy.value = true
        AppLog.debug(TAG, "checking for updates: " + when {
            force -> "asked by the user"
            needed -> "missing ${missing().values.sumOf { it.size }} categories"
            else -> "scheduled (every ${s.geoUpdateDays} d)"
        })
        try {
            run()
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
                    if (changed != null) it.copy(sha = it.sha + changed, refused = it.refused + refused, manifestSigned = manifestSigned) else it
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
                    scheduleRetry(failed.retryAt)
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

    /** [ListManifest.Manifest.signed] of the manifest the last [update] used. */
    @Volatile
    private var manifestSigned = 0L

    /**
     * The project's signed manifest ([ListManifest]) through [proxy]; throws [IOException] if it can't be had, its
     * signature doesn't check out (then another way, e.g. through a server, may still get the real one), or it is
     * older than the one already used. Debug builds read `manifest.test.json` here if present (signed all the same).
     */
    private fun signedManifest(proxy: Proxy): ListManifest.Manifest {
        val local = File(dir, "manifest.test.json").takeIf { app.borderless.BuildConfig.DEBUG && it.isFile }
        val text = local?.readText() ?: text(ListManifest.url(Updater.project()), proxy, max = 256 * 1024)
        val m = ListManifest.verify(text) ?: throw IOException("the signature of the routing lists doesn't check out")
        if (m.signed < _state.value.manifestSigned) throw IOException("signed lists older than the ones in use")
        return m
    }

    /** Categories the last [update] refused, by source + file (see [State.refused]). */
    @Volatile
    private var refused: Map<String, List<String>> = emptyMap()

    private fun update(proxy: Proxy): Result<Map<String, String>?> = runCatching {
        var downloaded = 0L
        val newSha = HashMap<String, String>()
        val picked = HashMap<String, MutableMap<String, ByteArray>>() // file -> code -> entry
        val codesOf = HashMap<String, Pair<String, Set<String>>>() // source + file -> file, its categories
        val refusedNow = HashMap<String, MutableSet<String>>() // file -> categories GeoCheck refused
        // One pass per source (the common lists and a region's usually come from the same files).
        val missing = missing()
        val sources = Regions.geoSources(Regions.all.map { it.code }).groupBy { it.source }.map { (url, parts) ->
            app.borderless.data.GeoSource(
                url, parts.fold(emptyMap()) { m, p -> m + p.geoip }, parts.fold(emptyMap()) { m, p -> m + p.geosite },
                mirrors = parts.flatMap { it.mirrors }.distinct(),
            )
        }
        // Only what the project signed (SECURITY.md S2): the release of each source and every category's hash.
        val manifest = signedManifest(proxy)
        for (src in sources) {
            val pin = manifest.sources[src.source]
            if (pin == null) {
                AppLog.debug(TAG, "${URL(src.source).host}: not in the signed lists, skipped")
                continue
            }
            // The signed release, else a mirror serving the very same files (same checksums).
            val base = (listOf(ListManifest.pinned(src.source, pin.tag)) + src.mirrors).firstOrNull { url ->
                runCatching {
                    names.all { n -> pin.files[n]?.let { text(url + n + ".sha256sum", proxy).trim().substringBefore(' ').lowercase() == it.sha256 } ?: true }
                }.onFailure { AppLog.debug(TAG, "${URL(url).host}: ${it.message}") }.getOrDefault(false)
            } ?: throw IOException("no source serves the signed lists (${(listOf(src.source) + src.mirrors).joinToString { URL(it).host }})")
            for (name in names) {
                // Local name → hash of the source's name; looked up by hash, written under the local name.
                val byLocal = if (name == "geoip.dat") src.geoip else src.geosite
                val wanted = byLocal.keys.map { it.uppercase() }.toSet()
                val signed = pin.files[name]
                if (wanted.isEmpty() || signed == null) continue
                val key = src.source + name
                val sha = signed.sha256
                // A category refused for this very file isn't fetched again until the signed version changes.
                val refused = _state.value.refused[key].orEmpty()
                // Unchanged and nothing of it missing here: no download.
                if (sha == _state.value.sha[key] && wanted.none { it in missing[name].orEmpty() && it !in refused }) continue
                val reader = RangeReader(base + name, proxy)
                val entries = GeoFile.pickHashed(reader, reader.size, byLocal.entries.associate { (local, h) -> h to local })
                downloaded += reader.bytes
                AppLog.debug(TAG, "$name from ${URL(base).host} (${pin.tag}): ${entries.size} of ${wanted.size} categories, ${reader.bytes / 1024} KB in ${reader.requests} requests")
                val target = picked.getOrPut(name) { LinkedHashMap() }
                entries.forEach { (code, raw) ->
                    // Byte for byte the signed category, or not at all.
                    if (ListManifest.sha256(raw) == signed.categories[code]) target.putIfAbsent(code, raw)
                    else {
                        refusedNow.getOrPut(name) { HashSet() } += code
                        val list = "${name.removeSuffix(".dat")}:${code.lowercase()}"
                        AppLog.debug(TAG, "$list doesn't match the signed lists")
                        AppLog.event(LText.of(R.string.ev_geo_unsigned, list), AppLog.Kind.WARN)
                    }
                }
                newSha[key] = sha
                codesOf[key] = name to wanted
            }
        }
        manifestSigned = manifest.signed
        fetched = downloaded to picked.values.sumOf { it.size }
        if (newSha.isEmpty()) return@runCatching null
        synchronized(fileLock) {
            for ((name, fresh) in picked) {
                // Categories a source no longer has stay as they were.
                val current = GeoFile.entries(File(dir, name).readBytes())
                val merged = LinkedHashMap<String, ByteArray>()
                val notes = ArrayList<String>()
                // Categories going direct are checked first (GeoCheck); a refused one keeps its current version.
                fun take(code: String, raw: ByteArray, old: ByteArray?): ByteArray? =
                    checked(name, code, raw, old, notes).also { if (it == null || it === old) refusedNow.getOrPut(name) { HashSet() } += code }
                current.forEach { e ->
                    val code = e.code.uppercase()
                    val new = fresh[code]
                    merged[code] = if (new == null) e.raw else take(code, new, e.raw) ?: e.raw
                }
                fresh.forEach { (code, raw) -> if (code !in merged) take(code, raw, null)?.let { merged[code] = it } }
                if (notes.isNotEmpty()) AppLog.debug(TAG, "$name: " + notes.joinToString("; "))
                val tmp = File(dir, "$name.tmp")
                tmp.writeBytes(GeoFile.write(merged.values))
                // Make sure it reads back before it replaces the file the core uses.
                GeoFile.entries(tmp.readBytes())
                if (!tmp.renameTo(File(dir, name))) throw IOException("cannot replace $name")
            }
            scanInstalled()
        }
        refused = codesOf.mapValues { (_, v) -> v.second.filter { it in refusedNow[v.first].orEmpty() } }
        newSha
    }.recoverCatching { e -> throw if (e is IOException) e else IOException(e.message ?: e.javaClass.simpleName, e) }

    private fun text(url: String, proxy: Proxy, max: Int = 4096): String {
        val c = open(url, proxy)
        try {
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode} for ${URL(url).path}")
            // At most [max] bytes (a checksum, the manifest). InputStream.readNBytes needs Android 13.
            return c.inputStream.use { input ->
                val buf = ByteArray(max)
                var n = 0
                while (n < buf.size) { val r = input.read(buf, n, buf.size - n); if (r < 0) break; n += r }
                String(buf, 0, n, Charsets.UTF_8)
            }
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
                // Up to the expected length (InputStream.readNBytes needs Android 13).
                val data = c.inputStream.use { input ->
                    val buf = ByteArray(expected)
                    var n = 0
                    while (n < expected) { val r = input.read(buf, n, expected - n); if (r < 0) break; n += r }
                    if (n == expected) buf else buf.copyOf(n)
                }
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

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO + Errors.handler)
    private var retryJob: kotlinx.coroutines.Job? = null

    /**
     * Missing lists are tried again at [at] by themselves (the app ships none, so until they are here the zones
     * take no part); a server connecting or the user's "try now" may come first. Lives as long as the process.
     */
    private fun scheduleRetry(at: Long) {
        retryJob?.cancel()
        retryJob = scope.launch {
            kotlinx.coroutines.delay((at - System.currentTimeMillis()).coerceAtLeast(0) + 1_000)
            updateIfDue()
        }
    }

    private fun readState(): State =
        runCatching { json.decodeFromString(State.serializer(), File(dir, STATE).readText()) }.getOrDefault(State())

    private fun writeState(s: State) {
        _state.value = s
        Errors.guard("routing lists", Unit, show = false) { File(dir, STATE).writeText(json.encodeToString(State.serializer(), s)) }
    }
}
