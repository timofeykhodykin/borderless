package app.borderless.demo

import android.content.Context
import app.borderless.core.Phase
import app.borderless.core.TunnelState
import app.borderless.core.TunnelStatus
import app.borderless.data.Activity
import app.borderless.data.Group
import app.borderless.data.Repo
import app.borderless.data.StatsDb
import app.borderless.data.Subscription
import kotlin.random.Random

/**
 * Made-up servers, pings and a day of statistics for README screenshots (`./gradlew assembleDebug -Pdemo`
 * builds `app.borderless.demo` with this source set). Nothing here reaches a real server: the addresses are
 * example.net, and the "connected" state is only shown, no tunnel is started.
 */
object Demo {
    private const val SUB = "demo-sub"
    private val cities = listOf(
        "Frankfurt" to 64, "Amsterdam" to 71, "Helsinki" to 83, "Stockholm" to 92, "Warsaw" to 104,
        "Paris" to 118, "London" to 126, "Zurich" to 139, "Vienna" to 151, "Istanbul" to 188,
        "New York" to 214, "Toronto" to 236, "Tokyo" to 312, "Singapore" to 344, "Sydney" to 0,
    )

    fun seed(context: Context) {
        if (Repo.servers.value.isNotEmpty()) { showConnected(); return }
        val now = System.currentTimeMillis()
        val group = Repo.createGroup("Work")
        val provider = Repo.createGroup("Example provider")
        Repo.upsertSubscription(
            Subscription(SUB, "Example provider", "https://sub.example.net/demo", groupId = provider.id,
                updatedAt = now - 40 * 60_000L, uploadBytes = 3_400_000_000, downloadBytes = 18_900_000_000, totalBytes = 100_000_000_000,
                expire = now / 1000 + 86_400L * 74, providerHours = 12),
        )
        fun link(i: Int, name: String) = when (i % 3) {
            0 -> "vless://00000000-0000-4000-8000-00000000000$i@s$i.example.net:443?security=reality&sni=www.example.com&pbk=AAAA&fp=chrome&flow=xtls-rprx-vision&type=tcp#$name"
            1 -> "trojan://example@s$i.example.net:443?security=tls&type=ws&path=%2Fws#$name"
            else -> "vless://00000000-0000-4000-8000-00000000000$i@s$i.example.net:443?security=tls&type=xhttp&path=%2Fx#$name"
        }
        val own = Repo.buildServers(cities.take(4).mapIndexed { i, (c, _) -> link(i, c) }, null, Group.DEFAULT_ID).first
        val work = Repo.buildServers(listOf(link(5, "Office"), link(6, "Backup")), null, group.id).first
        val sub = Repo.buildServers(cities.drop(4).mapIndexed { i, (c, _) -> link(i + 7, c) }, SUB).first
        Repo.addServers(own + work)
        Repo.replaceSubscriptionServers(SUB, sub)
        val all = Repo.servers.value
        val base = (cities.map { it.second } + listOf(97, 132)).let { b -> all.mapIndexed { i, s -> s.id to b.getOrElse(i) { 150 } } }.toMap()
        val r = Random(7)
        // Pings shown on the main screen and the map (one server never answers).
        all.forEach { s ->
            val ms = base.getValue(s.id).takeIf { it > 0 }?.let { it + r.nextInt(-6, 9) }
            Repo.recordPing(s.id, ms)
        }
        // A day of statistics: scans every 10 minutes, the tunnel on most of the time, power samples.
        val day = 24 * 3_600_000L
        val start = now - day
        var t = start
        val current = all.first().id
        // The tunnel's day: off at night, a short spell without a server in the afternoon, a failover, an optimisation.
        fun phaseAt(x: Long): Pair<Phase, String?> {
            val h = (x - start) / 3_600_000.0
            return when {
                h < 0.3 -> Phase.OFF to null
                h < 7 -> Phase.CONNECTED to current
                h < 9 -> Phase.OFF to null
                h < 15 -> Phase.CONNECTED to current
                h < 15.15 -> Phase.DIRECT to null
                h < 18 -> Phase.CONNECTED to all[1].id
                else -> Phase.CONNECTED to current
            }
        }
        var p = start
        while (p < now) {
            val (ph, srv) = phaseAt(p)
            StatsDb.phase(ph, srv, p)
            p += StatsDb.HEARTBEAT_MS
        }
        StatsDb.event(StatsDb.Event.LOST, current, ts = start + 15 * 3_600_000L)
        StatsDb.event(StatsDb.Event.FAILOVER, all[1].id, current, ts = start + 15 * 3_600_000L + 9 * 60_000L)
        StatsDb.event(StatsDb.Event.OPTIMIZE, current, all[1].id, ts = start + 18 * 3_600_000L)
        while (t < now) {
            val evening = ((t - start) / 3_600_000L) in 14..15
            var ok = 0
            all.forEachIndexed { i, s ->
                val b = base.getValue(s.id)
                val down = b == 0 || (evening && i % 3 == 0) || r.nextInt(100) < 4
                val ms = if (down) null else (b * (1 + 0.25 * kotlin.math.sin((t - start) / 7_200_000.0 + i)) + r.nextInt(0, 25)).toInt()
                if (ms != null) ok++
                StatsDb.sample(s.id, ms, StatsDb.Kind.PROBE, t + i * 400L)
            }
            // Checks of the server in use while connected (the "current server ping" figures).
            if (phaseAt(t).first == Phase.CONNECTED) phaseAt(t).second?.let { id ->
                for (k in 0 until 3) StatsDb.sample(id, base.getValue(id) + r.nextInt(-5, 30), StatsDb.Kind.HEALTH, t + k * 180_000L)
            }
            StatsDb.scan(ok, if (evening) 10 else all.size, null, all.size, "SCAN_BACKGROUND", t)
            StatsDb.power(StatsDb.PowerRow(t, 80, false, r.nextInt(3) > 0, false, r.nextInt(4) == 0, 900L + r.nextInt(600), 140 * 1024L, 4800, 3900))
            // The battery saving mode (19:00–21:00 of the demo day) does much less background work.
            val eco = ((t - start) / 3_600_000L) in 19..20
            val f = if (eco) 0.3 else 1.0
            StatsDb.activity(t, listOf(
                StatsDb.ActivityTotal(Activity.Kind.PING_RIDE.name, 3, 0, (900 * f).toLong(), 300, (1300 * f).toLong()),
                StatsDb.ActivityTotal(Activity.Kind.SCAN_BACKGROUND.name, if (eco) 0 else 1, 0, (2600 * f).toLong(), (1500 * f).toLong(), (4200 * f).toLong()),
                StatsDb.ActivityTotal(Activity.Kind.PROBE.name, all.size.toLong(), 0, 0, 0, 0),
                StatsDb.ActivityTotal(Activity.Kind.CORE_BASE.name, 1, 0, 0, 5200, 5900),
                StatsDb.ActivityTotal(Activity.Kind.DB_COMMIT.name, 30, 0, 0, 60, 70),
            ))
            StatsDb.traffic(4_000_000L + r.nextInt(9_000_000), 40_000_000L + r.nextInt(90_000_000), 300_000, 2_000_000, current, t)
            t += 10 * 60_000L
        }
        StatsDb.settings(start, mapOf("strategy" to "STABLE", "ecoOn" to "false"))
        StatsDb.settings(start + 19 * 3_600_000L, mapOf("ecoOn" to "true"))
        StatsDb.settings(start + 21 * 3_600_000L, mapOf("ecoOn" to "false"))
        Repo.updateSettings { it.copy(lastServerId = current) }
        showConnected()
    }

    /** The main screen as when connected (no tunnel is started). */
    private fun showConnected() {
        val s = Repo.servers.value.firstOrNull() ?: return
        TunnelState.set(TunnelStatus(Phase.CONNECTED, s.id, ping = Repo.pings.value[s.id]?.ms))
    }
}
