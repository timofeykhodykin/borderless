package app.borderless.data

import app.borderless.S
import app.borderless.core.GeoError
import app.borderless.net.HttpStatus
import app.borderless.net.NetError
import app.borderless.net.TooBig
import app.borderless.platform.Lock
import app.borderless.platform.dateTime
import app.borderless.platform.nowMs
import app.borderless.platform.text
import app.borderless.platform.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.math.abs

/**
 * Last line of defence. Failures the code can deal with are handled where they happen; anything
 * unexpected ends up here: it is logged (event + full trace in the detailed log) and queued for a
 * dialog with a user-level description and the full text to copy ([pending], shown by the app).
 *
 * Background coroutines use [handler]; a crash of the whole process is the platform's to catch (Android:
 * `AndroidErrors`, which writes it to a file and shows it on the next start).
 *
 * Failures that point at a bug in the app (not the network, not a missing app to share with) are also kept
 * for reporting ([records], stored like the logs): crashes, "not responding", native crashes of the core and
 * unexpected exceptions. [unreported] counts the different ones since the last statistics export, which carries
 * them ([export]); they stay until the logs are cleared.
 */
object Errors {
    private const val TAG = "Errors"
    private const val MAX_PENDING = 5
    /** The same failure again within this time is logged but not shown again. */
    private const val REPEAT_MS = 60_000L

    /** The records' file in the logs folder ([AppLog.Files]). */
    const val FILE = "errors.log"

    /** What only the platform knows about failures. */
    interface Platform {
        /** Version and device lines on top of every report. */
        fun header(): String

        /** The resource name of a user-level description of [root] ([S.err_network]…), null = none of the platform's. */
        fun reason(root: Throwable): String?

        /** [root] is the network's or the device's doing (no connection, timeout, full storage, no app to hand to): not a bug. */
        fun expected(root: Throwable): Boolean

        /** The first frame of our own code in [root]'s trace ("Class.method:line"), for telling bugs apart. */
        fun frame(root: Throwable): String?

        /** Also tell the platform's log (Android: logcat in debug builds). */
        fun warn(where: String, e: Throwable) {}

        /** When the kept failures were last exported (a small file of the platform's). */
        var reportedAt: Long
    }

    var platform: Platform? = null

    /**
     * One error to show: a user-level [message] and the full [details] for copying. [bug]: it points at a bug in the
     * app (it is kept for reporting too, see [isBug]) — the dialog asks the user to report it on the project's page.
     */
    data class Report(val id: Long, val message: String, val details: String, val crash: Boolean = false, val bug: Boolean = false)

    private val ids = AtomicLong(0L)
    private val _pending = MutableStateFlow<List<Report>>(emptyList())
    val pending: StateFlow<List<Report>> = _pending.asStateFlow()
    private val lastShown = HashMap<String, Long>()
    private val lock = Lock()

    /** A failure kept for reporting; [key] tells repeats of the same failure apart from different ones. */
    data class Record(val time: Long, val key: String, val details: String)

    private const val MAX_RECORDS = 300
    /** The same failure is kept again only after this long (a failure in a loop must not flood the file). */
    private const val RECORD_REPEAT_MS = 10 * 60_000L

    private val _records = MutableStateFlow<List<Record>>(emptyList())
    val records: StateFlow<List<Record>> = _records.asStateFlow()
    private val _reportedAt = MutableStateFlow(0L)

    /** Different failures kept since the last export. */
    val unreported: kotlinx.coroutines.flow.Flow<Int> =
        kotlinx.coroutines.flow.combine(_records, _reportedAt) { list, at -> list.filter { it.time > at }.distinctBy { it.key }.size }

    /** For coroutine scopes: an uncaught exception is reported instead of crashing the app. */
    val handler = CoroutineExceptionHandler { _, e -> report(e, "coroutine") }

    /** Once the logs are there ([AppLog.init]): reads back what was kept. */
    fun init(platform: Platform) {
        this.platform = platform
        runCatching {
            // Plus anything kept before this point (start-up steps that failed before the logs were there).
            _records.value = (AppLog.read(FILE).mapNotNull(::parseRecord) + _records.value).sortedBy { it.time }.takeLast(MAX_RECORDS)
            _reportedAt.value = platform.reportedAt
        }
    }

    /**
     * Logs [e] and, unless it is a repeat or [show] is false, queues a dialog. [where] says what the
     * app was doing (for the details); [userText] replaces the generic description.
     */
    fun report(e: Throwable, where: String, userText: String? = null, show: Boolean = true) {
        if (e is CancellationException) return
        runCatching {
            platform?.warn(where, e)
            AppLog.debug(TAG, Scrub.secrets("$where: ${e::class.qualifiedName}: ${e.message}\n${e.stackTraceToString()}"))
            val message = userText ?: describe(e)
            AppLog.event(LText.of(S.err_event, message), AppLog.Kind.ERROR)
            // A bug goes to "Errors to report" and the dialog offers to report it on the project's page.
            val bug = isBug(e)
            if (bug) keep(nowMs(), signature(e, where), details(e, where))
            if (!show) return
            val key = "$where|${e::class.qualifiedName}|${e.message}"
            val now = nowMs()
            lock.withLock {
                if ((lastShown[key] ?: 0L) > now - REPEAT_MS) return
                lastShown[key] = now
            }
            show(message, details(e, where), bug = bug)
        }
    }

    /** Queues a dialog (crashes of an earlier run come this way from the platform). */
    fun show(message: String, details: String, crash: Boolean = false, bug: Boolean = false) =
        _pending.update { (it + Report(ids.incrementAndFetch(), message, details, crash, bug)).takeLast(MAX_PENDING) }

    fun dismiss(id: Long) = _pending.update { list -> list.filterNot { it.id == id } }

    /** Whether [e] points at a bug in the app rather than at the network or the device. */
    fun isBug(e: Throwable): Boolean {
        // The network's doing on every platform (the shared code's own failures of requests, answers and lists).
        if (generateSequence(e) { it.cause }.any { it is NetError || it is TooBig || it is HttpStatus || it is GeoError }) return false
        return platform?.expected(root(e)) != true
    }

    private fun root(e: Throwable) = generateSequence(e) { it.cause }.last()

    /** Where it failed and how: the same bug hit again has the same signature. */
    private fun signature(e: Throwable, where: String): String {
        val root = root(e)
        return "$where|${root::class.qualifiedName}|${platform?.frame(root)}"
    }

    /** Keeps a failure for reporting (not again if the same one was kept within [RECORD_REPEAT_MS]). */
    fun keep(time: Long, key: String, details: String) = lock.withLock {
        val list = _records.value
        if (list.any { it.key == key && abs(it.time - time) < RECORD_REPEAT_MS }) return@withLock
        val r = Record(time, key, details)
        _records.value = (list + r).sortedBy { it.time }.takeLast(MAX_RECORDS)
        AppLog.append(FILE, recordLine(r))
    }

    /** The statistics export carried the failures kept so far: the count starts again (they stay kept). */
    fun markReported(at: Long = nowMs()) {
        _reportedAt.value = at
        runCatching { platform?.reportedAt = at }
    }

    /** Forgets every failure kept (together with the logs, [AppLog.clear]). */
    fun clear() {
        lock.withLock { _records.value = emptyList() }
        markReported()
    }

    /** All failures kept, newest first, for the statistics export. */
    fun export(): String = buildString {
        val list = _records.value
        val at = _reportedAt.value
        appendLine("${list.size} failure(s) kept, ${list.count { it.time > at }} since the previous export; repeats within ${RECORD_REPEAT_MS / 60_000} min are kept once.")
        list.groupBy { it.key }.values.sortedByDescending { it.last().time }.forEach { same ->
            val last = same.last()
            appendLine()
            appendLine("=".repeat(60))
            appendLine("${same.size} time(s), last ${dateTime(last.time)}" +
                (if (same.size > 1) ", first ${dateTime(same.first().time)}" else "") + if (last.time > at) " [new]" else "")
            appendLine(last.details.trimEnd())
        }
    }

    private fun recordLine(r: Record): String = kotlinx.serialization.json.buildJsonObject {
        put("t", kotlinx.serialization.json.JsonPrimitive(r.time))
        put("k", kotlinx.serialization.json.JsonPrimitive(r.key))
        put("d", kotlinx.serialization.json.JsonPrimitive(r.details))
    }.toString()

    private fun parseRecord(line: String): Record? = runCatching {
        val o = kotlinx.serialization.json.Json.parseToJsonElement(line) as kotlinx.serialization.json.JsonObject
        fun str(k: String) = (o[k] as kotlinx.serialization.json.JsonPrimitive).content
        Record(str("t").toLong(), str("k"), str("d"))
    }.getOrNull()

    /** Runs [block]; on failure reports it and returns [default]. */
    inline fun <T> guard(where: String, default: T, show: Boolean = true, block: () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        report(e, where, show = show)
        default
    }

    /** What went wrong, in user terms. */
    fun describe(e: Throwable): String {
        val root = root(e)
        val net = generateSequence(e) { it.cause }.firstOrNull { it is NetError || it is TooBig || it is HttpStatus }
        val name = platform?.reason(root) ?: when {
            net is NetError && net.kind == NetError.Kind.TLS -> S.err_tls
            net is NetError && net.kind == NetError.Kind.UNREACHABLE -> S.err_timeout
            net != null -> S.err_network
            root is SerializationException -> S.err_format
            else -> S.err_unexpected
        }
        val reason = root.message?.let(Scrub::secrets)?.lines()?.firstOrNull()?.take(160)
        return text(name) + if (reason.isNullOrBlank()) "" else "\n\n" + text(S.err_reason, reason)
    }

    /** Everything needed to understand the failure, for copying into a bug report. */
    fun details(e: Throwable, where: String): String = header(where) + "\n" + Scrub.secrets(e.stackTraceToString())

    /** The report's header: version, device, time, place. */
    fun header(where: String): String = buildString {
        platform?.header()?.let { appendLine(it.trimEnd()) }
        appendLine("Time: ${dateTime(nowMs())}")
        appendLine("Where: $where")
    }
}

/**
 * Launches work from the UI: a failure is reported (dialog) instead of crashing the screen's scope.
 * [finally] runs either way (e.g. to clear a "busy" flag).
 */
fun kotlinx.coroutines.CoroutineScope.launchSafe(
    where: String,
    finally: () -> Unit = {},
    block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit,
) = launch {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Errors.report(e, where)
    } finally {
        finally()
    }
}
