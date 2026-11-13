package tv.cypher.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MatcherTest {
    private fun cands(vararg p: Pair<String, String>) = p.map { Candidate(it.first, it.second) }
    private fun fta(name: String) = FtaChannel(name = name, aliases = Names.aliases(name), cat = "NEWS", languages = listOf("Hindi"))

    private val pool = cands(
        "AajTak.in" to "Aaj Tak",
        "ABPNews.in" to "ABP News",
        "StarNews.in" to "Star News",
        "RepublicKannada.in" to "Republic Kannada",
        "TV9Telugu.in" to "TV9 Telugu",
        "News18Tamil.in" to "News18 Tamil Nadu",
        "News7Tamil.in" to "News7 Tamil",
        "ZeeCinema.in" to "Zee Cinema",
        "ZeeNews.in" to "Zee News",
    )
    private val m = Matcher(pool, Overrides())

    @Test fun exactMatchAfterNormalisation() {
        val r = m.match(fta("AAJ TAK HD"))
        assertEquals(MatchKind.EXACT, r.kind); assertEquals("AajTak.in", r.candidate?.id)
    }

    @Test fun primaryBeatsAlias() {
        val r = m.match(fta("ABP NEWS (earlier STAR NEWS)"))
        assertEquals("ABPNews.in", r.candidate?.id); assertEquals(MatchKind.EXACT, r.kind)
    }

    @Test fun aliasUsedWhenPrimaryMissing() {
        val r = m.match(fta("India Updates (earlier Star News)"))
        assertEquals(MatchKind.ALIAS, r.kind); assertEquals("StarNews.in", r.candidate?.id)
    }

    @Test fun overrideAliasMapsAbbreviation() {
        val mm = Matcher(pool, Overrides(aliases = mapOf("R. Kannada" to listOf("Republic Kannada"))))
        val r = mm.match(fta("R. Kannada"))
        assertEquals(MatchKind.OVERRIDE, r.kind); assertEquals("RepublicKannada.in", r.candidate?.id)
    }

    @Test fun explicitIdOverrideWins() {
        val mm = Matcher(pool, Overrides(map = mapOf("Whatever" to "ZeeNews.in")))
        assertEquals("ZeeNews.in", mm.match(fta("Whatever")).candidate?.id)
    }

    @Test fun fuzzyAcceptsSpellingVariant() {
        val r = m.match(fta("TV9 Telgu"))
        assertEquals(MatchKind.FUZZY, r.kind); assertEquals("TV9Telugu.in", r.candidate?.id)
    }

    @Test fun fuzzyRejectsDifferentDigits() {
        assertNull(m.match(fta("News 18 Tamil")).candidate?.takeIf { it.id == "News7Tamil.in" })
    }

    @Test fun nearMissIsRejected() {
        val r = m.match(fta("Zee Cinemas Plus Extra"))
        assertEquals(MatchKind.NONE, r.kind); assertNull(r.candidate)
    }

    @Test fun shortNamesNeverFuzzy() {
        val r = Matcher(cands("X9.in" to "9X"), Overrides()).match(fta("9XM"))
        assertEquals(MatchKind.NONE, r.kind)
    }

    @Test fun prefersDotInWhenNamesCollide() {
        val mm = Matcher(cands("Zee.uk" to "Zee TV", "ZeeTV.in" to "Zee TV"), Overrides())
        assertEquals("ZeeTV.in", mm.match(fta("ZEE TV")).candidate?.id)
    }

    @Test fun excludedIsSkipped() {
        val mm = Matcher(pool, Overrides(exclude = listOf("Aaj Tak")))
        assertEquals(MatchKind.EXCLUDED, mm.match(fta("Aaj Tak")).kind)
    }
}
