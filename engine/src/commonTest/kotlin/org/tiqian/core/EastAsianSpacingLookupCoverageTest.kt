package org.tiqian.core

import kotlin.test.Test
import org.tiqian.test.trace.assertEquals
import kotlin.test.AfterTest
import org.tiqian.test.trace.TestTraceRecorder

/**
 * Coverage for EastAsianSpacingData.lookup: one representative per generated
 * table value plus the miss exit on both sides of the binary search.
 */
class EastAsianSpacingLookupCoverageTest {
    private val testTrace = TestTraceRecorder("EastAsianSpacingLookupCoverageTest")


    @Test
    fun lookupCoversEveryGeneratedValueAndBothMissDirections() {
        testTrace.section("lookupCoversEveryGeneratedValueAndBothMissDirections")
        // Conditional (value 3) and Narrow (value 1) sit at the table head.
        assertEquals(EastAsianSpacingValue.Conditional, EastAsianSpacingData.lookup('!'.code))
        assertEquals(EastAsianSpacingValue.Narrow, EastAsianSpacingData.lookup('A'.code))
        assertEquals(EastAsianSpacingValue.Narrow, EastAsianSpacingData.lookup('0'.code))
        // The unified CJK block is one Wide (value 0) range.
        assertEquals(EastAsianSpacingValue.Wide, EastAsianSpacingData.lookup(0x4E00))
        assertEquals(EastAsianSpacingValue.Wide, EastAsianSpacingData.lookup(0x9FFF))
        // Misses below the first range and above the last both fall to Other.
        assertEquals(EastAsianSpacingValue.Other, EastAsianSpacingData.lookup(0x02))
        assertEquals(EastAsianSpacingValue.Other, EastAsianSpacingData.lookup(0x10FFFF))
        // A miss inside a gap walks both traversal arms (below-low, above-high).
        assertEquals(EastAsianSpacingValue.Other, EastAsianSpacingData.lookup(0x22))
    }

    @AfterTest
    fun flushTestTrace() {
        testTrace.flush()
    }
}
