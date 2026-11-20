@file:OptIn(ExperimentalForeignApi::class)

package app.borderless.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.NSFileManager
import platform.Security.SecKeyCreateWithData
import platform.Security.SecKeyVerifySignature
import platform.Security.kSecAttrKeyClass
import platform.Security.kSecAttrKeyClassPublic
import platform.Security.kSecAttrKeyType
import platform.Security.kSecAttrKeyTypeECSECPrimeRandom
import platform.Security.kSecKeyAlgorithmECDSASignatureMessageX962SHA256
import platform.posix.O_RDONLY
import platform.posix.SEEK_SET
import platform.posix.close
import platform.posix.fclose
import platform.posix.fflush
import platform.posix.fileno
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseeko
import platform.posix.fsync
import platform.posix.fwrite
import platform.posix.open
import platform.posix.stat

actual object Files {
    actual fun readBytes(path: String): ByteArray? {
        val n = size(path).takeIf { it >= 0 } ?: return null
        return runCatching { readRange(path, 0, n.toInt()) }.getOrNull()
    }

    actual fun writeBytes(path: String, bytes: ByteArray) {
        val f = fopen(path, "wb") ?: throw IllegalStateException("can't write $path")
        try {
            if (bytes.isNotEmpty()) {
                val n = bytes.usePinned { fwrite(it.addressOf(0), 1u, bytes.size.convert(), f) }
                check(n.toLong() == bytes.size.toLong()) { "short write to $path" }
            }
            // To the device before any rename() over the real file: a power loss between the two must not
            // leave the name pointing at pages that never made it (audit — same gap Android still has).
            check(fflush(f) == 0) { "can't write $path" }
            check(fsync(fileno(f)) == 0) { "can't write $path" }
        } finally {
            fclose(f)
        }
    }

    actual fun rename(from: String, to: String): Boolean {
        if (platform.posix.rename(from, to) != 0) return false
        // The moved name is durable only once the directory entry is; the fsync above covered the file's
        // own blocks. Best effort — the entry survives the usual fsync-less mount as it did before.
        val d = open(to.substringBeforeLast('/', "."), O_RDONLY)
        if (d >= 0) { fsync(d); close(d) }
        return true
    }

    actual fun size(path: String): Long = memScoped {
        val st = alloc<stat>()
        if (stat(path, st.ptr) != 0) return -1
        if ((st.st_mode.toInt() and 0xF000) != 0x8000) return -1 // not a regular file
        st.st_size
    }

    actual fun readRange(path: String, offset: Long, length: Int): ByteArray {
        val f = fopen(path, "rb") ?: throw IllegalStateException("can't read $path")
        try {
            val out = ByteArray(length)
            if (length == 0) return out
            check(fseeko(f, offset, SEEK_SET) == 0) { "can't seek in $path" }
            val n = out.usePinned { fread(it.addressOf(0), 1u, length.convert(), f) }
            check(n.toLong() == length.toLong()) { "short read in $path" }
            return out
        } finally {
            fclose(f)
        }
    }

    actual fun makeDirs(path: String) {
        NSFileManager.defaultManager.createDirectoryAtPath(path, true, null, null)
    }
}

/**
 * Keeps damaged files aside so the next save doesn't overwrite them for good, and prunes the pile: the
 * newest [keep] `<name>.broken-<ms>` files survive (names sort by their millisecond suffix — all the same
 * width for ages). Used by the repo's store and the statistics' keep-aside (audit: `.broken-*` pruning).
 */
fun pruneAside(dir: String, name: String, keep: Int = 5) {
    listDir(dir).filter { it.startsWith("$name.broken-") }.sortedDescending().drop(keep)
        .forEach { deleteFile("$dir/$it") }
}

/** Keeps a copy of the damaged [name] aside (the copy's name), pruning older ones with [pruneAside]. */
fun keepAside(dir: String, name: String): String {
    val backup = "$name.broken-${nowMs()}"
    Files.readBytes("$dir/$name")?.let { Files.writeBytes("$dir/$backup", it) }
    pruneAside(dir, name)
    return backup
}

/** [path] to the device (for durability steps a rename over another file must not precede). */
fun fsyncFile(path: String) {
    val d = open(path, O_RDONLY)
    if (d >= 0) { fsync(d); close(d) }
}

private fun ByteArray.toCFData(): CFDataRef? =
    if (isEmpty()) CFDataCreate(null, null, 0) else usePinned { CFDataCreate(null, it.addressOf(0).reinterpret(), size.convert()) }

actual fun ecdsaP256Verify(publicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean {
    // Security wants the bare point (04 || X || Y); a P-256 SubjectPublicKeyInfo ends with it.
    if (publicKey.size < 65 || publicKey[publicKey.size - 65] != 4.toByte()) return false
    val point = publicKey.copyOfRange(publicKey.size - 65, publicKey.size).toCFData()
    val attrs = CFDictionaryCreateMutable(null, 2, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
    CFDictionarySetValue(attrs, kSecAttrKeyType, kSecAttrKeyTypeECSECPrimeRandom)
    CFDictionarySetValue(attrs, kSecAttrKeyClass, kSecAttrKeyClassPublic)
    val key = SecKeyCreateWithData(point, attrs, null)
    val message = data.toCFData()
    val sig = signature.toCFData()
    val ok = key != null && SecKeyVerifySignature(key, kSecKeyAlgorithmECDSASignatureMessageX962SHA256, message, sig, null)
    listOfNotNull(point, attrs, key, message, sig).forEach { CFRelease(it) }
    return ok
}
