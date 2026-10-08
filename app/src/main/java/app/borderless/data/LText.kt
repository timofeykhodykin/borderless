package app.borderless.data

import androidx.annotation.StringRes
import app.borderless.Res
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.util.Locale

/**
 * Text stored language-neutral and rendered in whatever language the app uses when it is shown:
 * parts are plain strings (server names) or string resources with arguments (which may be LText too).
 * Log events use it, so switching the app language also switches the event log.
 */
class LText private constructor(private val parts: List<Part>) {
    private sealed interface Part
    private class Raw(val text: String) : Part
    private class Ref(val name: String, val args: List<Any>) : Part

    fun render(locale: Locale? = null): String = parts.joinToString("") { p ->
        when (p) {
            is Raw -> p.text
            is Ref -> {
                val args = p.args.map { if (it is LText) it.render(locale) else it }.toTypedArray()
                Res.byName(p.name, locale, *args) ?: p.name
            }
        }
    }

    fun toJson(): JsonElement = buildJsonArray {
        parts.forEach { p ->
            add(
                when (p) {
                    is Raw -> JsonPrimitive(p.text)
                    is Ref -> buildJsonObject {
                        put("r", p.name)
                        put("a", JsonArray(p.args.map(::argJson)))
                    }
                }
            )
        }
    }

    companion object {
        /** Like `getString(id, args)`, but resolved when shown. Args: String, Int, Long or LText. */
        fun of(@StringRes id: Int, vararg args: Any): LText = LText(listOf(Ref(Res.name(id), args.map(::normalize))))

        /** Concatenation of strings and LTexts. */
        fun concat(vararg parts: Any): LText = LText(parts.flatMap {
            when (it) {
                is LText -> it.parts
                else -> listOf(Raw(it.toString()))
            }
        })

        fun raw(text: String) = LText(listOf(Raw(text)))

        fun fromJson(e: JsonElement): LText = LText(e.jsonArray.map { p ->
            if (p is JsonPrimitive) Raw(p.content)
            else Ref(p.jsonObject["r"]!!.jsonPrimitive.content, p.jsonObject["a"]!!.jsonArray.map(::argFrom))
        })

        private fun normalize(a: Any): Any = when (a) {
            is LText, is String -> a
            is Int -> a
            is Long -> a
            is Number -> a.toLong()
            else -> a.toString()
        }

        private fun argJson(a: Any): JsonElement = when (a) {
            is LText -> buildJsonObject { put("t", a.toJson()) }
            is Int -> buildJsonObject { put("i", a) }
            is Long -> buildJsonObject { put("l", a) }
            else -> buildJsonObject { put("s", a.toString()) }
        }

        private fun argFrom(e: JsonElement): Any {
            val o = e as JsonObject
            return when {
                "t" in o -> fromJson(o["t"]!!)
                "i" in o -> o["i"]!!.jsonPrimitive.long.toInt()
                "l" in o -> o["l"]!!.jsonPrimitive.long
                else -> o["s"]!!.jsonPrimitive.content
            }
        }
    }
}
