package app.borderless.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CountryStatsTest {
    @Test
    fun timesChosenCountsEachJoin() {
        // DE chosen, then FR added, then nothing, then DE again.
        assertEquals(mapOf("DE" to 2, "FR" to 1), CountryStats.timesChosen(listOf("DE", "DE,FR", "", "DE")))
        assertEquals(emptyMap<String, Int>(), CountryStats.timesChosen(listOf("", "")))
    }

    @Test
    fun stabilityIsSmoothed() {
        fun srv(id: String, country: String?) = Server(id, id, "x", "VLESS", "h", 443, country = country)
        val servers = listOf(srv("a", "DE"), srv("b", "DE"), srv("c", "FR"), srv("d", null))
        val agg = mapOf(
            "a" to StatsDb.Aggregate(100, 90, null, null, null),
            "b" to StatsDb.Aggregate(100, 100, null, null, null),
            "c" to StatsDb.Aggregate(2, 2, null, null, null),
        )
        val st = CountryStats.stability(servers, agg)
        assertEquals(191.0 / 202.0, st.getValue("DE"), 1e-9)
        assertEquals(3.0 / 4.0, st.getValue("FR"), 1e-9)
        assertTrue(Countries.UNKNOWN !in st) // never checked
    }

    @Test
    fun stabilityCountsTimeNotChecks() {
        fun srv(id: String, country: String?) = Server(id, id, "x", "VLESS", "h", 443, country = country)
        // 300 checks, mostly retries in a bad hour: by time (12 slices, 11 of them answered) it is 11/12, not 100/300.
        val agg = mapOf("a" to StatsDb.Aggregate(300, 100, null, null, null, share = 11.0 / 12, slices = 12))
        assertEquals(12.0 / 14.0, CountryStats.stability(listOf(srv("a", "DE")), agg).getValue("DE"), 1e-9)
    }
}
