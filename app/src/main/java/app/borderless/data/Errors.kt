package app.borderless.data

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ActivityNotFoundException
import android.content.Context
import android.database.sqlite.SQLiteException
import android.os.Build
import android.util.Log
import app.borderless.BuildConfig
import app.borderless.R
import app.borderless.Res
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import java.io.File
import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLException

/**
 * Last line of defence. Failures the code can deal with are handled where they happen; anything
 * unexpected ends up here: it is logged (event + full trace in the detailed log) and queued for a
 * dialog with a user-level description and the full text to copy ([pending], shown by MainActivity).
 *
 * Background coroutines use [handler]; a crash of the whole process is written to a file by the
 * default uncaught-exception handler and shown on the next start ([install]).
 */
object Errors {
    private const val TAG = "Errors"
    private const val MAX_PENDING = 5
    /** The same failure again within this time is logged but not shown again. */
    private const val REPEAT_MS = 60_000L

    /** One error to show: a user-level [message] and the full [details] for copying. */
    data class Report(val id: Long, val message: String, val details: String, val crash: Boolean = false)

    private val ids = AtomicLong()
    private val _pending = MutableStateFlow<List<Report>>(emptyList())
    val pending: StateFlow<List<Report>> = _pending.asStateFlow()
    private val lastShown = HashMap<String, Long>()
    private var crashFile: File? = null

    /** For coroutine scopes: an uncaught exception is reported instead of crashing the app. */
    val handler = CoroutineExceptionHandler { _, e -> report(e, "coroutine") }

    /** Writes crashes to a file (shown on the next start) before the system's handler ends the process. */
    fun install(context: Context) {
        crashFile = File(context.filesDir, "crash.txt")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                // The log is written asynchronously and the process is about to end: the trace goes to a file
                // first and into the detailed log on the next start.
                val text = details(e, "crash in thread ${thread.name}")
                crashFile?.writeText(text)
            }
            previous?.uncaughtException(thread, e)
        }
        // The previous run crashed: tell the user now, and keep the full trace in the detailed log so a
        // shared log is enough to debug it.
        runCatching {
            val f = crashFile ?: return@runCatching
            if (f.exists()) {
                val text = f.readText()
                f.delete()
                AppLog.event(LText.of(R.string.ev_crashed), AppLog.Kind.ERROR)
                AppLog.debug(TAG, "the previous run crashed:\n$text")
                enqueue(Report(ids.incrementAndGet(), Res.s(R.string.err_crashed), text, crash = true))
            }
        }
        Thread({ runCatching { logExits(context) } }, "exit-reasons").start()
    }

    /**
     * How earlier runs of the process ended, as the system recorded it (Android 11+): native crashes of the
     * core, "not responding" (with the system's thread dump), being killed for memory or by the user.
     * Each is written to the detailed log once; Java crashes come with their own trace above.
     */
    private fun logExits(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val seenFile = File(context.filesDir, "exits_seen")
        val seen = seenFile.takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull()
        val am = context.getSystemService(ActivityManager::class.java) ?: return
        val exits = am.getHistoricalProcessExitReasons(null, 0, 16).sortedBy { it.timestamp }
        exits.lastOrNull()?.let { seenFile.writeText(it.timestamp.toString()) }
        // The first run of this version only marks what is there: older exits were never reported before.
        if (seen == null) return
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        for (e in exits.filter { it.timestamp > seen }) {
            val line = "earlier run ended ${fmt.format(Date(e.timestamp))}: ${exitReason(e.reason)}" +
                (e.description?.let { " ($it)" } ?: "") + ", importance ${e.importance}, memory ${e.pss / 1024} MB"
            val trace = if (e.reason == ApplicationExitInfo.REASON_ANR) {
                runCatching { e.traceInputStream?.bufferedReader()?.use { r -> r.lineSequence().take(400).joinToString("\n") } }.getOrNull()
            } else null
            AppLog.debug(TAG, if (trace != null) "$line\n$trace" else line)
            if (e.reason == ApplicationExitInfo.REASON_CRASH_NATIVE || e.reason == ApplicationExitInfo.REASON_ANR) {
                AppLog.event(LText.of(R.string.ev_crashed), AppLog.Kind.ERROR)
            }
        }
    }

    private fun exitReason(r: Int): String = when (r) {
        ApplicationExitInfo.REASON_CRASH -> "crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash (core)"
        ApplicationExitInfo.REASON_ANR -> "not responding (ANR)"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "killed: low memory"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "killed: excessive resource use"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "stopped by the user"
        ApplicationExitInfo.REASON_USER_STOPPED -> "user stopped"
        ApplicationExitInfo.REASON_SIGNALED -> "killed by a signal"
        ApplicationExitInfo.REASON_EXIT_SELF -> "exited"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "initialization failure"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission changed"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "a dependency died"
        ApplicationExitInfo.REASON_OTHER -> "other (system)"
        14 -> "frozen by the system"
        15 -> "app state changed (e.g. disabled)"
        16 -> "app updated"
        else -> "reason $r"
    }

    /**
     * Logs [e] and, unless it is a repeat or [show] is false, queues a dialog. [where] says what the
     * app was doing (for the details); [userText] replaces the generic description.
     */
    fun report(e: Throwable, where: String, userText: String? = null, show: Boolean = true) {
        if (e is CancellationException) return
        runCatching {
            Log.w(TAG, where, e)
            AppLog.debug(TAG, "$where: ${e.javaClass.name}: ${e.message}\n${trace(e)}")
            val message = userText ?: describe(e)
            AppLog.event(LText.of(R.string.err_event, message), AppLog.Kind.ERROR)
            if (!show) return
            val key = "$where|${e.javaClass.name}|${e.message}"
            val now = System.currentTimeMillis()
            synchronized(lastShown) {
                if ((lastShown[key] ?: 0L) > now - REPEAT_MS) return
                lastShown[key] = now
            }
            enqueue(Report(ids.incrementAndGet(), message, details(e, where)))
        }
    }

    fun dismiss(id: Long) = _pending.update { list -> list.filterNot { it.id == id } }

    private fun enqueue(r: Report) = _pending.update { (it + r).takeLast(MAX_PENDING) }

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
        val root = generateSequence(e) { it.cause }.last()
        val id = when (root) {
            is UnknownHostException, is ConnectException -> R.string.err_network
            is SocketTimeoutException -> R.string.err_timeout
            is SSLException -> R.string.err_tls
            is SQLiteException -> R.string.err_database
            is SerializationException -> R.string.err_format
            is SecurityException -> R.string.err_permission
            is ActivityNotFoundException -> R.string.err_no_app
            is OutOfMemoryError -> R.string.err_memory
            is IOException -> R.string.err_io
            else -> R.string.err_unexpected
        }
        val reason = root.message?.lines()?.firstOrNull()?.take(160)
        return Res.s(id) + if (reason.isNullOrBlank()) "" else "\n\n" + Res.s(R.string.err_reason, reason)
    }

    /** Everything needed to understand the failure, for copying into a bug report. */
    fun details(e: Throwable, where: String): String = buildString {
        appendLine("Border(less) ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
        appendLine("Where: $where")
        appendLine()
        append(trace(e))
    }

    private fun trace(e: Throwable): String = StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString()
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
