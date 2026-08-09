package org.tiqian.shaping.coretext

import org.tiqian.core.TextRange
import org.tiqian.core.TextStyle
import org.tiqian.font.FontCandidate
import org.tiqian.font.FontDecision
import org.tiqian.font.FontMetricsRequest
import org.tiqian.font.FontRole
import org.tiqian.shaping.ShapingInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Runtime validation that the Core Text cinterop actually executes on macOS (not just
 * compiles): shapes real CJK/Latin text and reads real font metrics via PingFang SC /
 * Helvetica Neue (system fonts present on macOS).
 */
class CoreTextShaperTest {
    private val shaper = CoreTextShaper()
    private val metrics = CoreTextFontMetricsResolver()

    @Test
    fun shapesCjkRunWithRealAdvance() {
        val result = shaper.shape(input("中文", FontRole.CjkText))
        assertEquals(1, result.clusters.size)
        assertTrue(result.clusters.single().advance > 0f, "cluster advance should be positive")
        assertTrue(result.glyphRuns.single().glyphs.isNotEmpty(), "should produce glyphs")
        assertEquals("CoreText", result.decisions.single().source)
    }

    @Test
    fun glyphAdvancesSumToClusterAdvance() {
        val result = shaper.shape(input("中文书", FontRole.CjkText))
        val cluster = result.clusters.single()
        val glyphSum = result.glyphRuns.single().glyphs.sumOf { it.advance.toDouble() }.toFloat()
        assertEquals(cluster.advance, glyphSum, 0.5f)
    }

    @Test
    fun shapesLatinRunWithRealAdvance() {
        val result = shaper.shape(input("Tiqian", FontRole.LatinText))
        assertTrue(result.clusters.single().advance > 0f)
        assertTrue(result.glyphRuns.single().glyphs.isNotEmpty())
    }

    @Test
    fun exposesInkBoundsForIdeographicStop() {
        val result = shaper.shape(input("。", FontRole.CjkPunctuation))
        val glyph = result.glyphRuns.single().glyphs.first()
        assertTrue(glyph.advance > 0f)
        assertNotNull(glyph.bounds, "。 should carry ink bounds")
    }

    @Test
    fun resolvesCjkMetricsWithTypoBox() {
        val m = metrics.resolve(
            FontMetricsRequest(fontKey = "cjk", fontSize = 16f, role = FontRole.CjkText, locale = "zh-Hans"),
        )
        assertTrue(m.ascent > 0f && m.descent > 0f, "hhea metrics should be positive")
        assertNotNull(m.typoAscent, "a real CJK font should expose OS/2 sTypo ascent")
        assertNotNull(m.typoDescent)
        assertTrue(m.typoAscent!! > 0f && m.typoDescent!! > 0f)
    }

    @Test
    fun resolvesLatinMetrics() {
        val m = metrics.resolve(
            FontMetricsRequest(fontKey = "latin", fontSize = 16f, role = FontRole.LatinText, locale = "en"),
        )
        assertTrue(m.ascent > 0f && m.descent > 0f)
    }

    @Test
    fun emptyDisplayTextProducesDegenerateCluster() {
        // A layout-decided empty display cluster (e.g. a fully compressed space) must not shape
        // glyphs but must still emit one zero-advance cluster + run so downstream indices align.
        val result = shaper.shape(input("空", FontRole.CjkText, displayText = ""))
        val cluster = result.clusters.single()
        assertEquals(0f, cluster.advance)
        assertTrue(result.glyphRuns.single().glyphs.isEmpty(), "empty display text must not shape glyphs")
        assertEquals(0, result.decisions.single().glyphCount)
        assertTrue(
            result.decisions.single().reason.contains("empty"),
            "reason should record the empty branch, got '${result.decisions.single().reason}'",
        )
    }

    @Test
    fun shapesSameInputConsistentlyAcrossCalls() {
        // Second call hits CoreTextSupport's borrowed font cache; a use-after-free / rebuild
        // mismatch would change the advance or crash. measure==draw needs it stable.
        val a = shaper.shape(input("中文书", FontRole.CjkText))
        val b = shaper.shape(input("中文书", FontRole.CjkText))
        assertEquals(a.clusters.single().advance, b.clusters.single().advance)
        assertEquals(a.glyphRuns.single().glyphs.size, b.glyphRuns.single().glyphs.size)
    }

    private fun input(text: String, role: FontRole, displayText: String = text): ShapingInput =
        ShapingInput(
            text = text,
            range = TextRange(0, text.length),
            style = TextStyle(fontSize = 16f),
            fontDecision = FontDecision(
                range = TextRange(0, text.length),
                candidate = FontCandidate("test-${role.name}", "test-${role.name}", role),
                role = role,
                reason = "test",
            ),
            displayText = displayText,
        )
}
