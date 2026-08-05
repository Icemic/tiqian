package org.tiqian.core

import kotlin.test.Test
import kotlin.test.assertEquals

class EastAsianSpacingTest {
    @Test
    fun usesPinnedUnicodeDraftDataAcrossScripts() {
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
        assertEquals(
            EastAsianSpacingValue.Other,
            UnicodeEastAsianSpacing.resolvedForGraphemeCluster("A\u20DD", "zh-Hans"),
        )
    }

    @Test
    fun resolvesTheActualSourceUnitAtEachShapingClusterEdge() {
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
}
