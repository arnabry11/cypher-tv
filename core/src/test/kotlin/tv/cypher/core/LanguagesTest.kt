package tv.cypher.core

import kotlin.test.Test
import kotlin.test.assertEquals

class LanguagesTest {
    private fun pick(name: String, vararg langs: String) = Languages.primary(name, langs.toList())

    @Test fun singleLanguageWins() = assertEquals("Telugu", pick("ABN Andhra Jyothy", "Telugu"))

    @Test fun oneNonEnglishNonHindiLanguageBeatsHindiAndEnglish() {
        assertEquals("Punjabi", pick("TV 24", "English", "Punjabi", "Hindi"))
        assertEquals("Marathi", pick("Mantavya 24X7", "English", "Marathi", "Hindi"))
    }

    @Test fun hindiBeatsEnglishWhenBothPresent() = assertEquals("Hindi", pick("Some News", "English", "Hindi"))

    @Test fun englishOnly() = assertEquals("English", pick("Foo", "English"))

    @Test fun nameHintBeatsListWhenLanguageIsListed() {
        assertEquals("Tamil", pick("News7 Tamil", "Hindi", "Tamil", "Telugu"))
        assertEquals("Bengali", pick("TV9 Bangla", "Bengali", "Hindi"))
    }

    @Test fun nameHintIgnoredWhenNotInList() = assertEquals("Hindi", pick("Tamil Name Hindi Channel", "Hindi"))

    @Test fun noLanguageUsesNameHintElseOther() {
        assertEquals("Marathi", pick("Lokshahi Marathi"))
        assertEquals("Other", pick("Mystery"))
    }

    @Test fun spellingsAreNormalised() {
        assertEquals("Odia", pick("Foo", "Oriya"))
        assertEquals("Bengali", pick("Foo", "Bangla"))
    }

    @Test fun cleanDropsNaAndAllScheduled() {
        assertEquals(listOf("Hindi", "English"), Languages.clean("All Indian Scheduled langauge, Hindi, NA, English , hindi"))
    }

    @Test fun feedLanguageBeatsTheLicenceList() {
        assertEquals("Hindi", Languages.file("ABP News", listOf("English", "Hindi", "Marathi"), "Hindi"))
        assertEquals("Hindi", Languages.file("Aaj Tak", listOf("English"), "Hindi"))
        assertEquals("Malayalam", Languages.file("Kerala Vision", emptyList(), "Malayalam"))
    }

    @Test fun nameAndLicenceTogetherBeatTheFeed() =
        assertEquals("Kannada", Languages.file("Aastha Kannada", listOf("Hindi", "Kannada"), "Hindi"))

    @Test fun nameAloneDoesNotBeatTheFeed() =
        assertEquals("Hindi", Languages.file("Tamil Talkies", listOf("Hindi"), "Hindi"))

    @Test fun withoutFeedLanguageFallsBackToTheLicenceList() =
        assertEquals("Marathi", Languages.file("Mantavya 24X7", listOf("English", "Marathi", "Hindi"), null))

    @Test fun isoCodes() {
        assertEquals("Punjabi", Languages.fromIso("pan")); assertEquals("Odia", Languages.fromIso("ori"))
        assertEquals(null, Languages.fromIso("xyz"))
    }

    @Test fun bojpuriIsBhojpuri() = assertEquals(listOf("Bhojpuri"), Languages.clean("Bojpuri"))
}
