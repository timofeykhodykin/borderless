@file:OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)

package app.borderless.platform

import kotlin.experimental.ExperimentalNativeApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA1
import platform.CoreCrypto.CC_SHA1_DIGEST_LENGTH
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.CoreCrypto.CCHmac
import platform.CoreCrypto.kCCHmacAlgSHA256
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSRecursiveLock
import platform.Foundation.timeIntervalSince1970
import platform.zlib.Z_BEST_COMPRESSION
import platform.zlib.Z_DEFAULT_STRATEGY
import platform.zlib.Z_DEFLATED
import platform.zlib.Z_FINISH
import platform.zlib.Z_NO_FLUSH
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.deflate
import platform.zlib.deflateEnd
import platform.zlib.deflateInit2
import platform.zlib.inflate
import platform.zlib.inflateEnd
import platform.zlib.inflateInit2
import platform.zlib.z_stream

actual class Lock {
    private val lock = NSRecursiveLock()
    actual fun lock() = lock.lock()
    actual fun unlock() = lock.unlock()
}

actual fun nowMs(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()

private fun formatter(pattern: String) = platform.Foundation.NSDateFormatter().apply {
    dateFormat = pattern
    locale = platform.Foundation.NSLocale(localeIdentifier = "en_US_POSIX")
    // A root daemon's formatter zone resolves to UTC — the current calendar carries the zone the
    // user set (/etc/localtime), so the log's stamps match the times the app renders (owner, 2026-11-10).
    platform.Foundation.NSCalendar.currentCalendar.timeZone?.let { timeZone = it }
}

private val stampFmt = formatter("yyyy-MM-dd HH:mm:ss.SSS")
private val dateFmt = formatter("yyyy-MM-dd HH:mm:ss")
private val fmtLock = Lock()

private fun Long.date() = NSDate(timeIntervalSinceReferenceDate = this / 1000.0 - 978_307_200.0)

actual fun stampTime(ms: Long): String = fmtLock.withLock { stampFmt.stringFromDate(ms.date()) }

actual fun dateTime(ms: Long): String = fmtLock.withLock { dateFmt.stringFromDate(ms.date()) }

actual fun cpuCount(): Int = NSProcessInfo.processInfo.activeProcessorCount.toInt()

/**
 * A development build on the Mac ([Host.debug], what Android reads as `BuildConfig.DEBUG`): a debug-compiled
 * framework, or a release one run with `BORDERLESS_DEBUG` in the environment — the service is always built
 * release, so the variable is its debug switch. Gates the test affordances: the routing lists' own
 * `manifest.test.json` and the limited-network `limited.test` file.
 */
fun debugBinary(): Boolean = kotlin.native.Platform.isDebugBinary || platform.posix.getenv("BORDERLESS_DEBUG") != null

actual fun secureRandom(size: Int): ByteArray {
    val out = ByteArray(size)
    if (size > 0) out.usePinned { platform.posix.arc4random_buf(it.addressOf(0), size.convert()) }
    return out
}

actual fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
    val out = ByteArray(CC_SHA256_DIGEST_LENGTH)
    key.usePinned { k ->
        data.usePinned { d ->
            out.usePinned { o ->
                CCHmac(kCCHmacAlgSHA256, if (key.isEmpty()) null else k.addressOf(0), key.size.convert(),
                    if (data.isEmpty()) null else d.addressOf(0), data.size.convert(), o.addressOf(0))
            }
        }
    }
    return out
}

actual fun sha256(bytes: ByteArray): ByteArray {
    val out = ByteArray(CC_SHA256_DIGEST_LENGTH)
    bytes.usePinned { input ->
        out.usePinned { digest ->
            CC_SHA256(if (bytes.isEmpty()) null else input.addressOf(0), bytes.size.convert(), digest.addressOf(0).reinterpret())
        }
    }
    return out
}

actual fun sha1(bytes: ByteArray): ByteArray {
    val out = ByteArray(CC_SHA1_DIGEST_LENGTH)
    bytes.usePinned { input ->
        out.usePinned { digest ->
            CC_SHA1(if (bytes.isEmpty()) null else input.addressOf(0), bytes.size.convert(), digest.addressOf(0).reinterpret())
        }
    }
    return out
}

actual fun deflateRaw(bytes: ByteArray): ByteArray = memScoped {
    val s = alloc<z_stream>()
    // windowBits -15: raw deflate, as java.util.zip.Deflater(level, nowrap = true).
    if (deflateInit2(s.ptr, Z_BEST_COMPRESSION, Z_DEFLATED, -15, 8, Z_DEFAULT_STRATEGY) != Z_OK) return@memScoped ByteArray(0)
    val out = ArrayList<Byte>(bytes.size / 2 + 64)
    val buf = ByteArray(16384)
    val input = if (bytes.isEmpty()) ByteArray(1) else bytes
    input.usePinned { inPin ->
        buf.usePinned { outPin ->
            s.next_in = inPin.addressOf(0).reinterpret<UByteVar>()
            s.avail_in = bytes.size.convert()
            do {
                s.next_out = outPin.addressOf(0).reinterpret()
                s.avail_out = buf.size.convert()
                val rc = deflate(s.ptr, Z_FINISH)
                val n = buf.size - s.avail_out.toInt()
                for (i in 0 until n) out.add(buf[i])
            } while (rc == Z_OK)
        }
    }
    deflateEnd(s.ptr)
    out.toByteArray()
}

actual fun inflateRaw(bytes: ByteArray, maxBytes: Int): ByteArray? = memScoped {
    if (bytes.isEmpty()) return@memScoped null
    val s = alloc<z_stream>()
    if (inflateInit2(s.ptr, -15) != Z_OK) return@memScoped null
    val out = ArrayList<Byte>(bytes.size * 3)
    val buf = ByteArray(16384)
    var ok = false
    bytes.usePinned { inPin ->
        buf.usePinned { outPin ->
            s.next_in = inPin.addressOf(0).reinterpret()
            s.avail_in = bytes.size.convert()
            while (true) {
                s.next_out = outPin.addressOf(0).reinterpret()
                s.avail_out = buf.size.convert()
                val rc = inflate(s.ptr, Z_NO_FLUSH)
                val n = buf.size - s.avail_out.toInt()
                for (i in 0 until n) out.add(buf[i])
                if (out.size > maxBytes) break
                if (rc == Z_STREAM_END) { ok = true; break }
                if (rc != Z_OK || (n == 0 && s.avail_in.toInt() == 0)) break
            }
        }
    }
    inflateEnd(s.ptr)
    if (ok) out.toByteArray() else null
}

actual fun sourceAsset(name: String): String? =
    listOf("src/main/assets/$name", "../android/src/main/assets/$name", "android/src/main/assets/$name")
        .firstOrNull { fileExists(it) }
        ?.let(::readFile)

// The same fread path as Files (NSString.stringWithContentsOfFile is deprecated); strict UTF-8 — a
// malformed file reads as "can't be read", as the NSString form returned nil for it.
actual fun readFile(path: String): String? =
    runCatching { Files.readBytes(path)?.decodeToString(throwOnInvalidSequence = true) }.getOrNull()

actual fun listDir(path: String): List<String> =
    NSFileManager.defaultManager.contentsOfDirectoryAtPath(path, null)?.map { it as String }.orEmpty()

actual fun deleteFile(path: String): Boolean = NSFileManager.defaultManager.removeItemAtPath(path, null)

actual fun fileExists(path: String): Boolean = NSFileManager.defaultManager.fileExistsAtPath(path)
