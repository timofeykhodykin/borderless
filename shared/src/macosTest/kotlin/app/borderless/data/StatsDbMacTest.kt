package app.borderless.data

import app.borderless.core.Phase
import app.borderless.platform.MacSql
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The shared statistics on the Mac's SQLite: schema from scratch, writes through the queue, the queries. */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
class StatsDbMacTest {
    @Test
    fun writesAndReadsBack() = runBlocking {
        val path = NSTemporaryDirectory() + "borderless-stats-test.db"
        listOf("", "-wal", "-shm").forEach { NSFileManager.defaultManager.removeItemAtPath(path + it, null) }
        val db = MacSql.open(path)
        StatsDb.init(db)
        assertEquals(5, db.version)

        val t0 = 1_800_000_000_000L
        StatsDb.phase(Phase.SEARCHING, null, t0)
        StatsDb.phase(Phase.CONNECTED, "a", t0 + 1_000)
        for (i in 0 until 6) {
            StatsDb.sample("a", 80 + i, StatsDb.Kind.PROBE, t0 + i * 60_000L)
            StatsDb.sample("b", if (i % 2 == 0) null else 200, StatsDb.Kind.PROBE, t0 + i * 60_000L)
        }
        StatsDb.scan(available = 1, total = 2, best = 80, servers = 2, reason = "SCAN_MANUAL", ts = t0 + 5_000)
        StatsDb.flush()

        val samples = StatsDb.samples(t0, t0 + 3_600_000L)
        assertEquals(12, samples.size)
        assertEquals(3, samples.count { it.server == "b" && it.ms == null })
        val agg = StatsDb.aggregates(t0, t0 + 3_600_000L)
        assertEquals(6, agg.getValue("a").count)
        assertEquals(6, agg.getValue("a").ok)
        assertTrue((agg.getValue("b").availability ?: 1.0) < 1.0)
        assertEquals(listOf(Phase.SEARCHING, Phase.CONNECTED), StatsDb.phases(t0, t0 + 3_600_000L).map { it.phase })
        assertEquals(1, StatsDb.scans(t0, t0 + 3_600_000L).single().available)
        assertNotNull(StatsDb.lastConnected())
        val rank = StatsDb.ranking(t0 - 1)
        assertTrue(rank.getValue("a") > rank.getValue("b"), "$rank")
        val csv = StringBuilder()
        StatsDb.dumpCsv("samples") { csv.append(it) }
        assertTrue(csv.startsWith("ts,server,ms,kind,took\n"), csv.take(40).toString())
        db.close()
    }
}
