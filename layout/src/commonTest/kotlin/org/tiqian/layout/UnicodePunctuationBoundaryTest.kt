package org.tiqian.layout

import org.tiqian.clreq.ClreqProfile
import org.tiqian.clreq.ClreqProfileResolver
import org.tiqian.clreq.KinsokuLevel
import org.tiqian.clreq.KinsokuMode
import org.tiqian.core.Ic
import org.tiqian.core.LayoutConstraints
import org.tiqian.core.LayoutInput
import org.tiqian.core.LineLengthGrid
import org.tiqian.core.ParagraphStyle
import org.tiqian.core.TiqianTextContent
import org.tiqian.linebreak.NoHyphenator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UnicodePunctuationBoundaryTest {
    @Test
    fun westernClosingPunctuationCannotBeginAnAutomaticLine() {
        for (mark in listOf(')', ']', '}', ',', '.', ':', ';', '!', '?')) {
            for ((label, breaker) in breakers()) {
                val text = "中文${mark}文"
                val result = layout(
                    text = text,
                    maxWidth = 32f,
                    breaker = breaker,
                    level = KinsokuLevel.None,
                )
                val lineTexts = result.lines.map { text.substring(it.range.start, it.range.end) }

                assertTrue(
                    lineTexts.none { it.startsWith(mark) },
                    "$label placed '$mark' at line start: $lineTexts",
                )
                assertTrue(
                    result.debug.contextualKinsokuDecisions.any {
                        it.sourceText == mark.toString() &&
                            it.forbiddenPosition == "LineStart" &&
                            it.reason.startsWith("Uax14WesternPunctuationBoundary:")
                    },
                    "$label '$mark' decisions=${result.debug.contextualKinsokuDecisions}",
                )
            }
        }
    }

    @Test
    fun westernOpeningBracketsCannotEndAnAutomaticLine() {
        for (mark in listOf('(', '[', '{')) {
            for ((label, breaker) in breakers()) {
                val text = "ABCD${mark}E"
                val result = layout(text, maxWidth = 40f, breaker = breaker)
                val lineTexts = result.lines.map { text.substring(it.range.start, it.range.end) }

                assertTrue(
                    lineTexts.none { it.endsWith(mark) },
                    "$label placed '$mark' at line end: $lineTexts",
                )
                assertTrue(
                    result.debug.contextualKinsokuDecisions.any {
                        it.sourceText == mark.toString() &&
                            it.forbiddenPosition == "LineEnd" &&
                            it.reason == "Uax14WesternPunctuationBoundary:LB14"
                    },
                    "$label '$mark' decisions=${result.debug.contextualKinsokuDecisions}",
                )
            }
        }
    }

    @Test
    fun bracketBoundariesRemainProtectedAcrossWesternSpaces() {
        for ((label, breaker) in breakers()) {
            // The protected punctuation + spaces + neighbor must fit; below
            // this range a breaker is allowed to surface an impossible-width
            // violation rather than loop or drop source text.
            for (width in 48..80 step 4) {
                val openingText = "ABCD(  EFGH"
                val openingLines = layout(openingText, width.toFloat(), breaker).lines
                    .map { openingText.substring(it.range.start, it.range.end) }
                assertTrue(
                    openingLines.none { it.trimEnd().endsWith('(') },
                    "$label width=$width left an opener before trailing spaces: $openingLines",
                )

                val closingText = "ABCD  )EFGH"
                val closingLines = layout(closingText, width.toFloat(), breaker).lines
                    .map { closingText.substring(it.range.start, it.range.end) }
                assertTrue(
                    closingLines.none { it.trimStart().startsWith(')') },
                    "$label width=$width left a closer after leading spaces: $closingLines",
                )
            }
        }
    }

    @Test
    fun pairedLatinCurlyQuotesKeepTheirContentAcrossBothLineEdges() {
        for ((label, breaker) in breakers()) {
            val closingText = "“ABCD”E"
            val closing = layout(closingText, maxWidth = 40f, breaker = breaker)
            val closingLines = closing.lines.map { closingText.substring(it.range.start, it.range.end) }
            assertTrue(closingLines.none { it.startsWith('”') }, "$label closing lines=$closingLines")
            assertEquals(
                "Uax14WesternPunctuationBoundary:PairedClosingQuote",
                closing.debug.contextualKinsokuDecisions.single {
                    it.sourceText == "”" && it.forbiddenPosition == "LineStart"
                }.reason,
            )

            val openingText = "ABCD“E”"
            val opening = layout(openingText, maxWidth = 40f, breaker = breaker)
            val openingLines = opening.lines.map { openingText.substring(it.range.start, it.range.end) }
            assertTrue(openingLines.none { it.endsWith('“') }, "$label opening lines=$openingLines")
            assertEquals(
                "Uax14WesternPunctuationBoundary:PairedOpeningQuote",
                opening.debug.contextualKinsokuDecisions.single {
                    it.sourceText == "“" && it.forbiddenPosition == "LineEnd"
                }.reason,
            )
        }
    }

    @Test
    fun unmatchedWesternCurlyDoubleQuotesRetainTheirDirection() {
        for ((label, breaker) in breakers()) {
            val closingText = "ABCD”E"
            val closing = layout(closingText, maxWidth = 32f, breaker = breaker)
            val closingLines = closing.lines.map { closingText.substring(it.range.start, it.range.end) }
            assertTrue(closingLines.none { it.startsWith('”') }, "$label closing lines=$closingLines")
            assertEquals(
                "Uax14WesternPunctuationBoundary:LB19",
                closing.debug.contextualKinsokuDecisions.single {
                    it.sourceText == "”" && it.forbiddenPosition == "LineStart"
                }.reason,
            )

            val openingText = "ABCD“E"
            val opening = layout(openingText, maxWidth = 40f, breaker = breaker)
            val openingLines = opening.lines.map { openingText.substring(it.range.start, it.range.end) }
            assertTrue(openingLines.none { it.endsWith('“') }, "$label opening lines=$openingLines")
            assertEquals(
                "Uax14WesternPunctuationBoundary:LB19",
                opening.debug.contextualKinsokuDecisions.single {
                    it.sourceText == "“" && it.forbiddenPosition == "LineEnd"
                }.reason,
            )
        }
    }

    @Test
    fun unmatchedElisionApostropheBindsForwardInsteadOfBeingGuessedAsACloser() {
        val text = "AB ’90s"
        val result = layout(text, maxWidth = 16f, breaker = GreedyLineBreaker())

        assertTrue(
            result.debug.contextualKinsokuDecisions.none {
                it.sourceText == "’" && it.forbiddenPosition == "LineStart"
            },
        )
        assertEquals(
            "Uax14WesternPunctuationBoundary:LB19",
            result.debug.contextualKinsokuDecisions.single {
                it.sourceText == "’" && it.forbiddenPosition == "LineEnd"
            }.reason,
        )
    }

    @Test
    fun westernBaselineSurvivesClreqKinsokuNone() {
        val text = "ABCD)E"
        val result = layout(
            text = text,
            maxWidth = 32f,
            breaker = GreedyLineBreaker(),
            level = KinsokuLevel.None,
        )

        assertEquals(
            "Uax14WesternPunctuationBoundary:LB13",
            result.debug.contextualKinsokuDecisions.single { it.sourceText == ")" }.reason,
        )
    }

    private fun layout(
        text: String,
        maxWidth: Float,
        breaker: LineBreaker,
        level: KinsokuLevel = KinsokuLevel.Basic,
    ) = ExplainableStubParagraphLayoutEngine(
        lineBreaker = breaker,
        hyphenator = NoHyphenator,
        clreqProfileResolver = ClreqProfileResolver {
            ClreqProfile.MainlandHorizontal.copy(kinsokuMode = KinsokuMode.Fixed(level))
        },
    ).layout(
        LayoutInput(
            paragraphStyle = ParagraphStyle(
                firstLineIndent = Ic.Zero,
                lineLengthGrid = LineLengthGrid(enabled = false),
            ),
            content = TiqianTextContent(
                text = text,
                sourceBoundaries = (0..text.length).toSet(),
            ),
            constraints = LayoutConstraints(maxWidth = maxWidth),
        ),
    )

    private fun breakers(): List<Pair<String, LineBreaker>> = listOf(
        "greedy" to GreedyLineBreaker(),
        "lookahead" to LookaheadLineBreaker(),
    )
}
