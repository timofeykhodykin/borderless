package app.borderless.data

import app.borderless.S
import app.borderless.config.LinkParser
import app.borderless.config.ShareUrl
import app.borderless.core.ViaServer
import app.borderless.net.HttpRequest
import app.borderless.net.HttpStatus
import app.borderless.net.Net
import app.borderless.net.NetError
import app.borderless.net.Socks
import app.borderless.platform.Host
import app.borderless.platform.LockedMap
import app.borderless.platform.latin1AsUtf8
import app.borderless.platform.nowMs
import app.borderless.platform.percentEncode
import app.borderless.platform.text
import app.borderless.platform.urlHost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.uuid.Uuid

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
                if (servers > 0) append(text(S.import_added, servers))
                if (subscriptions > 0) { if (isNotEmpty()) append(", "); append(text(S.import_subs, subscriptions)) }
                if (isEmpty()) append(text(S.import_nothing))
                if (pending > 0) append(" (").append(text(S.import_pending, pending)).append(")")
            }.replaceFirstChar { it.titlecase() }

        /** First line of [message] for the event log, stored language-neutral. */
        val text: LText
            get() = when {
                servers > 0 && subscriptions > 0 -> LText.concat(LText.of(S.import_added, servers), ", ", LText.of(S.import_subs, subscriptions))
                servers > 0 -> LText.of(S.import_added, servers)
                subscriptions > 0 -> LText.of(S.import_subs, subscriptions)
                else -> LText.of(S.import_nothing)
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
        val subUrlSet = subUrls.toHashSet()
        // The raw text keeps multi-line JSON intact; fall back to tokens once something was taken out.
        val rest = if (subUrls.isEmpty() && tokens.size == all.size) text else tokens.filterNot { it in subUrlSet }.joinToString("\n")

        val errors = ArrayList<ImportErrors.Issue>()
        var serverCount = 0
        val links = LinkParser.extractLinks(rest)
        if (links.isNotEmpty()) {
            val (servers, errs) = Repo.buildServers(links, null, groupId ?: Group.DEFAULT_ID)
            // What really joined — servers already known are dropped by the id dedupe, not counted as added.
            serverCount += Repo.addServers(servers)
            errors += errs
        } else if (subUrls.isEmpty() && rest.isNotBlank()) {
            errors += ImportErrors.Issue(ImportErrors.excerpt(rest), text(S.imp_not_link))
        }

        var subCount = 0
        var pending = 0
        val knownUrls = Repo.subscriptions.value.mapTo(HashSet()) { it.url }
        for (url in subUrls) {
            // Plain http can be changed on the way (servers swapped for someone else's): https only.
            if (!isSecureUrl(url)) {
                errors += ImportErrors.Issue(safeUrl(url), text(S.imp_sub_http))
                continue
            }
            if (url in knownUrls) {
                errors += ImportErrors.Issue(safeUrl(url), text(S.imp_sub_exists))
                continue
            }
            // Into an existing group: keep that group's name; a new group is named after the subscription.
            groupId?.let(Repo::freezeGroupName)
            val target = groupId ?: Repo.createSubscriptionGroup().id
            val sub = Subscription(id = Uuid.random().toHexString().take(8), name = urlHost(url).orEmpty(), url = url, groupId = target, hwid = Repo.deviceIdFor(url))
            Repo.upsertSubscription(sub)
            knownUrls += url
            val r = update(sub)
            subCount++
            serverCount += r.getOrDefault(0)
            // Not reachable now: added all the same, it downloads later (see [pendingAfterConnect]).
            r.exceptionOrNull()?.let { if (ImportErrors.unreachable(it)) pending++ else errors += ImportErrors.forSubscription(url, it) }
        }
        resolveCountries()
        // The bundle imports' counts and problems join the text part's (a bundle alone returns early above).
        Result(
            serverCount + bundles.sumOf { it.servers },
            subCount + bundles.sumOf { it.subscriptions },
            errors + bundles.flatMap { it.errors },
            pending + bundles.sumOf { it.pending },
        ).also(::logResult)
    }

    /** The import summary: an event always; when lines failed, the detailed log gets each problem too. */
    private fun logResult(r: Result) {
        AppLog.event(r.text)
        if (r.errors.isNotEmpty()) AppLog.debug(TAG, "import problems: ${r.errors.joinToString("; ") { "${it.item}: ${it.detail ?: it.text}" }}")
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
        val knownUrls = Repo.subscriptions.value.mapTo(HashSet()) { it.url }
        for (g in b.groups) {
            if (g.servers.isEmpty() && g.subs.isEmpty()) continue
            val target = groupId ?: when {
                g.default -> Group.DEFAULT_ID
                g.name != null -> Repo.groups.value.firstOrNull { it.name == g.name }?.id ?: Repo.createGroup(g.name!!).id
                else -> null
            }
            if (g.servers.isNotEmpty()) {
                val (built, errs) = Repo.buildServers(g.servers.map { it.l }, null, target ?: Group.DEFAULT_ID)
                val off = g.servers.filter { it.off }.map { ShareFormat.key(it.l) }.toSet()
                servers += Repo.addServers(built.map { if (ShareFormat.key(it.link) in off) it.copy(disabled = true) else it })
                errors += errs
            }
            for (s in g.subs) {
                if (!isSecureUrl(s.url)) { errors += ImportErrors.Issue(safeUrl(s.url), text(S.imp_sub_http)); continue }
                if (s.url in knownUrls) { errors += ImportErrors.Issue(safeUrl(s.url), text(S.imp_sub_exists)); continue }
                target?.let(Repo::freezeGroupName)
                val sub = Subscription(
                    id = Uuid.random().toHexString().take(8), name = s.name ?: urlHost(s.url).orEmpty(), url = s.url,
                    groupId = target ?: Repo.createSubscriptionGroup().id, hwid = Repo.deviceIdFor(s.url),
                )
                Repo.upsertSubscription(sub)
                knownUrls += s.url
                val r = update(sub)
                Repo.hideByKeys(sub.id, s.hidden)
                subs++
                servers += r.getOrDefault(0)
                r.exceptionOrNull()?.let { if (ImportErrors.unreachable(it)) pending++ else errors += ImportErrors.forSubscription(s.url, it) }
            }
        }
        resolveCountries()
        return Result(servers, subs, errors, pending).also(::logResult)
    }

    /** One download of a subscription: body and what the provider says in the headers. */
    private class Fetched(val body: String, val title: String?, val info: Map<String, Long>, val intervalHours: Int?)

    /**
     * Downloads [url] directly, or through a local proxy ([proxy], see [ViaServer]).
     * Besides the user agent, sends the subscription's device id ([Subscription.hwid]) the way subscription panels
     * with a device limit expect (`x-hwid`); the device's system, its version and model (`x-device-os`, `x-ver-os`,
     * `x-device-model`) only if the user allows it ([AppSettings.sendDeviceModel]).
     */
    private fun fetch(sub: Subscription, proxy: Socks?, userAgent: String): Fetched {
        val headers = buildMap {
            put("User-Agent", userAgent)
            put("Accept", "*/*")
            put("x-hwid", sub.hwid.ifEmpty { Repo.deviceId() })
            if (Repo.settings.value.sendDeviceModel) {
                put("x-device-os", Host.system)
                put("x-ver-os", Host.systemVersion)
                put("x-device-model", Host.deviceModel)
            }
        }
        val r = Net.get(sub.url, headers, MAX_SUBSCRIPTION.toLong(), proxy, connectTimeoutMs = if (proxy != null) 15_000 else 8_000)
        return Fetched(
            r.text,
            r.header("profile-title")?.let(::decodeTitle),
            r.header("subscription-userinfo")?.let(::parseUserInfo).orEmpty(),
            SubMath.parseInterval(r.header("profile-update-interval")),
        )
    }

    /** Through the user's servers, best first (see [ViaServer.candidates]). */
    private suspend fun fetchViaServer(sub: Subscription, userAgent: String): Fetched {
        val url = sub.url
        var last: Exception? = null
        for (server in ViaServer.candidates(prefer = sub.id)) {
            try {
                return ViaServer.withProxy(server) { socks -> fetch(sub, socks, userAgent) }.also {
                    AppLog.debug(TAG, "${safeUrl(url)}: downloaded through ${server.name}")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.debug(TAG, "${safeUrl(url)}: through ${server.name} failed: ${e.message}")
                last = e
            }
        }
        throw last ?: NetError(NetError.Kind.UNREACHABLE, text(S.err_sub_no_server))
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.debug(TAG, "${safeUrl(sub.url)}: through servers failed (${e.message}), trying directly")
            }
            return direct() to false
        }
        return try {
            direct() to false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.debug(TAG, "${safeUrl(sub.url)}: direct download failed (${e.message}), trying through a server")
            try {
                fetchViaServer(sub, userAgent) to true
            } catch (viaError: CancellationException) {
                throw viaError
            } catch (viaError: Exception) {
                // Report the direct failure: it is the one that tells what is wrong with the subscription.
                throw e
            }
        }
    }

    suspend fun update(sub: Subscription): kotlin.Result<Int> = withContext(Dispatchers.IO) {
        try {
            val span = Work.begin(WorkKind.SUB_UPDATE)
            val ua = Repo.settings.value.userAgent
            val startedAt = nowMs()
            var (got, viaServer) = try { download(sub, ua).also { span.bytes = it.first.body.length.toLong() } } finally { Work.end(span) }
            Work.count(WorkKind.SUB_UPDATE, bytes = got.body.length.toLong())
            AppLog.debug(TAG, "${safeUrl(sub.url)}: ${got.body.length / 1024} KB in ${(nowMs() - startedAt) / 100 / 10.0} s, " +
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
                    AppLog.event(LText.of(S.ev_sub_default_ua, Repo.subName(sub)), AppLog.Kind.WARN)
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
                        S.ev_less_secure, Repo.displayName(s),
                        LText.of(if (Insecurity.Issue.NO_ENCRYPTION in now) S.ins_no_encryption else S.ins_no_cert_check),
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
                    updatedAt = nowMs(),
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
                    LText.of(S.ev_sub_updated, Repo.subscription(sub.id)?.let(Repo::subName).orEmpty(), servers.size),
                    if (added > 0 || gone > 0) LText.of(S.ev_sub_changes, added, gone) else "",
                    if (viaServer) LText.of(S.ev_sub_via_server) else "",
                    if (errors.isNotEmpty()) LText.of(S.ev_sub_skipped, errors.size) else "",
                )
            )
            if (errors.isNotEmpty()) AppLog.debug(TAG, "skipped in ${safeUrl(sub.url)}: ${errors.map { it.detail ?: it.text }.distinct().joinToString("; ")}")
            failed.remove(sub.id)
            kotlin.Result.success(servers.size)
        } catch (e: CancellationException) {
            // A cancelled update is not a failure: no "subscription failed" event, no stored error, no retry
            // backoff — the caller stopped it on purpose.
            throw e
        } catch (e: Exception) {
            val explained = ImportErrors.forSubscription(sub.url, e).text
            AppLog.event(LText.of(S.ev_sub_failed, Repo.subName(sub), explained), AppLog.Kind.ERROR)
            AppLog.debug(TAG, "subscription ${safeUrl(sub.url)} failed", e)
            // Stored as the plain-language explanation (shown in the subscription row).
            Repo.subscription(sub.id)?.let { Repo.upsertSubscription(it.copy(lastError = explained)) }
            failed[sub.id] = (failed[sub.id]?.first ?: 0).let { n -> n + 1 to nowMs() + SubMath.retryDelayMs(n + 1) }
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
        val now = nowMs()
        if (now - pendingTriedAt < 5 * 60_000L) return
        if (Repo.subscriptions.value.none { it.updatedAt == 0L }) return
        pendingTriedAt = now
        AppLog.debug(TAG, "connected: trying subscriptions that were never downloaded")
        // A new connection is a new chance: these don't wait for their retry time.
        Repo.subscriptions.value.filter { it.updatedAt == 0L }.forEach { update(it) }
        resolveCountries()
    }

    /** Failed scheduled updates in a row and when the next scheduled try may start, per subscription (this run only). */
    private val failed = LockedMap<String, Pair<Int, Long>>()

    /** Refreshes subscriptions older than the configured interval. */
    suspend fun updateDue() {
        val s = Repo.settings.value
        // Each subscription on its own schedule: the provider's interval if it gives one (not too often),
        // else the setting; the battery saving mode doubles it and avoids mobile data (SubMath.due).
        val now = nowMs()
        val metered = app.borderless.core.Power.metered
        val due = Repo.subscriptions.value.filter { SubMath.due(it, now, s.subUpdateHours, s.ecoOn, metered) && (failed[it.id]?.second ?: 0L) <= now }
        if (due.isEmpty()) return
        AppLog.debug(TAG, "scheduled update of ${due.size} subscription(s): ${due.joinToString { safeUrl(it.url) }}")
        due.forEach { update(it) }
        resolveCountries()
    }

    /**
     * Country is the exit seen through each individual server, not the address the app dials.
     * Shared gateways can serve dozens of countries. This runs in the background so an import or
     * subscription update never waits for a full set of individual exit checks.
     */
    fun resolveCountries() {
        countryWanted.store(true)
        countryScope.launch {
            if (!countryLock.tryLock()) return@launch
            try {
                while (countryWanted.exchange(false)) resolveCountriesNow()
            } finally {
                countryLock.unlock()
                if (countryWanted.load()) resolveCountries()
            }
        }
    }

    private val countryScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO + Errors.handler)
    private val countryLock = kotlinx.coroutines.sync.Mutex()
    private val countryWanted = AtomicBoolean(false)
    /** Per SERVER, not host: one gateway can route different links to different exits. */
    private val lookupFailed = LockedMap<String, Long>()
    private const val LOOKUP_RETRY_MS = 24 * 3_600_000L

    private suspend fun resolveCountriesNow() = coroutineScope {
        // Remove obsolete ingress-IP results immediately; keep the name as a temporary hint while
        // a server is unavailable. Manually chosen and verified exit countries remain untouched.
        Repo.applyCountryNameFallbacks()
        val now = nowMs()
        val missing = Repo.servers.value.filter {
            Countries.needsIpLookup(it) && (lookupFailed[it.id] ?: 0L) + LOOKUP_RETRY_MS <= now
        }
        if (missing.isEmpty()) return@coroutineScope
        val sem = kotlinx.coroutines.sync.Semaphore(2)
        missing.map { server -> async {
            sem.withPermit {
                val code = try {
                    ViaServer.withProxy(server) { socks -> ExitCountryLookup.query(socks) }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { null }
                if (code != null) {
                    // Repo rechecks the manual override at commit time too.
                    Repo.setCountries(mapOf(server.id to code))
                    AppLog.debug(TAG, "exit country for ${server.name}: $code")
                } else {
                    lookupFailed[server.id] = nowMs()
                    AppLog.debug(TAG, "exit country for ${server.name}: unavailable; kept the name hint")
                }
            }
        } }.awaitAll()
    }

    private const val MAX_SUBSCRIPTION = 10_000_000

    /** Servers taken from one subscription (a 10 MB body could hold ~100 000 links, SECURITY.md S19). */
    private const val MAX_SUB_SERVERS = 2000

    fun isSecureUrl(url: String) = url.trim().startsWith("https://", ignoreCase = true)

    /** A subscription URL for logs: host only, the path usually carries the access token. */
    fun safeUrl(url: String): String = urlHost(url) ?: "?"

    /** Header values arrive as ISO-8859-1; panels send either base64 or raw UTF-8 bytes. */
    private fun decodeTitle(raw: String): String =
        if (raw.startsWith("base64:")) ShareUrl.decodeBase64(raw.removePrefix("base64:")) ?: raw
        else latin1AsUtf8(raw)

    private fun parseUserInfo(raw: String): Map<String, Long> =
        raw.split(';').mapNotNull { part ->
            val k = part.substringBefore('=').trim().lowercase()
            val v = part.substringAfter('=', "").trim().toDoubleOrNull()?.toLong()
            if (k.isNotEmpty() && v != null) k to v else null
        }.toMap()
}
