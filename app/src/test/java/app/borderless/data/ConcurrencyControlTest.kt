package app.borderless.data

import app.borderless.core.ConcurrencyControl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConcurrencyControlTest {
    @Test
    fun growsWhilePingsStayNormal() {
        val c = ConcurrencyControl(start = 6, adaptive = true)
        repeat(30) { c.onResult(100, 100) }
        assertTrue(c.limit > 6)
    }

    @Test
    fun shrinksWhenPingsInflate() {
        val c = ConcurrencyControl(start = 12, adaptive = true)
        repeat(3) { c.onResult(300, 100) }
        assertEquals(10, c.limit)
    }

    @Test
    fun manualLimitNeverMoves() {
        val c = ConcurrencyControl(start = 5, adaptive = false)
        repeat(30) { c.onResult(100, 100) }
        repeat(10) { c.onResult(500, 100) }
        assertEquals(5, c.limit)
    }

    @Test
    fun staysWithinBounds() {
        val c = ConcurrencyControl(start = 4, adaptive = true)
        repeat(50) { c.onResult(900, 100) }
        assertEquals(3, c.limit)
        repeat(500) { c.onResult(100, 100) }
        assertEquals(24, c.limit)
    }
}
