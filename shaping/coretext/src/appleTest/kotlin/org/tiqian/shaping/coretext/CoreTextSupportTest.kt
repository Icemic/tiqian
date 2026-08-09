package org.tiqian.shaping.coretext

import kotlinx.cinterop.useContents
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Unit coverage for the shared Core Text helper + its process-lived font cache. The cache hands
 * out *borrowed* refs (callers never CFRelease), so the contract under test is: a real family
 * resolves to a font, the weighted (CTFontDescriptor) path resolves too, repeat requests are
 * consistent, and [CoreTextSupport.cfRange] builds the range asked for — the correctness the
 * shaper / metrics / renderer all rely on for `measure == draw`. (That the cache reuse is free of
 * use-after-free is proven end-to-end by the shaper's render-twice and the renderer's
 * render-same-result-twice tests.)
 */
class CoreTextSupportTest {

    @Test
    fun resolvesRealSystemFamilies() {
        assertNotNull(CoreTextSupport.font(CoreTextSupport.DEFAULT_CJK_FAMILY, 16.0), "CJK family should resolve")
        assertNotNull(CoreTextSupport.font(CoreTextSupport.DEFAULT_LATIN_FAMILY, 16.0), "Latin family should resolve")
    }

    @Test
    fun resolvesWeightedFontAndIsConsistent() {
        // weight != 400 takes the CTFontDescriptor weight-trait path in createWeighted().
        val heavy = CoreTextSupport.font(CoreTextSupport.DEFAULT_CJK_FAMILY, 16.0, weight = 700)
        assertNotNull(heavy, "the weighted (CTFontDescriptor) path must resolve a font")
        // Repeat request must hand back the same font (same address) — cache hit, not a rebuild.
        assertEquals(heavy, CoreTextSupport.font(CoreTextSupport.DEFAULT_CJK_FAMILY, 16.0, weight = 700))
    }

    @Test
    fun resolvesItalicFontAndIsConsistent() {
        // italic takes the synthetic-oblique matrix path in createStyled() (ADR 0030 B 档).
        val oblique = CoreTextSupport.font(CoreTextSupport.DEFAULT_CJK_FAMILY, 16.0, italic = true)
        assertNotNull(oblique, "the italic (oblique-matrix) path must resolve a font")
        assertEquals(oblique, CoreTextSupport.font(CoreTextSupport.DEFAULT_CJK_FAMILY, 16.0, italic = true))
    }

    @Test
    fun repeatRequestReturnsConsistentFont() {
        val a = CoreTextSupport.font(CoreTextSupport.DEFAULT_CJK_FAMILY, 18.0)
        val b = CoreTextSupport.font(CoreTextSupport.DEFAULT_CJK_FAMILY, 18.0)
        assertNotNull(a)
        assertEquals(a, b, "repeat request must return the same cached font address")
    }

    @Test
    fun cfRangeCarriesLocationAndLength() {
        CoreTextSupport.cfRange(3, 5).useContents {
            assertEquals(3L, location)
            assertEquals(5L, length)
        }
    }
}
