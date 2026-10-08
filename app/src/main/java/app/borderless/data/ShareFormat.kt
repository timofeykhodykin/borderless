package app.borderless.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * What can be shared, and the two ways to write it:
 * - [plain]: share links, one per line (subscriptions as their URL). Every client understands it.
 * - [encode]: `borderless://import?d=…`, compressed JSON that also keeps group names and hidden
 *   servers. There is no common format for groups of configs, so this one is our own.
 */
object ShareFormat {
    const val PREFIX = "borderless://import?d="

    @Serializable
    data class Bundle(val v: Int = 1, val groups: List<G> = emptyList())

    /** [default]: belongs in the receiver's default group rather than a new one. */
    @Serializable
    data class G(val name: String? = null, val default: Boolean = false, val servers: List<S> = emptyList(), val subs: List<Sub> = emptyList())

    @Serializable
    data class S(val l: String, val off: Boolean = false)

    /** [hidden]: links (without the #name part) of the subscription's servers that were hidden. */
    @Serializable
    data class Sub(val url: String, val name: String? = null, val hidden: List<String> = emptyList())

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    /** Share links of visible servers, then subscription URLs. Hidden servers are left out. */
    fun plain(b: Bundle): String = b.groups.flatMap { g ->
        g.servers.filterNot { it.off }.map { it.l } + g.subs.map { it.url }
    }.joinToString("\n")

    fun encode(b: Bundle): String {
        val bytes = json.encodeToString(Bundle.serializer(), b).toByteArray()
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true).apply { setInput(bytes); finish() }
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf))
        deflater.end()
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray())
    }

    private const val MAX_ENCODED = 2_000_000
    private const val MAX_INFLATED = 8_000_000

    fun decode(text: String): Bundle? = runCatching {
        val data = text.trim().substringAfter(PREFIX).substringBefore('&').trim()
        require(data.length <= MAX_ENCODED) { "share data too long" }
        val raw = Base64.getUrlDecoder().decode(data)
        val inflater = Inflater(true).apply { setInput(raw) }
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!inflater.finished()) {
            val n = inflater.inflate(buf)
            if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
            out.write(buf, 0, n)
            // A few bytes can inflate to gigabytes ("zip bomb"): stop well before that.
            require(out.size() <= MAX_INFLATED) { "share data too large" }
        }
        inflater.end()
        json.decodeFromString(Bundle.serializer(), out.toString(Charsets.UTF_8.name()))
    }.getOrNull()

    fun isInternal(text: String) = text.trim().startsWith(PREFIX)

    /** Link without its `#name` part: identifies a server across renames and re-imports. */
    fun key(link: String): String = if (link.trimStart().startsWith("{")) link.trim() else link.substringBefore('#').trim()

    fun serverCount(b: Bundle) = b.groups.sumOf { it.servers.size }
    fun subCount(b: Bundle) = b.groups.sumOf { it.subs.size }
}
