package org.tiqian.font

import kotlin.test.Test
import org.tiqian.test.trace.assertEquals
import org.tiqian.core.TextRange
import kotlin.test.AfterTest
import org.tiqian.test.trace.TestTraceRecorder

/**
 * Coverage for the CjkFontRoleClassifier symbol path: a supplementary math
 * symbol exercises the non-BMP arm of toCharOrNull (null) and lands on
 * Unknown, while BMP symbols resolve to Symbol.
 */
class FontRoleTailCoverageTest {
    private val testTrace = TestTraceRecorder("FontRoleTailCoverageTest")


    @Test
    fun supplementarySymbolIsUnknownBecauseItHasNoBmpCategory() {
        testTrace.section("supplementarySymbolIsUnknownBecauseItHasNoBmpCategory")
        val text = "𝐀" // U+1D400 MATHEMATICAL BOLD CAPITAL A
        assertEquals(
            FontRole.Unknown,
            CjkFontRoleClassifier().classify(text, TextRange(0, text.length)),
        )
    }

    @Test
    fun bmpMathAndCurrencySymbolsResolveToSymbolRole() {
        testTrace.section("bmpMathAndCurrencySymbolsResolveToSymbolRole")
        assertEquals(
            FontRole.Symbol,
            CjkFontRoleClassifier().classify("±", TextRange(0, 1)),
        )
        assertEquals(
            FontRole.Symbol,
            CjkFontRoleClassifier().classify("€", TextRange(0, 1)),
        )
    }

    @AfterTest
    fun flushTestTrace() {
        testTrace.flush()
    }
}
