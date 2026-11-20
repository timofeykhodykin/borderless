package app.borderless.platform

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlatformTest {
    @Test
    fun deflateRoundTrips() {
        val text = "vless://11111111-2222-3333-4444-555555555555@a.example.com:443#A\n".repeat(20).encodeToByteArray()
        val packed = deflateRaw(text)
        assertContentEquals(text, inflateRaw(packed, 1 shl 20))
        assertNull(inflateRaw(packed, 100), "over the limit")
        assertNull(inflateRaw(byteArrayOf(1, 2, 3, 4), 1000), "damaged")
    }

    /** Raw deflate made by Python's zlib (wbits -15) for "hello hello hello": both platforms must read it. */
    @Test
    fun inflatesForeignRawDeflate() {
        val bytes = byteArrayOf(-53, 72, -51, -55, -55, 87, -56, 64, -112, 0)
        assertEquals("hello hello hello", inflateRaw(bytes, 1000)?.decodeToString())
    }

    @Test
    fun sha256OfAbc() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256("abc".encodeToByteArray()).hex())
    }

    @Test
    fun hmacRfc4231() {
        assertEquals("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
            hmacSha256("Jefe".encodeToByteArray(), "what do ya want for nothing?".encodeToByteArray()).hex())
    }

    @Test
    fun hostsOfUrls() {
        assertEquals("panel.example.com", urlHost("https://user:pw@panel.example.com:8443/sub?x=1#y"))
        assertEquals("example.org", urlHost("https://example.org"))
        assertEquals("[2001:db8::1]", urlHost("https://[2001:db8::1]:443/a"))
        assertEquals(null, urlHost("example.org/path"))
    }

    @Test
    fun fixedFormats() {
        assertEquals("1.50", fixed(1.4999999, 2))
        assertEquals("-0.3", fixed(-0.25, 1))
        assertEquals("3", fixed(2.6, 0))
    }
}

class LocalesTest {
    @kotlin.test.Test
    fun namesAndOrder() {
        kotlin.test.assertEquals("Germany", Locales.countryName("DE", "en"))
        kotlin.test.assertTrue("DE" in Locales.isoCountries() && "JP" in Locales.isoCountries())
        kotlin.test.assertEquals(listOf("Austria", "Germany", "Zambia"), Locales.sorted(listOf("Zambia", "Austria", "Germany"), "en"))
    }
}
