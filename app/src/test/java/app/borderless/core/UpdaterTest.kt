package app.borderless.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {
    @Test
    fun versionCodes() {
        assertEquals(10099, Updater.codeOf("0.1.0"))
        assertEquals(1000099, Updater.codeOf("1.0.0"))
        assertEquals(2000099, Updater.codeOf("2.0.0"))
        assertEquals(1010299, Updater.codeOf("1.1.2"))
        // Pre-releases and other tags are never offered.
        assertNull(Updater.codeOf("2.0.0-beta.1"))
        assertNull(Updater.codeOf("v2.0.0"))
    }

    @Test
    fun apkNames() {
        assertTrue(Updater.isApk("borderless-2.0.0-arm64-v8a.apk", "arm64-v8a"))
        assertTrue(Updater.isApk("borderless-2.0.0-armeabi-v7a.apk", "armeabi-v7a"))
        assertFalse(Updater.isApk("borderless-2.0.0-armeabi-v7a.apk", "arm64-v8a"))
        // Anything else is not taken as a file name.
        assertFalse(Updater.isApk("../borderless-2.0.0-arm64-v8a.apk", "arm64-v8a"))
        assertFalse(Updater.isApk("other-2.0.0-arm64-v8a.apk", "arm64-v8a"))
        assertFalse(Updater.isApk("borderless-2.0.0-arm64-v8a.apk.exe", "arm64-v8a"))
    }
}
