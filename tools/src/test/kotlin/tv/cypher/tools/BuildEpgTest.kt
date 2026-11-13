package tv.cypher.tools

import tv.cypher.core.Channel
import tv.cypher.core.Stream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals

class BuildEpgTest {
    @Test fun startOfIstDay() {
        // 2026-11-13T00:30:00Z is 06:00 IST on the 13th -> day starts 2026-11-12T18:30:00Z
        assertEquals(1794529800L - 6 * 3600, BuildEpg.startOfIstDay(1794529800L))
        // 2026-11-12T19:00:00Z is 00:30 IST on the 13th
        assertEquals(1794529800L - 6 * 3600, BuildEpg.startOfIstDay(1794529800L - 6 * 3600 + 1800))
    }

    @Test fun convertsXmltvForPublishedChannelsOnly() {
        val xml = """<tv><programme start="20261113060000 +0530" stop="20261113070000 +0530" channel="A.in"><title>X</title></programme>
            <programme start="20261113060000 +0530" stop="20261113070000 +0530" channel="B.in"><title>Y</title></programme></tv>"""
        val chans = listOf(Channel("A.in", 100, "A", "Hindi", "News", null, listOf(Stream("u"))))
        val out = ByteArrayOutputStream()
        val n = BuildEpg.convert(xml.byteInputStream(), chans, 1794529800L, out)
        assertEquals(1, n)
        assertEquals(setOf("A.in"), tv.cypher.core.Epg.readGzip(out.toByteArray().inputStream()).keys)
    }
}
