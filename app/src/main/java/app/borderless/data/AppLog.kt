package app.borderless.data

import android.content.Context
import android.system.Os
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.serialization.json.Json

/**
 * Two logs:
 * - [event]: short human-readable story of what the tunnel does ("Connected to …", "Server not responding…").
 * - [debug]: detailed technical log, including the Xray core's own output.
 *
 * Both are kept in memory for the UI and appended to files, so they survive restarts and can be shared.
 */
object AppLog {
    enum class Kind { INFO, GOOD, WARN, ERROR }

    data class Event(val time: Long, val kind: Kind, val text: LText)

    private const val MAX_EVENTS = 400
    private const val MAX_DEBUG = 3000
    private const val MAX_FILE = 2_000_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> Log.w("AppLog", "log write failed", e) })
    private val writes = Channel<Pair<File, String>>(Channel.UNLIMITED)
    private var dir: File? = null

    private val _events = MutableStateFlow<List<Event>>(emptyList())
    val events: StateFlow<List<Event>> = _events.asStateFlow()

    private val _debug = MutableStateFlow<List<String>>(emptyList())
    val debugLines: StateFlow<List<String>> = _debug.asStateFlow()

    private val timeFmt = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    }
    private val dateFmt = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    }

    val eventsFile: File? get() = dir?.let { File(it, "events.log") }
    val debugFile: File? get() = dir?.let { File(it, "debug.log") }

    @Synchronized
    fun init(context: Context) {
        if (dir != null) return
        val d = File(context.filesDir, "logs").apply { mkdirs() }
        dir = d
        _events.value = readLines(File(d, "events.log")).takeLast(MAX_EVENTS).mapNotNull(::parseEvent)
        _debug.value = readLines(File(d, "debug.log")).takeLast(MAX_DEBUG)
        scope.launch {
            for ((file, line) in writes) {
                runCatching {
                    if (file.length() > MAX_FILE) {
                        val old = File(file.path + ".1")
                        old.delete()
                        file.renameTo(old)
                    }
                    // Each line is encrypted on its own (see Crypto), so the log can still be appended to.
                    Energy.track(Activity.Kind.LOG_WRITE) {
                        val stored = Crypto.key?.let { Seal.sealLine(it, line, file.name) } ?: line
                        file.appendText(stored + "\n")
                    }
                }
            }
        }
    }

    /** A user-facing event. Also mirrored into the detailed log. */
    /** A user-facing event, stored language-neutral; the detailed log gets it as written now. */
    fun event(text: LText, kind: Kind = Kind.INFO) {
        val e = Event(System.currentTimeMillis(), kind, text)
        _events.update { (it + e).takeLast(MAX_EVENTS) }
        eventsFile?.let { writes.trySend(it to "${e.time}|${e.kind}|${text.toJson()}") }
        debug("event", text.render())
    }

    fun debug(tag: String, text: String, error: Throwable? = null) {
        val msg = if (error != null) "$text: ${error.javaClass.simpleName}: ${error.message}" else text
        Log.d("borderless/$tag", msg)
        appendDebug("${timeFmt.get()!!.format(Date())} [$tag] $msg")
    }

    fun clear() {
        _events.value = emptyList()
        _debug.value = emptyList()
        scope.launch {
            listOfNotNull(eventsFile, debugFile).forEach { it.delete(); File(it.path + ".1").delete() }
        }
    }

    /** Full detailed log as text, with a header useful for bug reports. */
    fun exportDebug(header: String): String = buildString {
        appendLine(header)
        appendLine("exported ${dateFmt.get()!!.format(Date())}")
        appendLine()
        debugFile?.let { f ->
            (readLines(File(f.path + ".1"), f.name) + readLines(f)).forEach { appendLine(it) }
        }
    }

    fun exportEvents(): String = _events.value.joinToString("\n") {
        "${dateFmt.get()!!.format(Date(it.time))}  ${it.text.render()}"
    }

    private fun appendDebug(line: String) {
        _debug.update { (it + line).takeLast(MAX_DEBUG) }
        debugFile?.let { writes.trySend(it to line) }
    }

    /** Lines of a log file, decrypted; lines that fail to decrypt (other key, damaged) are skipped. */
    private fun readLines(file: File, label: String = file.name): List<String> =
        if (!file.exists()) emptyList()
        else runCatching { file.readLines().mapNotNull { Seal.openLine(Crypto.key, it, label) } }.getOrDefault(emptyList())

    private fun parseEvent(line: String): Event? {
        val parts = line.split('|', limit = 3)
        if (parts.size != 3) return null
        val t = parts[0].toLongOrNull() ?: return null
        val k = runCatching { Kind.valueOf(parts[1]) }.getOrDefault(Kind.INFO)
        val text = runCatching { LText.fromJson(Json.parseToJsonElement(parts[2])) }.getOrNull() ?: return null
        return Event(t, k, text)
    }

    /**
     * Xray writes its log to stdout. Point stdout at a pipe and copy every line into the detailed
     * log. Must run after the Go library is loaded: its init redirects stdout to logcat itself.
     */
    @Synchronized
    fun captureStdout() {
        if (pipe != null) return
        try {
            val fds = Os.pipe().also { pipe = it }
            val (read, write) = fds[0] to fds[1]
            Os.dup2(write, 1)
            Thread({
                FileInputStream(read).bufferedReader().forEachLine { line ->
                    if (line.isNotBlank()) appendDebug("${timeFmt.get()!!.format(Date())} [xray] ${stripXrayTimestamp(line)}")
                }
            }, "xray-log").apply { isDaemon = true }.start()
        } catch (e: Exception) {
            Log.w("borderless", "cannot capture native output", e)
        }
    }

    /** Xray prefixes lines with its own "2026/10/06 00:50:11.064450 "; we already add a time. */
    private fun stripXrayTimestamp(line: String): String =
        line.replace(Regex("^\\d{4}/\\d{2}/\\d{2} \\d{2}:\\d{2}:\\d{2}(\\.\\d+)? "), "")

    /** Held so the pipe ends are never garbage-collected and closed. */
    private var pipe: Array<FileDescriptor>? = null
}
