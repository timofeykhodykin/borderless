package app.borderless.data

import app.borderless.platform.readFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Repo mutations the post-audit fixes added or changed (F-ROUTING-0034, F-IMPORT-0029, F-DATA-0015):
 * the zone on/off switch, the real added-count of an import, and the emptied nameless-group cleanup on the
 * whole-list replace path. `Repo.init` runs once per process, so this loads the same compat fixture
 * [RepoTest] does — either order yields the same state — and every mutation is undone before a test returns.
 */
class PostAuditFixTest {
    private class Memory(val files: MutableMap<String, ByteArray>) : DataStore {
        override fun read(name: String) = files[name]
        override fun write(name: String, bytes: ByteArray) { files[name] = bytes }
        override fun keepAside(name: String) = "$name.broken"
    }

    private fun init() {
        val names = listOf("servers", "subscriptions", "pings", "settings", "groups", "auto", "zones")
        val files = names.associate { n ->
            "$n.json" to (readFile("../android/src/test/resources/compat/3.1.0/$n.json") ?: error("fixture $n missing")).encodeToByteArray()
        }.toMutableMap()
        Repo.init(Memory(files))
    }

    /** F-ROUTING-0034: switching a zone off must remove it from the enabled set, not only add on enable. */
    @Test
    fun zoneEnableWritesRegions() {
        init()
        val regions = Repo.settings.value.regions
        try {
            Repo.setZoneEnabled("u-example", false)
            assertTrue("u-example" !in Repo.settings.value.regions)
            Repo.setZoneEnabled("u-example", true)
            assertTrue("u-example" in Repo.settings.value.regions)
            // An id that was never enabled: on adds it, off keeps it out (no accidental member left).
            Repo.setZoneEnabled("u-ghost", true)
            assertTrue("u-ghost" in Repo.settings.value.regions)
            Repo.setZoneEnabled("u-ghost", false)
            assertTrue("u-ghost" !in Repo.settings.value.regions)
        } finally {
            Repo.updateSettings { it.copy(regions = regions) }
        }
    }

    /** F-IMPORT-0029: an import's count must be the servers that really joined, not the parsed ones. */
    @Test
    fun addServersCountsOnlyNewIds() {
        init()
        val fresh = Repo.servers.value.first().copy(id = "test-new-server")
        try {
            assertEquals(1, Repo.addServers(listOf(fresh)))
            // The same id again joins nothing — a duplicate re-import is no longer reported as added.
            assertEquals(0, Repo.addServers(listOf(fresh.copy(name = "renamed"))))
        } finally {
            Repo.removeServer("test-new-server")
        }
    }

    /** F-DATA-0015 / F-SUBS-0031: a nameless group a deleted subscription leaves goes with the whole-list save. */
    @Test
    fun replaceAllDropsEmptiedNamelessGroups() {
        init()
        // nameless, nothing in it: the group a deleted subscription leaves behind on the Mac path.
        Repo.replaceAll(Repo.servers.value, Repo.groups.value + Group("g-orphan-test"), Repo.subscriptions.value)
        assertFalse(Repo.groups.value.any { it.id == "g-orphan-test" })
        // A nameless group that still holds a subscription is kept, as is any named group.
        val sub = Repo.subscriptions.value.first().copy(id = "test-sub", groupId = "g-keep-test")
        try {
            Repo.replaceAll(Repo.servers.value, Repo.groups.value + Group("g-keep-test"), Repo.subscriptions.value + sub)
            assertTrue(Repo.groups.value.any { it.id == "g-keep-test" })
            // Once it is empty the group goes, on the next whole-list save too.
            Repo.replaceAll(Repo.servers.value, Repo.groups.value, Repo.subscriptions.value.filter { it.id != "test-sub" })
            assertFalse(Repo.groups.value.any { it.id == "g-keep-test" })
        } finally {
            Repo.replaceAll(
                Repo.servers.value,
                Repo.groups.value.filter { it.id != "g-keep-test" },
                Repo.subscriptions.value.filter { it.id != "test-sub" },
            )
        }
    }
}
