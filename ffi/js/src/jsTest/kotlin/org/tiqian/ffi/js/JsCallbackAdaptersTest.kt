package org.tiqian.ffi.js

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
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
import org.tiqian.shaping.ExplainableStubTextShaper
import org.tiqian.shaping.ShapingInput
import org.tiqian.shaping.ShapingResult
import org.tiqian.shaping.TextShaper

class JsCallbackAdaptersTest {

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

        val directRequest = prepareRequest { builder ->
            builder.text = text
            builder.maxWidthPx = maxWidthPx
            builder.fontFamilies = arrayOf(fontFamilies)
            builder.fontSizePx = fontSizePx
            builder.lineHeightPx = lineHeightPx
            builder.locale = locale
            builder.fontWeight = fontWeight
            builder.italic = italic
            builder.firstLineIndentIc = firstLineIndentIc
            builder.lineLengthGridEnabled = lineLengthGridEnabled
        }
        val directPlan = ParagraphWireCodec(
            textShaper = stubShaper,
            fontMetricsResolver = stubResolver,
        ).planWithDiagnostics(directRequest, zeroAdvanceEpsilonPx)

        val bridgeShaper = JsCallbackTextShaper { requestJson ->
            echoShape(requestJson, stubShaper)
        }
        val bridgeResolver = JsCallbackFontMetricsResolver { requestJson ->
            echoMetrics(requestJson, stubResolver)
        }

        val bridgeRequest = prepareRequest { builder ->
            builder.text = text
            builder.maxWidthPx = maxWidthPx
            builder.fontFamilies = arrayOf(fontFamilies)
            builder.fontSizePx = fontSizePx
            builder.lineHeightPx = lineHeightPx
            builder.locale = locale
            builder.fontWeight = fontWeight
            builder.italic = italic
            builder.firstLineIndentIc = firstLineIndentIc
            builder.lineLengthGridEnabled = lineLengthGridEnabled
        }
        val bridgePlan = ParagraphWireCodec(
            textShaper = bridgeShaper,
            fontMetricsResolver = bridgeResolver,
        ).planWithDiagnostics(bridgeRequest, zeroAdvanceEpsilonPx)

        assertEquals(directPlan, bridgePlan)
    }

    @Test
    fun endToEndWithDiagnostics() {
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

        val request = prepareRequest { builder ->
            builder.text = "中"
            builder.maxWidthPx = 100.0
            builder.fontFamilies = arrayOf("FixtureFont")
            builder.fontSizePx = 16.0
            builder.lineHeightPx = 24.0
            builder.locale = "zh-Hans"
            builder.fontWeight = 400
            builder.italic = false
            builder.firstLineIndentIc = 0.0
            builder.lineLengthGridEnabled = true
        }
        val codec = ParagraphWireCodec(
            textShaper = JsCallbackTextShaper(cannedShapeJson),
            fontMetricsResolver = JsCallbackFontMetricsResolver(cannedMetricsJson),
        )
        val envelopeJson = codec.planWithDiagnostics(request, 0.01)

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

    @Test
    fun browserMetricsWithDecorationsAndGapCarriesEmphasisRanges() {
        val stubShaper = ExplainableStubTextShaper()
        val stubResolver = StubFontMetricsResolver()

        val bridgeShaper: (String) -> String = { requestJson ->
            echoShape(requestJson, stubShaper)
        }
        val bridgeResolver: (String) -> String = { requestJson ->
            echoMetrics(requestJson, stubResolver)
        }

        val request = prepareRequest { builder ->
            builder.text = "中文测试"
            builder.maxWidthPx = 200.0
            builder.fontFamilies = arrayOf("Noto Sans CJK")
            builder.fontSizePx = 16.0
            builder.lineHeightPx = 24.0
            builder.locale = "zh-Hans"
            builder.fontWeight = 400
            builder.italic = false
            builder.firstLineIndentIc = 0.0
            builder.lineLengthGridEnabled = true
            builder.decorations = arrayOf(decoration(0, 2, "Emphasis"))
            builder.emphasisDotGapEm = 0.2
        }

        val codec = ParagraphWireCodec(
            textShaper = JsCallbackTextShaper(bridgeShaper),
            fontMetricsResolver = JsCallbackFontMetricsResolver(bridgeResolver),
        )
        val envelopeJson = codec.planWithDiagnostics(request, 0.001)

        assertContains(envelopeJson, "\"plan\":")
        val parsedEnvelope = kotlin.js.JSON.parse<dynamic>(envelopeJson)
        val planJson = parsedEnvelope.plan as String
        assertContains(planJson, "\"emphasisRanges\":[[0,2]]")
    }

    @Test
    fun renderEvidenceOverrideReachesLayoutCall() {
        val stubShaper = ExplainableStubTextShaper()
        val stubResolver = StubFontMetricsResolver()

        val bridgeShaper: (String) -> String = { requestJson ->
            echoShape(requestJson, stubShaper)
        }
        val bridgeResolver: (String) -> String = { requestJson ->
            echoMetrics(requestJson, stubResolver)
        }

        val request = prepareRequest { builder ->
            builder.text = "中文测试"
            builder.maxWidthPx = 200.0
            builder.fontFamilies = arrayOf("Noto Sans CJK")
            builder.fontSizePx = 16.0
            builder.lineHeightPx = 24.0
            builder.locale = "zh-Hans"
            builder.fontWeight = 400
            builder.italic = false
            builder.firstLineIndentIc = 0.0
            builder.lineLengthGridEnabled = true
            builder.renderEvidenceOverride = true
        }

        val codec = ParagraphWireCodec(
            textShaper = JsCallbackTextShaper(bridgeShaper),
            fontMetricsResolver = JsCallbackFontMetricsResolver(bridgeResolver),
        )
        val envelopeJson = codec.planWithDiagnostics(request, 0.001)

        val parsedEnvelope = kotlin.js.JSON.parse<dynamic>(envelopeJson)
        val planJson = parsedEnvelope.plan as String
        assertContains(planJson, "\"fontSize\":16")
        assertContains(planJson, "\"overlayWidth\"")
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

    private fun prepareRequest(block: (PrepareRequestBuilder) -> Unit): PrepareParagraphRequestDto {
        val builder = PrepareRequestBuilder()
        block(builder)
        return builder.build()
    }

    private fun toExportRequest(dto: PrepareParagraphRequestDto): PrepareParagraphRequest {
        val obj = js("{}")
        obj.text = dto.text
        obj.maxWidthPx = dto.maxWidthPx
        obj.fontFamilies = dto.fontFamilies
        obj.fontSizePx = dto.fontSizePx
        obj.lineHeightPx = dto.lineHeightPx
        obj.locale = dto.locale
        obj.fontWeight = dto.fontWeight
        obj.italic = dto.italic
        obj.firstLineIndentIc = dto.firstLineIndentIc
        obj.lineLengthGridEnabled = dto.lineLengthGridEnabled
        obj.sourceBoundaries = dto.sourceBoundaries
        obj.textSpans = dto.textSpans
        obj.inlineBoxes = dto.inlineBoxes
        obj.lineBreakSpans = dto.lineBreakSpans
        obj.inlineObjects = dto.inlineObjects
        obj.decorations = dto.decorations
        obj.emphasisDotGapEm = dto.emphasisDotGapEm
        obj.renderEvidenceOverride = dto.renderEvidenceOverride
        return obj as PrepareParagraphRequest
    }

    class PrepareRequestBuilder {
        var text: String = ""
        var maxWidthPx: Double = 0.0
        var fontFamilies: Array<String> = emptyArray()
        var fontSizePx: Double = 0.0
        var lineHeightPx: Double = 0.0
        var locale: String = ""
        var fontWeight: Int = 0
        var italic: Boolean = false
        var firstLineIndentIc: Double = 0.0
        var lineLengthGridEnabled: Boolean = false
        var sourceBoundaries: Array<Int> = emptyArray()
        var textSpans: Array<TextSpanWireDto> = emptyArray()
        var inlineBoxes: Array<InlineBoxWireDto> = emptyArray()
        var lineBreakSpans: Array<LineBreakSpanWireDto> = emptyArray()
        var inlineObjects: Array<InlineObjectWireDto> = emptyArray()
        var decorations: Array<DecorationWireDto> = emptyArray()
        var emphasisDotGapEm: Double? = null
        var renderEvidenceOverride: Boolean? = null

        fun build(): PrepareParagraphRequestDto {
            return PrepareParagraphRequestDto(
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
                sourceBoundaries = sourceBoundaries,
                textSpans = textSpans,
                inlineBoxes = inlineBoxes,
                lineBreakSpans = lineBreakSpans,
                inlineObjects = inlineObjects,
                decorations = decorations,
                emphasisDotGapEm = emphasisDotGapEm,
                renderEvidenceOverride = renderEvidenceOverride,
            )
        }
    }

    private fun decoration(
        start: Int,
        end: Int,
        kind: String,
    ): DecorationWireDto {
        return DecorationWireDto(start = start, end = end, kind = kind)
    }
}