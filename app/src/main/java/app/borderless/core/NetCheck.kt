package app.borderless.core

import app.borderless.data.Activity
import app.borderless.data.AppLog
import app.borderless.data.Energy
import app.borderless.data.Errors
import app.borderless.data.Regions
import app.borderless.data.Repo
import app.borderless.data.StatsDb
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * How far the phone's own network reaches outside the tunnel (our app is outside it): not at all, only the chosen
 * domain zones' own sites (a limited network: everything else, servers included, is out of reach), or the wider
 * internet too. Asked when no server answers, to tell "offline" and "limited" apart from "the servers are down".
 */
object NetCheck {
    enum class Reach { NONE, LIMITED, FULL }

    /** The whole check, all addresses at once (DNS included, which no socket timeout covers). */
    private const val DEADLINE_MS = 8_000L

    /** A result is reused this long while the network stays the same ([forget] on a change). */
    private const val FRESH_MS = 5 * 60_000L

    /** [pulse]: a shorter wait, it is repeated every few seconds anyway. */
    private const val PULSE_DEADLINE_MS = 4_000L

    private val COMMON = listOf("https://www.gstatic.com/generate_204", "https://cp.cloudflare.com/generate_204")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + Errors.handler)

    @Volatile
    private var last: Pair<Long, Reach>? = null

    /** Pure (unit-tested): what the answers mean. Without zone sites to ask, a network is either full or none. */
    fun classify(zoneAnswered: Boolean, commonAnswered: Boolean): Reach = when {
        commonAnswered -> Reach.FULL
        zoneAnswered -> Reach.LIMITED
        else -> Reach.NONE
    }

    /** The network changed: the next [reach] asks again. */
    fun forget() {
        last = null
    }

    /**
     * The latest result if younger than [maxAgeMs], else a new check. Nothing reachable at all: the failed checks of
     * servers since the last answer are taken out of their statistics ([StatsDb.forgetFailures]) — the network, not
     * the servers, failed. A limited network keeps them: which servers fail there is worth knowing.
     */
    suspend fun reach(maxAgeMs: Long = FRESH_MS): Reach {
        val r = last?.takeIf { System.currentTimeMillis() - it.first < maxAgeMs }?.second ?: check()
        if (r == Reach.NONE) StatsDb.forgetFailures()
        return r
    }

    /**
     * A quick look whether the network reaches further than [now] (NONE or LIMITED; repeated every few seconds while
     * so, see Engine.pulse): limited asks only the common sites, offline every site. Returns the reach found.
     */
    suspend fun pulse(now: Reach): Reach {
        val zone = if (now == Reach.LIMITED) emptyList() else zoneUrls()
        val answered = ask(zone + COMMON, PULSE_DEADLINE_MS)
        val reach = when {
            COMMON.any { it in answered } -> Reach.FULL
            // Limited: the zones' sites weren't asked, so nothing new is known.
            now == Reach.LIMITED -> Reach.LIMITED
            else -> classify(zone.any { it in answered }, false)
        }
        if (reach != now) AppLog.debug("NetCheck", "pulse: $now -> $reach (answered: ${answered.joinToString { URL(it).host }})")
        last = System.currentTimeMillis() to reach
        return reach
    }

    /**
     * Asks the zones' check sites and common ones at once. A common one answering settles it (full); otherwise
     * everything gets until [DEADLINE_MS]. One by one with their timeouts, a bad mobile network kept the radio up
     * for ~48 s per check (phone export 2026-10-14: a quarter of the app's energy that night).
     */
    suspend fun check(): Reach {
        val zone = zoneUrls()
        val answered = ask(zone + COMMON, DEADLINE_MS)
        val reach = classify(zone.any { it in answered }, COMMON.any { it in answered })
        AppLog.debug("NetCheck", "direct: $reach (answered: ${answered.joinToString { URL(it).host }.ifEmpty { "none" }})")
        last = System.currentTimeMillis() to reach
        return reach
    }

    private fun zoneUrls() = Regions.of(Repo.settings.value.regions).flatMap { it.checkUrls }.distinct()

    /** Asks [urls] at once; returns the ones that answered by the time a common one did, or by [deadline]. */
    private suspend fun ask(urls: List<String>, deadline: Long): Set<String> {
        val answered = ConcurrentHashMap.newKeySet<String>()
        Activity.count(Activity.Kind.NET_CHECK)
        Energy.track(Activity.Kind.NET_CHECK, exchanges = urls.size) {
            val full = CompletableDeferred<Unit>()
            // Not tied to this call: a blocked socket ignores cancellation, nobody waits for it after the deadline.
            val jobs = urls.map { url -> scope.launch { if (reachable(url, deadline)) { answered += url; if (url in COMMON) full.complete(Unit) } } }
            scope.launch { jobs.joinAll(); full.complete(Unit) }
            withTimeoutOrNull(deadline) { full.await() }
        }
        return answered.toSet()
    }

    private fun reachable(url: String, deadline: Long): Boolean = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = deadline.toInt()
        c.readTimeout = deadline.toInt()
        c.instanceFollowRedirects = false
        c.requestMethod = "HEAD"
        val code = c.responseCode
        c.disconnect()
        code in 100..599
    }.getOrDefault(false)
}
