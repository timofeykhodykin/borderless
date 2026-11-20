package app.borderless.data

import app.borderless.S
import kotlin.concurrent.Volatile

/** What the app itself does, by kind. [wake]: usually wakes the radio by itself (not riding along with the user's own
 * traffic). [label]: its text's name. */
enum class WorkKind(val label: String, val group: WorkGroup, val wake: Boolean = false) {
    PING_RIDE(S.act_ping_ride, WorkGroup.PING),
    PING_PERIODIC(S.act_ping_periodic, WorkGroup.PING, wake = true),
    PING_STALL(S.act_ping_stall, WorkGroup.PING),
    PING_VERIFY(S.act_ping_verify, WorkGroup.PING),
    PING_FAILED(S.act_ping_failed, WorkGroup.PING),
    PING_WAKE(S.act_ping_wake, WorkGroup.PING),
    SCAN_BACKGROUND(S.act_scan_background, WorkGroup.SCAN, wake = true),
    SCAN_OPEN(S.act_scan_open, WorkGroup.SCAN, wake = true),
    SCAN_MANUAL(S.act_scan_manual, WorkGroup.SCAN),
    SCAN_ENGINE(S.act_scan_engine, WorkGroup.SCAN),
    /** Fresh pings to choose a server by a strategy the user just picked. */
    SCAN_STRATEGY(S.act_scan_strategy, WorkGroup.SCAN),
    /** Servers a subscription update added, checked at once. */
    SCAN_NEW(S.act_scan_new, WorkGroup.SCAN),
    RESCAN_SKIPPED(S.act_rescan_skipped, WorkGroup.SAVED),
    PROBE(S.act_probe, WorkGroup.PROBE),
    PROBE_EXPLORE(S.act_probe_explore, WorkGroup.PROBE),
    PROBE_RESCUE(S.act_probe_rescue, WorkGroup.PROBE),
    SUB_UPDATE(S.act_sub_update, WorkGroup.OTHER, wake = true),
    /** Starting a limited-network server's core program (its own traffic runs in a separate process, see Limited). */
    LIMITED_START(S.act_limited, WorkGroup.OTHER, wake = true),
    NET_CHECK(S.act_net_check, WorkGroup.OTHER),
    CORE_START(S.act_core_start, WorkGroup.LOCAL),
    DB_COMMIT(S.act_db_commit, WorkGroup.LOCAL),
    FILE_WRITE(S.act_file_write, WorkGroup.LOCAL),
    STATS_SAMPLE(S.act_stats_sample, WorkGroup.LOCAL),
    LOG_WRITE(S.act_log_write, WorkGroup.LOCAL),
    CURRENT_METER(S.act_current_meter, WorkGroup.LOCAL),
    /** The rest of the app's CPU time while its screens are closed: Xray processing the user's traffic. */
    CORE_BASE(S.act_core_base, WorkGroup.LOCAL),
    /** The rest of the app's CPU time while it is open: its screens (and the core meanwhile). */
    UI_BASE(S.act_ui_base, WorkGroup.LOCAL),
}

enum class WorkGroup { PING, SCAN, PROBE, OTHER, SAVED, LOCAL }

/**
 * What the app itself does that costs battery (pings, probes, scans, downloads, core starts, writes), as the shared
 * code reports it: [Work.track] around an action, [Work.count] for a plain count. The platform's [Work.Meter] turns
 * that into energy (Android: `Activity` counts, `Energy` estimates from the power profile; the Mac measures).
 */
object Work {
    /** One tracked action; [kind] may change before it ends (a check that turned out to download). */
    open class Span(kind: WorkKind) {
        @Volatile var kind = kind
        /** Request exchanges (the probes of a scan) and bytes transferred: they size the radio time. */
        @Volatile var exchanges = 1
        @Volatile var bytes = 0L
    }

    interface Meter {
        fun count(kind: WorkKind, n: Int, bytes: Long)
        fun begin(kind: WorkKind, exchanges: Int): Span
        fun end(span: Span)

        /** The engine's heartbeat (every few minutes while the tunnel runs): sample the power state, write the counters. */
        fun heartbeat() {}

        /** The user's own traffic is flowing now (the radio is awake anyway). */
        fun userTraffic() {}

        /** CPU time of the whole process so far, -1 if unknown (then the protocols' cost isn't measured). */
        val processCpuMs: Long get() = -1

        /** CPU time charged to tracked actions so far (taken out of [processCpuMs] when measuring the core alone). */
        val attributedCpuMs: Long get() = 0

        /** No window of the app was shown since [t] (its drawing would count as the core's work). */
        fun uiQuietSince(t: Long): Boolean = true
    }

    var meter: Meter? = null

    fun count(kind: WorkKind, n: Int = 1, bytes: Long = 0) {
        meter?.count(kind, n, bytes)
    }

    fun begin(kind: WorkKind, exchanges: Int = 1): Span =
        meter?.begin(kind, exchanges) ?: Span(kind).also { it.exchanges = exchanges }

    fun end(span: Span) {
        meter?.end(span)
    }

    /** Runs [block] as one tracked action of [kind]. */
    inline fun <T> track(kind: WorkKind, exchanges: Int = 1, block: () -> T): T {
        val span = begin(kind, exchanges)
        try {
            return block()
        } finally {
            end(span)
        }
    }
}
