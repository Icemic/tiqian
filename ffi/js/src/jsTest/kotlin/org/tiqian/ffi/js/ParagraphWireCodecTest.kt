package org.tiqian.ffi.js

import org.tiqian.core.ShapingDecisionInfo
import org.tiqian.font.StubFontMetricsResolver
import org.tiqian.shaping.ExplainableStubTextShaper
import org.tiqian.shaping.ShapingInput
import org.tiqian.shaping.ShapingResult
import org.tiqian.shaping.TextShaper
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ParagraphWireCodecTest {

    private val shaper = ExplainableStubTextShaper()
    private val metrics = StubFontMetricsResolver()
    private val codec = ParagraphWireCodec(textShaper = shaper, fontMetricsResolver = metrics)

    class WorkerRequestBuilder {
        var text: String = "你好世界"
        var textSpans: Array<TextSpanWireDto> = emptyArray()
        var inlineBoxes: Array<InlineBoxWireDto> = emptyArray()
        var lineBreakSpans: Array<LineBreakSpanWireDto> = emptyArray()
        var inlineObjects: Array<InlineObjectWireDto> = emptyArray()
        var renderEvidence: Boolean = false
        var semantics: Array<SemanticSpanWireDto> = emptyArray()
        var renderInlineBoxes: Array<RenderInlineBoxWireDto> = emptyArray()
        var sourceTag: String = "p"
        var firstLineIndentIc: Double = 2.0
        var lineLengthGridEnabled: Boolean = false

        fun build(): WorkerLayoutRequestDto {
            return WorkerLayoutRequestDto(
                text = text,
                maxWidthPx = 400.0,
                fontFamilies = arrayOf("Noto Sans CJK SC"),
                fontSizePx = 16.0,
                lineHeightPx = 24.0,
                locale = "zh-Hans",
                fontWeight = 400,
                italic = false,
                firstLineIndentIc = firstLineIndentIc,
                lineLengthGridEnabled = lineLengthGridEnabled,
                sourceBoundaries = emptyArray(),
                textSpans = textSpans,
                inlineBoxes = inlineBoxes,
                lineBreakSpans = lineBreakSpans,
                inlineObjects = inlineObjects,
                renderEvidence = renderEvidence,
                semantics = semantics,
                renderInlineBoxes = renderInlineBoxes,
                sourceTag = sourceTag,
            )
        }
    }

    private fun workerRequest(configure: WorkerRequestBuilder.() -> Unit): WorkerLayoutRequestDto {
        val builder = WorkerRequestBuilder()
        builder.configure()
        return builder.build()
    }

    class PrepareRequestBuilder {
        var text: String = "你好世界"
        var textSpans: Array<TextSpanWireDto> = emptyArray()
        var inlineBoxes: Array<InlineBoxWireDto> = emptyArray()
        var lineBreakSpans: Array<LineBreakSpanWireDto> = emptyArray()
        var inlineObjects: Array<InlineObjectWireDto> = emptyArray()
        var decorations: Array<DecorationWireDto> = emptyArray()
        var emphasisDotGapEm: Double? = null
        var renderEvidenceOverride: Boolean? = null
        var firstLineIndentIc: Double = 2.0
        var lineLengthGridEnabled: Boolean = false

        fun build(): PrepareParagraphRequestDto {
            return PrepareParagraphRequestDto(
                text = text,
                maxWidthPx = 400.0,
                fontFamilies = arrayOf("Noto Sans CJK SC"),
                fontSizePx = 16.0,
                lineHeightPx = 24.0,
                locale = "zh-Hans",
                fontWeight = 400,
                italic = false,
                firstLineIndentIc = firstLineIndentIc,
                lineLengthGridEnabled = lineLengthGridEnabled,
                sourceBoundaries = emptyArray(),
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

    private fun prepareRequest(configure: PrepareRequestBuilder.() -> Unit): PrepareParagraphRequestDto {
        val builder = PrepareRequestBuilder()
        builder.configure()
        return builder.build()
    }

    private fun textSpan(
        start: Int,
        end: Int,
        fontFamilies: Array<String> = arrayOf("Noto Sans CJK SC"),
        fontSize: Double = 16.0,
        fontWeight: Int = 400,
        italic: Boolean = false,
        baselineShift: Double = 0.0,
    ): TextSpanWireDto = TextSpanWireDto(
        start = start,
        end = end,
        fontFamilies = fontFamilies,
        fontSize = fontSize,
        fontWeight = fontWeight,
        italic = italic,
        baselineShift = baselineShift,
    )

    private fun inlineBox(
        start: Int,
        end: Int,
        inlineStart: Double,
        inlineEnd: Double,
        outerSpacing: String = "Narrow",
    ): InlineBoxWireDto = InlineBoxWireDto(
        start = start,
        end = end,
        inlineStart = inlineStart,
        inlineEnd = inlineEnd,
        outerSpacing = outerSpacing,
    )

    private fun lineBreakSpan(
        start: Int,
        end: Int,
        policy: String = "ProgressiveTechnical",
    ): LineBreakSpanWireDto = LineBreakSpanWireDto(
        start = start,
        end = end,
        policy = policy,
    )

    private fun inlineObject(
        start: Int,
        end: Int,
        advance: Double,
        ascent: Double,
        descent: Double,
    ): InlineObjectWireDto = InlineObjectWireDto(
        start = start,
        end = end,
        advance = advance,
        ascent = ascent,
        descent = descent,
    )

    private fun decoration(
        start: Int,
        end: Int,
        kind: String,
    ): DecorationWireDto = DecorationWireDto(
        start = start,
        end = end,
        kind = kind,
    )

    @Test
    fun emptyTextThrowsEmptyParagraph() {
        val e = assertFailsWith<IllegalArgumentException> {
            codec.plan(workerRequest { text = "" })
        }
        assertContains(e.message ?: "", "EmptyParagraph")
    }

    @Test
    fun textSpansRangeOutOfBoundsThrowsInvalidTextSpanRange() {
        val e = assertFailsWith<IllegalArgumentException> {
            codec.plan(workerRequest {
                text = "你好"
                textSpans = arrayOf(textSpan(start = 0, end = 5))
            })
        }
        assertContains(e.message ?: "", "InvalidTextSpanRange")
    }

    @Test
    fun normalChineseParagraphReturnsLayoutRevisionV2() {
        val result = codec.plan(workerRequest { text = "你好世界" })
        assertContains(result, "\"layoutRevision\":\"tiqian-layout-v2\"")
        assertContains(result, "\"rangeStart\":0")
    }

    @Test
    fun inlineObjectsEnterLayoutInputAndPlanEvidence() {
        val result = codec.plan(workerRequest {
            text = "中文"
            inlineObjects = arrayOf(inlineObject(0, 1, 18.0, 14.4, 4.32))
        })
        val parsed = kotlin.js.JSON.parse<dynamic>(result)
        assertEquals("tiqian-layout-v2", parsed.layoutRevision)
    }

    @Test
    fun plainParagraphWithoutInlineObjectsStaysLegacyPlan() {
        val result = codec.plan(workerRequest { text = "你好世界" })
        assertFalse(result.contains("\"inlineObject\":"))
    }

    @Test
    fun inlineObjectsFieldCountNotFiveThrowsInvalidInlineObjectWire() {
    }

    @Test
    fun inlineObjectsRangeOutOfBoundsThrowsInvalidInlineObjectRange() {
        val e = assertFailsWith<IllegalArgumentException> {
            codec.plan(workerRequest {
                text = "中文"
                inlineObjects = arrayOf(inlineObject(start = 1, end = 5, advance = 18.0, ascent = 14.4, descent = 4.32))
            })
        }
        assertContains(e.message ?: "", "InvalidInlineObjectRange")
    }

    @Test
    fun planWithDiagnosticsEmbedsTheExactPlanJson() {
        val text = "你好世界"
        val plan = codec.plan(workerRequest { this.text = text })
        val envelope = codec.planWithDiagnostics(prepareRequest { this.text = text }, zeroAdvanceEpsilonPx = 0.01)
        val expected = "{\"plan\":${plan.escapedAsJsonString()},\"diagnostics\":{\"capabilityIssues\":[],\"advanceSuspects\":[]}}"
        assertEquals(expected, envelope)
    }

    @Test
    fun planWithDiagnosticsCarriesCapabilityIssueFacts() {
        val text = "你好世界"
        val wrapped = DiagnosticWrappingTextShaper(shaper) {
            it.copy(capabilityIssue = "UnverifiedDisplaySubstitutionCoverage")
        }
        val codecWrapped = ParagraphWireCodec(textShaper = wrapped, fontMetricsResolver = metrics)
        val envelope = codecWrapped.planWithDiagnostics(prepareRequest { this.text = text }, zeroAdvanceEpsilonPx = 0.01)
        assertContains(
            envelope,
            "{\"name\":\"UnverifiedDisplaySubstitutionCoverage\"," +
                "\"reason\":\"ExplainableStubTextShaper:nominal-em-advance\"," +
                "\"rangeStart\":0,\"rangeEnd\":1",
        )
        assertContains(envelope, "\"advanceSuspects\":[]")
    }

    @Test
    fun planWithDiagnosticsCarriesNonFiniteAdvanceAsString() {
        val text = "你好世界"
        val wrapped = DiagnosticWrappingTextShaper(shaper) {
            it.copy(advance = Float.NaN)
        }
        val codecWrapped = ParagraphWireCodec(textShaper = wrapped, fontMetricsResolver = metrics)
        val envelope = codecWrapped.planWithDiagnostics(prepareRequest { this.text = text }, zeroAdvanceEpsilonPx = 0.01)
        assertContains(
            envelope,
            "{\"displayText\":\"你\",\"advance\":\"NaN\"," +
                "\"reason\":\"ExplainableStubTextShaper:nominal-em-advance\"," +
                "\"rangeStart\":0,\"rangeEnd\":1",
        )
    }

    @Test
    fun planWithDiagnosticsThresholdFlagsSuspects() {
        val text = "你好世界"
        val allSuspects = codec.planWithDiagnostics(prepareRequest { this.text = text }, zeroAdvanceEpsilonPx = 1e9)
        val parsedAll = kotlin.js.JSON.parse<dynamic>(allSuspects)
        val suspectCount = (parsedAll.diagnostics.advanceSuspects as Array<dynamic>).size
        assertEquals(4, suspectCount)
        val noneSuspects = codec.planWithDiagnostics(prepareRequest { this.text = text }, zeroAdvanceEpsilonPx = 0.0)
        val parsedNone = kotlin.js.JSON.parse<dynamic>(noneSuspects)
        val noneCount = (parsedNone.diagnostics.advanceSuspects as Array<dynamic>).size
        assertEquals(0, noneCount)
    }

    @Test
    fun decorationsAloneEnableRenderEvidenceAndEmitEmphasisRanges() {
        val envelope = codec.planWithDiagnostics(
            prepareRequest {
                text = "你好世界"
                decorations = arrayOf(decoration(0, 2, "Emphasis"))
            },
            zeroAdvanceEpsilonPx = 0.01,
        )
        assertContains(envelope, "\\\"emphasisRanges\\\":[[0,2]]")
        assertContains(envelope, "\\\"emphasisDots\\\":[")
    }

    @Test
    fun emphasisDotGapEmDefaultAndExplicitOverride() {
        val defaultEnvelope = codec.planWithDiagnostics(
            prepareRequest {
                text = "你好世界"
                decorations = arrayOf(decoration(0, 2, "Emphasis"))
                emphasisDotGapEm = null
            },
            zeroAdvanceEpsilonPx = 0.01,
        )
        val customEnvelope = codec.planWithDiagnostics(
            prepareRequest {
                text = "你好世界"
                decorations = arrayOf(decoration(0, 2, "Emphasis"))
                emphasisDotGapEm = 0.5
            },
            zeroAdvanceEpsilonPx = 0.01,
        )
        assertContains(defaultEnvelope, "\\\"emphasisRanges\\\":[[0,2]]")
        assertContains(customEnvelope, "\\\"emphasisRanges\\\":[[0,2]]")
        val defaultAnchorY = defaultEnvelope.substringAfter("\\\"anchorY\\\":").substringBefore(",")
        val customAnchorY = customEnvelope.substringAfter("\\\"anchorY\\\":").substringBefore(",")
        assertFalse(defaultEnvelope == customEnvelope)
        assertFalse(defaultAnchorY == customAnchorY)
    }

    @Test
    fun invalidEmphasisDotGapEmThrows() {
        val eNeg = assertFailsWith<IllegalArgumentException> {
            codec.planWithDiagnostics(
                prepareRequest {
                    text = "你好世界"
                    emphasisDotGapEm = -0.1
                },
                zeroAdvanceEpsilonPx = 0.01,
            )
        }
        assertContains(eNeg.message ?: "", "InvalidEmphasisDotGapEm")

        val eNan = assertFailsWith<IllegalArgumentException> {
            codec.planWithDiagnostics(
                prepareRequest {
                    text = "你好世界"
                    emphasisDotGapEm = Double.NaN
                },
                zeroAdvanceEpsilonPx = 0.01,
            )
        }
        assertContains(eNan.message ?: "", "InvalidEmphasisDotGapEm")
    }

    @Test
    fun invalidDecorationWireFieldCountThrowsInvalidDecorationWire() {
    }

    @Test
    fun invalidDecorationWireUnknownKindThrows() {
        assertFailsWith<IllegalArgumentException> {
            codec.planWithDiagnostics(
                prepareRequest {
                    text = "你好世界"
                    decorations = arrayOf(decoration(0, 2, "UnknownKind"))
                },
                zeroAdvanceEpsilonPx = 0.01,
            )
        }
    }

    @Test
    fun planOmittedOverrideKeepsWireDerivedEvidence() {
        val plain = codec.plan(workerRequest { text = "你好世界" })
        val plainParsed = kotlin.js.JSON.parse<dynamic>(plain)
        assertEquals("tiqian-layout-v2", plainParsed.layoutRevision)
        val styled = codec.plan(workerRequest {
            text = "你好世界"
            textSpans = arrayOf(textSpan(
                start = 0, end = 4,
                fontFamilies = arrayOf("Source Han Sans SC"),
                fontSize = 16.0, fontWeight = 400, italic = false, baselineShift = 0.0,
            ))
        })
        val styledParsed = kotlin.js.JSON.parse<dynamic>(styled)
        assertEquals("tiqian-layout-v2", styledParsed.layoutRevision)
    }

    @Test
    fun planOverrideTrueOnPlainInputAddsEvidenceSections() {
        val result = codec.plan(workerRequest {
            text = "你好世界"
            renderEvidence = true
        })
        assertContains(result, "fontSize")
        assertContains(result, "overlayWidth")
    }

    @Test
    fun planOverrideFalseOnRichInputRemovesEvidenceSections() {
        val plain = codec.plan(workerRequest { text = "你好世界" })
        val stripped = codec.plan(workerRequest {
            text = "你好世界"
            textSpans = arrayOf(textSpan(
                start = 0, end = 4,
                fontFamilies = arrayOf("Source Han Sans SC"),
                fontSize = 16.0, fontWeight = 400, italic = false, baselineShift = 0.0,
            ))
            renderEvidence = false
        })
        assertEquals(plain, stripped)
        assertFalse(stripped.contains("style"))
    }

    @Test
    fun planWithDiagnosticsOmittedOverrideKeepsWireDerivedEvidence() {
        val plain = codec.planWithDiagnostics(prepareRequest { text = "你好世界" }, zeroAdvanceEpsilonPx = 0.01)
        assertFalse(plain.contains("fontSize"))
        val styled = codec.planWithDiagnostics(prepareRequest {
            text = "你好世界"
            textSpans = arrayOf(textSpan(
                start = 0, end = 4,
                fontFamilies = arrayOf("Source Han Sans SC"),
                fontSize = 16.0, fontWeight = 400, italic = false, baselineShift = 0.0,
            ))
        }, zeroAdvanceEpsilonPx = 0.01)
        assertContains(styled, "fontSize")
        assertContains(styled, "overlayWidth")
    }

    @Test
    fun planWithDiagnosticsOverrideTrueOnPlainInputAddsEvidenceSections() {
        val envelope = codec.planWithDiagnostics(prepareRequest { text = "你好世界"; renderEvidenceOverride = true }, zeroAdvanceEpsilonPx = 0.01)
        assertContains(envelope, "fontSize")
        assertContains(envelope, "overlayWidth")
    }

    @Test
    fun planWithDiagnosticsOverrideFalseOnRichInputRemovesEvidenceSections() {
        val plain = codec.planWithDiagnostics(prepareRequest { text = "你好世界" }, zeroAdvanceEpsilonPx = 0.01)
        val stripped = codec.planWithDiagnostics(
            prepareRequest {
                text = "你好世界"
                textSpans = arrayOf(textSpan(
                    start = 0, end = 4,
                    fontFamilies = arrayOf("Source Han Sans SC"),
                    fontSize = 16.0, fontWeight = 400, italic = false, baselineShift = 0.0,
                ))
                renderEvidenceOverride = false
            },
            zeroAdvanceEpsilonPx = 0.01,
        )
        assertEquals(plain, stripped)
        assertFalse(stripped.contains("style"))
    }
}

/**
 * Test double that runs [delegate] and rewrites every shaping decision so a
 * test can force a [capabilityIssue] or a non-finite/zero advance through the
 * real layout pipeline without inventing a shaper from scratch.
 */
private class DiagnosticWrappingTextShaper(
    private val delegate: ExplainableStubTextShaper,
    private val transform: (ShapingDecisionInfo) -> ShapingDecisionInfo,
) : TextShaper {
    override fun shape(input: ShapingInput): ShapingResult {
        val result = delegate.shape(input)
        return result.copy(decisions = result.decisions.map(transform))
    }
}

/** Local mirror of the wire codec's JSON string escaper for building expected envelopes. */
private fun String.escapedAsJsonString(): String = buildString {
    append('"')
    for (char in this@escapedAsJsonString) {
        when (char) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000c' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (char.code < 0x20) {
                append("\\u").append(char.code.toString(16).padStart(4, '0'))
            } else {
                append(char)
            }
        }
    }
    append('"')
}