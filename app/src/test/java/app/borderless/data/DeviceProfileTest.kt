package app.borderless.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceProfileTest {
    private val items = mapOf(
        "cpu.cluster_power.cluster0" to 10.0, "cpu.cluster_power.cluster1" to 30.0,
        "modem.controller.rx" to 150.0, "wifi.controller.rx" to 80.0, "wifi.controller.tx" to 120.0,
        "battery.capacity" to 5000.0,
    )
    private val arrays = mapOf(
        "cpu.core_power.cluster0" to listOf(20.0, 40.0, 60.0),
        "cpu.core_power.cluster1" to listOf(100.0, 200.0, 400.0),
        "modem.controller.tx" to listOf(200.0, 300.0, 400.0),
    )

    @Test
    fun readsPowersFromTheTable() {
        val p = DeviceProfile.fromTable(items, arrays, "Test SoC", 8, emptyList())!!
        // mid = middle steps (+ half the cluster current) averaged: (45 + 215) / 2 = 130 mA at 3.85 V.
        assertEquals(0.5005, p.cpu.mid, 1e-3)
        assertTrue(p.cpu.low < p.cpu.mid && p.cpu.mid < p.cpu.high)
        // Mobile: (rx 150 + mean tx 300) / 2 = 225 mA.
        assertEquals(0.866, p.cell.mid, 1e-3)
        assertEquals(5000, p.capacityMah)
        assertEquals("power_profile", p.source)
    }

    @Test
    fun placeholderTableIsRejected() {
        // AOSP's default profile is all tiny placeholder currents.
        assertNull(DeviceProfile.fromTable(mapOf("cpu.active" to 0.1, "radio.active" to 0.1), emptyMap(), "", 8, emptyList()))
    }

    @Test
    fun rateRangeContainsTheEstimate() {
        val p = DeviceProfile.GENERIC
        val r = ActivityMath.rate(mj = 20_000.0, cpuMs = 10_000, hours = 1.0, volts = 3.85, capacityMah = 5000, p = p)!!
        assertTrue(r.mahLow <= r.mahPerHour && r.mahPerHour <= r.mahHigh)
        assertTrue(r.mahPlusMinus > 0)
        assertEquals(r.mahPerHour / 5000 * 100, r.percentPerHour!!, 1e-9)
    }
}
