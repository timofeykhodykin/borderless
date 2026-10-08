package app.borderless.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EcoMathTest {
    @Test
    fun strategySwappedAndRestored() {
        val s = AppSettings(strategy = Strategy.STABLE)
        val on = EcoMath.switch(s, on = true, bySaver = false)
        assertTrue(on.ecoOn)
        assertEquals(Strategy.FAILOVER, on.strategy)
        assertEquals(Strategy.STABLE, on.strategyBeforeEco)
        val off = EcoMath.switch(on, on = false, bySaver = false)
        assertEquals(Strategy.STABLE, off.strategy)
        assertNull(off.strategyBeforeEco)
    }

    @Test
    fun userChoiceDuringModeIsKept() {
        val on = EcoMath.switch(AppSettings(strategy = Strategy.FASTEST), on = true, bySaver = true)
        val picked = on.copy(strategy = Strategy.MANUAL)
        assertEquals(Strategy.MANUAL, EcoMath.switch(picked, on = false, bySaver = true).strategy)
    }

    @Test
    fun noSwapWhenDisabledOrAlreadyFrugal() {
        assertEquals(Strategy.STABLE, EcoMath.switch(AppSettings(strategy = Strategy.STABLE, ecoStrategy = false), true, false).strategy)
        val manual = EcoMath.switch(AppSettings(strategy = Strategy.MANUAL), true, false)
        assertEquals(Strategy.MANUAL, manual.strategy)
        assertNull(manual.strategyBeforeEco)
    }

    @Test
    fun saverDoesNotTakeOverUsersMode() {
        val user = EcoMath.switch(AppSettings(), on = true, bySaver = false)
        // Already on: the saver starting changes nothing, so its end won't turn the user's mode off.
        assertEquals(false, EcoMath.switch(user, on = true, bySaver = true).ecoBySaver)
    }

    @Test
    fun startUsesFrugalDefault() {
        val s = EcoMath.startStrategy(AppSettings(ecoOn = true, defaultStrategy = Strategy.STABLE, strategy = Strategy.MANUAL))
        assertEquals(Strategy.FAILOVER, s.strategy)
        assertEquals(Strategy.STABLE, s.strategyBeforeEco)
        assertEquals(Strategy.STABLE, EcoMath.startStrategy(AppSettings(defaultStrategy = Strategy.STABLE)).strategy)
    }

    @Test
    fun policyIsMorePatient() {
        val p = AppSettings(strategy = Strategy.STABLE, ecoOn = true).policy
        val base = AppSettings(strategy = Strategy.STABLE).policy
        assertEquals(base.rescanMin * 3, p.rescanMin)
        assertTrue(p.gainPercent > base.gainPercent && p.gainMs > base.gainMs && p.minStayMin > base.minStayMin)
    }

    @Test
    fun savingsFromOwnSplit() {
        // Half the energy is the core forwarding traffic (unchanged), half periodic pings (a third left).
        val share = EcoMath.savings(mapOf("CORE_BASE" to 50.0, "PING_PERIODIC" to 50.0), servers = 20, strategySwapped = false)!!
        assertEquals(1 - (50 + 50 / 3.0) / 100, share, 1e-9)
        assertNull(EcoMath.savings(emptyMap(), 20, false))
        val typical = EcoMath.savings(EcoMath.TYPICAL, 40, strategySwapped = true)!!
        assertTrue(typical in 0.2..0.7)
    }

    @Test
    fun intervalsFromHistory() {
        val h = listOf(0L to "true", 100L to "false", 200L to "true", 250L to "true")
        assertEquals(listOf(50L to 100L, 200L to 300L), EcoMath.onIntervals(h, 50, 300))
        assertEquals(listOf(100L to 200L), EcoMath.complement(listOf(50L to 100L, 200L to 300L), 50, 300))
        assertEquals(emptyList<Pair<Long, Long>>(), EcoMath.onIntervals(emptyList(), 0, 10))
    }
}
