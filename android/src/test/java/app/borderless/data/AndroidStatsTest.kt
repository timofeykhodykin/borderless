package app.borderless.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The file-level failure paths of [AndroidStats]: the encrypted copy's atomic swap-in (a failing rename must
 * leave the plain database readable) and the keep-aside of a file whose schema this build can't read.
 */
class AndroidStatsTest {

    private fun dir(): File = Files.createTempDirectory("stats").toFile()

    @Test
    fun swapInReplacesAndDropsJournals() {
        val d = dir()
        val file = File(d, "stats.db").apply { writeText("plain") }
        val tmp = File(d, "stats.db.enc").apply { writeText("sealed") }
        for (s in listOf("-wal", "-shm", "-journal")) File(d, "stats.db$s").writeText("old")
        // The verification connection's own leftover.
        File(d, "stats.db.enc-shm").writeText("verify")
        AndroidStats.swapIn(tmp, file)
        assertEquals("sealed", file.readText())
        assertFalse(tmp.exists())
        for (s in listOf("-wal", "-shm", "-journal")) assertFalse(File(d, "stats.db$s").exists())
        assertFalse(File(d, "stats.db.enc-shm").exists())
    }

    @Test
    fun swapInFailureKeepsTheOriginal() {
        val d = dir()
        val file = File(d, "stats.db").apply { writeText("plain") }
        File(d, "stats.db-wal").writeText("wal")
        val tmp = File(d, "stats.db.enc") // never written: the rename fails
        assertThrows(IllegalStateException::class.java) { AndroidStats.swapIn(tmp, file) }
        assertEquals("plain", file.readText())
        assertTrue(File(d, "stats.db-wal").exists())
    }

    @Test
    fun keepAsideMovesTheWholeSet() {
        val d = dir()
        val file = File(d, "stats.db").apply { writeText("newer-schema") }
        File(d, "stats.db-wal").writeText("wal")
        val aside = AndroidStats.keepAside(file)
        assertTrue(aside.name.startsWith("stats.db.incompatible-"))
        assertEquals("newer-schema", File(d, aside.name).readText())
        assertEquals("wal", File(d, aside.name + "-wal").readText())
        assertFalse(file.exists())
        assertFalse(File(d, "stats.db-wal").exists())
        // Nothing blocks an empty database in the old place.
        file.writeText("empty")
        assertEquals("empty", file.readText())
    }

    @Test
    fun dropGuardedRefusesOnlyDrops() {
        val sql = mutableListOf<String>()
        val inner = object : SqlDatabase {
            override fun execSQL(s: String) { sql += s }
            override fun execSQL(s: String, args: Array<Any?>) { sql += s }
            override fun rawQuery(s: String, args: Array<String>): SqlCursor = throw UnsupportedOperationException()
            override fun insert(table: String, nullColumnHack: String?, values: SqlValues): Long = throw UnsupportedOperationException()
            override fun delete(table: String, whereClause: String?, whereArgs: Array<String>?): Int = throw UnsupportedOperationException()
            override fun beginTransaction() {}
            override fun setTransactionSuccessful() {}
            override fun endTransaction() {}
            override var version: Int = 0
        }
        val db = AndroidStats.DropGuarded(inner)
        db.execSQL("CREATE TABLE x (a INTEGER)")
        db.execSQL("INSERT INTO x VALUES (1)")
        assertThrows(AndroidStats.DropGuarded.Dropped::class.java) { db.execSQL("DROP TABLE IF EXISTS x") }
        assertThrows(AndroidStats.DropGuarded.Dropped::class.java) { db.execSQL("  drop index ix") }
        // Everything else passes through untouched, including reads StatsDb.init needs.
        assertEquals(listOf("CREATE TABLE x (a INTEGER)", "INSERT INTO x VALUES (1)"), sql)
        assertEquals(0, db.version)
    }
}
