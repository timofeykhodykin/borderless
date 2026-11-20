package app.borderless.data

import app.borderless.platform.Host
import app.borderless.platform.LockedMap
import app.borderless.platform.Locales

/** Detects a server's country from its name, and formats country codes for display. */
object Countries {
    private val codes: Set<String> by lazy { Locales.isoCountries() }

    /** Extra names, cities, common-word exceptions: `assets/countries.json` (no place names in the code). */
    @kotlinx.serialization.Serializable
    private data class Table(
        val nameLanguages: List<String> = listOf("en"),
        val aliases: Map<String, List<String>> = emptyMap(),
        val stopWords: Set<String> = emptySet(),
        val ambiguousCodes: Set<String> = emptySet(),
        val codeAliases: Map<String, String> = emptyMap(),
    )

    private val table: Table by lazy {
        Host.asset("countries.json")?.let {
            runCatching { StoreJson.decodeFromString(Table.serializer(), it) }.onFailure { e -> Host.report(e, "reading countries.json") }.getOrNull()
        } ?: Table()
    }

    /** In [AppSettings.countries]: the servers whose country isn't known. */
    const val UNKNOWN = "-"

    /** A server's country as [AppSettings.countries] names it: its code, or [UNKNOWN]. */
    fun of(s: Server): String = s.country?.takeIf { it.length == 2 }?.uppercase() ?: UNKNOWN

    /** Name hints never suppress a pending IP lookup, including records from older releases. */
    fun needsIpLookup(s: Server): Boolean = !s.countryManual && (!s.countryByIp || s.countryLookupVersion < 2) && s.host.isNotBlank() && !s.limited

    fun fromIp(s: Server, code: String): Server =
        if (s.countryManual) s else s.copy(country = code, countryByIp = true, countryLookupVersion = 2)

    fun nameFallback(s: Server): Server =
        if (s.countryManual || (s.countryByIp && s.countryLookupVersion >= 2)) s
        else s.copy(country = detect(s.name), countryByIp = false, countryLookupVersion = 0)

    /** A code of [AppSettings.countries] by name ("Unknown country" for [UNKNOWN]). */
    fun label(code: String): String = if (code == UNKNOWN) name(null) else name(code)

    /** Several of them as one line, alphabetically. */
    fun labels(codes: Collection<String>): String =
        Locales.sorted(codes.map(::label), Host.language()).joinToString(", ")

    /** Country name in the UI language; null/unknown codes give "Unknown country". */
    fun name(code: String?): String {
        if (code.isNullOrEmpty()) return Host.string("country_unknown") ?: "?"
        return shortNames()[code] ?: Locales.countryName(code, Host.language()).ifEmpty { code }
    }

    fun flag(code: String?): String {
        if (code == null || code.length != 2) return "🏳️"
        // Regional indicator symbols U+1F1E6… as UTF-16: the high surrogate D83C, then DDE6 + the letter.
        fun indicator(c: Char) = charArrayOf('\uD83C', (0xDDE6 + (c.uppercaseChar() - 'A')).toChar()).concatToString()
        return indicator(code[0]) + indicator(code[1])
    }

    /** Server name without a leading flag emoji (two regional indicators or 🏳️; the flag is shown separately). */
    fun stripFlag(name: String): String {
        var i = 0
        while (i < name.length && name[i].isWhitespace()) i++
        val start = i
        if (regional(name, i) && regional(name, i + 2)) i += 4
        else if (name.startsWith("\uD83C\uDFF3", i)) { i += 2; if (name.startsWith("\uFE0F", i)) i++ }
        if (i == start) return name
        while (i < name.length && name[i].isWhitespace()) i++
        return name.substring(i).ifEmpty { name }
    }

    /** A regional indicator symbol (U+1F1E6…U+1F1FF) at [i]. */
    private fun regional(s: String, i: Int) = i + 1 < s.length && s[i] == '\uD83C' && s[i + 1] in '\uDDE6'..'\uDDFF'

    /** Best-effort detection; returns null when the name says nothing about the location. */
    fun detect(name: String): String? {
        flagCode(name)?.let { return it }
        val lower = name.lowercase()
        val words = Regex("[\\p{L}]+(?:[-'][\\p{L}]+)*").findAll(lower).map { it.value }.toList()

        // Multi-word and single-word names (in the table's languages), cities and aliases.
        for (n in 3 downTo 1) {
            for (i in 0..words.size - n) {
                val phrase = words.subList(i, i + n).joinToString(" ")
                nameIndex[phrase]?.let { return it }
            }
        }
        // Bare ISO codes written in upper case: "DE Germany", "[NL]", "US-1".
        Regex("(?<![A-Za-z])([A-Z]{2})(?![A-Za-z])").findAll(name).forEach { m ->
            val c = m.groupValues[1]
            if (c in codes && c !in table.ambiguousCodes) return table.codeAliases[c] ?: c
        }
        return null
    }

    private fun flagCode(s: String): String? {
        var i = 0
        while (i < s.length) {
            if (regional(s, i) && regional(s, i + 2)) {
                val code = "${'A' + (s[i + 1] - '\uDDE6')}${'A' + (s[i + 3] - '\uDDE6')}"
                return table.codeAliases[code] ?: code
            }
            i++
        }
        return null
    }

    private val nameIndex: Map<String, String> by lazy {
        val m = HashMap<String, String>()
        val languages = table.nameLanguages + Host.language()
        for (c in codes) {
            languages.forEach { l ->
                Locales.countryName(c, l).lowercase().takeIf { it.isNotEmpty() }?.let { m[it.replace(",", "")] = c }
            }
        }
        table.stopWords.forEach { m.remove(it) }
        table.aliases.forEach { (code, names) -> names.forEach { m[it] = code } }
        m
    }

    /**
     * Shorter names the translation prefers to the system's ("USA" for "United States"): the string array
     * `country_short_names` of the UI language, items "CODE=Name".
     */
    private fun shortNames(): Map<String, String> {
        return shortCache.getOrPut(Host.language()) {
            Host.stringArray("country_short_names").associate { it.substringBefore('=') to it.substringAfter('=') }
        }
    }

    private val shortCache = LockedMap<String, Map<String, String>>()
}
