package tv.cypher.tools

import tv.cypher.core.Programme
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class XmltvTest {
    private val xml = """<?xml version="1.0" encoding="UTF-8"?>
<tv>
  <channel id="AajTak.in"><display-name>Aaj Tak</display-name></channel>
  <programme start="20261113060000 +0530" stop="20261113070000 +0530" channel="AajTak.in"><title lang="hi">Morning &amp; News</title></programme>
  <programme start="20261113070000 +0530" stop="20261113073000 +0530" channel="AajTak.in@SD"><title>Bulletin</title></programme>
  <programme start="20261113073000 +0530" stop="20261113073000 +0530" channel="AajTak.in"><title>Zero length</title></programme>
  <programme start="20261110060000 +0530" stop="20261110070000 +0530" channel="AajTak.in"><title>Too old</title></programme>
  <programme start="20261113060000 +0530" stop="20261113070000 +0530" channel="AajTak.in"><title>Morning &amp; News</title></programme>
  <programme start="20261113060000 +0530" stop="20261113070000 +0530" channel="Other.in"><title>Not wanted</title></programme>
</tv>"""
    private val t0 = 1794529800L // 2026-11-13T06:00:00+05:30

    @Test fun convertsToEpochSortedDedupedFiltered() {
        val out = Xmltv.fromXmltv(ByteArrayInputStream(xml.toByteArray()), setOf("AajTak.in"), fromEpoch = t0 - 3600, toEpoch = t0 + 86400)
        val list = out.getValue("AajTak.in")
        assertEquals(listOf(Programme(t0, t0 + 3600, "Morning & News"), Programme(t0 + 3600, t0 + 5400, "Bulletin")), list)
        assertTrue("Other.in" !in out)
    }

    @Test fun parseXmltvTimeHandlesOffsetsAndNoOffset() {
        assertEquals(t0, Xmltv.parseTime("20261113060000 +0530"))
        assertEquals(t0, Xmltv.parseTime("20261113003000 +0000"))
        assertEquals(t0 + 19800, Xmltv.parseTime("20261113060000"))
    }
}
