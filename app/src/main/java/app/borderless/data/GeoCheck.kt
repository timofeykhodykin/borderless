package app.borderless.data

import app.borderless.data.GeoFile.writeVarint
import java.io.ByteArrayOutputStream
import java.net.InetAddress

/**
 * Limits on the routing-list categories whose entries go around the tunnel (SECURITY.md S2). They come from a third
 * party and update by themselves, so one bad update could send everything direct while the app shows a server.
 * Before such a category is installed (and once for the files already here) entries that reach far beyond a zone
 * are left out, and a category that changed suspiciously is not taken at all (the previous one stays). Not a
 * guarantee: a list can still name a single wrong site; only signed lists would catch that. Pure (unit-tested).
 */
object GeoCheck {
    /** What to install: [raw] (null = nothing new, keep what is there), the entries left out, and why it was refused. */
    class Result(val raw: ByteArray?, val dropped: List<String>, val refused: String?)

    /** Ordinary names no direct list may reach: a keyword or a pattern that matches one reaches most of the internet. */
    private val PROBES = listOf(
        "www.example.com", "mail.example.org", "cdn.example.net", "api.example.io", "app.example.dev", "example.info",
        "static.example.co.uk", "m.example.xyz", "example.me", "img.example.tv", "example.co", "s3.example.cloud",
    )

    /** Special-use names (RFC 6761 and common local ones): the only bare top-level names the local list may hold. */
    val LOCAL_TLDS = setOf("localhost", "local", "localdomain", "lan", "internal", "home", "arpa", "example", "invalid", "test", "intranet", "corp", "private")

    /** Reserved address ranges (RFC 6890 and the like): everything the local list may hold. */
    val RESERVED = listOf(
        "0.0.0.0/8", "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8", "169.254.0.0/16", "172.16.0.0/12", "192.0.0.0/24",
        "192.0.2.0/24", "192.88.99.0/24", "192.168.0.0/16", "198.18.0.0/15", "198.51.100.0/24", "203.0.113.0/24",
        "224.0.0.0/3", "::/127", "fc00::/7", "fe80::/10", "ff00::/8",
    ).map(::cidr)

    private const val MIN_KEYWORD = 4
    private const val MIN_V4 = 8
    private const val MIN_V6 = 16
    /** At most 1/8 of all IPv4 addresses in one direct category (a large country holds a few percent). */
    private const val MAX_V4 = 1L shl 29
    /** An update may at most double a category, unless the growth is small in absolute terms. */
    private const val GROWTH_ITEMS = 1000
    private const val GROWTH_V4 = 1L shl 24

    /**
     * A category of sites going direct. [bareTld]: whether a `domain:` entry without a dot (a whole top-level name) may
     * stay — the zone's own ones. [old]: the category installed now, if any.
     */
    fun sites(raw: ByteArray, old: ByteArray?, bareTld: (String) -> Boolean): Result {
        val dropped = ArrayList<String>()
        val kept = filter(raw) { item ->
            var type = 0L
            var value = ""
            DatFile.fields(item) { f, v, b -> if (f == 1 && b == null) type = v; if (f == 2 && b != null) value = String(b) }
            val bad = when (type) {
                0L -> value.length < MIN_KEYWORD || PROBES.any { value.lowercase() in it }
                1L -> runCatching { Regex(value) }.getOrNull()?.let { r -> PROBES.any { r.containsMatchIn(it) } } ?: true
                2L -> '.' !in value.trim('.') && !bareTld(value.trim('.').lowercase())
                else -> false
            }
            if (bad) dropped += (when (type) { 0L -> "keyword:"; 1L -> "regexp:"; 2L -> "domain:"; else -> "" } + value)
            !bad
        }
        val refused = old?.let { growth(DatFile.items(raw).size, DatFile.items(it).size, 0, 0) }
        return Result(if (refused != null) null else kept, dropped, refused)
    }

    /** A category of address ranges going direct; [local]: the reserved-ranges list (only those may be in it). */
    fun addresses(raw: ByteArray, old: ByteArray?, local: Boolean): Result {
        if (inverse(raw)) return Result(null, emptyList(), "inverse match")
        val dropped = ArrayList<String>()
        val kept = filter(raw) { item ->
            val range = range(item)
            val bad = when {
                range == null -> true
                local -> RESERVED.none { it.contains(range) }
                else -> range.prefix < (if (range.ip.size == 4) MIN_V4 else MIN_V6)
            }
            if (bad) dropped += range?.toString() ?: "?"
            !bad
        }
        val cover = v4(kept)
        val refused = when {
            !local && cover > MAX_V4 -> "covers ${cover * 100 / (1L shl 32)}% of IPv4"
            old != null -> growth(DatFile.items(raw).size, DatFile.items(old).size, cover, v4(old))
            else -> null
        }
        return Result(if (refused != null) null else kept, dropped, refused)
    }

    private fun growth(items: Int, oldItems: Int, cover: Long, oldCover: Long): String? = when {
        items > 2 * oldItems && items - oldItems > GROWTH_ITEMS -> "entries $oldItems → $items"
        cover > 2 * oldCover && cover - oldCover > GROWTH_V4 -> "IPv4 addresses $oldCover → $cover"
        else -> null
    }

    /** IPv4 addresses a category covers (overlaps counted twice: an upper bound). */
    private fun v4(entry: ByteArray): Long =
        DatFile.items(entry).mapNotNull(::range).filter { it.ip.size == 4 }.sumOf { 1L shl (32 - it.prefix) }

    /** A geoip entry with `inverse_match` set: everything *outside* its ranges would match. */
    private fun inverse(entry: ByteArray): Boolean {
        var inv = false
        DatFile.fields(body(entry)) { f, v, b -> if (f == 3 && b == null && v != 0L) inv = true }
        return inv
    }

    private fun body(entry: ByteArray): ByteArray {
        val r = DatFile.Reader(entry)
        r.varint()
        val len = r.varint().toInt()
        return entry.copyOfRange(r.pos, r.pos + len)
    }

    /** [entry] with the items [keep] rejects left out (every other field kept as it was). */
    private inline fun filter(entry: ByteArray, keep: (ByteArray) -> Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        var changed = false
        DatFile.fields(body(entry)) { f, v, b ->
            if (b == null) { out.writeVarint((f shl 3).toLong()); out.writeVarint(v); return@fields }
            if (f == 2 && !keep(b)) { changed = true; return@fields }
            out.writeVarint(((f shl 3) or 2).toLong()); out.writeVarint(b.size.toLong()); out.write(b)
        }
        if (!changed) return entry
        val body = out.toByteArray()
        return ByteArrayOutputStream().apply { write(0x0A); writeVarint(body.size.toLong()); write(body) }.toByteArray()
    }

    class Range(val ip: ByteArray, val prefix: Int) {
        fun contains(o: Range): Boolean {
            if (o.ip.size != ip.size || o.prefix < prefix) return false
            for (bit in 0 until prefix) {
                val m = 0x80 ushr (bit % 8)
                if ((ip[bit / 8].toInt() and m) != (o.ip[bit / 8].toInt() and m)) return false
            }
            return true
        }

        override fun toString() = "${InetAddress.getByAddress(ip).hostAddress}/$prefix"
    }

    private fun range(item: ByteArray): Range? {
        var ip: ByteArray? = null
        var prefix = -1L
        DatFile.fields(item) { f, v, b -> if (f == 1 && b != null) ip = b; if (f == 2 && b == null) prefix = v }
        val b = ip?.takeIf { it.size == 4 || it.size == 16 } ?: return null
        val p = if (prefix < 0) b.size * 8 else prefix.toInt()
        return if (p in 0..b.size * 8) Range(b, p) else null
    }

    private fun cidr(s: String) = Range(InetAddress.getByName(s.substringBefore('/')).address, s.substringAfter('/').toInt())
}
