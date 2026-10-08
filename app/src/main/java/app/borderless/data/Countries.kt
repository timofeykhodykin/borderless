package app.borderless.data

import app.borderless.R
import java.util.Locale

/** Detects a server's country from its name, and formats country codes for display. */
object Countries {
    private val codes: Set<String> = Locale.getISOCountries().toSet()

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
        val text = app.borderless.Res.asset("countries.json") ?: java.io.File("src/main/assets/countries.json").takeIf { it.exists() }?.readText()
        text?.let { runCatching { StoreJson.decodeFromString(Table.serializer(), it) }.getOrNull() } ?: Table()
    }

    /** Country name in the UI language; null/unknown codes give "Unknown country". */
    fun name(code: String?): String {
        val lang = app.borderless.Res.locale
        if (code.isNullOrEmpty()) return runCatching { app.borderless.Res.s(app.borderless.R.string.country_unknown) }.getOrDefault("?")
        val n = Locale.Builder().setRegion(code).build().getDisplayCountry(lang)
        return shortNames()[code] ?: n.ifEmpty { code }
    }

    fun flag(code: String?): String {
        if (code == null || code.length != 2) return "🏳️"
        val base = 0x1F1E6 - 'A'.code
        return String(Character.toChars(base + code[0].uppercaseChar().code)) +
            String(Character.toChars(base + code[1].uppercaseChar().code))
    }

    private val leadingFlag = Regex("^\\s*(?:[\\x{1F1E6}-\\x{1F1FF}]{2}|\\x{1F3F3}\\x{FE0F}?)\\s*")

    /** Server name without a leading flag emoji (the flag is shown separately). */
    fun stripFlag(name: String): String = name.replace(leadingFlag, "").ifEmpty { name }

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
            val cp = s.codePointAt(i)
            val next = i + Character.charCount(cp)
            if (cp in 0x1F1E6..0x1F1FF && next < s.length) {
                val cp2 = s.codePointAt(next)
                if (cp2 in 0x1F1E6..0x1F1FF) {
                    val code = "${'A' + (cp - 0x1F1E6)}${'A' + (cp2 - 0x1F1E6)}"
                    return table.codeAliases[code] ?: code
                }
            }
            i = next
        }
        return null
    }

    private val nameIndex: Map<String, String> by lazy {
        val m = HashMap<String, String>()
        for (c in codes) {
            val loc = Locale.Builder().setRegion(c).build()
            (table.nameLanguages.map(Locale::forLanguageTag) + app.borderless.Res.locale).forEach { l ->
                loc.getDisplayCountry(l).lowercase().takeIf { it.isNotEmpty() }?.let { m[it.replace(",", "")] = c }
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
        val lang = app.borderless.Res.locale
        return shortCache[lang] ?: runCatching {
            app.borderless.Res.array(R.array.country_short_names).associate { it.substringBefore('=') to it.substringAfter('=') }
        }.getOrDefault(emptyMap()).also { shortCache[lang] = it }
    }

    private val shortCache = java.util.concurrent.ConcurrentHashMap<Locale, Map<String, String>>()
}
