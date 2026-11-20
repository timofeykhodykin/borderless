package app.borderless.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import app.borderless.platform.base64Decode
import app.borderless.platform.ecdsaP256Verify
import app.borderless.platform.hex

/**
 * The project's signed approval of the routing lists (SECURITY.md S2, step 2). The lists come from a third party;
 * what the app installs must be exactly what the project looked at and signed: one release of each source ([Source.tag],
 * the files' checksums) and the SHA-256 of every category as the app stores it (renamed to its local name, see
 * [GeoFile.pickHashed]). Published in the project's repository (`lists/manifest.json`), signed by a key that never
 * leaves the developer's computer (`local/lists/sign.py`); its public half is [PUBLIC_KEY].
 *
 * File: `{"payload": base64(JSON), "sig": base64(ECDSA P-256 / SHA-256 over the payload bytes, DER)}`. Pure (tested).
 */
object ListManifest {
    @Serializable
    data class Signed(val payload: String, val sig: String)

    /** [signed]: when it was signed (ms) — a manifest older than the one already used is refused (no rollback). */
    @Serializable
    data class Manifest(val v: Int = 1, val signed: Long = 0, val sources: Map<String, Source> = emptyMap())

    /** One source of `regions.json` (by its URL), pinned to the release [tag]; [files] by file name. */
    @Serializable
    data class Source(val tag: String, val files: Map<String, Dat> = emptyMap())

    /** A file's own checksum (what `<file>.sha256sum` says) and its categories' SHA-256 by local upper-case name. */
    @Serializable
    data class Dat(val sha256: String, val categories: Map<String, String> = emptyMap())

    /** Public half of the signing key (X.509 SubjectPublicKeyInfo, P-256). */
    const val PUBLIC_KEY =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE+agQxdoC3SCbQUI1Jp51CqjQxxWcrYl7r3OTspHXp+1IpB1gYpe2/nPdM5zxUxvP9ViNlQVOzpV6zj8aNfsYlA=="

    private val json = Json { ignoreUnknownKeys = true }

    /** [text] parsed, if its signature checks out with [publicKey]; null otherwise (or if it doesn't parse). */
    fun verify(text: String, publicKey: String = PUBLIC_KEY): Manifest? = runCatching {
        val file = json.decodeFromString(Signed.serializer(), text)
        val payload = base64Decode(file.payload)!!
        val ok = ecdsaP256Verify(base64Decode(publicKey)!!, payload, base64Decode(file.sig)!!)
        if (ok) json.decodeFromString(Manifest.serializer(), payload.decodeToString()) else null
    }.getOrNull()

    /** A GitHub "latest release" folder pinned to [tag]; other sources as they are. */
    fun pinned(source: String, tag: String): String =
        Regex("^(https://github\\.com/[^/]+/[^/]+)/releases/latest/download/$").find(source)
            ?.let { "${it.groupValues[1]}/releases/download/$tag/" } ?: source

    /** Where the project publishes it ([project] = "owner/name"). */
    fun url(project: String) = "https://raw.githubusercontent.com/$project/main/lists/manifest.json"

    fun sha256(bytes: ByteArray): String = app.borderless.platform.sha256(bytes).hex()

    /**
     * The project's repository, "owner/name" — everything the app reads from GitHub goes through it (updates, this
     * manifest, "Report on GitHub"). Kept out of the binaries as plain text.
     */
    fun project(): String {
        val key = byteArrayOf(0x5a, 0x13, 0xc7.toByte(), 0x2e, 0x91.toByte(), 0x44, 0x0b, 0xe8.toByte())
        val enc = byteArrayOf(46, 122, -86, 65, -9, 33, 114, -125, 50, 124, -93, 87, -6, 45, 101, -57, 56, 124, -75, 74, -12, 54, 103, -115, 41, 96)
        return ByteArray(enc.size) { i -> (enc[i].toInt() xor key[i % key.size].toInt()).toByte() }.decodeToString()
    }
}
