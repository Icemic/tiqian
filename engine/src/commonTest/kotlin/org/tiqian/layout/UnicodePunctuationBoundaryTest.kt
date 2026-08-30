package org.tiqian.layout

import org.tiqian.clreq.ClreqProfile
import org.tiqian.clreq.ClreqProfileResolver
import org.tiqian.clreq.KinsokuLevel
import org.tiqian.clreq.KinsokuMode
import org.tiqian.core.Ic
import org.tiqian.core.Cluster
import org.tiqian.core.LayoutConstraints
import org.tiqian.core.LayoutInput
import org.tiqian.core.LineLengthGrid
import org.tiqian.core.ParagraphStyle
import org.tiqian.core.TiqianTextContent
import org.tiqian.core.TextRange
import org.tiqian.font.FontRole
import org.tiqian.linebreak.NoHyphenator
import kotlin.test.Test
import org.tiqian.test.trace.assertEquals
import org.tiqian.test.trace.assertTrue
import kotlin.test.AfterTest
import org.tiqian.test.trace.TestTraceRecorder

class UnicodePunctuationBoundaryTest {
    private val testTrace = TestTraceRecorder("UnicodePunctuationBoundaryTest")

    @Test
    fun westernBracketsTouchingCjkExposeAllFourStretchBoundaries() {
        testTrace.section("westernBracketsTouchingCjkExposeAllFourStretchBoundaries")
        val text = "育(中文)后"
        val clusters = text.indices.map { index ->
            Cluster(
                range = TextRange(index, index + 1),
                text = text[index].toString(),
                fontKey = if (text[index] == '(' || text[index] == ')') "latin" else "cjk",
                advance = 16f,
            )
        }
        val roles = text.map { char ->
            if (char == '(' || char == ')') FontRole.LatinText else FontRole.CjkText
        }

        assertEquals(
            setOf(0, 1, 3, 4),
            resolveWesternBracketCjkInterCharBoundaries(text, clusters, roles),
        )

        val westernText = "A(B)C"
        val westernClusters = westernText.indices.map { index ->
            Cluster(
                range = TextRange(index, index + 1),
                text = westernText[index].toString(),
                fontKey = "latin",
                advance = 8f,
            )
        }
        assertEquals(
            emptySet(),
            resolveWesternBracketCjkInterCharBoundaries(
                westernText,
                westernClusters,
                List(westernClusters.size) { FontRole.LatinText },
            ),
        )
    }

    @Test
    fun westernClosingPunctuationCannotBeginAnAutomaticLine() {
        testTrace.section("westernClosingPunctuationCannotBeginAnAutomaticLine")
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
        testTrace.section("westernOpeningBracketsCannotEndAnAutomaticLine")
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
        testTrace.section("bracketBoundariesRemainProtectedAcrossWesternSpaces")
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
        testTrace.section("pairedLatinCurlyQuotesKeepTheirContentAcrossBothLineEdges")
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
        testTrace.section("unmatchedWesternCurlyDoubleQuotesRetainTheirDirection")
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
        testTrace.section("unmatchedElisionApostropheBindsForwardInsteadOfBeingGuessedAsACloser")
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
        testTrace.section("westernBaselineSurvivesClreqKinsokuNone")
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

    @AfterTest
    fun flushTestTrace() {
        testTrace.flush()
    }
}
