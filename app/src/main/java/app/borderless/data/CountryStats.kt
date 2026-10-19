package app.borderless.data

/** What the list of countries can be sorted by (pure, tested). */
object CountryStats {
    /**
     * How many times each country was chosen, from the history of the "country" setting (settings_log: the chosen
     * codes joined by ",", oldest first). A country counts once each time it joins the chosen set.
     */
    fun timesChosen(values: List<String>): Map<String, Int> {
        val out = HashMap<String, Int>()
        var before = emptySet<String>()
        for (v in values) {
            val now = v.split(',').map(String::trim).filter(String::isNotEmpty).toSet()
            (now - before).forEach { out[it] = (out[it] ?: 0) + 1 }
            before = now
        }
        return out
    }

    /**
     * How reliably each country's servers answer: answered time slices of all its servers over all of them (by time, see
     * [StatsMath.availability]), smoothed
     * (one success and one failure added, so a country checked twice doesn't top one checked a thousand times).
     * Countries without checks are left out.
     */
    fun stability(servers: List<Server>, aggregates: Map<String, StatsDb.Aggregate>): Map<String, Double> =
        servers.groupBy(Countries::of).mapNotNull { (code, list) ->
            val count = list.sumOf { aggregates[it.id]?.weight ?: 0.0 }
            val ok = list.sumOf { aggregates[it.id]?.answered ?: 0.0 }
            if (count == 0.0) null else code to (ok + 1.0) / (count + 2.0)
        }.toMap()
}
