package org.tiqian.core

import kotlin.test.Test
import org.tiqian.test.trace.assertEquals
import org.tiqian.test.trace.assertFailsWith
import org.tiqian.test.trace.assertFalse
import org.tiqian.test.trace.assertNotNull
import org.tiqian.test.trace.assertNull
import org.tiqian.test.trace.assertTrue
import kotlin.test.AfterTest
import org.tiqian.test.trace.TestTraceRecorder

class TextModelCoverageTest {
    private val testTrace = TestTraceRecorder("TextModelCoverageTest")


    @Test
    fun testTiqianTextContentAndLinkAddressDisplay() {
        testTrace.section("testTiqianTextContentAndLinkAddressDisplay")
        val content = TiqianTextContent(
            text = "Hello Tiqian",
            spans = listOf(TextSpan(TextRange(0, 5), TextStyle())),
            sourceBoundaries = setOf(0, 5, 12),
            lineBreakSpans = listOf(LineBreakSpan(TextRange(0, 5), LineBreakPolicy.ProgressiveTechnical)),
            autoSpaceSuppressedRanges = listOf(TextRange(6, 12)),
        )
        assertEquals("Hello Tiqian", content.text)
        assertEquals(1, content.spans.size)
        assertEquals(3, content.sourceBoundaries.size)
        assertEquals(1, content.lineBreakSpans.size)
        assertEquals(1, content.autoSpaceSuppressedRanges.size)
        assertEquals(content, content.copy())
        assertTrue(content.hashCode() == content.copy().hashCode())
        assertTrue(content.toString().contains("TiqianTextContent"))

        // LinkAddressDisplay
        assertFalse(LinkAddressDisplay.displaysAddress("", ""))
        assertFalse(LinkAddressDisplay.displaysAddress("tiqian.org", ""))
        assertFalse(LinkAddressDisplay.displaysAddress("", "https://tiqian.org"))
        assertTrue(LinkAddressDisplay.displaysAddress("tiqian.org", "tiqian.org"))
        assertTrue(LinkAddressDisplay.displaysAddress("tiqian.org", "https://tiqian.org"))
        assertTrue(LinkAddressDisplay.displaysAddress("tiqian.org", "http://tiqian.org"))
        assertTrue(LinkAddressDisplay.displaysAddress("dev@tiqian.org", "mailto:dev@tiqian.org"))
        assertFalse(LinkAddressDisplay.displaysAddress("tiqian.org", "https://other.org"))
        assertFalse(LinkAddressDisplay.displaysAddress("tiqian.org", "ftp://tiqian.org"))
    }

    @Test
    fun testSpansAndInlineBox() {
        testTrace.section("testSpansAndInlineBox")
        val lineBreakSpan = LineBreakSpan(TextRange(0, 4), LineBreakPolicy.ProgressiveTechnical)
        assertEquals(TextRange(0, 4), lineBreakSpan.range)
        assertEquals(LineBreakPolicy.ProgressiveTechnical, lineBreakSpan.policy)
        assertEquals(lineBreakSpan, lineBreakSpan.copy())
        assertTrue(lineBreakSpan.hashCode() == lineBreakSpan.copy().hashCode())

        for (policy in LineBreakPolicy.entries) {
            assertNotNull(LineBreakPolicy.valueOf(policy.name))
        }

        for (attachment in InlineAttachment.entries) {
            assertNotNull(InlineAttachment.valueOf(attachment.name))
        }

        for (outer in InlineBoxOuterSpacing.entries) {
            assertNotNull(InlineBoxOuterSpacing.valueOf(outer.name))
        }

        val inlineBox = InlineBoxSpan(
            range = TextRange(1, 3),
            inlineStart = 2.0f,
            inlineEnd = 3.0f,
            outerSpacing = InlineBoxOuterSpacing.Source,
        )
        assertEquals(TextRange(1, 3), inlineBox.range)
        assertEquals(2.0f, inlineBox.inlineStart)
        assertEquals(3.0f, inlineBox.inlineEnd)
        assertEquals(InlineBoxOuterSpacing.Source, inlineBox.outerSpacing)
        assertEquals(inlineBox, inlineBox.copy())
        assertTrue(inlineBox.hashCode() == inlineBox.copy().hashCode())

        assertEquals('\uFFFC', INLINE_OBJECT_REPLACEMENT_CHAR)
    }

    @Test
    fun testInlineObjectPreferredStretchAndAdjustment() {
        testTrace.section("testInlineObjectPreferredStretchAndAdjustment")
        for (kind in InlineObjectPreferredStretchKind.entries) {
            assertNotNull(InlineObjectPreferredStretchKind.valueOf(kind.name))
        }

        val stretch = InlineObjectPreferredStretch(
            kind = InlineObjectPreferredStretchKind.Relation,
            naturalWidth = 10.0f,
            targetWidth = 15.0f,
        )
        assertEquals(InlineObjectPreferredStretchKind.Relation, stretch.kind)
        assertEquals(10.0f, stretch.naturalWidth)
        assertEquals(15.0f, stretch.targetWidth)
        assertEquals(5.0f, stretch.capacity)
        assertEquals(stretch, stretch.copy())
        assertTrue(stretch.hashCode() == stretch.copy().hashCode())

        // Requirements validation on InlineObjectPreferredStretch
        assertFailsWith<IllegalArgumentException> {
            InlineObjectPreferredStretch(InlineObjectPreferredStretchKind.PunctuationTrailing, -1.0f, 10.0f)
        }
        assertFailsWith<IllegalArgumentException> {
            InlineObjectPreferredStretch(InlineObjectPreferredStretchKind.PunctuationTrailing, Float.NaN, 10.0f)
        }
        assertFailsWith<IllegalArgumentException> {
            InlineObjectPreferredStretch(InlineObjectPreferredStretchKind.PunctuationTrailing, Float.POSITIVE_INFINITY, 10.0f)
        }
        assertFailsWith<IllegalArgumentException> {
            InlineObjectPreferredStretch(InlineObjectPreferredStretchKind.PunctuationTrailing, 10.0f, 10.0f)
        }
        assertFailsWith<IllegalArgumentException> {
            InlineObjectPreferredStretch(InlineObjectPreferredStretchKind.PunctuationTrailing, 10.0f, 8.0f)
        }
        assertFailsWith<IllegalArgumentException> {
            InlineObjectPreferredStretch(InlineObjectPreferredStretchKind.PunctuationTrailing, 10.0f, Float.NaN)
        }
        assertFailsWith<IllegalArgumentException> {
            InlineObjectPreferredStretch(InlineObjectPreferredStretchKind.PunctuationTrailing, 10.0f, Float.POSITIVE_INFINITY)
        }

        // InlineObjectBoundaryAdjustment
        val fixed = InlineObjectBoundaryAdjustment.Fixed
        assertFalse(fixed.participatesInUniformStretch)
        assertNull(fixed.preferredStretch)
        assertEquals(0.0f, fixed.shrinkCapacity)
        assertEquals(0.0f, fixed.lineEndDiscardableAdvance)
        assertFalse(fixed.preventsLineBreak)

        val customAdj = InlineObjectBoundaryAdjustment(
            participatesInUniformStretch = true,
            preferredStretch = stretch,
            shrinkCapacity = 2.0f,
            lineEndDiscardableAdvance = 1.0f,
            preventsLineBreak = true,
        )
        assertTrue(customAdj.participatesInUniformStretch)
        assertEquals(stretch, customAdj.preferredStretch)
        assertEquals(2.0f, customAdj.shrinkCapacity)
        assertEquals(1.0f, customAdj.lineEndDiscardableAdvance)
        assertTrue(customAdj.preventsLineBreak)
        assertEquals(customAdj, customAdj.copy())
        assertTrue(customAdj.hashCode() == customAdj.copy().hashCode())

        assertFailsWith<IllegalArgumentException> {
            InlineObjectBoundaryAdjustment(shrinkCapacity = -0.5f)
        }
        assertFailsWith<IllegalArgumentException> {
            InlineObjectBoundaryAdjustment(shrinkCapacity = Float.NaN)
        }
        assertFailsWith<IllegalArgumentException> {
            InlineObjectBoundaryAdjustment(lineEndDiscardableAdvance = -0.5f)
        }
        assertFailsWith<IllegalArgumentException> {
            InlineObjectBoundaryAdjustment(lineEndDiscardableAdvance = Float.NaN)
        }

        val inlineObject = InlineObjectSpan(
            range = TextRange(0, 1),
            advance = 16.0f,
            ascent = 12.0f,
            descent = 4.0f,
            leadingBoundary = fixed,
            trailingBoundary = customAdj,
        )
        assertEquals(TextRange(0, 1), inlineObject.range)
        assertEquals(16.0f, inlineObject.advance)
        assertEquals(12.0f, inlineObject.ascent)
        assertEquals(12.0f, inlineObject.copy().ascent)
        assertTrue(inlineObject.hashCode() == inlineObject.copy().hashCode())
    }

    @Test
    fun testTextStyleAndDecorations() {
        testTrace.section("testTextStyleAndDecorations")
        val style = TextStyle(
            fontFamilies = listOf("Noto Serif CJK SC"),
            fontSize = 18.0f,
            locale = "zh-CN",
            fontWeight = 700,
            italic = true,
            baselineShift = -2.0f,
            inlineAttachment = InlineAttachment.Previous,
        )
        assertEquals(listOf("Noto Serif CJK SC"), style.fontFamilies)
        assertEquals(18.0f, style.fontSize)
        assertEquals("zh-CN", style.locale)
        assertEquals(700, style.fontWeight)
        assertTrue(style.italic)
        assertEquals(-2.0f, style.baselineShift)
        assertEquals(InlineAttachment.Previous, style.inlineAttachment)
        assertEquals(style, style.copy())
        assertTrue(style.hashCode() == style.copy().hashCode())

        for (kind in DecorationKind.entries) {
            assertNotNull(DecorationKind.valueOf(kind.name))
        }

        val decoration = DecorationSpan(TextRange(2, 4), DecorationKind.Emphasis)
        assertEquals(TextRange(2, 4), decoration.range)
        assertEquals(DecorationKind.Emphasis, decoration.kind)
        assertEquals(decoration, decoration.copy())
        assertTrue(decoration.hashCode() == decoration.copy().hashCode())

        val color = ColorSpan(start = 1, end = 5, argb = 0xFF112233.toInt())
        assertEquals(1, color.start)
        assertEquals(5, color.end)
        assertEquals(0xFF112233.toInt(), color.argb)
        assertEquals(color, color.copy())
        assertTrue(color.hashCode() == color.copy().hashCode())
        assertTrue(color.toString().contains("ColorSpan"))
    }

    @Test
    fun testRichTextSpansAndPatterns() {
        testTrace.section("testRichTextSpansAndPatterns")
        val paint = RichTextPaint(
            argb = 0xFF000000.toInt(),
            linePattern = RichTextLinePattern.Solid,
            background = RichTextBackgroundPaint(),
            adjacentSameStyleClearance = 1.5f,
        )
        assertEquals(0xFF000000.toInt(), paint.argb)
        assertEquals(1.5f, paint.adjacentSameStyleClearance)
        assertEquals(paint, paint.copy())
        assertTrue(paint.hashCode() == paint.copy().hashCode())

        assertFailsWith<IllegalArgumentException> {
            RichTextPaint(adjacentSameStyleClearance = -0.1f)
        }
        assertFailsWith<IllegalArgumentException> {
            RichTextPaint(adjacentSameStyleClearance = Float.NaN)
        }
        assertFailsWith<IllegalArgumentException> {
            RichTextPaint(adjacentSameStyleClearance = Float.POSITIVE_INFINITY)
        }

        // RichTextBackgroundPaint validation
        val bgPaint = RichTextBackgroundPaint(
            horizontalPadding = 2.0f,
            verticalPadding = 3.0f,
            cornerRadius = 4.0f,
            continuationCornerRadius = 1.0f,
            metricPolicy = RichTextBackgroundMetricPolicy.UniformTextStyle,
            drawStyle = RichTextBackgroundDrawStyle.Border(1.5f),
        )
        assertEquals(2.0f, bgPaint.horizontalPadding)
        assertEquals(3.0f, bgPaint.verticalPadding)
        assertEquals(4.0f, bgPaint.cornerRadius)
        assertEquals(1.0f, bgPaint.continuationCornerRadius)
        assertEquals(RichTextBackgroundMetricPolicy.UniformTextStyle, bgPaint.metricPolicy)
        assertEquals(bgPaint, bgPaint.copy())
        assertTrue(bgPaint.hashCode() == bgPaint.copy().hashCode())

        assertFailsWith<IllegalArgumentException> { RichTextBackgroundPaint(horizontalPadding = -1.0f) }
        assertFailsWith<IllegalArgumentException> { RichTextBackgroundPaint(horizontalPadding = Float.NaN) }
        assertFailsWith<IllegalArgumentException> { RichTextBackgroundPaint(verticalPadding = -1.0f) }
        assertFailsWith<IllegalArgumentException> { RichTextBackgroundPaint(verticalPadding = Float.NaN) }
        assertFailsWith<IllegalArgumentException> { RichTextBackgroundPaint(cornerRadius = -1.0f) }
        assertFailsWith<IllegalArgumentException> { RichTextBackgroundPaint(cornerRadius = Float.NaN) }
        assertFailsWith<IllegalArgumentException> { RichTextBackgroundPaint(continuationCornerRadius = -1.0f) }
        assertFailsWith<IllegalArgumentException> { RichTextBackgroundPaint(continuationCornerRadius = Float.NaN) }

        // DrawStyle
        assertEquals(RichTextBackgroundDrawStyle.Fill, RichTextBackgroundDrawStyle.Fill)
        val border = RichTextBackgroundDrawStyle.Border(2.0f)
        assertEquals(2.0f, border.strokeWidth)
        assertEquals(border, border.copy())
        assertTrue(border.hashCode() == border.copy().hashCode())
        assertFailsWith<IllegalArgumentException> { RichTextBackgroundDrawStyle.Border(0.0f) }
        assertFailsWith<IllegalArgumentException> { RichTextBackgroundDrawStyle.Border(-1.0f) }
        assertFailsWith<IllegalArgumentException> { RichTextBackgroundDrawStyle.Border(Float.NaN) }

        for (policy in RichTextBackgroundMetricPolicy.entries) {
            assertNotNull(RichTextBackgroundMetricPolicy.valueOf(policy.name))
        }

        // Line patterns
        assertEquals(RichTextLinePattern.Solid, RichTextLinePattern.Solid)
        val dashed = RichTextLinePattern.Dashed(strokeWidth = 1.0f, dashLength = 4.0f, gapLength = 2.0f)
        assertEquals(1.0f, dashed.strokeWidth)
        assertEquals(4.0f, dashed.dashLength)
        assertEquals(2.0f, dashed.gapLength)
        assertEquals(dashed, dashed.copy())
        assertTrue(dashed.hashCode() == dashed.copy().hashCode())

        assertFailsWith<IllegalArgumentException> { RichTextLinePattern.Dashed(0.0f, 4.0f, 2.0f) }
        assertFailsWith<IllegalArgumentException> { RichTextLinePattern.Dashed(-1.0f, 4.0f, 2.0f) }
        assertFailsWith<IllegalArgumentException> { RichTextLinePattern.Dashed(Float.NaN, 4.0f, 2.0f) }
        assertFailsWith<IllegalArgumentException> { RichTextLinePattern.Dashed(Float.POSITIVE_INFINITY, 4.0f, 2.0f) }
        assertFailsWith<IllegalArgumentException> { RichTextLinePattern.Dashed(1.0f, 0.0f, 2.0f) }
        assertFailsWith<IllegalArgumentException> { RichTextLinePattern.Dashed(1.0f, -1.0f, 2.0f) }
        assertFailsWith<IllegalArgumentException> { RichTextLinePattern.Dashed(1.0f, Float.NaN, 2.0f) }
        assertFailsWith<IllegalArgumentException> { RichTextLinePattern.Dashed(1.0f, Float.POSITIVE_INFINITY, 2.0f) }
        assertFailsWith<IllegalArgumentException> { RichTextLinePattern.Dashed(1.0f, 4.0f, 0.0f) }
        assertFailsWith<IllegalArgumentException> { RichTextLinePattern.Dashed(1.0f, 4.0f, -1.0f) }
        assertFailsWith<IllegalArgumentException> { RichTextLinePattern.Dashed(1.0f, 4.0f, Float.NaN) }
        assertFailsWith<IllegalArgumentException> { RichTextLinePattern.Dashed(1.0f, 4.0f, Float.POSITIVE_INFINITY) }

        val dotted = RichTextLinePattern.Dotted(dotDiameter = 2.0f, gapLength = 3.0f)
        assertEquals(2.0f, dotted.dotDiameter)
        assertEquals(3.0f, dotted.gapLength)
        assertEquals(dotted, dotted.copy())
        assertTrue(dotted.hashCode() == dotted.copy().hashCode())

        assertFailsWith<IllegalArgumentException> { RichTextLinePattern.Dotted(0.0f, 3.0f) }
        assertFailsWith<IllegalArgumentException> { RichTextLinePattern.Dotted(-1.0f, 3.0f) }
        assertFailsWith<IllegalArgumentException> { RichTextLinePattern.Dotted(Float.NaN, 3.0f) }
        assertFailsWith<IllegalArgumentException> { RichTextLinePattern.Dotted(Float.POSITIVE_INFINITY, 3.0f) }
        assertFailsWith<IllegalArgumentException> { RichTextLinePattern.Dotted(2.0f, 0.0f) }
        assertFailsWith<IllegalArgumentException> { RichTextLinePattern.Dotted(2.0f, -1.0f) }
        assertFailsWith<IllegalArgumentException> { RichTextLinePattern.Dotted(2.0f, Float.NaN) }
        assertFailsWith<IllegalArgumentException> { RichTextLinePattern.Dotted(2.0f, Float.POSITIVE_INFINITY) }

        // Roles
        val linkRole = RichTextRole.Link("https://tiqian.org")
        assertEquals("https://tiqian.org", linkRole.target)
        assertEquals(linkRole, linkRole.copy())
        assertTrue(linkRole.hashCode() == linkRole.copy().hashCode())

        val roles: List<RichTextRole> = listOf(
            RichTextRole.Background,
            RichTextRole.Underline,
            RichTextRole.LineThrough,
            linkRole,
            RichTextRole.TechnicalInline,
            RichTextRole.InlineCode,
        )
        for (r in roles) {
            val span = RichTextSpan(TextRange(0, 2), r, paint)
            assertEquals(r, span.role)
            assertEquals(span, span.copy())
            assertTrue(span.hashCode() == span.copy().hashCode())
        }
    }

    @Test
    fun testRubyAndParagraphModels() {
        testTrace.section("testRubyAndParagraphModels")
        for (kind in RubyKind.entries) {
            assertNotNull(RubyKind.valueOf(kind.name))
        }
        for (mode in RubyLineHeightMode.entries) {
            assertNotNull(RubyLineHeightMode.valueOf(mode.name))
        }

        val pinyinRuby = RubySpan(
            baseRange = TextRange(0, 1),
            text = "hàn",
            fontFamilies = listOf("CustomFont"),
            kind = RubyKind.Pinyin,
        )
        assertEquals(RubyKind.Pinyin, pinyinRuby.kind)
        assertNull(pinyinRuby.locale)

        val bopomofoRuby = RubySpan(
            baseRange = TextRange(0, 1),
            text = "ㄏㄢˋ",
            kind = RubyKind.Bopomofo,
        )
        assertEquals(RubyKind.Bopomofo, bopomofoRuby.kind)
        assertEquals("zh-TW", bopomofoRuby.locale)
        assertEquals(bopomofoRuby, bopomofoRuby.copy())
        assertTrue(bopomofoRuby.hashCode() == bopomofoRuby.copy().hashCode())

        assertEquals(0.1f, DEFAULT_EMPHASIS_DOT_GAP_EM)
        assertEquals(0.1f, DEFAULT_INLINE_OBJECT_MINIMUM_CLEARANCE_EM)

        for (align in LastLineAlignment.entries) {
            assertNotNull(LastLineAlignment.valueOf(align.name))
        }
        for (mode in WritingMode.entries) {
            assertNotNull(WritingMode.valueOf(mode.name))
        }

        val adaptiveIndent = MeasureAdaptiveFirstLineIndent(
            shortBelowEm = 14.0f,
            shortEm = 1.0f,
            longEm = 2.0f,
        )
        assertEquals(1.0f, adaptiveIndent.resolveEm(10.0f))
        assertEquals(2.0f, adaptiveIndent.resolveEm(14.0f))
        assertEquals(2.0f, adaptiveIndent.resolveEm(20.0f))
        assertEquals(adaptiveIndent, adaptiveIndent.copy())
        assertTrue(adaptiveIndent.hashCode() == adaptiveIndent.copy().hashCode())

        val grid = LineLengthGrid(enabled = true, bodyAlignment = LastLineAlignment.Center)
        assertTrue(grid.enabled)
        assertEquals(LastLineAlignment.Center, grid.bodyAlignment)
        assertEquals(grid, grid.copy())
        assertTrue(grid.hashCode() == grid.copy().hashCode())

        val paraStyle = ParagraphStyle(
            lastLineAlignment = LastLineAlignment.End,
            writingMode = WritingMode.VerticalRl,
            lineHeight = 32.0f,
            firstLineIndent = null,
            firstLineIndentPolicy = adaptiveIndent,
            lineLengthGrid = grid,
            rubyLineHeightMode = RubyLineHeightMode.UniformParagraph,
            inlineObjectMinimumClearanceEm = 0.2f,
            emphasisDotGapEm = 0.15f,
        )
        assertEquals(LastLineAlignment.End, paraStyle.lastLineAlignment)
        assertEquals(WritingMode.VerticalRl, paraStyle.writingMode)
        assertEquals(32.0f, paraStyle.lineHeight)
        assertEquals(RubyLineHeightMode.UniformParagraph, paraStyle.rubyLineHeightMode)
        assertEquals(paraStyle, paraStyle.copy())
        assertTrue(paraStyle.hashCode() == paraStyle.copy().hashCode())

        val profileId = LayoutProfileId("custom-profile")
        assertEquals("custom-profile", profileId.value)
        assertEquals("clreq-horizontal", BuiltInLayoutProfiles.ClreqHorizontal.value)
        assertEquals(profileId, profileId.copy())
        assertTrue(profileId.hashCode() == profileId.copy().hashCode())

        val layoutInput = LayoutInput(
            content = TiqianTextContent("Test"),
            textStyle = TextStyle(),
            paragraphStyle = paraStyle,
            constraints = LayoutConstraints(maxWidth = 300.0f),
            profileId = profileId,
            decorations = listOf(DecorationSpan(TextRange(0, 2), DecorationKind.Emphasis)),
            rubySpans = listOf(pinyinRuby),
            inlineBoxes = listOf(InlineBoxSpan(TextRange(0, 1))),
            inlineObjects = listOf(InlineObjectSpan(TextRange(0, 1), 10.0f, 8.0f, 2.0f)),
        )
        assertEquals(profileId, layoutInput.profileId)
        assertEquals(layoutInput, layoutInput.copy())
        assertTrue(layoutInput.hashCode() == layoutInput.copy().hashCode())
    }

    @AfterTest
    fun flushTestTrace() {
        testTrace.flush()
    }
}
