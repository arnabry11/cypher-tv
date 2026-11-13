package tv.cypher.tools

import kotlin.test.Test
import kotlin.test.assertEquals

class FeedsTest {
    @Test fun mainFeedWinsAndUnknownCodesAreSkipped() {
        val json = """[
          {"channel":"A.in","id":"HD","is_main":false,"languages":["eng"],"extra":1},
          {"channel":"A.in","id":"SD","is_main":true,"languages":["hin","eng"]},
          {"channel":"B.in","id":"SD","is_main":false,"languages":["pan"]},
          {"channel":"C.in","id":"SD","is_main":true,"languages":["xyz"]},
          {"channel":"D.in","id":"SD","is_main":true,"languages":[]}
        ]"""
        assertEquals(mapOf("A.in" to "Hindi", "B.in" to "Punjabi"), Feeds.languages(json))
    }
}
