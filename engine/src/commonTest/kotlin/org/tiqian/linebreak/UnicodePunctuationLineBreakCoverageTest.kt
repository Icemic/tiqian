package org.tiqian.linebreak

import kotlin.test.Test
import org.tiqian.test.trace.assertEquals
import org.tiqian.test.trace.assertFailsWith
import kotlin.test.AfterTest
import org.tiqian.test.trace.TestTraceRecorder

/**
 * Coverage for UnicodePunctuationLineBreak.classOf: the scalar-value and
 * surrogate guards, and the lookup classes the pipeline text corpus never
 * queries (BreakAfter, BreakBoth, HyphenHH, Nonstarter).
 */
class UnicodePunctuationLineBreakCoverageTest {
    private val testTrace = TestTraceRecorder("UnicodePunctuationLineBreakCoverageTest")


    @Test
    fun lookupClassesCoverTheUaxTailorablePunctuationClasses() {
        testTrace.section("lookupClassesCoverTheUaxTailorablePunctuationClasses")
        assertEquals(UnicodePunctuationLineBreakClass.BreakAfter, UnicodePunctuationLineBreak.classOf(0x7C))
        assertEquals(UnicodePunctuationLineBreakClass.BreakBoth, UnicodePunctuationLineBreak.classOf(0x2014))
        assertEquals(UnicodePunctuationLineBreakClass.HyphenHH, UnicodePunctuationLineBreak.classOf(0x58A))
        assertEquals(UnicodePunctuationLineBreakClass.Nonstarter, UnicodePunctuationLineBreak.classOf(0x203C))
    }

    @Test
    fun nonScalarCodePointsAreRejected() {
        testTrace.section("nonScalarCodePointsAreRejected")
        assertFailsWith<IllegalArgumentException> {
            UnicodePunctuationLineBreak.classOf(-1)
        }
        assertFailsWith<IllegalArgumentException> {
            UnicodePunctuationLineBreak.classOf(0x110000)
        }
        assertFailsWith<IllegalArgumentException> {
            UnicodePunctuationLineBreak.classOf(0xD800)
        }
    }

    @AfterTest
    fun flushTestTrace() {
        testTrace.flush()
    }
}
