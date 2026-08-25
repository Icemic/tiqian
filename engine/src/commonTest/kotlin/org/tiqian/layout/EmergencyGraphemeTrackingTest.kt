package org.tiqian.layout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.tiqian.core.Cluster
import org.tiqian.core.Ic
import org.tiqian.core.InlineObjectSpan
import org.tiqian.core.LayoutConstraints
import org.tiqian.core.LayoutInput
import org.tiqian.core.LineBreakPolicy
import org.tiqian.core.LineBreakSpan
import org.tiqian.core.LineEndReason
import org.tiqian.core.LineLengthGrid
import org.tiqian.core.ParagraphStyle
import org.tiqian.core.TextRange
import org.tiqian.core.TiqianTextContent
import org.tiqian.linebreak.EnglishHyphenation
import org.tiqian.shaping.ShapingInput
import org.tiqian.shaping.ShapingResult
import org.tiqian.shaping.TextShaper

class EmergencyGraphemeTrackingTest {
    private val noIndent = ParagraphStyle(
        firstLineIndent = Ic(0f),
        lineLengthGrid = LineLengthGrid(enabled = false),
    )

    @Test
    fun rejectedLetterDigitStructuralOffsetsRemainAvailableAsEmergencyCuts() {
        val text = "Machine2Machine"
        val result = TiqianParagraphLayoutEngine(
            hyphenator = EnglishHyphenation.enUs,
        ).layout(
            LayoutInput(
                paragraphStyle = noIndent,
                content = TiqianTextContent(
                    text = text,
                    lineBreakSpans = listOf(
                        LineBreakSpan(TextRange(0, text.length), LineBreakPolicy.ProgressiveTechnical),
                    ),
                ),
                constraints = LayoutConstraints(maxWidth = 120f),
            ),
        )

        val emergency = result.debug.breakOpportunityDecisions
            .filter { it.tier == "Emergency" }
            .flatMap { it.breakOffsets }
        assertTrue(7 in emergency, emergency.toString())
        assertTrue(8 in emergency, emergency.toString())
        assertTrue(result.lines.all { it.hyphenAdvance == 0f })
    }

    @Test
    fun technicalIdentifierRelabelsLooseLetterDigitBoundaryAsEmergency() {
        val text = "Machine2Machine"
        val uniformAdvanceShaper = object : TextShaper {
            override fun shape(input: ShapingInput): ShapingResult {
                val source = input.text.substring(input.range.start, input.range.end)
                return ShapingResult(
                    clusters = listOf(
                        Cluster(
                            range = input.range,
                            text = source,
                            displayText = input.displayText,
                            fontKey = input.fontDecision.candidate.key,
                            advance = source.length * 10f,
                        ),
                    ),
                    glyphRuns = emptyList(),
                )
            }
        }
        val result = TiqianParagraphLayoutEngine(
            textShaper = uniformAdvanceShaper,
            hyphenator = EnglishHyphenation.enUs,
        ).layout(
            LayoutInput(
                paragraphStyle = noIndent,
                content = TiqianTextContent(
                    text = text,
                    lineBreakSpans = listOf(
                        LineBreakSpan(TextRange(0, text.length), LineBreakPolicy.ProgressiveTechnical),
                    ),
                ),
                constraints = LayoutConstraints(maxWidth = 85f),
            ),
        )

        assertEquals(TextRange(0, 8), result.lines.first().range)
        assertTrue(result.debug.lineDecisions.first().notes.contains("technical-break:Emergency"))
        assertEquals(0f, result.lines.first().hyphenAdvance)
    }

    @Test
    fun hashPieceInsideTechnicalUrlSkipsSyllableClassification() {
        val hash = "deadbeefcafebabefeedfaceabcdefabcdef"
        val text = "https://example.com/commit/$hash"
        val hashStart = text.indexOf(hash)
        val result = TiqianParagraphLayoutEngine(
            hyphenator = EnglishHyphenation.enUs,
        ).layout(
            LayoutInput(
                paragraphStyle = noIndent,
                content = TiqianTextContent(
                    text = text,
                    lineBreakSpans = listOf(
                        LineBreakSpan(TextRange(0, text.length), LineBreakPolicy.ProgressiveTechnical),
                    ),
                ),
                constraints = LayoutConstraints(maxWidth = 192f),
            ),
        )

        val syllableOffsets = result.debug.breakOpportunityDecisions
            .filter { it.tier == "Syllable" }
            .flatMap { it.breakOffsets }
        assertTrue(syllableOffsets.none { it > hashStart && it < text.length }, syllableOffsets.toString())
        assertTrue(result.lines.all { it.hyphenAdvance == 0f })
    }

    @Test
    fun standaloneTechnicalHashUsesTrackingToFillEveryAutoWrappedLine() {
        val text = "deadbeefcafebabefeedfaceabcdefabcdef"
        val result = TiqianParagraphLayoutEngine(
            hyphenator = EnglishHyphenation.enUs,
        ).layout(
            LayoutInput(
                paragraphStyle = noIndent,
                content = TiqianTextContent(
                    text = text,
                    lineBreakSpans = listOf(
                        LineBreakSpan(TextRange(0, text.length), LineBreakPolicy.ProgressiveTechnical),
                    ),
                ),
                constraints = LayoutConstraints(maxWidth = 101f),
            ),
        )

        val autoLines = result.lines.filter { it.endReason == LineEndReason.AutoWrap }
        assertTrue(autoLines.isNotEmpty())
        autoLines.forEach { line -> assertEquals(101f, line.visualWidth, 0.001f) }
        assertTrue(
            result.debug.justificationDecisions.flatMap { it.allocations }.any {
                it.kind == "EmergencyGraphemeTracking" &&
                    it.reason == "TerminalTechnicalEmergencyTracking:ProgressiveTechnicalSpan"
            },
        )
    }

    @Test
    fun repeatedPlainTokenGetsNarrowNonLexicalAuthorization() {
        val text = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        val result = TiqianParagraphLayoutEngine(
            hyphenator = EnglishHyphenation.enUs,
        ).layout(
            LayoutInput(
                paragraphStyle = noIndent,
                content = TiqianTextContent(text),
                constraints = LayoutConstraints(maxWidth = 101f),
            ),
        )

        assertTrue(
            result.debug.emergencyTrackingEligibilityDecisions.any {
                it.range == TextRange(0, text.length) && it.reason == "LongRepeatedLetterRun"
            },
        )
        result.lines.filter { it.endReason == LineEndReason.AutoWrap }.forEach { line ->
            assertEquals(101f, line.visualWidth, 0.001f)
        }
    }

    @Test
    fun longAllCapsWesternWordDoesNotBecomeTrackingEligible() {
        val text = "SUPERCALIFRAGILISTICEXPIALIDOCIOUS"
        val result = TiqianParagraphLayoutEngine(
            hyphenator = EnglishHyphenation.enUs,
        ).layout(
            LayoutInput(
                paragraphStyle = noIndent,
                content = TiqianTextContent(text),
                constraints = LayoutConstraints(maxWidth = 101f),
            ),
        )

        assertTrue(result.debug.emergencyTrackingEligibilityDecisions.isEmpty())
        assertFalse(
            result.debug.justificationDecisions.flatMap { it.allocations }.any {
                it.kind == "EmergencyGraphemeTracking"
            },
        )
    }

    @Test
    fun plainOpaqueHardBreakKeepsCombiningGraphemeIntact() {
        val text = "abc123e\u0301def456ghi"
        val combiningMarkOffset = text.indexOf('\u0301')
        val result = TiqianParagraphLayoutEngine(
            hyphenator = EnglishHyphenation.enUs,
        ).layout(
            LayoutInput(
                paragraphStyle = noIndent,
                content = TiqianTextContent(text),
                constraints = LayoutConstraints(maxWidth = 64f),
            ),
        )

        assertTrue(result.clusters.size > 1, result.clusters.toString())
        assertTrue(
            result.clusters.none {
                it.range.start == combiningMarkOffset || it.range.end == combiningMarkOffset
            },
            result.clusters.joinToString { it.range.toString() },
        )
    }

    @Test
    fun technicalTrackingDoesNotOpenEdgesTouchingInlineObjectsOrZeroWidthControls() {
        val objectText = "aaaaaaaaaaaa\uFFFCbbbbbbbbbbbb"
        val objectRange = TextRange(12, 13)
        val objectResult = TiqianParagraphLayoutEngine().layout(
            LayoutInput(
                paragraphStyle = noIndent,
                content = TiqianTextContent(
                    text = objectText,
                    lineBreakSpans = listOf(
                        LineBreakSpan(
                            TextRange(0, objectText.length),
                            LineBreakPolicy.ProgressiveTechnical,
                        ),
                    ),
                ),
                constraints = LayoutConstraints(maxWidth = 300f),
                inlineObjects = listOf(
                    InlineObjectSpan(
                        range = objectRange,
                        advance = 16f,
                        ascent = 12f,
                        descent = 4f,
                    ),
                ),
            ),
        )
        val objectAllocations = objectResult.debug.justificationDecisions
            .flatMap { it.allocations }
            .filter { it.kind == "EmergencyGraphemeTracking" }
        assertTrue(objectAllocations.isNotEmpty())
        assertTrue(
            objectAllocations.none {
                it.clusterRange.end == objectRange.start || it.clusterRange == objectRange
            },
            objectAllocations.toString(),
        )

        val zeroWidthText = "aaaaaaaaaaaa\u200Bbbbbbbbbbbbb"
        val zeroWidthRange = TextRange(12, 13)
        val zeroWidthResult = TiqianParagraphLayoutEngine().layout(
            LayoutInput(
                paragraphStyle = noIndent,
                content = TiqianTextContent(
                    text = zeroWidthText,
                    lineBreakSpans = listOf(
                        LineBreakSpan(
                            TextRange(0, zeroWidthText.length),
                            LineBreakPolicy.ProgressiveTechnical,
                        ),
                    ),
                ),
                constraints = LayoutConstraints(maxWidth = 300f),
            ),
        )
        val zeroWidthAllocations = zeroWidthResult.debug.justificationDecisions
            .flatMap { it.allocations }
            .filter { it.kind == "EmergencyGraphemeTracking" }
        assertTrue(zeroWidthAllocations.isNotEmpty())
        assertTrue(
            zeroWidthAllocations.none {
                it.clusterRange.end == zeroWidthRange.start || it.clusterRange == zeroWidthRange
            },
            zeroWidthAllocations.toString(),
        )
    }

    @Test
    fun unannotatedUrlDoesNotAuthorizeTrackingAcrossOrdinaryPathComponents() {
        val identity = "abc123def456ghi789"
        val text = "https://example.com/path/to/$identity"
        val identityStart = text.indexOf(identity)
        val result = TiqianParagraphLayoutEngine(
            hyphenator = EnglishHyphenation.enUs,
        ).layout(
            LayoutInput(
                paragraphStyle = noIndent,
                content = TiqianTextContent(text),
                constraints = LayoutConstraints(maxWidth = 160f),
            ),
        )

        assertEquals(
            listOf(TextRange(identityStart, text.length)),
            result.debug.emergencyTrackingEligibilityDecisions.map { it.range },
        )
        assertTrue(
            result.debug.justificationDecisions.flatMap { it.allocations }
                .filter { it.kind == "EmergencyGraphemeTracking" }
                .all { it.clusterRange.start >= identityStart },
        )
    }

    @Test
    fun ordinaryWesternProseIsNeverInferredAsTrackingEligible() {
        val text = "ordinary Western paragraphs keep their natural word spacing"
        val result = TiqianParagraphLayoutEngine(
            hyphenator = EnglishHyphenation.enUs,
        ).layout(
            LayoutInput(
                paragraphStyle = noIndent,
                content = TiqianTextContent(text),
                constraints = LayoutConstraints(maxWidth = 137f),
            ),
        )

        assertTrue(result.debug.emergencyTrackingEligibilityDecisions.isEmpty())
        assertFalse(
            result.debug.justificationDecisions.flatMap { it.allocations }.any {
                it.kind == "EmergencyGraphemeTracking"
            },
        )
        assertTrue(
            result.debug.justificationDecisions.any {
                it.deficitAfter > 0f
            },
            "ordinary Western lines may remain ragged after bounded word-space adjustment",
        )
    }
}
