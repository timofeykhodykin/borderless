package app.borderless.bridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Mac meter's pure math (ENERGY-DESIGN §4): the resources() line, counter deltas, the learned
 *  J/ms, energy-unavailable detection and the infrastructure split — all without a live service. */
class MacEnergyMathTest {

    private fun line(
        self: Triple<Long, Long, Long> = Triple(1000, 2_000_000, 40_000),
        helper: Triple<Long, Long, Long> = Triple(500, 500_000, 30_000),
        core: Triple<Long, Long, Long> = Triple(9000, 18_000_000, 120_000),
        limited: Triple<Long, Long, Long> = Triple(60, 0, 10_000),
        stamp: Long = 1_700_000_000_000,
    ): String = listOf(self, helper, core, limited).joinToString(" ") { "${it.first} ${it.second} ${it.third}" } + " $stamp"

    @Test
    fun parseResources() {
        val s = MacEnergyMath.parseResources(line())!!
        assertEquals(1000, s.self.cpuMs)
        assertEquals(2_000_000, s.self.nj)
        assertEquals(40_000, s.self.fbKb)
        assertEquals(500, s.helper.cpuMs)
        assertEquals(9000, s.core.cpuMs)
        assertEquals(60, s.limited.cpuMs)
        assertEquals(1_700_000_000_000, s.lastClientReqMs)
        // The stream the CPU share works on: service + helper; process: + the main core; total: + limited.
        assertEquals(1500, s.streamCpuMs)
        assertEquals(10_500, s.processCpuMs)
        assertEquals(10_560, s.totalCpuMs)
        assertEquals(200_000, s.totalFbKb)
    }

    @Test
    fun parseResourcesBad() {
        assertNull(MacEnergyMath.parseResources(""))
        assertNull(MacEnergyMath.parseResources("1 2 3"))
        assertNull(MacEnergyMath.parseResources("a b c d e f g h i j k l m"))
        // Short lines (an older service shipping fewer fields) fail the same way — never a crash.
        assertNull(MacEnergyMath.parseResources(line().substringBeforeLast(" ")))
        // A "-" field (−1 = unknown) reads as 0, the stamp stays signed.
        val s = MacEnergyMath.parseResources("-1 -1 -1 0 0 0 0 0 0 0 0 0 -1")!!
        assertEquals(0, s.self.cpuMs)
        assertEquals(-1, s.lastClientReqMs)
    }

    @Test
    fun deltaClamped() {
        assertEquals(30, MacEnergyMath.delta(100, 130))
        assertEquals(0, MacEnergyMath.delta(130, 100)) // a reset reads as 0, never negative
        assertEquals(0, MacEnergyMath.delta(0, 0))
    }

    @Test
    fun learnRateEwmaAndClamp() {
        // 100 CPU-ms cost 200 mJ (2 J/ms = 2 W): from a 6 W seed the estimate moves a quarter of the way.
        val r = MacEnergyMath.learnRate(6.0, cpuDelta = 100, njDelta = 200_000_000)
        assertEquals(5.0, r, 0.001)
        // Repeated identical observations converge on the measured 2 W.
        var cur = 6.0
        repeat(20) { cur = MacEnergyMath.learnRate(cur, 100, 200_000_000) }
        assertEquals(2.0, cur, 0.05)
        // Nothing measured (0 or a reset) teaches nothing.
        assertEquals(6.0, MacEnergyMath.learnRate(6.0, 0, 100))
        assertEquals(6.0, MacEnergyMath.learnRate(6.0, 100, 0))
        // Implausible readings stay inside the design's 0.05…30 W.
        assertEquals(MacEnergyMath.MAX_W, MacEnergyMath.learnRate(29.9, 1, 1_000_000_000))
        assertEquals(MacEnergyMath.MIN_W, MacEnergyMath.learnRate(MacEnergyMath.MIN_W, 10_000, 1))
    }

    @Test
    fun energyDead() {
        // CPU grows while nj stays 0: dead after a minute of CPU (an Intel Mac reads 0 forever).
        var dead = 0L
        dead = MacEnergyMath.trackDead(dead, 10_000, 0)
        assertFalse(MacEnergyMath.energyDead(dead))
        dead = MacEnergyMath.trackDead(dead, MacEnergyMath.NO_ENERGY_CPU_MS, 0)
        assertTrue(MacEnergyMath.energyDead(dead))
        // A real reading restarts the accumulation — an arm64 counter that stalls once isn't dead.
        dead = MacEnergyMath.trackDead(dead, 0, 1)
        assertEquals(0, dead)
    }

    @Test
    fun spanEnergy() {
        assertEquals(200, MacEnergyMath.spanEnergy(cpuShareMs = 100, jPerMs = 2.0))
        assertEquals(0, MacEnergyMath.spanEnergy(-5, 2.0))
    }

    @Test
    fun splitBase() {
        // Stream 1000 cpu / 2000 nj, core 5000/8000, limited 100/200; actions charged 400 of the stream.
        val b = MacEnergyMath.splitBase(
            streamCpu = 1000, streamNj = 2000, coreCpu = 5000, coreNj = 8000,
            limitedCpu = 100, limitedNj = 200, attributedCpu = 400,
        )
        assertEquals(600, b.streamBaseCpu)
        // CORE_BASE = the stream's untracked rest + the whole core.
        assertEquals(5600, b.coreBaseCpu)
        // The same share (40 %) comes out of the stream's nanojoules.
        assertEquals(1200 + 8000, b.coreBaseNj)
        assertEquals(100, b.limitedCpu)
        assertEquals(200, b.limitedNj)
    }

    @Test
    fun splitBaseOverCharged() {
        // A span ending after the sample can charge more than the sample's stream: clamps at zero,
        // never a negative base (the design's explicit case).
        val b = MacEnergyMath.splitBase(
            streamCpu = 100, streamNj = 500, coreCpu = 40, coreNj = 10,
            limitedCpu = 0, limitedNj = 0, attributedCpu = 300,
        )
        assertEquals(0, b.streamBaseCpu)
        assertEquals(40, b.coreBaseCpu)
        assertEquals(10, b.coreBaseNj)
    }
}
