package app.borderless.data

import java.io.ByteArrayOutputStream

/**
 * Xray's geoip.dat / geosite.dat: a protobuf list of entries (field 1, length-delimited), each starting with its
 * category code (field 1, a string). The app keeps only the categories its lists use (see [Regions]); this reads
 * and writes such files without a protobuf library, and can pick entries out of a remote file read in ranges
 * (only the wanted entries and the small headers between them are downloaded, see [pick]).
 */
object GeoFile {
    /** Reads [len] bytes at [offset] of a file (local or remote). */
    fun interface Source {
        fun read(offset: Long, len: Int): ByteArray
    }

    class Entry(val code: String, val raw: ByteArray)

    /** Entries of a whole file in memory, in file order. Throws on a damaged file. */
    fun entries(bytes: ByteArray): List<Entry> {
        val out = ArrayList<Entry>()
        var pos = 0L
        while (pos < bytes.size) {
            val h = header(bytes, 0, pos, bytes.size.toLong())
            out += Entry(h.code, bytes.copyOfRange(pos.toInt(), (pos + h.total).toInt()))
            pos += h.total
        }
        return out
    }

    /**
     * The entries of [wanted] categories (upper case) from a file of [size] bytes, by code. Every entry's header
     * is read (a few bytes), bodies only of the wanted ones; the rest is skipped. Throws if the structure doesn't
     * add up to [size] (a damaged or changed file).
     */
    fun pick(src: Source, size: Long, wanted: Set<String>): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        var pos = 0L
        while (pos < size) {
            val head = src.read(pos, minOf(HEADER_BYTES.toLong(), size - pos).toInt())
            val h = header(head, pos, pos, size)
            val code = h.code.uppercase()
            if (code in wanted && code !in out) out[code] = src.read(pos, h.total.toInt())
            pos += h.total
        }
        check(pos == size) { "entries end at $pos, file has $size bytes" }
        return out
    }

    /** Codes (upper case) of a file's entries, reading only their headers. */
    fun codes(src: Source, size: Long): Set<String> {
        val out = HashSet<String>()
        var pos = 0L
        while (pos < size) {
            val h = header(src.read(pos, minOf(HEADER_BYTES.toLong(), size - pos).toInt()), pos, pos, size)
            out += h.code.uppercase()
            pos += h.total
        }
        return out
    }

    /**
     * Categories in the files the core uses ("geoip" / "geosite" → codes); null until known (unit tests: all
     * allowed). Xray refuses to start with a rule naming a category its file lacks, so rules are checked here.
     */
    @Volatile
    var installed: Map<String, Set<String>>? = null

    /** Whether a routing rule can be used: a `geosite:` / `geoip:` category the files have, or any other rule. */
    fun usable(rule: String): Boolean {
        val have = installed ?: return true
        val kind = rule.substringBefore(':', "").lowercase()
        if (kind != "geosite" && kind != "geoip") return true
        // "geosite:category@attr" and "ext:" forms: the part before '@'.
        val code = rule.substringAfter(':').substringBefore('@').uppercase()
        return code in have[kind].orEmpty()
    }

    /**
     * Like [pick], but looking categories up by [Regions.hash] of their upper-case name ([wanted]: hash → local name)
     * and returning each renamed to its local name (see [rename]).
     */
    fun pickHashed(src: Source, size: Long, wanted: Map<String, String>): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        var pos = 0L
        while (pos < size) {
            val head = src.read(pos, minOf(HEADER_BYTES.toLong(), size - pos).toInt())
            val h = header(head, pos, pos, size)
            val local = wanted[Regions.hash(h.code.uppercase())]
            if (local != null && local.uppercase() !in out) out[local.uppercase()] = rename(src.read(pos, h.total.toInt()), local.uppercase())
            pos += h.total
        }
        check(pos == size) { "entries end at $pos, file has $size bytes" }
        return out
    }

    /** An entry with its category name replaced by [code] (the rest of the entry is kept byte for byte). */
    fun rename(raw: ByteArray, code: String): ByteArray {
        var i = 0
        fun varint(): Long {
            var r = 0L
            var shift = 0
            while (true) {
                val b = raw[i++].toInt() and 0xFF
                r = r or ((b and 0x7F).toLong() shl shift)
                if (b < 0x80) return r
                shift += 7
            }
        }
        varint() // entry tag
        varint() // entry length
        varint() // code tag
        val codeLen = varint().toInt()
        val rest = raw.copyOfRange(i + codeLen, raw.size)
        val name = code.toByteArray()
        val body = ByteArrayOutputStream().apply { write(0x0A); writeVarint(name.size.toLong()); write(name); write(rest) }.toByteArray()
        return ByteArrayOutputStream().apply { write(0x0A); writeVarint(body.size.toLong()); write(body) }.toByteArray()
    }

    private fun ByteArrayOutputStream.writeVarint(v: Long) {
        var x = v
        while (x >= 0x80) { write(((x and 0x7F) or 0x80).toInt()); x = x ushr 7 }
        write(x.toInt())
    }

    /** A file made of [entries] (raw, as read). */
    fun write(entries: Collection<ByteArray>): ByteArray = ByteArrayOutputStream().apply { entries.forEach { write(it) } }.toByteArray()

    private class Header(val code: String, val total: Long)

    /** The entry starting at [pos] of the file, from [buf] holding the file's bytes from [bufStart] on. */
    private fun header(buf: ByteArray, bufStart: Long, pos: Long, size: Long): Header {
        var i = (pos - bufStart).toInt()
        fun byte(): Int {
            check(i < buf.size) { "truncated entry header at $pos" }
            return buf[i++].toInt() and 0xFF
        }
        fun varint(): Long {
            var r = 0L
            var shift = 0
            while (true) {
                val b = byte()
                r = r or ((b and 0x7F).toLong() shl shift)
                if (b < 0x80) return r
                shift += 7
                check(shift < 64) { "bad varint at $pos" }
            }
        }
        check(varint() == 0x0AL) { "unexpected field at $pos" }
        val len = varint()
        val bodyStart = i
        val total = (bodyStart - (pos - bufStart).toInt()) + len
        check(pos + total <= size) { "entry at $pos runs past the end" }
        check(varint() == 0x0AL) { "entry at $pos has no code" }
        val codeLen = varint().toInt()
        check(codeLen in 1..64) { "bad code length at $pos" }
        check(i + codeLen <= buf.size) { "truncated code at $pos" }
        val code = String(buf, i, codeLen, Charsets.UTF_8)
        return Header(code, total)
    }

    /** Enough for an entry's length and code. */
    private const val HEADER_BYTES = 96

    /**
     * Whether the lists are due for a check: every [days] (0 = never), twice as long on mobile data or in the
     * battery saving mode (both: four times).
     */
    fun due(lastCheck: Long, days: Int, metered: Boolean, eco: Boolean, now: Long): Boolean {
        if (days <= 0) return false
        val every = days * 86_400_000L * (if (metered) 2 else 1) * (if (eco) 2 else 1)
        return now - lastCheck >= every
    }
}
