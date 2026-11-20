package app.borderless.platform

/** Plain file access for the shared code (the routing lists, their state): paths are absolute. */
expect object Files {
    /** The whole file, null when it can't be read. */
    fun readBytes(path: String): ByteArray?

    /** Writes the whole file (created or replaced); throws when it can't. */
    fun writeBytes(path: String, bytes: ByteArray)

    /** Moves [from] over [to] (replacing it); false when it can't. */
    fun rename(from: String, to: String): Boolean

    /** The file's size in bytes, -1 when there is none. */
    fun size(path: String): Long

    /** [length] bytes from [offset]; throws when the file is shorter. */
    fun readRange(path: String, offset: Long, length: Int): ByteArray

    /** Creates the folder and its parents. */
    fun makeDirs(path: String)
}

fun Files.readText(path: String): String? = readBytes(path)?.decodeToString()

fun Files.writeText(path: String, text: String) = writeBytes(path, text.encodeToByteArray())

/**
 * Whether [signature] (DER) is a valid ECDSA P-256 / SHA-256 signature of [data] by the key [publicKey]
 * (X.509 SubjectPublicKeyInfo, DER).
 */
expect fun ecdsaP256Verify(publicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean
