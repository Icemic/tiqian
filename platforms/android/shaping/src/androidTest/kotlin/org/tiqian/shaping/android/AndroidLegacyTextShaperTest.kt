package org.tiqian.shaping.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import org.junit.Test
import org.junit.runner.RunWith
import org.tiqian.core.TextRange
import org.tiqian.core.TextStyle
import org.tiqian.font.FontCandidate
import org.tiqian.font.FontDecision
import org.tiqian.font.FontRole
import org.tiqian.shaping.ShapingInput
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@SdkSuppress(maxSdkVersion = 30)
class AndroidLegacyTextShaperTest {
    private val shaper = AndroidLegacyTextShaper()

    @Test
    fun ordinaryTextDoesNotRequestSyntheticHanContext() {
        assertTrue(!requiresHanShapingContext("中", FontRole.CjkText))
        assertTrue(!requiresHanShapingContext("かな", FontRole.CjkText))
        assertTrue(!requiresHanShapingContext("A", FontRole.CjkText))
        assertTrue(requiresHanShapingContext("—", FontRole.CjkText))
        assertTrue(requiresHanShapingContext("。", FontRole.CjkPunctuation))
    }

    @Test
    fun ordinaryHanStillProvidesReplayGeometry() {
        val result = shaper.shape(input("中", FontRole.CjkText))
        val cluster = result.clusters.single()
        val glyph = result.glyphRuns.single().glyphs.single()

        assertTrue(cluster.advance > 0f)
        assertEquals(cluster.advance, glyph.advance)
        assertNotNull(glyph.bounds)
    }

    @Test
    fun isolatedCjkDashKeepsContextualMeasurement() {
        val result = shaper.shape(input("—", FontRole.CjkPunctuation))
        val cluster = result.clusters.single()

        assertTrue(cluster.advance > 0f)
        assertTrue(result.glyphRuns.single().glyphs.single().bounds != null)
    }

    @Test
    fun repeatedDisplayTextKeepsExactGeometryWithoutLeakingSourceRange() {
        val text = "中甲中"
        val firstRange = TextRange(0, 1)
        val secondRange = TextRange(2, 3)
        val first = shaper.shape(input(text, FontRole.CjkText, firstRange, "中"))
        val second = shaper.shape(input(text, FontRole.CjkText, secondRange, "中"))

        assertEquals(firstRange, first.clusters.single().range)
        assertEquals(secondRange, second.clusters.single().range)
        assertEquals(first.clusters.single().advance, second.clusters.single().advance)
        assertEquals(
            first.glyphRuns.single().glyphs.single().bounds,
            second.glyphRuns.single().glyphs.single().bounds,
        )
    }

    private fun input(
        text: String,
        role: FontRole,
        range: TextRange = TextRange(0, text.length),
        displayText: String = text,
    ): ShapingInput = ShapingInput(
        text = text,
        range = range,
        style = TextStyle(fontSize = 32f, locale = "zh-Hans"),
        fontDecision = FontDecision(
            range = range,
            role = role,
            candidate = FontCandidate(
                key = "android-test-${role.name}",
                family = "android-test-${role.name}",
                role = role,
            ),
            reason = "android-legacy-test",
        ),
        displayText = displayText,
    )
}
