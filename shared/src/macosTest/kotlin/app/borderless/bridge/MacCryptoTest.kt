@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package app.borderless.bridge

import app.borderless.platform.Files
import app.borderless.platform.fileExists
import app.borderless.platform.nowMs
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import kotlin.test.*

class MacCryptoTest {
    private val wrapKey = ByteArray(32) { 8 }
    private val dataKey = ByteArray(32) { 1 }
    private class Box(val key: ByteArray, var fails: Boolean = false) : WrapBox {
        fun wrap(plain: ByteArray) = MacSeal.seal(key, plain, "wrap")
        override fun unwrap(wrapped: ByteArray): ByteArray {
            if (fails) error("temporarily unavailable")
            return MacSeal.open(key, wrapped, "wrap")
        }
    }
    private var seq = 0
    private fun dir() = (NSTemporaryDirectory() + "bllegacy-${nowMs()}-${seq++}").also { Files.makeDirs(it) }
    private fun file(d: String, name: String, raw: ByteArray) {
        Files.makeDirs("$d/keys")
        Files.writeBytes("$d/keys/$name", "BLWK".encodeToByteArray() + Box(wrapKey).wrap(raw))
    }
    private fun load(d: String, box: Box = Box(wrapKey)) {
        MacCrypto.resetForTest(); MacCrypto.wrapBoxes = { listOf(box) }; MacCrypto.loadLegacy(d)
    }
    private fun clean(d: String) { MacCrypto.resetForTest(); NSFileManager.defaultManager.removeItemAtPath(d, null) }

    @Test fun freshInstallCreatesNoEncryptionKeys() {
        val d = dir()
        try { load(d); assertNull(MacCrypto.key); assertFalse(fileExists("$d/keys/data.key")) }
        finally { clean(d) }
    }
    @Test fun readingLegacyKeysNeverRewritesTheirBytes() {
        val d = dir()
        try {
            file(d, "data.key", dataKey)
            val original = Files.readBytes("$d/keys/data.key")!!
            load(d); assertContentEquals(dataKey, MacCrypto.key)
            assertContentEquals(original, Files.readBytes("$d/keys/data.key"))
            assertFalse(fileExists("$d/keys/data.key.recovery"))
        } finally { clean(d) }
    }
    @Test fun unreadablePrimaryDoesNotPreventUsingTheRecoveryKey() {
        val d = dir()
        try {
            file(d, "data.key.recovery", dataKey)
            Files.writeBytes("$d/keys/data.key", "damaged".encodeToByteArray())
            load(d); assertContentEquals(dataKey, MacCrypto.key)
            assertEquals("damaged", Files.readBytes("$d/keys/data.key")!!.decodeToString())
        } finally { clean(d) }
    }
    @Test fun olderKeysRecoverWithoutRotation() {
        val d = dir()
        try {
            file(d, "data.key", ByteArray(32) { 2 })
            file(d, "data.key.broken-1", dataKey)
            val blob = MacSeal.seal(dataKey, "servers".encodeToByteArray(), "servers.json")
            load(d)
            assertContentEquals("servers".encodeToByteArray(), MacCrypto.open(blob, "servers.json").first)
            assertEquals(1, MacCrypto.dbOldKeys().size)
        } finally { clean(d) }
    }
    @Test fun unavailableKeyDoesNotBlockPlaintextAndCanBeRetriedReadOnly() {
        val d = dir()
        try {
            file(d, "data.key", dataKey)
            val original = Files.readBytes("$d/keys/data.key")!!
            val box = Box(wrapKey, fails = true)
            load(d, box); assertNull(MacCrypto.key)
            assertContentEquals("{}".encodeToByteArray(), MacCrypto.open("{}".encodeToByteArray(), "settings.json").first)
            val blob = MacSeal.seal(dataKey, "{}".encodeToByteArray(), "settings.json")
            assertFailsWith<MacCrypto.Locked> { MacCrypto.open(blob, "settings.json") }
            box.fails = false; MacCrypto.loadLegacy(d)
            assertContentEquals("{}".encodeToByteArray(), MacCrypto.open(blob, "settings.json").first)
            assertContentEquals(original, Files.readBytes("$d/keys/data.key"))
        } finally { clean(d) }
    }
}
