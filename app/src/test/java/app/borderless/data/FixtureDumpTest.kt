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
            // 3.0.0: WDTT links and their own settings (streams, device id, the server's limit).
            Server("s3", "WDTT reserve", "wdtt://203.0.113.7:56000:56001:9000:examplePass0001:exampleCallIdentifier#WDTT reserve",
                "WDTT · WireGuard", "203.0.113.7", 56000, groupId = "own", country = "NL", addedAt = 1759700000002,
                limitedStreams = 3, limitedDevice = "exampledevice0001", limitedIgnoreLimit = true),
        )
        File(out, "servers.json").writeText(json.encodeToString(ListSerializer(Server.serializer()), servers))
        File(out, "subscriptions.json").writeText(json.encodeToString(ListSerializer(Subscription.serializer()), listOf(
            Subscription("sub1", "My sub", "https://sub.example/x", groupId = "g1", updatedAt = 1759700000000, uploadBytes = 1, downloadBytes = 2,
                totalBytes = 3, expire = 1790000000, providerHours = 6, viaServer = true, customName = "Mine", hwid = "0123456789abcdef0123456789abcdef"),
        )))
        File(out, "groups.json").writeText(json.encodeToString(ListSerializer(Group.serializer()), listOf(Group("own"), Group("g1", "Friends"))))
        File(out, "pings.json").writeText(json.encodeToString(MapSerializer(String.serializer(), PingRecord.serializer()), mapOf(
            "s1" to PingRecord(80, 1759700000000, listOf(90, 80), 1759700000000, 0, checks = listOf(120, 95), outcomes = listOf(true, false, true)),
            "s2" to PingRecord(null, 1759700000000, emptyList(), 0, 3),
        )))
        File(out, "settings.json").writeText(json.encodeToString(AppSettings.serializer(), AppSettings(
            strategy = Strategy.FAILOVER, defaultStrategy = Strategy.STABLE, autoActive = true, genericNames = true, palette = "mono",
            statusIcon = true, healthIntervalSec = 30, appsDirect = setOf("com.example.app"), appsTunnel = setOf("com.example.other"), lastServerId = "s1", statsDays = 90,
            ecoOn = true, ecoBySaver = true, saverSeen = true, strategyBeforeEco = Strategy.STABLE, deviceId = "0123456789abcdef",
            // 2.0.0
            countries = setOf("DE", "-"), countrySort = CountrySort.STABLE, strategyBeforeCountry = Strategy.MANUAL, serverBeforeCountry = "s2",
            notifyMode = NotifyMode.ALWAYS, noServerAction = NoServerAction.PAUSE, avoidInsecure = true, sendDeviceModel = true,
            confirmShare = true, carefulScans = true, pingAllSwitches = false, verboseLog = true, verboseSince = 1759700000000,
            regions = setOf("ru", "u-example"), manualServerId = "s1",
            // 3.0.0
            limitedAuto = false,
        )))
        File(out, "zones.json").writeText(json.encodeToString(ListSerializer(CustomZone.serializer()), listOf(
            CustomZone("u-example", "Example", direct = listOf("domain:a.example", "10.1.0.0/16"), server = listOf("full:b.example"),
                apps = listOf("com.example.app"), listSizes = mapOf("ext:u-example-ab12.geosite.dat:XX" to 3)),
        )))
        File(out, "auto.json").writeText(json.encodeToString(AutoState.serializer(), AutoState(setOf("s1"), mapOf("s1" to 1759700000000), 5, 2)))
    }
}
