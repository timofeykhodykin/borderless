package app.borderless.data

import android.content.Context
import net.zetetic.database.sqlcipher.SQLiteDatabase

/**
 * The Android side of [StatsDb]: the database file encrypted with SQLCipher (SECURITY.md S16) — the history of when
 * the tunnel ran, through which servers, on which networks is the user's own business — and the queries of the
 * tables only the Android energy model writes (Android's own accounting, the core's cost, the activity counters' writes).
 */
object AndroidStats {
    private const val NAME = "stats.db"

    /**
     * Opens the database: [password] = a raw key derived from the app's data key ([dbKey]); empty (an unencrypted
     * database) only when the phone has no Keystore at all. Versions up to 1.0 kept it unencrypted: encrypted once,
     * in place; if that fails it stays as it was. A file whose schema this build doesn't know (a newer version
     * ran here) is kept aside whole instead of being dropped — [DropGuarded].
     */
    fun init(context: Context) {
        // Sealed data whose key can't be opened now: the database (sealed by a key from it) waits too.
        if (Crypto.locked) return
        System.loadLibrary("sqlcipher")
        val app = context.applicationContext
        val key = Crypto.key?.let(::dbKey) ?: ByteArray(0)
        val file = app.getDatabasePath(NAME)
        val password = if (key.isNotEmpty() && isPlain(file) && !encrypt(file, key)) ByteArray(0) else key
        file.parentFile?.mkdirs()
        var db = open(file, password)
        StatsDb.ecoOn = { Repo.settings.value.ecoOn }
        StatsDb.cellPowerW = { Energy.CELL_W }
        StatsDb.beforeFlush = { Activity.flush() }
        try {
            StatsDb.init(DropGuarded(Driver(db)))
        } catch (e: DropGuarded.Dropped) {
            // A schema newer than this build's was about to have every table dropped (a rollback to an older
            // version). The file is kept aside with its journals and an empty database takes its place.
            runCatching { db.close() }
            val kept = keepAside(file)
            Errors.report(e, "statistics database (unknown schema, kept as ${kept.name})", show = false)
            db = open(file, password)
            StatsDb.init(Driver(db))
        }
    }

    /** The database connection with the journaling the statistics want. */
    private fun open(file: java.io.File, password: ByteArray): SQLiteDatabase {
        val db = SQLiteDatabase.openDatabase(file.path, password, null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY, null, null)
        // Write-ahead log + normal sync: far fewer disk flushes than the default rollback journal.
        runCatching { db.enableWriteAheadLogging() }
        runCatching { db.execSQL("PRAGMA synchronous = NORMAL") }
        return db
    }

    /**
     * A [SqlDatabase] that refuses `DROP` statements: [StatsDb]'s answer to a file of a schema newer than its own
     * is to drop every table, so the refusal means the database is not this build's to take over — the file is
     * kept aside ([keepAside]) instead of losing its history silently. Any other destructive migration would hit
     * the same wall.
     */
    internal class DropGuarded(private val inner: SqlDatabase) : SqlDatabase by inner {
        /** Thrown before the first drop runs, inside the migration's transaction (it rolls back untouched). */
        class Dropped : IllegalStateException("the schema migration tried to drop a table")

        override fun execSQL(sql: String) {
            guard(sql)
            inner.execSQL(sql)
        }

        /** The bound-args overload runs the same statements — the same wall, or a `DROP` written this way bypassed the guard. */
        override fun execSQL(sql: String, args: Array<Any?>) {
            guard(sql)
            inner.execSQL(sql, args)
        }

        /**
         * `rawQuery` too: the query path still *executes* a non-SELECT statement when the cursor
         * steps (Android happily runs `rawQuery("DROP TABLE x", …)`), so it was a way around the
         * guard. [StatsDb] only ever SELECTs through it — a DROP here is a migration's, never a read's.
         */
        override fun rawQuery(sql: String, args: Array<String>): SqlCursor {
            guard(sql)
            return inner.rawQuery(sql, args)
        }

        private fun guard(sql: String) {
            if (sql.trimStart().startsWith("DROP ", ignoreCase = true)) throw Dropped()
        }
    }

    /**
     * Moves the database and its journal files aside (`stats.db.incompatible-<ts>`, `-wal`, `-shm`, `-journal`):
     * a file this build can't read is preserved whole — the name marks why it was set aside.
     */
    internal fun keepAside(file: java.io.File): java.io.File {
        val aside = java.io.File(file.path + ".incompatible-" + System.currentTimeMillis())
        for (suffix in listOf("", "-wal", "-shm", "-journal")) {
            java.io.File(file.path + suffix).renameTo(java.io.File(aside.path + suffix))
        }
        return aside
    }

    /** [SqlDatabase] over SQLCipher's connection (the same API as Android's own). */
    private class Driver(private val db: SQLiteDatabase) : SqlDatabase {
        override fun execSQL(sql: String) = db.execSQL(sql)
        override fun execSQL(sql: String, args: Array<Any?>) = db.execSQL(sql, args)
        override fun rawQuery(sql: String, args: Array<String>): SqlCursor = Cursor(db.rawQuery(sql, args))
        override fun insert(table: String, nullColumnHack: String?, values: SqlValues): Long =
            db.insert(table, nullColumnHack, android.content.ContentValues().apply {
                values.entries.forEach { (k, v) ->
                    when (v) {
                        null -> putNull(k)
                        is Long -> put(k, v)
                        is Double -> put(k, v)
                        else -> put(k, v.toString())
                    }
                }
            })
        override fun delete(table: String, whereClause: String?, whereArgs: Array<String>?): Int = db.delete(table, whereClause, whereArgs)
        override fun beginTransaction() = db.beginTransaction()
        override fun setTransactionSuccessful() = db.setTransactionSuccessful()
        override fun endTransaction() = db.endTransaction()
        override var version: Int
            get() = db.version
            set(value) { db.version = value }
    }

    private class Cursor(private val c: android.database.Cursor) : SqlCursor {
        override fun moveToNext() = c.moveToNext()
        override fun moveToFirst() = c.moveToFirst()
        override fun isNull(i: Int) = c.isNull(i)
        override fun getLong(i: Int) = c.getLong(i)
        override fun getInt(i: Int) = c.getInt(i)
        override fun getDouble(i: Int) = c.getDouble(i)
        override fun getString(i: Int): String? = c.getString(i)
        override val columnCount: Int get() = c.columnCount
        override val count: Int get() = c.count
        override val columnNames: Array<String> get() = c.columnNames
        override fun close() = c.close()
    }

    /** The database's raw key (`x'…'`: no slow passphrase derivation): HMAC-SHA256 of its name under the data key. */
    private fun dbKey(dataKey: javax.crypto.SecretKey): ByteArray {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256").apply { init(javax.crypto.spec.SecretKeySpec(dataKey.encoded, "HmacSHA256")) }
        val hex = mac.doFinal("stats-db".toByteArray()).joinToString("") { "%02x".format(it) }
        return "x'$hex'".toByteArray()
    }

    /** An unencrypted SQLite file (its plain header), as older versions wrote it. */
    private fun isPlain(file: java.io.File): Boolean = file.isFile && file.length() >= 16 &&
        file.inputStream().use { ByteArray(16).also { b -> it.read(b) } }.contentEquals("SQLite format 3\u0000".toByteArray())

    /**
     * Copies the unencrypted [file] into an encrypted one (`sqlcipher_export`) and puts it in its place.
     * The plain file survives until the copy is proven readable and renamed over it: a failure anywhere on the
     * way leaves it exactly as it was (the copy goes).
     */
    private fun encrypt(file: java.io.File, key: ByteArray): Boolean {
        val tmp = java.io.File(file.path + ".enc")
        return try {
            tmp.delete()
            // CREATE: ATTACH opens the new file with the main database's flags.
            val db = SQLiteDatabase.openDatabase(file.path, ByteArray(0), null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY, null, null)
            val version = try {
                db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", emptyArray<String>()).use { it.moveToFirst() }
                val version = db.version
                // Bound, not written into the statement: SQLite logs a failing statement's text (with the key).
                db.execSQL("ATTACH DATABASE ? AS enc KEY ?", arrayOf<Any>(tmp.path, String(key)))
                db.rawQuery("SELECT sqlcipher_export('enc')", emptyArray<String>()).use { it.moveToFirst() }
                db.execSQL("PRAGMA enc.user_version = $version")
                db.execSQL("DETACH DATABASE enc")
                version
            } finally {
                db.close()
            }
            verify(tmp, key, version)
            swapIn(tmp, file)
            AppLog.debug("Stats", "statistics database encrypted (${file.length() / 1024} KB)")
            true
        } catch (e: Exception) {
            for (suffix in listOf("", "-wal", "-shm", "-journal")) java.io.File(tmp.path + suffix).delete()
            Errors.report(e, "encrypting the statistics database", show = false)
            false
        }
    }

    /** The encrypted copy must open with the key and carry the source's schema version before it may replace the file. */
    private fun verify(file: java.io.File, key: ByteArray, version: Int) {
        SQLiteDatabase.openDatabase(file.path, key, null, SQLiteDatabase.OPEN_READWRITE, null, null).use { db ->
            check(db.version == version) { "encrypted copy lost the schema version" }
            db.rawQuery("SELECT count(*) FROM sqlite_master", emptyArray<String>()).use { it.moveToFirst() }
        }
    }

    /**
     * Puts the verified encrypted copy [tmp] in [file]'s place: the rename replaces the plain file atomically
     * (only then do its journal files go), so a failing rename keeps the plain database readable.
     */
    internal fun swapIn(tmp: java.io.File, file: java.io.File) {
        check(tmp.renameTo(file)) { "cannot replace the database" }
        for (suffix in listOf("-wal", "-shm", "-journal")) {
            java.io.File(file.path + suffix).delete()
            java.io.File(tmp.path + suffix).delete()
        }
    }
}

/** Android's accounting for the app since the previous power sample ([SystemPower.delta]). */
fun StatsDb.sysPower(ts: Long, d: SystemPower.Snapshot) = write(signal = false) {
    insert("sys_power", null, SqlValues().apply {
        put("ts", ts); put("cpu_mams", d.cpuMams); put("cpu_ms", d.cpuMs); put("mobile_mams", d.mobileMams)
        put("wifi_mams", d.wifiMams); put("radio_ms", d.radioMs); put("mobile_bytes", d.mobileBytes); put("wifi_bytes", d.wifiBytes)
    })
}

/** Android's accounting rows in [from, to). */
suspend fun StatsDb.sysPowerRows(from: Long, to: Long): List<Pair<Long, SystemPower.Snapshot>> = read(emptyList()) {
    rawQuery(
        "SELECT ts, cpu_mams, cpu_ms, mobile_mams, wifi_mams, radio_ms, mobile_bytes, wifi_bytes FROM sys_power WHERE ts >= ? AND ts < ? ORDER BY ts",
        arrayOf(from.toString(), to.toString()),
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(
                c.getLong(0) to SystemPower.Snapshot(c.getLong(1), c.getLong(2), c.getLong(3), c.getLong(4), c.getLong(5), c.getLong(6), c.getLong(7))
            )
        }
    }
}

/**
 * The activity counters' writes in [from, to) with their energy per kind, for the energy chart
 * ([ActivityMath.energyOverTime]); writes with no energy are kept, they bound the stretch of the next one.
 * Kinds this version doesn't know any more are left out of the energy.
 */
suspend fun StatsDb.activityFlushes(from: Long, to: Long): List<ActivityMath.Flush> = read(emptyList()) {
    val known = Activity.Kind.entries.associateBy { it.name }
    rawQuery(
        "SELECT ts, kind, SUM(mj) FROM activity WHERE ts >= ? AND ts < ? GROUP BY ts, kind ORDER BY ts",
        arrayOf(from.toString(), to.toString()),
    ).use { c ->
        val out = ArrayList<ActivityMath.Flush>()
        var ts = Long.MIN_VALUE
        var cur = HashMap<Activity.Kind, Long>()
        while (c.moveToNext()) {
            if (c.getLong(0) != ts) {
                if (ts != Long.MIN_VALUE) out += ActivityMath.Flush(ts, cur)
                ts = c.getLong(0)
                cur = HashMap()
            }
            known[c.getString(1)]?.let { k -> c.getLong(2).takeIf { it > 0 }?.let { cur[k] = (cur[k] ?: 0L) + it } }
        }
        if (ts != Long.MIN_VALUE) out += ActivityMath.Flush(ts, cur)
        out
    }
}
