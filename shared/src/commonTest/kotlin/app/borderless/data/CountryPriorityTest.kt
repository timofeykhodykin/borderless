package app.borderless.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CountryPriorityTest {
    private fun server() = Server("s", "🇩🇪 Frankfurt", "x", "VLESS", "example.com", 443)

    @Test fun ipWinsOverAConflictingNameAndSurvivesFallback() {
        val located = Countries.fromIp(server(), "NL")
        assertEquals("NL", Countries.nameFallback(located).country)
        assertTrue(located.countryByIp)
        assertFalse(Countries.needsIpLookup(located))
    }
    @Test fun nameIsOnlyAFallbackAndDoesNotSuppressLaterIpLookup() {
        val fallback = Countries.nameFallback(server())
        assertEquals("DE", fallback.country)
        assertTrue(Countries.needsIpLookup(fallback))
        assertEquals("NL", Countries.fromIp(fallback, "NL").country)
    }
    @Test fun manualCountryAlwaysWins() {
        val manual = server().copy(country = "FR", countryManual = true)
        assertEquals(manual, Countries.fromIp(manual, "NL"))
        assertEquals(manual, Countries.nameFallback(manual))
        assertFalse(Countries.needsIpLookup(manual))
    }
    @Test fun oldStoredNameHintsAreCheckedByIp() {
        assertTrue(Countries.needsIpLookup(server().copy(country = "DE")))
    }
    @Test fun oldIngressIpResultsAreRecheckedAndTemporarilyUseTheNameHint() {
        val ingress = server().copy(country = "CZ", countryByIp = true)
        assertTrue(Countries.needsIpLookup(ingress))
        assertEquals("DE", Countries.nameFallback(ingress).country)
        val exit = Countries.fromIp(ingress, "NL")
        assertFalse(Countries.needsIpLookup(exit))
        assertEquals("NL", Countries.nameFallback(exit).country)
    }

}
