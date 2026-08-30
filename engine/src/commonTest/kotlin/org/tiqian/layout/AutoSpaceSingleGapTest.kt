package org.tiqian.layout

import org.tiqian.core.Ic
import org.tiqian.core.InlineAttachment
import org.tiqian.core.LayoutConstraints
import org.tiqian.core.LayoutInput
import org.tiqian.core.ParagraphStyle
import org.tiqian.core.TiqianTextContent
import org.tiqian.core.TextRange
import org.tiqian.core.TextSpan
import org.tiqian.core.TextStyle
import kotlin.test.Test
import org.tiqian.test.trace.assertEquals
import org.tiqian.test.trace.assertTrue
import kotlin.test.AfterTest
import org.tiqian.test.trace.TestTraceRecorder

/**
 * `text-autospace: replace` per CSS Text 4 + ADR 0009 must collapse ALL
 * typed U+0020 at a CJK ↔ Latin boundary into a SINGLE autospace gap of
 * `gapEm × fontSize`, regardless of how many spaces the author typed.
 *
 * The earlier implementation multiplied the gap by the typed-space count
 * (`count × gapEm × fontSize`), so doubling the typed spaces also doubled
 * the boundary width. That violates the spec and the project's intent.
 */
class AutoSpaceSingleGapTest {
    private val testTrace = TestTraceRecorder("AutoSpaceSingleGapTest")


    @Test
    fun attachedReferenceBetweenCjkTextDoesNotInventAnAutospaceGap() {
        testTrace.section("attachedReferenceBetweenCjkTextDoesNotInventAnAutospaceGap")
        val result = ExplainableStubParagraphLayoutEngine().layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent(
                    text = "正文1后文",
                    spans = listOf(
                        TextSpan(
                            TextRange(2, 3),
                            TextStyle(inlineAttachment = InlineAttachment.Previous),
                        ),
                    ),
                ),
                constraints = LayoutConstraints(maxWidth = 320f),
            ),
        )

        assertTrue(result.debug.autoSpaceDecisions.isEmpty(), "${result.debug.autoSpaceDecisions}")
    }

    @Test
    fun attachedReferenceBeforeLatinTextGetsTheVirtualCjkLatinGap() {
        testTrace.section("attachedReferenceBeforeLatinTextGetsTheVirtualCjkLatinGap")
        val result = ExplainableStubParagraphLayoutEngine().layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent(
                    text = "正文1ABC",
                    spans = listOf(
                        TextSpan(
                            TextRange(2, 3),
                            TextStyle(inlineAttachment = InlineAttachment.Previous),
                        ),
                    ),
                ),
                constraints = LayoutConstraints(maxWidth = 320f),
            ),
        )

        val decision = result.debug.autoSpaceDecisions.single()
        assertEquals("trailing", decision.side)
        assertEquals("InlineAttachment.Previous", decision.boundaryRole)
        assertEquals(
            "AttachedInlineVirtualAutoSpace:east-asian-spacing-W-N",
            decision.reason,
        )
    }

    @Test
    fun attachedReferenceAtParagraphEndHasNoAutospaceGap() {
        testTrace.section("attachedReferenceAtParagraphEndHasNoAutospaceGap")
        val result = ExplainableStubParagraphLayoutEngine().layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent(
                    text = "正文1",
                    spans = listOf(
                        TextSpan(
                            TextRange(2, 3),
                            TextStyle(inlineAttachment = InlineAttachment.Previous),
                        ),
                    ),
                ),
                constraints = LayoutConstraints(maxWidth = 320f),
            ),
        )

        assertTrue(result.debug.autoSpaceDecisions.isEmpty(), "${result.debug.autoSpaceDecisions}")
    }

    @Test
    fun oneTypedSpaceBecomesOneAutospaceGap() {
        testTrace.section("oneTypedSpaceBecomesOneAutospaceGap")
        // `中文 CJK 段落` — LatinWordSegmentation makes each space its own
        // cluster. CJK-adjacent space clusters ARE the sino-western gap:
        // their advance normalises from 1em (stub) to gapEm (default 0.125em).
        val result = ExplainableStubParagraphLayoutEngine().layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent("中文 CJK 段落"),
                constraints = LayoutConstraints(maxWidth = 320f),
            ),
        )
        val spaces = result.clusters.filter { it.text == " " }
        assertEquals(2, spaces.size)
        assertTrue(spaces.all { it.advance == 2f })
        val word = result.clusters.single { it.text == "CJK" }
        assertEquals(48f, word.advance)
    }

    @Test
    fun twoTypedSpacesAtBoundaryStillCollapseToOneGap() {
        testTrace.section("twoTypedSpacesAtBoundaryStillCollapseToOneGap")
        // `中文  CJK 段落` — the 2-space run is ONE cluster and still
        // normalises to a single gapEm gap, same as one typed space.
        val result = ExplainableStubParagraphLayoutEngine().layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent("中文  CJK 段落"),
                constraints = LayoutConstraints(maxWidth = 320f),
            ),
        )
        val doubleSpace = result.clusters.single { it.text == "  " }
        assertEquals(2f, doubleSpace.advance)
        val singleSpace = result.clusters.single { it.text == " " }
        assertEquals(2f, singleSpace.advance)
    }

    @Test
    fun threeTypedSpacesStillOneGap() {
        testTrace.section("threeTypedSpacesStillOneGap")
        // 3 leading spaces, 0 trailing — the 3-space run normalises to one
        // 2 px gap, and the space-less trailing boundary CJK→段 gains an
        // Insert gap inside the word cluster (48 + 2 = 50).
        val result = ExplainableStubParagraphLayoutEngine().layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent("中文   CJK段落"),
                constraints = LayoutConstraints(maxWidth = 320f),
            ),
        )
        val tripleSpace = result.clusters.single { it.text == "   " }
        assertEquals(2f, tripleSpace.advance)
        val word = result.clusters.single { it.text == "CJK" }
        assertEquals(50f, word.advance)
    }

    @Test
    fun zeroSpacesGetInsertedGaps() {
        testTrace.section("zeroSpacesGetInsertedGaps")
        // TextAutoSpaceInsert (CLREQ:「汉字与西文字母、数字间使用不多于四分
        // 之一个汉字宽的字距或空白」——1/8 合规): boundaries WITHOUT typed
        // spaces gain a gapEm gap per side. "CJK" nominal 48 → 48 + 2 + 2 = 52.
        val result = ExplainableStubParagraphLayoutEngine().layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent("中文CJK段落"),
                constraints = LayoutConstraints(maxWidth = 320f),
            ),
        )
        val cluster = result.clusters.single { it.text == "CJK" }
        assertEquals(52f, cluster.advance)

        val decisions = result.debug.autoSpaceDecisions
        assertEquals(2, decisions.size)
        assertTrue(decisions.all { it.mode == "Insert" && it.charactersAffected == 0 })
        assertTrue(decisions.all { it.totalReduction == -2f })
        assertTrue(decisions.all { it.reason.startsWith("TextAutoSpaceInsert") })
    }

    @Test
    fun unicodeEastAsianSpacingCoversNarrowScriptsWithoutScriptWhitelists() {
        testTrace.section("unicodeEastAsianSpacingCoversNarrowScriptsWithoutScriptWhitelists")
        for (sample in listOf("α", "я", "ա")) {
            val result = ExplainableStubParagraphLayoutEngine().layout(
                LayoutInput(
                    paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                    content = TiqianTextContent("中${sample}文"),
                    constraints = LayoutConstraints(maxWidth = 320f),
                ),
            )

            val narrow = result.clusters.single { it.text == sample }
            assertEquals(20f, narrow.advance, "sample=$sample")
            assertEquals(2, result.debug.autoSpaceDecisions.size, "sample=$sample")
            assertTrue(result.debug.autoSpaceDecisions.all { it.clusterRange == narrow.range }, "sample=$sample")
            assertTrue(
                result.debug.autoSpaceDecisions.all {
                    it.reason == "TextAutoSpaceInsert:east-asian-spacing-W-N"
                },
                "sample=$sample",
            )
        }
    }

    @Test
    fun conditionalPunctuationFollowsChineseLanguageResolution() {
        testTrace.section("conditionalPunctuationFollowsChineseLanguageResolution")
        val result = ExplainableStubParagraphLayoutEngine().layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent("中%文"),
                constraints = LayoutConstraints(maxWidth = 320f),
            ),
        )

        assertEquals(2, result.debug.autoSpaceDecisions.size)
        assertTrue(result.debug.autoSpaceDecisions.all { it.boundaryRole == "EastAsianSpacing.Wide" })
    }

    @Test
    fun autospaceDoesNotFireBetweenLatinAndCjkPunctuation() {
        testTrace.section("autospaceDoesNotFireBetweenLatinAndCjkPunctuation")
        // Per CSS Text 4, `text-autospace` fires at ideograph ↔ alpha/numeric
        // boundaries only. Punctuation has its own spacing model. The text
        // `Tiqian ）说明` has a typed space between Latin and a closing
        // bracket: there must be no autospace decision for that boundary.
        val result = ExplainableStubParagraphLayoutEngine().layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent("Tiqian ）说明"),
                constraints = LayoutConstraints(maxWidth = 320f),
            ),
        )
        // The boundary at the right of the Latin cluster is CjkPunctuation
        // (the closing bracket), which should NOT trigger autospace. The
        // left of the Latin cluster is text boundary, also no autospace.
        assertEquals(0, result.debug.autoSpaceDecisions.size)
    }

    @Test
    fun autospaceDoesNotFireBeforeSlashLedLatinTechnicalRun() {
        testTrace.section("autospaceDoesNotFireBeforeSlashLedLatinTechnicalRun")
        // `/TERFism` is shaped as one LatinText run so slash + acronym use a
        // compatible western font and escape CJK punctuation geometry, but the
        // boundary at `跨/` is still ideograph ↔ punctuation, not ideograph ↔
        // alpha/numeric. TextAutoSpaceInsert must not synthesize `恐跨 /TERFism`.
        val result = ExplainableStubParagraphLayoutEngine().layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent("恐跨/TERFism。如果"),
                constraints = LayoutConstraints(maxWidth = 320f),
            ),
        )
        val cluster = result.clusters.single { it.text == "/TERFism" }
        assertTrue(
            result.debug.autoSpaceDecisions.none { it.clusterRange == cluster.range && it.side == "leading" },
            "slash-led Latin technical run must not receive leading autospace: ${result.debug.autoSpaceDecisions}",
        )
    }

    @Test
    fun autospaceStillFiresBetweenLatinAndCjkTextEvenWithPunctuationNearby() {
        testTrace.section("autospaceStillFiresBetweenLatinAndCjkTextEvenWithPunctuationNearby")
        // The boundary is between Latin "shaping" and the CjkText `之`. The
        // closing bracket `）` next to it does not cancel the firing.
        val result = ExplainableStubParagraphLayoutEngine().layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent("中文 shaping 之后"),
                constraints = LayoutConstraints(maxWidth = 320f),
            ),
        )
        assertEquals(2, result.debug.autoSpaceDecisions.size) // one per space cluster
        assertTrue(result.debug.autoSpaceDecisions.all { it.boundaryRole == "EastAsianSpacing.Wide" })
        assertTrue(result.debug.autoSpaceDecisions.all { it.side == "gap" })
    }

    @Test
    fun autospaceDistinguishesLetterFromDigitAtBoundary() {
        testTrace.section("autospaceDistinguishesLetterFromDigitAtBoundary")
        // CLREQ 字母/数字之分: the boundary-adjacent western char selects
        // cjkLatin (letter) vs cjkDigit (digit). Disable cjkDigit only —
        // 汉字↔字母 still inserts a gap, 汉字↔数字 does not.
        val engine = ExplainableStubParagraphLayoutEngine(
            clreqProfileResolver = {
                org.tiqian.clreq.ClreqProfile.MainlandHorizontal.copy(
                    autoSpace = org.tiqian.clreq.AutoSpacePolicy(
                        cjkLatin = org.tiqian.clreq.AutoSpaceMode.Insert,
                        cjkDigit = org.tiqian.clreq.AutoSpaceMode.Disabled,
                    ),
                )
            },
        )
        val result = engine.layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
                content = TiqianTextContent("甲A乙9丙"),
                constraints = LayoutConstraints(maxWidth = 320f),
            ),
        )
        val decided = result.debug.autoSpaceDecisions
        val aRange = result.clusters.single { it.text == "A" }.range
        val nineRange = result.clusters.single { it.text == "9" }.range
        assertTrue(decided.isNotEmpty() && decided.all { it.clusterRange == aRange }, "only the letter fires: $decided")
        assertTrue(decided.none { it.clusterRange == nineRange }, "digit boundary must not fire when cjkDigit disabled")
    }

    @AfterTest
    fun flushTestTrace() {
        testTrace.flush()
    }
}
