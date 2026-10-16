package app.borderless.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class ListManifestTest {
    private val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val publicKey = Base64.getEncoder().encodeToString(keys.public.encoded)

    private fun signed(payload: String, sig: ByteArray? = null): String {
        val bytes = payload.toByteArray()
        val s = sig ?: Signature.getInstance("SHA256withECDSA").run { initSign(keys.private); update(bytes); sign() }
        val e = Base64.getEncoder()
        return """{"payload":"${e.encodeToString(bytes)}","sig":"${e.encodeToString(s)}"}"""
    }

    private val payload = """{"v":1,"signed":1792160000000,"sources":{"https://github.com/o/r/releases/latest/download/":""" +
        """{"tag":"202610161407","files":{"geoip.dat":{"sha256":"ab","categories":{"RU":"cd"}}}}}}"""

    @Test
    fun verifies() {
        val m = ListManifest.verify(signed(payload), publicKey)!!
        assertEquals(1792160000000, m.signed)
        val src = m.sources.getValue("https://github.com/o/r/releases/latest/download/")
        assertEquals("202610161407", src.tag)
        assertEquals("cd", src.files.getValue("geoip.dat").categories.getValue("RU"))
    }

    @Test
    fun refusesChangesAndOtherKeys() {
        val good = signed(payload)
        // The payload changed after signing.
        val tampered = signed(payload.replace("\"cd\"", "\"ce\""), sig = Base64.getDecoder().decode(good.substringAfter("\"sig\":\"").substringBefore('"')))
        assertNull(ListManifest.verify(tampered, publicKey))
        // Signed by someone else (the app's own key isn't ours here).
        assertNull(ListManifest.verify(good))
        assertNull(ListManifest.verify("not json", publicKey))
    }

    @Test
    fun pinsGithubReleases() {
        assertEquals("https://github.com/o/r/releases/download/202610161407/",
            ListManifest.pinned("https://github.com/o/r/releases/latest/download/", "202610161407"))
        assertEquals("https://example.org/lists/", ListManifest.pinned("https://example.org/lists/", "x"))
    }

    /** The published manifest (if the repository has one) is signed by the app's key. */
    @Test
    fun publishedManifest() {
        val f = File("../lists/manifest.json").takeIf { it.isFile } ?: return
        assertNotNull(ListManifest.verify(f.readText()))
    }
}
