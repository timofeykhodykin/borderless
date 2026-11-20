package app.borderless.config

import app.borderless.data.AppSettings
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Test
import java.io.File

/**
 * Developer tool, not a real test: with BORDERLESS_LINKS=<file with links> and BORDERLESS_OUT=<dir>,
 * writes a desktop Xray config (SOCKS inbound on 10800+i) per link, so the generated outbounds
 * can be checked against real servers with the desktop `xray` binary.
 */
class DumpConfigsTest {
    @Test
    fun dump() {
        val input = System.getenv("BORDERLESS_LINKS") ?: return
        val out = File(System.getenv("BORDERLESS_OUT") ?: return).apply { mkdirs() }
        val links = LinkParser.extractLinks(File(input).readText())
        links.forEachIndexed { i, link ->
            val parsed = runCatching { LinkParser.parse(link) }.getOrElse {
                println("SKIP $i: ${it.message}")
                return@forEachIndexed
            }
            val probe = kotlinx.serialization.json.Json.parseToJsonElement(XrayConfig.probe(parsed.outbounds, AppSettings())).let { it as kotlinx.serialization.json.JsonObject }
            val cfg = buildJsonObject {
                putJsonObject("log") { put("loglevel", "warning") }
                putJsonArray("inbounds") {
                    addJsonObject {
                        put("port", 10800 + i)
                        put("listen", "127.0.0.1")
                        put("protocol", "socks")
                        putJsonObject("settings") { put("udp", true) }
                    }
                }
                put("outbounds", probe["outbounds"]!!)
            }
            File(out, "%03d.json".format(i)).writeText(cfg.toString())
            File(out, "%03d.name".format(i)).writeText(parsed.name + " | " + parsed.protocol)
        }
        // Full on-device configs (TUN inbound, DNS, routing) for `xray run -test`.
        LinkParser.parse(links.first()).outbounds.let { ob ->
            File(out, "full-zones.json").writeText(XrayConfig.build(XrayConfig.Mode.PROXY, ob, AppSettings(adFilter = true, directDomains = listOf("example.ru", "10.0.0.0/8"), proxyDomains = listOf("youtube.com"))))
            File(out, "full-listed.json").writeText(XrayConfig.build(XrayConfig.Mode.PROXY, ob, AppSettings(routingMode = app.borderless.data.RoutingMode.LISTED_ONLY)))
            File(out, "full-norouting.json").writeText(XrayConfig.build(XrayConfig.Mode.PROXY, ob, AppSettings(routingEnabled = false, fragment = true)))
            File(out, "full-direct.json").writeText(XrayConfig.build(XrayConfig.Mode.DIRECT, emptyList(), AppSettings()))
            File(out, "full-pause.json").writeText(XrayConfig.build(XrayConfig.Mode.PAUSE, emptyList(), AppSettings()))
        }
        println("dumped ${links.size} configs to $out")
    }
}
