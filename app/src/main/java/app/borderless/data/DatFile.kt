package app.borderless.data

/**
 * Xray's own list files (`geosite.dat` / `geoip.dat` and the regional packages made the same way, e.g. a
 * country's `.dat`): categories of sites or of address ranges, often tens of thousands of entries. A zone takes
 * chosen categories as a file of its own next to the routing lists ([subset], named by [fileName]) and refers to
 * them as `ext:<file>:<category>` — the core reads them like its own lists, so a big category costs no config size
 * (the config is parsed at every core start). [rules] turns a category into plain entries instead (tests, small
 * lists).
 *
 * The format is protobuf: a list of entries {1: category name, 2: repeated item}; a site item is
 * {1: type (0 keyword, 1 regexp, 2 domain, 3 full), 2: value, 3: attributes}, an address item {1: ip bytes, 2: prefix}.
 */
object DatFile {
    enum class Kind { SITES, ADDRESSES }

    class Category(val code: String, val size: Int, internal val raw: ByteArray)

    class Parsed(val kind: Kind, val categories: List<Category>)

    /** The file's categories (and whether it lists sites or addresses); null if it isn't such a file. */
    fun read(bytes: ByteArray): Parsed? = runCatching {
        val entries = GeoFile.entries(bytes)
        if (entries.isEmpty()) return null
        var kind: Kind? = null
        val cats = entries.map { e ->
            val items = items(e.raw)
            if (kind == null) items.firstOrNull()?.let { kind = kindOf(it) }
            Category(e.code, items.size, e.raw)
        }
        Parsed(kind ?: Kind.SITES, cats)
    }.getOrNull()

    /** A file holding only the [codes] categories of [p] (entries byte for byte, as Xray reads them). */
    fun subset(p: Parsed, codes: Set<String>): ByteArray = GeoFile.write(p.categories.filter { it.code in codes }.map { it.raw })

    /** The file name a zone's categories are kept under (".geosite" / ".geoip": what [ZoneFile.isAddress] goes by). */
    fun fileName(zoneId: String, kind: Kind): String =
        "$zoneId-${java.util.UUID.randomUUID().toString().take(4)}.${if (kind == Kind.SITES) "geosite" else "geoip"}.dat"

    /** The zone rule for [code] of the file [file]. */
    fun rule(file: String, code: String): String = "ext:$file:${code.uppercase()}"

    /** The entries of [category] as zone rules (unknown / broken items are left out). */
    fun rules(kind: Kind, category: Category): List<String> = items(category.raw).mapNotNull { item ->
        if (kind == Kind.ADDRESSES) address(item) else site(item)
    }

    // An address item starts with its ip bytes (field 1, length-delimited); a site item with its type (field 1,
    // varint) or, for the default type, its value (field 2).
    private fun kindOf(item: ByteArray): Kind = if ((item.firstOrNull()?.toInt() ?: 0) == 0x0A) Kind.ADDRESSES else Kind.SITES

    private fun site(item: ByteArray): String? {
        var type = 0L
        var value: String? = null
        fields(item) { field, v, bytes -> if (field == 1 && bytes == null) type = v; if (field == 2 && bytes != null) value = String(bytes) }
        val s = value ?: return null
        return ZoneFile.entry(
            when (type) {
                0L -> "keyword:$s"
                1L -> "regexp:$s"
                2L -> "domain:$s"
                3L -> "full:$s"
                else -> return null
            }
        )
    }

    private fun address(item: ByteArray): String? {
        var ip: ByteArray? = null
        var prefix = -1L
        fields(item) { field, v, bytes -> if (field == 1 && bytes != null) ip = bytes; if (field == 2 && bytes == null) prefix = v }
        val b = ip ?: return null
        val text = when (b.size) {
            4 -> b.joinToString(".") { (it.toInt() and 0xFF).toString() }
            16 -> java.net.InetAddress.getByAddress(b).hostAddress ?: return null
            else -> return null
        }
        val bits = if (prefix < 0) b.size * 8L else prefix
        return ZoneFile.ip("$text/$bits")
    }

    /** The items (field 2) of one entry, [raw] = the entry with its own tag and length. */
    internal fun items(raw: ByteArray): List<ByteArray> {
        val r = Reader(raw)
        r.varint() // the entry's tag
        val end = r.varint().toInt() + r.pos
        val out = ArrayList<ByteArray>()
        val body = raw.copyOfRange(r.pos, end)
        fields(body) { field, _, bytes -> if (field == 2 && bytes != null) out += bytes }
        return out
    }

    /** Every field of a message: varints with their value, length-delimited ones with their bytes. */
    internal inline fun fields(msg: ByteArray, on: (field: Int, value: Long, bytes: ByteArray?) -> Unit) {
        val r = Reader(msg)
        while (r.pos < msg.size) {
            val tag = r.varint()
            val field = (tag ushr 3).toInt()
            when ((tag and 7).toInt()) {
                0 -> on(field, r.varint(), null)
                2 -> {
                    val len = r.varint().toInt()
                    require(len >= 0 && r.pos + len <= msg.size) { "bad length" }
                    on(field, 0, msg.copyOfRange(r.pos, r.pos + len))
                    r.pos += len
                }
                5 -> r.pos += 4
                1 -> r.pos += 8
                else -> error("wire type ${tag and 7}")
            }
        }
    }

    internal class Reader(val b: ByteArray) {
        var pos = 0
        fun varint(): Long {
            var r = 0L
            var shift = 0
            while (true) {
                val x = b[pos++].toInt() and 0xFF
                r = r or ((x and 0x7F).toLong() shl shift)
                if (x < 0x80) return r
                shift += 7
                require(shift < 64) { "bad varint" }
            }
        }
    }
}
