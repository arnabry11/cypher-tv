package tv.cypher.tools

import tv.cypher.core.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BuildChannelsTest {
    private val streams = """
        #EXTM3U
        #EXTINF:-1 tvg-id="AajTak.in@SD",Aaj Tak (576p)
        https://a/1.m3u8
        #EXTINF:-1 tvg-id="AajTak.in@HD",Aaj Tak (1080p) [Geo-blocked]
        #EXTVLCOPT:http-user-agent=UA-X
        #EXTVLCOPT:http-referrer=https://ref/
        https://a/2.m3u8
        #EXTINF:-1 tvg-id="TV9Telugu.in@SD",TV9 Telugu (576p)
        https://t/1.m3u8
        #EXTINF:-1 tvg-id="DDNational.in@SD",DD National (576p)
        https://d/1.m3u8
        #EXTINF:-1 tvg-id="Unrelated.in@SD",Unrelated Channel (576p)
        https://u/1.m3u8
    """.trimIndent()
    private val index = """
        #EXTM3U
        #EXTINF:-1 tvg-id="AajTak.in@SD" tvg-logo="https://logo/aaj.png" group-title="News",Aaj Tak (576p)
        https://a/1.m3u8
        #EXTINF:-1 tvg-id="TV9Telugu.in@SD" tvg-logo="https://logo/tv9.png" group-title="Entertainment",TV9 Telugu (576p)
        https://t/1.m3u8
        #EXTINF:-1 tvg-id="DDNational.in@SD" group-title="General",DD National (576p)
        https://d/1.m3u8
    """.trimIndent()

    private fun fta(name: String, cat: String, vararg l: String) = FtaChannel(name, Names.aliases(name), cat, l.toList())

    private val ftas = listOf(
        fta("AAJ TAK", "News", "Hindi"),
        fta("Aaj Tak HD", "News", "Hindi"), // duplicate claim of the same channel
        fta("TV9 Telgu", "Non-News", "Telugu"),
        fta("Totally Missing", "Non-News", "Hindi"),
    )

    @Test fun buildsChannelsWithStreamsHeadersLogoAndNumbers() {
        val r = BuildChannels.build(ftas, M3u.parse(streams), M3u.parse(index), Overrides(), emptyMap())
        val byId = r.channels.associateBy { it.id }
        assertEquals(setOf("AajTak.in", "TV9Telugu.in"), byId.keys)
        val aaj = byId.getValue("AajTak.in")
        assertEquals(listOf("https://a/1.m3u8", "https://a/2.m3u8"), aaj.streams.map { it.url })
        assertEquals("UA-X", aaj.streams[1].ua); assertEquals("https://ref/", aaj.streams[1].referer)
        assertEquals("https://logo/aaj.png", aaj.logo)
        assertEquals("News", aaj.cat); assertEquals("Hindi", aaj.lang); assertEquals(100, aaj.num)
        val tv9 = byId.getValue("TV9Telugu.in")
        assertEquals("Telugu", tv9.lang); assertEquals(400, tv9.num); assertEquals("Entertainment", tv9.cat)
        assertEquals(listOf(100, 400), r.channels.map { it.num })
    }

    @Test fun reportFlagsFuzzyUnmatchedAndDuplicates() {
        val r = BuildChannels.build(ftas, M3u.parse(streams), M3u.parse(index), Overrides(), emptyMap())
        assertTrue("TV9 Telgu" in r.report && "fuzzy" in r.report.lowercase())
        assertTrue("Totally Missing" in r.report)
        assertTrue("Aaj Tak HD" in r.report && "duplicate" in r.report.lowercase())
    }

    @Test fun extraChannelsAndPreferredStream() {
        val ov = Overrides(
            extra = listOf(ExtraChannel("DDNational.in", lang = "Hindi")),
            preferred = mapOf("AajTak.in" to "/2.m3u8"),
        )
        val r = BuildChannels.build(ftas, M3u.parse(streams), M3u.parse(index), ov, emptyMap())
        val byId = r.channels.associateBy { it.id }
        assertNotNull(byId["DDNational.in"])
        assertEquals("https://a/2.m3u8", byId.getValue("AajTak.in").streams.first().url)
        assertEquals("Entertainment", byId.getValue("DDNational.in").cat) // "General" is not a useful category
    }

    @Test fun existingNumbersAreKeptAndNewOnesAppended() {
        val r = BuildChannels.build(ftas, M3u.parse(streams), M3u.parse(index), Overrides(), mapOf("TV9Telugu.in" to 405, "Gone.in" to 101))
        val byId = r.channels.associateBy { it.id }
        assertEquals(405, byId.getValue("TV9Telugu.in").num)
        assertEquals(100, byId.getValue("AajTak.in").num)
        assertEquals(101, r.numbers["Gone.in"]) // numbers of vanished channels are never reused
    }

    @Test fun channelsWithoutStreamsAreNotPublished() {
        val r = BuildChannels.build(ftas, M3u.parse("#EXTM3U"), emptyList(), Overrides(), emptyMap())
        assertEquals(0, r.channels.size)
    }
}
