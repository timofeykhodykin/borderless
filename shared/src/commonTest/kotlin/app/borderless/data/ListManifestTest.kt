package app.borderless.data

import app.borderless.platform.readFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.fail

/** The signature check on every platform: the published manifest is signed by the app's key; a changed one isn't. */
class ListManifestCommonTest {
    @Test
    fun publishedManifestChecksOut() {
        // The file is in the repo and read from the module's working directory on every platform the test
        // runs on (JVM and macOS native): a miss must fail, not silently skip the signature check.
        val text = readFile("../lists/manifest.json")
            ?: fail("lists/manifest.json is not readable from the test working directory (../lists/manifest.json)")
        assertNotNull(ListManifest.verify(text))
        // One payload byte changed: refused.
        val payload = text.substringAfter("\"payload\":\"").substringBefore('"')
        val i = payload.length / 2
        val changed = payload.substring(0, i) + (if (payload[i] == 'A') 'B' else 'A') + payload.substring(i + 1)
        assertNull(ListManifest.verify(text.replace(payload, changed)))
    }

    @Test
    fun projectName() {
        assertEquals(2, ListManifest.project().split('/').size)
    }
}
