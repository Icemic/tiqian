package org.tiqian.clreq

import org.tiqian.core.BuiltInLayoutProfiles
import org.tiqian.core.LayoutProfileId
import kotlin.test.Test
import org.tiqian.test.trace.assertEquals
import org.tiqian.test.trace.assertFalse
import org.tiqian.test.trace.assertNotNull
import org.tiqian.test.trace.assertTrue
import kotlin.test.AfterTest
import org.tiqian.test.trace.TestTraceRecorder

// A lone surrogate written inside a string literal is replaced with '?' when
// the JS test bundle re-serializes its sources, so inputs that carry one are
// built from char codes at runtime to keep the code unit intact everywhere.
private fun surrogateText(vararg codes: Int): String =
    CharArray(codes.size) { codes[it].toChar() }.concatToString()

class ClreqProfileCoverageTest {
    private val testTrace = TestTraceRecorder("ClreqProfileCoverageTest")


    @Test
    fun testBopomofoModelsAndParser() {
        testTrace.section("testBopomofoModelsAndParser")
        for (tone in BopomofoTone.entries) {
            assertNotNull(BopomofoTone.valueOf(tone.name))
        }

        val reading = BopomofoReading(listOf("ㄅ", "ㄚ"), BopomofoTone.Yangping)
        assertEquals(listOf("ㄅ", "ㄚ"), reading.symbols)
        assertEquals(BopomofoTone.Yangping, reading.tone)
        assertEquals(reading, reading.copy())
        assertTrue(reading.hashCode() == reading.copy().hashCode())
        assertTrue(reading.toString().contains("BopomofoReading"))

        // BopomofoParser
        val emptyReading = BopomofoParser.parse("")
        assertEquals(emptyList(), emptyReading.symbols)
        assertEquals(BopomofoTone.Yinping, emptyReading.tone)

        val neutralReading = BopomofoParser.parse("˙ㄇㄚ")
        assertEquals(listOf("ㄇ", "ㄚ"), neutralReading.symbols)
        assertEquals(BopomofoTone.Neutral, neutralReading.tone)

        val yangpingReading = BopomofoParser.parse("ㄇㄚˊ")
        assertEquals(listOf("ㄇ", "ㄚ"), yangpingReading.symbols)
        assertEquals(BopomofoTone.Yangping, yangpingReading.tone)

        val shangReading = BopomofoParser.parse("ㄇㄚˇ")
        assertEquals(listOf("ㄇ", "ㄚ"), shangReading.symbols)
        assertEquals(BopomofoTone.Shang, shangReading.tone)

        val quReading = BopomofoParser.parse("ㄇㄚˋ")
        assertEquals(listOf("ㄇ", "ㄚ"), quReading.symbols)
        assertEquals(BopomofoTone.Qu, quReading.tone)

        val explicitYinping = BopomofoParser.parse("ㄇㄚˉ")
        assertEquals(listOf("ㄇ", "ㄚ"), explicitYinping.symbols)
        assertEquals(BopomofoTone.Yinping, explicitYinping.tone)

        val defaultYinping = BopomofoParser.parse("ㄇㄚ")
        assertEquals(listOf("ㄇ", "ㄚ"), defaultYinping.symbols)
        assertEquals(BopomofoTone.Yinping, defaultYinping.tone)
    }

    @Test
    fun testClreqProfileAndResolver() {
        testTrace.section("testClreqProfileAndResolver")
        for (strictness in ClreqStrictness.entries) {
            assertNotNull(ClreqStrictness.valueOf(strictness.name))
        }
        for (region in ClreqRegion.entries) {
            assertNotNull(ClreqRegion.valueOf(region.name))
        }
        for (policy in CjkPunctuationGlyphPolicy.entries) {
            assertNotNull(CjkPunctuationGlyphPolicy.valueOf(policy.name))
        }
        for (cls in PunctuationClass.entries) {
            assertNotNull(PunctuationClass.valueOf(cls.name))
        }

        assertTrue(ClreqProfile.DefaultCoalesceRepeatablePunctuation.contains(0x2014))
        assertEquals("clreq-mainland-horizontal", ClreqProfile.MainlandHorizontal.id)
        assertEquals("clreq-taiwan-horizontal", ClreqProfile.TaiwanHorizontal.id)
        assertEquals("clreq-hongkong-horizontal", ClreqProfile.HongKongHorizontal.id)

        assertEquals(PunctuationGluePlacement.MainlandSimplified, PunctuationGluePlacement.forRegion(ClreqRegion.Mainland))
        assertEquals(PunctuationGluePlacement.Traditional, PunctuationGluePlacement.forRegion(ClreqRegion.Taiwan))
        assertEquals(PunctuationGluePlacement.Traditional, PunctuationGluePlacement.forRegion(ClreqRegion.HongKong))
        assertEquals(PunctuationGluePlacement.MainlandSimplified, PunctuationGluePlacement.forRegion(ClreqRegion.Custom))

        val resolvedBuiltIn = BuiltInClreqProfileResolver.resolve(BuiltInLayoutProfiles.ClreqHorizontal)
        assertEquals(ClreqProfile.MainlandHorizontal, resolvedBuiltIn)

        val resolvedMainlandId = BuiltInClreqProfileResolver.resolve(LayoutProfileId("clreq-mainland-horizontal"))
        assertEquals(ClreqProfile.MainlandHorizontal, resolvedMainlandId)

        val resolvedOtherId = BuiltInClreqProfileResolver.resolve(LayoutProfileId("other-profile"))
        assertEquals(ClreqProfile.MainlandHorizontal, resolvedOtherId)
    }

    @Test
    fun testClreqPunctuationPoliciesAndClassification() {
        testTrace.section("testClreqPunctuationPoliciesAndClassification")
        for (ch in listOf(',', '.', ':', ';', '!', '?')) {
            assertTrue(ClreqPunctuationPolicies.isAsciiPointMark(ch))
        }
        assertFalse(ClreqPunctuationPolicies.isAsciiPointMark('a'))
        assertFalse(ClreqPunctuationPolicies.isAsciiPointMark('，'))

        // Test every branch in classify
        val openingChars = listOf('“', '‘', '（', '《', '〈', '「', '『', '【', '〔', '〖', '〘', '〚')
        for (ch in openingChars) {
            assertEquals(PunctuationClass.Opening, ClreqPunctuationPolicies.classify(ch))
        }

        val closingChars = listOf('”', '’', '）', '》', '〉', '」', '』', '】', '〕', '〗', '〙', '〛')
        for (ch in closingChars) {
            assertEquals(PunctuationClass.Closing, ClreqPunctuationPolicies.classify(ch))
        }

        val pauseOrStopChars = listOf('，', '、', '。', '；', '：', '！', '？')
        for (ch in pauseOrStopChars) {
            assertEquals(PunctuationClass.PauseOrStop, ClreqPunctuationPolicies.classify(ch))
        }

        assertEquals(PunctuationClass.MiddleDot, ClreqPunctuationPolicies.classify('·'))
        for (ch in listOf('・', '‧', '•')) {
            assertEquals(PunctuationClass.Interpunct, ClreqPunctuationPolicies.classify(ch))
        }
        for (ch in listOf('～', '~', '-', '–')) {
            assertEquals(PunctuationClass.Connector, ClreqPunctuationPolicies.classify(ch))
        }
        for (ch in listOf('/', '／')) {
            assertEquals(PunctuationClass.Solidus, ClreqPunctuationPolicies.classify(ch))
        }
        for (ch in listOf('…', '⋯')) {
            assertEquals(PunctuationClass.Ellipsis, ClreqPunctuationPolicies.classify(ch))
        }
        for (ch in listOf('—', '⸺')) {
            assertEquals(PunctuationClass.Dash, ClreqPunctuationPolicies.classify(ch))
        }
        assertEquals(PunctuationClass.Other, ClreqPunctuationPolicies.classify('中'))
    }

    @Test
    fun testForcedHalfWidthAndPolicyFor() {
        testTrace.section("testForcedHalfWidthAndPolicyFor")
        // Hyphens are always forced half width
        assertTrue(ClreqPunctuationPolicies.forcedHalfWidth('-', PunctuationWidthPolicy()))
        assertTrue(ClreqPunctuationPolicies.forcedHalfWidth('–', PunctuationWidthPolicy()))

        // gbFixedSeparators
        val gbPolicy = PunctuationWidthPolicy(gbFixedSeparators = true)
        assertTrue(ClreqPunctuationPolicies.forcedHalfWidth('～', gbPolicy))
        assertTrue(ClreqPunctuationPolicies.forcedHalfWidth('·', gbPolicy))
        assertTrue(ClreqPunctuationPolicies.forcedHalfWidth('•', gbPolicy))
        assertTrue(ClreqPunctuationPolicies.forcedHalfWidth('/', gbPolicy))
        assertFalse(ClreqPunctuationPolicies.forcedHalfWidth('，', gbPolicy))

        // Kaiming interior style
        val kaimingPolicy = PunctuationWidthPolicy(
            gbFixedSeparators = false,
            interior = InteriorPunctuationStyle.Kaiming,
        )
        assertTrue(ClreqPunctuationPolicies.forcedHalfWidth('（', kaimingPolicy)) // Opening
        assertTrue(ClreqPunctuationPolicies.forcedHalfWidth('）', kaimingPolicy)) // Closing
        assertTrue(ClreqPunctuationPolicies.forcedHalfWidth('，', kaimingPolicy)) // Pause not sentence end
        assertTrue(ClreqPunctuationPolicies.forcedHalfWidth('；', kaimingPolicy)) // Pause not sentence end
        assertFalse(ClreqPunctuationPolicies.forcedHalfWidth('。', kaimingPolicy)) // Sentence end stop (full width)
        assertFalse(ClreqPunctuationPolicies.forcedHalfWidth('！', kaimingPolicy)) // Sentence end stop
        assertFalse(ClreqPunctuationPolicies.forcedHalfWidth('？', kaimingPolicy)) // Sentence end stop
        assertFalse(ClreqPunctuationPolicies.forcedHalfWidth('．', kaimingPolicy)) // Sentence end stop
        assertFalse(ClreqPunctuationPolicies.forcedHalfWidth('中', kaimingPolicy)) // Other

        // policyFor
        val dash2Policy = ClreqPunctuationPolicies.policyFor('⸺')
        assertEquals(2.0f, dash2Policy.defaultBodyEm)
        assertEquals(2.0f, dash2Policy.defaultAdvanceEm)

        val hyphenPolicy = ClreqPunctuationPolicies.policyFor('-')
        assertEquals(0.5f, hyphenPolicy.defaultBodyEm)
        assertEquals(0.5f, hyphenPolicy.defaultAdvanceEm)

        val commaPolicy = ClreqPunctuationPolicies.policyFor('，')
        assertEquals(0.5f, commaPolicy.defaultBodyEm)
        assertEquals(1.0f, commaPolicy.defaultAdvanceEm)

        val openPolicy = ClreqPunctuationPolicies.policyFor('（')
        assertEquals(0.5f, openPolicy.defaultBodyEm)
        assertEquals(1.0f, openPolicy.defaultAdvanceEm)

        val closePolicy = ClreqPunctuationPolicies.policyFor('）')
        assertEquals(0.5f, closePolicy.defaultBodyEm)
        assertEquals(1.0f, closePolicy.defaultAdvanceEm)

        val hanPolicy = ClreqPunctuationPolicies.policyFor('字')
        assertEquals(1.0f, hanPolicy.defaultBodyEm)
        assertEquals(1.0f, hanPolicy.defaultAdvanceEm)
    }

    @Test
    fun testForbiddenAtLineStartAndEnd() {
        testTrace.section("testForbiddenAtLineStartAndEnd")
        // None level allows everywhere
        assertFalse(ClreqPunctuationPolicies.forbiddenAtLineStart('，', KinsokuLevel.None))
        assertFalse(ClreqPunctuationPolicies.forbiddenAtLineEnd('（', KinsokuLevel.None))

        // forbiddenAtLineStart
        assertTrue(ClreqPunctuationPolicies.forbiddenAtLineStart('，', KinsokuLevel.Basic))
        assertTrue(ClreqPunctuationPolicies.forbiddenAtLineStart('）', KinsokuLevel.Basic))
        assertTrue(ClreqPunctuationPolicies.forbiddenAtLineStart('～', KinsokuLevel.Basic))
        assertTrue(ClreqPunctuationPolicies.forbiddenAtLineStart('·', KinsokuLevel.Basic))
        assertTrue(ClreqPunctuationPolicies.forbiddenAtLineStart('•', KinsokuLevel.Basic))
        assertTrue(ClreqPunctuationPolicies.forbiddenAtLineStart('/', KinsokuLevel.Basic))

        // Dash & Ellipsis only forbidden at line start in Strict level
        assertFalse(ClreqPunctuationPolicies.forbiddenAtLineStart('—', KinsokuLevel.Basic))
        assertTrue(ClreqPunctuationPolicies.forbiddenAtLineStart('—', KinsokuLevel.Strict))
        assertFalse(ClreqPunctuationPolicies.forbiddenAtLineStart('…', KinsokuLevel.Basic))
        assertTrue(ClreqPunctuationPolicies.forbiddenAtLineStart('…', KinsokuLevel.Strict))

        assertFalse(ClreqPunctuationPolicies.forbiddenAtLineStart('（', KinsokuLevel.Strict))
        assertFalse(ClreqPunctuationPolicies.forbiddenAtLineStart('字', KinsokuLevel.Strict))

        // forbiddenAtLineEnd
        assertTrue(ClreqPunctuationPolicies.forbiddenAtLineEnd('（', KinsokuLevel.Basic))
        assertFalse(ClreqPunctuationPolicies.forbiddenAtLineEnd('/', KinsokuLevel.Basic))
        assertTrue(ClreqPunctuationPolicies.forbiddenAtLineEnd('/', KinsokuLevel.Strict))
        assertFalse(ClreqPunctuationPolicies.forbiddenAtLineEnd('）', KinsokuLevel.Strict))
        assertFalse(ClreqPunctuationPolicies.forbiddenAtLineEnd('字', KinsokuLevel.Strict))
    }

    @Test
    fun testPunctuationAdvanceAndSubstitutor() {
        testTrace.section("testPunctuationAdvanceAndSubstitutor")
        assertEquals(2.0f, ClreqPunctuationAdvancePolicy.advanceEm("⸺", "⸺"))
        assertEquals(2.0f, ClreqPunctuationAdvancePolicy.advanceEm("—", "⸺"))
        assertEquals(2.0f, ClreqPunctuationAdvancePolicy.advanceEm("⸺", "——"))
        assertEquals(3.0f, ClreqPunctuationAdvancePolicy.advanceEm("abc", "abc"))

        // String with surrogate pair
        assertEquals(1.0f, ClreqPunctuationAdvancePolicy.advanceEm("\uD83D\uDE00", "dummy"))
        // Lone surrogates and non-surrogate combinations
        assertEquals(1.0f, ClreqPunctuationAdvancePolicy.advanceEm(surrogateText(0xD800), "dummy"))
        assertEquals(2.0f, ClreqPunctuationAdvancePolicy.advanceEm(surrogateText(0xD800, 'A'.code), "dummy")) // low < 0xDC00
        assertEquals(2.0f, ClreqPunctuationAdvancePolicy.advanceEm(surrogateText(0xD800, 0xE000), "dummy")) // low > 0xDFFF

        // Substitutors
        val preserveSubstitutor = ClreqPunctuationGlyphSubstitutor(CjkPunctuationGlyphPolicy.PreserveInput)
        val preserveRes = preserveSubstitutor.substitute("……")
        assertEquals("……", preserveRes.displayText)
        assertTrue(preserveRes.reason.contains("preserve"))

        val preferSubstitutor = ClreqPunctuationGlyphSubstitutor(CjkPunctuationGlyphPolicy.PreferClreqRecommendedCodepoints)
        val preferRes = preferSubstitutor.substitute("——")
        assertEquals("——", preferRes.sourceText)

        val forceSubstitutor = ClreqPunctuationGlyphSubstitutor(CjkPunctuationGlyphPolicy.ForceClreqRecommendedCodepoints)
        val forceRes = forceSubstitutor.substitute("abc")
        assertEquals("abc", forceRes.displayText)
        assertTrue(forceRes.reason.contains("preserve"))
    }

    @AfterTest
    fun flushTestTrace() {
        testTrace.flush()
    }
}
