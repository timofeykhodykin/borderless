package app.borderless.data

/**
 * Where [Repo]'s files live and how they are kept: Android seals each one with the Keystore's key (`Crypto`), the
 * Mac's service keeps them in its root-only folder. Names are plain file names ("servers.json").
 */
interface DataStore {
    /** The file's contents; null when there is none, or when it can't be opened now and must stay as it is. Throws when damaged. */
    fun read(name: String): ByteArray?

    /** Replaces the file at once (a temporary file renamed over it). */
    fun write(name: String, bytes: ByteArray)

    /** Keeps a damaged file aside so the next save doesn't overwrite it for good; the copy's name. */
    fun keepAside(name: String): String

    /** Stored data can't be opened now: nothing may be written over it. */
    val locked: Boolean get() = false
}
