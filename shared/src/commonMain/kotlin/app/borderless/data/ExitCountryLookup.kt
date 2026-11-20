package app.borderless.data

import app.borderless.net.HttpRequest
import app.borderless.net.HttpResponse
import app.borderless.net.Net
import app.borderless.net.Socks
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Ask where THIS proxy exits. Shared ingress hosts can route each account to a different country. */
internal object ExitCountryLookup {
    fun query(proxy: Socks, request: (HttpRequest) -> HttpResponse = Net::request): String? {
        val services = listOf(
            "https://api.country.is/" to "country",
            "https://ipwho.is/?fields=success,country_code" to "country_code",
            "https://ipinfo.io/country" to null,
        )
        for ((url, field) in services) {
            val code = try {
                val r = request(HttpRequest(url, proxy = proxy, connectTimeoutMs = 4000, readTimeoutMs = 4000, maxBytes = 4096))
                if (r.code !in 200..299) continue
                if (field == null) r.text.trim() else {
                    val body = Json.parseToJsonElement(r.text) as? JsonObject ?: continue
                    if ((body["success"] as? JsonPrimitive)?.contentOrNull == "false") continue
                    (body[field] as? JsonPrimitive)?.contentOrNull
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { null }
            val normalized = code?.uppercase()?.takeIf { it.length == 2 && it.all(Char::isLetter) && it !in setOf("XX", "ZZ") }
            if (normalized != null) return normalized
        }
        return null
    }
}
