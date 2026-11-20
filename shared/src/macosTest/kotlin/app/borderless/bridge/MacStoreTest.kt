@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package app.borderless.bridge

import app.borderless.platform.Files
import app.borderless.platform.fileExists
import app.borderless.platform.nowMs
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import kotlin.test.*

class MacStoreTest {
    private val dataKey = ByteArray(32) { 9 }
    private val wrapKey = ByteArray(32) { 7 }
    private class Box(private val key: ByteArray) : WrapBox {
        fun wrap(plain: ByteArray) = MacSeal.seal(key, plain, "wrap")
        override fun unwrap(wrapped: ByteArray) = MacSeal.open(key, wrapped, "wrap")
    }
    private var seq = 0
    private fun dir() = (NSTemporaryDirectory() + "blplain-${nowMs()}-${seq++}").also { Files.makeDirs(it) }
    private fun legacy(d: String) {
        MacCrypto.resetForTest()
        Files.makeDirs("$d/keys")
        Files.writeBytes("$d/keys/data.key", "BLWK".encodeToByteArray() + Box(wrapKey).wrap(dataKey))
        MacCrypto.wrapBoxes = { listOf(Box(wrapKey)) }
        MacCrypto.loadLegacy(d)
    }
    private fun clean(d: String) { MacCrypto.resetForTest(); NSFileManager.defaultManager.removeItemAtPath(d, null) }

    @Test fun newStorageNeverNeedsOrCreatesAKey() {
        val d = dir()
        try {
            MacCrypto.resetForTest(); MacCrypto.loadLegacy(d)
            val store = MacStore("$d/repo")
            val bytes = "[{\"id\":\"s1\"}]".encodeToByteArray()
            store.write("servers.json", bytes)
            assertContentEquals(bytes, Files.readBytes("$d/repo/servers.json"))
            assertContentEquals(bytes, store.read("servers.json"))
            assertNull(MacCrypto.key)
            assertFalse(fileExists("$d/keys/data.key"))
        } finally { clean(d) }
    }
    @Test fun encryptedServersBecomePlaintextAndOriginalIsPreserved() {
        val d = dir()
        try {
            legacy(d); val store = MacStore("$d/repo")
            val bytes = "[{\"id\":\"s1\"}]".encodeToByteArray()
            val sealed = MacSeal.seal(dataKey, bytes, "servers.json")
            Files.writeBytes("$d/repo/servers.json", sealed)
            assertContentEquals(bytes, store.read("servers.json"))
            assertContentEquals(bytes, Files.readBytes("$d/repo/servers.json"))
            assertContentEquals(sealed, Files.readBytes("$d/repo/servers.json.encrypted-backup"))
            // Once migrated, losing every old key has no effect on reading or saving user data.
            MacCrypto.resetForTest()
            assertContentEquals(bytes, store.read("servers.json"))
            store.write("servers.json", "[]".encodeToByteArray())
            assertContentEquals("[]".encodeToByteArray(), store.read("servers.json"))
        } finally { clean(d) }
    }
    @Test fun unreadableAutoCacheCannotHideServersOrLockSettings() {
        val d = dir()
        try {
            legacy(d); val store = MacStore("$d/repo")
            val servers = "[{\"id\":\"s1\"}]".encodeToByteArray()
            Files.writeBytes("$d/repo/servers.json", MacSeal.seal(dataKey, servers, "servers.json"))
            val cache = MacSeal.seal(ByteArray(32) { 3 }, "{}".encodeToByteArray(), "auto.json")
            Files.writeBytes("$d/repo/auto.json", cache)
            assertContentEquals(servers, store.read("servers.json"))
            assertContentEquals("{}".encodeToByteArray(), store.read("auto.json"))
            assertFalse(store.locked)
            store.write("settings.json", "{\"ecoOn\":true}".encodeToByteArray())
            assertContentEquals(cache, Files.readBytes("$d/repo/auto.json.encrypted-backup"))
        } finally { clean(d) }
    }
    @Test fun unreadableUserSettingsRemainProtected() {
        val d = dir()
        try {
            legacy(d); val store = MacStore("$d/repo")
            val original = MacSeal.seal(ByteArray(32) { 3 }, "{}".encodeToByteArray(), "settings.json")
            Files.writeBytes("$d/repo/settings.json", original)
            assertNull(store.read("settings.json")); assertTrue(store.locked)
            assertFailsWith<MacCrypto.Locked> { store.write("settings.json", "{}".encodeToByteArray()) }
            assertContentEquals(original, Files.readBytes("$d/repo/settings.json"))
        } finally { clean(d) }
    }
    @Test fun preservedServersAreRecoveredUsingTheOriginalFileLabel() {
        val d = dir()
        try {
            legacy(d); val store = MacStore("$d/repo")
            val servers = "[{\"id\":\"s1\"}]".encodeToByteArray()
            val original = MacSeal.seal(dataKey, servers, "servers.json")
            Files.writeBytes("$d/repo/servers.json.broken-1", original)
            val broken = MacSeal.seal(ByteArray(32) { 3 }, "[]".encodeToByteArray(), "servers.json")
            Files.writeBytes("$d/repo/servers.json", broken)
            assertContentEquals(servers, store.read("servers.json"))
            assertFalse(store.locked)
            assertContentEquals(broken, Files.readBytes("$d/repo/servers.json.encrypted-backup"))
            assertContentEquals(original, Files.readBytes("$d/repo/servers.json.broken-1"))
        } finally { clean(d) }
    }
    @Test fun missingPrimaryCanBeRestoredFromItsEncryptedBackup() {
        val d = dir()
        try {
            legacy(d); val store = MacStore("$d/repo")
            val servers = "[{\"id\":\"s1\"}]".encodeToByteArray()
            Files.writeBytes("$d/repo/servers.json.broken-1", MacSeal.seal(dataKey, servers, "servers.json"))
            assertContentEquals(servers, store.read("servers.json"))
            assertContentEquals(servers, Files.readBytes("$d/repo/servers.json"))
            assertFalse(store.locked)
        } finally { clean(d) }
    }
    @Test fun failedMigrationNeverOverwritesTheEncryptedSource() {
        val d = dir()
        try {
            legacy(d); val store = MacStore("$d/repo")
            val original = MacSeal.seal(dataKey, "{}".encodeToByteArray(), "settings.json")
            Files.writeBytes("$d/repo/settings.json", original)
            Files.makeDirs("$d/repo/settings.json.tmp") // force an I/O failure
            store.read("settings.json")
            assertTrue(store.locked)
            assertContentEquals(original, Files.readBytes("$d/repo/settings.json"))
        } finally { clean(d) }
    }
    @Test fun newLogLinesArePlainAndLegacyHistoryCanStillBeRead() {
        val d = dir()
        try {
            legacy(d); val logs = MacLogFiles("$d/logs")
            val sealed = MacSeal.sealLine(dataKey, "old entry", "debug.log") + "\n"
            Files.writeBytes("$d/logs/debug.log", sealed.encodeToByteArray())
            assertEquals(listOf("old entry"), logs.read("debug.log"))
            logs.append("debug.log", listOf("new entry"), 10000)
            assertEquals(listOf("old entry", "new entry"), logs.read("debug.log"))
            assertEquals("old entry\nnew entry\n", Files.readBytes("$d/logs/debug.log")!!.decodeToString())
            assertContentEquals(sealed.encodeToByteArray(), Files.readBytes("$d/logs/debug.log.encrypted-backup"))
        } finally { clean(d) }
    }
}
