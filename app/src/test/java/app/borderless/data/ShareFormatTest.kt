package app.borderless.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareFormatTest {
    private val bundle = ShareFormat.Bundle(
        groups = listOf(
            ShareFormat.G(
                name = "Работа", servers = listOf(ShareFormat.S("vless://a@h:1#one"), ShareFormat.S("trojan://b@h:2#two", off = true)),
                subs = listOf(ShareFormat.Sub("https://example.org/sub", "Example", hidden = listOf("vless://c@h:3"))),
            ),
            ShareFormat.G(default = true, servers = listOf(ShareFormat.S("ss://x@h:4#four"))),
        )
    )

    @Test
    fun roundTrip() {
        val text = ShareFormat.encode(bundle)
        assertTrue(text.startsWith(ShareFormat.PREFIX))
        assertTrue(ShareFormat.isInternal(text))
        assertEquals(bundle, ShareFormat.decode(text))
    }

    @Test
    fun plainLeavesOutHiddenServers() {
        assertEquals("vless://a@h:1#one\nhttps://example.org/sub\nss://x@h:4#four", ShareFormat.plain(bundle))
    }

    @Test
    fun keyIgnoresName() {
        assertEquals("vless://a@h:1", ShareFormat.key("vless://a@h:1#renamed"))
    }

    @Test
    fun garbageIsNotABundle() {
        assertNull(ShareFormat.decode(ShareFormat.PREFIX + "not-base64!"))
    }

    @Test
    fun hugeInflatedDataIsRefused() {
        // 50 MB of zeros deflate to a few dozen KB: decoding must stop instead of filling memory.
        val zeros = ByteArray(50_000_000)
        val d = java.util.zip.Deflater(9, true).apply { setInput(zeros); finish() }
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(65536)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        val text = "borderless://import?d=" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray())
        org.junit.Assert.assertNull(ShareFormat.decode(text))
    }
}
