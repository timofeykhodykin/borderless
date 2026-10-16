package app.borderless.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Authenticated encryption (AES-256-GCM) of a byte blob, bound to a label (the file name), so a
 * file can be neither read, nor changed, nor swapped for another one without the key.
 * Format: "BLS1" | 12-byte nonce | ciphertext + 16-byte tag. Pure JVM, unit-tested.
 */
object Seal {
    private val MAGIC = "BLS1".toByteArray()
    private const val NONCE = 12
    private const val TAG_BITS = 128
    private val random = SecureRandom()

    fun seal(key: SecretKey, plain: ByteArray, label: String): ByteArray {
        val nonce = ByteArray(NONCE).also(random::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
        c.updateAAD(label.toByteArray())
        return MAGIC + nonce + c.doFinal(plain)
    }

    fun isSealed(b: ByteArray): Boolean =
        b.size >= MAGIC.size + NONCE + TAG_BITS / 8 && b.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)

    /** Throws (AEADBadTagException) if the data was changed, belongs to another label or another key. */
    fun open(key: SecretKey, sealed: ByteArray, label: String): ByteArray {
        require(isSealed(sealed)) { "not sealed data" }
        val nonce = sealed.copyOfRange(MAGIC.size, MAGIC.size + NONCE)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
        c.updateAAD(label.toByteArray())
        return c.doFinal(sealed, MAGIC.size + NONCE, sealed.size - MAGIC.size - NONCE)
    }

    private const val LINE = "E1:"

    /** One text line (a log line), encrypted and Base64-encoded so it stays one line. */
    fun sealLine(key: SecretKey, line: String, label: String): String =
        LINE + Base64.getEncoder().encodeToString(seal(key, line.toByteArray(), label))

    /** Inverse of [sealLine]; plain lines (written without a Keystore) come back as they are, broken ones as null. */
    fun openLine(key: SecretKey?, line: String, label: String): String? {
        if (!line.startsWith(LINE)) return line
        if (key == null) return null
        return runCatching { String(open(key, Base64.getDecoder().decode(line.substring(LINE.length)), label)) }.getOrNull()
    }
}

/**
 * The app's data key. A random AES-256 key encrypts everything stored ([Repo] files, logs); it is
 * itself kept only in wrapped form, encrypted by a non-exportable key in the Android Keystore
 * (hardware-backed where the phone has it). Without the Keystore — e.g. it is broken on the device —
 * a new install keeps its data unencrypted and the problem is reported ([initError]). If sealed data is already
 * here but the Keystore doesn't answer, nothing is rotated or overwritten: the app runs [locked] (SECURITY.md S5).
 */
object Crypto {
    private const val ALIAS = "borderless.wrap"
    private const val KEY_FILE = "keys/data.key"

    @Volatile
    var key: SecretKey? = null
        private set

    /** Why encryption is off (or [locked]), if it is. */
    @Volatile
    var initError: Throwable? = null
        private set

    /**
     * Sealed data is here, but the Keystore didn't answer (often a passing fault, e.g. right after a system update):
     * the data can't be opened now, and nothing may be written — a write would replace it with plaintext.
     */
    @Volatile
    var locked = false
        private set

    /** Data keys kept aside by versions that started a new key on any failure; tried when the current one fails. */
    @Volatile
    var oldKeys: List<SecretKey> = emptyList()
        private set

    /** Data sealed with the key in use can't be opened now ([locked]). */
    class Locked : IllegalStateException("stored data is encrypted and its key is unavailable now")

    @Synchronized
    fun init(context: Context) {
        if (key != null) return
        val file = File(context.filesDir, KEY_FILE)
        var last: Exception? = null
        for (attempt in 0 until 3) {
            try {
                val wrapping = keystoreKey()
                key = if (file.exists()) {
                    try {
                        SecretKeySpec(Seal.open(wrapping, file.readBytes(), "data-key"), "AES")
                    } catch (e: Exception) {
                        // A passing fault: try again (and in the end run locked), never start a new key over it.
                        if (!permanent(e)) throw e
                        // The Keystore lost its key (reset of the keystore): data sealed with the old key can't be
                        // read any more. Start a new key and keep the old file aside.
                        file.renameTo(File(file.path + ".broken-${System.currentTimeMillis()}"))
                        initError = e
                        newKey(file, wrapping)
                    }
                } else {
                    newKey(file, wrapping)
                }
                oldKeys = file.parentFile?.listFiles { f -> f.name.startsWith("${file.name}.broken-") }.orEmpty().mapNotNull { f ->
                    runCatching { SecretKeySpec(Seal.open(wrapping, f.readBytes(), "data-key"), "AES") }.getOrNull()
                }
                return
            } catch (e: Exception) {
                last = e
                Thread.sleep(250L shl (2 * attempt))
            }
        }
        initError = last
        key = null
        // Data sealed before: keep it as it is until the Keystore answers again (next start).
        locked = file.exists()
    }

    /** The data key file can never be opened with this Keystore key (it was replaced or invalidated). */
    private fun permanent(e: Throwable): Boolean = generateSequence(e) { it.cause }.any {
        it is javax.crypto.AEADBadTagException || it is android.security.keystore.KeyPermanentlyInvalidatedException ||
            it is java.security.UnrecoverableKeyException || it is IllegalArgumentException
    }

    private fun newKey(file: File, wrapping: SecretKey): SecretKey {
        val raw = ByteArray(32).also(SecureRandom()::nextBytes)
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeBytes(Seal.seal(wrapping, raw, "data-key"))
        tmp.renameTo(file)
        return SecretKeySpec(raw, "AES")
    }

    private fun keystoreKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // Seal picks its own random nonce for every message.
                .setRandomizedEncryptionRequired(false)
                .build()
        )
        return gen.generateKey()
    }

    /** Encrypts a file's contents for [label] (its name); unencrypted if the Keystore is unavailable (never while [locked]). */
    fun seal(plain: ByteArray, label: String): ByteArray {
        if (locked) throw Locked()
        return key?.let { Seal.seal(it, plain, label) } ?: plain
    }

    /**
     * Decrypts a file's contents; the flag says it should be written again with the key in use (it was unencrypted,
     * or sealed with one of the [oldKeys]). Throws [Locked] while the key is unavailable, and if the data was
     * tampered with.
     */
    fun open(bytes: ByteArray, label: String): Pair<ByteArray, Boolean> {
        if (!Seal.isSealed(bytes)) return bytes to (key != null)
        val k = key ?: throw Locked()
        return try {
            Seal.open(k, bytes, label) to false
        } catch (e: javax.crypto.AEADBadTagException) {
            oldKeys.firstNotNullOfOrNull { old -> runCatching { Seal.open(old, bytes, label) }.getOrNull() }?.let { it to true } ?: throw e
        }
    }
}
