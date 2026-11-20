package app.borderless.data

import app.borderless.core.Phase

// Rows of the statistics database that the shared math reads. `StatsDb` (Android) stores them and keeps these names
// as `StatsDb.Sample`, `StatsDb.Aggregate`… (type aliases).

/** One probe or health check of a server: its ping, or null when it gave no reply. */
data class Sample(val ts: Long, val server: String, val ms: Int?)

/** A phase change or heartbeat; [run] = the process that wrote it (stats DB v3). */
data class PhaseRow(val ts: Long, val phase: Phase, val server: String?, val run: Long? = null)

/** A scan's summary: how many answered of how many were checked, the best ping, the servers the user had. */
data class ScanRow(val ts: Long, val available: Int, val total: Int, val best: Int?, val servers: Int? = null)

data class Aggregate(
    val count: Int, val ok: Int, val avg: Int?, val min: Int?, val max: Int?,
    val share: Double? = null, val slices: Int = 0,
) {
    val availability: Double? get() = share ?: if (count == 0) null else ok.toDouble() / count

    /** How much this server was checked, in time slices (plain checks when no slices are known). */
    val weight: Double get() = if (slices > 0) slices.toDouble() else count.toDouble()

    /** The answered part of [weight]: smoothed shares sum these over servers, never raw checks. */
    val answered: Double get() = (availability ?: 0.0) * weight
}

data class PoolStat(
    val shortCount: Double, val shortOk: Double,
    val longCount: Double, val longOk: Double,
    val avgMs: Double?, val lastOk: Long?,
)
