package tv.cypher.tools

import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import tv.cypher.core.Formats
import tv.cypher.core.Languages

/** iptv-org's `feeds.json`: what language each channel actually broadcasts in. */
object Feeds {
    /** Channel id -> language name, from the channel's main feed (else its first feed). Unknown codes are left out. */
    fun languages(json: String): Map<String, String> {
        val out = HashMap<String, String>()
        val fromMain = HashSet<String>()
        for (e in Formats.compact.parseToJsonElement(json).jsonArray) {
            val f = e.jsonObject
            val channel = f["channel"]?.jsonPrimitive?.content ?: continue
            val code = (f["languages"] as? kotlinx.serialization.json.JsonArray)?.firstOrNull()?.jsonPrimitive?.content ?: continue
            val lang = Languages.fromIso(code) ?: continue
            if (f["is_main"]?.jsonPrimitive?.booleanOrNull == true) { out[channel] = lang; fromMain.add(channel) }
            else if (channel !in fromMain) out.putIfAbsent(channel, lang)
        }
        return out
    }
}
