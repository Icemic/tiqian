package org.tiqian.ffi.js

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.tiqian.core.Cluster
import org.tiqian.core.Glyph
import org.tiqian.core.GlyphRun
import org.tiqian.core.Rect
import org.tiqian.core.ShapingDecisionInfo
import org.tiqian.core.TextRange
import org.tiqian.core.TextStyle
import org.tiqian.font.FontCandidate
import org.tiqian.font.FontDecision
import org.tiqian.font.FontMetricSource
import org.tiqian.font.FontMetricsRequest
import org.tiqian.font.FontRole
import org.tiqian.font.RawFontMetrics
import org.tiqian.shaping.ShapingInput
import org.tiqian.shaping.ShapingResult

class WireJsonTest {

    @Test
    fun shapingInputRequestJsonIsByteExact() {
        val input = ShapingInput(
            text = "Hello world",
            range = TextRange(0, 5),
            style = TextStyle(
                fontFamilies = listOf("Noto Sans", "Roboto"),
                fontSize = 16f,
                locale = "zh-Hans",
                fontWeight = 700,
                italic = true,
            ),
            fontDecision = FontDecision(
                range = TextRange(0, 5),
                candidate = FontCandidate(key = "noto-sans-key", family = "Noto Sans", role = FontRole.LatinText),
                role = FontRole.LatinText,
                reason = "test-reason",
            ),
            displayText = "Hello",
            openTypeFeatures = listOf("kern", "liga"),
        )

        val json = input.appendShapingInputJson()
        val expected =
            "{\"text\":\"Hello world\",\"range\":{\"start\":0,\"end\":5},\"style\":{\"fontFamilies\":[\"Noto Sans\",\"Roboto\"],\"fontSize\":16,\"fontWeight\":700,\"italic\":true,\"locale\":\"zh-Hans\"},\"fontDecision\":{\"role\":\"LatinText\",\"candidateKey\":\"noto-sans-key\"},\"displayText\":\"Hello\",\"openTypeFeatures\":[\"kern\",\"liga\"]}"
        assertEquals(expected, json)

        val emptyInput = ShapingInput(
            text = "A",
            range = TextRange(0, 1),
            style = TextStyle(
                fontFamilies = emptyList(),
                fontSize = 16.5f,
                locale = "en",
                fontWeight = 400,
                italic = false,
            ),
            fontDecision = FontDecision(
                range = TextRange(0, 1),
                candidate = FontCandidate(key = "cand-1", family = "Default", role = FontRole.CjkText),
                role = FontRole.CjkText,
                reason = "default",
            ),
            displayText = "A",
            openTypeFeatures = emptyList(),
        )
        val emptyJson = emptyInput.appendShapingInputJson()
        val expectedEmpty =
            "{\"text\":\"A\",\"range\":{\"start\":0,\"end\":1},\"style\":{\"fontFamilies\":[],\"fontSize\":16.5,\"fontWeight\":400,\"italic\":false,\"locale\":\"en\"},\"fontDecision\":{\"role\":\"CjkText\",\"candidateKey\":\"cand-1\"},\"displayText\":\"A\",\"openTypeFeatures\":[]}"
        assertEquals(expectedEmpty, emptyJson)
    }

    @Test
    fun fontMetricsRequestJsonIsByteExact() {
        val request = FontMetricsRequest(
            fontKey = "cjk-key",
            fontSize = 18f,
            role = FontRole.CjkText,
            locale = "zh-Hans",
            fontFamilies = listOf("Source Han Sans"),
            fontWeight = 400,
            italic = false,
            faceSelectionText = "中",
        )

        val json = request.appendFontMetricsRequestJson()
        val expected =
            "{\"fontKey\":\"cjk-key\",\"fontSize\":18,\"role\":\"CjkText\",\"locale\":\"zh-Hans\",\"fontFamilies\":[\"Source Han Sans\"],\"fontWeight\":400,\"italic\":false,\"faceSelectionText\":\"中\"}"
        assertEquals(expected, json)
    }

    @Test
    fun parseShapingResultJsonFillsEveryFieldFromCompleteJsonLiteral() {
        val json = """
            {
              "clusters": [
                {
                  "range": { "start": 0, "end": 1 },
                  "text": "A",
                  "displayText": "A",
                  "fontKey": "font1",
                  "advance": 10.5,
                  "baselineShift": 1.2
                }
              ],
              "glyphRuns": [
                {
                  "range": { "start": 0, "end": 1 },
                  "fontKey": "font1",
                  "advance": 10.5,
                  "openTypeFeatures": ["kern"],
                  "glyphs": [
                    {
                      "id": 42,
                      "clusterRange": { "start": 0, "end": 1 },
                      "advance": 10.5,
                      "x": 0.5,
                      "y": -0.5,
                      "bounds": { "left": 0.1, "top": -8.0, "right": 9.9, "bottom": 1.0 }
                    }
                  ]
                }
              ],
              "decisions": [
                {
                  "range": { "start": 0, "end": 1 },
                  "sourceText": "A",
                  "displayText": "A",
                  "fontKey": "font1",
                  "glyphCount": 1,
                  "advance": 10.5,
                  "source": "CanvasShaper",
                  "reason": "exact-match",
                  "glyphsWithoutInkBounds": 0,
                  "missingGlyphs": 0,
                  "resolvedFace": "Face1",
                  "script": "Latn",
                  "language": "en",
                  "strategy": "Direct",
                  "featureEvidence": "kern",
                  "capabilityIssue": "None"
                }
              ]
            }
        """.trimIndent()

        val result = parseShapingResultJson(json)
        assertEquals(1, result.clusters.size)
        val cluster = result.clusters[0]
        assertEquals(TextRange(0, 1), cluster.range)
        assertEquals("A", cluster.text)
        assertEquals("A", cluster.displayText)
        assertEquals("font1", cluster.fontKey)
        assertEquals(10.5f, cluster.advance)
        assertEquals(1.2f, cluster.baselineShift)

        assertEquals(1, result.glyphRuns.size)
        val run = result.glyphRuns[0]
        assertEquals(TextRange(0, 1), run.range)
        assertEquals("font1", run.fontKey)
        assertEquals(10.5f, run.advance)
        assertEquals(listOf("kern"), run.openTypeFeatures)
        assertEquals(1, run.glyphs.size)
        val glyph = run.glyphs[0]
        assertEquals(42u, glyph.id)
        assertEquals(TextRange(0, 1), glyph.clusterRange)
        assertEquals(10.5f, glyph.advance)
        assertEquals(0.5f, glyph.x)
        assertEquals(-0.5f, glyph.y)
        assertEquals(Rect(0.1f, -8.0f, 9.9f, 1.0f), glyph.bounds)

        assertEquals(1, result.decisions.size)
        val decision = result.decisions[0]
        assertEquals(TextRange(0, 1), decision.range)
        assertEquals("A", decision.sourceText)
        assertEquals("A", decision.displayText)
        assertEquals("font1", decision.fontKey)
        assertEquals(1, decision.glyphCount)
        assertEquals(10.5f, decision.advance)
        assertEquals("CanvasShaper", decision.source)
        assertEquals("exact-match", decision.reason)
        assertEquals(0, decision.glyphsWithoutInkBounds)
        assertEquals(0, decision.missingGlyphs)
        assertEquals("Face1", decision.resolvedFace)
        assertEquals("Latn", decision.script)
        assertEquals("en", decision.language)
        assertEquals("Strategy", decision.strategy?.let { "Strategy" })
        assertEquals("Direct", decision.strategy)
        assertEquals("kern", decision.featureEvidence)
        assertEquals("None", decision.capabilityIssue)
    }

    @Test
    fun parseShapingResultJsonFillsDocumentedDefaultsFromMinimalLiteral() {
        val json = """
            {
              "clusters": [
                {
                  "range": { "start": 0, "end": 0 },
                  "text": null,
                  "displayText": null,
                  "fontKey": null,
                  "advance": null,
                  "baselineShift": null
                }
              ],
              "glyphRuns": [
                {
                  "range": { "start": 0, "end": 0 },
                  "fontKey": null,
                  "glyphs": [
                    {
                      "id": null,
                      "clusterRange": { "start": 0, "end": 0 },
                      "advance": null,
                      "x": null,
                      "y": null,
                      "bounds": null
                    }
                  ],
                  "advance": null,
                  "openTypeFeatures": null
                }
              ],
              "decisions": [
                {
                  "range": { "start": 0, "end": 0 },
                  "sourceText": null,
                  "displayText": null,
                  "fontKey": null,
                  "glyphCount": null,
                  "advance": null,
                  "source": null,
                  "reason": null,
                  "glyphsWithoutInkBounds": null,
                  "missingGlyphs": null,
                  "resolvedFace": null,
                  "script": null,
                  "language": null,
                  "strategy": null,
                  "featureEvidence": null,
                  "capabilityIssue": null
                }
              ]
            }
        """.trimIndent()

        val result = parseShapingResultJson(json)
        assertEquals(1, result.clusters.size)
        val cluster = result.clusters[0]
        assertEquals(TextRange(0, 0), cluster.range)
        assertEquals("", cluster.text)
        assertEquals("", cluster.displayText)
        assertEquals("", cluster.fontKey)
        assertTrue(cluster.advance.isNaN())
        assertEquals(0f, cluster.baselineShift)

        assertEquals(1, result.glyphRuns.size)
        val run = result.glyphRuns[0]
        assertEquals(TextRange(0, 0), run.range)
        assertEquals("", run.fontKey)
        assertTrue(run.advance.isNaN())
        assertEquals(emptyList(), run.openTypeFeatures)
        assertEquals(1, run.glyphs.size)
        val glyph = run.glyphs[0]
        assertEquals(0u, glyph.id)
        assertEquals(TextRange(0, 0), glyph.clusterRange)
        assertTrue(glyph.advance.isNaN())
        assertEquals(0f, glyph.x)
        assertEquals(0f, glyph.y)
        assertNull(glyph.bounds)

        assertEquals(1, result.decisions.size)
        val decision = result.decisions[0]
        assertEquals(TextRange(0, 0), decision.range)
        assertEquals("", decision.sourceText)
        assertEquals("", decision.displayText)
        assertEquals("", decision.fontKey)
        assertEquals(0, decision.glyphCount)
        assertTrue(decision.advance.isNaN())
        assertEquals("", decision.source)
        assertEquals("", decision.reason)
        assertEquals(0, decision.glyphsWithoutInkBounds)
        assertEquals(0, decision.missingGlyphs)
        assertNull(decision.resolvedFace)
        assertNull(decision.script)
        assertNull(decision.language)
        assertNull(decision.strategy)
        assertNull(decision.featureEvidence)
        assertNull(decision.capabilityIssue)

        val emptyResult = parseShapingResultJson("{}")
        assertEquals(emptyList(), emptyResult.clusters)
        assertEquals(emptyList(), emptyResult.glyphRuns)
        assertEquals(emptyList(), emptyResult.decisions)
    }

    @Test
    fun nanFidelityPreservesNullAdvanceAsNaN() {
        val json = """
            {
              "decisions": [
                {
                  "range": { "start": 0, "end": 1 },
                  "advance": null
                }
              ]
            }
        """.trimIndent()
        val result = parseShapingResultJson(json)
        assertEquals(1, result.decisions.size)
        assertTrue(result.decisions[0].advance.isNaN())
    }

    @Test
    fun parseRawFontMetricsJsonHandlesTypoPairAndDefaults() {
        val fullJson = """
            {
              "ascent": 18.5,
              "descent": 4.5,
              "leading": 1.0,
              "source": "OpenTypeBase",
              "typoAscent": 16.0,
              "typoDescent": 2.0
            }
        """.trimIndent()
        val full = parseRawFontMetricsJson(fullJson)
        assertEquals(18.5f, full.ascent)
        assertEquals(4.5f, full.descent)
        assertEquals(1.0f, full.leading)
        assertEquals(FontMetricSource.OpenTypeBase, full.source)
        assertEquals(16.0f, full.typoAscent)
        assertEquals(2.0f, full.typoDescent)

        val minimalJson = """
            {
              "ascent": 18.5,
              "descent": 4.5
            }
        """.trimIndent()
        val minimal = parseRawFontMetricsJson(minimalJson)
        assertEquals(18.5f, minimal.ascent)
        assertEquals(4.5f, minimal.descent)
        assertEquals(0f, minimal.leading)
        assertEquals(FontMetricSource.RawTables, minimal.source)
        assertNull(minimal.typoAscent)
        assertNull(minimal.typoDescent)

        val nullsJson = """
            {
              "ascent": null,
              "descent": null
            }
        """.trimIndent()
        val nullMetrics = parseRawFontMetricsJson(nullsJson)
        assertTrue(nullMetrics.ascent.isNaN())
        assertTrue(nullMetrics.descent.isNaN())
    }
}