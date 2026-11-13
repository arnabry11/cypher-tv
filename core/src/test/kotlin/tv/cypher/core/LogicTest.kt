package tv.cypher.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LogicTest {
    @Test fun favouritesToggleAddsAtEndAndRemoves() {
        val f = Favourites(listOf("a", "b"))
        f.toggle("c"); assertEquals(listOf("a", "b", "c"), f.ids)
        f.toggle("a"); assertEquals(listOf("b", "c"), f.ids)
    }

    @Test fun favouritesMoveClampsAtEdges() {
        val f = Favourites(listOf("a", "b", "c"))
        assertEquals(1, f.move(0, +1)); assertEquals(listOf("b", "a", "c"), f.ids)
        assertEquals(0, f.move(0, -1)); assertEquals(listOf("b", "a", "c"), f.ids)
        assertEquals(2, f.move(2, +1)); assertEquals(listOf("b", "a", "c"), f.ids)
        assertEquals(2, f.move(1, +1)); assertEquals(listOf("b", "c", "a"), f.ids)
    }

    @Test fun favouritesIgnoreDuplicatesOnLoad() {
        assertEquals(listOf("a", "b"), Favourites(listOf("a", "b", "a")).ids)
    }

    @Test fun zapWrapsBothWays() {
        val l = listOf("a", "b", "c")
        assertEquals("b", Zap.next(l, "a")); assertEquals("a", Zap.next(l, "c"))
        assertEquals("c", Zap.prev(l, "a")); assertEquals("a", Zap.prev(l, "b"))
    }

    @Test fun zapFromUnknownStartsAtEdges() {
        val l = listOf("a", "b", "c")
        assertEquals("a", Zap.next(l, "zzz")); assertEquals("c", Zap.prev(l, "zzz"))
        assertNull(Zap.next(emptyList(), "a"))
    }

    @Test fun numberEntryAccumulatesAndCaps() {
        val n = NumberEntry(maxDigits = 3)
        n.press(1); n.press(0); assertEquals("10", n.text)
        n.press(5); n.press(9); assertEquals("105", n.text)
        assertEquals(105, n.commit()); assertEquals("", n.text)
        assertNull(n.commit())
    }

    @Test fun numberEntryResolvesAgainstChannelNumbers() {
        val byNum = mapOf(101 to "a", 205 to "b")
        assertEquals("b", NumberEntry.resolve(205, byNum))
        assertNull(NumberEntry.resolve(999, byNum))
    }

    private fun p(s: Long, e: Long, t: String) = Programme(s, e, t)

    @Test fun nowNextPicksCurrentAndFollowing() {
        val rows = listOf(p(0, 10, "A"), p(10, 20, "B"), p(20, 30, "C"))
        val (now, next) = NowNext.pick(rows, 12)
        assertEquals("B", now?.title); assertEquals("C", next?.title)
    }

    @Test fun nowNextWithGapHasNoNow() {
        val rows = listOf(p(20, 30, "C"), p(30, 40, "D"))
        val (now, next) = NowNext.pick(rows, 12)
        assertNull(now); assertEquals("C", next?.title)
    }

    @Test fun nowNextAtBoundaryMovesOn() {
        val rows = listOf(p(0, 10, "A"), p(10, 20, "B"))
        assertEquals("B", NowNext.pick(rows, 10).first?.title)
        assertNull(NowNext.pick(rows, 20).first)
        assertNull(NowNext.pick(emptyList(), 5).second)
    }

    @Test fun failoverTriesLastGoodThenOthersOnce() {
        val f = StreamFailover(count = 4, lastGood = 2)
        assertEquals(2, f.current)
        // streams are sorted best-first, so from the remembered one go to lower quality first, higher last
        assertEquals(3, f.advance()); assertEquals(0, f.advance()); assertEquals(1, f.advance())
        assertNull(f.advance())
    }

    @Test fun failoverIgnoresBadLastGood() {
        assertEquals(0, StreamFailover(count = 2, lastGood = 9).current)
        assertEquals(0, StreamFailover(count = 2, lastGood = -1).current)
    }

    @Test fun failureAfterLongHealthyPlayRetriesTheSameStreamFirst() {
        val f = StreamFailover(count = 3, lastGood = 1)
        assertEquals(1, f.failed(playedMs = 60_000))
        assertEquals(1, f.current)
        assertEquals(2, f.failed(playedMs = 1_000)) // now it moves on, to lower quality first
    }

    @Test fun streamThatDiesQuicklyAlwaysMovesOnAndEventuallyGivesUp() {
        val f = StreamFailover(count = 3, lastGood = 0)
        assertEquals(1, f.failed(5_000)); assertEquals(2, f.failed(5_000)); assertNull(f.failed(5_000))
    }

    @Test fun flappingStreamCannotLoopForever() {
        val f = StreamFailover(count = 2, lastGood = 0)
        assertEquals(0, f.failed(60_000))      // healthy then broke: retry once
        assertEquals(1, f.failed(4_000))       // retried stream died quickly: try the backup
        assertNull(f.failed(4_000))            // backup died quickly too
    }

    @Test fun backoffRetriesSoonAfterFailureAndResetsOnSuccess() {
        val b = RetryBackoff(firstMs = 60_000, maxMs = 240_000)
        assertEquals(10_000_000L, b.next(success = true, normalMs = 10_000_000L))
        assertEquals(60_000L, b.next(false, 10_000_000L))
        assertEquals(120_000L, b.next(false, 10_000_000L))
        assertEquals(240_000L, b.next(false, 10_000_000L))
        assertEquals(240_000L, b.next(false, 10_000_000L))
        assertEquals(10_000_000L, b.next(true, 10_000_000L))
        assertEquals(60_000L, b.next(false, 10_000_000L))
    }

    @Test fun qualityMemoryRoundTripsWhileFresh() {
        val raw = QualityMemory.encode(2, nowMs = 1_000_000)
        assertEquals(2, QualityMemory.decode(raw, nowMs = 1_000_000 + 60_000, ttlMs = 30 * 60_000))
    }

    @Test fun qualityMemoryExpiresAndToleratesJunk() {
        val raw = QualityMemory.encode(2, nowMs = 1_000_000)
        assertEquals(-1, QualityMemory.decode(raw, nowMs = 1_000_000 + 31 * 60_000, ttlMs = 30 * 60_000))
        assertEquals(-1, QualityMemory.decode(raw, nowMs = 1_000_000 - 5_000, ttlMs = 30 * 60_000)) // clock went backwards
        assertEquals(-1, QualityMemory.decode(null, 5, 10)); assertEquals(-1, QualityMemory.decode("junk", 5, 10))
        assertEquals(-1, QualityMemory.decode("-3@100", 105, 10))
    }

    @Test fun threeRebuffersWithinAMinuteTriggerAStepDown() {
        val t = RebufferTracker(windowMs = 60_000, limit = 3)
        assertEquals(false, t.record(0)); assertEquals(false, t.record(20_000)); assertEquals(true, t.record(40_000))
        assertEquals(false, t.record(41_000)) // counter restarted after triggering
    }

    @Test fun slowRebuffersDoNotAccumulate() {
        val t = RebufferTracker(windowMs = 60_000, limit = 3)
        assertEquals(false, t.record(0)); assertEquals(false, t.record(50_000)); assertEquals(false, t.record(100_000))
        assertEquals(false, t.record(150_000))
    }

    @Test fun stepDownGoesToLowerQualityOneAtATimeThenStops() {
        val f = StreamFailover(count = 3, lastGood = -1) // streams 0 (best), 1, 2 (worst)
        assertEquals(0, f.current)
        assertEquals(1, f.stepDown()); assertEquals(1, f.current)
        assertEquals(2, f.stepDown())
        assertNull(f.stepDown()); assertEquals(2, f.current) // already the lowest: stays put
    }

    @Test fun stepDownNeverWrapsBackUpToBetterQuality() {
        val f = StreamFailover(count = 3, lastGood = 1) // order 1, 2, 0
        assertEquals(2, f.stepDown())
        assertNull(f.stepDown()); assertEquals(2, f.current)
        assertEquals(0, f.advance()) // a real failure may still fall back to the best one
    }
}
