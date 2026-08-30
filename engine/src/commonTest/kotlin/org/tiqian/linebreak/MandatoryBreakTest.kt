package org.tiqian.linebreak

import kotlin.test.Test
import org.tiqian.test.trace.assertEquals
import org.tiqian.test.trace.assertFalse
import org.tiqian.test.trace.assertTrue
import kotlin.test.AfterTest
import org.tiqian.test.trace.TestTraceRecorder

class MandatoryBreakTest {
    private val testTrace = TestTraceRecorder("MandatoryBreakTest")

    private val analyzer = SimpleCharacterLineBreakAnalyzer()

    @Test
    fun recognizesMandatoryBreakCodePoints() {
        testTrace.section("recognizesMandatoryBreakCodePoints")
        for (cp in listOf(0x000A, 0x000B, 0x000C, 0x000D, 0x0085, 0x2028, 0x2029)) {
            assertTrue(isMandatoryBreakCodePoint(cp), u(cp))
        }
        for (cp in listOf('a'.code, '中'.code, ' '.code, '\t'.code, 0x3000)) {
            assertFalse(isMandatoryBreakCodePoint(cp), u(cp))
        }
    }

    @Test
    fun recognizesZeroWidthSpaceWithoutConflatingNoBreakControls() {
        testTrace.section("recognizesZeroWidthSpaceWithoutConflatingNoBreakControls")
        assertTrue(isZeroWidthSpaceCodePoint(0x200B), u(0x200B))
        for (cp in listOf(0x200C, 0x200D, 0x2060, 0xFEFF)) {
            assertFalse(isZeroWidthSpaceCodePoint(cp), u(cp))
        }
    }

    // Common-stdlib codepoint label (no JVM String.format, so this test compiles on JS too).
    private fun u(cp: Int): String = "U+" + cp.toString(16).uppercase().padStart(4, '0')

    @Test
    fun marksRequiredAfterLineFeed() {
        testTrace.section("marksRequiredAfterLineFeed")
        val ops = analyzer.analyze("a\nb")
        assertEquals(BreakKind.Required, ops.single { it.index == 2 }.kind) // after '\n'
        assertEquals(BreakKind.Allowed, ops.single { it.index == 1 }.kind) // after 'a'
    }

    @Test
    fun collapsesCrlfToASingleBreakAfterLf() {
        testTrace.section("collapsesCrlfToASingleBreakAfterLf")
        val ops = analyzer.analyze("a\r\nb") // indices: a=0 \r=1 \n=2 b=3
        assertEquals(BreakKind.Allowed, ops.single { it.index == 2 }.kind) // NOT after the CR
        assertEquals(BreakKind.Required, ops.single { it.index == 3 }.kind) // after the LF
    }

    @Test
    fun preservesEachBlankLineBreak() {
        testTrace.section("preservesEachBlankLineBreak")
        val ops = analyzer.analyze("a\n\nb") // a=0 \n=1 \n=2 b=3
        assertEquals(BreakKind.Required, ops.single { it.index == 2 }.kind)
        assertEquals(BreakKind.Required, ops.single { it.index == 3 }.kind)
    }

    @AfterTest
    fun flushTestTrace() {
        testTrace.flush()
    }
}
