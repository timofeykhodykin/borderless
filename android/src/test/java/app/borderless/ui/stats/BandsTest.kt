package app.borderless.ui.stats

import app.borderless.core.Phase
import app.borderless.data.StatsMath
import org.junit.Assert.assertEquals
import org.junit.Test

class BandsTest {
    private fun seg(a: Long, b: Long, p: Phase) = StatsMath.Segment(a, b, p, null)

    @Test
    fun gapsAreOffAndDirectIsMarked() {
        val segments = listOf(
            seg(10, 20, Phase.CONNECTED),
            seg(20, 30, Phase.DIRECT),
            seg(30, 40, Phase.DIRECT), // merges with the previous one
            seg(50, 60, Phase.CONNECTED),
        )
        val b = bands(segments, 0, 100)
        assertEquals(
            listOf(Band(0, 10, BandKind.OFF), Band(20, 40, BandKind.DIRECT), Band(40, 50, BandKind.OFF), Band(60, 100, BandKind.OFF)),
            b,
        )
    }

    @Test
    fun noDataMeansOffForTheWholePeriod() {
        assertEquals(listOf(Band(0, 100, BandKind.OFF)), bands(emptyList(), 0, 100))
    }
}
