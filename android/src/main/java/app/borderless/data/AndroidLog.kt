package app.borderless.data

import android.content.Context
import android.system.Os
import android.util.Log
import app.borderless.BuildConfig
import app.borderless.platform.nowMs
import app.borderless.platform.stampTime
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream

/**
 * The Android side of [AppLog]: the logs folder in the app's files, every line sealed (see [Crypto]); debug builds
 * copy debug lines to logcat; Xray's stdout is captured into the detailed log.
 */
object AndroidLog {
    private var dir: File? = null

    /** The logs folder (for the statistics export and the crash file's neighbours). */
    val folder: File? get() = dir

    @Synchronized
    fun init(context: Context) {
        if (dir != null) return
        val d = File(context.filesDir, "logs").apply { mkdirs() }
        dir = d
        // Logcat is a plain-text system log: only debug builds copy lines there.
        if (BuildConfig.DEBUG) AppLog.mirror = { tag, msg -> Log.d("borderless/$tag", msg) }
        AppLog.init(object : AppLog.Files {
            override val writable: Boolean get() = !Crypto.locked

            override fun read(name: String): List<String> = readLines(File(d, "$name.1"), name) + readLines(File(d, name), name)

            override fun append(name: String, lines: List<String>, maxBytes: Long) {
                val file = File(d, name)
                if (file.length() > maxBytes) {
                    val old = File(file.path + ".1")
                    old.delete()
                    file.renameTo(old)
                }
                // Each line is encrypted on its own, so the log can still be appended to.
                Energy.track(Activity.Kind.LOG_WRITE) {
                    val key = Crypto.key
                    file.appendText(lines.joinToString("") { line -> (key?.let { k -> Seal.sealLine(k, line, file.name) } ?: line) + "\n" })
                }
            }

            override fun delete(name: String) {
                File(d, name).delete()
                File(d, "$name.1").delete()
            }
        })
    }

    /** Lines of a log file, decrypted; lines that fail to decrypt (other key, damaged) are skipped. */
    private fun readLines(file: File, label: String): List<String> =
        if (!file.exists()) emptyList()
        else runCatching { file.readLines().mapNotNull { Seal.openLine(Crypto.key, it, label) } }.getOrDefault(emptyList())

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
                    if (line.isNotBlank()) AppLog.raw("${stampTime(nowMs())} [xray] ${stripXrayTimestamp(line)}")
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
