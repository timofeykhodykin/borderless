package app.borderless.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Servers whose XHTTP transport sends a connection's whole upload as one long HTTP request: the modes
 * "stream-one" and "stream-up", and "auto" with Reality (Xray picks stream-one there, stream-up with
 * separate download settings). "packet-up" (what "auto" picks otherwise) sends the upload in many short
 * requests instead. Some networks stall a long request after its first kilobytes, so large uploads hang
 * while pings, which are small, still pass. The Stable strategy therefore takes such a server only when it
 * is clearly better: its latency counts as [FACTOR] × + [EXTRA_MS] (see [ProtocolWeights]).
 */
object SingleStream {
    const val FACTOR = 1.5
    const val EXTRA_MS = 100

    /** Pure (unit-tested): does the proxy (first outbound) upload in one stream? */
    fun detect(outbounds: List<JsonObject>): Boolean {
        val stream = outbounds.firstOrNull()?.get("streamSettings") as? JsonObject ?: return false
        val net = stream.str("network")?.lowercase()
        if (net != "xhttp" && net != "splithttp") return false
        val xhttp = (stream["xhttpSettings"] ?: stream["splithttpSettings"]) as? JsonObject
        val extra = when (val e = xhttp?.get("extra")) {
            is JsonObject -> e
            is JsonPrimitive -> e.contentOrNull?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
            else -> null
        }
        val mode = (xhttp?.str("mode") ?: extra?.str("mode"))?.lowercase()?.ifEmpty { null } ?: "auto"
        return when (mode) {
            "stream-one", "stream-up" -> true
            "auto" -> stream.str("security")?.lowercase() == "reality"
            else -> false
        }
    }

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
}
