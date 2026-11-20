package app.borderless.platform

import kotlin.io.encoding.Base64

/** A growing byte array (`java.io.ByteArrayOutputStream` in common code). */
class Bytes(capacity: Int = 64) {
    private var buf = ByteArray(maxOf(capacity, 16))
    var size = 0
        private set

    private fun ensure(more: Int) {
        if (size + more <= buf.size) return
        var n = buf.size * 2
        while (n < size + more) n *= 2
        buf = buf.copyOf(n)
    }

    fun write(b: Int) { ensure(1); buf[size++] = b.toByte() }

    fun write(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
        ensure(length)
        bytes.copyInto(buf, size, offset, offset + length)
        size += length
    }

    fun toByteArray(): ByteArray = buf.copyOf(size)
}

/** Base64: standard or URL-safe alphabet, padding optional on input (links carry both); null when not Base64. */
fun base64Decode(text: String): ByteArray? {
    val s = text.trim().filterNot { it == '\n' || it == '\r' || it == ' ' }
    val url = '-' in s || '_' in s
    val bare = s.trimEnd('=')
    val padded = bare + "=".repeat((4 - bare.length % 4) % 4)
    return runCatching {
        (if (url) Base64.UrlSafe else Base64.Default).withPadding(Base64.PaddingOption.PRESENT_OPTIONAL).decode(padded)
    }.getOrNull()
}

fun base64UrlEncode(bytes: ByteArray): String = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(bytes)

fun base64Encode(bytes: ByteArray): String = Base64.Default.encode(bytes)

/**
 * A query value percent-encoded as `URLEncoder.encode(v, "UTF-8").replace("+", "%20")` did: letters, digits and `.-*_`
 * stay, everything else is %XX of its UTF-8 bytes (a space too).
 */
fun percentEncode(value: String): String = buildString {
    for (b in value.encodeToByteArray()) {
        val c = (b.toInt() and 0xFF)
        val ch = c.toChar()
        if (c < 0x80 && (ch.isLetterOrDigit() || ch in ".-*_")) append(ch)
        else append('%').append(HEX_UP[c shr 4]).append(HEX_UP[c and 15])
    }
}

private const val HEX_UP = "0123456789ABCDEF"

/** IP address literals (`java.net.InetAddress` without DNS). */
object Ip {
    /** 4 or 16 bytes for an IPv4 / IPv6 literal, null for anything else (never a name lookup). */
    fun parse(text: String): ByteArray? = parse4(text) ?: parse6(text)

    fun parse4(text: String): ByteArray? {
        val p = text.split('.')
        if (p.size != 4) return null
        val out = ByteArray(4)
        for (i in 0 until 4) {
            val s = p[i]
            if (s.isEmpty() || s.length > 3 || !s.all { it in '0'..'9' }) return null
            val n = s.toInt()
            if (n > 255) return null
            out[i] = n.toByte()
        }
        return out
    }

    fun parse6(raw: String): ByteArray? {
        var text = raw.removePrefix("[").removeSuffix("]")
        if (':' !in text || text.count { it == ':' } > 7 + (if ("::" in text) 1 else 0)) return null
        text = text.substringBefore('%')
        // A trailing IPv4 part (::ffff:1.2.3.4) becomes two groups.
        val v4 = text.substringAfterLast(':').takeIf { '.' in it }?.let { parse4(it) ?: return null }
        if (v4 != null) {
            val g1 = ((v4[0].toInt() and 0xFF) shl 8) or (v4[1].toInt() and 0xFF)
            val g2 = ((v4[2].toInt() and 0xFF) shl 8) or (v4[3].toInt() and 0xFF)
            text = text.substringBeforeLast(':') + ":" + g1.toString(16) + ":" + g2.toString(16)
        }
        val halves = text.split("::")
        if (halves.size > 2) return null
        fun groups(s: String) = if (s.isEmpty()) emptyList() else s.split(':')
        val head = groups(halves[0])
        val tail = if (halves.size == 2) groups(halves[1]) else emptyList()
        val missing = 8 - head.size - tail.size
        if ((halves.size == 1 && missing != 0) || (halves.size == 2 && missing < 1)) return null
        val all = head + List(if (halves.size == 2) missing else 0) { "0" } + tail
        val out = ByteArray(16)
        for ((i, g) in all.withIndex()) {
            if (g.isEmpty() || g.length > 4) return null
            val n = g.toIntOrNull(16) ?: return null
            out[2 * i] = (n shr 8).toByte()
            out[2 * i + 1] = n.toByte()
        }
        return out
    }

    /** As `InetAddress.getHostAddress()`: dotted IPv4, IPv6 as eight hex groups without compression. */
    fun format(bytes: ByteArray): String? = when (bytes.size) {
        4 -> bytes.joinToString(".") { (it.toInt() and 0xFF).toString() }
        16 -> (0 until 8).joinToString(":") { i -> (((bytes[2 * i].toInt() and 0xFF) shl 8) or (bytes[2 * i + 1].toInt() and 0xFF)).toString(16) }
        else -> null
    }
}

/**
 * A domain name to its ASCII form (`java.net.IDN.toASCII`): labels lower-cased, any label with other than ASCII
 * letters becomes `xn--` + punycode (RFC 3492); null for an empty label or one too long.
 */
fun idnToAscii(name: String): String? {
    if (name.isEmpty()) return null
    val labels = name.split('.')
    return labels.mapIndexed { i, raw ->
        if (raw.isEmpty()) return if (i == labels.lastIndex && i > 0) "" else null
        val label = raw.lowercase()
        val ascii = if (label.all { it.code < 0x80 }) label else "xn--" + (punycode(label) ?: return null)
        if (ascii.length > 63) return null
        ascii
    }.joinToString(".")
}

private fun punycode(input: String): String? {
    val base = 36; val tMin = 1; val tMax = 26; val skew = 38; val damp = 700
    val cps = input.toCodePoints()
    val out = StringBuilder()
    cps.filter { it < 0x80 }.forEach { out.append(it.toChar()) }
    var h = out.length
    val b = h
    if (b > 0) out.append('-')
    var n = 0x80; var delta = 0L; var bias = 72
    fun adapt(d0: Long, num: Int, first: Boolean): Int {
        var d = if (first) d0 / damp else d0 / 2
        d += d / num
        var k = 0
        while (d > ((base - tMin) * tMax) / 2) { d /= base - tMin; k += base }
        return (k + (base - tMin + 1) * d / (d + skew)).toInt()
    }
    fun digit(d: Int) = if (d < 26) 'a' + d else '0' + (d - 26)
    while (h < cps.size) {
        val m = cps.filter { it >= n }.minOrNull() ?: return null
        delta += (m - n).toLong() * (h + 1)
        n = m
        for (c in cps) {
            if (c < n) delta++
            if (c == n) {
                var q = delta
                var k = base
                while (true) {
                    val t = if (k <= bias) tMin else if (k >= bias + tMax) tMax else k - bias
                    if (q < t) break
                    out.append(digit((t + (q - t) % (base - t)).toInt()))
                    q = (q - t) / (base - t)
                    k += base
                }
                out.append(digit(q.toInt()))
                bias = adapt(delta, h + 1, h == b)
                delta = 0
                h++
            }
        }
        delta++; n++
    }
    return out.toString()
}

private fun String.toCodePoints(): List<Int> {
    val out = ArrayList<Int>(length)
    var i = 0
    while (i < length) {
        val c = this[i]
        if (c.isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate()) {
            out.add(((c.code - 0xD800) shl 10) + (this[i + 1].code - 0xDC00) + 0x10000)
            i += 2
        } else {
            out.add(c.code)
            i++
        }
    }
    return out
}

/** The host of an absolute URL as `java.net.URL` gives it (no user info or port; IPv6 keeps its brackets), null when there is no "scheme://". */
fun urlHost(url: String): String? {
    val i = url.indexOf("://").takeIf { it > 0 } ?: return null
    val authority = url.substring(i + 3).takeWhile { it != '/' && it != '?' && it != '#' }.substringAfterLast('@')
    return if (authority.startsWith("[")) authority.substringBefore(']') + "]" else authority.substringBefore(':')
}

/** A header value as the HTTP client gave it (bytes as ISO-8859-1 chars) read again as UTF-8; as it is otherwise. */
fun latin1AsUtf8(raw: String): String =
    if (raw.any { it.code > 0xFF }) raw else ByteArray(raw.length) { raw[it].code.toByte() }.decodeToString()
