package tv.cypher.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EpgTest {
    @Test fun jsonRoundTripThroughGzip() {
        val data = mapOf("A.in" to listOf(Programme(1, 2, "x \"q\" y"), Programme(2, 3, "é")))
        val bos = ByteArrayOutputStream()
        Epg.writeGzip(data, bos)
        assertEquals(data, Epg.readGzip(ByteArrayInputStream(bos.toByteArray())))
    }
}
