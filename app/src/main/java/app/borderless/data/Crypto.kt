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
 * data stays readable but unencrypted and the problem is reported ([initError]).
 */
object Crypto {
    private const val ALIAS = "borderless.wrap"
    private const val KEY_FILE = "keys/data.key"

    @Volatile
    var key: SecretKey? = null
        private set

    /** Why encryption is off, if it is. */
    @Volatile
    var initError: Throwable? = null
        private set

    @Synchronized
    fun init(context: Context) {
        if (key != null) return
        try {
            val file = File(context.filesDir, KEY_FILE)
            val wrapping = keystoreKey()
            key = if (file.exists()) {
                try {
                    SecretKeySpec(Seal.open(wrapping, file.readBytes(), "data-key"), "AES")
                } catch (e: Exception) {
                    // The Keystore lost its key (rare: device reset of the keystore). Data sealed with
                    // the old key cannot be read any more; start a new key and keep the old file aside.
                    file.renameTo(File(file.path + ".broken-${System.currentTimeMillis()}"))
                    initError = e
                    newKey(file, wrapping)
                }
            } else {
                newKey(file, wrapping)
            }
        } catch (e: Exception) {
            initError = e
            key = null
        }
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

    /** Encrypts a file's contents for [label] (its name); unencrypted if the Keystore is unavailable. */
    fun seal(plain: ByteArray, label: String): ByteArray = key?.let { Seal.seal(it, plain, label) } ?: plain

    /**
     * Decrypts a file's contents. Unencrypted data (written without a Keystore) is returned as is with `plain = true`.
     * Throws if the data was tampered with.
     */
    fun open(bytes: ByteArray, label: String): Pair<ByteArray, Boolean> {
        if (!Seal.isSealed(bytes)) return bytes to true
        val k = key ?: error("data is encrypted but the key is unavailable")
        return Seal.open(k, bytes, label) to false
    }
}
