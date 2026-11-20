package app.borderless.platform

import java.io.File
import java.io.RandomAccessFile
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

actual object Files {
    actual fun readBytes(path: String): ByteArray? = runCatching { File(path).readBytes() }.getOrNull()

    actual fun writeBytes(path: String, bytes: ByteArray) = File(path).writeBytes(bytes)

    actual fun rename(from: String, to: String): Boolean = File(from).renameTo(File(to))

    actual fun size(path: String): Long = File(path).takeIf { it.isFile }?.length() ?: -1

    actual fun readRange(path: String, offset: Long, length: Int): ByteArray =
        RandomAccessFile(path, "r").use { raf -> ByteArray(length).also { raf.seek(offset); raf.readFully(it) } }

    actual fun makeDirs(path: String) {
        File(path).mkdirs()
    }
}

actual fun ecdsaP256Verify(publicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean = runCatching {
    val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicKey))
    Signature.getInstance("SHA256withECDSA").run {
        initVerify(key)
        update(data)
        verify(signature)
    }
}.getOrDefault(false)
