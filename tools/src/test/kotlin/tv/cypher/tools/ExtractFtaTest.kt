package tv.cypher.tools

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals

class ExtractFtaTest {
    private fun xlsx(strings: List<String>, rows: String): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            fun put(n: String, c: String) { z.putNextEntry(ZipEntry(n)); z.write(c.toByteArray()); z.closeEntry() }
            put("xl/sharedStrings.xml", """<sst xmlns="x">${strings.joinToString("") { "<si><t>$it</t></si>" }}</sst>""")
            put("xl/worksheets/sheet1.xml", """<worksheet xmlns="x"><sheetData>$rows</sheetData></worksheet>""")
        }
        return bos.toByteArray()
    }

    private fun row(vararg cells: Pair<String, Int>) =
        "<row>" + cells.joinToString("") { (ref, idx) -> """<c r="$ref" t="s"><v>$idx</v></c>""" } + "</row>"

    @Test fun parsesChannelsWithCarriedCompanyAliasesAndCleanLanguages() {
        val s = listOf(
            "Title row", "S.No.", "Company", "S.No", "Channel Name", "Category", "Language", "Permission Type", // 0-7
            "ACME LTD", "ABP NEWS (earlier STAR NEWS)", "NEWS and Current Affairs", "Hindi, NA", "Uplinking",
            "9X JALWA", "NON- News and Current Affairs", "All Indian Scheduled langauge, English, Hindi", // 8-15
        )
        val rows = row("A1" to 0) +
            row("A2" to 1, "B2" to 2, "C2" to 3, "D2" to 4, "E2" to 5, "F2" to 6, "G2" to 7) +
            row("A3" to 1, "B3" to 8, "D3" to 9, "E3" to 10, "F3" to 11, "G3" to 12) +
            row("D4" to 13, "E4" to 14, "F4" to 15, "G4" to 12)
        val out = ExtractFta.parse(xlsx(s, rows).inputStream())
        assertEquals(2, out.size)
        assertEquals("ABP NEWS (earlier STAR NEWS)", out[0].name)
        assertEquals(listOf("STAR NEWS"), out[0].aliases)
        assertEquals("News", out[0].cat)
        assertEquals(listOf("Hindi"), out[0].languages)
        assertEquals("ACME LTD", out[0].company)
        assertEquals("ACME LTD", out[1].company)
        assertEquals("Non-News", out[1].cat)
        assertEquals(listOf("English", "Hindi"), out[1].languages)
    }

    @Test fun rowsWithoutNameAreSkipped() {
        val s = listOf("Company", "Channel Name", "Category", "Language", "X")
        val rows = row("B1" to 0, "D1" to 1, "E1" to 2, "F1" to 3) + row("B2" to 4)
        assertEquals(0, ExtractFta.parse(xlsx(s, rows).inputStream()).size)
    }
}
