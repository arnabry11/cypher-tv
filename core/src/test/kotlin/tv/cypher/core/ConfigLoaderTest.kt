package tv.cypher.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ConfigLoaderTest {
    private fun json(schema: Int = 1, ch: String = "https://x/c.json", ep: String = "https://x/e.gz") =
        """{"schema":$schema,"channelsUrl":"$ch","epgUrl":"$ep"}"""

    @Test fun validConfigParses() = assertEquals("https://x/c.json", ConfigLoader.parse(json(), 1)?.channelsUrl)

    @Test fun newerSchemaThanTheAppUnderstandsIsRejected() = assertNull(ConfigLoader.parse(json(schema = 2), 1))

    @Test fun captivePortalHtmlAndGarbageAreRejected() {
        assertNull(ConfigLoader.parse("<html><body>Please log in</body></html>", 1))
        assertNull(ConfigLoader.parse("", 1))
    }

    @Test fun blankUrlsAreRejected() {
        assertNull(ConfigLoader.parse(json(ch = ""), 1))
        assertNull(ConfigLoader.parse(json(ep = " "), 1))
    }
}
