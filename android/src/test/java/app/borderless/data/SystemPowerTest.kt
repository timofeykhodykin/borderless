package app.borderless.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SystemPowerTest {
    private fun snap(cpuMams: Long, cpuMs: Long, radioMs: Long = 0) = SystemPower.Snapshot(cpuMams, cpuMs, 0, 0, radioMs, 0, 0)

    @Test
    fun deltaSkipsFirstSampleAndResets() {
        assertNull(SystemPower.delta(null, snap(10, 10)))
        assertEquals(snap(5, 7, 3), SystemPower.delta(snap(10, 10, 1), snap(15, 17, 4)))
        // The system reset its counters (after a full charge): no delta for that stretch.
        assertNull(SystemPower.delta(snap(10, 10), snap(2, 40)))
    }

    @Test
    fun cpuWattsFromAccounting() {
        // 0.13 A on average while busy, at 3.85 V: 0.5 W per busy core.
        val rows = listOf(snap(130L * 400_000, 400_000), snap(130L * 400_000, 400_000))
        assertEquals(0.5005, SystemPower.cpuWatts(rows, 3.85)!!, 1e-9)
        // Too little CPU time, no energy figures, or nonsense: not trusted.
        assertNull(SystemPower.cpuWatts(listOf(snap(130L * 100_000, 100_000)), 3.85))
        assertNull(SystemPower.cpuWatts(listOf(snap(0, 900_000)), 3.85))
        assertNull(SystemPower.cpuWatts(listOf(snap(100_000L * 900_000, 900_000)), 3.85))
    }

    @Test
    fun calibratedProfileNarrowsCpu() {
        val c = DeviceProfile.calibrated(DeviceProfile.GENERIC, 0.4)
        assertEquals(0.4, c.cpu.mid, 1e-9)
        assertEquals(0.34, c.cpu.low, 1e-9)
        assertEquals(0.46, c.cpu.high, 1e-9)
        assertEquals("generic + android", c.source)
        assertEquals(DeviceProfile.GENERIC, DeviceProfile.calibrated(DeviceProfile.GENERIC, null))
    }

    @Test
    fun radioStopsWhenThePhoneSleeps() {
        // Started at 1000, the wall clock says 30 min later, but only 2 s of it awake.
        assertEquals(3_000L, Energy.awakeEnd(1_000, 1_801_000, 2_000))
        // Awake all along: the wall clock end.
        assertEquals(1_500L, Energy.awakeEnd(1_000, 1_500, 600))
    }
}
