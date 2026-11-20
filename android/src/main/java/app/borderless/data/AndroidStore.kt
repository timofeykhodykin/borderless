package app.borderless.data

import android.content.Context
import java.io.File

/** [Repo]'s files on Android: `files/data/<name>`, each sealed with the Keystore's key and bound to its name ([Crypto]). */
class AndroidStore(context: Context) : DataStore {
    private val dir = File(context.filesDir, "data").apply { mkdirs() }

    override fun read(name: String): ByteArray? {
        val f = File(dir, name)
        if (!f.exists()) return null
        val (bytes, reseal) = try {
            Crypto.open(f.readBytes(), name)
        } catch (e: Crypto.Locked) {
            // Kept exactly as it is until the key can be opened again (no copy, no report: Crypto says it once).
            return null
        }
        // Unencrypted (written without a Keystore) or under an old key: sealed with the key in use now.
        if (reseal) runCatching {
            val tmp = File(dir, "$name.tmp")
            tmp.writeBytes(Crypto.seal(bytes, name))
            tmp.renameTo(f)
        }
        return bytes
    }

    override fun write(name: String, bytes: ByteArray) {
        val tmp = File(dir, "$name.tmp")
        // Encrypted and bound to the file name (see Crypto): cannot be read or swapped without the key.
        tmp.writeBytes(Crypto.seal(bytes, name))
        tmp.renameTo(File(dir, name))
    }

    override fun keepAside(name: String): String {
        val backup = File(dir, "$name.broken-${System.currentTimeMillis()}")
        File(dir, name).copyTo(backup, overwrite = true)
        return backup.name
    }

    override val locked: Boolean get() = Crypto.locked
}

/** Once at start (every process: app, tunnel service, tile). */
fun Repo.init(context: Context) = init(AndroidStore(context))
