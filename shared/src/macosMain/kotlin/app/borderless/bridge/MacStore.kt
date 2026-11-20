@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package app.borderless.bridge

import app.borderless.data.AppLog
import app.borderless.data.DataStore
import app.borderless.platform.Files
import app.borderless.platform.Lock
import app.borderless.platform.deleteFile
import app.borderless.platform.fileExists
import app.borderless.platform.keepAside
import app.borderless.platform.listDir
import app.borderless.platform.preserveLegacyFile
import app.borderless.platform.readText
import app.borderless.platform.withLock
import kotlin.concurrent.Volatile
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.posix.chmod
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite

/** Plain JSON in the root-only service folder. Encryption is supported only for recovering older files. */
class MacStore(private val dir: String) : DataStore {
    init { Files.makeDirs(dir); chmod(dir, 0x1C0u) }
    @Volatile private var readFailed = false
    private val ioLock = Lock()
    private val reported = HashSet<String>()

    private fun decode(raw: ByteArray, name: String): ByteArray {
        val plain = MacCrypto.open(raw, name).first
        if (name.endsWith(".json")) kotlinx.serialization.json.Json.parseToJsonElement(plain.decodeToString(throwOnInvalidSequence = true))
        return plain
    }

    override fun read(name: String): ByteArray? = ioLock.withLock {
        val path = "$dir/$name"
        val raw = Files.readBytes(path)
        val candidates = listDir(dir).filter { it.startsWith("$name.broken-") || it.startsWith("$name.encrypted-backup") }
            .filterNot { it.endsWith(".tmp") }.sortedDescending()
        if (raw == null && !fileExists(path) && candidates.isEmpty()) return@withLock null
        val decoded = raw?.let { runCatching { decode(it, name) }.getOrNull() }
        if (decoded != null) {
            if (raw != null && MacSeal.isSealed(raw)) {
                // Migration failure must block writes rather than leave a mixed repository that
                // pretends the encrypted source has safely moved to plaintext.
                try { replacePlain(name, decoded, preserve = true) }
                catch (e: Throwable) { readFailed = true; report(name, e) }
            }
            return@withLock decoded
        }
        // Older releases kept originals under these names. Try their ORIGINAL AAD label.
        val recovered = candidates.firstNotNullOfOrNull { candidate ->
                Files.readBytes("$dir/$candidate")?.let { runCatching { decode(it, name) }.getOrNull() }
            }
        if (recovered != null) {
            try { replacePlain(name, recovered, preserve = raw != null) }
            catch (e: Throwable) { readFailed = true; report(name, e) }
            AppLog.debug("Store", "$name restored from a preserved copy")
            return@withLock recovered
        }
        if (name == "auto.json" && raw != null) {
            // The automatic selection pool is a disposable cache, not user configuration.
            // Its stale key must never hide successfully recovered servers/settings from the UI.
            try {
                replacePlain(name, "{}".encodeToByteArray(), preserve = true)
                AppLog.debug("Store", "unreadable automatic selection cache preserved; the pool will be rebuilt")
                return@withLock "{}".encodeToByteArray()
            } catch (e: Throwable) { report(name, e) }
        }
        readFailed = true
        report(name, IllegalStateException("can't recover $name; original preserved and writes blocked"))
        null
    }

    private fun report(name: String, e: Throwable) {
        if (reported.add(name)) app.borderless.data.Errors.report(e, "recovering $name; original preserved")
    }

    override fun write(name: String, bytes: ByteArray) = ioLock.withLock {
        if (readFailed) throw MacCrypto.Locked()
        val path = "$dir/$name"
        if (fileExists(path)) {
            val previous = Files.readBytes(path) ?: run { readFailed = true; throw MacCrypto.Locked() }
            try { decode(previous, name) }
            catch (e: Throwable) { readFailed = true; throw MacCrypto.Locked() }
        }
        replacePlain(name, bytes, preserve = Files.readBytes(path)?.let(MacSeal::isSealed) == true)
    }

    private fun replacePlain(name: String, plain: ByteArray, preserve: Boolean) {
        val path = "$dir/$name"
        if (preserve) preserveLegacyFile(path)
        val tmp = "$path.tmp"
        Files.writeBytes(tmp, plain)
        chmod(tmp, 0x180u)
        check(Files.readBytes(tmp)?.contentEquals(plain) == true) { "can't verify $name" }
        check(Files.rename(tmp, path)) { "cannot replace $name" }
    }

    override fun keepAside(name: String): String = keepAside(dir, name)
    override val locked: Boolean get() = readFailed
}

/** New log entries are plaintext, 0600. Recoverable old entries are decrypted with every surviving legacy key. */
class MacLogFiles(private val dir: String) : AppLog.Files {
    init { Files.makeDirs(dir); chmod(dir, 0x1C0u) }
    private val ioLock = Lock()

    override fun read(name: String): List<String> = ioLock.withLock {
        listOf("$name.1", name).flatMap { physical ->
            val path = "$dir/$physical"
            val lines = Files.readText(path)?.lines()?.filter { it.isNotEmpty() }.orEmpty()
            val decoded = lines.map { line ->
                if (!line.startsWith("E1:")) line else
                    (listOfNotNull(MacCrypto.key) + MacCrypto.oldKeys).firstNotNullOfOrNull { MacSeal.openLine(it, line, name) }
                        ?: "<unreadable legacy entry; encrypted original preserved>"
            }
            if (lines.any { it.startsWith("E1:") }) runCatching {
                preserveLegacyFile(path)
                val tmp = "$path.tmp"
                val plain = (decoded.joinToString("\n") + "\n").encodeToByteArray()
                Files.writeBytes(tmp, plain); chmod(tmp, 0x180u)
                check(Files.readBytes(tmp)?.contentEquals(plain) == true)
                check(Files.rename(tmp, path))
            }
            decoded
        }
    }

    override fun append(name: String, lines: List<String>, maxBytes: Long) = ioLock.withLock {
        val path = "$dir/$name"
        if (Files.size(path) > maxBytes) Files.rename(path, "$path.1")
        val f = fopen(path, "a") ?: return@withLock
        try {
            lines.forEach { line ->
                val bytes = (line + "\n").encodeToByteArray()
                bytes.usePinned { check(fwrite(it.addressOf(0), 1u, bytes.size.convert(), f).toLong() == bytes.size.toLong()) }
            }
        } finally { fclose(f) }
        chmod(path, 0x180u)
    }

    override fun delete(name: String) { deleteFile("$dir/$name"); deleteFile("$dir/$name.1") }
    override val writable: Boolean get() = true
}
