package tv.cypher.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class M3uTest {
    private val sample = """
        #EXTM3U
        #EXTINF:-1 tvg-id="4SiDESTV.in@SD" tvg-logo="https://x/l.png" group-title="News",4 SiDES TV (1080p)
        https://a/index.m3u8
        #EXTINF:-1 tvg-id="4TVNews.in@HD" http-user-agent="Mozilla/5.0 (KHTML, like Gecko) Chrome/1",4TV News (576p) [Geo-blocked]
        #EXTVLCOPT:http-user-agent=Mozilla/5.0 (KHTML, like Gecko) Chrome/1
        #EXTVLCOPT:http-referrer=https://ref.example/
        https://b/master.m3u8
        #EXTINF:-1 tvg-id="",No Id
        https://c/x.m3u8
    """.trimIndent()

    @Test fun parsesIdFeedNameLogoGroup() {
        val e = M3u.parse(sample)
        assertEquals(3, e.size)
        assertEquals("4SiDESTV.in", e[0].channelId)
        assertEquals("SD", e[0].feed)
        assertEquals("4 SiDES TV", e[0].name)
        assertEquals("https://x/l.png", e[0].logo)
        assertEquals("News", e[0].group)
        assertEquals("https://a/index.m3u8", e[0].url)
        assertNull(e[0].ua)
        assertFalse(e[0].geoBlocked)
    }

    @Test fun commaInsideQuotedAttributeDoesNotSplitTitle() {
        val e = M3u.parse(sample)[1]
        assertEquals("4TV News", e.name)
        assertEquals("Mozilla/5.0 (KHTML, like Gecko) Chrome/1", e.ua)
        assertEquals("https://ref.example/", e.referer)
        assertTrue(e.geoBlocked)
        assertEquals("HD", e.feed)
    }

    @Test fun entryWithoutIdHasEmptyChannelId() {
        assertEquals("", M3u.parse(sample)[2].channelId)
    }

    @Test fun uaFromVlcOptWinsOverNothing() {
        val e = M3u.parse("#EXTINF:-1 tvg-id=\"A.in@SD\",A\n#EXTVLCOPT:http-user-agent=UA1\nhttps://z/a.m3u8").single()
        assertEquals("UA1", e.ua)
    }

    @Test fun resolutionIsReadFromTheTitleTag() {
        val e = M3u.parse("#EXTINF:-1 tvg-id=\"A.in@SD\",A (1080p) [Geo-blocked]\nhttps://z/a.m3u8\n#EXTINF:-1 tvg-id=\"A.in@SD\",A\nhttps://z/b.m3u8")
        assertEquals(1080, e[0].height); assertEquals("A", e[0].name)
        assertEquals(0, e[1].height)
    }
}
