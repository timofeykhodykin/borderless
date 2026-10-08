package app.borderless.config

import java.util.Base64

/**
 * Lenient parser for share links (`scheme://user@host:port/path?query#name`).
 *
 * java.net.URI rejects many links found in the wild (unescaped spaces, `|`, raw unicode),
 * so links are split by hand and every component is percent-decoded separately.
 */
class ShareUrl private constructor(
    val scheme: String,
    val userInfo: String?,
    val host: String,
    val port: Int?,
    val path: String,
    val query: Map<String, String>,
    val fragment: String,
) {
    fun q(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { k -> query[k]?.takeIf { it.isNotBlank() } }

    companion object {
        fun parse(link: String): ShareUrl {
            val schemeEnd = link.indexOf("://")
            require(schemeEnd > 0) { "not a link" }
            val scheme = link.substring(0, schemeEnd).lowercase()
            var rest = link.substring(schemeEnd + 3)

            var fragment = ""
            rest.indexOf('#').takeIf { it >= 0 }?.let {
                fragment = decodeName(rest.substring(it + 1))
                rest = rest.substring(0, it)
            }
            var rawQuery = ""
            rest.indexOf('?').takeIf { it >= 0 }?.let {
                rawQuery = rest.substring(it + 1)
                rest = rest.substring(0, it)
            }
            var path = ""
            rest.indexOf('/').takeIf { it >= 0 }?.let {
                path = rest.substring(it)
                rest = rest.substring(0, it)
            }
            var userInfo: String? = null
            rest.lastIndexOf('@').takeIf { it >= 0 }?.let {
                userInfo = percentDecode(rest.substring(0, it))
                rest = rest.substring(it + 1)
            }
            val host: String
            var port: Int? = null
            if (rest.startsWith("[")) {
                val close = rest.indexOf(']')
                require(close > 0) { "bad IPv6 host" }
                host = rest.substring(1, close)
                rest.substring(close + 1).removePrefix(":").takeIf { it.isNotEmpty() }?.let { port = it.toInt() }
            } else {
                val colon = rest.lastIndexOf(':')
                if (colon >= 0) {
                    host = rest.substring(0, colon)
                    port = rest.substring(colon + 1).trim().toIntOrNull()
                } else {
                    host = rest
                }
            }
            val query = LinkedHashMap<String, String>()
            rawQuery.split('&').filter { it.isNotEmpty() }.forEach { part ->
                val eq = part.indexOf('=')
                if (eq < 0) query[percentDecode(part)] = ""
                else query[percentDecode(part.substring(0, eq))] = percentDecode(part.substring(eq + 1))
            }
            return ShareUrl(scheme, userInfo, percentDecode(host), port, percentDecode(path), query, fragment.trim())
        }

        /** Percent-decoding that keeps `+` as is (it is common in passwords and base64). */
        fun percentDecode(s: String): String {
            if ('%' !in s) return s
            val out = java.io.ByteArrayOutputStream(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '%' && i + 2 < s.length && isHex(s[i + 1]) && isHex(s[i + 2])) {
                    out.write(s.substring(i + 1, i + 3).toInt(16))
                    i += 3
                } else {
                    out.write(c.toString().toByteArray(Charsets.UTF_8))
                    i++
                }
            }
            return out.toString(Charsets.UTF_8.name())
        }

        private fun decodeName(s: String): String {
            val spaced = if ("%20" in s || ' ' in s) s else s.replace('+', ' ')
            return percentDecode(spaced)
        }

        private fun isHex(c: Char) = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

        /** Decodes standard or URL-safe base64, with or without padding. Returns null if it is not base64. */
        fun decodeBase64(s: String): String? {
            val clean = s.trim().replace("\n", "").replace("\r", "").replace(" ", "")
            if (clean.isEmpty()) return null
            val padded = clean.trimEnd('=').let { it + "=".repeat((4 - it.length % 4) % 4) }
            return runCatching {
                val bytes = if ('-' in padded || '_' in padded) Base64.getUrlDecoder().decode(padded)
                else Base64.getDecoder().decode(padded)
                String(bytes, Charsets.UTF_8)
            }.getOrNull()
        }
    }
}
