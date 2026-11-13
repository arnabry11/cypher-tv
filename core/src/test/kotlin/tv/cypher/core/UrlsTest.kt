package tv.cypher.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UrlsTest {
    @Test fun rawGithubMapsToJsDelivr() {
        assertEquals(
            "https://cdn.jsdelivr.net/gh/arnabry11/cypher-tv@dist/config.json",
            Urls.jsDelivrMirror("https://raw.githubusercontent.com/arnabry11/cypher-tv/dist/config.json"),
        )
        assertEquals(
            "https://cdn.jsdelivr.net/gh/o/r@main/a/b.json",
            Urls.jsDelivrMirror("https://raw.githubusercontent.com/o/r/main/a/b.json"),
        )
    }

    @Test fun otherUrlsHaveNoMirror() {
        assertNull(Urls.jsDelivrMirror("https://example.com/x.json"))
        assertNull(Urls.jsDelivrMirror("https://raw.githubusercontent.com/o/r"))
    }
}
