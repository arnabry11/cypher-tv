package tv.cypher.tools

import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants

/**
 * Builds the `channels.xml` handed to the iptv-org/epg grabber: for each published channel, the first
 * site (in priority order) whose channel list maps it. Channels no site knows get no guide.
 */
object EpgChannels {
    class Result(val xml: String, val covered: Set<String>, val missing: List<String>)

    private class Entry(val site: String, val siteId: String, val lang: String, val xmltvId: String, val name: String)

    fun build(sites: List<Pair<String, String>>, ids: Set<String>): Result {
        val chosen = LinkedHashMap<String, Entry>()
        for ((_, xml) in sites) {
            for (e in parse(xml)) {
                val id = e.xmltvId.substringBefore('@')
                if (id in ids && id !in chosen) chosen[id] = Entry(e.site, e.siteId, e.lang, id, e.name)
            }
        }
        val out = buildString {
            appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
            appendLine("<channels>")
            for (e in chosen.values) {
                appendLine("""  <channel site="${esc(e.site)}" site_id="${esc(e.siteId)}" lang="${esc(e.lang)}" xmltv_id="${esc(e.xmltvId)}">${esc(e.name)}</channel>""")
            }
            appendLine("</channels>")
        }
        return Result(out, chosen.keys.toSet(), ids.filter { it !in chosen }.sorted())
    }

    private fun parse(xml: String): List<Entry> {
        val f = XMLInputFactory.newInstance().apply {
            setProperty(XMLInputFactory.SUPPORT_DTD, false)
            setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
        }
        val r = f.createXMLStreamReader(xml.reader())
        val out = ArrayList<Entry>()
        var site = ""; var siteId = ""; var lang = ""; var id = ""
        val text = StringBuilder()
        var inChannel = false
        while (r.hasNext()) {
            when (r.next()) {
                XMLStreamConstants.START_ELEMENT -> if (r.localName == "channel") {
                    inChannel = true; text.setLength(0)
                    site = r.getAttributeValue(null, "site").orEmpty()
                    siteId = r.getAttributeValue(null, "site_id").orEmpty()
                    lang = r.getAttributeValue(null, "lang").orEmpty()
                    id = r.getAttributeValue(null, "xmltv_id").orEmpty()
                }
                XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> if (inChannel) text.append(r.text)
                XMLStreamConstants.END_ELEMENT -> if (r.localName == "channel" && inChannel) {
                    inChannel = false
                    if (id.isNotEmpty() && siteId.isNotEmpty()) out.add(Entry(site, siteId, lang, id, text.toString().trim()))
                }
            }
        }
        r.close()
        return out
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
