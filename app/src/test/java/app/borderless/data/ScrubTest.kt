package app.borderless.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrubTest {
    @Test
    fun secretsLeave() {
        val msg = "Unexpected JSON token at offset 12: x at path: \$\nJSON input: [{\"link\":\"vless://uuid@1.2.3.4:443\"}]"
        val s = Scrub.secrets(msg)
        assertFalse(s.contains("uuid"))
        assertTrue(s.contains("JSON input: (hidden)"))
        assertEquals("see vless://… and trojan://…", Scrub.secrets("see vless://abc@h:1?x=y#n and trojan://pw@h:443"))
        // A subscription keeps its host, not its token.
        assertEquals("https://sub.example.com/…", Scrub.secrets("https://sub.example.com/api/v1/token123?x=1"))
        assertEquals("https://sub.example.com", Scrub.secrets("https://sub.example.com"))
    }

    @Test
    fun addressesBecomeLabels() {
        val text = "via shumbrat.example.org #8 (a.b.example.org:443), dial 203.0.113.7 then 203.0.113.7, local 192.168.1.2"
        val s = Scrub.addresses(text, listOf("shumbrat.example.org", "a.b.example.org"))
        assertFalse(s.contains("example.org"))
        assertFalse(s.contains("203.0.113.7"))
        assertTrue(s.contains("192.168.1.2"))
        // The same address is the same label.
        assertEquals(2, Regex("ip-1").findAll(s).count())
        assertTrue(s.contains("server-1") && s.contains("server-2"))
    }
}
