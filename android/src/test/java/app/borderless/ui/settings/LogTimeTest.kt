package app.borderless.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** The event log's "today means no date prefix" boundary — F-LOG-0060-1: the leftover milliseconds of 'now'
 * used to push the boundary up to a second into the day and mark its first sub-second as yesterday. */
class LogTimeTest {

    /** Local midnight of the test's day plus [ms]. */
    private fun midnightPlus(ms: Long): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis + ms

    @Test
    fun justAfterMidnightKeepsTodaysPrefix() {
        // 'now' is 900 ms into the day: the old boundary would sit at 00:00:00.900.
        val now = midnightPlus(900)
        val event = midnightPlus(500)
        assertEquals(SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(event)), formatTime(event, now))
    }

    @Test
    fun justBeforeMidnightGetsTheDate() {
        val now = midnightPlus(900)
        val event = midnightPlus(-1)
        assertTrue(formatTime(event, now).matches(Regex("""\d\d\.\d\d \d\d:\d\d:\d\d""")))
    }

    @Test
    fun exactlyMidnightIsToday() {
        val now = midnightPlus(3_600_000)
        val event = midnightPlus(0)
        assertEquals(SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(event)), formatTime(event, now))
    }
}
