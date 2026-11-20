package app.borderless.data

import app.borderless.net.HttpResponse
import app.borderless.net.Socks
import kotlinx.coroutines.CancellationException
import kotlin.test.*

class ExitCountryLookupTest {
    @Test fun sharedIngressDoesNotShareTheCountryOfDifferentExitProxies() {
        val one = Socks(11001, "one", "secret")
        val two = Socks(11002, "two", "secret")
        fun query(proxy: Socks) = ExitCountryLookup.query(proxy) { r ->
            assertEquals(proxy, r.proxy)
            assertEquals("https://api.country.is/", r.url) // no ingress IP in the URL
            HttpResponse(200, emptyMap(), "{\"country\":\"${if (r.proxy == one) "HU" else "PL"}\"}".encodeToByteArray())
        }
        assertEquals("HU", query(one))
        assertEquals("PL", query(two))
    }
    @Test fun failedServiceUsesSameServer() {
        val proxy = Socks(11001, "one", "secret")
        var n = 0
        val result = ExitCountryLookup.query(proxy) { r ->
            assertEquals(proxy, r.proxy)
            n++
            if (n == 1) HttpResponse(503, emptyMap(), byteArrayOf())
            else HttpResponse(200, emptyMap(), "{\"success\":true,\"country_code\":\"nl\"}".encodeToByteArray())
        }
        assertEquals("NL", result)
        assertEquals(2, n)
    }
    @Test fun invalidAnswersNeverBecomeCountries() {
        val proxy = Socks(11001, "one", "secret")
        assertNull(ExitCountryLookup.query(proxy) { HttpResponse(200, emptyMap(), "error".encodeToByteArray()) })
    }
    @Test fun cancellationIsNotSwallowed() {
        assertFailsWith<CancellationException> {
            ExitCountryLookup.query(Socks(11001, "one", "secret")) { throw CancellationException() }
        }
    }
}
