package app.borderless.data

/** Pure rules of subscriptions (unit-tested): update schedule and recognising servers across updates. */
object SubMath {
    /** The provider's own interval is followed, but never more often than this. */
    const val MIN_PROVIDER_HOURS = 3

    /**
     * After [failures] failed scheduled updates in a row, the next scheduled try waits this long: 15 min, then
     * 30, 60, 120, at most 4 h. A failed try costs a direct attempt plus downloads through servers (10–40 s with
     * throwaway cores); the 2026-10-08 phone export had one failing subscription retried hourly all night and on
     * every app open. Updating by hand never waits.
     */
    fun retryDelayMs(failures: Int): Long =
        if (failures <= 0) 0L else minOf(15 * 60_000L shl minOf(failures - 1, 5), 4 * 3_600_000L)

    /**
     * Hours between automatic updates of a subscription: the provider's interval (`profile-update-interval`,
     * at least [MIN_PROVIDER_HOURS]) if it gives one, else the user's setting; 0 (manual only) if the user
     * turned automatic updates off. Doubled in the battery saving mode.
     */
    fun intervalHours(userHours: Int, providerHours: Int?, eco: Boolean): Int {
        if (userHours <= 0) return 0
        val base = providerHours?.takeIf { it > 0 }?.coerceAtLeast(MIN_PROVIDER_HOURS) ?: userHours
        return base * (if (eco) EcoMath.SUB_FACTOR else 1)
    }

    /** Whether [sub] should be updated now; on mobile data in the battery saving mode only when twice overdue. */
    fun due(sub: Subscription, now: Long, userHours: Int, eco: Boolean, metered: Boolean): Boolean {
        val hours = intervalHours(userHours, sub.providerHours, eco)
        if (hours == 0) return false
        val wait = hours * 3_600_000L * (if (eco && metered) 2 else 1)
        return now - sub.updatedAt > wait
    }

    /** `profile-update-interval` header: whole hours (some panels send "12" or "12.0"). */
    fun parseInterval(raw: String?): Int? = raw?.trim()?.toDoubleOrNull()?.takeIf { it > 0 && it < 24 * 365 }?.let { Math.round(it).toInt().coerceAtLeast(1) }

    /**
     * Servers of a fresh download that are old ones with changed parameters (new key, port, path…): a
     * server's id comes from its link, so any change makes a new id. Unmatched fresh servers are paired
     * with unmatched previous ones by, in this order: the same name and address (host, port, protocol);
     * the same name, if it is unique on both sides; the same address, if unique on both sides.
     * Returns fresh id → previous id.
     */
    fun matchChanged(previous: List<Server>, fresh: List<Server>): Map<String, String> {
        val freshIds = fresh.map { it.id }.toSet()
        val prevIds = previous.map { it.id }.toSet()
        val leftPrev = previous.filter { it.id !in freshIds }.toMutableList()
        val leftFresh = fresh.filter { it.id !in prevIds }.toMutableList()
        val out = LinkedHashMap<String, String>()
        fun address(s: Server) = "${s.protocol}|${s.host.lowercase()}|${s.port}"
        fun pair(key: (Server) -> String, uniqueOnly: Boolean) {
            val prevBy = leftPrev.groupBy(key)
            val freshBy = leftFresh.groupBy(key)
            for ((k, fs) in freshBy) {
                val ps = prevBy[k] ?: continue
                if (uniqueOnly && (fs.size != 1 || ps.size != 1)) continue
                fs.zip(ps).forEach { (f, p) ->
                    out[f.id] = p.id
                    leftFresh.remove(f)
                    leftPrev.remove(p)
                }
            }
        }
        pair({ it.name + "|" + address(it) }, uniqueOnly = false)
        pair({ it.name }, uniqueOnly = true)
        pair(::address, uniqueOnly = true)
        return out
    }
}
