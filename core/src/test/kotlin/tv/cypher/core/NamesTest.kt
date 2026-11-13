package tv.cypher.core

import kotlin.test.Test
import kotlin.test.assertEquals

class NamesTest {
    @Test fun normalizeStripsBracketsQualityAndPunctuation() {
        assertEquals("aaj tak", Names.normalize("Aaj Tak HD (1080p) [Geo-blocked]"))
        assertEquals("sony max", Names.normalize("SONY  MAX"))
        assertEquals("star plus", Names.normalize("Star Plus HD"))
    }

    @Test fun normalizeKeepsMeaningfulSymbols() {
        assertEquals("b4u kadak", Names.normalize("B4U-Kadak"))
        assertEquals("news18 india", Names.normalize("News18 India"))
        assertEquals("zee and tv", Names.normalize("Zee & TV"))
        assertEquals("studio one plus", Names.normalize("Studio One +"))
    }

    @Test fun normalizeDropsTrailingQualityOnlyNotMidWord() {
        assertEquals("hdfc tv", Names.normalize("HDFC TV"))
        assertEquals("sun tv", Names.normalize("Sun TV HD"))
    }

    @Test fun primaryNameDropsParentheticals() {
        assertEquals("ABP NEWS", Names.primary("ABP NEWS (earlier STAR NEWS)"))
        assertEquals("ZEE NEWS", Names.primary("ZEE NEWS (Zee National)"))
    }

    @Test fun aliasesFromEarlierClauses() {
        assertEquals(listOf("STAR NEWS"), Names.aliases("ABP NEWS (earlier STAR NEWS)"))
        assertEquals(listOf("BHASKAR NEWS", "AP 9"), Names.aliases("Mantavya 24X7 (earlier BHASKAR NEWS, AP 9)"))
        assertEquals(listOf("Kannada").map { "ABP $it" }, Names.aliases("ABP NEWS HD (Earlier known as ABP Kannada)"))
    }

    @Test fun aliasesHandleTyposAndNesting() {
        assertEquals(listOf("TV 9"), Names.aliases("TV9 Telgu (earleir TV 9)"))
        val a = Names.aliases("News 1 India (earlier High News (earlier News1 India, CEE NEWS)")
        assertEquals(listOf("High News", "News1 India", "CEE NEWS"), a)
    }

    @Test fun aliasesAlternateNameInBrackets() {
        assertEquals(listOf("Zee National"), Names.aliases("ZEE NEWS (Zee National)"))
        assertEquals(emptyList(), Names.aliases("Plain Channel"))
    }
}
