package app.borderless.data

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Data written by every release must stay readable: `test/resources/compat/<version>/` holds files written by that
 * release's own code (generated from its commit with FixtureDumpTest, example data only). Every public release adds
 * its folder; every folder must keep parsing.
 */
class CompatTest {
    private val root = javaClass.classLoader!!.getResource("compat")?.toURI()?.let(::File)

    private fun read(version: String, name: String) = File(root, "$version/$name").readText()

    @Test
    fun everyReleaseFolderParses() {
        val versions = root?.listFiles()?.filter { it.isDirectory }?.map { it.name }.orEmpty()
        versions.forEach { v ->
            StoreJson.decodeFromString(ListSerializer(Server.serializer()), read(v, "servers.json"))
            StoreJson.decodeFromString(ListSerializer(Subscription.serializer()), read(v, "subscriptions.json"))
            StoreJson.decodeFromString(ListSerializer(Group.serializer()), read(v, "groups.json"))
            StoreJson.decodeFromString(MapSerializer(String.serializer(), PingRecord.serializer()), read(v, "pings.json"))
            StoreJson.decodeFromString(AppSettings.serializer(), read(v, "settings.json"))
            StoreJson.decodeFromString(AutoState.serializer(), read(v, "auto.json"))
        }
    }

    @Test
    fun unknownValuesFallBack() {
        // A file from a newer version (or a removed option): unknown keys and enum values must not break reading.
        val s = StoreJson.decodeFromString(AppSettings.serializer(), """{"strategy":"SOMETHING_NEW","removedOption":1,"statsDays":30}""")
        assertEquals(AppSettings().strategy, s.strategy)
        assertEquals(30, s.statsDays)
    }
}
