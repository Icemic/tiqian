package org.tiqian.font

import kotlin.test.Test
import org.tiqian.test.trace.assertEquals
import kotlin.test.AfterTest
import org.tiqian.test.trace.TestTraceRecorder

class CjkDashCapabilityPolicyTest {
    private val testTrace = TestTraceRecorder("CjkDashCapabilityPolicyTest")

    @Test
    fun nullStatusNamesMissingConformingGlyphAndUnpreparedDetail() {
        testTrace.section("nullStatusNamesMissingConformingGlyphAndUnpreparedDetail")
        assertEquals(
            CjkDashCapabilityPolicy.NoConformingCjkDashGlyph,
            CjkDashCapabilityPolicy.issueNameFor(null),
        )
        assertEquals(
            "CjkDashFontShapingNotPrepared",
            CjkDashCapabilityPolicy.issueDetailFor(null, null),
        )
    }

    @Test
    fun conformingStatusWithBlankDetailNamesTheMissingSession() {
        testTrace.section("conformingStatusWithBlankDetailNamesTheMissingSession")
        assertEquals(
            CjkDashCapabilityPolicy.ConformingCjkDashRequiresExactFontSession,
            CjkDashCapabilityPolicy.issueNameFor("conforming"),
        )
        assertEquals(
            "status=conforming",
            CjkDashCapabilityPolicy.issueDetailFor("conforming", "  "),
        )
    }

    @Test
    fun conformingStatusWithDetailAppendsHostEvidence() {
        testTrace.section("conformingStatusWithDetailAppendsHostEvidence")
        assertEquals(
            CjkDashCapabilityPolicy.ConformingCjkDashRequiresExactFontSession,
            CjkDashCapabilityPolicy.issueNameFor("conforming"),
        )
        assertEquals(
            "status=conforming; FixtureDashFace",
            CjkDashCapabilityPolicy.issueDetailFor("conforming", "FixtureDashFace"),
        )
    }

    @Test
    fun nonConformingStatusWithDetailNamesMissingGlyphAndAppendsEvidence() {
        testTrace.section("nonConformingStatusWithDetailNamesMissingGlyphAndAppendsEvidence")
        assertEquals(
            CjkDashCapabilityPolicy.NoConformingCjkDashGlyph,
            CjkDashCapabilityPolicy.issueNameFor("unavailable"),
        )
        assertEquals(
            "status=unavailable; BrowserHarfBuzzDisabled",
            CjkDashCapabilityPolicy.issueDetailFor("unavailable", "BrowserHarfBuzzDisabled"),
        )
    }

    @Test
    fun nonConformingStatusWithBlankDetailKeepsOnlyStatusPrefix() {
        testTrace.section("nonConformingStatusWithBlankDetailKeepsOnlyStatusPrefix")
        assertEquals(
            CjkDashCapabilityPolicy.NoConformingCjkDashGlyph,
            CjkDashCapabilityPolicy.issueNameFor("unavailable"),
        )
        assertEquals(
            "status=unavailable",
            CjkDashCapabilityPolicy.issueDetailFor("unavailable", null),
        )
    }

    @AfterTest
    fun flushTestTrace() {
        testTrace.flush()
    }
}