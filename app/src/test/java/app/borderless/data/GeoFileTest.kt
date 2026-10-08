package app.borderless.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GeoFileTest {
    private val geosite = File("src/main/assets/geosite.dat").readBytes()

    @Test
    fun readsTheBundledFile() {
        val codes = GeoFile.entries(geosite).map { it.code.uppercase() }
        assertTrue("CATEGORY-ADS-ALL" in codes && "PRIVATE" in codes)
        // Written back unchanged.
        assertArrayEquals(geosite, GeoFile.write(GeoFile.entries(geosite).map { it.raw }))
    }

    @Test
    fun picksInRangesAndReadsOnlyWhatIsNeeded() {
        var read = 0L
        val src = GeoFile.Source { o, n -> read += n; geosite.copyOfRange(o.toInt(), (o + n).toInt()) }
        // The two smallest categories of the bundled file, whatever they are.
        val small = GeoFile.entries(geosite).sortedBy { it.raw.size }.take(2).map { it.code.uppercase() }.toSet()
        val picked = GeoFile.pick(src, geosite.size.toLong(), small)
        assertEquals(small, picked.keys)
        val whole = GeoFile.entries(geosite).associate { it.code.uppercase() to it.raw }
        small.forEach { assertArrayEquals(whole.getValue(it), picked.getValue(it)) }
        // Headers of the others plus the two bodies: far less than the file.
        assertTrue(read < geosite.size / 4)
    }

    @Test(expected = IllegalStateException::class)
    fun damagedFileIsRejected() {
        val cut = geosite.copyOf(geosite.size - 10)
        GeoFile.pick({ o, n -> cut.copyOfRange(o.toInt(), minOf(cut.size, (o + n).toInt())) }, cut.size.toLong(), setOf("PRIVATE"))
    }

    @Test
    fun rulesNeedTheirCategories() {
        val zone = Regions.all.first()
        val geo = zone.geo!!
        val all = mapOf("geosite" to setOf("PRIVATE") + geo.geosite.keys.map { it.uppercase() }, "geoip" to setOf("PRIVATE") + geo.geoip.keys.map { it.uppercase() })
        try {
            // Every list of the zone is here: its rules apply.
            GeoFile.installed = all
            assertTrue(Regions.ready(zone))
            val on = AppSettings.fresh().copy(regions = setOf(zone.code))
            assertTrue(zone.directDomains.first() in app.borderless.config.XrayConfig.RegionRules(on).directDomains)
            // Some still being downloaded: the zone takes no part at all yet (and the core still starts).
            val notBundled = geo.notBundled.map { it.uppercase() }.toSet()
            GeoFile.installed = all.mapValues { (_, v) -> v - notBundled }
            assertFalse(Regions.ready(zone))
            assertFalse(GeoFile.usable(zone.serverDomains.first()))
            val rules = app.borderless.config.XrayConfig.RegionRules(on)
            assertTrue(rules.directIps.isEmpty() && rules.serverDomains.isEmpty() && rules.serverIps.isEmpty())
            assertTrue(GeoFile.usable("domain:example.com") && GeoFile.usable("1.2.3.4/24"))
        } finally {
            GeoFile.installed = null
        }
    }

    @Test
    fun renamedEntryKeepsItsContent() {
        val e = GeoFile.entries(geosite).first()
        val r = GeoFile.rename(e.raw, "ZONE-1")
        assertEquals("ZONE-1", GeoFile.entries(r).single().code)
        // Everything after the name is the same.
        assertArrayEquals(e.raw.copyOfRange(e.raw.size - 32, e.raw.size), r.copyOfRange(r.size - 32, r.size))
    }



    @Test
    fun unitsNeverOverflow() {
        assertEquals(app.borderless.R.string.act_seconds, Units.duration(-5).first)
        assertEquals(app.borderless.R.string.dur_ms, Units.duration(90).first)
        assertEquals(app.borderless.R.string.dur_m, Units.duration(3000).first)
        assertEquals(app.borderless.R.string.dur_hm, Units.duration(3 * 3600 + 120).first)
        assertEquals(app.borderless.R.string.dur_d, Units.duration(7 * 86_400).first)
        // Absurd values (damaged data) still give a day count, never an overflowed one.
        assertEquals(Int.MAX_VALUE, Units.duration(Long.MAX_VALUE).second[0])
    }

    @Test
    fun dueByInterval() {
        val day = 86_400_000L
        assertFalse(GeoFile.due(0, 0, false, false, 100 * day))
        assertTrue(GeoFile.due(0, 3, false, false, 3 * day))
        assertFalse(GeoFile.due(0, 3, true, false, 5 * day))
        assertTrue(GeoFile.due(0, 3, true, false, 6 * day))
        assertFalse(GeoFile.due(0, 3, true, true, 11 * day))
    }
}
