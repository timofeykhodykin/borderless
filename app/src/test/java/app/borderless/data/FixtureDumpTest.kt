package app.borderless.data

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import org.junit.Test
import java.io.File

/**
 * Dev tool for releases (data compatibility): writes data files the way this version's Repo writes them,
 * with example links only, into `FIXTURE_OUT` (e.g. app/src/test/resources/compat/<version>). Does
 * nothing without it. See CompatTest.
 */
class FixtureDumpTest {
    @Test
    fun dump() {
        val out = File(System.getenv("FIXTURE_OUT") ?: return).apply { mkdirs() }
        val json = StoreJson
        val servers = listOf(
            Server("s1", "🇩🇪 Frankfurt", "vless://11111111-1111-1111-1111-111111111111@a.example:443?security=reality&sni=x.com&pbk=AAAA&fp=chrome&flow=xtls-rprx-vision&type=tcp#Frankfurt",
                "VLESS · Reality", "a.example", 443, groupId = "own", country = "DE", addedAt = 1759700000000, customName = "Work"),
            Server("s2", "Sub server", "trojan://pw@b.example:443?security=tls&sni=x.com&type=ws&path=%2F#Sub", "Trojan · WS TLS", "b.example", 443,
                subscriptionId = "sub1", groupId = "g1", country = "NL", countryManual = true, disabled = true, addedAt = 1759700000001),
        )
        File(out, "servers.json").writeText(json.encodeToString(ListSerializer(Server.serializer()), servers))
        File(out, "subscriptions.json").writeText(json.encodeToString(ListSerializer(Subscription.serializer()), listOf(
            Subscription("sub1", "My sub", "https://sub.example/x", groupId = "g1", updatedAt = 1759700000000, uploadBytes = 1, downloadBytes = 2,
                totalBytes = 3, expire = 1790000000, providerHours = 6, viaServer = true, customName = "Mine"),
        )))
        File(out, "groups.json").writeText(json.encodeToString(ListSerializer(Group.serializer()), listOf(Group("own"), Group("g1", "Friends"))))
        File(out, "pings.json").writeText(json.encodeToString(MapSerializer(String.serializer(), PingRecord.serializer()), mapOf(
            "s1" to PingRecord(80, 1759700000000, listOf(90, 80), 1759700000000, 0), "s2" to PingRecord(null, 1759700000000, emptyList(), 0, 3),
        )))
        File(out, "settings.json").writeText(json.encodeToString(AppSettings.serializer(), AppSettings(
            strategy = Strategy.FAILOVER, defaultStrategy = Strategy.STABLE, autoActive = true, genericNames = true, palette = "mono",
            statusIcon = true, healthIntervalSec = 30, appsDirect = setOf("com.example.app"), appsTunnel = setOf("com.example.other"), lastServerId = "s1", statsDays = 90,
            ecoOn = true, ecoBySaver = true, saverSeen = true, strategyBeforeEco = Strategy.STABLE, deviceId = "0123456789abcdef",
        )))
        File(out, "auto.json").writeText(json.encodeToString(AutoState.serializer(), AutoState(setOf("s1"), mapOf("s1" to 1759700000000), 5, 2)))
    }
}
