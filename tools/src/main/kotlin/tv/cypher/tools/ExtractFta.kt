package tv.cypher.tools

import tv.cypher.core.FtaChannel
import tv.cypher.core.Languages
import tv.cypher.core.Names
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants

/** Reads the MIB "FTA channels" spreadsheet (xlsx) into `data/fta.json`. No spreadsheet library needed. */
object ExtractFta {
    fun parse(xlsx: InputStream): List<FtaChannel> {
        var shared: ByteArray? = null
        var sheet: ByteArray? = null
        ZipInputStream(xlsx).use { z ->
            generateSequence { z.nextEntry }.forEach { e ->
                when (e.name) {
                    "xl/sharedStrings.xml" -> shared = z.readBytes()
                    "xl/worksheets/sheet1.xml" -> sheet = z.readBytes()
                }
            }
        }
        val strings = shared?.let(::readSharedStrings) ?: emptyList()
        val rows = sheet?.let { readRows(it, strings) } ?: error("xl/worksheets/sheet1.xml not found")

        val headerRow = rows.indexOfFirst { r -> r.values.any { it.trim().equals("Channel Name", true) } }
        require(headerRow >= 0) { "No 'Channel Name' header row found" }
        val cols = rows[headerRow].entries.associate { (i, v) -> v.trim().lowercase() to i }
        val cName = cols.getValue("channel name")
        val cCompany = cols["company"]
        val cCat = cols["category"]
        val cLang = cols["language"]

        var company: String? = null
        val out = ArrayList<FtaChannel>()
        for (r in rows.drop(headerRow + 1)) {
            cCompany?.let { c -> r[c]?.let(::clean)?.takeIf { it.isNotEmpty() }?.let { company = it } }
            val name = r[cName]?.let(::clean)?.takeIf { it.isNotEmpty() } ?: continue
            val catRaw = cCat?.let { r[it] }.orEmpty()
            out.add(
                FtaChannel(
                    name = name,
                    aliases = Names.aliases(name),
                    cat = if (catRaw.trim().startsWith("non", true) || !catRaw.contains("news", true)) "Non-News" else "News",
                    languages = cLang?.let { r[it] }?.let(Languages::clean).orEmpty(),
                    company = company,
                ),
            )
        }
        return out
    }

    private fun clean(s: String) = s.replace(Regex("\\s+"), " ").trim()

    private fun factory() = XMLInputFactory.newInstance().apply {
        setProperty(XMLInputFactory.SUPPORT_DTD, false)
        setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
    }

    private fun readSharedStrings(xml: ByteArray): List<String> {
        val r = factory().createXMLStreamReader(xml.inputStream())
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var inSi = false
        var inPhonetic = false
        var inT = false
        while (r.hasNext()) {
            when (r.next()) {
                XMLStreamConstants.START_ELEMENT -> when (r.localName) {
                    "si" -> { inSi = true; sb.setLength(0) }
                    "rPh" -> inPhonetic = true
                    "t" -> inT = inSi && !inPhonetic
                }
                XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> if (inT) sb.append(r.text)
                XMLStreamConstants.END_ELEMENT -> when (r.localName) {
                    "t" -> inT = false
                    "rPh" -> inPhonetic = false
                    "si" -> { inSi = false; out.add(sb.toString()) }
                }
            }
        }
        return out
    }

    /** Each row as column index -> text. */
    private fun readRows(xml: ByteArray, strings: List<String>): List<Map<Int, String>> {
        val r = factory().createXMLStreamReader(xml.inputStream())
        val rows = ArrayList<Map<Int, String>>()
        var cur: MutableMap<Int, String>? = null
        var col = 0
        var type: String? = null
        val text = StringBuilder()
        var capture = false
        while (r.hasNext()) {
            when (r.next()) {
                XMLStreamConstants.START_ELEMENT -> when (r.localName) {
                    "row" -> cur = HashMap()
                    "c" -> {
                        col = columnIndex(r.getAttributeValue(null, "r").orEmpty())
                        type = r.getAttributeValue(null, "t")
                        text.setLength(0)
                    }
                    "v", "t" -> { capture = true }
                }
                XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> if (capture) text.append(r.text)
                XMLStreamConstants.END_ELEMENT -> when (r.localName) {
                    "v", "t" -> capture = false
                    "c" -> {
                        val raw = text.toString()
                        val value = if (type == "s") strings.getOrNull(raw.trim().toIntOrNull() ?: -1) else raw
                        if (!value.isNullOrBlank()) cur?.put(col, value)
                    }
                    "row" -> { cur?.let { rows.add(it) }; cur = null }
                }
            }
        }
        return rows
    }

    private fun columnIndex(ref: String): Int {
        var n = 0
        for (ch in ref) { if (ch in 'A'..'Z') n = n * 26 + (ch - 'A' + 1) else break }
        return n - 1
    }
}
