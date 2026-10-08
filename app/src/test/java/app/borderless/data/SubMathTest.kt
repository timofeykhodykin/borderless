package app.borderless.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubMathTest {
    @Test
    fun intervalFollowsProviderButNotTooOften() {
        assertEquals(12, SubMath.intervalHours(12, null, eco = false))
        assertEquals(24, SubMath.intervalHours(12, 24, eco = false))
        assertEquals(SubMath.MIN_PROVIDER_HOURS, SubMath.intervalHours(12, 1, eco = false))
        assertEquals(48, SubMath.intervalHours(12, 24, eco = true))
        assertEquals(0, SubMath.intervalHours(0, 6, eco = false)) // automatic updates off: manual only
    }

    @Test
    fun dueRespectsMeteredInEco() {
        val sub = Subscription("a", "A", "https://x", updatedAt = 0, providerHours = 6)
        val h = 3_600_000L
        // Provider: 6 h. Normal: due after 6 h; battery saving: 12 h; battery saving on mobile data: 24 h.
        assertTrue(SubMath.due(sub, 7 * h, 12, eco = false, metered = true))
        assertFalse(SubMath.due(sub, 11 * h, 12, eco = true, metered = false))
        assertTrue(SubMath.due(sub, 13 * h, 12, eco = true, metered = false))
        assertFalse(SubMath.due(sub, 13 * h, 12, eco = true, metered = true))
        assertTrue(SubMath.due(sub, 25 * h, 12, eco = true, metered = true))
    }

    @Test
    fun parsesIntervalHeader() {
        assertEquals(12, SubMath.parseInterval("12"))
        assertEquals(12, SubMath.parseInterval(" 12.0 "))
        assertNull(SubMath.parseInterval("soon"))
        assertNull(SubMath.parseInterval("0"))
    }

    private fun srv(id: String, name: String, host: String = "h", port: Int = 443, protocol: String = "VLESS · Reality") =
        Server(id, name, "link-$id", protocol, host, port, subscriptionId = "s")

    @Test
    fun changedServersKeepTheirIdentity() {
        val prev = listOf(srv("a", "Germany", "de.x"), srv("b", "Finland", "fi.x"), srv("c", "Poland", "pl.x"))
        val fresh = listOf(
            srv("a", "Germany", "de.x"),            // unchanged
            srv("b2", "Finland", "fi.x", 8443),     // new port, same name: same server
            srv("c2", "Poland 2", "pl.x"),           // renamed and new key, same address: same server
            srv("d", "Sweden", "se.x"),             // really new
        )
        assertEquals(mapOf("b2" to "b", "c2" to "c"), SubMath.matchChanged(prev, fresh))
    }

    @Test
    fun ambiguousAddressesAreNotGuessed() {
        // Two servers on one host and port, both renamed and changed: can't tell which is which.
        val prev = listOf(srv("a", "WS"), srv("b", "gRPC"))
        val fresh = listOf(srv("a2", "WS new"), srv("b2", "gRPC new"))
        assertEquals(emptyMap<String, String>(), SubMath.matchChanged(prev, fresh))
        // Same names, though, are enough even on a shared address.
        assertEquals(mapOf("a3" to "a", "b3" to "b"), SubMath.matchChanged(prev, listOf(srv("a3", "WS"), srv("b3", "gRPC"))))
    }

    @Test
    fun failedUpdatesBackOff() {
        val min = 60_000L
        assertEquals(0L, SubMath.retryDelayMs(0))
        assertEquals(15 * min, SubMath.retryDelayMs(1))
        assertEquals(30 * min, SubMath.retryDelayMs(2))
        assertEquals(120 * min, SubMath.retryDelayMs(4))
        assertEquals(240 * min, SubMath.retryDelayMs(5))
        assertEquals(240 * min, SubMath.retryDelayMs(40))
    }
}
