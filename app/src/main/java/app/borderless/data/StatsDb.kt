package app.borderless.data

import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import app.borderless.Res

import app.borderless.R

import android.content.ContentValues
import android.content.Context
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteOpenHelper
import app.borderless.core.Phase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withContext

/**
 * Long-term statistics in SQLite: every ping sample, connection phase changes, switch events, scan
 * summaries and traffic. Writes are queued on one background thread; reads are suspend functions.
 */
object StatsDb {
    /** Where a ping sample came from. */
    enum class Kind { PROBE, HEALTH }

    /** Why the engine changed (or tried to change) the server. */
    /** RECONNECT (2026-10-16): the same server, a fresh core — it answered a probe while the core's connection hung. */
    enum class Event { LOST, INITIAL, FAILOVER, OPTIMIZE, MANUAL, RETRY, BEST, RECONNECT }

    /** Schema version of the first public release; raise it with every additive change (see Helper.onUpgrade). */
    private const val VERSION = 4

    /** Phase rows are repeated this often while the tunnel runs, so a killed process does not inflate durations. */
    const val HEARTBEAT_MS = 5 * 60_000L

    /**
     * This process, written with every phase row (v3): a silence between two rows of the same process means the
     * phone slept (heartbeats are late in deep sleep), not that the tunnel was gone.
     */
    private val process = System.currentTimeMillis()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val writer = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1) + Errors.handler)
    private lateinit var helper: Helper

    /**
     * Encrypted with SQLCipher (SECURITY.md S16): the history of when the tunnel ran, through which servers, on which
     * networks is the user's own business. [password] = a raw key derived from the app's data key ([dbKey]); empty
     * (an unencrypted database) only when the phone has no Keystore at all.
     */
    private class Helper(context: Context, password: ByteArray) :
        SQLiteOpenHelper(context, NAME, password, null, VERSION, 0, null, null, true) {
        override fun onCreate(db: SQLiteDatabase) {
            // took: how long a health check took in all (v4; null for probes and older rows).
            db.execSQL("CREATE TABLE samples (ts INTEGER NOT NULL, server TEXT NOT NULL, ms INTEGER, kind INTEGER NOT NULL, took INTEGER)")
            db.execSQL("CREATE INDEX samples_server_ts ON samples(server, ts)")
            db.execSQL("CREATE INDEX samples_ts ON samples(ts)")
            // run: the process that wrote the row (v3, see [process]).
            db.execSQL("CREATE TABLE phases (ts INTEGER NOT NULL, phase TEXT NOT NULL, server TEXT, run INTEGER)")
            db.execSQL("CREATE INDEX phases_ts ON phases(ts)")
            // pos / pos_of: where the chosen server stood in the check order of the scan that found it (v2).
            db.execSQL("CREATE TABLE events (ts INTEGER NOT NULL, type TEXT NOT NULL, server TEXT, from_server TEXT, pos INTEGER, pos_of INTEGER)")
            db.execSQL("CREATE INDEX events_ts ON events(ts)")
            createV4(db)
            // A scan: how many of the checked servers answered, all servers the user had, why it ran.
            db.execSQL("CREATE TABLE scans (ts INTEGER NOT NULL, available INTEGER NOT NULL, total INTEGER NOT NULL, best INTEGER, servers INTEGER, reason TEXT)")
            // Traffic since the previous row; the proxied part counted for the server that carried it.
            db.execSQL("CREATE TABLE traffic (ts INTEGER NOT NULL, proxy_up INTEGER, proxy_down INTEGER, direct_up INTEGER, direct_down INTEGER, server TEXT)")
            // The app's own activity per kind, with its estimated radio / CPU time and energy.
            db.execSQL("CREATE TABLE activity (ts INTEGER NOT NULL, kind TEXT NOT NULL, n INTEGER NOT NULL, bytes INTEGER NOT NULL, radio_ms INTEGER NOT NULL DEFAULT 0, cpu_ms INTEGER NOT NULL DEFAULT 0, mj INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("CREATE INDEX activity_ts ON activity(ts)")
            db.execSQL(
                "CREATE TABLE power (ts INTEGER NOT NULL, battery INTEGER, charging INTEGER, screen INTEGER, " +
                    "saver INTEGER, metered INTEGER, cpu_ms INTEGER, pss_kb INTEGER, cap_mah INTEGER, mv INTEGER)"
            )
            db.execSQL("CREATE INDEX power_ts ON power(ts)")
            // Battery current samples and per-action measurements (diagnostics).
            db.execSQL("CREATE TABLE current (ts INTEGER NOT NULL, raw INTEGER NOT NULL, mv INTEGER)")
            db.execSQL("CREATE INDEX current_ts ON current(ts)")
            db.execSQL(
                "CREATE TABLE measurements (ts INTEGER NOT NULL, kind TEXT NOT NULL, duration_ms INTEGER, " +
                    "model_mj INTEGER, measured_mj INTEGER, base_ua INTEGER, metered INTEGER, screen INTEGER)"
            )
            db.execSQL("CREATE INDEX measurements_ts ON measurements(ts)")
            // Which settings were in effect when (strategy, intervals…).
            db.execSQL("CREATE TABLE settings_log (ts INTEGER NOT NULL, key TEXT NOT NULL, value TEXT)")
            db.execSQL("CREATE INDEX settings_log_ts ON settings_log(ts)")
            // The core's CPU time per protocol class and traffic, measured in quiet windows (ProtocolCost).
            db.execSQL("CREATE TABLE core_cost (ts INTEGER NOT NULL, class TEXT NOT NULL, bytes INTEGER NOT NULL, cpu_ms INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX core_cost_ts ON core_cost(ts)")
            // What each subscription's provider reported at every update.
            db.execSQL("CREATE TABLE sub_usage (ts INTEGER NOT NULL, sub TEXT NOT NULL, used INTEGER, total INTEGER, expire INTEGER)")
            db.execSQL("CREATE INDEX sub_usage_sub_ts ON sub_usage(sub, ts)")
        }

        /**
         * Future schema changes go here as additive steps (`if (oldVersion < N)`: new tables / columns with defaults),
         * never dropping or renaming what a released version wrote.
         */
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // 2: the chosen server's place in the check order (null for events written before).
            if (oldVersion < 2) {
                db.execSQL("ALTER TABLE events ADD COLUMN pos INTEGER")
                db.execSQL("ALTER TABLE events ADD COLUMN pos_of INTEGER")
            }
            // 3: the process that wrote a phase row (null for rows written before).
            if (oldVersion < 3) db.execSQL("ALTER TABLE phases ADD COLUMN run INTEGER")
            // 4: what a loss looked like, connection cuts, health check durations.
            if (oldVersion < 4) {
                db.execSQL("ALTER TABLE samples ADD COLUMN took INTEGER")
                createV4(db)
            }
        }

        /**
         * v4. LOST events get: how long the connection had lived, the network kind (wifi / cell / ethernet / other),
         * metered, made in a limited network, proxy bytes up / down in the last minute. `cuts`: every interruption of
         * a working connection — from its first sign (stall, suspicion, failed check) or the planned core restart to
         * the next answered check (end_ts null: the tunnel stopped first) — with its cause, screen, network, server
         * and the last minute's proxy bytes.
         */
        private fun createV4(db: SQLiteDatabase) {
            for (c in listOf("lifetime_ms INTEGER", "net TEXT", "metered INTEGER", "limited INTEGER", "up60 INTEGER", "down60 INTEGER")) {
                db.execSQL("ALTER TABLE events ADD COLUMN $c")
            }
            db.execSQL(
                "CREATE TABLE cuts (ts INTEGER NOT NULL, end_ts INTEGER, cause TEXT NOT NULL, screen INTEGER, net TEXT, " +
                    "server TEXT, up60 INTEGER, down60 INTEGER)",
            )
            db.execSQL("CREATE INDEX cuts_ts ON cuts(ts)")
        }

        /** A database of an unknown schema (a pre-release build, or a newer version rolled back): start it over. */
        override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name != 'android_metadata'", emptyArray<String>()).use { c ->
                buildList { while (c.moveToNext()) add(c.getString(0)) }
            }.forEach { db.execSQL("DROP TABLE IF EXISTS \"$it\"") }
            onCreate(db)
        }

        override fun onConfigure(db: SQLiteDatabase) {
            // Write-ahead log + normal sync: far fewer disk flushes than the default rollback journal.
            runCatching { db.execSQL("PRAGMA synchronous = NORMAL") }
        }
    }

    fun init(context: Context) {
        if (::helper.isInitialized) return
        // Sealed data whose key can't be opened now: the database (sealed by a key from it) waits too.
        if (Crypto.locked) return
        System.loadLibrary("sqlcipher")
        val app = context.applicationContext
        val key = Crypto.key?.let(::dbKey) ?: ByteArray(0)
        // Versions up to 1.0 kept it unencrypted: encrypted once, in place; if that fails it stays as it was.
        val password = if (key.isNotEmpty() && isPlain(app.getDatabasePath(NAME)) && !encrypt(app.getDatabasePath(NAME), key)) ByteArray(0) else key
        helper = Helper(app, password)
        writer.launch { writeLoop() }
    }

    private const val NAME = "stats.db"

    /** The database's raw key (`x'…'`: no slow passphrase derivation): HMAC-SHA256 of its name under the data key. */
    private fun dbKey(dataKey: javax.crypto.SecretKey): ByteArray {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256").apply { init(javax.crypto.spec.SecretKeySpec(dataKey.encoded, "HmacSHA256")) }
        val hex = mac.doFinal("stats-db".toByteArray()).joinToString("") { "%02x".format(it) }
        return "x'$hex'".toByteArray()
    }

    /** An unencrypted SQLite file (its plain header), as older versions wrote it. */
    private fun isPlain(file: java.io.File): Boolean = file.isFile && file.length() >= 16 &&
        file.inputStream().use { ByteArray(16).also { b -> it.read(b) } }.contentEquals("SQLite format 3\u0000".toByteArray())

    /** Copies the unencrypted [file] into an encrypted one (`sqlcipher_export`) and puts it in its place. */
    private fun encrypt(file: java.io.File, key: ByteArray): Boolean {
        val tmp = java.io.File(file.path + ".enc")
        return try {
            tmp.delete()
            // CREATE: ATTACH opens the new file with the main database's flags.
            val db = SQLiteDatabase.openDatabase(file.path, ByteArray(0), null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY, null, null)
            try {
                db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", emptyArray<String>()).use { it.moveToFirst() }
                val version = db.version
                // Bound, not written into the statement: SQLite logs a failing statement's text (with the key).
                db.execSQL("ATTACH DATABASE ? AS enc KEY ?", arrayOf<Any>(tmp.path, String(key)))
                db.rawQuery("SELECT sqlcipher_export('enc')", emptyArray<String>()).use { it.moveToFirst() }
                db.execSQL("PRAGMA enc.user_version = $version")
                db.execSQL("DETACH DATABASE enc")
            } finally {
                db.close()
            }
            for (suffix in listOf("", "-wal", "-shm", "-journal")) java.io.File(file.path + suffix).delete()
            check(tmp.renameTo(file)) { "cannot replace the database" }
            AppLog.debug("Stats", "statistics database encrypted (${file.length() / 1024} KB)")
            true
        } catch (e: Exception) {
            tmp.delete()
            Errors.report(e, "encrypting the statistics database", show = false)
            false
        }
    }

    @Volatile
    private var writeFailed = false

    private sealed interface Op {
        /** [signal]: new data for open statistics screens (bookkeeping rows don't count). */
        class Write(val signal: Boolean, val block: SQLiteDatabase.() -> Unit) : Op
        class Flush(val done: CompletableDeferred<Unit>) : Op
    }

    private val queue = Channel<Op>(Channel.UNLIMITED)

    /** Writes are collected for a few seconds and committed in one transaction (one disk sync, not dozens). */
    private val _changes = MutableStateFlow(0L)

    /**
     * Time of the last commit that brought new data (pings, states, scans, traffic, samples). Open
     * statistics screens reload on it. Activity counters and the settings log don't signal: loading a
     * screen writes those itself, which would otherwise make it reload itself forever.
     */
    val changes: StateFlow<Long> = _changes.asStateFlow()

    private fun write(signal: Boolean = true, block: SQLiteDatabase.() -> Unit) {
        if (!::helper.isInitialized) return
        queue.trySend(Op.Write(signal, block))
    }

    /** Commits queued writes now (before queries that need the latest samples, and on shutdown). */
    suspend fun flush() {
        if (!::helper.isInitialized) return
        Activity.flush()
        val done = CompletableDeferred<Unit>()
        queue.trySend(Op.Flush(done))
        withTimeoutOrNull(5_000) { done.await() }
    }

    private suspend fun writeLoop() {
        while (true) {
            val batch = mutableListOf(queue.receive())
            // Wait for more unless someone is waiting for the data.
            if (batch.none { it is Op.Flush }) withTimeoutOrNull(if (Repo.settings.value.ecoOn) EcoMath.STATS_BATCH_MS else BATCH_MS) {
                while (true) {
                    val op = queue.receive()
                    batch += op
                    if (op is Op.Flush) break
                }
            }
            while (true) batch += queue.tryReceive().getOrNull() ?: break
            commit(batch.filterIsInstance<Op.Write>())
            batch.filterIsInstance<Op.Flush>().forEach { it.done.complete(Unit) }
        }
    }

    private fun commit(ops: List<Op.Write>) {
        if (ops.isEmpty()) return
        // The statistics' own cost: each commit is counted (CPU of the write).
        val span = Energy.begin(Activity.Kind.DB_COMMIT)
        try {
            val db = helper.writableDatabase
            db.beginTransaction()
            try {
                ops.forEach { it.block(db) }
                db.setTransactionSuccessful()
                if (ops.any { it.signal }) _changes.value = System.currentTimeMillis()
                Activity.count(Activity.Kind.DB_COMMIT)
            } finally {
                db.endTransaction()
            }
        } catch (e: Exception) {
            // Statistics are not essential: tell once, keep the app running.
            Errors.report(e, "statistics write", Res.s(R.string.err_stats), show = !writeFailed)
            writeFailed = true
        } finally {
            Energy.end(span)
        }
    }

    private const val BATCH_MS = 10_000L

    // ------------------------------------------------------------------ recording

    fun sample(server: String, ms: Int?, kind: Kind, ts: Long = System.currentTimeMillis(), took: Int? = null) = write {
        insert("samples", null, ContentValues().apply {
            put("ts", ts); put("server", server); put("kind", kind.ordinal)
            if (ms != null) put("ms", ms) else putNull("ms")
            took?.let { put("took", it) }
        })
    }

    /** What a LOST event records about the connection that was lost (v4). */
    data class Loss(val lifetimeMs: Long, val net: String?, val metered: Boolean, val limited: Boolean, val up60: Long, val down60: Long)

    /** An interruption of a working connection (v4, see [Helper.createV4]); [end] null: the tunnel stopped first. */
    fun cut(start: Long, end: Long?, cause: String, screen: Boolean, net: String?, server: String?, up60: Long, down60: Long) = write {
        insert("cuts", null, ContentValues().apply {
            put("ts", start); end?.let { put("end_ts", it) }; put("cause", cause); put("screen", if (screen) 1 else 0)
            put("net", net); put("server", server); put("up60", up60); put("down60", down60)
        })
    }

    fun phase(phase: Phase, server: String?, ts: Long = System.currentTimeMillis()) = write {
        insert("phases", null, ContentValues().apply { put("ts", ts); put("phase", phase.name); put("server", server); put("run", process) })
    }

    /** [pos]: the chosen server's place (1-based) in the check order of the scan that found it, of [pos].second servers. */
    fun event(type: Event, server: String?, from: String? = null, ts: Long = System.currentTimeMillis(), pos: Pair<Int, Int>? = null, loss: Loss? = null) = write {
        insert("events", null, ContentValues().apply {
            put("ts", ts); put("type", type.name); put("server", server); put("from_server", from)
            pos?.let { put("pos", it.first); put("pos_of", it.second) }
            loss?.let {
                put("lifetime_ms", it.lifetimeMs); put("net", it.net); put("metered", if (it.metered) 1 else 0)
                put("limited", if (it.limited) 1 else 0); put("up60", it.up60); put("down60", it.down60)
            }
        })
    }

    /** A scan: [available] of [total] checked servers answered; [servers] = all the user has; [reason] = Activity kind. */
    fun scan(available: Int, total: Int, best: Int?, servers: Int? = null, reason: String? = null, ts: Long = System.currentTimeMillis()) = write {
        insert("scans", null, ContentValues().apply {
            put("ts", ts); put("available", available); put("total", total)
            if (best != null) put("best", best) else putNull("best")
            put("servers", servers); put("reason", reason)
        })
    }

    /** Traffic since the last row; [server] = the server that carried the proxied part (null: none / unknown). */
    fun traffic(proxyUp: Long, proxyDown: Long, directUp: Long, directDown: Long, server: String? = null, ts: Long = System.currentTimeMillis()) {
        if (proxyUp + proxyDown + directUp + directDown == 0L) return
        write {
            insert("traffic", null, ContentValues().apply {
                put("ts", ts); put("proxy_up", proxyUp); put("proxy_down", proxyDown); put("direct_up", directUp); put("direct_down", directDown)
                put("server", server)
            })
        }
    }

    /** What a subscription's provider reported at an update (bytes, expiry in Unix seconds). */
    fun subUsage(sub: String, used: Long?, total: Long?, expire: Long?, ts: Long = System.currentTimeMillis()) = write {
        insert("sub_usage", null, ContentValues().apply { put("ts", ts); put("sub", sub); put("used", used); put("total", total); put("expire", expire) })
    }

    data class UsageRow(val ts: Long, val used: Long?, val total: Long?, val expire: Long?)

    /** A subscription's reported usage in the period, starting with the last report before it. */
    suspend fun subUsage(sub: String, from: Long, to: Long): List<UsageRow> = read(emptyList()) {
        fun row(c: android.database.Cursor) = UsageRow(
            c.getLong(0), if (c.isNull(1)) null else c.getLong(1), if (c.isNull(2)) null else c.getLong(2), if (c.isNull(3)) null else c.getLong(3),
        )
        val before = rawQuery("SELECT ts, used, total, expire FROM sub_usage WHERE sub = ? AND ts < ? ORDER BY ts DESC LIMIT 1", arrayOf(sub, from.toString())).use { c ->
            if (c.moveToFirst()) row(c) else null
        }
        val rows = rawQuery("SELECT ts, used, total, expire FROM sub_usage WHERE sub = ? AND ts >= ? AND ts < ? ORDER BY ts", arrayOf(sub, from.toString(), to.toString())).use { c ->
            buildList { while (c.moveToNext()) add(row(c)) }
        }
        listOfNotNull(before) + rows
    }

    /** Proxied bytes per server in the period (rows written since version 7 name their server). */
    suspend fun trafficByServer(from: Long, to: Long): Map<String, Long> = read(emptyMap()) {
        rawQuery(
            "SELECT server, TOTAL(proxy_up) + TOTAL(proxy_down) FROM traffic WHERE ts >= ? AND ts < ? AND server IS NOT NULL GROUP BY server",
            arrayOf(from.toString(), to.toString()),
        ).use { c -> buildMap { while (c.moveToNext()) put(c.getString(0), c.getDouble(1).toLong()) } }
    }

    /** Best latency per bucket among [servers] (e.g. one subscription's), like [buckets] with server = null. */
    suspend fun bucketsAmong(from: Long, to: Long, buckets: Int, servers: Collection<String>): List<StatsMath.Point> = read(emptyList()) {
        if (servers.isEmpty()) return@read emptyList()
        val width = maxOf(1L, (to - from) / buckets)
        val marks = servers.joinToString(",") { "?" }
        rawQuery(
            "SELECT (ts - $from) / ? AS b, MIN(ms), SUM(CASE WHEN ms IS NULL THEN 1 ELSE 0 END), COUNT(*) FROM samples " +
                "WHERE ts >= ? AND ts < ? AND server IN ($marks) GROUP BY b ORDER BY b",
            (listOf(width.toString(), from.toString(), to.toString()) + servers).toTypedArray(),
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(StatsMath.Point(from + c.getLong(0) * width + width / 2, if (c.isNull(1)) null else c.getDouble(1).toInt(), c.getInt(2), c.getInt(3)))
            }
        }
    }

    /** How many of [servers] were checked in an interval and how many of them answered at least once. */
    data class Checked(val t: Long, val checked: Int, val answered: Int)

    /**
     * Per interval of at least [minWidth] (one rescan, so each interval holds a full check of the servers):
     * the distinct servers among [servers] with a sample, and those with an answer. For one subscription's
     * "servers that answered" chart (the overview uses whole scans, which mix every subscription).
     */
    suspend fun checkedAmong(from: Long, to: Long, buckets: Int, servers: Collection<String>, minWidth: Long = 10 * 60_000L): List<Checked> = read(emptyList()) {
        if (servers.isEmpty()) return@read emptyList()
        val width = maxOf(minWidth, (to - from) / buckets)
        val marks = servers.joinToString(",") { "?" }
        rawQuery(
            "SELECT (ts - $from) / ? AS b, COUNT(DISTINCT server), COUNT(DISTINCT CASE WHEN ms IS NOT NULL THEN server END) FROM samples " +
                "WHERE ts >= ? AND ts < ? AND server IN ($marks) GROUP BY b ORDER BY b",
            (listOf(width.toString(), from.toString(), to.toString()) + servers).toTypedArray(),
        ).use { c ->
            buildList { while (c.moveToNext()) add(Checked(from + c.getLong(0) * width, c.getInt(1), c.getInt(2))) }
        }
    }

    /** Deletes everything older than [days]. */
    val TABLES = listOf("samples", "phases", "events", "scans", "traffic", "activity", "power", "current", "measurements", "settings_log", "core_cost", "sub_usage")

    /** One window of the core's work: [bytes] proxied through a server of [cls] for [cpuMs] of CPU. */
    fun coreCost(ts: Long, cls: String, bytes: Long, cpuMs: Long) = write(signal = false) {
        insert("core_cost", null, ContentValues().apply { put("ts", ts); put("class", cls); put("bytes", bytes); put("cpu_ms", cpuMs) })
    }

    suspend fun coreCostByClass(from: Long, to: Long): Map<String, ProtocolCost.Measured> = read(emptyMap()) {
        rawQuery("SELECT class, SUM(bytes), SUM(cpu_ms), COUNT(*) FROM core_cost WHERE ts >= ? AND ts < ? GROUP BY class", arrayOf(from.toString(), to.toString())).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), ProtocolCost.Measured(c.getLong(1), c.getLong(2), c.getInt(3))) }
        }
    }

    suspend fun coreCostWindows(from: Long, to: Long): List<ProtocolCost.Window> = read(emptyList()) {
        rawQuery("SELECT class, bytes, cpu_ms FROM core_cost WHERE ts >= ? AND ts < ?", arrayOf(from.toString(), to.toString())).use { c ->
            buildList { while (c.moveToNext()) add(ProtocolCost.Window(c.getString(0), c.getLong(1), c.getLong(2))) }
        }
    }

    /** Values of one setting over [from, to): (time, value), starting with the last one before [from]. */
    suspend fun settingsHistory(key: String, from: Long, to: Long): List<Pair<Long, String>> = read(emptyList()) {
        val before = rawQuery("SELECT ts, value FROM settings_log WHERE key = ? AND ts < ? ORDER BY ts DESC LIMIT 1", arrayOf(key, from.toString())).use { c ->
            if (c.moveToFirst()) c.getLong(0) to c.getString(1).orEmpty() else null
        }
        val rows = rawQuery("SELECT ts, value FROM settings_log WHERE key = ? AND ts >= ? AND ts < ? ORDER BY ts", arrayOf(key, from.toString(), to.toString())).use { c ->
            buildList { while (c.moveToNext()) add(c.getLong(0) to c.getString(1).orEmpty()) }
        }
        listOfNotNull(before) + rows
    }

    /** Settings that changed (or all of them, at start): key → value as text. */
    fun settings(ts: Long, values: Map<String, String>) = write(signal = false) {
        values.forEach { (k, v) -> insert("settings_log", null, ContentValues().apply { put("ts", ts); put("key", k); put("value", v) }) }
    }

    /** Totals of one activity kind: count, bytes downloaded, estimated radio / CPU time and energy. */
    data class ActivityTotal(val kind: String, val n: Long, val bytes: Long, val radioMs: Long, val cpuMs: Long, val mj: Long)

    /** App activity counters gathered since the last write. */
    fun activity(ts: Long, rows: List<ActivityTotal>) = write(signal = false) {
        rows.forEach { r ->
            insert("activity", null, ContentValues().apply {
                put("ts", ts); put("kind", r.kind); put("n", r.n); put("bytes", r.bytes)
                put("radio_ms", r.radioMs); put("cpu_ms", r.cpuMs); put("mj", r.mj)
            })
        }
    }

    /** One battery current sample (raw value as the phone reports it, voltage in mV). */
    fun current(ts: Long, raw: Int, mv: Int?) = write {
        insert("current", null, ContentValues().apply { put("ts", ts); put("raw", raw); put("mv", mv) })
    }

    data class Measurement(
        val ts: Long, val kind: String, val durationMs: Long, val modelMj: Long, val measuredMj: Long?,
        val baseUa: Long?, val metered: Boolean, val screen: Boolean,
    )

    fun measurement(m: Measurement) = write {
        insert("measurements", null, ContentValues().apply {
            put("ts", m.ts); put("kind", m.kind); put("duration_ms", m.durationMs); put("model_mj", m.modelMj)
            put("measured_mj", m.measuredMj); put("base_ua", m.baseUa); put("metered", if (m.metered) 1 else 0); put("screen", if (m.screen) 1 else 0)
        })
    }

    data class PowerRow(
        val ts: Long, val battery: Int?, val charging: Boolean, val screen: Boolean,
        val saver: Boolean, val metered: Boolean, val cpuMs: Long, val pssKb: Long,
        val capacityMah: Int? = null, val mv: Int? = null,
    )

    fun power(r: PowerRow) = write {
        insert("power", null, ContentValues().apply {
            put("ts", r.ts); put("battery", r.battery); put("charging", if (r.charging) 1 else 0); put("screen", if (r.screen) 1 else 0)
            put("saver", if (r.saver) 1 else 0); put("metered", if (r.metered) 1 else 0); put("cpu_ms", r.cpuMs); put("pss_kb", r.pssKb)
            put("cap_mah", r.capacityMah); put("mv", r.mv)
        })
    }

    fun prune(days: Int) = write {
        // Current samples are diagnostics: a week is plenty.
        delete("current", "ts < ?", arrayOf((System.currentTimeMillis() - 7 * 86_400_000L).toString()))
        val cutoff = System.currentTimeMillis() - days * 86_400_000L
        for (t in TABLES) delete(t, "ts < ?", arrayOf(cutoff.toString()))
    }

    /**
     * Failed samples after the last answer of any server are dropped: the network itself didn't reach the servers
     * (none, or limited to the zones' own sites), which says nothing about them. Written by the queue, so it also
     * covers failures still waiting to be committed.
     */
    fun forgetFailures() = write {
        val since = rawQuery("SELECT MAX(ts) FROM samples WHERE ms IS NOT NULL", emptyArray<String>()).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L
        }
        val n = delete("samples", "ms IS NULL AND ts > ?", arrayOf(since.toString()))
        if (n > 0) AppLog.debug("StatsDb", "forgot $n failed check(s) since the last answer: the network didn't reach the servers")
    }

    fun clear() = write {
        for (t in TABLES) delete(t, null, null)
    }

    // ------------------------------------------------------------------ queries

    data class Sample(val ts: Long, val server: String, val ms: Int?)
    data class PhaseRow(val ts: Long, val phase: Phase, val server: String?, val run: Long? = null)
    data class EventRow(val ts: Long, val type: Event, val server: String?, val pos: Int? = null, val posOf: Int? = null)
    data class ScanRow(val ts: Long, val available: Int, val total: Int, val best: Int?, val servers: Int? = null)
    data class Traffic(val proxyUp: Long, val proxyDown: Long, val directUp: Long, val directDown: Long) {
        val proxy get() = proxyUp + proxyDown
        val direct get() = directUp + directDown
    }

    /** Runs a query; if the database fails (corrupt, disk full, …) the error is reported and [default] returned. */
    private suspend fun <T> read(default: T, block: SQLiteDatabase.() -> T): T = withContext(Dispatchers.IO) {
        if (!::helper.isInitialized) return@withContext default
        try {
            helper.readableDatabase.block()
        } catch (e: Exception) {
            Errors.report(e, "statistics query", Res.s(R.string.err_stats))
            default
        }
    }

    suspend fun samples(from: Long, to: Long, servers: Collection<String>? = null, kind: Kind? = null): List<Sample> = read(emptyList()) {
        val where = StringBuilder("ts >= ? AND ts < ?")
        val args = mutableListOf(from.toString(), to.toString())
        if (servers != null) {
            if (servers.isEmpty()) return@read emptyList()
            where.append(" AND server IN (").append(servers.joinToString(",") { "?" }).append(")")
            args += servers
        }
        if (kind != null) { where.append(" AND kind = ?"); args += kind.ordinal.toString() }
        rawQuery("SELECT ts, server, ms FROM samples WHERE $where ORDER BY ts", args.toTypedArray()).use { c ->
            buildList(c.count) {
                while (c.moveToNext()) add(Sample(c.getLong(0), c.getString(1), if (c.isNull(2)) null else c.getInt(2)))
            }
        }
    }

    /**
     * Samples with at most [limit] rows: for long periods every n-th row is taken, which keeps
     * medians and charts representative without loading millions of rows.
     */
    suspend fun samplesLimited(from: Long, to: Long, servers: Collection<String>?, kind: Kind?, limit: Int = 60_000): List<Sample> = read(emptyList()) {
        val where = StringBuilder("ts >= ? AND ts < ?")
        val args = mutableListOf(from.toString(), to.toString())
        if (servers != null) {
            if (servers.isEmpty()) return@read emptyList()
            where.append(" AND server IN (").append(servers.joinToString(",") { "?" }).append(")")
            args += servers
        }
        if (kind != null) { where.append(" AND kind = ?"); args += kind.ordinal.toString() }
        val count = rawQuery("SELECT COUNT(*) FROM samples WHERE $where", args.toTypedArray()).use { c -> c.moveToFirst(); c.getInt(0) }
        val stride = (count + limit - 1) / limit
        if (stride > 1) { where.append(" AND (rowid % ?) = 0"); args += stride.toString() }
        rawQuery("SELECT ts, server, ms FROM samples WHERE $where ORDER BY ts", args.toTypedArray()).use { c ->
            buildList(c.count) {
                while (c.moveToNext()) add(Sample(c.getLong(0), c.getString(1), if (c.isNull(2)) null else c.getInt(2)))
            }
        }
    }

    data class Aggregate(val count: Int, val ok: Int, val avg: Int?, val min: Int?, val max: Int?) {
        val availability: Double? get() = if (count == 0) null else ok.toDouble() / count
    }

    /** Per-server counts and latency aggregates computed in SQLite. */
    suspend fun aggregates(from: Long, to: Long): Map<String, Aggregate> = read(emptyMap()) {
        rawQuery(
            "SELECT server, COUNT(*), COUNT(ms), AVG(ms), MIN(ms), MAX(ms) FROM samples WHERE ts >= ? AND ts < ? GROUP BY server",
            arrayOf(from.toString(), to.toString()),
        ).use { c ->
            buildMap {
                while (c.moveToNext()) put(
                    c.getString(0),
                    Aggregate(c.getInt(1), c.getInt(2), if (c.isNull(3)) null else c.getDouble(3).toInt(),
                        if (c.isNull(4)) null else c.getInt(4), if (c.isNull(5)) null else c.getInt(5)),
                )
            }
        }
    }

    /**
     * Chart points for one server (average per bucket) or, with [server] = null, the best latency
     * of any server per bucket.
     */
    suspend fun buckets(from: Long, to: Long, buckets: Int, server: String?): List<StatsMath.Point> = read(emptyList()) {
        val width = maxOf(1L, (to - from) / buckets)
        val agg = if (server == null) "MIN(ms)" else "AVG(ms)"
        val where = "ts >= ? AND ts < ?" + if (server != null) " AND server = ?" else ""
        val args = listOfNotNull(width.toString(), from.toString(), to.toString(), server).toTypedArray()
        rawQuery(
            "SELECT (ts - ${from}) / ? AS b, $agg, SUM(CASE WHEN ms IS NULL THEN 1 ELSE 0 END), COUNT(*) FROM samples WHERE $where GROUP BY b ORDER BY b",
            args,
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val b = c.getLong(0)
                    add(StatsMath.Point(from + b * width + width / 2, if (c.isNull(1)) null else c.getDouble(1).toInt(), c.getInt(2), c.getInt(3)))
                }
            }
        }
    }

    /**
     * Score per server for ordering scans (higher = try earlier): smoothed share of successful
     * probes since [since], scaled down for high average latency, with a bonus for answering in
     * the last hour.
     */
    suspend fun ranking(since: Long): Map<String, Double> = read(emptyMap()) {
        val hourAgo = System.currentTimeMillis() - 3_600_000L
        rawQuery(
            "SELECT server, COUNT(*), COUNT(ms), AVG(ms), MAX(CASE WHEN ms IS NOT NULL THEN ts END) FROM samples WHERE ts >= ? GROUP BY server",
            arrayOf(since.toString()),
        ).use { c ->
            buildMap {
                while (c.moveToNext()) {
                    val count = c.getInt(1)
                    val ok = c.getInt(2)
                    val avg = if (c.isNull(3)) null else c.getDouble(3)
                    val lastOk = if (c.isNull(4)) 0L else c.getLong(4)
                    val recent = if (lastOk >= hourAgo) 1.5 else 1.0
                    put(c.getString(0), StatsMath.score(count, ok, avg) * recent)
                }
            }
        }
    }

    /** Per-server counts over a long and a short window (see [poolStats]). */
    data class PoolStat(
        val shortCount: Int, val shortOk: Int,
        val longCount: Int, val longOk: Int,
        val avgMs: Double?, val lastOk: Long?,
    )

    /**
     * Probe/health counts per server since [longSince], and since [shortSince] separately. A failure
     * only counts if some server answered within a minute of it: when nothing answers at all, the
     * phone was offline, which says nothing about the servers.
     */
    suspend fun poolStats(longSince: Long, shortSince: Long): Map<String, PoolStat> = read(emptyMap()) {
        rawQuery(
            """
            WITH good AS (SELECT DISTINCT ts / 60000 AS m FROM samples WHERE ms IS NOT NULL AND ts >= ?1)
            SELECT server,
                SUM(CASE WHEN ts >= ?2 THEN 1 ELSE 0 END),
                SUM(CASE WHEN ts >= ?2 AND ms IS NOT NULL THEN 1 ELSE 0 END),
                COUNT(*), COUNT(ms), AVG(ms), MAX(CASE WHEN ms IS NOT NULL THEN ts END)
            FROM samples
            WHERE ts >= ?1 AND (ms IS NOT NULL OR ts / 60000 IN good OR ts / 60000 - 1 IN good OR ts / 60000 + 1 IN good)
            GROUP BY server
            """.trimIndent(),
            arrayOf(longSince.toString(), shortSince.toString()),
        ).use { c ->
            buildMap {
                while (c.moveToNext()) put(
                    c.getString(0),
                    PoolStat(
                        c.getInt(1), c.getInt(2), c.getInt(3), c.getInt(4),
                        if (c.isNull(5)) null else c.getDouble(5), if (c.isNull(6)) null else c.getLong(6),
                    ),
                )
            }
        }
    }

    /** Phase rows in the period, plus the last row before it (the state the period started in) and the first after it. */
    suspend fun phases(from: Long, to: Long): List<PhaseRow> = read(emptyList()) {
        fun row(c: android.database.Cursor) = PhaseRow(c.getLong(0), Phase.valueOf(c.getString(1)), c.getString(2), if (c.isNull(3)) null else c.getLong(3))
        val before = rawQuery("SELECT ts, phase, server, run FROM phases WHERE ts < ? ORDER BY ts DESC LIMIT 1", arrayOf(from.toString())).use { c ->
            if (c.moveToFirst()) runCatching { row(c) }.getOrNull() else null
        }
        val rows = rawQuery("SELECT ts, phase, server, run FROM phases WHERE ts >= ? AND ts < ? ORDER BY ts", arrayOf(from.toString(), to.toString())).use { c ->
            buildList { while (c.moveToNext()) runCatching { add(row(c)) } }
        }
        // The row after the period too: it tells whether the period's last row was followed by the same process.
        val after = rawQuery("SELECT ts, phase, server, run FROM phases WHERE ts >= ? ORDER BY ts LIMIT 1", arrayOf(to.toString())).use { c ->
            if (c.moveToFirst()) runCatching { row(c) }.getOrNull() else null
        }
        listOfNotNull(before) + rows + listOfNotNull(after)
    }

    suspend fun events(from: Long, to: Long): List<EventRow> = read(emptyList()) {
        rawQuery("SELECT ts, type, server, pos, pos_of FROM events WHERE ts >= ? AND ts < ? ORDER BY ts", arrayOf(from.toString(), to.toString())).use { c ->
            buildList {
                while (c.moveToNext()) runCatching {
                    add(EventRow(c.getLong(0), Event.valueOf(c.getString(1)), c.getString(2), if (c.isNull(3)) null else c.getInt(3), if (c.isNull(4)) null else c.getInt(4)))
                }
            }
        }
    }

    suspend fun scans(from: Long, to: Long): List<ScanRow> = read(emptyList()) {
        rawQuery("SELECT ts, available, total, best, servers FROM scans WHERE ts >= ? AND ts < ? ORDER BY ts", arrayOf(from.toString(), to.toString())).use { c ->
            buildList {
                while (c.moveToNext()) add(ScanRow(c.getLong(0), c.getInt(1), c.getInt(2), if (c.isNull(3)) null else c.getInt(3), if (c.isNull(4)) null else c.getInt(4)))
            }
        }
    }

    suspend fun traffic(from: Long, to: Long): Traffic = read(Traffic(0, 0, 0, 0)) {
        rawQuery(
            "SELECT TOTAL(proxy_up), TOTAL(proxy_down), TOTAL(direct_up), TOTAL(direct_down) FROM traffic WHERE ts >= ? AND ts < ?",
            arrayOf(from.toString(), to.toString()),
        ).use { c ->
            c.moveToFirst()
            Traffic(c.getDouble(0).toLong(), c.getDouble(1).toLong(), c.getDouble(2).toLong(), c.getDouble(3).toLong())
        }
    }

    /** Activity totals per kind in the period. */
    suspend fun activityTotals(from: Long, to: Long): Map<String, ActivityTotal> = read(emptyMap()) {
        rawQuery(
            "SELECT kind, SUM(n), SUM(bytes), SUM(radio_ms), SUM(cpu_ms), SUM(mj) FROM activity WHERE ts >= ? AND ts < ? GROUP BY kind",
            arrayOf(from.toString(), to.toString()),
        ).use { c ->
            buildMap {
                while (c.moveToNext()) put(c.getString(0), ActivityTotal(c.getString(0), c.getLong(1), c.getLong(2), c.getLong(3), c.getLong(4), c.getLong(5)))
            }
        }
    }

    /** Per kind: number of measured actions, average measured and modelled mJ (current measurement mode). */
    data class MeasuredKind(val kind: String, val count: Int, val measuredMj: Double, val modelMj: Double)

    /**
     * Per kind: the median measured energy (one reading is noisy — other apps, the radio — and a mean is
     * pulled far off by a few outliers, even below zero) and the mean modelled energy.
     */
    suspend fun measuredKinds(from: Long, to: Long): List<MeasuredKind> = read(emptyList()) {
        val byKind = LinkedHashMap<String, Pair<MutableList<Double>, MutableList<Double>>>()
        rawQuery(
            "SELECT kind, measured_mj, model_mj FROM measurements WHERE ts >= ? AND ts < ? AND measured_mj IS NOT NULL",
            arrayOf(from.toString(), to.toString()),
        ).use { c ->
            while (c.moveToNext()) byKind.getOrPut(c.getString(0)) { mutableListOf<Double>() to mutableListOf() }.let { (m, model) ->
                m += c.getDouble(1); model += c.getDouble(2)
            }
        }
        byKind.map { (kind, v) -> MeasuredKind(kind, v.first.size, StatsMath.median(v.first), v.second.average()) }.sortedByDescending { it.count }
    }

    /** Writes a whole table as CSV (header + rows) for the statistics export. */
    suspend fun dumpCsv(table: String, out: java.io.Writer) = read(Unit) {
        require(table in TABLES) { "unknown table" }
        rawQuery("SELECT * FROM $table ORDER BY ts", emptyArray<String>()).use { c ->
            out.write(c.columnNames.joinToString(",") + "\n")
            while (c.moveToNext()) {
                out.write((0 until c.columnCount).joinToString(",") { i -> if (c.isNull(i)) "" else csv(c.getString(i)) } + "\n")
            }
        }
    }

    private fun csv(v: String) = if (v.any { it == ',' || it == '"' || it == '\n' }) "\"" + v.replace("\"", "\"\"") + "\"" else v

    /** Activity counts per time bucket and kind: (bucket start, kind, count). */
    suspend fun activityBuckets(from: Long, to: Long, buckets: Int): List<Triple<Long, String, Long>> = read(emptyList()) {
        val width = maxOf(1L, (to - from) / buckets)
        rawQuery(
            "SELECT (ts - $from) / ? AS b, kind, SUM(n) FROM activity WHERE ts >= ? AND ts < ? GROUP BY b, kind ORDER BY b",
            arrayOf(width.toString(), from.toString(), to.toString()),
        ).use { c -> buildList { while (c.moveToNext()) add(Triple(from + c.getLong(0) * width + width / 2, c.getString(1), c.getLong(2))) } }
    }

    suspend fun powerRows(from: Long, to: Long): List<PowerRow> = read(emptyList()) {
        rawQuery(
            "SELECT ts, battery, charging, screen, saver, metered, cpu_ms, pss_kb, cap_mah, mv FROM power WHERE ts >= ? AND ts < ? ORDER BY ts",
            arrayOf(from.toString(), to.toString()),
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(
                    PowerRow(
                        c.getLong(0), if (c.isNull(1)) null else c.getInt(1), c.getInt(2) == 1, c.getInt(3) == 1,
                        c.getInt(4) == 1, c.getInt(5) == 1, c.getLong(6), c.getLong(7),
                        if (c.isNull(8)) null else c.getInt(8), if (c.isNull(9)) null else c.getInt(9),
                    )
                )
            }
        }
    }

    /** When the tunnel last carried traffic through a server: the end of the last "connected" stretch; null = never. */
    suspend fun lastConnected(): Long? = read(null) {
        val last = rawQuery("SELECT MAX(ts) FROM phases WHERE phase = ?", arrayOf(Phase.CONNECTED.name)).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        } ?: return@read null
        // The next row ends it (off, another state); without one the process ended while connected.
        rawQuery("SELECT MIN(ts) FROM phases WHERE ts > ?", arrayOf(last.toString())).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else last
        }
    }

    suspend fun firstTimestamp(): Long? = read(null) {
        rawQuery("SELECT MIN(ts) FROM (SELECT MIN(ts) AS ts FROM samples UNION ALL SELECT MIN(ts) FROM phases)", emptyArray<String>()).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }
    }
}
