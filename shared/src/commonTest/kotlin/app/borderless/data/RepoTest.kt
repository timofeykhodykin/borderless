package app.borderless.data

import app.borderless.platform.readFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The shared [Repo] reads what the last release's Android code wrote (`compat/3.1.0`), on the JVM and on the Mac. */
class RepoTest {
    private class Memory(val files: MutableMap<String, ByteArray>) : DataStore {
        override fun read(name: String) = files[name]
        override fun write(name: String, bytes: ByteArray) { files[name] = bytes }
        override fun keepAside(name: String) = "$name.broken"
    }

    @Test
    fun readsTheLastReleasesFiles() {
        val names = listOf("servers", "subscriptions", "pings", "settings", "groups", "auto", "zones")
        val files = names.associate { n ->
            "$n.json" to (readFile("../android/src/test/resources/compat/3.1.0/$n.json") ?: error("fixture $n missing")).encodeToByteArray()
        }.toMutableMap()
        Repo.init(Memory(files))
        assertEquals(listOf("s1", "s2", "s3"), Repo.servers.value.map { it.id })
        assertEquals(listOf("sub1"), Repo.subscriptions.value.map { it.id })
        assertEquals(listOf("own", "g1"), Repo.groups.value.map { it.id })
        assertEquals(listOf("u-example"), Repo.zones.value.map { it.id })
        val s = Repo.settings.value
        assertEquals(Strategy.FAILOVER, s.strategy)
        assertEquals(setOf("DE", "-"), s.countries)
        assertTrue(!s.autoUpdate)
    }
}
