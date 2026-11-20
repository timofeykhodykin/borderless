@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package app.borderless.platform

import app.borderless.data.SqlValues
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [MacSql.openSealed] + [CryptVfs]: the sealed format on disk, the plaintext migration's no-loss ordering,
 * the corrupt/wrong-key keep-aside, the old-key re-key, and a fresh run's round trip through the VFS.
 */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
class MacSqlSealedTest {
    private val key = ByteArray(32) { (it * 3 + 11).toByte() }
    private val oldKey = ByteArray(32) { (it * 5 + 7).toByte() }
    private var dirSeq = 0

    private fun dir(): String = (NSTemporaryDirectory() + "blsql-${nowMs()}-${dirSeq++}").also { Files.makeDirs(it) }

    private fun rm(dir: String) = NSFileManager.defaultManager.removeItemAtPath(dir, null)

    private fun head(path: String): ByteArray = Files.readRange(path, 0, 16)

    private fun MacSql.seedRow(value: String) {
        execSQL("CREATE TABLE IF NOT EXISTS t(v TEXT)")
        execSQL("DELETE FROM t")
        insert("t", null, SqlValues().apply { put("v", value) })
    }

    private fun MacSql.row(): String? =
        rawQuery("SELECT v FROM t", emptyArray()).use { if (it.moveToFirst()) it.getString(0) else null }

    @Test
    fun sealedRoundTripHidesHeader() {
        val d = dir()
        try {
            val path = "$d/stats.db"
            val db = MacSql.openSealed(path, key)
            db.version = 3
            db.seedRow("hello")
            db.close()
            // The database header and its WAL/journal are sealed blocks — the plain magic is nowhere on disk.
            assertEquals("BLDB", head(path).copyOfRange(0, 4).decodeToString())
            assertTrue(!Files.readBytes(path)!!.toList().windowed(16)
                .any { it.toByteArray().contentEquals("SQLite format 3\u0000".encodeToByteArray()) })
            val again = MacSql.openSealed(path, key)
            assertEquals("hello", again.row())
            assertEquals(3, again.version)
            again.close()
        } finally {
            rm(d)
        }
    }

    @Test
    fun plainMigrationKeepsData() {
        val d = dir()
        try {
            val path = "$d/stats.db"
            val plain = MacSql.open(path)
            plain.execSQL("CREATE TABLE t(v TEXT)")
            plain.insert("t", null, SqlValues().apply { put("v", "before encryption") })
            plain.version = 7
            plain.close()
            val sealed = MacSql.openSealed(path, key)
            assertEquals("before encryption", sealed.row())
            assertEquals(7, sealed.version)
            sealed.close()
            assertEquals("BLDB", head(path).copyOfRange(0, 4).decodeToString())
            // No migration leftovers: the .enc copy and its sidecars are gone (the live -wal/-shm are sealed).
            assertTrue(listDir(d).none { it.contains(".enc") }, listDir(d).toString())
            if (fileExists("$path-wal")) {
                val walHead = Files.readRange("$path-wal", 0, 4).decodeToString()
                assertEquals("BLDB", walHead)
            }
        } finally {
            rm(d)
        }
    }

    @Test
    fun aStaleEncLeftoverFromACrashedMigrationDoesNotLoseThePlaintext() {
        val d = dir()
        try {
            val path = "$d/stats.db"
            val plain = MacSql.open(path)
            plain.execSQL("CREATE TABLE t(v TEXT)")
            plain.insert("t", null, SqlValues().apply { put("v", "survivor") })
            plain.close()
            // The crash left the half-written sealed copy; the plaintext must still win.
            Files.writeBytes("$path.enc", ByteArray(333) { it.toByte() })
            val db = MacSql.openSealed(path, key)
            assertEquals("survivor", db.row())
            db.close()
            assertEquals("BLDB", head(path).copyOfRange(0, 4).decodeToString())
        } finally {
            rm(d)
        }
    }

    @Test
    fun aSealedFileUnderAnotherKeyIsKeptAsideAndAFreshOneStarts() {
        val d = dir()
        try {
            val path = "$d/stats.db"
            val db = MacSql.openSealed(path, oldKey)
            db.seedRow("under k1")
            db.close()
            val wrong = ByteArray(32) { it.toByte() }
            val fresh = MacSql.openSealed(path, wrong)
            // Not silently clobbered: the original sealed file is kept aside whole, and the new db is empty.
            assertTrue(listDir(d).any { it.startsWith("stats.db.broken-") })
            assertFailsWith<IllegalStateException> { fresh.row() } // table t does not exist in the fresh db
            fresh.execSQL("CREATE TABLE t(v TEXT)")
            fresh.close()
            // And the fresh database is an ordinary sealed one from here: a second open (the next boot)
            // just works under the key in use — the dead file doesn't throw into the start again.
            val again = MacSql.openSealed(path, wrong)
            assertEquals("BLDB", head(path).copyOfRange(0, 4).decodeToString())
            again.close()
        } finally {
            rm(d)
        }
    }

    @Test
    fun aSealedFileUnderAnOldKeyIsReKeyedInPlace() {
        val d = dir()
        try {
            val path = "$d/stats.db"
            val db = MacSql.openSealed(path, oldKey)
            db.version = 4
            db.seedRow("rekey me")
            db.close()
            // The current key can't open it, but a remembered old key can: re-keyed, not kept aside.
            val again = MacSql.openSealed(path, key, oldKeys = listOf(oldKey))
            assertEquals("rekey me", again.row())
            assertEquals(4, again.version)
            again.close()
            assertTrue(listDir(d).none { it.startsWith("stats.db.broken-") })
            // And it now opens under the current key alone.
            val plain = MacSql.openSealed(path, key)
            assertEquals("rekey me", plain.row())
            plain.close()
        } finally {
            rm(d)
        }
    }

    @Test
    fun aPlaintextDatabaseOpensUnencryptedWithoutAKey() {
        val d = dir()
        try {
            val path = "$d/stats.db"
            val db = MacSql.openSealed(path, null)
            db.execSQL("CREATE TABLE t(v TEXT)")
            db.close()
            assertEquals("SQLite format 3\u0000", head(path).decodeToString())
        } finally {
            rm(d)
        }
    }
    @Test
    fun legacyStatisticsMigrateToPlaintextWithTheirRowsAndSchema() {
        val d = dir()
        try {
            val path = "$d/stats.db"
            val db = MacSql.openSealed(path, oldKey)
            db.version = 4; db.seedRow("keep this history"); db.close()
            val before = Files.readBytes(path)!!
            val plain = MacSql.openPlain(path, listOf(key, oldKey))
            assertEquals("keep this history", plain.row())
            assertEquals(4, plain.version); plain.close()
            assertEquals("SQLite format 3\u0000", head(path).decodeToString())
            kotlin.test.assertContentEquals(before, Files.readBytes("$path.encrypted-backup"))
            val withoutKeys = MacSql.openPlain(path)
            assertEquals("keep this history", withoutKeys.row()); withoutKeys.close()
        } finally { rm(d) }
    }

    @Test
    fun failedPlaintextMigrationPreservesTheOriginalDatabase() {
        val d = dir()
        try {
            val path = "$d/stats.db"
            val db = MacSql.openSealed(path, oldKey)
            db.seedRow("keep me"); db.close()
            val before = Files.readBytes(path)!!
            kotlin.test.assertFailsWith<IllegalStateException> { MacSql.openPlain(path, listOf(key)) }
            kotlin.test.assertContentEquals(before, Files.readBytes(path))
            val again = MacSql.openSealed(path, oldKey)
            assertEquals("keep me", again.row()); again.close()
        } finally { rm(d) }
    }

    @Test
    fun plaintextMigrationIncludesUncheckpointedWalRows() {
        val d = dir()
        try {
            val source = "$d/source.db"
            val db = MacSql.openSealed(source, oldKey)
            db.seedRow("latest WAL row")
            val path = "$d/stats.db"
            for (suffix in listOf("", "-wal", "-shm")) {
                Files.readBytes(source + suffix)?.let { Files.writeBytes(path + suffix, it) }
            }
            db.close()
            val plain = MacSql.openPlain(path, listOf(oldKey))
            assertEquals("latest WAL row", plain.row())
            plain.close()
            assertEquals("SQLite format 3\u0000", head(path).decodeToString())
        } finally { rm(d) }
    }

}
