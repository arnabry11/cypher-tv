package tv.cypher.core

import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** Guide data: XMLTV in, compact `epg.json.gz` out. `{channelId: [[start, stop, title], ...]}` in epoch seconds. */
object Epg {
    private val xmltvTime = Regex("""^(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})\s*([+-]\d{4})?$""")

    fun parseTime(s: String): Long {
        val m = xmltvTime.matchEntire(s.trim()) ?: throw IllegalArgumentException("Bad XMLTV time: $s")
        val f = m.groupValues.subList(1, 7).map { it.toInt() }
        val offset = m.groupValues[7].let {
            if (it.isEmpty()) 0 else {
                val sign = if (it[0] == '-') -1 else 1
                sign * (it.substring(1, 3).toInt() * 3600 + it.substring(3, 5).toInt() * 60)
            }
        }
        return epochDay(f[0], f[1], f[2]) * 86400 + f[3] * 3600 + f[4] * 60 + f[5] - offset
    }

    /** Days since 1970-01-01 (proleptic Gregorian). */
    private fun epochDay(y0: Int, m: Int, d: Int): Long {
        val y = if (m <= 2) y0 - 1 else y0
        val era = Math.floorDiv(y, 400)
        val yoe = y - era * 400
        val doy = (153 * (if (m > 2) m - 3 else m + 9) + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era.toLong() * 146097 + doe - 719468
    }

    /** Streams [input] and keeps programmes of [wanted] channels overlapping [fromEpoch, toEpoch). */
    fun fromXmltv(
        input: InputStream,
        wanted: Set<String>,
        fromEpoch: Long,
        toEpoch: Long,
    ): Map<String, List<Programme>> {
        val factory = XMLInputFactory.newInstance().apply {
            setProperty(XMLInputFactory.SUPPORT_DTD, false)
            setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
        }
        val r = factory.createXMLStreamReader(input)
        val acc = HashMap<String, MutableList<Programme>>()
        var channel: String? = null
        var start = 0L
        var stop = 0L
        var title: String? = null
        var inTitle = false
        val text = StringBuilder()
        var inProgramme = false
        try {
            while (r.hasNext()) {
                when (r.next()) {
                    XMLStreamConstants.START_ELEMENT -> when (r.localName) {
                        "programme" -> {
                            inProgramme = true; title = null
                            channel = r.getAttributeValue(null, "channel")?.substringBefore('@')
                            val s = r.getAttributeValue(null, "start")
                            val e = r.getAttributeValue(null, "stop")
                            if (s == null || e == null) { inProgramme = false } else {
                                try { start = parseTime(s); stop = parseTime(e) } catch (_: IllegalArgumentException) { inProgramme = false }
                            }
                        }
                        "title" -> if (inProgramme && title == null) { inTitle = true; text.setLength(0) }
                    }
                    XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> if (inTitle) text.append(r.text)
                    XMLStreamConstants.END_ELEMENT -> when (r.localName) {
                        "title" -> if (inTitle) { title = text.toString().trim(); inTitle = false }
                        "programme" -> {
                            val ch = channel
                            val t = title
                            if (inProgramme && ch != null && ch in wanted && !t.isNullOrEmpty() &&
                                stop > start && stop > fromEpoch && start < toEpoch
                            ) acc.getOrPut(ch) { ArrayList() }.add(Programme(start, stop, t))
                            inProgramme = false
                        }
                    }
                }
            }
        } finally {
            r.close()
        }
        return acc.mapValues { (_, v) ->
            v.sortedWith(compareBy({ it.start }, { it.stop })).distinct()
                // Drop programmes that start at the same time as an earlier one.
                .let { sorted -> sorted.filterIndexed { i, p -> i == 0 || sorted[i - 1].start != p.start } }
        }.toSortedMap()
    }

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
