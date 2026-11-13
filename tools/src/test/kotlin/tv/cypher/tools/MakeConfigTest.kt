package tv.cypher.tools

import tv.cypher.core.AppInfo
import tv.cypher.core.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MakeConfigTest {
    @Test fun defaultsPointAtRawUrlsOfTheDistBranch() {
        val c = MakeConfig.build("o/r", "dist", previous = null, app = null)
        assertEquals("https://raw.githubusercontent.com/o/r/dist/channels.json", c.channelsUrl)
        assertEquals("https://raw.githubusercontent.com/o/r/dist/epg.json.gz", c.epgUrl)
        assertEquals(1, c.schema)
        assertNull(c.app)
    }

    @Test fun keepsPreviousAppBlockWhenNoNewRelease() {
        val prev = Config(1, "x", "y", AppInfo(5, "https://a/apk", "abc", "n"))
        assertEquals(5, MakeConfig.build("o/r", "dist", prev, null).app?.versionCode)
    }

    @Test fun newReleaseReplacesAppBlockAndNeverGoesBackwards() {
        val prev = Config(1, "x", "y", AppInfo(5, "https://a/apk", "abc", "n"))
        val newer = AppInfo(6, "https://a/apk6", "def", "six")
        assertEquals(6, MakeConfig.build("o/r", "dist", prev, newer).app?.versionCode)
        val older = AppInfo(4, "https://a/apk4", "000", "four")
        assertEquals(5, MakeConfig.build("o/r", "dist", prev, older).app?.versionCode)
    }

    @Test fun parsePreviousToleratesGarbage() {
        assertNull(MakeConfig.parse("not json"))
        assertNull(MakeConfig.parse(""))
        assertEquals("y", MakeConfig.parse("""{"schema":1,"channelsUrl":"x","epgUrl":"y"}""")?.epgUrl)
    }
}
