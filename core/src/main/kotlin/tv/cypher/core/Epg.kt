package tv.cypher.core

import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** Guide data as compact `epg.json.gz`. `{channelId: [[start, stop, title], ...]}` in epoch seconds. */
object Epg {
    fun writeGzip(data: Map<String, List<Programme>>, out: OutputStream) {
        GZIPOutputStream(out).bufferedWriter(Charsets.UTF_8).use { w ->
            w.write("{")
            var firstCh = true
            for ((id, list) in data) {
                if (!firstCh) w.write(",")
                firstCh = false
                w.write(JsonPrimitive(id).toString()); w.write(":[")
                list.forEachIndexed { i, p ->
                    if (i > 0) w.write(",")
                    w.write("[${p.start},${p.stop},${JsonPrimitive(p.title)}]")
                }
                w.write("]")
            }
            w.write("}")
        }
    }

    fun readGzip(input: InputStream): Map<String, List<Programme>> {
        val text = GZIPInputStream(input).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val root: JsonObject = Formats.compact.parseToJsonElement(text).jsonObject
        return root.mapValues { (_, v) ->
            (v as JsonArray).map { row ->
                val a = row.jsonArray
                Programme(a[0].jsonPrimitive.long, a[1].jsonPrimitive.long, a[2].jsonPrimitive.content)
            }
        }
    }
}
