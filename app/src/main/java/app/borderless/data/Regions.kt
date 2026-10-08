package app.borderless.data

import app.borderless.Res
import kotlinx.serialization.Serializable
import java.io.File

/**
 * Regional routing lists, read from `assets/regions.json`. A region is named by its domain zone (e.g.
 * "ru"): sites and addresses that should go direct, the ones listed for the server (the only ones sent through
 * the server in [RoutingMode.LISTED_ONLY]), resolvers for its sites, and apps that refuse to work
 * through a tunnel. Nothing is on by default; the user picks zones in the routing settings
 * ([AppSettings.regions]). A new region is one more entry in the file (its geo categories are copied
 * into the bundled geo files by `scripts/build_geo.py`).
 */
@Serializable
data class Region(
    val code: String,
    /** Domain zones shown to the user, e.g. ".ru". */
    val zones: List<String> = emptyList(),
    val directDomains: List<String> = emptyList(),
    val directIps: List<String> = emptyList(),
    val serverDomains: List<String> = emptyList(),
    val serverIps: List<String> = emptyList(),
    /** Resolvers inside the region, for its sites (direct DNS when the user sets none). */
    val dns: List<String> = emptyList(),
    /** Sites inside the region for the direct internet check (they are the most likely to answer locally). */
    val checkUrls: List<String> = emptyList(),
    val apps: AppRules = AppRules(),
    /** Where its geo categories come from (see [GeoSource]). */
    val geo: GeoSource? = null,
) {
    /** "ru" → ".ru": what the user sees. */
    val label: String get() = zones.firstOrNull() ?: ".$code"
}

/**
 * Recognises a region's apps by package name, all from the region's file: [packages] (hashes of whole package names,
 * see [Regions.hash]), [prefixes] / [suffixes] (the zone's own namespace, plain) and [markers] (name fragments as
 * "length:hash": every piece of a package name of that length is hashed and looked up). Names are kept hashed so the
 * file doesn't list them; nothing about any region is written in the code.
 */
@Serializable
data class AppRules(
    val packages: Set<String> = emptySet(),
    val prefixes: List<String> = emptyList(),
    val suffixes: List<String> = emptyList(),
    val markers: List<String> = emptyList(),
) {
    /** Marker hashes by fragment length. */
    @kotlinx.serialization.Transient
    private val markersByLength: Map<Int, Set<String>> =
        markers.mapNotNull { m -> m.substringBefore(':').toIntOrNull()?.let { it to m.substringAfter(':') } }
            .groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() }

    /** Answers per package (hashing every fragment costs a little; the installed apps rarely change). */
    @kotlinx.serialization.Transient
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    fun matches(pkg: String): Boolean = cache.getOrPut(pkg) {
        val p = pkg.lowercase()
        prefixes.any { p.startsWith(it) } || suffixes.any { p.endsWith(it) } || Regions.hash(p) in packages ||
            markersByLength.any { (len, hashes) -> (0..p.length - len).any { i -> Regions.hash(p.substring(i, i + len)) in hashes } }
    }
}

/**
 * A published pair of geoip.dat / geosite.dat ([source] = the folder URL) and the categories taken from it: by local
 * name (what the rules and the app's own files use) → [Regions.hash] of the category's name at the source.
 */
@Serializable
data class GeoSource(
    val source: String,
    val geoip: Map<String, String> = emptyMap(),
    val geosite: Map<String, String> = emptyMap(),
    /** Categories not shipped in the APK (they change daily): downloaded when needed. */
    val notBundled: List<String> = emptyList(),
    /** Other folders with the same files, tried when [source] can't be reached. */
    val mirrors: List<String> = emptyList(),
)

@Serializable
private data class RegionsFile(val common: GeoSource? = null, val regions: List<Region> = emptyList())

object Regions {
    @Volatile
    private var file: RegionsFile? = null

    private val loaded: RegionsFile get() = file ?: synchronized(this) { file ?: parseFile(read()).also { file = it } }

    /** Every region the app knows, in file order. */
    val all: List<Region> get() = loaded.regions

    /** Geo sources with their categories: the common lists, then the regions' among [codes] (the chosen zones). */
    fun geoSources(codes: Collection<String>): List<GeoSource> =
        listOfNotNull(loaded.common) + loaded.regions.filter { it.code in codes }.mapNotNull { it.geo }

    fun byCode(code: String): Region? = all.firstOrNull { it.code == code }

    /**
     * Whether a zone's lists are all in the files the core uses (some are downloaded first, see GeoLists). Until then
     * the zone takes no part: no rules, no apps. Unknown files (unit tests): ready.
     */
    fun ready(region: Region): Boolean {
        val have = GeoFile.installed ?: return true
        val geo = region.geo ?: return true
        return geo.geoip.keys.all { it.uppercase() in have["geoip"].orEmpty() } &&
            geo.geosite.keys.all { it.uppercase() in have["geosite"].orEmpty() }
    }

    /** The chosen zones that are ready to work (see [ready]). */
    fun active(codes: Collection<String>): List<Region> = of(codes).filter(::ready)

    /** The regions among [codes] that exist (unknown codes, e.g. from a newer version, are skipped). */
    fun of(codes: Collection<String>): List<Region> = all.filter { it.code in codes }

    fun parse(json: String): List<Region> = parseFile(json).regions

    /** How names are kept in regions.json: SHA-256 of the text, first 16 hex digits (categories: upper case; apps: lower case). */
    fun hash(text: String): String {
        val d = digest.get()!!.digest(text.toByteArray())
        val out = CharArray(16)
        for (i in 0 until 8) {
            val b = d[i].toInt() and 0xFF
            out[2 * i] = HEX[b ushr 4]
            out[2 * i + 1] = HEX[b and 0x0F]
        }
        return String(out)
    }

    private val digest = ThreadLocal.withInitial { java.security.MessageDigest.getInstance("SHA-256") }
    private val HEX = "0123456789abcdef".toCharArray()

    private fun parseFile(json: String): RegionsFile = runCatching { StoreJson.decodeFromString(RegionsFile.serializer(), json) }.getOrDefault(RegionsFile())

    /** From the app's assets; in unit tests (no Android) from the source tree. */
    private fun read(): String = Res.asset(FILE) ?: File("src/main/assets/$FILE").takeIf { it.exists() }?.readText() ?: "{}"

    private const val FILE = "regions.json"
}
