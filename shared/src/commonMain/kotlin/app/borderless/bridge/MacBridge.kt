package app.borderless.bridge

import app.borderless.config.LinkParser
import app.borderless.config.XrayConfig
import app.borderless.data.AppSettings
import app.borderless.data.GeoFile
import app.borderless.data.Group
import app.borderless.data.ImportIssue
import app.borderless.data.Insecurity
import app.borderless.data.LinkErrors
import app.borderless.data.Server
import app.borderless.data.ServerBuild
import app.borderless.data.ShareFormat
import app.borderless.platform.Host
import app.borderless.platform.readFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The Mac app's and service's way into the shared code. Plain strings in and out (JSON), so Swift needs none of the
 * Kotlin types and the calls stay the same while the code behind them grows (local/macos/PLAN.md §1.3).
 */
object MacBridge {
    /** Every field written, empty lists too: Swift's decoders need the keys (an empty `problems` left out read as
     *  "nothing added", 2026-10-23). */
    private val Out = Json { encodeDefaults = true }

    /** What the app sends back in ([encodeShare]): unknown keys ignored like everywhere else. */
    private val In = Json { ignoreUnknownKeys = true }
    /**
     * Call once at start: [assets] = a folder with the bundled files (regions.json, countries.json), [language] =
     * the UI language — asked per call, not fixed at start, because the app's language pick switches live (R10;
     * country names and number formats would otherwise keep the launch language), [text] renders a string by its
     * Android resource name with its arguments (the Mac's `L`).
     */
    fun setUp(assets: String, language: () -> String, text: ((String, List<String>) -> String)?) {
        Host.asset = { name -> readFile("$assets/$name") }
        Host.language = language
        if (text != null) Host.text = { name, args -> text(name, args.map { it.toString() }) }
        // No routing lists on the Mac yet: no rule may name a category (as on Android before the first download).
        GeoFile.installed = emptyMap()
    }

    @Serializable
    class Parsed(val groups: List<ParsedGroup> = emptyList(), val problems: List<Problem> = emptyList())

    /** [name] null = the group the user picked, else the default one; [default] = a bundle's default group. */
    @Serializable
    class ParsedGroup(
        val name: String? = null,
        val default: Boolean = false,
        val servers: List<ParsedServer> = emptyList(),
        val subscriptions: List<ParsedSubscription> = emptyList(),
    )

    /** [name] = the link's (stored), [title] = how it is shown: without a leading flag (Android `Repo.realName`).
     *  [insecure] = [Insecurity.of]'s verdict as wire names ("no_encryption" / "no_cert_check"), the same names the
     *  service's `servers` reply carries (`MacEngine.StoredServer`). */
    @Serializable
    class ParsedServer(
        val id: String, val name: String, val title: String, val protocol: String, val link: String,
        val country: String? = null, val limited: Boolean = false, val hidden: Boolean = false,
        val insecure: List<String> = emptyList(),
    )

    /** [hidden] = keys of the subscription's servers the bundle kept hidden ([ShareFormat.key]). */
    @Serializable
    class ParsedSubscription(val url: String, val name: String? = null, val hidden: List<String> = emptyList())

    @Serializable
    class Problem(val item: String, val text: String, val detail: String? = null)

    /**
     * What the text the user pasted or dropped holds, as Android's `Importer.importText` reads it: Border(less)
     * bundles (groups, hidden servers, subscriptions), share links, Xray JSON, subscription URLs (https only), and every
     * piece that gave nothing, explained. Nothing is downloaded or stored here.
     */
    fun parse(text: String): String {
        val all = text.split('\n', '\r', ' ', '\t').map { it.trim() }.filter { it.isNotEmpty() }
        val groups = ArrayList<ParsedGroup>()
        val problems = ArrayList<Problem>()
        for (b in all.filter(ShareFormat::isInternal)) {
            val bundle = ShareFormat.decode(b)
            if (bundle == null) { problems += Problem(LinkErrors.excerpt(b), Host.text("imp_damaged", emptyList())); continue }
            for (g in bundle.groups) {
                if (g.servers.isEmpty() && g.subs.isEmpty()) continue
                val off = g.servers.filter { it.off }.map { ShareFormat.key(it.l) }.toSet()
                val (servers, errs) = ServerBuild.build(g.servers.map { it.l }, null, Group.DEFAULT_ID)
                problems += errs.map(::problem)
                val subs = g.subs.mapNotNull { s -> subscription(s.url, problems)?.let { ParsedSubscription(it, s.name, s.hidden) } }
                groups += ParsedGroup(g.name, g.default, servers.map { it.parsed(ShareFormat.key(it.link) in off) }, subs)
            }
        }
        val tokens = all.filterNot(ShareFormat::isInternal)
        if (tokens.isNotEmpty()) {
            val urls = tokens.filter { it.startsWith("http://", true) || it.startsWith("https://", true) }
            // The raw text keeps multi-line JSON intact; fall back to tokens once something was taken out.
            val rest = if (urls.isEmpty() && tokens.size == all.size) text else tokens.filterNot { it in urls }.joinToString("\n")
            val links = LinkParser.extractLinks(rest)
            val (servers, errs) = ServerBuild.build(links, null, Group.DEFAULT_ID)
            problems += errs.map(::problem)
            if (links.isEmpty() && urls.isEmpty() && rest.isNotBlank()) {
                problems += Problem(LinkErrors.excerpt(rest), Host.text("imp_not_link", emptyList()))
            }
            val subs = urls.mapNotNull { u -> subscription(u, problems)?.let { ParsedSubscription(it) } }
            if (servers.isNotEmpty() || subs.isNotEmpty()) groups.add(0, ParsedGroup(null, false, servers.map { it.parsed(false) }, subs))
        }
        return Out.encodeToString(Parsed.serializer(), Parsed(groups, problems))
    }

    /** A subscription URL as it can be added (https only), else the problem. */
    private fun subscription(url: String, problems: MutableList<Problem>): String? {
        val u = url.trim()
        if (u.startsWith("https://", ignoreCase = true)) return u
        problems += Problem(host(u), Host.text("imp_sub_http", emptyList()))
        return null
    }

    private fun host(url: String) = url.substringAfter("://").substringBefore('/').substringBefore('?').substringAfter('@').ifEmpty { "?" }

    private fun problem(i: ImportIssue) = Problem(i.item, i.text, i.detail)

    private fun Server.parsed(hidden: Boolean) = ParsedServer(
        id, name, app.borderless.data.Countries.stripFlag(name), protocol, link, country, limited = app.borderless.config.LimitedLink.matches(link), hidden = hidden,
        insecure = Insecurity.of(this).sorted().map { issue ->
            when (issue) {
                Insecurity.Issue.NO_ENCRYPTION -> "no_encryption"
                Insecurity.Issue.NO_CERT_CHECK -> "no_cert_check"
            }
        },
    )

    /** How a server's link name is shown: without a leading flag (Android `Repo.realName`). */
    fun title(name: String): String = app.borderless.data.Countries.stripFlag(name)

    @Serializable
    class ServerInfo(val host: String, val port: Int, val protocol: String)

    /** Display metadata from the same parser as the connection, including JSON and encoded links.
     * DNS URLs, Reality serverName and other auxiliary addresses are never the server endpoint. */
    fun serverInfo(link: String): String = runCatching {
        val p = LinkParser.parse(link)
        Out.encodeToString(ServerInfo.serializer(), ServerInfo(p.host, p.port, p.protocol))
    }.getOrDefault("{}")

    /** A limited-network server's link (`wdtt://`, `qwdtt://`). */
    fun isLimited(link: String): Boolean = app.borderless.config.LimitedLink.matches(link)

    /** The device id a limited-network link carries (empty = none). */
    fun limitedDevice(link: String): String =
        runCatching { app.borderless.config.LimitedLink.parse(link).deviceId.orEmpty() }.getOrDefault("")

    /** [link] with [device] as its device id (`LimitedLink.withDevice`); other links come back unchanged. */
    fun withDevice(link: String, device: String): String = app.borderless.config.LimitedLink.withDevice(link, device)

    /** [ShareFormat.key] of [link]: the identity a subscription's hidden servers are shared by. */
    fun shareKey(link: String): String = ShareFormat.key(link)

    /** One group of a share bundle ([ShareFormat.G]): its name, the default flag, servers and subscriptions. */
    @Serializable
    class ShareGroup(
        val name: String? = null,
        val default: Boolean = false,
        val servers: List<ShareServer> = emptyList(),
        val subs: List<ShareSub> = emptyList(),
    )

    /** A server of a share group ([ShareFormat.S]): the link, [off] = hidden on the sender. */
    @Serializable
    class ShareServer(val link: String, val off: Boolean = false)

    /** A subscription of a share group ([ShareFormat.Sub]); [hidden] = keys of its servers kept hidden. */
    @Serializable
    class ShareSub(val url: String, val name: String? = null, val hidden: List<String> = emptyList())

    /**
     * The `borderless://import?d=` link for [groupsJson] ([ShareGroup]s), packed by [ShareFormat.encode] — raw
     * deflate, the only form every Border(less) build reads (Foundation's `compressed(.zlib)` is a zlib stream and
     * decodes nowhere else). Empty when [groupsJson] doesn't read.
     */
    fun encodeShare(groupsJson: String): String = runCatching {
        val groups = In.decodeFromString(ListSerializer(ShareGroup.serializer()), groupsJson)
        ShareFormat.encode(ShareFormat.Bundle(groups = groups.map { g ->
            ShareFormat.G(
                name = g.name, default = g.default,
                servers = g.servers.map { ShareFormat.S(it.link, it.off) },
                subs = g.subs.map { ShareFormat.Sub(it.url, it.name, it.hidden) },
            )
        }))
    }.getOrDefault("")

    /**
     * A log event's language-neutral text ([LText] as JSON) in the UI language of the moment. The service stores the
     * events; the app renders them, so switching its language also switches the log.
     */
    fun renderText(json: String): String = runCatching {
        app.borderless.data.LText.fromJson(Json.parseToJsonElement(json)).render()
    }.getOrDefault("")

    /**
     * A log for sharing: proxy links lose their secrets, web links lose their paths and JSON input goes at all
     * ([Scrub.secrets], always); with [hideServers] the hosts of the servers whose [links] are given and public
     * IPv4 addresses are renamed too. [links] come from the caller, not `Repo.servers`: the repo is never
     * initialised in the app process (audit A2 — it read as empty and no host was scrubbed).
     */
    fun scrub(text: String, hideServers: Boolean, links: List<String>): String {
        val clean = app.borderless.data.Scrub.secrets(text)
        if (!hideServers) return clean
        val hosts = links.mapNotNull { l -> runCatching { LinkParser.parse(l).host }.getOrNull() }
        return app.borderless.data.Scrub.addresses(clean, hosts)
    }

    @Serializable
    class Config(val config: String? = null, val error: String? = null)

    /**
     * The core's full config for one server, as Android builds it ([XrayConfig.build], routing off until the Mac has
     * the lists), every outbound bound to [bindInterface] and servers' names asked first at [resolver] (the network's
     * own DNS), then DoH. Placeholders are fine: the service swaps in the checked values.
     */
    fun config(link: String, bindInterface: String, resolver: String): String {
        val result = runCatching {
            if (app.borderless.config.LimitedLink.matches(link)) error("limited-network servers connect through the engine, not this check")
            val parsed = LinkParser.parse(link)
            val settings = AppSettings.fresh().copy(routingEnabled = false, regions = emptySet())
            Config(XrayConfig.build(XrayConfig.Mode.PROXY, parsed.outbounds, settings, bindInterface = bindInterface,
                bootstrapDns = listOf(resolver, "https://1.0.0.1/dns-query")))
        }.getOrElse { Config(error = it.message ?: it.toString()) }
        return Out.encodeToString(Config.serializer(), result)
    }
}
