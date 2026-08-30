package org.tiqian.font

import kotlin.test.Test
import kotlin.test.assertEquals

class CjkDashCapabilityPolicyTest {
    @Test
    fun nullStatusNamesMissingConformingGlyphAndUnpreparedDetail() {
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
        assertEquals(
            CjkDashCapabilityPolicy.NoConformingCjkDashGlyph,
            CjkDashCapabilityPolicy.issueNameFor("unavailable"),
        )
        assertEquals(
            "status=unavailable",
            CjkDashCapabilityPolicy.issueDetailFor("unavailable", null),
        )
    }
}