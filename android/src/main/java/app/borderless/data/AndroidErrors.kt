package app.borderless.data

import app.borderless.S
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
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.net.ssl.SSLException

/**
 * The Android side of [Errors]: a crash of the whole process is written to a file by the default uncaught-exception
 * handler and shown on the next start; how earlier runs ended (native crashes, "not responding") comes from the
 * system; exceptions are described by their JVM / Android class.
 */
object AndroidErrors {
    private const val TAG = "Errors"
    private var crashFile: File? = null

    /** Writes crashes to a file (shown on the next start) before the system's handler ends the process. */
    fun install(context: Context) {
        crashFile = File(context.filesDir, "crash.txt")
        val reported = AndroidLog.folder?.let { File(it, "errors_reported") }
        Errors.init(object : Errors.Platform {
            override fun header(): String =
                "Border(less) ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
                    "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}"

            override fun reason(root: Throwable): String? = when (root) {
                is UnknownHostException, is ConnectException -> "err_network"
                is SocketTimeoutException -> "err_timeout"
                is SSLException -> "err_tls"
                is SQLiteException -> "err_database"
                is SecurityException -> "err_permission"
                is ActivityNotFoundException -> "err_no_app"
                is OutOfMemoryError -> "err_memory"
                is IOException -> "err_io"
                else -> null
            }

            override fun expected(root: Throwable): Boolean = root is IOException || root is ActivityNotFoundException

            override fun frame(root: Throwable): String? =
                (root.stackTrace.firstOrNull { it.className.startsWith("app.borderless") } ?: root.stackTrace.firstOrNull())
                    ?.let { "${it.className}.${it.methodName}:${it.lineNumber}" }

            override fun warn(where: String, e: Throwable) {
                if (BuildConfig.DEBUG) Log.w(TAG, where, e)
            }

            override var reportedAt: Long
                get() = reported?.takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull() ?: 0L
                set(value) { reported?.writeText(value.toString()) }
        })
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                // The log is written asynchronously and the process is about to end: the trace goes to a file
                // first and into the detailed log on the next start.
                val text = Errors.details(e, "crash in thread ${thread.name}")
                // Encrypted like everything else (a trace may name a server); older plain files still read.
                crashFile?.writeText(Crypto.key?.let { k -> Seal.sealLine(k, text, "crash.txt") } ?: text)
            }
            previous?.uncaughtException(thread, e)
        }
        // The previous run crashed: tell the user now, and keep the full trace in the detailed log so a
        // shared log is enough to debug it.
        runCatching {
            val f = crashFile ?: return@runCatching
            if (f.exists()) {
                val text = Seal.openLine(Crypto.key, f.readText().trim(), "crash.txt") ?: "(unreadable crash report)"
                val at = f.lastModified()
                f.delete()
                AppLog.event(LText.of(S.ev_crashed), AppLog.Kind.ERROR)
                AppLog.debug(TAG, "the previous run crashed:\n$text")
                Errors.keep(at, "crash|" + text.lines().drop(5).take(2).joinToString("|"), text)
                Errors.show(Res.s(R.string.err_crashed), text, crash = true, bug = true)
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
                AppLog.event(LText.of(S.ev_crashed), AppLog.Kind.ERROR)
                // The version that ran then is not known for sure: the header says this one.
                Errors.keep(e.timestamp, "exit|${e.reason}|${e.description.orEmpty().take(80)}", Errors.header("earlier run") + "\n" + (if (trace != null) "$line\n$trace" else line))
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

}
