package tv.cypher.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EpgChannelsTest {
    private val tata = """<?xml version="1.0"?><channels>
        <channel site="tataplay.com" site_id="1" lang="en" xmltv_id="AajTak.in@SD">Aaj Tak</channel>
        <channel site="tataplay.com" site_id="2" lang="en" xmltv_id="">No id</channel>
        <channel site="tataplay.com" site_id="3" lang="en" xmltv_id="Other.in">Not ours</channel>
        <channel site="tataplay.com" site_id="4" lang="hi" xmltv_id="Zee&amp;Co.in">Zee &amp; Co</channel>
    </channels>"""
    private val dish = """<channels>
        <channel site="dishtv.in" site_id="9" lang="en" xmltv_id="AajTak.in">Aaj Tak dup</channel>
        <channel site="dishtv.in" site_id="10" lang="en" xmltv_id="ABPNews.in@HD">ABP News</channel>
    </channels>"""

    @Test fun picksFirstSiteInPriorityOrderAndStripsFeed() {
        val r = EpgChannels.build(listOf("tataplay.com" to tata, "dishtv.in" to dish), setOf("AajTak.in", "ABPNews.in", "Missing.in", "Zee&Co.in"))
        assertEquals(setOf("AajTak.in", "ABPNews.in", "Zee&Co.in"), r.covered)
        assertEquals(listOf("Missing.in"), r.missing)
        assertTrue("""site="tataplay.com" site_id="1" lang="en" xmltv_id="AajTak.in"""" in r.xml)
        assertTrue("""site="dishtv.in" site_id="10"""" in r.xml)
        assertTrue("dishtv.in\" site_id=\"9\"" !in r.xml)
        assertTrue("Other.in" !in r.xml)
    }

    @Test fun escapesXmlInOutput() {
        val r = EpgChannels.build(listOf("tataplay.com" to tata), setOf("Zee&Co.in"))
        assertTrue("xmltv_id=\"Zee&amp;Co.in\">Zee &amp; Co</channel>" in r.xml)
    }

    @Test fun emptyWhenNothingMatches() {
        val r = EpgChannels.build(listOf("tataplay.com" to tata), setOf("Nope.in"))
        assertTrue(r.covered.isEmpty())
        assertTrue("<channel " !in r.xml)
    }
}
