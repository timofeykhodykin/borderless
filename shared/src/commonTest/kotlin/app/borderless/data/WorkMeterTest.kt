package app.borderless.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The meter is optional (Android sets `Energy.meter`, other platforms may set theirs or none — R11):
 * every [Work] call must behave with either. Covers the R11 verification note and F-DATA-0042 / F-ENGINE-0038.
 */
class WorkMeterTest {
    private class FakeMeter : Work.Meter {
        val counts = ArrayList<Triple<WorkKind, Int, Long>>()
        val ended = ArrayList<Work.Span>()
        override fun count(kind: WorkKind, n: Int, bytes: Long) { counts += Triple(kind, n, bytes) }
        override fun begin(kind: WorkKind, exchanges: Int): Work.Span = Work.Span(kind).also { it.exchanges = exchanges }
        override fun end(span: Work.Span) { ended += span }
    }

    @Test
    fun workRunsWithoutAMeterAndWithOne() {
        try {
            Work.meter = null
            // No meter: nothing throws, spans still carry what the caller set.
            Work.count(WorkKind.SCAN_MANUAL, 2, 7)
            val s = Work.begin(WorkKind.PING_PERIODIC, 3)
            assertEquals(3, s.exchanges)
            Work.end(s)
            assertEquals(42, Work.track(WorkKind.NET_CHECK) { 42 })

            val m = FakeMeter()
            Work.meter = m
            Work.count(WorkKind.SUB_UPDATE, 1, 10)
            assertEquals(listOf(Triple(WorkKind.SUB_UPDATE, 1, 10L)), m.counts)
            assertEquals("ok", Work.track(WorkKind.PROBE, 4) { "ok" })
            assertEquals(1, m.ended.size)
            assertEquals(4, m.ended.single().exchanges)
        } finally {
            Work.meter = null
        }
    }

    /** F-DATA-0034 / F-STATS-0062: `cuts` is a real table — it must take part in prune, clear and dumpCsv. */
    @Test
    fun statsTablesCoverEveryCreatedTable() {
        assertTrue("cuts" in StatsDb.TABLES)
        assertEquals(StatsDb.TABLES.size, StatsDb.TABLES.toSet().size)
    }
}
