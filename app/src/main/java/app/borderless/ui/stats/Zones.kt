package app.borderless.ui.stats

import android.content.Context
import app.borderless.core.Eco
import app.borderless.data.EcoMath
import app.borderless.data.StatsDb
import app.borderless.data.Strategy

/**
 * What the app was set to over a period, from the statistics' settings log: periods of the battery
 * saving mode (hatched on the charts) and strategy changes (vertical lines with the strategy's name).
 */
internal class Zones(val eco: List<Band>, val strategy: List<Marker>) {
    companion object {
        val NONE = Zones(emptyList(), emptyList())
    }
}

internal suspend fun loadZones(context: Context, from: Long, to: Long): Zones {
    val eco = EcoMath.onIntervals(StatsDb.settingsHistory(Eco.KEY, from, to), from, to).map { (a, b) -> Band(a, b, BandKind.ECO) }
    val markers = ArrayList<Marker>()
    var last: String? = null
    for ((t, v) in StatsDb.settingsHistory("strategy", from, to)) {
        // The log repeats the full snapshot at every app start: only real changes count.
        if (v == last) continue
        val first = last == null
        last = v
        val strategy = runCatching { Strategy.valueOf(v) }.getOrNull() ?: continue
        markers += Marker(maxOf(t, from), context.getString(Eco.label(strategy)), edge = t <= from, line = !first)
    }
    return Zones(eco, markers)
}
