package app.borderless.data

import app.borderless.R
import app.borderless.Res
import java.text.NumberFormat

/**
 * How amounts are written for people, whatever their size: durations switch to larger units ("45 s", "1 min 30 s",
 * "2 h 5 min", "3 d 4 h"), counts get thousands separators and then "M", bytes go up to TB, energy to kJ. Negative
 * or absurd values (damaged data) never produce garbage: they are clamped to zero.
 */
object Units {
    /** String resource and arguments for a duration of [sec] seconds. */
    fun duration(sec: Long): Pair<Int, Array<Any>> {
        val s = sec.coerceAtLeast(0)
        val m = s / 60
        return when {
            s < 60 -> R.string.act_seconds to arrayOf(s.toInt())
            m < 10 && s % 60 != 0L -> R.string.dur_ms to arrayOf(m.toInt(), (s % 60).toInt())
            m < 60 -> R.string.dur_m to arrayOf(m.toInt())
            m < 24 * 60 -> if (m % 60 == 0L) R.string.dur_h_short to arrayOf((m / 60).toInt()) else R.string.dur_hm to arrayOf((m / 60).toInt(), (m % 60).toInt())
            (m % 1440) / 60 == 0L -> R.string.dur_d to arrayOf((m / 1440).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            else -> R.string.dur_dh to arrayOf((m / 1440).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), ((m % 1440) / 60).toInt())
        }
    }

    /** "45 s" … "3 d 4 h". */
    fun durationText(sec: Long): String = duration(sec).let { (id, args) -> Res.s(id, *args) }

    /** The same, stored language-neutral in the event log. */
    fun durationL(sec: Long): LText = duration(sec).let { (id, args) -> LText.of(id, *args) }

    /** Seconds with a decimal while small ("0.4 s", "7.2 s"), then like [durationText]. */
    fun seconds(sec: Double): String {
        val s = if (sec.isNaN() || sec < 0) 0.0 else sec
        return if (s < 10) Res.s(R.string.act_seconds_f, "%.1f".format(s)) else durationText(s.toLong())
    }

    /** "2 865", "123 456", then "1.2 M". */
    fun count(n: Long): String {
        val v = n.coerceAtLeast(0)
        return if (v < 1_000_000) NumberFormat.getIntegerInstance(Res.locale).format(v)
        else Res.s(R.string.count_millions, "%.1f".format(v / 1_000_000.0))
    }

    fun bytes(b: Long): String {
        val v = b.coerceAtLeast(0)
        return when {
            v >= 1L shl 40 -> Res.s(R.string.bytes_tb, v / 1_099_511_627_776.0)
            v >= 1L shl 30 -> Res.s(R.string.bytes_gb, v / 1_073_741_824.0)
            v >= 1L shl 20 -> Res.s(R.string.bytes_mb, v / 1_048_576.0)
            else -> Res.s(R.string.bytes_kb, v / 1024.0)
        }
    }

    /** Energy: "350 mJ", "4.2 J", "12.5 kJ". */
    fun energy(mj: Double): String {
        val v = if (mj.isNaN() || mj < 0) 0.0 else mj
        return when {
            v >= 10_000_000 -> Res.s(R.string.act_kj, "%.1f".format(v / 1_000_000))
            v >= 1000 -> Res.s(R.string.act_j, "%.1f".format(v / 1000))
            else -> Res.s(R.string.act_mj, v.toInt())
        }
    }
}
