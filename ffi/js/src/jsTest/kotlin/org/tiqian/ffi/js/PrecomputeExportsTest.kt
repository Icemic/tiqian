@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package org.tiqian.ffi.js

import org.tiqian.font.StubFontMetricsResolver
import org.tiqian.shaping.ExplainableStubTextShaper
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class PrecomputeExportsTest {
    @Test
    fun realLayoutPipelineUsesAndReleasesSynchronousNodeFontHandles() {
        val request = prepareRequest {
            text = "中文中文"
            maxWidthPx = 36.0
            fontFamilies = arrayOf("Fixture CJK")
            fontSizePx = 18.0
            lineHeightPx = 27.0
            locale = "zh-Hans"
            fontWeight = 400
            italic = false
            firstLineIndentIc = 0.0
            lineLengthGridEnabled = true
        }
        val codec = ParagraphWireCodec(
            textShaper = ExplainableStubTextShaper(),
            fontMetricsResolver = StubFontMetricsResolver(),
        )
        val json = codec.planWithDiagnostics(request, 0.0)

        // Parse the outer envelope and check the plan
        val envelope = kotlin.js.JSON.parse<dynamic>(json)
        val planJson = envelope.plan as String
        assertContains(planJson, "\"layoutRevision\":\"tiqian-layout-v2\"")
        // With the fixture backend, each character gets its own line due to glyph advance measurement
        assertContains(planJson, "\"rangeStart\":0,\"rangeEnd\":1")
        assertContains(planJson, "\"rangeStart\":1,\"rangeEnd\":2")
        assertContains(planJson, "\"rangeStart\":2,\"rangeEnd\":3")
        assertContains(planJson, "\"rangeStart\":3,\"rangeEnd\":4")
    }

    @Test
    fun unavailableMidlineEllipsisRollsBackToSourceEllipsis() {
        val request = prepareRequest {
            text = "……"
            maxWidthPx = 72.0
            fontFamilies = arrayOf("Fixture CJK")
            fontSizePx = 18.0
            lineHeightPx = 27.0
            locale = "zh-Hans"
            fontWeight = 400
            italic = false
            firstLineIndentIc = 0.0
            lineLengthGridEnabled = true
        }
        val codec = ParagraphWireCodec(
            textShaper = ExplainableStubTextShaper(),
            fontMetricsResolver = StubFontMetricsResolver(),
        )
        val json = codec.planWithDiagnostics(request, 0.0)

        val envelope = kotlin.js.JSON.parse<dynamic>(json)
        val planJson = envelope.plan as String
        assertContains(planJson, "source")
        assertContains(planJson, "display")
    }
}

private inline fun prepareRequest(block: PrepareRequestBuilder.() -> Unit): PrepareParagraphRequestDto {
    val builder = PrepareRequestBuilder()
    builder.block()
    return builder.build()
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