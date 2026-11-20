@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package app.borderless.bridge

import app.borderless.data.AppLog
import app.borderless.platform.AesGcm
import app.borderless.platform.Files
import app.borderless.platform.Lock
import app.borderless.platform.base64Decode
import app.borderless.platform.base64Encode
import app.borderless.platform.hmacSha256
import app.borderless.platform.listDir
import app.borderless.platform.secureRandom
import app.borderless.platform.withLock
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFErrorGetCode
import platform.CoreFoundation.CFErrorRefVar
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanFalse
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Security.SecItemCopyMatching
import platform.Security.SecKeyCopyPublicKey
import platform.Security.SecKeyCreateDecryptedData
import platform.Security.errSecItemNotFound
import platform.Security.kSecAttrApplicationTag
import platform.Security.kSecAttrKeyClass
import platform.Security.kSecAttrKeyClassPrivate
import platform.Security.kSecAttrKeyType
import platform.Security.kSecAttrKeyTypeRSA
import platform.Security.kSecClass
import platform.Security.kSecClassKey
import platform.Security.kSecKeyAlgorithmRSAEncryptionOAEPSHA256
import platform.Security.kSecReturnRef
import platform.Security.kSecUseDataProtectionKeychain
import platform.posix.gethostuuid
import kotlin.concurrent.Volatile

/**
 * Authenticated encryption (AES-256-GCM) of a byte blob, bound to a label (the file name), so a file can be
 * neither read, nor changed, nor swapped for another one without the key — byte-for-byte Android's `Seal`
 * format ("BLS1" | 12-byte nonce | ciphertext + 16-byte tag, the label as AAD), so the two platforms could
 * read each other's blobs if the key ever travelled (it does not — see [MacCrypto]).
 */
object MacSeal {
    private val MAGIC = "BLS1".encodeToByteArray()

    fun seal(key: ByteArray, plain: ByteArray, label: String): ByteArray {
        val nonce = secureRandom(AesGcm.NONCE_BYTES)
        return MAGIC + nonce + AesGcm.seal(key, nonce, label.encodeToByteArray(), plain)
    }

    fun isSealed(b: ByteArray): Boolean =
        b.size >= MAGIC.size + AesGcm.NONCE_BYTES + AesGcm.TAG_BYTES &&
            b.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)

    /** Throws ([SealOpenException]) if the data was changed, belongs to another label or another key. */
    fun open(key: ByteArray, sealed: ByteArray, label: String): ByteArray {
        require(isSealed(sealed)) { "not sealed data" }
        val nonce = sealed.copyOfRange(MAGIC.size, MAGIC.size + AesGcm.NONCE_BYTES)
        return AesGcm.open(key, nonce, label.encodeToByteArray(), sealed.copyOfRange(MAGIC.size + AesGcm.NONCE_BYTES, sealed.size))
            ?: throw SealOpenException("can't unseal data for $label")
    }

    class SealOpenException(message: String) : IllegalStateException(message)

    private const val LINE = "E1:"

    /** One text line (a log line), encrypted and Base64-encoded so it stays one line — Android's `sealLine`. */
    fun sealLine(key: ByteArray, line: String, label: String): String =
        LINE + base64Encode(seal(key, line.encodeToByteArray(), label))

    /** Inverse of [sealLine]; plain lines (written without a key) come back as they are, broken ones as null. */
    fun openLine(key: ByteArray?, line: String, label: String): String? {
        if (!line.startsWith(LINE)) return line
        if (key == null) return null
        val bytes = base64Decode(line.substring(LINE.length)) ?: return null
        return runCatching { open(key, bytes, label).decodeToString() }.getOrNull()
    }
}

/**
 * What wraps the data key for storage (the platform's non-exportable-key facility). [unwrap] throws
 * [WrapGone] when the data key can never come back (the wrapping key was replaced or the blob is not its
 * work) and anything else for a passing fault — exactly Android's `Crypto.permanent()` split.
 */
interface WrapBox {
    /** The mechanism's name for the log ("keychain", "machine-file") — which tier answered is diagnosis. */
    val name: String get() = "wrap"

    /**
     * Whether the wrapped blob can be this mechanism's work at all, by format alone — before any key
     * operation. Used to give the tier that wrote `data.key` the first try (and to keep its blob out of
     * other tiers' keep-aside decisions). Default true: a single-box world needs no discrimination.
     */
    fun owns(wrapped: ByteArray): Boolean = true

    fun unwrap(wrapped: ByteArray): ByteArray
}

/** The wrapped blob is not this box's work — the same data will never unwrap with the key the box has now. */
class WrapGone(message: String) : Exception(message)

/** Legacy decryption only. New Mac storage uses ordinary root-owned JSON, log files and SQLite. */
object MacCrypto {
    @Volatile var key: ByteArray? = null
        private set
    @Volatile var oldKeys: List<ByteArray> = emptyList()
        private set
    class Locked : IllegalStateException("stored data could not be recovered; writes are blocked")
    internal var wrapBoxes: () -> List<WrapBox> = { listOf(KeychainWrap(), FileWrap()) }
    private val lock = Lock()
    private val KEY_MAGIC = "BLWK".encodeToByteArray()
    private val KEY_TAG = "app.borderless.wrap".encodeToByteArray()
    internal fun tagData(): CFDataRef? = KEY_TAG.toCFData()
    /** Read-only legacy key recovery. Production never creates, rotates, rewraps or writes data keys. */
    fun loadLegacy(dataDir: String) = lock.withLock {
        key = null; oldKeys = emptyList()
        val names = listDir("$dataDir/keys").filter { it == "data.key" || it == "data.key.recovery" || it.startsWith("data.key.broken-") }
            .sortedWith(compareBy<String> { when (it) { "data.key" -> 0; "data.key.recovery" -> 1; else -> 2 } }.thenByDescending { it })
        val boxes = runCatching { wrapBoxes() }.getOrDefault(emptyList())
        val recovered = ArrayList<ByteArray>()
        for (name in names) {
            val bytes = Files.readBytes("$dataDir/keys/$name") ?: continue
            val blob = bytes.drop(KEY_MAGIC.size).toByteArray()
            for (box in boxes.sortedByDescending { it.owns(blob) }) {
                val raw = runCatching { unwrapKeyFile(box, bytes) }.getOrNull() ?: continue
                if (recovered.none { it.contentEquals(raw) }) recovered.add(raw)
                break
            }
        }
        key = recovered.firstOrNull()
        oldKeys = recovered.drop(1)
        // Plain files remain usable even when none of the legacy keys is available.
    }

    /** The sealed key file → the raw data key. Throws [WrapGone] for damage and for another key's work. */
    private fun unwrapKeyFile(box: WrapBox, bytes: ByteArray): ByteArray {
        if (bytes.size <= KEY_MAGIC.size || !bytes.copyOfRange(0, KEY_MAGIC.size).contentEquals(KEY_MAGIC))
            throw WrapGone("not a wrapped data key")
        val raw = box.unwrap(bytes.copyOfRange(KEY_MAGIC.size, bytes.size))
        if (raw.size != AesGcm.KEY_BYTES) throw WrapGone("wrapped blob is not a data key")
        return raw
    }

    /**
     * Decrypts a legacy blob with any surviving key. The Boolean identifies use of an older key.
     * Plain files pass through without needing a key.
     */
    fun open(bytes: ByteArray, label: String): Pair<ByteArray, Boolean> {
        if (!MacSeal.isSealed(bytes)) return bytes to false
        val k = key ?: throw Locked()
        return try {
            MacSeal.open(k, bytes, label) to false
        } catch (e: MacSeal.SealOpenException) {
            oldKeys.firstNotNullOfOrNull { old -> runCatching { MacSeal.open(old, bytes, label) }.getOrNull() }
                ?.let { it to true } ?: throw e
        }
    }

    /** The statistics database's raw key: HMAC-SHA256 of its name under the data key, as Android's `dbKey`. */
    fun dbKey(): ByteArray? = key?.let { hmacSha256(it, "stats-db".encodeToByteArray()) }

    /** The same derivation over [oldKeys] — a database sealed under a rotated key can still be opened and re-keyed. */
    fun dbOldKeys(): List<ByteArray> = oldKeys.map { hmacSha256(it, "stats-db".encodeToByteArray()) }

    internal fun resetForTest() = lock.withLock {
        key = null; oldKeys = emptyList()
        wrapBoxes = { listOf(KeychainWrap(), FileWrap()) }
    }

}

// ------------------------------------------------------------------ the Keychain wrap box

/** Read an existing legacy RSA wrapping key. Never create keys or change their ACLs. */
private class KeychainWrap : WrapBox {
    override val name get() = "keychain"

    /** An RSA-OAEP-2048 wrap is 256 raw bytes — it can never look like [FileWrap]'s `BLS1` blob. */
    override fun owns(wrapped: ByteArray): Boolean = !MacSeal.isSealed(wrapped)
    /** The keychain item, if it exists (the private half; the tag makes it ours alone). */
    private fun find(): Pair<COpaquePointer, COpaquePointer>? = memScoped {
        val q = CFDictionaryCreateMutable(null, 5, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
        CFDictionarySetValue(q, kSecClass, kSecClassKey)
        CFDictionarySetValue(q, kSecAttrKeyClass, kSecAttrKeyClassPrivate)
        CFDictionarySetValue(q, kSecAttrKeyType, kSecAttrKeyTypeRSA)
        MacCrypto.tagData()?.let { t -> CFDictionarySetValue(q, kSecAttrApplicationTag, t); CFRelease(t) }
        // The file-based keychain (System.keychain for a root daemon) — secd, which serves the
        // data-protection keychain, never answers a process without a user session.
        CFDictionarySetValue(q, kSecUseDataProtectionKeychain, kCFBooleanFalse)
        CFDictionarySetValue(q, kSecReturnRef, kCFBooleanTrue)
        val out = alloc<CFTypeRefVar>()
        val rc = SecItemCopyMatching(q, out.ptr)
        CFRelease(q)
        when (rc) {
            0 -> {
                val priv = out.value ?: throw IllegalStateException("keychain returned an empty key")
                val pub = SecKeyCopyPublicKey(priv.reinterpret())
                    ?: throw IllegalStateException("the keychain key has no public half")
                priv to pub
            }
            errSecItemNotFound -> null
            else -> throw IllegalStateException("keychain lookup failed ($rc)")
        }
    }

    override fun unwrap(wrapped: ByteArray): ByteArray = memScoped {
        val err = alloc<CFErrorRefVar>()
        val data = wrapped.toCFData()
        val existing = find() ?: throw WrapGone("the legacy wrapping key is missing")
        val out = SecKeyCreateDecryptedData(existing.first.reinterpret(), kSecKeyAlgorithmRSAEncryptionOAEPSHA256, data, err.ptr)
        CFRelease(data)
        if (out == null) {
            val code = err.value?.let { CFErrorGetCode(it) }
            throw IllegalStateException("cannot unwrap the data key ($code)")
        }
        out.toByteArray().also { CFRelease(out) }
    }


}

/** Recover the legacy machine-file wrap using its original hardware-UUID derivation. */
private class FileWrap : WrapBox {
    override val name get() = "machine-file"

    /** Its blob is a [MacSeal] (`BLS1`) of the data key under the machine key — never an RSA blob. */
    override fun owns(wrapped: ByteArray): Boolean = MacSeal.isSealed(wrapped)

    /** HMAC-SHA256 of our tag under the 16-byte hardware UUID: the machine-bound wrap key ("" = same key everywhere — still perms-protected). */
    private val wrapKey: ByteArray by lazy {
        val uuid = runCatching {
            ByteArray(16).also { u ->
                u.usePinned { check(gethostuuid(it.addressOf(0).reinterpret(), null) == 0) { "gethostuuid failed" } }
            }
        }.getOrElse {
            AppLog.debug("Crypto", "the hardware UUID can't be read ($it); the machine-file wrap degrades to a fixed key")
            ByteArray(0)
        }
        hmacSha256(uuid, "borderless-wrap".encodeToByteArray())
    }


    override fun unwrap(wrapped: ByteArray): ByteArray {
        if (!MacSeal.isSealed(wrapped)) throw WrapGone("the blob is not a sealed wrap")
        return try {
            MacSeal.open(wrapKey, wrapped, "data-key")
        } catch (e: MacSeal.SealOpenException) {
            throw WrapGone("the blob is not this machine's wrap")
        }
    }
}

internal fun ByteArray.toCFData(): CFDataRef? =
    if (isEmpty()) CFDataCreate(null, null, 0)
    else usePinned { CFDataCreate(null, it.addressOf(0).reinterpret(), size.convert()) }

private fun CFDataRef.toByteArray(): ByteArray {
    val n = CFDataGetLength(this).toInt()
    val p = CFDataGetBytePtr(this) ?: return ByteArray(0)
    return p.readBytes(n)
}
