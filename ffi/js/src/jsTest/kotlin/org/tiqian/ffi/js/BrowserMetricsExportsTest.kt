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
import org.tiqian.font.FontMetricsResolver
import org.tiqian.font.FontRole
import org.tiqian.font.RawFontMetrics
import org.tiqian.font.StubFontMetricsResolver
import org.tiqian.layout.ParagraphWireFace
import org.tiqian.shaping.ExplainableStubTextShaper
import org.tiqian.shaping.ShapingInput
import org.tiqian.shaping.ShapingResult
import org.tiqian.shaping.TextShaper

class BrowserMetricsExportsTest {

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

    @Test
    fun parityBetweenDirectBackendsAndJsCallbackBackends() {
        val stubShaper = ExplainableStubTextShaper()
        val stubResolver = StubFontMetricsResolver()

        val text = "中文测试 Latin 123"
        val maxWidthPx = 200.0
        val fontFamilies = "Noto Sans CJK"
        val fontSizePx = 16.0
        val lineHeightPx = 24.0
        val locale = "zh-Hans"
        val fontWeight = 400
        val italic = false
        val firstLineIndentIc = 0.0
        val lineLengthGridEnabled = true
        val zeroAdvanceEpsilonPx = 0.001

        val directPlan = ParagraphWireFace(
            textShaper = stubShaper,
            fontMetricsResolver = stubResolver,
        ).planWithDiagnostics(
            text = text,
            maxWidthPx = maxWidthPx,
            fontFamilies = fontFamilies,
            fontSizePx = fontSizePx,
            lineHeightPx = lineHeightPx,
            locale = locale,
            fontWeight = fontWeight,
            italic = italic,
            firstLineIndentIc = firstLineIndentIc,
            lineLengthGridEnabled = lineLengthGridEnabled,
            sourceBoundaries = "",
            textSpans = "",
            inlineBoxes = "",
            lineBreakSpans = "",
            inlineObjects = "",
            zeroAdvanceEpsilonPx = zeroAdvanceEpsilonPx,
        )

        val bridgeShaper = JsCallbackTextShaper { requestJson ->
            echoShape(requestJson, stubShaper)
        }
        val bridgeResolver = JsCallbackFontMetricsResolver { requestJson ->
            echoMetrics(requestJson, stubResolver)
        }

        val bridgePlan = ParagraphWireFace(
            textShaper = bridgeShaper,
            fontMetricsResolver = bridgeResolver,
        ).planWithDiagnostics(
            text = text,
            maxWidthPx = maxWidthPx,
            fontFamilies = fontFamilies,
            fontSizePx = fontSizePx,
            lineHeightPx = lineHeightPx,
            locale = locale,
            fontWeight = fontWeight,
            italic = italic,
            firstLineIndentIc = firstLineIndentIc,
            lineLengthGridEnabled = lineLengthGridEnabled,
            sourceBoundaries = "",
            textSpans = "",
            inlineBoxes = "",
            lineBreakSpans = "",
            inlineObjects = "",
            zeroAdvanceEpsilonPx = zeroAdvanceEpsilonPx,
        )

        assertEquals(directPlan, bridgePlan)
    }

    @Test
    fun endToEndExportWithBrowserMetricsAndDiagnostics() {
        val cannedShapeJson: (String) -> String = { requestJson ->
            val raw = kotlin.js.JSON.parse<dynamic>(requestJson)
            val start = (raw.range.start as Double).toInt()
            val end = (raw.range.end as Double).toInt()
            val text = raw.text as String
            val seg = text.substring(start, end)
            """
            {
              "clusters": [
                {
                  "range": { "start": $start, "end": $end },
                  "text": "$seg",
                  "displayText": "$seg",
                  "fontKey": "k",
                  "advance": 0.0,
                  "baselineShift": 0.0
                }
              ],
              "glyphRuns": [
                {
                  "range": { "start": $start, "end": $end },
                  "fontKey": "k",
                  "advance": 0.0,
                  "openTypeFeatures": [],
                  "glyphs": [
                    {
                      "id": 1,
                      "clusterRange": { "start": $start, "end": $end },
                      "advance": 0.0,
                      "x": 0.0,
                      "y": 0.0,
                      "bounds": null
                    }
                  ]
                }
              ],
              "decisions": [
                {
                  "range": { "start": $start, "end": $end },
                  "sourceText": "$seg",
                  "displayText": "$seg",
                  "fontKey": "k",
                  "glyphCount": 1,
                  "advance": 0.0,
                  "source": "stub",
                  "reason": "canned-reason",
                  "glyphsWithoutInkBounds": 1,
                  "missingGlyphs": 0,
                  "capabilityIssue": "TestIssue"
                }
              ]
            }
            """.trimIndent()
        }

        val cannedMetricsJson: (String) -> String = {
            """
            {
              "ascent": 16.0,
              "descent": 4.0,
              "leading": 0.0,
              "source": "RawTables",
              "typoAscent": 14.0,
              "typoDescent": 2.0
            }
            """.trimIndent()
        }

        val envelopeJson = precomputeParagraphWithBrowserMetrics(
            text = "中",
            maxWidthPx = 100.0,
            fontFamilies = "FixtureFont",
            fontSizePx = 16.0,
            lineHeightPx = 24.0,
            locale = "zh-Hans",
            fontWeight = 400,
            italic = false,
            firstLineIndentIc = 0.0,
            lineLengthGridEnabled = true,
            sourceBoundaries = "",
            textSpans = "",
            inlineBoxes = "",
            lineBreakSpans = "",
            inlineObjects = null,
            zeroAdvanceEpsilonPx = 0.01,
            shapeJson = cannedShapeJson,
            metricsJson = cannedMetricsJson,
        )

        assertContains(envelopeJson, "\"plan\":")
        assertContains(envelopeJson, "\\\"layoutRevision\\\":\\\"tiqian-layout-v2\\\"")
        assertContains(envelopeJson, "\"diagnostics\":")
        assertContains(envelopeJson, "\"name\":\"TestIssue\"")
        assertContains(envelopeJson, "\"reason\":\"canned-reason\"")
        assertContains(envelopeJson, "\"advance\":\"0\"")

        val parsedEnvelope = kotlin.js.JSON.parse<dynamic>(envelopeJson)
        val planJson = parsedEnvelope.plan as String
        assertContains(planJson, "\"layoutRevision\":\"tiqian-layout-v2\"")
        assertEquals("TestIssue", parsedEnvelope.diagnostics.capabilityIssues[0].name as String)
        assertEquals("canned-reason", parsedEnvelope.diagnostics.capabilityIssues[0].reason as String)
        assertEquals("0", parsedEnvelope.diagnostics.advanceSuspects[0].advance as String)
    }

    private fun echoShape(requestJson: String, shaper: TextShaper): String {
        val raw = kotlin.js.JSON.parse<dynamic>(requestJson)
        val text = raw.text as String
        val range = TextRange((raw.range.start as Double).toInt(), (raw.range.end as Double).toInt())
        val styleFamilies = (raw.style.fontFamilies as Array<dynamic>).map { it as String }
        val style = TextStyle(
            fontFamilies = styleFamilies,
            fontSize = (raw.style.fontSize as Double).toFloat(),
            locale = raw.style.locale as String,
            fontWeight = (raw.style.fontWeight as Double).toInt(),
            italic = raw.style.italic as Boolean,
        )
        val role = FontRole.valueOf(raw.fontDecision.role as String)
        val candidateKey = raw.fontDecision.candidateKey as String
        val fontDecision = FontDecision(
            range = range,
            candidate = FontCandidate(key = candidateKey, family = styleFamilies.firstOrNull() ?: "", role = role),
            role = role,
            reason = "echo",
        )
        val displayText = raw.displayText as String
        val openTypeFeatures = (raw.openTypeFeatures as Array<dynamic>).map { it as String }
        val input = ShapingInput(
            text = text,
            range = range,
            style = style,
            fontDecision = fontDecision,
            displayText = displayText,
            openTypeFeatures = openTypeFeatures,
        )
        val result = shaper.shape(input)
        return serializeShapingResultForTest(result)
    }

    private fun echoMetrics(requestJson: String, resolver: FontMetricsResolver): String {
        val raw = kotlin.js.JSON.parse<dynamic>(requestJson)
        val fontKey = raw.fontKey as String
        val fontSize = (raw.fontSize as Double).toFloat()
        val role = FontRole.valueOf(raw.role as String)
        val locale = raw.locale as String
        val fontFamilies = (raw.fontFamilies as Array<dynamic>).map { it as String }
        val fontWeight = (raw.fontWeight as Double).toInt()
        val italic = raw.italic as Boolean
        val faceSelectionText = raw.faceSelectionText as String
        val request = FontMetricsRequest(
            fontKey = fontKey,
            fontSize = fontSize,
            role = role,
            locale = locale,
            fontFamilies = fontFamilies,
            fontWeight = fontWeight,
            italic = italic,
            faceSelectionText = faceSelectionText,
        )
        val metrics = resolver.resolve(request)
        return serializeRawFontMetricsForTest(metrics)
    }

    private fun serializeShapingResultForTest(result: ShapingResult): String {
        val b = StringBuilder()
        b.append("{\"clusters\":[")
        result.clusters.forEachIndexed { i, c ->
            if (i > 0) b.append(',')
            b.append("{\"range\":{\"start\":").append(c.range.start).append(",\"end\":").append(c.range.end).append("}")
            b.append(",\"text\":\"").append(c.text).append('"')
            b.append(",\"displayText\":\"").append(c.displayText).append('"')
            b.append(",\"fontKey\":\"").append(c.fontKey).append('"')
            b.append(",\"advance\":").append(c.advance)
            b.append(",\"baselineShift\":").append(c.baselineShift)
            b.append("}")
        }
        b.append("],\"glyphRuns\":[")
        result.glyphRuns.forEachIndexed { i, r ->
            if (i > 0) b.append(',')
            b.append("{\"range\":{\"start\":").append(r.range.start).append(",\"end\":").append(r.range.end).append("}")
            b.append(",\"fontKey\":\"").append(r.fontKey).append('"')
            b.append(",\"advance\":").append(r.advance)
            b.append(",\"openTypeFeatures\":[")
            r.openTypeFeatures.forEachIndexed { fi, f ->
                if (fi > 0) b.append(',')
                b.append('"').append(f).append('"')
            }
            b.append("],\"glyphs\":[")
            r.glyphs.forEachIndexed { gi, g ->
                if (gi > 0) b.append(',')
                b.append("{\"id\":").append(g.id)
                b.append(",\"clusterRange\":{\"start\":").append(g.clusterRange.start).append(",\"end\":").append(g.clusterRange.end).append("}")
                b.append(",\"advance\":").append(g.advance)
                b.append(",\"x\":").append(g.x)
                b.append(",\"y\":").append(g.y)
                val bounds = g.bounds
                if (bounds != null) {
                    b.append(",\"bounds\":{\"left\":").append(bounds.left)
                    b.append(",\"top\":").append(bounds.top)
                    b.append(",\"right\":").append(bounds.right)
                    b.append(",\"bottom\":").append(bounds.bottom).append("}")
                } else {
                    b.append(",\"bounds\":null")
                }
                b.append("}")
            }
            b.append("]}")
        }
        b.append("],\"decisions\":[")
        result.decisions.forEachIndexed { i, d ->
            if (i > 0) b.append(',')
            b.append("{\"range\":{\"start\":").append(d.range.start).append(",\"end\":").append(d.range.end).append("}")
            b.append(",\"sourceText\":\"").append(d.sourceText).append('"')
            b.append(",\"displayText\":\"").append(d.displayText).append('"')
            b.append(",\"fontKey\":\"").append(d.fontKey).append('"')
            b.append(",\"glyphCount\":").append(d.glyphCount)
            b.append(",\"advance\":").append(d.advance)
            b.append(",\"source\":\"").append(d.source).append('"')
            b.append(",\"reason\":\"").append(d.reason).append('"')
            b.append(",\"glyphsWithoutInkBounds\":").append(d.glyphsWithoutInkBounds)
            b.append(",\"missingGlyphs\":").append(d.missingGlyphs)
            if (d.resolvedFace != null) b.append(",\"resolvedFace\":\"").append(d.resolvedFace).append('"')
            if (d.script != null) b.append(",\"script\":\"").append(d.script).append('"')
            if (d.language != null) b.append(",\"language\":\"").append(d.language).append('"')
            if (d.strategy != null) b.append(",\"strategy\":\"").append(d.strategy).append('"')
            if (d.featureEvidence != null) b.append(",\"featureEvidence\":\"").append(d.featureEvidence).append('"')
            if (d.capabilityIssue != null) b.append(",\"capabilityIssue\":\"").append(d.capabilityIssue).append('"')
            b.append("}")
        }
        b.append("]}")
        return b.toString()
    }

    private fun serializeRawFontMetricsForTest(metrics: RawFontMetrics): String {
        val b = StringBuilder()
        b.append("{\"ascent\":").append(metrics.ascent)
        b.append(",\"descent\":").append(metrics.descent)
        b.append(",\"leading\":").append(metrics.leading)
        b.append(",\"source\":\"").append(metrics.source.name).append('"')
        if (metrics.typoAscent != null) b.append(",\"typoAscent\":").append(metrics.typoAscent)
        if (metrics.typoDescent != null) b.append(",\"typoDescent\":").append(metrics.typoDescent)
        b.append("}")
        return b.toString()
    }
}
