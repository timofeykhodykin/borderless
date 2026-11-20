package app.borderless.data

import app.borderless.platform.dateTime
import app.borderless.platform.nowMs
import app.borderless.platform.stampTime
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlin.concurrent.Volatile

/**
 * Two logs:
 * - [event]: short human-readable story of what the tunnel does ("Connected to …", "Server not responding…").
 * - [debug]: detailed technical log, including the Xray core's own output.
 *
 * Both are kept in memory for the UI and appended to files ([Files], the platform's: Android seals every line), so
 * they survive restarts and can be shared.
 */
object AppLog {
    enum class Kind { INFO, GOOD, WARN, ERROR }

    data class Event(val time: Long, val kind: Kind, val text: LText)

    /** Where the logs are kept: the files of the logs folder by name, each line stored as the platform does it. */
    interface Files {
        /** The lines of [name] with its rotated part, oldest first; lines that can't be read are left out. */
        fun read(name: String): List<String>

        /** Appends [lines] to [name], rotating it to `<name>.1` when it outgrew [maxBytes]. */
        fun append(name: String, lines: List<String>, maxBytes: Long)

        /** Deletes [name] and its rotated part. */
        fun delete(name: String)

        /** Whether writing is possible now (Android: not while the key is unavailable — nothing in the clear). */
        val writable: Boolean get() = true
    }

    const val EVENTS = "events.log"
    const val DEBUG = "debug.log"

    private const val MAX_EVENTS = 400
    private const val MAX_DEBUG = 3000
    private const val MAX_FILE = 2_000_000L
    private const val MAX_BATCH = 200

    /** Debug lines also go here (Android: logcat in debug builds only). */
    var mirror: ((tag: String, message: String) -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> mirror?.invoke("AppLog", "log write failed: $e") })
    private val writes = Channel<Pair<String, String>>(Channel.UNLIMITED)
    private var files: Files? = null

    private val _events = MutableStateFlow<List<Event>>(emptyList())
    val events: StateFlow<List<Event>> = _events.asStateFlow()

    private val _debug = MutableStateFlow<List<String>>(emptyList())
    val debugLines: StateFlow<List<String>> = _debug.asStateFlow()

    /** Once at start: reads both logs back and starts writing. */
    fun init(store: Files) {
        if (files != null) return
        files = store
        _events.value = store.read(EVENTS).takeLast(MAX_EVENTS).mapNotNull(::parseEvent)
        _debug.value = store.read(DEBUG).takeLast(MAX_DEBUG) + _debug.value
        scope.launch {
            for (first in writes) {
                // Whatever queued up meanwhile goes out with it: one append per file instead of one per line
                // (a burst of core output or a scan's probe lines is a single write).
                val batch = ArrayList<Pair<String, String>>().apply { add(first) }
                while (batch.size < MAX_BATCH) batch += writes.tryReceive().getOrNull() ?: break
                if (!store.writable) continue
                for ((name, lines) in batch.groupBy({ it.first }, { it.second })) runCatching { store.append(name, lines, MAX_FILE) }
            }
        }
    }

    /** Appends [line] to the logs folder's [name] (stored like the logs): for [Errors]' own file. */
    internal fun append(name: String, line: String) {
        writes.trySend(name to line)
    }

    /** The lines of a file written by [append] (with its rotated part). */
    internal fun read(name: String): List<String> = files?.read(name).orEmpty()

    /** A user-facing event, stored language-neutral; the detailed log gets it as written now. */
    fun event(text: LText, kind: Kind = Kind.INFO) {
        val e = Event(nowMs(), kind, text)
        _events.update { (it + e).takeLast(MAX_EVENTS) }
        if (files != null) writes.trySend(EVENTS to "${e.time}|${e.kind}|${text.toJson()}")
        debug("event", text.render())
    }

    fun debug(tag: String, text: String, error: Throwable? = null) {
        val msg = if (error != null) "$text: ${error::class.simpleName}: ${error.message}" else text
        mirror?.invoke(tag, msg)
        raw("${stampTime(nowMs())} [$tag] $msg")
    }

    /** A line exactly as given (the core's own output, already stamped by the caller). */
    fun raw(line: String) {
        _debug.update { (it + line).takeLast(MAX_DEBUG) }
        if (files != null) writes.trySend(DEBUG to line)
    }

    /** The last [clear]'s delete job ([awaitCleared] joins it — the Mac's service answers after the delete). */
    @Volatile
    private var clearJob: Job? = null

    /** Deletes both logs and the errors kept for reporting ([Errors.clear]). */
    fun clear() {
        _events.value = emptyList()
        _debug.value = emptyList()
        Errors.clear()
        clearJob = scope.launch { listOf(EVENTS, DEBUG, Errors.FILE).forEach { files?.delete(it) } }
    }

    /** Suspends until the last [clear]'s file delete ran (the async delete would otherwise trail the reply). */
    suspend fun awaitCleared() {
        clearJob?.join()
    }

    /** Full detailed log as text, with a header useful for bug reports. */
    fun exportDebug(header: String): String = buildString {
        appendLine(header)
        appendLine("exported ${dateTime(nowMs())}")
        appendLine()
        files?.read(DEBUG)?.forEach { appendLine(it) }
    }

    fun exportEvents(): String = _events.value.joinToString("\n") { "${dateTime(it.time)}  ${it.text.render()}" }

    private fun parseEvent(line: String): Event? {
        val parts = line.split('|', limit = 3)
        if (parts.size != 3) return null
        val t = parts[0].toLongOrNull() ?: return null
        val k = runCatching { Kind.valueOf(parts[1]) }.getOrDefault(Kind.INFO)
        val text = runCatching { LText.fromJson(Json.parseToJsonElement(parts[2])) }.getOrNull() ?: return null
        return Event(t, k, text)
    }
}
