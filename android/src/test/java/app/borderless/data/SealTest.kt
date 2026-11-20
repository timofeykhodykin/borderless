package app.borderless.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.spec.SecretKeySpec

class SealTest {
    private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
    private val other = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")

    private fun fails(block: () -> Unit): Boolean = runCatching(block).isFailure

    @Test
    fun roundTrip() {
        val plain = """[{"link":"vless://secret@host:443"}]""".toByteArray()
        val sealed = Seal.seal(key, plain, "servers.json")
        assertTrue(Seal.isSealed(sealed))
        assertTrue(!String(sealed, Charsets.ISO_8859_1).contains("secret"))
        assertArrayEquals(plain, Seal.open(key, sealed, "servers.json"))
    }

    @Test
    fun tamperingIsDetected() {
        val sealed = Seal.seal(key, "hello".toByteArray(), "servers.json")
        val flipped = sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertTrue(fails { Seal.open(key, flipped, "servers.json") })
    }

    @Test
    fun fileCannotBeSwappedOrOpenedWithAnotherKey() {
        val sealed = Seal.seal(key, "hello".toByteArray(), "settings.json")
        assertTrue(fails { Seal.open(key, sealed, "servers.json") })
        assertTrue(fails { Seal.open(other, sealed, "settings.json") })
    }

    @Test
    fun logLines() {
        val line = Seal.sealLine(key, "10-06 12:00:00.000 [Engine] connected", "debug.log")
        assertTrue(line.startsWith("E1:") && '\n' !in line)
        assertEquals("10-06 12:00:00.000 [Engine] connected", Seal.openLine(key, line, "debug.log"))
        // Written without a Keystore: plain lines come back as they are.
        assertEquals("plain line", Seal.openLine(key, "plain line", "debug.log"))
        assertNull(Seal.openLine(other, line, "debug.log"))
    }
}
