@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package app.borderless.platform

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [AesGcm] against published vectors: the AES-256 block is the FIPS-197 Appendix C.3 example, the GCM tag is
 * the NIST CAVP all-zero vector for a 256-bit key; the rest exercises the properties the store layer relies on
 * (AAD binding, tag rejection, nonce freshness).
 */
class AesGcmTest {
    private fun unhex(s: String): ByteArray =
        ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    @Test
    fun aes256BlockMatchesFips197() {
        val aes = AesGcm.RoundKeys(unhex("603deb1015ca71be2b73aef0857d77811f352c073b6108d72d9810a30914dff4"))
        val out = ByteArray(16)
        aes.encryptBlock(unhex("6bc1bee22e409f96e93d7e117393172a"), out)
        assertEquals("f3eed1bdb5d2a03c064b5a7e3db181f8", out.hex())
        aes.encryptBlock(unhex("ae2d8a571e03ac9c9eb76fac45af8e51"), out)
        assertEquals("591ccb10d410ed26dc5ba74a31362870", out.hex())
    }

    @Test
    fun gcmTagMatchesNistZeroVector() {
        // AES-256-GCM, key and IV all zero, empty plaintext and AAD: the tag is E(K, J0), the CAVP vector.
        val out = AesGcm.seal(ByteArray(32), ByteArray(12), ByteArray(0), ByteArray(0))
        assertEquals(16, out.size)
        assertEquals("530f8afbc74536b9a963b4f1c4cb738b", out.hex())
    }

    @Test
    fun sealOpenRoundTrips() {
        val key = ByteArray(32) { (it * 7 + 3).toByte() }
        val aad = "servers.json".encodeToByteArray()
        for (n in listOf(0, 1, 15, 16, 17, 4096, 10_000)) {
            val plain = ByteArray(n) { it.toByte() }
            val nonce = ByteArray(12) { it.toByte() }
            val sealed = AesGcm.seal(key, nonce, aad, plain)
            assertEquals(n + AesGcm.TAG_BYTES, sealed.size)
            assertContentEquals(plain, AesGcm.open(key, nonce, aad, sealed))
        }
    }

    @Test
    fun tamperingBreaksTheTag() {
        val key = ByteArray(32) { it.toByte() }
        val nonce = ByteArray(12) { (it + 5).toByte() }
        val plain = "the user's data".encodeToByteArray()
        for (pos in listOf(0, 5, plain.size - 1, plain.size, plain.size + 15)) {
            val sealed = AesGcm.seal(key, nonce, "f".encodeToByteArray(), plain)
            sealed[pos] = (sealed[pos].toInt() xor 0x01).toByte()
            assertNull(AesGcm.open(key, nonce, "f".encodeToByteArray(), sealed), "byte $pos")
        }
    }

    @Test
    fun wrongKeyNonceOrAadOpensAsNull() {
        val key = ByteArray(32) { it.toByte() }
        val nonce = ByteArray(12) { it.toByte() }
        val sealed = AesGcm.seal(key, nonce, "a".encodeToByteArray(), "x".encodeToByteArray())
        assertNull(AesGcm.open(ByteArray(32) { (it + 1).toByte() }, nonce, "a".encodeToByteArray(), sealed))
        assertNull(AesGcm.open(key, ByteArray(12) { (it + 1).toByte() }, "a".encodeToByteArray(), sealed))
        assertNull(AesGcm.open(key, nonce, "b".encodeToByteArray(), sealed))
        assertNull(AesGcm.open(key, nonce, "a".encodeToByteArray(), sealed.copyOf(sealed.size - 4)))
        assertNotNull(AesGcm.open(key, nonce, "a".encodeToByteArray(), sealed))
    }

    @Test
    fun sealUsesARandomNonceEachTime() {
        // The MacSeal format ("BLS1" | nonce | ct) embeds a fresh nonce — two seals of the same bytes differ.
        val key = ByteArray(32) { it.toByte() }
        val a = app.borderless.bridge.MacSeal.seal(key, "same".encodeToByteArray(), "f")
        val b = app.borderless.bridge.MacSeal.seal(key, "same".encodeToByteArray(), "f")
        assertTrue(!a.contentEquals(b))
        assertContentEquals("same".encodeToByteArray(), app.borderless.bridge.MacSeal.open(key, a, "f"))
        assertContentEquals("same".encodeToByteArray(), app.borderless.bridge.MacSeal.open(key, b, "f"))
    }
}
