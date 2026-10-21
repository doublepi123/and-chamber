package io.andchamber.app.browser

import org.junit.Assert.*
import org.junit.Test

class NativeFixtureTimelineRingTest {
    private fun unknown() = List<Long?>(NativeFixtureTimelineRing.FIELDS.size - 4) { null }
    private fun rows(ring: NativeFixtureTimelineRing) = ring.json().substringAfter("\"events\":[")
        .substringBeforeLast("]").let { Regex("\\[([^]]*)]").findAll(it).map { it.groupValues[1].split(',') }.toList() }

    @Test fun retainsLast256WithOverflowAndSequence() {
        val ring = NativeFixtureTimelineRing { 10 }
        repeat(300) { ring.record(NativeFixtureTimelineRing.Kind.LAYOUT, 1, unknown()) }
        assertEquals(256, rows(ring).size)
        assertEquals(44L, ring.overflow)
        assertEquals("45", rows(ring).first()[0])
        assertEquals("300", rows(ring).last()[0])
    }
    @Test fun timestampsNeverRegressButEqualTimesAreHonest() {
        var time = 100L
        val ring = NativeFixtureTimelineRing { time }
        for (next in listOf(100L, 100L, 99L, 102L)) {
            time = next
            ring.record(NativeFixtureTimelineRing.Kind.FOCUS, 1, unknown())
        }
        assertEquals(listOf("100", "100", "100", "102"), rows(ring).map { it[1] })
    }
    @Test fun unknownFieldsRemainNull() {
        val ring = NativeFixtureTimelineRing { 1 }
        ring.record(NativeFixtureTimelineRing.Kind.INSTALL, 7, unknown())
        assertTrue(rows(ring).single().drop(4).all { it == "null" })
    }
    @Test fun sourcesRemainDistinctInOneRing() {
        val ring = NativeFixtureTimelineRing { 1 }
        for (source in listOf(7, 9, 7)) ring.record(NativeFixtureTimelineRing.Kind.ATTACH, source, unknown())
        assertEquals(listOf("7", "9", "7"), rows(ring).map { it[3] })
    }
    @Test fun byteBudgetAlsoBoundsWorstCaseNumericRows() {
        val ring = NativeFixtureTimelineRing { Long.MAX_VALUE }
        repeat(1000) { ring.record(NativeFixtureTimelineRing.Kind.LAYOUT, Int.MAX_VALUE,
            List(NativeFixtureTimelineRing.FIELDS.size - 4) { Long.MIN_VALUE }) }
        assertTrue(ring.json().toByteArray(Charsets.UTF_8).size < 65_536)
        assertTrue(ring.overflow > 744)
    }
    @Test fun schemaCannotAcceptVariableTextAndRejectsWrongWidth() {
        val ring = NativeFixtureTimelineRing { 1 }
        ring.record(NativeFixtureTimelineRing.Kind.BEFORE_DOWN, 1, unknown())
        assertTrue(rows(ring).single().all { it == "null" || it.toLongOrNull() != null })
        assertFalse(NativeFixtureTimelineRing.FIELDS.any { it in setOf("url", "text", "cookie", "token", "inputValue") })
        try {
            ring.record(NativeFixtureTimelineRing.Kind.LAYOUT, 1, emptyList())
            fail("wrong schema accepted")
        } catch (_: IllegalArgumentException) { }
        assertEquals(1L, ring.sequence)
    }
}
