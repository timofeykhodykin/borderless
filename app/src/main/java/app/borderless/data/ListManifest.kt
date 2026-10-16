package app.borderless.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

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
        val payload = Base64.getDecoder().decode(file.payload)
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKey)))
        val ok = Signature.getInstance("SHA256withECDSA").run {
            initVerify(key)
            update(payload)
            verify(Base64.getDecoder().decode(file.sig))
        }
        if (ok) json.decodeFromString(Manifest.serializer(), String(payload, Charsets.UTF_8)) else null
    }.getOrNull()

    /** A GitHub "latest release" folder pinned to [tag]; other sources as they are. */
    fun pinned(source: String, tag: String): String =
        Regex("^(https://github\\.com/[^/]+/[^/]+)/releases/latest/download/$").find(source)
            ?.let { "${it.groupValues[1]}/releases/download/$tag/" } ?: source

    /** Where the project publishes it ([project] = "owner/name"). */
    fun url(project: String) = "https://raw.githubusercontent.com/$project/main/lists/manifest.json"

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
