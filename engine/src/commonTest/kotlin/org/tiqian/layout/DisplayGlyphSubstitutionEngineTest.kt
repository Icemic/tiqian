package org.tiqian.layout

import org.tiqian.core.Ic

import org.tiqian.clreq.CjkPunctuationGlyphPolicy
import org.tiqian.clreq.ClreqProfile
import org.tiqian.clreq.ClreqProfileResolver
import org.tiqian.clreq.LineAdjustmentStrategy
import org.tiqian.core.Cluster
import org.tiqian.core.Glyph
import org.tiqian.core.GlyphRun
import org.tiqian.core.LayoutConstraints
import org.tiqian.core.LayoutResult
import org.tiqian.core.LineBreakPolicy
import org.tiqian.core.LineBreakSpan
import org.tiqian.core.LineLengthGrid
import org.tiqian.core.LineEndReason
import org.tiqian.linebreak.Hyphenator
import org.tiqian.linebreak.NoHyphenator
import org.tiqian.core.LayoutInput
import org.tiqian.core.ParagraphStyle
import org.tiqian.core.Rect
import org.tiqian.core.TextRange
import org.tiqian.core.TextSpan
import org.tiqian.core.TextStyle
import org.tiqian.core.TiqianTextContent
import org.tiqian.core.positionedClusters
import org.tiqian.font.FontRole
import org.tiqian.shaping.ExplainableStubTextShaper
import org.tiqian.shaping.ShapingInput
import org.tiqian.shaping.ShapingResult
import org.tiqian.shaping.TextShaper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Display glyph substitution（ClreqPunctuationGlyphSubstitutor 替换、rollback、
 * ink 证据与 OpenType feature 边界），自 TiqianParagraphLayoutEngineTest
 * 按主题拆出；引擎与断言方式不变。
 */
class DisplayGlyphSubstitutionEngineTest {
    @Test
    fun preservesSourceTextWhenUsingClreqRecommendedDisplayGlyphs() {
        val result = TiqianParagraphLayoutEngine().layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent("……——・／"),
                constraints = LayoutConstraints(maxWidth = 320f),
            ),
        )

        val ellipsis = result.clusters.first { it.text == "……" }
        val dash = result.clusters.first { it.text == "——" }
        val interpunct = result.clusters.first { it.text == "・" }
        val solidus = result.clusters.first { it.text == "／" }

        assertEquals("……", ellipsis.text)
        assertEquals("⋯⋯", ellipsis.displayText)
        assertEquals("——", dash.text)
        assertEquals("⸺", dash.displayText)
        assertEquals("・", interpunct.text)
        assertEquals("·", interpunct.displayText)
        assertEquals("／", solidus.text)
        assertEquals("／", solidus.displayText)
        assertEquals("cjk-primary", ellipsis.fontKey)
        assertEquals("cjk-primary", dash.fontKey)
        assertEquals("cjk-primary", interpunct.fontKey)
        assertEquals("cjk-primary", solidus.fontKey)
    }

    @Test
    fun honorsProfilePunctuationGlyphPolicy() {
        val engine = TiqianParagraphLayoutEngine(
            clreqProfileResolver = ClreqProfileResolver {
                ClreqProfile.MainlandHorizontal.copy(
                    punctuationGlyphPolicy = CjkPunctuationGlyphPolicy.PreserveInput,
                )
            },
        )

        val result = engine.layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent("……——"),
                constraints = LayoutConstraints(maxWidth = 320f),
            ),
        )

        assertEquals("……", result.clusters.first { it.text == "……" }.displayText)
        assertEquals("——", result.clusters.first { it.text == "——" }.displayText)
    }

    @Test
    fun coalesceSetIsDrivenByProfile() {
        // Profile with empty coalesce set should split "——" into two clusters of "—"
        val engine = TiqianParagraphLayoutEngine(
            clreqProfileResolver = ClreqProfileResolver {
                ClreqProfile.MainlandHorizontal.copy(
                    punctuationGlyphPolicy = CjkPunctuationGlyphPolicy.PreserveInput,
                    coalesceRepeatablePunctuation = emptySet(),
                )
            },
        )

        val result = engine.layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent("——"),
                constraints = LayoutConstraints(maxWidth = 320f),
            ),
        )

        assertEquals(2, result.clusters.size)
        assertEquals("—", result.clusters[0].text)
        assertEquals("—", result.clusters[1].text)
    }

    @Test
    fun usesTwoEmAdvanceForRecommendedDashCodepoint() {
        val result = TiqianParagraphLayoutEngine().layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent("⸺"),
                constraints = LayoutConstraints(maxWidth = 320f),
            ),
        )

        assertEquals(32f, result.clusters.single().advance)
        assertEquals(32f, result.size.width)
    }

    @Test
    fun preservesOpenTypeFeaturesAsFinalGlyphRunBoundaries() {
        val proportionalQuoteFeatures = listOf("pwid", "palt")
        val engine = TiqianParagraphLayoutEngine(
            textShaper = object : TextShaper {
                private val delegate = ExplainableStubTextShaper()

                override fun shape(input: ShapingInput): ShapingResult {
                    if (input.displayText != "A’B") return delegate.shape(input)
                    val clusters = (input.range.start until input.range.end).map { index ->
                        val range = TextRange(index, index + 1)
                        Cluster(
                            range = range,
                            text = input.text.substring(range.start, range.end),
                            displayText = input.displayText.substring(
                                range.start - input.range.start,
                                range.end - input.range.start,
                            ),
                            fontKey = input.fontDecision.candidate.key,
                            advance = 16f,
                        )
                    }
                    return ShapingResult(
                        clusters = clusters,
                        glyphRuns = clusters.mapIndexed { glyphId, cluster ->
                            GlyphRun(
                                range = cluster.range,
                                fontKey = cluster.fontKey,
                                glyphs = listOf(
                                    Glyph(
                                        id = glyphId.toUInt(),
                                        clusterRange = cluster.range,
                                        advance = cluster.advance,
                                    ),
                                ),
                                advance = cluster.advance,
                                openTypeFeatures = if (cluster.text == "’") {
                                    proportionalQuoteFeatures
                                } else {
                                    emptyList()
                                },
                            )
                        },
                    )
                }
            },
        )

        val result = engine.layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent("A’B"),
                constraints = LayoutConstraints(maxWidth = 320f),
            ),
        )

        assertEquals(
            listOf(TextRange(0, 1), TextRange(1, 2), TextRange(2, 3)),
            result.glyphRuns.map { it.range },
        )
        assertEquals(
            listOf(emptyList(), proportionalQuoteFeatures, emptyList()),
            result.glyphRuns.map { it.openTypeFeatures },
        )
    }

    @Test
    fun stubShaperReportsProfileFallbackWhenInkBoundsAreUnavailable() {
        val result = TiqianParagraphLayoutEngine().layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent("中文，世界。"),
                constraints = LayoutConstraints(maxWidth = 320f),
            ),
        )

        val punctuationDecisions = result.debug.punctuationDecisions
        assertTrue(punctuationDecisions.isNotEmpty())
        for (p in punctuationDecisions) {
            assertEquals(
                "ProfileGlueFallbackWithoutFontGeometry",
                p.geometrySource,
                "Stub shaper provides advance but no bounds for '${p.char}'",
            )
            // MissingInkBoundsFallback: the degradation reason is recorded.
            assertEquals("shaper-no-ink-bounds", p.inkBoundsFallback, "fallback for '${p.char}'")
            // PauseOrStop: all glue on trailing side
            assertEquals(0f, p.leadingGlueNatural, "leading glue for '${p.char}'")
            assertEquals(8f, p.trailingGlueNatural, "trailing glue for '${p.char}'")
        }
    }

    @Test
    fun shapingWithoutBoundsProducesNamedProfileFallback() {
        val engine = TiqianParagraphLayoutEngine(
            textShaper = object : TextShaper {
                override fun shape(input: ShapingInput): ShapingResult =
                    ShapingResult(
                        clusters = listOf(
                            Cluster(
                                range = input.range,
                                text = input.text.substring(input.range.start, input.range.end),
                                displayText = input.displayText,
                                fontKey = input.fontDecision.candidate.key,
                                advance = 16f,
                            ),
                        ),
                        glyphRuns = listOf(
                            GlyphRun(
                                range = input.range,
                                fontKey = input.fontDecision.candidate.key,
                                glyphs = listOf(
                                    Glyph(
                                        id = 0u,
                                        clusterRange = input.range,
                                        advance = 16f,
                                        bounds = null,
                                    ),
                                ),
                                advance = 16f,
                            ),
                        ),
                    )
            },
        )

        val result = engine.layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent("。"),
                constraints = LayoutConstraints(maxWidth = 320f),
            ),
        )

        val punctuation = result.debug.punctuationDecisions.single()
        assertEquals("ProfileGlueFallbackWithoutFontGeometry", punctuation.geometrySource)
        assertEquals("shaper-no-ink-bounds", punctuation.inkBoundsFallback)
        assertEquals(8f, punctuation.bodyWidth)
        // PauseOrStop: all glue on trailing side (class-based, not ink-based)
        assertEquals(0f, punctuation.leadingGlueNatural)
        assertEquals(8f, punctuation.trailingGlueNatural)
    }

    @Test
    fun substitutionRollsBackToSourceTextWhenFontLacksTheGlyph() {
        // SubstitutionRollbackOnMissingGlyph: the CLREQ substitution `——` →
        // `⸺` only stands if the font covers U+2E3A. This shaper reports a
        // .notdef for the substituted form (like PingFang SC / Hiragino /
        // Heiti would), so the engine must re-shape with the source text.
        val engine = TiqianParagraphLayoutEngine(
            textShaper = object : TextShaper {
                val delegate = ExplainableStubTextShaper()
                override fun shape(input: ShapingInput): ShapingResult {
                    val result = delegate.shape(input)
                    return if (input.displayText.contains('⸺')) {
                        result.copy(decisions = result.decisions.map { it.copy(missingGlyphs = 1) })
                    } else {
                        result
                    }
                }
            },
        )

        val result = engine.layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent("中——文"),
                constraints = LayoutConstraints(maxWidth = 320f),
            ),
        )

        // The dash cluster renders the SOURCE text, not the tofu substitution.
        val dashCluster = result.clusters.single { it.text == "——" }
        assertEquals("——", dashCluster.displayText)

        val dashDecision = result.debug.fontDecisions.single { it.sourceText == "——" }
        assertEquals("——", dashDecision.displayText)
        assertTrue(dashDecision.substitutionReason.endsWith("SubstitutionRollbackOnMissingGlyph"))
    }

    @Test
    fun ellipsisSubstitutionRollsBackWhenCoverageCannotBeVerified() {
        val engine = TiqianParagraphLayoutEngine(
            textShaper = object : TextShaper {
                val delegate = ExplainableStubTextShaper()
                override fun shape(input: ShapingInput): ShapingResult {
                    val result = delegate.shape(input)
                    return if (input.displayText.contains('⋯')) {
                        result.copy(
                            decisions = result.decisions.map {
                                it.copy(capabilityIssue = "UnverifiedDisplaySubstitutionCoverage")
                            },
                        )
                    } else {
                        result
                    }
                }
            },
        )

        val result = engine.layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent("中……文"),
                constraints = LayoutConstraints(maxWidth = 320f),
            ),
        )

        assertEquals("……", result.clusters.single { it.text == "……" }.displayText)
        assertTrue(
            result.debug.fontDecisions.single { it.sourceText == "……" }
                .substitutionReason.endsWith("SubstitutionRollbackOnUnverifiedGlyphCoverage"),
        )
    }

    @Test
    fun dashSubstitutionRollsBackWhenInkDoesNotFillTheTwoEmAdvance() {
        // DashSubstitutionInkCoverageRollback: the font HAS `⸺` but draws it as
        // a ~1.6em rule left-aligned in the 2em advance (Pixel's Noto CJK) — the
        // substitution would leave a ~0.35em hole against the next character, so
        // the engine re-shapes with the source `——` (two full-width em dashes).
        val engine = TiqianParagraphLayoutEngine(
            textShaper = object : TextShaper {
                val delegate = ExplainableStubTextShaper()
                override fun shape(input: ShapingInput): ShapingResult {
                    val result = delegate.shape(input)
                    return if (input.displayText.contains('⸺')) {
                        result.copy(
                            glyphRuns = result.glyphRuns.map { run ->
                                run.copy(
                                    glyphs = run.glyphs.map { g ->
                                        // 32px advance, ink 1..26 → 25/32 ≈ 78% < 85%.
                                        g.copy(advance = 32f, bounds = Rect(1f, -10f, 26f, -8f))
                                    },
                                )
                            },
                        )
                    } else {
                        result
                    }
                }
            },
        )

        val result = engine.layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent("中——文"),
                constraints = LayoutConstraints(maxWidth = 320f),
            ),
        )

        val dashCluster = result.clusters.single { it.text == "——" }
        assertEquals("——", dashCluster.displayText)
        val dashDecision = result.debug.fontDecisions.single { it.sourceText == "——" }
        assertTrue(dashDecision.substitutionReason.endsWith("DashSubstitutionInkCoverageRollback"))
    }

    @Test
    fun dashSubstitutionRollsBackWhenFallbackReportsAFullOneEmGlyph() {
        // Browser font fallback can return a healthy-looking one-em U+2E3A:
        // its ink fills 95% of its own (wrong) advance, but only 47.5% of the
        // required CLREQ two-em box. The target box, not fallback advance, is
        // the denominator.
        val engine = TiqianParagraphLayoutEngine(
            textShaper = object : TextShaper {
                private val delegate = ExplainableStubTextShaper()

                override fun shape(input: ShapingInput): ShapingResult {
                    val result = delegate.shape(input)
                    if (!input.displayText.contains('⸺')) return result
                    return result.copy(
                        clusters = result.clusters.map { it.copy(advance = 16f) },
                        glyphRuns = result.glyphRuns.map { run ->
                            run.copy(
                                advance = 16f,
                                glyphs = run.glyphs.map {
                                    it.copy(
                                        advance = 16f,
                                        bounds = Rect(0.5f, -9f, 15.7f, -7f),
                                    )
                                },
                            )
                        },
                        decisions = result.decisions.map { it.copy(advance = 16f) },
                    )
                }
            },
        )

        val result = engine.layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent("中——文"),
                constraints = LayoutConstraints(maxWidth = 320f),
            ),
        )

        assertEquals("——", result.clusters.single { it.text == "——" }.displayText)
        assertTrue(
            result.debug.fontDecisions.single { it.sourceText == "——" }
                .substitutionReason.endsWith("DashSubstitutionInkCoverageRollback"),
        )
    }

    @Test
    fun dashCoverageTargetUsesTheDashSpanFontSize() {
        val engine = TiqianParagraphLayoutEngine(
            textShaper = object : TextShaper {
                private val delegate = ExplainableStubTextShaper()

                override fun shape(input: ShapingInput): ShapingResult {
                    val result = delegate.shape(input)
                    if (!input.displayText.contains('⸺')) return result
                    // A complete one-em fallback at the span's 32px size.
                    return result.copy(
                        clusters = result.clusters.map { it.copy(advance = 32f) },
                        glyphRuns = result.glyphRuns.map { run ->
                            run.copy(
                                advance = 32f,
                                glyphs = run.glyphs.map {
                                    it.copy(
                                        advance = 32f,
                                        bounds = Rect(1f, -18f, 31f, -14f),
                                    )
                                },
                            )
                        },
                        decisions = result.decisions.map { it.copy(advance = 32f) },
                    )
                }
            },
        )

        val result = engine.layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent(
                    text = "中——文",
                    spans = listOf(
                        TextSpan(TextRange(1, 3), TextStyle(fontSize = 32f)),
                    ),
                ),
                constraints = LayoutConstraints(maxWidth = 320f),
            ),
        )

        assertEquals("——", result.clusters.single { it.text == "——" }.displayText)
    }

    @Test
    fun rolledBackDashStillKeepsItsBoundariesClosedUnderJustification() {
        // Regression (device): after DashSubstitutionInkCoverageRollback the dash
        // displays as TWO chars, whose atoms are PER-CHAR ranges — the coalesced
        // cluster-range lookup missed, dropping the dash out of
        // NoStretchBoundaryClusters, and justify opened a gap right after it.
        val engine = TiqianParagraphLayoutEngine(
            lineBreaker = LookaheadLineBreaker(),
            textShaper = object : TextShaper {
                val delegate = ExplainableStubTextShaper()
                override fun shape(input: ShapingInput): ShapingResult {
                    val result = delegate.shape(input)
                    return if (input.displayText.contains('⸺')) {
                        result.copy(
                            glyphRuns = result.glyphRuns.map { run ->
                                run.copy(
                                    glyphs = run.glyphs.map { g ->
                                        // 78% coverage → rollback to source `——`.
                                        g.copy(advance = 32f, bounds = Rect(1f, -10f, 26f, -8f))
                                    },
                                )
                            },
                        )
                    } else {
                        result
                    }
                }
            },
        )

        // Find a width where the dash sits on a JUSTIFIED (non-last) line.
        val text = "在所谓中文语境下——不如说中文中文中文中文"
        val hit = (13..30).firstNotNullOfOrNull { cells ->
            val result = engine.layout(
                LayoutInput(
                    paragraphStyle = ParagraphStyle(
                        firstLineIndent = Ic(0f),
                        lineLengthGrid = LineLengthGrid(enabled = false),
                    ),
                    content = TiqianTextContent(text),
                    // +7px so lines never fill exactly — every non-last line
                    // carries a deficit and justification actually allocates.
                    constraints = LayoutConstraints(maxWidth = cells * 16f + 7f),
                ),
            )
            val dash = result.clusters.single { it.text == "——" }
            val decision = result.debug.justificationDecisions.firstOrNull {
                dash.range.start >= it.lineRange.start && dash.range.end <= it.lineRange.end
            }
            if (decision != null && decision.allocations.isNotEmpty()) Pair(dash, decision) else null
        } ?: error("no width produced a justified line containing the dash")

        val (dash, decision) = hit
        assertEquals("——", dash.displayText)
        assertTrue(
            decision.allocations.none { it.kind == "CjkInterChar" && it.clusterRange == dash.range },
            "boundary after a rolled-back dash must stay closed: ${'$'}{decision.allocations}",
        )
    }

    @Test
    fun dashInkCentersWithinTheTwoEmBodyWhenTheFontRuleUnderfills() {
        // DashInkCentering: ink 0.5..28 (width 27.5 = 86% ≥ 85% → substitution
        // kept) in a 32px body → inset = (32 − 27.5) / 2 − 0.5 = 1.75px, the
        // glyph draw origin shifts so the rule sits centred.
        val engine = TiqianParagraphLayoutEngine(
            textShaper = object : TextShaper {
                val delegate = ExplainableStubTextShaper()
                override fun shape(input: ShapingInput): ShapingResult {
                    val result = delegate.shape(input)
                    return if (input.displayText.contains('⸺')) {
                        result.copy(
                            glyphRuns = result.glyphRuns.map { run ->
                                run.copy(
                                    glyphs = run.glyphs.map { g ->
                                        g.copy(advance = 32f, bounds = Rect(0.5f, -10f, 28f, -8f))
                                    },
                                )
                            },
                        )
                    } else {
                        result
                    }
                }
            },
        )

        val result = engine.layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent("中——文"),
                constraints = LayoutConstraints(maxWidth = 320f),
            ),
        )

        val dash = result.clusters.single { it.text == "——" }
        assertEquals("⸺", dash.displayText)
        val glyph = result.glyphRuns.flatMap { it.glyphs }.single { it.clusterRange == dash.range }
        assertEquals(1.75f, glyph.x, 0.01f)
    }

    @Test
    fun dashSubstitutionIsKeptWhenInkFillsTheTwoEmAdvance() {
        // Counterpart: a proper two-em rule (Source Han: ink ≈94% of advance)
        // keeps the `——` → `⸺` substitution.
        val engine = TiqianParagraphLayoutEngine(
            textShaper = object : TextShaper {
                val delegate = ExplainableStubTextShaper()
                override fun shape(input: ShapingInput): ShapingResult {
                    val result = delegate.shape(input)
                    return if (input.displayText.contains('⸺')) {
                        result.copy(
                            glyphRuns = result.glyphRuns.map { run ->
                                run.copy(
                                    glyphs = run.glyphs.map { g ->
                                        g.copy(advance = 32f, bounds = Rect(1f, -10f, 31f, -8f))
                                    },
                                )
                            },
                        )
                    } else {
                        result
                    }
                }
            },
        )

        val result = engine.layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent("中——文"),
                constraints = LayoutConstraints(maxWidth = 320f),
            ),
        )

        assertEquals("⸺", result.clusters.single { it.text == "——" }.displayText)
    }

    @Test
    fun substitutionIsKeptWhenFontCoversTheGlyph() {
        // Counterpart: the default stub shaper reports no missing glyphs, so
        // the `——` → `⸺` substitution stays in effect.
        val result = TiqianParagraphLayoutEngine().layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent("中——文"),
                constraints = LayoutConstraints(maxWidth = 320f),
            ),
        )

        val dashCluster = result.clusters.single { it.text == "——" }
        assertEquals("⸺", dashCluster.displayText)
    }

    @Test
    fun ambiguousGlyphClusterMappingFallsBackToPolicyWithRecordedReason() {
        // A multi-character punctuation cluster shaped into a single glyph:
        // per-character ink cannot be attributed, so geometry must fall back
        // to pure policy AND record glyph-cluster-mapping-ambiguous instead
        // of silently looking like the no-shaping path.
        val engine = TiqianParagraphLayoutEngine(
            textShaper = object : TextShaper {
                override fun shape(input: ShapingInput): ShapingResult =
                    ShapingResult(
                        clusters = listOf(
                            Cluster(
                                range = input.range,
                                text = input.text.substring(input.range.start, input.range.end),
                                displayText = input.displayText,
                                fontKey = input.fontDecision.candidate.key,
                                advance = 32f,
                            ),
                        ),
                        glyphRuns = listOf(
                            GlyphRun(
                                range = input.range,
                                fontKey = input.fontDecision.candidate.key,
                                glyphs = listOf(
                                    Glyph(
                                        id = 0u,
                                        clusterRange = input.range,
                                        advance = 32f,
                                        bounds = Rect(left = 2f, top = -10f, right = 30f, bottom = -6f),
                                    ),
                                ),
                                advance = 32f,
                            ),
                        ),
                    )
            },
        )

        val result = engine.layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent("……"),
                constraints = LayoutConstraints(maxWidth = 320f),
            ),
        )

        val punctuationDecisions = result.debug.punctuationDecisions
        assertEquals(2, punctuationDecisions.size)
        for (p in punctuationDecisions) {
            assertEquals("ProfileGlueFallbackWithoutFontGeometry", p.geometrySource, "source for '${p.char}'")
            assertEquals(
                "glyph-cluster-mapping-ambiguous",
                p.inkBoundsFallback,
                "fallback for '${p.char}'",
            )
        }
    }

    @Test
    fun multiCharacterPunctuationUsesCharacterLocalInkBounds() {
        val engine = TiqianParagraphLayoutEngine(
            textShaper = object : TextShaper {
                private val delegate = ExplainableStubTextShaper()

                override fun shape(input: ShapingInput): ShapingResult {
                    if (input.displayText != "⋯⋯") return delegate.shape(input)
                    return ShapingResult(
                        clusters = listOf(
                            Cluster(
                                range = input.range,
                                text = input.text.substring(input.range.start, input.range.end),
                                displayText = input.displayText,
                                fontKey = input.fontDecision.candidate.key,
                                advance = 32f,
                            ),
                        ),
                        glyphRuns = listOf(
                            GlyphRun(
                                range = input.range,
                                fontKey = input.fontDecision.candidate.key,
                                glyphs = listOf(
                                    Glyph(
                                        id = 1u,
                                        clusterRange = input.range,
                                        advance = 16f,
                                        x = 0f,
                                        bounds = Rect(1.5f, -7f, 14.5f, -5f),
                                    ),
                                    Glyph(
                                        id = 2u,
                                        clusterRange = input.range,
                                        advance = 16f,
                                        x = 16f,
                                        bounds = Rect(1.5f, -7f, 14.5f, -5f),
                                    ),
                                ),
                                advance = 32f,
                            ),
                        ),
                    )
                }
            },
        )

        val result = engine.layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent("……"),
                constraints = LayoutConstraints(maxWidth = 320f),
            ),
        )

        val decisions = result.debug.punctuationDecisions
        assertEquals(2, decisions.size)
        assertEquals(listOf(8f, 8f), decisions.map { it.inkCenter })
        assertEquals(listOf(16f, 16f), decisions.map { it.advance })
    }
}
