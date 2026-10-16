package app.borderless.data

import app.borderless.R
import android.util.Log
import app.borderless.Res
import app.borderless.config.LinkParser
import app.borderless.core.ViaServer
import app.borderless.config.ShareUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** Adds servers and subscriptions from user input and keeps subscriptions fresh. */
object Importer {
    private const val TAG = "Importer"

    /**
     * What an import did: servers and subscriptions added, and every problem explained ([ImportErrors]).
     * [pending]: subscriptions added but not downloaded because the provider couldn't be reached (likely no
     * internet at the moment) — not a problem, they download on their own later.
     */
    data class Result(val servers: Int, val subscriptions: Int, val errors: List<ImportErrors.Issue>, val pending: Int = 0) {
        /** One line: what was added (or that nothing was). */
        val message: String
            get() = buildString {
                if (servers > 0) append(Res.s(R.string.import_added, servers))
                if (subscriptions > 0) { if (isNotEmpty()) append(", "); append(Res.s(R.string.import_subs, subscriptions)) }
                if (isEmpty()) append(Res.s(R.string.import_nothing))
                if (pending > 0) append(" (").append(Res.s(R.string.import_pending, pending)).append(")")
            }.replaceFirstChar { it.titlecase() }

        /** First line of [message] for the event log, stored language-neutral. */
        val text: LText
            get() = when {
                servers > 0 && subscriptions > 0 -> LText.concat(LText.of(R.string.import_added, servers), ", ", LText.of(R.string.import_subs, subscriptions))
                servers > 0 -> LText.of(R.string.import_added, servers)
                subscriptions > 0 -> LText.of(R.string.import_subs, subscriptions)
                else -> LText.of(R.string.import_nothing)
            }
    }

    /** Accepts any mix of share links, subscription URLs, base64 blobs and Xray JSON. */
    /**
     * [groupId]: the group chosen by the user. Null means automatic: links go to the default group
     * and each new subscription gets a group of its own, named after it.
     */
    suspend fun importText(text: String, groupId: String? = null): Result = withContext(Dispatchers.IO) {
        val all = text.split('\n', '\r', ' ', '\t').map { it.trim() }.filter { it.isNotEmpty() }
        // Border(less) bundles keep group names and hidden servers; import them separately.
        val bundles = all.filter(ShareFormat::isInternal).mapNotNull(ShareFormat::decode).map { importBundle(it, groupId) }
        val tokens = all.filterNot(ShareFormat::isInternal)
        if (tokens.isEmpty() && bundles.isNotEmpty()) {
            return@withContext Result(bundles.sumOf { it.servers }, bundles.sumOf { it.subscriptions }, bundles.flatMap { it.errors }, bundles.sumOf { it.pending })
        }
        val subUrls = tokens.filter { it.startsWith("http://", true) || it.startsWith("https://", true) }
        // The raw text keeps multi-line JSON intact; fall back to tokens once something was taken out.
        val rest = if (subUrls.isEmpty() && tokens.size == all.size) text else tokens.filterNot { it in subUrls }.joinToString("\n")

        val errors = ArrayList<ImportErrors.Issue>()
        var serverCount = 0
        val links = LinkParser.extractLinks(rest)
        if (links.isNotEmpty()) {
            val (servers, errs) = Repo.buildServers(links, null, groupId ?: Group.DEFAULT_ID)
            Repo.addServers(servers)
            serverCount += servers.size
            errors += errs
        } else if (subUrls.isEmpty() && rest.isNotBlank()) {
            errors += ImportErrors.Issue(ImportErrors.excerpt(rest), Res.s(R.string.imp_not_link))
        }

        var subCount = 0
        var pending = 0
        for (url in subUrls) {
            // Plain http can be changed on the way (servers swapped for someone else's): https only.
            if (!isSecureUrl(url)) {
                errors += ImportErrors.Issue(safeUrl(url), Res.s(R.string.imp_sub_http))
                continue
            }
            if (Repo.subscriptions.value.any { it.url == url }) {
                errors += ImportErrors.Issue(safeUrl(url), Res.s(R.string.imp_sub_exists))
                continue
            }
            // Into an existing group: keep that group's name; a new group is named after the subscription.
            groupId?.let(Repo::freezeGroupName)
            val target = groupId ?: Repo.createSubscriptionGroup().id
            val sub = Subscription(id = UUID.randomUUID().toString().take(8), name = URL(url).host, url = url, groupId = target, hwid = Repo.deviceIdFor(url))
            Repo.upsertSubscription(sub)
            val r = update(sub)
            subCount++
            serverCount += r.getOrDefault(0)
            // Not reachable now: added all the same, it downloads later (see [pendingAfterConnect]).
            r.exceptionOrNull()?.let { if (ImportErrors.unreachable(it)) pending++ else errors += ImportErrors.forSubscription(url, it) }
        }
        resolveCountries()
        Result(serverCount, subCount, errors, pending).also { r ->
            AppLog.event(r.text)
            if (errors.isNotEmpty()) AppLog.debug(TAG, "import problems: ${errors.joinToString("; ") { "${it.item}: ${it.detail ?: it.text}" }}")
        }
    }

    /**
     * Imports a Border(less) bundle. Each group becomes a new group with its name (or goes into
     * [groupId] when the user picked one); servers keep their hidden state.
     */
    private suspend fun importBundle(b: ShareFormat.Bundle, groupId: String?): Result {
        var servers = 0
        var subs = 0
        var pending = 0
        val errors = ArrayList<ImportErrors.Issue>()
        for (g in b.groups) {
            if (g.servers.isEmpty() && g.subs.isEmpty()) continue
            val target = groupId ?: when {
                g.default -> Group.DEFAULT_ID
                g.name != null -> Repo.groups.value.firstOrNull { it.name == g.name }?.id ?: Repo.createGroup(g.name).id
                else -> null
            }
            if (g.servers.isNotEmpty()) {
                val (built, errs) = Repo.buildServers(g.servers.map { it.l }, null, target ?: Group.DEFAULT_ID)
                val off = g.servers.filter { it.off }.map { ShareFormat.key(it.l) }.toSet()
                Repo.addServers(built.map { if (ShareFormat.key(it.link) in off) it.copy(disabled = true) else it })
                servers += built.size
                errors += errs
            }
            for (s in g.subs) {
                if (!isSecureUrl(s.url)) { errors += ImportErrors.Issue(safeUrl(s.url), Res.s(R.string.imp_sub_http)); continue }
                if (Repo.subscriptions.value.any { it.url == s.url }) { errors += ImportErrors.Issue(safeUrl(s.url), Res.s(R.string.imp_sub_exists)); continue }
                target?.let(Repo::freezeGroupName)
                val sub = Subscription(
                    id = UUID.randomUUID().toString().take(8), name = s.name ?: URL(s.url).host, url = s.url,
                    groupId = target ?: Repo.createSubscriptionGroup().id, hwid = Repo.deviceIdFor(s.url),
                )
                Repo.upsertSubscription(sub)
                val r = update(sub)
                Repo.hideByKeys(sub.id, s.hidden)
                subs++
                servers += r.getOrDefault(0)
                r.exceptionOrNull()?.let { if (ImportErrors.unreachable(it)) pending++ else errors += ImportErrors.forSubscription(s.url, it) }
            }
        }
        resolveCountries()
        return Result(servers, subs, errors, pending).also { AppLog.event(it.text) }
    }

    /** One download of a subscription: body and what the provider says in the headers. */
    private class Fetched(val body: String, val title: String?, val info: Map<String, Long>, val intervalHours: Int?)

    /**
     * Downloads [url] directly, or through a local proxy ([proxy], see [ViaServer]).
     * Besides the user agent, sends the subscription's device id ([Subscription.hwid]) the way subscription panels
     * with a device limit expect (`x-hwid`); the phone's model and Android version (`x-device-os`, `x-ver-os`,
     * `x-device-model`) only if the user allows it ([AppSettings.sendDeviceModel]).
     */
    private fun fetch(sub: Subscription, proxy: java.net.Proxy?, userAgent: String): Fetched {
        val url = sub.url
        val conn = ((if (proxy != null) URL(url).openConnection(proxy) else URL(url).openConnection()) as HttpURLConnection).apply {
            connectTimeout = if (proxy != null) 15_000 else 8_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept", "*/*")
            setRequestProperty("x-hwid", sub.hwid.ifEmpty { Repo.deviceId() })
            if (Repo.settings.value.sendDeviceModel) {
                setRequestProperty("x-device-os", "Android")
                setRequestProperty("x-ver-os", android.os.Build.VERSION.RELEASE)
                setRequestProperty("x-device-model", android.os.Build.MODEL)
            }
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw java.io.IOException("HTTP $code")
            val body = readLimited(conn, MAX_SUBSCRIPTION)
            return Fetched(
                body,
                conn.getHeaderField("profile-title")?.let(::decodeTitle),
                conn.getHeaderField("subscription-userinfo")?.let(::parseUserInfo).orEmpty(),
                SubMath.parseInterval(conn.getHeaderField("profile-update-interval")),
            )
        } finally {
            conn.disconnect()
        }
    }

    /** Through the user's servers, best first (see [ViaServer.candidates]). */
    private suspend fun fetchViaServer(sub: Subscription, userAgent: String): Fetched {
        val url = sub.url
        var last: Exception? = null
        for (server in ViaServer.candidates(prefer = sub.id)) {
            try {
                return ViaServer.withProxy(server) { proxy -> fetch(sub, proxy, userAgent) }.also {
                    AppLog.debug(TAG, "${safeUrl(url)}: downloaded through ${server.name}")
                }
            } catch (e: Exception) {
                AppLog.debug(TAG, "${safeUrl(url)}: through ${server.name} failed: ${e.message}")
                last = e
            }
        }
        throw last ?: java.io.IOException(Res.s(R.string.err_sub_no_server))
    }

    /**
     * Downloads a subscription: directly, and if that fails (the provider can't be reached, or it
     * refuses our IP) through one of the user's servers. After a download that only worked through a
     * server, the next one starts there (no waiting for the direct attempt to time out).
     * Returns the download and whether it went through a server.
     */
    private suspend fun download(sub: Subscription, userAgent: String): Pair<Fetched, Boolean> {
        suspend fun direct() = withContext(Dispatchers.IO) { fetch(sub, null, userAgent) }
        if (sub.viaServer) {
            try {
                return fetchViaServer(sub, userAgent) to true
            } catch (e: Exception) {
                AppLog.debug(TAG, "${safeUrl(sub.url)}: through servers failed (${e.message}), trying directly")
            }
            return direct() to false
        }
        return try {
            direct() to false
        } catch (e: Exception) {
            AppLog.debug(TAG, "${safeUrl(sub.url)}: direct download failed (${e.message}), trying through a server")
            try {
                fetchViaServer(sub, userAgent) to true
            } catch (viaError: Exception) {
                // Report the direct failure: it is the one that tells what is wrong with the subscription.
                throw e
            }
        }
    }

    suspend fun update(sub: Subscription): kotlin.Result<Int> = withContext(Dispatchers.IO) {
        try {
            val span = Energy.begin(Activity.Kind.SUB_UPDATE)
            val ua = Repo.settings.value.userAgent
            val startedAt = System.currentTimeMillis()
            var (got, viaServer) = try { download(sub, ua).also { span.bytes = it.first.body.length.toLong() } } finally { Energy.end(span) }
            Activity.count(Activity.Kind.SUB_UPDATE, bytes = got.body.length.toLong())
            AppLog.debug(TAG, "${safeUrl(sub.url)}: ${got.body.length / 1024} KB in ${(System.currentTimeMillis() - startedAt) / 100 / 10.0} s, " +
                if (viaServer) "through a server" else "directly")

            var (servers, errors) = Repo.buildServers(LinkParser.extractLinks(got.body).take(MAX_SUB_SERVERS), sub.id)
            // A custom User-Agent may make the panel answer in a format we don't read (Clash, sing-box…):
            // then ask once more as the default client, which gets plain links.
            if (servers.isEmpty() && ua != AppSettings().userAgent) {
                val kind = ImportErrors.kindOf(got.body)
                AppLog.debug(TAG, "${safeUrl(sub.url)}: nothing usable for User-Agent \"$ua\" ($kind), retrying with the default one")
                val retry = download(sub, AppSettings().userAgent)
                val rebuilt = Repo.buildServers(LinkParser.extractLinks(retry.first.body).take(MAX_SUB_SERVERS), sub.id)
                if (rebuilt.first.isNotEmpty()) {
                    got = retry.first; viaServer = retry.second; servers = rebuilt.first; errors = rebuilt.second
                    AppLog.event(LText.of(R.string.ev_sub_default_ua, Repo.subName(sub)), AppLog.Kind.WARN)
                }
            }
            if (servers.isEmpty()) {
                // Links were there but none could be used: explain the first; otherwise say what came instead.
                errors.firstOrNull()?.let { throw SubscriptionLinksException(it.text) }
                throw SubscriptionFormatException(ImportErrors.kindOf(got.body))
            }
            val before = Repo.servers.value.filter { it.subscriptionId == sub.id }.map { it.id }.toSet()
            val issuesBefore = Repo.servers.value.filter { it.subscriptionId == sub.id }.associate { it.id to Insecurity.of(it) }
            Repo.replaceSubscriptionServers(sub.id, servers)
            // A provider can lower a known server's protection with any refresh: said, never silent (SECURITY.md S7).
            Repo.servers.value.filter { it.subscriptionId == sub.id }.forEach { s ->
                val was = issuesBefore[s.id] ?: return@forEach
                val now = Insecurity.of(s) - was
                if (now.isNotEmpty()) AppLog.event(
                    LText.of(
                        R.string.ev_less_secure, Repo.displayName(s),
                        LText.of(if (Insecurity.Issue.NO_ENCRYPTION in now) R.string.ins_no_encryption else R.string.ins_no_cert_check),
                    ),
                    AppLog.Kind.WARN,
                )
            }
            val after = Repo.servers.value.filter { it.subscriptionId == sub.id }.map { it.id }.toSet()
            val added = (after - before).size
            // New servers get their first result right away instead of showing up blank.
            app.borderless.core.Scanner.probeNew(after - before)
            val gone = (before - after).size
            Repo.upsertSubscription(
                (Repo.subscription(sub.id) ?: sub).copy(
                    name = got.title?.takeIf { it.isNotBlank() } ?: Repo.subscription(sub.id)?.name ?: sub.name,
                    updatedAt = System.currentTimeMillis(),
                    lastError = null,
                    uploadBytes = got.info["upload"],
                    downloadBytes = got.info["download"],
                    totalBytes = got.info["total"]?.takeIf { it > 0 },
                    expire = got.info["expire"]?.takeIf { it > 0 },
                    providerHours = got.intervalHours,
                    viaServer = viaServer,
                )
            )
            // The provider's report goes to the statistics too (usage over time).
            if (got.info.isNotEmpty()) {
                val used = (got.info["upload"] ?: 0) + (got.info["download"] ?: 0)
                StatsDb.subUsage(sub.id, used, got.info["total"]?.takeIf { it > 0 }, got.info["expire"]?.takeIf { it > 0 })
            }
            AppLog.event(
                LText.concat(
                    LText.of(R.string.ev_sub_updated, Repo.subscription(sub.id)?.let(Repo::subName).orEmpty(), servers.size),
                    if (added > 0 || gone > 0) LText.of(R.string.ev_sub_changes, added, gone) else "",
                    if (viaServer) LText.of(R.string.ev_sub_via_server) else "",
                    if (errors.isNotEmpty()) LText.of(R.string.ev_sub_skipped, errors.size) else "",
                )
            )
            if (errors.isNotEmpty()) AppLog.debug(TAG, "skipped in ${safeUrl(sub.url)}: ${errors.map { it.detail ?: it.text }.distinct().joinToString("; ")}")
            failed.remove(sub.id)
            kotlin.Result.success(servers.size)
        } catch (e: Exception) {
            val explained = ImportErrors.forSubscription(sub.url, e).text
            AppLog.event(LText.of(R.string.ev_sub_failed, Repo.subName(sub), explained), AppLog.Kind.ERROR)
            AppLog.debug(TAG, "subscription ${safeUrl(sub.url)} failed", e)
            // Stored as the plain-language explanation (shown in the subscription row).
            Repo.subscription(sub.id)?.let { Repo.upsertSubscription(it.copy(lastError = explained)) }
            failed[sub.id] = (failed[sub.id]?.first ?: 0).let { n -> n + 1 to System.currentTimeMillis() + SubMath.retryDelayMs(n + 1) }
            kotlin.Result.failure(e)
        }
    }

    suspend fun updateAll(): Int {
        var failed = 0
        for (s in Repo.subscriptions.value) if (update(s).isFailure) failed++
        resolveCountries()
        return failed
    }

    private var pendingTriedAt = 0L

    /**
     * Called when a server connects: subscriptions that were never downloaded (added while the provider couldn't
     * be reached, e.g. without internet) are tried at once instead of waiting for the next app start or hourly
     * check. At most every 5 minutes, so a provider that stays unreachable doesn't cost a download per switch.
     */
    suspend fun pendingAfterConnect() {
        // Countries wait for a working server to be looked up through (cheap when nothing is missing).
        resolveCountries()
        val now = System.currentTimeMillis()
        if (now - pendingTriedAt < 5 * 60_000L) return
        if (Repo.subscriptions.value.none { it.updatedAt == 0L }) return
        pendingTriedAt = now
        AppLog.debug(TAG, "connected: trying subscriptions that were never downloaded")
        // A new connection is a new chance: these don't wait for their retry time.
        Repo.subscriptions.value.filter { it.updatedAt == 0L }.forEach { update(it) }
        resolveCountries()
    }

    /** Failed scheduled updates in a row and when the next scheduled try may start, per subscription (this run only). */
    private val failed = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, Long>>()

    /** Refreshes subscriptions older than the configured interval. */
    suspend fun updateDue() {
        val s = Repo.settings.value
        // Each subscription on its own schedule: the provider's interval if it gives one (not too often),
        // else the setting; the battery saving mode doubles it and avoids mobile data (SubMath.due).
        val now = System.currentTimeMillis()
        val metered = app.borderless.core.Power.metered
        val due = Repo.subscriptions.value.filter { SubMath.due(it, now, s.subUpdateHours, s.ecoOn, metered) && (failed[it.id]?.second ?: 0L) <= now }
        if (due.isEmpty()) return
        AppLog.debug(TAG, "scheduled update of ${due.size} subscription(s): ${due.joinToString { safeUrl(it.url) }}")
        due.forEach { update(it) }
        resolveCountries()
    }

    /**
     * Looks up the country by IP for servers whose names give no hint, over HTTPS ([countryOf]), host names resolved
     * first by DNS-over-HTTPS ([resolveIp]) — all of it **through one of the user's servers** ([ViaServer]), never
     * from the phone's own address: the services would otherwise learn which servers this IP uses (SECURITY.md S6).
     * No server that answered lately → nothing now; it runs again when a server connects ([pendingAfterConnect]).
     * Results are stored, so each host is resolved once; a host that gave nothing (no address, no country) is left
     * alone for [LOOKUP_RETRY_MS].
     */
    suspend fun resolveCountries() = countryLock.withLock { resolveCountriesNow() }

    /** One lookup at a time: callers overlap (app start, a refresh, a connection) and each would start a proxy. */
    private val countryLock = kotlinx.coroutines.sync.Mutex()

    private suspend fun resolveCountriesNow() = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val missing = Repo.servers.value.filter {
            it.country == null && !it.countryManual && it.host.isNotBlank() && (lookupFailed[it.host] ?: 0L) + LOOKUP_RETRY_MS <= now
        }
        if (missing.isEmpty()) return@withContext
        val byHost = missing.groupBy { it.host }
        val servers = ViaServer.candidates(2)
        if (servers.isEmpty()) {
            AppLog.debug(TAG, "country by IP: ${missing.size} waiting for a working server to look up through")
            return@withContext
        }
        for (server in servers) {
            answered.set(false)
            val result = runCatching { ViaServer.withProxy(server) { proxy -> lookUp(byHost.keys, proxy) } }
                .onFailure { AppLog.debug(TAG, "country by IP through ${server.name}: ${it.message}") }.getOrNull() ?: continue
            // Not a single service answered: the way through this server doesn't work, try the next one.
            if (!answered.get()) {
                AppLog.debug(TAG, "country by IP through ${server.name}: no service answered")
                continue
            }
            val (ipOf, codes) = result
            val found = HashMap<String, String>()
            ipOf.forEach { (host, ip) -> codes[ip]?.let { cc -> byHost[host]?.forEach { found[it.id] = cc } } }
            val unresolved = byHost.keys - ipOf.keys
            byHost.filterValues { list -> list.none { it.id in found } }.keys.forEach { lookupFailed[it] = now }
            AppLog.debug(TAG, "country by IP through ${server.name}: ${found.size} of ${missing.size} resolved" +
                if (unresolved.isEmpty()) "" else "; no address for ${unresolved.joinToString()}")
            Repo.setCountries(found)
            return@withContext
        }
    }

    /** Some lookup service answered during the current [lookUp] (else the way through the server is broken). */
    private val answered = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Addresses of [hosts] and the countries of those addresses, every request through [proxy]. */
    private suspend fun lookUp(hosts: Set<String>, proxy: java.net.Proxy): Pair<Map<String, String>, Map<String, String>> = coroutineScope {
        // A few at a time: a big subscription must not open dozens of connections at once.
        val sem = kotlinx.coroutines.sync.Semaphore(6)
        val ipOf = hosts.map { h -> async { sem.withPermit { h to resolveIp(h, proxy) } } }.awaitAll()
            .mapNotNull { (h, ip) -> ip?.let { h to it } }.toMap()
        val codes = ipOf.values.distinct().map { ip -> async { sem.withPermit { ip to countryOf(ip, proxy) } } }.awaitAll()
            .mapNotNull { (ip, cc) -> cc?.let { ip to it } }.toMap()
        ipOf to codes
    }

    /** Hosts whose country lookup gave nothing, and when (in memory: a restart may try again). */
    private val lookupFailed = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val LOOKUP_RETRY_MS = 24 * 3_600_000L

    /** Country code for an IP from public HTTPS services (the next one is tried if one fails), through [proxy]. */
    private fun countryOf(ip: String, proxy: java.net.Proxy): String? {
        val q = java.net.URLEncoder.encode(ip, "UTF-8")
        val services = listOf<Pair<String, (String) -> String?>>(
            "https://api.country.is/$q" to { body -> jsonField(body, "country") },
            "https://ipwho.is/$q?fields=success,country_code" to { body -> jsonField(body, "country_code") },
            "https://ipinfo.io/$q/country" to { body -> body.trim().takeIf { it.length == 2 } },
        )
        for ((url, parse) in services) {
            val cc = runCatching {
                val c = (URL(url).openConnection(proxy) as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 8000 }
                val code = c.responseCode
                answered.set(true)
                if (code !in 200..299) return@runCatching null
                parse(readLimited(c, 64_000))
            }.getOrNull()?.uppercase()?.takeIf { it.length == 2 && it.all(Char::isLetter) }
            if (cc != null) return cc
        }
        return null
    }

    private fun jsonField(body: String, name: String): String? =
        ((Json.parseToJsonElement(body) as? JsonObject)?.get(name) as? JsonPrimitive)?.contentOrNull

    private const val MAX_SUBSCRIPTION = 10_000_000

    /** Servers taken from one subscription (a 10 MB body could hold ~100 000 links, SECURITY.md S19). */
    private const val MAX_SUB_SERVERS = 2000

    /** Reads a response body, refusing more than [max] bytes (a huge reply must not exhaust memory). */
    private fun readLimited(c: HttpURLConnection, max: Int): String {
        c.inputStream.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > max) throw java.io.IOException("response larger than ${max / 1_000_000} MB")
            }
            return out.toString(Charsets.UTF_8.name())
        }
    }

    fun isSecureUrl(url: String) = url.trim().startsWith("https://", ignoreCase = true)

    /** A subscription URL for logs: host only, the path usually carries the access token. */
    fun safeUrl(url: String): String = runCatching { URL(url).host }.getOrDefault("?")

    /**
     * An IPv4 address for a server host: the host itself if it is one, otherwise from the system DNS.
     * If that answer is private or fake (a fake-IP proxy app on the network, e.g. 198.18.x), ask
     * DNS-over-HTTPS instead.
     */
    /** [host]'s IPv4 address: a literal as it is, a name by DNS-over-HTTPS through [proxy] (never the system's DNS). */
    private fun resolveIp(host: String, proxy: java.net.Proxy): String? {
        val h = host.trim('[', ']')
        if (IPV4.matches(h)) return h
        if (':' in h) return h // IPv6 literal: the lookup services take it as is
        return dohLookup(h, proxy)
    }

    private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")

    /** A record via DNS-over-HTTPS (Google, then Cloudflare), JSON API. */
    private fun dohLookup(host: String, proxy: java.net.Proxy): String? {
        val q = java.net.URLEncoder.encode(host, "UTF-8")
        for (url in listOf("https://dns.google/resolve?name=$q&type=A", "https://cloudflare-dns.com/dns-query?name=$q&type=A")) {
            val ip = runCatching {
                val c = (URL(url).openConnection(proxy) as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                    setRequestProperty("Accept", "application/dns-json")
                }
                val body = readLimited(c, 64 * 1024)
                answered.set(true)
                val answers = (Json.parseToJsonElement(body) as JsonObject)["Answer"] as? JsonArray
                answers?.mapNotNull { (it as? JsonObject)?.get("data")?.let { d -> (d as JsonPrimitive).contentOrNull } }
                    ?.firstOrNull { IPV4.matches(it) }
            }.getOrNull()
            if (ip != null) return ip
        }
        return null
    }

    /** Header values arrive as ISO-8859-1; panels send either base64 or raw UTF-8 bytes. */
    private fun decodeTitle(raw: String): String =
        if (raw.startsWith("base64:")) ShareUrl.decodeBase64(raw.removePrefix("base64:")) ?: raw
        else String(raw.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)

    private fun parseUserInfo(raw: String): Map<String, Long> =
        raw.split(';').mapNotNull { part ->
            val k = part.substringBefore('=').trim().lowercase()
            val v = part.substringAfter('=', "").trim().toDoubleOrNull()?.toLong()
            if (k.isNotEmpty() && v != null) k to v else null
        }.toMap()
}
