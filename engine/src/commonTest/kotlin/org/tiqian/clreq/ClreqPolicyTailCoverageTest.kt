package org.tiqian.clreq

import kotlin.test.Test
import org.tiqian.test.trace.assertEquals
import org.tiqian.test.trace.assertFalse
import org.tiqian.test.trace.assertTrue
import org.tiqian.core.Cluster
import org.tiqian.core.TextRange
import org.tiqian.layout.ClreqKinsokuRule
import kotlin.test.AfterTest
import org.tiqian.test.trace.TestTraceRecorder

/**
 * Coverage for the ClreqProfile kinsoku policy arms, the ClreqKinsokuRule
 * empty-display guard, and every BopomofoParser tone arm.
 */
class ClreqPolicyTailCoverageTest {
    private val testTrace = TestTraceRecorder("ClreqPolicyTailCoverageTest")


    @Test
    fun forbiddenAtLineStartCoversEveryPunctuationClass() {
        testTrace.section("forbiddenAtLineStartCoversEveryPunctuationClass")
        // PauseOrStop / Closing / MiddleDot / Interpunct / Connector / Solidus
        // are forbidden at line start at every processed level.
        for (level in listOf(KinsokuLevel.Basic, KinsokuLevel.GbStyle, KinsokuLevel.Strict)) {
            assertTrue(ClreqPunctuationPolicies.forbiddenAtLineStart('，', level), "comma at $level")
            assertTrue(ClreqPunctuationPolicies.forbiddenAtLineStart('”', level), "closing quote at $level")
            assertTrue(ClreqPunctuationPolicies.forbiddenAtLineStart('·', level), "middle dot at $level")
            assertTrue(ClreqPunctuationPolicies.forbiddenAtLineStart('・', level), "interpunct at $level")
            assertTrue(ClreqPunctuationPolicies.forbiddenAtLineStart('～', level), "connector at $level")
            assertTrue(ClreqPunctuationPolicies.forbiddenAtLineStart('/', level), "solidus at $level")
        }
        // Dash and ellipsis are only forbidden under strict processing.
        assertFalse(ClreqPunctuationPolicies.forbiddenAtLineStart('—', KinsokuLevel.Basic))
        assertFalse(ClreqPunctuationPolicies.forbiddenAtLineStart('—', KinsokuLevel.GbStyle))
        assertTrue(ClreqPunctuationPolicies.forbiddenAtLineStart('—', KinsokuLevel.Strict))
        assertFalse(ClreqPunctuationPolicies.forbiddenAtLineStart('…', KinsokuLevel.Basic))
        assertTrue(ClreqPunctuationPolicies.forbiddenAtLineStart('…', KinsokuLevel.Strict))
        // Other (plain CJK ideograph) is never forbidden at line start.
        assertFalse(ClreqPunctuationPolicies.forbiddenAtLineStart('文', KinsokuLevel.Strict))
        // None short-circuits before classification.
        assertFalse(ClreqPunctuationPolicies.forbiddenAtLineStart('，', KinsokuLevel.None))
    }

    @Test
    fun forbiddenAtLineEndCoversOpeningSolidusAndOther() {
        testTrace.section("forbiddenAtLineEndCoversOpeningSolidusAndOther")
        assertTrue(ClreqPunctuationPolicies.forbiddenAtLineEnd('“', KinsokuLevel.Basic))
        // Solidus is only forbidden at line end beyond the basic level.
        assertFalse(ClreqPunctuationPolicies.forbiddenAtLineEnd('/', KinsokuLevel.Basic))
        assertTrue(ClreqPunctuationPolicies.forbiddenAtLineEnd('/', KinsokuLevel.GbStyle))
        assertTrue(ClreqPunctuationPolicies.forbiddenAtLineEnd('/', KinsokuLevel.Strict))
        // PauseOrStop falls through to the else arm at line end.
        assertFalse(ClreqPunctuationPolicies.forbiddenAtLineEnd('，', KinsokuLevel.Strict))
        assertFalse(ClreqPunctuationPolicies.forbiddenAtLineEnd('“', KinsokuLevel.None))
    }

    @Test
    fun kinsokuRuleAllowsClustersWithoutDisplayText() {
        testTrace.section("kinsokuRuleAllowsClustersWithoutDisplayText")
        val empty = Cluster(
            range = TextRange(0, 0),
            text = "",
            displayText = "",
            fontKey = "stub",
            advance = 0.0f,
        )
        val rule = ClreqKinsokuRule()
        assertFalse(rule.forbiddenAtLineStart(empty))
        assertFalse(rule.forbiddenAtLineEnd(empty))
    }

    @Test
    fun bopomofoParserCoversEveryToneArm() {
        testTrace.section("bopomofoParserCoversEveryToneArm")
        assertEquals(BopomofoTone.Yinping, BopomofoParser.parse("ㄅㄚ").tone)
        assertEquals(listOf("ㄅ", "ㄚ"), BopomofoParser.parse("ㄅㄚ").symbols)
        assertEquals(BopomofoTone.Yangping, BopomofoParser.parse("ㄅㄚˊ").tone)
        assertEquals(BopomofoTone.Shang, BopomofoParser.parse("ㄅㄚˇ").tone)
        assertEquals(BopomofoTone.Qu, BopomofoParser.parse("ㄅㄚˋ").tone)
        assertEquals(listOf("ㄅ", "ㄚ"), BopomofoParser.parse("ㄅㄚˋ").symbols)
        // Explicit macron keeps plain Yinping.
        assertEquals(BopomofoTone.Yinping, BopomofoParser.parse("ㄅㄚˉ").tone)
        // Prefixed neutral dot strips into the tone.
        val neutral = BopomofoParser.parse("˙ㄅㄚ")
        assertEquals(BopomofoTone.Neutral, neutral.tone)
        assertEquals(listOf("ㄅ", "ㄚ"), neutral.symbols)
        // Empty reading keeps the default tone with no symbols.
        assertEquals(BopomofoTone.Yinping, BopomofoParser.parse("").tone)
        // U+02C8 sits between the Shang and Yangping cases of the tone-mark
        // tableswitch without being a tone mark: it lands on the plain
        // Yinping arm through the in-range default entry.
        assertEquals(BopomofoTone.Yinping, BopomofoParser.parse("ㄅㄚ\u02C8").tone)
        // The vertical line is not a tone mark, so it stays inside the body
        // and appears as a third symbol.
        assertEquals(listOf("ㄅ", "ㄚ", "ˈ"), BopomofoParser.parse("ㄅㄚ\u02C8").symbols)
        // U+02CC is outside the switch range entirely: range-miss default.
        assertEquals(BopomofoTone.Yinping, BopomofoParser.parse("ㄅㄚ\u02CC").tone)
    }

    @AfterTest
    fun flushTestTrace() {
        testTrace.flush()
    }
}
