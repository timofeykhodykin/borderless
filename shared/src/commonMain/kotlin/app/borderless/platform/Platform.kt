package app.borderless.platform

import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

/**
 * What the shared code needs from the platform and the Kotlin standard library doesn't give in common code: the
 * clock, SHA-256, raw deflate (the share format), a lock for small caches. JVM (Android) and macOS each implement it.
 */
expect fun nowMs(): Long

/** Local time as "MM-dd HH:mm:ss.SSS" (the detailed log's lines). */
expect fun stampTime(ms: Long): String

/** Local time as "yyyy-MM-dd HH:mm:ss" (exports, reports). */
expect fun dateTime(ms: Long): String

expect fun sha256(bytes: ByteArray): ByteArray

/** [size] bytes from the system's cryptographic random source. */
expect fun secureRandom(size: Int): ByteArray

/** HMAC-SHA256 of [data] keyed with [key]. */
expect fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray

/** SHA-1: only where earlier releases fixed it (server ids). */
expect fun sha1(bytes: ByteArray): ByteArray

/** Processor cores available to the app. */
expect fun cpuCount(): Int

/** Whether a regular file exists at [path] (the routing lists' own `.dat` files). */
expect fun fileExists(path: String): Boolean

/** The names of the entries in the folder at [path]; empty when there is none. */
expect fun listDir(path: String): List<String>

/** Deletes the file at [path]; false when it wasn't there or couldn't go. */
expect fun deleteFile(path: String): Boolean

/** A UTF-8 text file's contents, null when it can't be read. */
expect fun readFile(path: String): String?

/** Raw DEFLATE (RFC 1951, no zlib header), best compression — byte-compatible across platforms for the share format. */
expect fun deflateRaw(bytes: ByteArray): ByteArray

/** Inflates raw DEFLATE; null when damaged or larger than [maxBytes] (a zip-bomb guard). */
expect fun inflateRaw(bytes: ByteArray, maxBytes: Int): ByteArray?

/** A reentrant lock (`synchronized` in common code): `ReentrantLock` on the JVM, `NSRecursiveLock` on macOS. */
expect class Lock() {
    fun lock()
    fun unlock()
}

@OptIn(ExperimentalContracts::class)
inline fun <T> Lock.withLock(block: () -> T): T {
    contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
    lock()
    try {
        return block()
    } finally {
        unlock()
    }
}

/** A map guarded by a [Lock]: `getOrPut` for caches. */
class LockedMap<K, V> {
    private val lock = Lock()
    private val map = HashMap<K, V>()

    fun getOrPut(key: K, make: () -> V): V {
        lock.withLock { map[key] }?.let { return it }
        val v = make()
        return lock.withLock { map.getOrPut(key) { v } }
    }

    operator fun get(key: K): V? = lock.withLock { map[key] }
    operator fun set(key: K, value: V) { lock.withLock { map[key] = value } }
    fun clear() = lock.withLock { map.clear() }
    fun remove(key: K): V? = lock.withLock { map.remove(key) }
}

/** "%.Nf" without `String.format` (JVM only): rounds half away from zero, '.' as the separator. */
fun fixed(value: Double, digits: Int): String {
    if (value.isNaN()) return "NaN"
    var scale = 1.0
    repeat(digits) { scale *= 10 }
    val negative = value < 0
    // Half away from zero, as java's String.format (kotlin.math.round would round halves to even).
    val n = kotlin.math.floor(kotlin.math.abs(value) * scale + 0.5).toLong()
    val whole = n / scale.toLong()
    val frac = n % scale.toLong()
    val sign = if (negative && n != 0L) "-" else ""
    return if (digits == 0) "$sign$whole" else "$sign$whole." + frac.toString().padStart(digits, '0')
}

/** Lower-case hex of bytes. */
fun ByteArray.hex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
