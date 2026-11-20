package app.borderless.core

import app.borderless.data.AppSettings
import app.borderless.data.Strategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CountryFilterTest {
    @Test
    fun manualStandsAsideAndComesBack() {
        // Manual on a server that isn't among the chosen countries (here: not known at all).
        val manual = AppSettings(strategy = Strategy.MANUAL, defaultStrategy = Strategy.FASTEST, manualServerId = "gone", lastServerId = "gone")
        val on = CountryFilter.apply(manual, setOf("DE"))
        assertEquals(Strategy.FASTEST, on.strategy)
        assertEquals(Strategy.MANUAL, on.strategyBeforeCountry)
        assertEquals("gone", on.serverBeforeCountry)
        // A second country while on: nothing more to swap.
        val more = CountryFilter.apply(on, setOf("DE", "FR"))
        assertEquals(Strategy.FASTEST, more.strategy)
        assertEquals(Strategy.MANUAL, more.strategyBeforeCountry)
        val off = CountryFilter.apply(more, emptySet())
        assertEquals(Strategy.MANUAL, off.strategy)
        assertNull(off.strategyBeforeCountry)
    }

    @Test
    fun aStrategyChosenMeanwhileStays() {
        val on = CountryFilter.apply(AppSettings(strategy = Strategy.MANUAL, defaultStrategy = Strategy.STABLE), setOf("DE"))
        val changed = on.copy(strategy = Strategy.FAILOVER)
        assertEquals(Strategy.FAILOVER, CountryFilter.apply(changed, emptySet()).strategy)
        // Automatic strategies aren't touched at all.
        assertEquals(Strategy.STABLE, CountryFilter.apply(AppSettings(strategy = Strategy.STABLE), setOf("DE")).strategy)
    }
}
