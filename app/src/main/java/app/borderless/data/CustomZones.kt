package app.borderless.data

import kotlinx.serialization.Serializable

/**
 * A domain zone the user made: sites and addresses that go without the tunnel ([direct]), ones that go through the
 * server ([server]) and apps kept outside the tunnel ([apps], package names). Entries are stored as routing rules
 * ([ZoneFile.entry]: "domain:example.com", "full:…", "10.0.0.0/8", "geosite:…"). It takes part like a zone of
 * regions.json ([toRegion], chosen in [AppSettings.regions] by its [id]); saved in `zones.json` (see [Repo]).
 */
@Serializable
data class CustomZone(
    val id: String,
    val name: String,
    val direct: List<String> = emptyList(),
    val server: List<String> = emptyList(),
    val apps: List<String> = emptyList(),
    /** How many sites / addresses each `ext:` entry (categories of a `.dat`, see [DatFile]) holds, for showing. */
    val listSizes: Map<String, Int> = emptyMap(),
) {
    /** Entries of [list] as the user thinks of them: a category of a `.dat` counts with all it holds. */
    fun count(list: List<String>): Int = list.sumOf { if (it.startsWith("ext:")) listSizes[it] ?: 1 else 1 }

    fun toRegion(): Region {
        val (directIps, directDomains) = direct.partition(ZoneFile::isAddress)
        val (serverIps, serverDomains) = server.partition(ZoneFile::isAddress)
        return Region(
            code = id, name = name,
            directDomains = directDomains, directIps = directIps, serverDomains = serverDomains, serverIps = serverIps,
            apps = AppRules(packages = apps.map { Regions.hash(it.lowercase()) }.toSet()),
        )
    }

    companion object {
        /** Ids of the user's zones start with this (regions.json codes never do). */
        const val PREFIX = "u-"

        fun newId(): String = PREFIX + java.util.UUID.randomUUID().toString().take(8)
    }
}

/**
 * The zone file: plain text that can be written by hand, shared and loaded. `#` starts a comment; `name: …` names the
 * zone; `[direct]`, `[server]` and `[apps]` start the lists (lines before any of them are direct). An entry is a site
 * (`example.com` = with its subdomains, `full:host` = exactly that name, `keyword:…`, `regexp:…`, `geosite:category`),
 * an address (`1.2.3.4`, `10.0.0.0/8`, IPv6, `geoip:category`) or, under `[apps]`, a package name.
 *
 * Other people's lists load as well, line by line: plain domain lists, hosts files ("0.0.0.0 example.com"), the
 * domain-list-community format (`domain:` / `full:` / `keyword:` / `regexp:`, `@attributes` dropped, `include:` not
 * followed), Clash / Surge rule lists (`DOMAIN-SUFFIX,example.com`, `IP-CIDR,…`, `+.example.com`, a `payload:` list),
 * AdBlock-style `||example.com^`, and sing-box rule-set sources (JSON). Xray's binary `.dat` lists: see [DatFile].
 */
object ZoneFile {
    const val MAX_ENTRIES = 50_000

    data class Parsed(
        val name: String?,
        val direct: List<String>,
        val server: List<String>,
        val apps: List<String>,
        /** Lines that are none of the above (shown to the user, never put into the rules: the core would refuse them). */
        val skipped: List<String>,
        /** More entries than [MAX_ENTRIES]: the rest was left out. */
        val truncated: Boolean = false,
        /** The file said where its entries go (`[direct]` / `[server]`); else they are all in [direct]. */
        val sectioned: Boolean = false,
    ) {
        val empty: Boolean get() = direct.isEmpty() && server.isEmpty() && apps.isEmpty()
    }

    private enum class Part { DIRECT, SERVER, APPS }

    fun parse(text: String): Parsed {
        if (text.trimStart().startsWith("{")) singBox(text)?.let { return it }
        var name: String? = null
        var sectioned = false
        var part = Part.DIRECT
        val lists = Part.entries.associateWith { LinkedHashSet<String>() }
        val skipped = ArrayList<String>()
        var count = 0
        var truncated = false
        for (raw in text.lineSequence()) {
            val line = raw.substringBefore('#').trim().removePrefix("\uFEFF")
            if (line.isEmpty()) continue
            val lower = line.lowercase()
            when {
                lower == "[direct]" -> { part = Part.DIRECT; sectioned = true; continue }
                lower == "[server]" -> { part = Part.SERVER; sectioned = true; continue }
                // Clash rule providers: the list follows.
                lower == "payload:" -> continue
                lower == "[apps]" -> { part = Part.APPS; continue }
                lower.startsWith("name:") || lower.startsWith("name =") || lower.startsWith("name=") -> {
                    name = line.substringAfter(if (':' in line.substringBefore('=')) ':' else '=').trim().takeIf { it.isNotEmpty() }?.take(60)
                    continue
                }
            }
            if (count >= MAX_ENTRIES) { truncated = true; continue }
            val e = if (part == Part.APPS) app(line) else entry(line)
            if (e == null) skipped += line else if (lists.getValue(part).add(e)) count++
        }
        return Parsed(name, lists.getValue(Part.DIRECT).toList(), lists.getValue(Part.SERVER).toList(), lists.getValue(Part.APPS).toList(), skipped, truncated, sectioned)
    }

    /** A sing-box rule-set source: {"rules": [{"domain": [...], "domain_suffix": [...], "ip_cidr": [...], ...}]}. */
    private fun singBox(text: String): Parsed? = runCatching {
        val root = kotlinx.serialization.json.Json.parseToJsonElement(text) as kotlinx.serialization.json.JsonObject
        val rules = root["rules"] as? kotlinx.serialization.json.JsonArray ?: return null
        val out = LinkedHashSet<String>()
        val skipped = ArrayList<String>()
        fun values(o: kotlinx.serialization.json.JsonObject, key: String): List<String> = when (val v = o[key]) {
            is kotlinx.serialization.json.JsonArray -> v.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            is kotlinx.serialization.json.JsonPrimitive -> listOf(v.content)
            else -> emptyList()
        }
        for (r in rules) {
            val o = r as? kotlinx.serialization.json.JsonObject ?: continue
            fun add(prefix: String, key: String) = values(o, key).forEach { v -> entry(prefix + v.removePrefix("."))?.let { if (out.size < MAX_ENTRIES) out += it } ?: run { skipped += v } }
            add("full:", "domain"); add("domain:", "domain_suffix"); add("keyword:", "domain_keyword"); add("regexp:", "domain_regex"); add("", "ip_cidr")
        }
        Parsed(null, out.toList(), emptyList(), emptyList(), skipped, out.size >= MAX_ENTRIES)
    }.getOrNull()

    /**
     * The zone as a file (what [parse] reads back). Categories taken from an Xray `.dat` are only named in a
     * comment: their file stays on this phone.
     */
    fun write(z: CustomZone): String = buildString {
        appendLine("# Border(less) domain zone")
        appendLine("name: ${z.name}")
        appendLine()
        fun list(entries: List<String>) = entries.forEach { if (it.startsWith("ext:")) appendLine("# not included (categories of a .dat file): ${it.substringAfterLast(':').lowercase()}") else appendLine(display(it)) }
        appendLine("[direct]")
        list(z.direct)
        appendLine()
        appendLine("[server]")
        list(z.server)
        appendLine()
        appendLine("[apps]")
        z.apps.forEach { appendLine(it) }
    }

    /** How an entry is shown and edited: plain domains without their "domain:". */
    fun display(entry: String): String = entry.removePrefix("domain:")

    /** Lines typed by the user (or from a file) → entries; the ones that aren't understood come back separately. */
    fun entries(text: String, apps: Boolean = false): Pair<List<String>, List<String>> {
        val ok = LinkedHashSet<String>()
        val bad = ArrayList<String>()
        text.lineSequence().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.forEach { l ->
            val e = if (apps) app(l) else entry(l)
            if (e == null) bad += l else ok += e
        }
        return ok.toList().take(MAX_ENTRIES) to bad
    }

    /** One line as a routing rule, or null when it isn't a site or an address (see the file format above). */
    fun entry(line: String): String? {
        var t = line.trim().removePrefix("- ").trim().trim('\'', '"')
        // AdBlock-style "||example.com^".
        if (t.startsWith("||")) t = t.removePrefix("||").substringBefore('^').substringBefore('$')
        // Clash / Surge rules: "TYPE,value[,options]".
        if (Regex("^[A-Za-z0-9-]+,").containsMatchIn(t)) {
            val parts = t.split(',').map(String::trim)
            val v = parts.getOrNull(1).orEmpty()
            t = when (parts[0].uppercase()) {
                "DOMAIN" -> "full:$v"
                "DOMAIN-SUFFIX" -> "domain:$v"
                "DOMAIN-KEYWORD" -> "keyword:$v"
                "DOMAIN-REGEX" -> "regexp:$v"
                "IP-CIDR", "IP-CIDR6" -> v
                else -> return null
            }
        }
        // Clash domain lists: "+.example.com" = with subdomains.
        t = t.removePrefix("+.")
        // domain-list-community attributes ("example.com @ads").
        t = t.split(Regex("\\s+")).filterNot { it.startsWith("@") }.joinToString(" ")
        // Hosts files: "0.0.0.0 example.com" / "127.0.0.1 example.com".
        t.split(Regex("\\s+")).let { parts -> if (parts.size == 2 && ip(parts[0]) != null) t = parts[1] else if (parts.size > 1) return null }
        val colon = t.indexOf(':')
        val prefix = if (colon > 0) t.substring(0, colon).lowercase() else ""
        val rest = if (colon > 0) t.substring(colon + 1).trim() else t
        return when (prefix) {
            "domain" -> host(rest)?.let { "domain:$it" }
            "full" -> host(rest)?.let { "full:$it" }
            "keyword" -> rest.lowercase().takeIf { it.isNotEmpty() && it.none(Char::isWhitespace) }?.let { "keyword:$it" }
            "regexp" -> rest.takeIf { it.isNotEmpty() && runCatching { Regex(it) }.isSuccess }?.let { "regexp:$it" }
            "geosite", "geoip" -> rest.lowercase().takeIf { Regex("^[a-z0-9!@._-]+$").matches(it) }?.let { "$prefix:$it" }
            // Categories of a list file kept for a zone (DatFile).
            "ext" -> rest.takeIf { Regex("^[A-Za-z0-9._-]+\\.dat:[A-Za-z0-9!@._-]+$").matches(it) }?.let { "ext:$it" }
            "http", "https" -> host(t.substringAfter("://").substringBefore('/').substringBefore(':'))?.let { "domain:$it" }
            else -> ip(t) ?: host(t)?.let { "domain:$it" }
        }
    }

    /** A package name ("com.example.app"), lower-cased; null if it isn't one. */
    fun app(line: String): String? = line.trim().takeIf { Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$").matches(it) }

    fun isAddress(entry: String): Boolean = entry.startsWith("geoip:") || (entry.startsWith("ext:") && ".geoip.dat:" in entry) || (!entry.contains(':') || entry.count { it == ':' } >= 2) && ip(entry) != null

    /** An IPv4 / IPv6 address or range in canonical text, or null. */
    fun ip(s: String): String? {
        val addr = s.substringBefore('/')
        val bits = if ('/' in s) s.substringAfter('/').toIntOrNull() ?: return null else null
        if (Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(addr)) {
            if (addr.split('.').any { it.toInt() > 255 }) return null
            if (bits != null && bits !in 0..32) return null
            return s
        }
        if (':' in addr && Regex("^[0-9a-fA-F:.]+$").matches(addr) && addr.count { it == ':' } >= 2) {
            if (bits != null && bits !in 0..128) return null
            // A literal: no lookup happens for it.
            return runCatching { java.net.InetAddress.getByName(addr) }.getOrNull()?.let { s.lowercase() }
        }
        return null
    }

    /** A host name as Xray matches it (lower case, IDN in ASCII), without "*." / leading dot / trailing dot. */
    private fun host(s: String): String? {
        val t = s.trim().lowercase().removePrefix("*.").removePrefix(".").removeSuffix(".")
        if (t.isEmpty() || t.length > 253) return null
        val ascii = runCatching { java.net.IDN.toASCII(t) }.getOrNull() ?: return null
        val label = Regex("^[a-z0-9_]([a-z0-9_-]{0,61}[a-z0-9_])?$")
        return ascii.takeIf { a -> a.split('.').all { label.matches(it) } && !Regex("^[0-9.]+$").matches(a) }
    }
}

/**
 * Where the user's zones disagree with something that wins over them. The order in the core decides (see XrayConfig):
 * the user's own sites (Exceptions) and apps first, then every chosen zone's "through the server" entries, then the
 * "without the tunnel" ones. So an entry of a zone doesn't apply when an own list covers it, and a "without the tunnel"
 * entry doesn't when a chosen zone sends the same site (or a domain above it, or a range holding the address) through
 * the server. Regions.json zones are categories of the downloaded lists: their contents aren't looked into here.
 */
object ZoneConflicts {
    enum class Reason {
        /** The user's own list of sites without the tunnel / through the server decides instead. */
        OWN_DIRECT, OWN_SERVER,
        /** Another chosen zone (or this one) sends it through the server. */
        ZONE_SERVER,
        /** The user keeps this app in the tunnel. */
        OWN_APP_TUNNEL,
    }

    /** [entry] of [zone] doesn't apply because of [reason]; [by] = the zone name that wins (ZONE_SERVER). */
    data class Conflict(val entry: String, val reason: Reason, val by: String? = null)

    /**
     * Conflicts of [zone] with the user's lists and the other zones in [chosen] (the user's zones that are on,
     * this one included or not). [ownDirect] / [ownServer] are the Exceptions lists as typed.
     */
    fun of(
        zone: CustomZone,
        chosen: List<CustomZone>,
        ownDirect: List<String>,
        ownServer: List<String>,
        appsTunnel: Set<String>,
    ): List<Conflict> {
        val own = { list: List<String> -> list.mapNotNull(ZoneFile::entry) }
        val ownD = own(ownDirect)
        val ownS = own(ownServer)
        val out = ArrayList<Conflict>()
        val serverOthers = (chosen.filter { it.id != zone.id } + zone).flatMap { z -> z.server.map { it to z.name } }
        fun check(e: String, direct: Boolean) {
            // The user's own lists come first, whichever way they send it (a same-way listing is harmless).
            if (direct && ownS.any { covers(it, e) }) return run { out += Conflict(e, Reason.OWN_SERVER) }
            if (!direct && ownD.any { covers(it, e) }) return run { out += Conflict(e, Reason.OWN_DIRECT) }
            if (direct) serverOthers.firstOrNull { (s, _) -> covers(s, e) }?.let { (_, by) -> out += Conflict(e, Reason.ZONE_SERVER, by) }
        }
        zone.direct.forEach { check(it, direct = true) }
        zone.server.forEach { check(it, direct = false) }
        zone.apps.filter { it.lowercase() in appsTunnel.map(String::lowercase) }.forEach { out += Conflict(it, Reason.OWN_APP_TUNNEL) }
        return out
    }

    /** Whether rule [a] matches everything rule [b] does (same rule, a domain above it, a range holding it). */
    fun covers(a: String, b: String): Boolean {
        if (a == b) return true
        val bHost = when {
            b.startsWith("domain:") -> b.removePrefix("domain:")
            b.startsWith("full:") -> b.removePrefix("full:")
            else -> null
        }
        if (a.startsWith("domain:") && bHost != null) {
            val d = a.removePrefix("domain:")
            return bHost == d || bHost.endsWith(".$d")
        }
        if (a.startsWith("keyword:") && bHost != null) return a.removePrefix("keyword:") in bHost
        if (ZoneFile.ip(a) != null && ZoneFile.ip(b) != null) return holds(a, b)
        return false
    }

    /** IPv4 range [a] holds address / range [b]. */
    private fun holds(a: String, b: String): Boolean {
        fun parse(s: String): Pair<Long, Int>? {
            val addr = s.substringBefore('/')
            if (!Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(addr)) return null
            val v = addr.split('.').fold(0L) { acc, p -> acc * 256 + p.toLong() }
            return v to (s.substringAfter('/', "32").toIntOrNull() ?: 32)
        }
        val (av, ab) = parse(a) ?: return false
        val (bv, bb) = parse(b) ?: return false
        if (bb < ab) return false
        val mask = if (ab == 0) 0L else (0xFFFFFFFFL shl (32 - ab)) and 0xFFFFFFFFL
        return (av and mask) == (bv and mask)
    }
}
