package org.tiqian.core

import kotlin.test.Test
import org.tiqian.test.trace.assertEquals
import org.tiqian.test.trace.assertFalse
import org.tiqian.test.trace.assertTrue
import kotlin.test.AfterTest
import org.tiqian.test.trace.TestTraceRecorder

class EastAsianSpacingTest {
    private val testTrace = TestTraceRecorder("EastAsianSpacingTest")

    @Test
    fun chineseLanguageContextUsesPinnedMacrolanguageRegistry() {
        testTrace.section("chineseLanguageContextUsesPinnedMacrolanguageRegistry")
        assertTrue(UnicodeEastAsianSpacing.isChineseLanguageContext("zh-Hans"))
        assertTrue(UnicodeEastAsianSpacing.isChineseLanguageContext("yue-Hant-HK"))
        assertFalse(UnicodeEastAsianSpacing.isChineseLanguageContext("en"))
    }

    @Test
    fun usesPinnedUnicodeDraftDataAcrossScripts() {
        testTrace.section("usesPinnedUnicodeDraftDataAcrossScripts")
        assertEquals(EastAsianSpacingValue.Wide, UnicodeEastAsianSpacing.propertyOf('提'.code))
        assertEquals(EastAsianSpacingValue.Wide, UnicodeEastAsianSpacing.propertyOf(0x17000))
        assertEquals(EastAsianSpacingValue.Narrow, UnicodeEastAsianSpacing.propertyOf('A'.code))
        assertEquals(EastAsianSpacingValue.Narrow, UnicodeEastAsianSpacing.propertyOf('α'.code))
        assertEquals(EastAsianSpacingValue.Narrow, UnicodeEastAsianSpacing.propertyOf('я'.code))
        assertEquals(EastAsianSpacingValue.Narrow, UnicodeEastAsianSpacing.propertyOf('9'.code))
        assertEquals(EastAsianSpacingValue.Conditional, UnicodeEastAsianSpacing.propertyOf('%'.code))
        assertEquals(EastAsianSpacingValue.Other, UnicodeEastAsianSpacing.propertyOf('／'.code))
        assertEquals(EastAsianSpacingValue.Other, UnicodeEastAsianSpacing.propertyOf(0x1F600))
    }

    @Test
    fun resolvesConditionalValuesFromChineseLanguageContext() {
        testTrace.section("resolvesConditionalValuesFromChineseLanguageContext")
        assertEquals(
            EastAsianSpacingValue.Narrow,
            UnicodeEastAsianSpacing.resolvedForGraphemeCluster("%", "zh-Hans"),
        )
        assertEquals(
            EastAsianSpacingValue.Narrow,
            UnicodeEastAsianSpacing.resolvedForGraphemeCluster("%", "yue-Hant-HK"),
        )
        assertEquals(
            EastAsianSpacingValue.Other,
            UnicodeEastAsianSpacing.resolvedForGraphemeCluster("%", "en"),
        )
    }

    @Test
    fun enclosingMarkMakesTheWholeGraphemeClusterOther() {
        testTrace.section("enclosingMarkMakesTheWholeGraphemeClusterOther")
        assertEquals(
            EastAsianSpacingValue.Other,
            UnicodeEastAsianSpacing.resolvedForGraphemeCluster("A\u20DD", "zh-Hans"),
        )
    }

    @Test
    fun resolvesTheActualSourceUnitAtEachShapingClusterEdge() {
        testTrace.section("resolvesTheActualSourceUnitAtEachShapingClusterEdge")
        assertEquals(
            EastAsianSpacingEdges(
                leading = EastAsianSpacingValue.Other,
                trailing = EastAsianSpacingValue.Narrow,
                containsWide = false,
            ),
            UnicodeEastAsianSpacing.resolvedEdges("/Hi", "zh-Hans"),
        )
        assertEquals(
            EastAsianSpacingEdges(
                leading = EastAsianSpacingValue.Other,
                trailing = EastAsianSpacingValue.Other,
                containsWide = false,
            ),
            UnicodeEastAsianSpacing.resolvedEdges("A\u20DD", "zh-Hans"),
        )
    }

    @AfterTest
    fun flushTestTrace() {
        testTrace.flush()
    }
}
