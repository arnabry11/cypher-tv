package tv.cypher.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NumberingTest {
    @Test fun allocatesInLanguageBlocks() {
        val n = Numbering.allocate(emptyMap(), listOf("a" to "Hindi", "b" to "English", "c" to "Hindi", "d" to "Telugu"))
        assertEquals(100, n["a"]); assertEquals(101, n["c"]); assertEquals(300, n["b"]); assertEquals(400, n["d"])
    }

    @Test fun existingNumbersNeverChangeAndAreNotReused() {
        val old = mapOf("a" to 100, "gone" to 101)
        val n = Numbering.allocate(old, listOf("a" to "Hindi", "x" to "Hindi"))
        assertEquals(100, n["a"]); assertEquals(101, n["gone"]); assertEquals(102, n["x"])
    }

    @Test fun overflowSpillsToOverflowArea() {
        val old = (0 until 100).associate { "e$it" to 300 + it }
        val n = Numbering.allocate(old, listOf("new" to "English"))
        assertTrue(n["new"]!! >= Numbering.OVERFLOW_START)
    }

    @Test fun unknownLanguageGoesToOther() {
        val n = Numbering.allocate(emptyMap(), listOf("z" to "Klingon"))
        assertEquals(Numbering.OTHER_START, n["z"])
    }

    @Test fun hindiBlockIsWide() {
        val ids = (0 until 150).map { "h$it" to "Hindi" }
        val n = Numbering.allocate(emptyMap(), ids)
        assertEquals(100, n["h0"]); assertEquals(249, n["h149"])
    }
}
