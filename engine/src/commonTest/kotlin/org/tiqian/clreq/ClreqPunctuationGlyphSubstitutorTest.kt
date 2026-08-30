package org.tiqian.clreq

import kotlin.test.Test
import org.tiqian.test.trace.assertEquals
import kotlin.test.AfterTest
import org.tiqian.test.trace.TestTraceRecorder

class ClreqPunctuationGlyphSubstitutorTest {
    private val testTrace = TestTraceRecorder("ClreqPunctuationGlyphSubstitutorTest")

    @Test
    fun preferPolicyUsesClreqRecommendedDisplayCodepoints() {
        testTrace.section("preferPolicyUsesClreqRecommendedDisplayCodepoints")
        val substitutor = ClreqPunctuationGlyphSubstitutor(
            policy = CjkPunctuationGlyphPolicy.PreferClreqRecommendedCodepoints,
        )

        assertEquals("⋯⋯", substitutor.substitute("……").displayText)
        assertEquals("⸺", substitutor.substitute("——").displayText)
        assertEquals("·", substitutor.substitute("・").displayText)
        assertEquals("·", substitutor.substitute("‧").displayText)
        assertEquals("·", substitutor.substitute("•").displayText)
    }

    @Test
    fun preservePolicyKeepsInputDisplayCodepoints() {
        testTrace.section("preservePolicyKeepsInputDisplayCodepoints")
        val substitutor = ClreqPunctuationGlyphSubstitutor(
            policy = CjkPunctuationGlyphPolicy.PreserveInput,
        )

        assertEquals("……", substitutor.substitute("……").displayText)
        assertEquals("——", substitutor.substitute("——").displayText)
        assertEquals("・", substitutor.substitute("・").displayText)
    }

    @Test
    fun preferPolicyDoesNotRewriteAmbiguousConnectorOrSolidusForms() {
        testTrace.section("preferPolicyDoesNotRewriteAmbiguousConnectorOrSolidusForms")
        val substitutor = ClreqPunctuationGlyphSubstitutor(
            policy = CjkPunctuationGlyphPolicy.PreferClreqRecommendedCodepoints,
        )

        assertEquals("～", substitutor.substitute("～").displayText)
        assertEquals("-", substitutor.substitute("-").displayText)
        assertEquals("/", substitutor.substitute("/").displayText)
        assertEquals("／", substitutor.substitute("／").displayText)
        assertEquals("．", substitutor.substitute("．").displayText)
    }

    @Test
    fun recommendedDashCodepointOccupiesTwoEm() {
        testTrace.section("recommendedDashCodepointOccupiesTwoEm")
        assertEquals(2.0f, ClreqPunctuationPolicies.policyFor('⸺').defaultBodyEm)
        assertEquals(2.0f, ClreqPunctuationPolicies.policyFor('⸺').defaultAdvanceEm)
        assertEquals(2.0f, ClreqPunctuationAdvancePolicy.advanceEm(sourceText = "⸺", displayText = "⸺"))
        assertEquals(2.0f, ClreqPunctuationAdvancePolicy.advanceEm(sourceText = "——", displayText = "⸺"))
    }

    @AfterTest
    fun flushTestTrace() {
        testTrace.flush()
    }
}
