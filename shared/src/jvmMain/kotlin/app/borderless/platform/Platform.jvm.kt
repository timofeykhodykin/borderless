package app.borderless.platform

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.Inflater

actual class Lock {
    private val lock = java.util.concurrent.locks.ReentrantLock()
    actual fun lock() = lock.lock()
    actual fun unlock() = lock.unlock()
}

actual fun nowMs(): Long = System.currentTimeMillis()

private val stampFmt = ThreadLocal.withInitial { java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US) }
private val dateFmt = ThreadLocal.withInitial { java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US) }

actual fun stampTime(ms: Long): String = stampFmt.get().format(java.util.Date(ms))

actual fun dateTime(ms: Long): String = dateFmt.get().format(java.util.Date(ms))

actual fun cpuCount(): Int = Runtime.getRuntime().availableProcessors()

actual fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

private val random = java.security.SecureRandom()

actual fun secureRandom(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)

actual fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray =
    javax.crypto.Mac.getInstance("HmacSHA256").apply { init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

actual fun listDir(path: String): List<String> = java.io.File(path).list()?.toList().orEmpty()

actual fun deleteFile(path: String): Boolean = java.io.File(path).delete()

actual fun sha1(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-1").digest(bytes)

actual fun deflateRaw(bytes: ByteArray): ByteArray {
    val deflater = Deflater(Deflater.BEST_COMPRESSION, true).apply { setInput(bytes); finish() }
    val out = ByteArrayOutputStream()
    val buf = ByteArray(4096)
    while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf))
    deflater.end()
    return out.toByteArray()
}

actual fun inflateRaw(bytes: ByteArray, maxBytes: Int): ByteArray? {
    val inflater = Inflater(true).apply { setInput(bytes) }
    val out = ByteArrayOutputStream()
    val buf = ByteArray(8192)
    try {
        while (!inflater.finished()) {
            val n = inflater.inflate(buf)
            if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
            out.write(buf, 0, n)
            if (out.size() > maxBytes) return null
        }
        return if (inflater.finished()) out.toByteArray() else null
    } catch (_: java.util.zip.DataFormatException) {
        return null
    } finally {
        inflater.end()
    }
}

actual fun sourceAsset(name: String): String? =
    listOf("src/main/assets/$name", "../android/src/main/assets/$name", "android/src/main/assets/$name")
        .map { java.io.File(it) }.firstOrNull { it.isFile }?.readText()

actual fun fileExists(path: String): Boolean = java.io.File(path).isFile

actual fun readFile(path: String): String? = runCatching { java.io.File(path).readText() }.getOrNull()
