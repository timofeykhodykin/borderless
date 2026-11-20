@file:OptIn(ExperimentalForeignApi::class)

package app.borderless.platform

import app.borderless.data.AppLog
import app.borderless.data.Errors
import app.borderless.data.SqlCursor
import app.borderless.data.SqlDatabase
import app.borderless.data.SqlValues
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import sqlite3.SQLITE_DONE
import sqlite3.SQLITE_NULL
import sqlite3.SQLITE_OK
import sqlite3.SQLITE_OPEN_READONLY
import sqlite3.SQLITE_OPEN_CREATE
import sqlite3.SQLITE_OPEN_FULLMUTEX
import sqlite3.SQLITE_OPEN_READWRITE
import sqlite3.SQLITE_ROW
import cnames.structs.sqlite3
import sqlite3.sqlite3_backup_finish
import sqlite3.sqlite3_backup_init
import sqlite3.sqlite3_backup_step
import sqlite3.sqlite3_bind_double
import sqlite3.sqlite3_bind_int64
import sqlite3.sqlite3_bind_null
import sqlite3.sqlite3_bind_text
import sqlite3.sqlite3_changes
import sqlite3.sqlite3_close_v2
import sqlite3.sqlite3_column_count
import sqlite3.sqlite3_column_double
import sqlite3.sqlite3_column_int64
import sqlite3.sqlite3_column_name
import sqlite3.sqlite3_column_text
import sqlite3.sqlite3_column_type
import sqlite3.sqlite3_errmsg
import sqlite3.sqlite3_finalize
import sqlite3.sqlite3_last_insert_rowid
import sqlite3.sqlite3_open_v2
import sqlite3.sqlite3_prepare_v2
import sqlite3.sqlite3_reset
import sqlite3.sqlite3_step
import cnames.structs.sqlite3_stmt

/**
 * The statistics' [SqlDatabase] on the Mac: the system's SQLite in WAL mode with **two connections** — writes (the
 * one transaction lane) and reads. One serialized connection cannot serve both: a read cursor open while the writer
 * COMMITs fails it ("SQL statements in progress"), and serializing them by lock deadlocks — the writer's transaction
 * spans a suspension, so its lock is held across threads (both found 2026-11-02). WAL readers never block a writer
 * and read the last committed state, which is exactly what the screens' queries mean.
 */
class MacSql private constructor(private val wdb: CPointer<sqlite3>, private val rdb: CPointer<sqlite3>) : SqlDatabase {
    companion object {
        private fun connect(path: String, vfs: String? = null, readOnly: Boolean = false): CPointer<sqlite3> = memScoped {
            val out = alloc<CPointerVar<sqlite3>>()
            val rc = sqlite3_open_v2(path, out.ptr, (if (readOnly) SQLITE_OPEN_READONLY else SQLITE_OPEN_READWRITE or SQLITE_OPEN_CREATE) or SQLITE_OPEN_FULLMUTEX, vfs)
            val handle = out.value ?: error("sqlite: cannot open $path ($rc)")
            if (rc != SQLITE_OK) {
                val msg = sqlite3_errmsg(handle)?.toKString()
                sqlite3_close_v2(handle)
                error("sqlite: cannot open $path: $msg")
            }
            handle
        }

        private fun open(path: String, vfs: String?): MacSql {
            val wdb = connect(path, vfs)
            val rdb = try { connect(path, vfs) } catch (e: Throwable) { sqlite3_close_v2(wdb); throw e }
            val db = MacSql(wdb, rdb)
            try {
                // Write-ahead log + normal sync, as on Android; a busy timeout, so a read never fails for meeting
                // the writer mid-checkpoint. The pragma *answers* the mode it actually set — "delete" would
                // mean the VFS can't serve the wal-index and the two-connection design below quietly breaks.
                check(scalarText(wdb, "PRAGMA journal_mode = WAL").equals("wal", ignoreCase = true)) {
                    "sqlite: WAL mode was refused — the reader/writer split needs the wal-index"
                }
                runSql(wdb, "PRAGMA synchronous = NORMAL")
                runSql(wdb, "PRAGMA busy_timeout = 5000")
                runSql(rdb, "PRAGMA busy_timeout = 5000")
                // The read probe that proves a sealed file's key: for a non-empty database it forces the
                // first block's decryption — a wrong key fails here (SQLITE_CORRUPT), not mid-session.
                scalarLong(wdb, "PRAGMA schema_version")
            } catch (e: Throwable) {
                sqlite3_close_v2(wdb)
                sqlite3_close_v2(rdb)
                throw e
            }
            return db
        }

        fun open(path: String): MacSql = open(path, null)

        /** Open ordinary SQLite, decrypting a legacy database only after a verified copy and backup. */
        fun openPlain(path: String, legacyKeys: List<ByteArray> = emptyList()): MacSql {
            when (kind(path)) {
                DbKind.MISSING, DbKind.PLAIN -> return open(path)
                DbKind.OTHER -> error("sqlite: statistics file is damaged; original preserved")
                DbKind.SEALED -> Unit
            }
            CryptVfs.ensureRegistered()
            for (key in legacyKeys) {
                if (copyToPlain(path, key)) return open(path)
            }
            error("sqlite: legacy statistics could not be decrypted; original preserved")
        }

        private fun copyToPlain(path: String, key: ByteArray): Boolean {
            val plain = "$path.plain-migration"
            try {
                for (suffix in listOf("", "-wal", "-shm", "-journal")) deleteFile(plain + suffix)
                // Preserve the database AND its WAL before even checkpointing the source.
                for (suffix in listOf("", "-wal", "-shm", "-journal")) {
                    if (fileExists(path + suffix)) preserveLegacyFile(path + suffix)
                }
                CryptVfs.key = key
                val src = connect(path, CryptVfs.NAME, readOnly = true)
                try {
                    check(scalarLong(src, "PRAGMA schema_version") >= 0)
                    val dst = connect(plain)
                    try {
                        val copy = sqlite3_backup_init(dst, "main", src, "main")
                            ?: error("sqlite: cannot start plaintext migration")
                        val rc = sqlite3_backup_step(copy, -1)
                        val finish = sqlite3_backup_finish(copy)
                        check(rc == SQLITE_DONE && finish == SQLITE_OK) { "sqlite: plaintext migration failed ($rc/$finish)" }
                        check(scalarText(dst, "PRAGMA integrity_check") == "ok") { "sqlite: plaintext migration failed integrity check" }
                        check(scalarLong(src, "PRAGMA user_version") == scalarLong(dst, "PRAGMA user_version"))
                    } finally { sqlite3_close_v2(dst) }
                } finally { sqlite3_close_v2(src) }
                platform.posix.chmod(plain, 0x180u)
                val fd = platform.posix.open(plain, platform.posix.O_RDONLY)
                check(fd >= 0) { "can't open plaintext database for sync" }
                try { check(platform.posix.fsync(fd) == 0) { "can't sync plaintext database" } }
                finally { platform.posix.close(fd) }
                check(Files.rename(plain, path)) { "cannot replace the statistics database" }
                for (suffix in listOf("-wal", "-shm", "-journal")) deleteFile(path + suffix)
                AppLog.debug("Stats", "legacy statistics decrypted into ordinary SQLite; encrypted originals preserved")
                return true
            } catch (e: Throwable) {
                for (suffix in listOf("", "-wal", "-shm", "-journal")) deleteFile(plain + suffix)
                return false
            }
        }

        /**
         * [open] with the database file sealed at rest (R1, F-DATA-0038-1): [key] is `MacCrypto.dbKey`'s
         * HMAC-derived AES key, the pages go through [CryptVfs], so `stats.db`, its `-wal` and `-journal`
         * hold only sealed blocks (the `-shm` index is exempt — metadata only, see CryptVfs).
         *
         * Failure semantics mirror Android's `AndroidStats.init`:
         * - [key] null and the file is not sealed: unencrypted, as before (a daemon without a working
         *   keychain encrypts nothing); [key] null over a sealed file refuses — the Android `locked`
         *   database is not opened at all;
         * - a plain database is copied into a sealed one first ([copyToSealed] — write + fsync +
         *   integrity-verify, then the atomic rename replaces the plaintext; the plaintext is never deleted
         *   before the sealed copy is durable — the F-DATA-0022-1 lesson);
         * - a sealed file under a rotated key is opened with one of [oldKeys] and re-keyed through the
         *   same copy-verify-rename path, as the store re-seals old-key files;
         * - a sealed file that can't be opened by any key (damage, or another key's work) is kept aside
         *   (`stats.db.broken-<ms>`) and a fresh one starts — never rotated over, as `Crypto`'s recovery.
         */
        fun openSealed(path: String, key: ByteArray?, oldKeys: List<ByteArray> = emptyList()): MacSql {
            if (key == null) {
                check(kind(path) != DbKind.SEALED) { "sqlite: a sealed statistics database without its key" }
                return open(path)
            }
            CryptVfs.ensureRegistered()
            CryptVfs.key = key
            return when (kind(path)) {
                DbKind.PLAIN -> if (copyToSealed(path, null, key)) openSealedDb(path, key, emptyList()) else open(path)
                DbKind.SEALED -> {
                    // A sealed database's sidecars must be sealed too: plain ones are leftovers of the file
                    // it replaced — deleted first, or the VFS would refuse the whole database as damaged.
                    dropStalePlainSidecars(path)
                    openSealedDb(path, key, oldKeys)
                }
                // MISSING starts fresh; OTHER (damage) refuses in the VFS and lands in keep-aside too.
                else -> openSealedDb(path, key, oldKeys)
            }
        }

        /** What's on disk before the database is touched. */
        private enum class DbKind { MISSING, PLAIN, SEALED, OTHER }

        private fun kind(path: String): DbKind {
            val n = Files.size(path)
            if (n < 0) return DbKind.MISSING
            if (n < 16) return DbKind.OTHER
            val head = Files.readRange(path, 0, 16)
            if (head.copyOfRange(0, 4).contentEquals("BLDB".encodeToByteArray())) return DbKind.SEALED
            if (head.contentEquals("SQLite format 3\u0000".encodeToByteArray())) return DbKind.PLAIN
            return DbKind.OTHER
        }

        /**
         * Opens through [CryptVfs] under [key]. On failure the [oldKeys] are tried one by one — a file sealed
         * under a still-remembered key is re-keyed through the same durable copy path the plain migration uses;
         * a file no key opens (damage, another key's work) is kept aside and a fresh one is created.
         */
        private fun openSealedDb(path: String, key: ByteArray, oldKeys: List<ByteArray>): MacSql {
            CryptVfs.key = key
            try {
                return open(path, CryptVfs.NAME)
            } catch (e: Throwable) {
                for (old in oldKeys) {
                    CryptVfs.key = old
                    val ok = runCatching { open(path, CryptVfs.NAME) }.onSuccess { it.close() }.isSuccess
                    if (ok && copyToSealed(path, old, key)) {
                        CryptVfs.key = key
                        AppLog.debug("Stats", "the statistics database was re-keyed with the key in use")
                        return open(path, CryptVfs.NAME)
                    }
                }
                CryptVfs.key = key
                val aside = runCatching { keepAsideDb(path) }.getOrDefault("")
                AppLog.debug("Stats", "the statistics database can't be opened ($e)" +
                    (if (aside.isNotEmpty()) ", kept aside as $aside — a fresh one starts" else ""))
                return open(path, CryptVfs.NAME)
            }
        }

        /** Deletes `-wal` / `-journal` sidecars whose contents is not sealed — leftovers of the file a sealed database replaced. */
        private fun dropStalePlainSidecars(path: String) {
            for (suffix in listOf("-wal", "-journal")) {
                val sidecar = path + suffix
                if (fileExists(sidecar) && kind(sidecar) != DbKind.SEALED) deleteFile(sidecar)
            }
        }

        /** Renames the database and its sidecars aside (`stats.db.broken-<ms>`), pruning old ones. */
        private fun keepAsideDb(path: String): String {
            val name = path.substringAfterLast('/')
            val dir = path.substringBeforeLast('/')
            val backup = "$path.broken-${nowMs()}"
            for (suffix in listOf("", "-wal", "-shm", "-journal"))
                if (fileExists(path + suffix)) Files.rename(path + suffix, backup + suffix)
            pruneAside(dir, name)
            return backup.substringAfterLast('/')
        }

        /**
         * Copies the database into a sealed `path.enc` under [dstKey] and puts it in place — the exact ordering
         * that avoids F-DATA-0022-1 (whose old Android `encrypt()` deleted the plaintext before the rename).
         * [srcKey] null = the source is plaintext (the usual migration); a key = the source is sealed under a
         * rotated key and is being re-keyed:
         * 1. open the source, fold its WAL in (checkpoint TRUNCATE);
         * 2. copy every page into a sealed `path.enc` (sqlite3_backup through [CryptVfs]);
         * 3. checkpoint + `PRAGMA integrity_check` the sealed copy (decrypts every page back);
         * 4. fsync `path.enc` — durable before it may replace anything;
         * 5. `rename()` — the atomic replace is the source's removal, never a delete first;
         * 6. only then the source's `-wal`/`-shm`/`-journal` sidecars are deleted.
         * Any failure: `path.enc*` is deleted, the source is untouched (as Android's "stays as it was").
         * [CryptVfs.key] ends as [dstKey] either way.
         */
        private fun copyToSealed(path: String, srcKey: ByteArray?, dstKey: ByteArray): Boolean {
            val enc = "$path.enc"
            try {
                for (suffix in listOf("", "-wal", "-shm", "-journal")) deleteFile(enc + suffix)
                if (srcKey != null) CryptVfs.key = srcKey
                val src = connect(path, if (srcKey != null) CryptVfs.NAME else null)
                try {
                    runCatching { runSql(src, "PRAGMA wal_checkpoint(TRUNCATE)") }
                    CryptVfs.key = dstKey
                    val dst = connect(enc, CryptVfs.NAME)
                    try {
                        val bp = sqlite3_backup_init(dst, "main", src, "main")
                            ?: error("sqlite: cannot start the encrypted copy: ${sqlite3_errmsg(dst)?.toKString()}")
                        val brc = sqlite3_backup_step(bp, -1)
                        sqlite3_backup_finish(bp)
                        check(brc == SQLITE_DONE) { "sqlite: the encrypted copy failed ($brc)" }
                        runCatching { runSql(dst, "PRAGMA wal_checkpoint(TRUNCATE)") }
                        check(scalarText(dst, "PRAGMA integrity_check") == "ok") { "sqlite: the encrypted copy fails integrity_check" }
                        check(scalarLong(dst, "PRAGMA user_version") == scalarLong(src, "PRAGMA user_version")) {
                            "sqlite: the encrypted copy lost the schema version"
                        }
                    } finally {
                        sqlite3_close_v2(dst)
                    }
                } finally {
                    sqlite3_close_v2(src)
                }
                fsyncFile(enc)
                check(Files.rename(enc, path)) { "cannot replace the database" }
                for (suffix in listOf("-wal", "-shm", "-journal")) {
                    deleteFile(path + suffix)
                    deleteFile(enc + suffix)
                }
                AppLog.debug("Stats", "statistics database encrypted (${Files.size(path) / 1024} KB)")
                return true
            } catch (e: Throwable) {
                CryptVfs.key = dstKey
                for (suffix in listOf("", "-wal", "-shm", "-journal")) deleteFile(enc + suffix)
                AppLog.debug("Stats", "statistics encryption failed, kept the source as it was ($e)")
                Errors.report(e, "encrypting the statistics database", show = false)
                return false
            }
        }

        private fun prepareSql(db: CPointer<sqlite3>, sql: String): CPointer<sqlite3_stmt> = memScoped {
            val out = alloc<CPointerVar<sqlite3_stmt>>()
            check(sqlite3_prepare_v2(db, sql, -1, out.ptr, null) == SQLITE_OK) {
                "sqlite: prepare: ${sqlite3_errmsg(db)?.toKString()}"
            }
            out.value ?: error("sqlite: prepare")
        }

        private fun runSql(db: CPointer<sqlite3>, sql: String) {
            val st = prepareSql(db, sql)
            try {
                var rc = sqlite3_step(st)
                while (rc == SQLITE_ROW) rc = sqlite3_step(st)
                check(rc == SQLITE_DONE) { "sqlite: step: ${sqlite3_errmsg(db)?.toKString()}" }
            } finally {
                sqlite3_finalize(st)
            }
        }

        private fun scalarText(db: CPointer<sqlite3>, sql: String): String? {
            val st = prepareSql(db, sql)
            try {
                return if (sqlite3_step(st) == SQLITE_ROW)
                    sqlite3_column_text(st, 0)?.let { it.reinterpret<kotlinx.cinterop.ByteVar>().toKString() } else null
            } finally {
                sqlite3_finalize(st)
            }
        }

        private fun scalarLong(db: CPointer<sqlite3>, sql: String): Long {
            val st = prepareSql(db, sql)
            try {
                return if (sqlite3_step(st) == SQLITE_ROW) sqlite3_column_int64(st, 0) else -1
            } finally {
                sqlite3_finalize(st)
            }
        }
    }

    private fun fail(db: CPointer<sqlite3>, what: String): Nothing = error("sqlite: $what: ${sqlite3_errmsg(db)?.toKString()}")

    private fun prepare(db: CPointer<sqlite3>, sql: String, args: List<Any?>): CPointer<sqlite3_stmt> = memScoped {
        val out = alloc<CPointerVar<sqlite3_stmt>>()
        if (sqlite3_prepare_v2(db, sql, -1, out.ptr, null) != SQLITE_OK) fail(db, "prepare")
        val st = out.value ?: fail(db, "prepare")
        args.forEachIndexed { i, a ->
            val n = i + 1
            val rc = when (a) {
                null -> sqlite3_bind_null(st, n)
                is Long -> sqlite3_bind_int64(st, n, a)
                is Int -> sqlite3_bind_int64(st, n, a.toLong())
                is Boolean -> sqlite3_bind_int64(st, n, if (a) 1 else 0)
                is Double -> sqlite3_bind_double(st, n, a)
                is Float -> sqlite3_bind_double(st, n, a.toDouble())
                // SQLITE_TRANSIENT (-1): SQLite copies the text.
                else -> sqlite3_bind_text(st, n, a.toString(), -1, SQLITE_TRANSIENT)
            }
            if (rc != SQLITE_OK) { sqlite3_finalize(st); fail(db, "bind") }
        }
        st
    }

    private fun run(db: CPointer<sqlite3>, sql: String, args: List<Any?>) {
        val st = prepare(db, sql, args)
        try {
            var rc = sqlite3_step(st)
            while (rc == SQLITE_ROW) rc = sqlite3_step(st)
            if (rc != SQLITE_DONE) fail(db, "step")
        } finally {
            sqlite3_finalize(st)
        }
    }

    // Writes on the writer connection (transactions included); reads on their own one.
    override fun execSQL(sql: String) = run(wdb, sql, emptyList())

    override fun execSQL(sql: String, args: Array<Any?>) = run(wdb, sql, args.toList())

    override fun rawQuery(sql: String, args: Array<String>): SqlCursor = Cursor(prepare(rdb, sql, args.toList()))

    override fun insert(table: String, nullColumnHack: String?, values: SqlValues): Long {
        val keys = values.entries.keys.toList()
        val sql = if (keys.isEmpty()) "INSERT INTO $table ($nullColumnHack) VALUES (NULL)"
        else "INSERT INTO $table (${keys.joinToString(",")}) VALUES (${keys.joinToString(",") { "?" }})"
        run(wdb, sql, keys.map { values.entries[it] })
        return sqlite3_last_insert_rowid(wdb)
    }

    override fun delete(table: String, whereClause: String?, whereArgs: Array<String>?): Int {
        run(wdb, "DELETE FROM $table" + (whereClause?.let { " WHERE $it" } ?: ""), whereArgs?.toList().orEmpty())
        return sqlite3_changes(wdb)
    }

    // Transactions live on the writer connection only (the writer is one lane; nested begins only count).
    // Two traps, both kept in check by every caller of StatsDb's withTransaction today:
    //  - [txLock] is an NSRecursiveLock, which must be unlocked on the thread that locked it. A suspension
    //    point between beginTransaction() and endTransaction() would resume the block on another thread and
    //    break the pairing, so a transaction must never span a suspend (the run-blocking StatsDb API is safe).
    //  - [ok] is one flag for every depth: an inner endTransaction() after a setTransactionSuccessful() marks
    //    the whole stack good, and an inner failure does not poison the outer transaction — the outermost
    //    commit/rollback decides alone. Android's stacked semantics differ (each level keeps its own flag);
    //    nothing here relies on nesting beyond the depth count.
    private val txLock = Lock()
    private var depth = 0
    private var ok = true

    override fun beginTransaction() {
        txLock.lock()
        try {
            if (depth++ == 0) { ok = false; execSQL("BEGIN IMMEDIATE") }
        } catch (e: Throwable) {
            depth--
            txLock.unlock()
            throw e
        }
    }

    /** Marks the transaction good — all of it: a nested call's [endTransaction] shares this flag (see [txLock]). */
    override fun setTransactionSuccessful() { ok = true }

    /** Decides COMMIT vs ROLLBACK only at depth 0, from the single [ok] flag — see [txLock] for the caveat. */
    override fun endTransaction() {
        try {
            if (--depth == 0) {
                try {
                    execSQL(if (ok) "COMMIT" else "ROLLBACK")
                } catch (e: Throwable) {
                    // Whatever went wrong, an open transaction must not survive: the next BEGIN would refuse.
                    runCatching { execSQL("ROLLBACK") }
                    throw e
                }
            }
        } finally {
            txLock.unlock()
        }
    }

    override var version: Int
        get() = rawQuery("PRAGMA user_version", emptyArray()).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        set(value) = execSQL("PRAGMA user_version = $value")

    fun close() { sqlite3_close_v2(wdb); sqlite3_close_v2(rdb) }

    private class Cursor(private val st: CPointer<sqlite3_stmt>) : SqlCursor {
        private var done = false
        private var started = false

        override fun moveToNext(): Boolean {
            if (done) return false
            started = true
            return when (sqlite3_step(st)) {
                SQLITE_ROW -> true
                else -> { done = true; false }
            }
        }

        override fun moveToFirst(): Boolean {
            if (started) { sqlite3_reset(st); done = false }
            return moveToNext()
        }

        override fun isNull(i: Int) = sqlite3_column_type(st, i) == SQLITE_NULL
        override fun getLong(i: Int) = sqlite3_column_int64(st, i)
        override fun getInt(i: Int) = sqlite3_column_int64(st, i).toInt()
        override fun getDouble(i: Int) = sqlite3_column_double(st, i)
        override fun getString(i: Int): String? =
            if (isNull(i)) null else sqlite3_column_text(st, i)?.let { it.reinterpret<kotlinx.cinterop.ByteVar>().toKString() }
        override val columnCount: Int get() = sqlite3_column_count(st)
        override val columnNames: Array<String> get() = Array(columnCount) { sqlite3_column_name(st, it)?.toKString().orEmpty() }
        override val count: Int get() = 0
        override fun close() { sqlite3_finalize(st) }
    }
}

/** `SQLITE_TRANSIENT`: a macro (`(sqlite3_destructor_type)-1`) the interop can't express. */
private val SQLITE_TRANSIENT =
    kotlinx.cinterop.interpretCPointer<kotlinx.cinterop.CFunction<(kotlinx.cinterop.COpaquePointer?) -> Unit>>(kotlinx.cinterop.NativePtr.NULL + (-1L))
