package app.borderless.data

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The routing-list checks on real files (JVM only: reads a folder named by an environment variable). */
class GeoCheckFilesTest {
    /**
     * Dev check on real files: `BORDERLESS_GEO=<dir with geoip.dat, geosite.dat>` (e.g. copied off the emulator) —
     * every direct category must pass untouched.
     */
    @Test
    fun realLists() {
        val dir = System.getenv("BORDERLESS_GEO")?.let(::File) ?: return
        val direct = Regions.directCategories()
        for (name in listOf("geoip.dat", "geosite.dat")) {
            for (e in GeoFile.entries(File(dir, name).readBytes())) {
                val code = e.code.uppercase()
                if (code !in direct[name]!!) continue
                val local = code == "PRIVATE"
                val r = if (name == "geoip.dat") GeoCheck.addresses(e.raw, e.raw, local)
                else GeoCheck.sites(e.raw, e.raw) { if (local) it in GeoCheck.LOCAL_TLDS else Regions.bareTld(it) }
                println("$name $code: dropped ${r.dropped.take(10)} refused ${r.refused}")
                assertNull(r.refused)
                assertTrue("$code ${r.dropped}", r.dropped.isEmpty())
            }
        }
    }
}
