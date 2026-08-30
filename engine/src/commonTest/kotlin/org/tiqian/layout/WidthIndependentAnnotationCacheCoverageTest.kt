package org.tiqian.layout

import org.tiqian.clreq.AdjustmentStylePolicy
import org.tiqian.clreq.AutoSpaceMode
import org.tiqian.clreq.AutoSpacePolicy
import org.tiqian.clreq.ClreqProfile
import org.tiqian.clreq.ClreqProfileResolver
import org.tiqian.clreq.ClreqPunctuationGlyphSubstitutor
import org.tiqian.clreq.PunctuationClass
import org.tiqian.clreq.PunctuationWidthPolicy
import org.tiqian.core.Cluster
import org.tiqian.core.DecorationKind
import org.tiqian.core.DecorationSpan
import org.tiqian.core.EastAsianSpacingEdges
import org.tiqian.core.EastAsianSpacingValue
import org.tiqian.core.Glyph
import org.tiqian.core.GlyphRun
import org.tiqian.core.Ic
import org.tiqian.core.InlineAttachment
import org.tiqian.core.InlineBoxOuterSpacing
import org.tiqian.core.InlineBoxSpan
import org.tiqian.core.InlineObjectBoundaryAdjustment
import org.tiqian.core.InlineObjectPreferredStretch
import org.tiqian.core.InlineObjectPreferredStretchKind
import org.tiqian.core.InlineObjectSpan
import org.tiqian.core.LastLineAlignment
import org.tiqian.core.LayoutConstraints
import org.tiqian.core.LayoutInput
import org.tiqian.core.LayoutProfileId
import org.tiqian.core.LineBreakPolicy
import org.tiqian.core.LineBreakSpan
import org.tiqian.core.LineLengthGrid
import org.tiqian.core.ParagraphStyle
import org.tiqian.core.Rect
import org.tiqian.core.RubyKind
import org.tiqian.core.RubySpan
import org.tiqian.core.TextRange
import org.tiqian.core.TextSpan
import org.tiqian.core.TextStyle
import org.tiqian.core.TiqianTextContent
import org.tiqian.font.FontCandidate
import org.tiqian.font.FontDecision
import org.tiqian.font.FontRole
import org.tiqian.shaping.ExplainableStubTextShaper
import org.tiqian.shaping.ShapingInput
import org.tiqian.shaping.ShapingResult
import org.tiqian.shaping.TextShaper
import kotlin.test.Test
import org.tiqian.test.trace.assertEquals
import org.tiqian.test.trace.assertFailsWith
import org.tiqian.test.trace.assertNotNull
import org.tiqian.test.trace.assertNull
import org.tiqian.test.trace.assertTrue
import kotlin.test.AfterTest
import org.tiqian.test.trace.TestTraceRecorder

class WidthIndependentAnnotationCacheCoverageTest {
    private val testTrace = TestTraceRecorder("WidthIndependentAnnotationCacheCoverageTest")


    @Test
    fun lruCacheUpdateExistingKeyAndClear() {
        testTrace.section("lruCacheUpdateExistingKeyAndClear")
        val cache = LruWidthIndependentAnnotationCache(maxEntries = 2)
        val dummyInput = LayoutInput(
            content = TiqianTextContent("测试缓存"),
            constraints = LayoutConstraints(maxWidth = 300.0f),
        )
        val key = dummyInput.toWidthIndependentAnnotationKey()

        // 1. Initial put
        cache.put(key, "v1")
        assertEquals(1, cache.size)
        assertEquals("v1", cache.get(key))

        // 2. Put existing key (key in map branch)
        cache.put(key, "v2")
        assertEquals(1, cache.size)
        assertEquals("v2", cache.get(key))

        // 3. Put another key
        val key2 = dummyInput.copy(textStyle = TextStyle(fontSize = 20.0f)).toWidthIndependentAnnotationKey()
        cache.put(key2, "v3")
        assertEquals(2, cache.size)

        // 4. Put third key to trigger eviction
        val key3 = dummyInput.copy(textStyle = TextStyle(fontSize = 30.0f)).toWidthIndependentAnnotationKey()
        cache.put(key3, "v4")
        assertEquals(2, cache.size)
        assertNull(cache.get(key))
        assertEquals("v3", cache.get(key2))
        assertEquals("v4", cache.get(key3))

        // 5. Clear
        cache.clear()
        assertEquals(0, cache.size)
        assertNull(cache.get(key2))
        assertNull(cache.get(key3))
    }

    @Test
    fun containingItemsAndFirstContainedItemBranches() {
        testTrace.section("containingItemsAndFirstContainedItemBranches")
        val clusters = listOf(
            Cluster(range = TextRange(0, 2), text = "aa", displayText = "aa", fontKey = "k", advance = 10.0f),
            Cluster(range = TextRange(2, 5), text = "bbb", displayText = "bbb", fontKey = "k", advance = 15.0f),
            Cluster(range = TextRange(5, 7), text = "cc", displayText = "cc", fontKey = "k", advance = 10.0f),
            Cluster(range = TextRange(7, 9), text = "dd", displayText = "dd", fontKey = "k", advance = 10.0f),
        )

        val items = listOf(
            TextRange(0, 2),   // exact match cluster 0
            TextRange(1, 4),   // cluster 0 ends at 2 (<=4), cluster 1 starts at 2 (>=1), but cluster 1 ends at 5 (>4)
            TextRange(5, 8),   // cluster 2 (5..7) contained in 5..8; cluster 3 (7..9) not contained
            TextRange(10, 12), // cluster out of range
        )

        val contained = clusters.containingItems(items) { it }
        assertEquals(4, contained.size)
        assertEquals(TextRange(0, 2), contained[0])
        assertNull(contained[1]) // cluster 2..5 is not contained in 1..4 (end 5 > 4)
        assertEquals(TextRange(5, 8), contained[2]) // cluster 5..7 is contained in 5..8
        assertNull(contained[3])

        val firstContained = clusters.firstContainedItem(items) { it }
        assertEquals(4, firstContained.size)
        assertEquals(TextRange(0, 2), firstContained[0])
        assertNull(firstContained[1])
        assertNull(firstContained[2])
        assertNull(firstContained[3])
    }

    @Test
    fun prepareWidthIndependentAnnotationBranches() {
        testTrace.section("prepareWidthIndependentAnnotationBranches")
        val engine = ExplainableStubParagraphLayoutEngine()

        val input = LayoutInput(
            content = TiqianTextContent(
                text = "测试文本【中文】与English，以及注音与行内框。",
                spans = listOf(
                    TextSpan(range = TextRange(0, 0), style = TextStyle(fontSize = 10.0f)), // empty span
                    TextSpan(range = TextRange(0, 1), style = TextStyle(fontSize = 18.0f, fontWeight = 500)), // forces cluster 0 to be 0..1
                    TextSpan(range = TextRange(1, 4), style = TextStyle(fontSize = 18.0f, fontWeight = 500)),
                    TextSpan(range = TextRange(4, 8), style = TextStyle(fontSize = 14.0f, fontWeight = 300)),
                ),
                lineBreakSpans = listOf(
                    LineBreakSpan(range = TextRange(8, 15), policy = LineBreakPolicy.ProgressiveTechnical),
                ),
                sourceBoundaries = setOf(1, 2, 3, 4, 6),
            ),
            textStyle = TextStyle(fontSize = 16.0f, locale = "zh-CN", fontWeight = 400),
            decorations = listOf(
                DecorationSpan(range = TextRange(0, 4), kind = DecorationKind.Emphasis),
                DecorationSpan(range = TextRange(4, 8), kind = DecorationKind.ProperNoun),
            ),
            rubySpans = listOf(
                RubySpan(baseRange = TextRange(0, 2), text = "cèshì", kind = RubyKind.Pinyin, locale = "zh-Latn"),
                RubySpan(baseRange = TextRange(2, 4), text = "", kind = RubyKind.Pinyin), // empty ruby text
                RubySpan(baseRange = TextRange(0, 1), text = "˙ㄅ", kind = RubyKind.Bopomofo),
                RubySpan(baseRange = TextRange(0, 1), text = "ㄆ", kind = RubyKind.Bopomofo),
                RubySpan(baseRange = TextRange(99, 100), text = "invalid", kind = RubyKind.Bopomofo),
            ),
            inlineBoxes = listOf(
                InlineBoxSpan(range = TextRange(15, 17), inlineStart = 4.0f, inlineEnd = 0.0f),
                InlineBoxSpan(range = TextRange(17, 19), inlineStart = 0.0f, inlineEnd = 4.0f),
                InlineBoxSpan(range = TextRange(19, 21), outerSpacing = InlineBoxOuterSpacing.Narrow),
                InlineBoxSpan(range = TextRange(21, 23), inlineStart = 0.0f, inlineEnd = 0.0f, outerSpacing = InlineBoxOuterSpacing.Source),
            ),
            inlineObjects = listOf(
                InlineObjectSpan(range = TextRange(23, 24), advance = 20.0f, ascent = 12.0f, descent = 4.0f),
            ),
            constraints = LayoutConstraints(maxWidth = 300.0f),
        )

        val annotation = engine.prepareWidthIndependentAnnotation(input, emptyMap())
        assertNotNull(annotation)
        assertEquals(18.0f, annotation.fontSizeAt(0))
        assertEquals(14.0f, annotation.fontSizeAt(5))
        assertEquals(16.0f, annotation.fontSizeAt(24)) // fallback to default textStyle

        assertEquals(800, annotation.bopomofoFontWeightAt(0)) // 500 + 300
        assertEquals(600, annotation.bopomofoFontWeightAt(5)) // 300 + 300
        assertEquals(700, annotation.bopomofoFontWeightAt(24)) // 400 + 300

        // Test styleAt and fontSizeAt at boundary offsets before, inside, between, and after spans
        assertEquals(18.0f, annotation.styleAt(0).fontSize)
        assertEquals(18.0f, annotation.styleAt(3).fontSize)
        assertEquals(14.0f, annotation.styleAt(4).fontSize)
        assertEquals(14.0f, annotation.styleAt(7).fontSize)
        assertEquals(16.0f, annotation.styleAt(8).fontSize)
        assertEquals(16.0f, annotation.styleAt(25).fontSize)

        val prep = engine.buildParagraphLayoutPrep(input, annotation, emptyMap())
        assertNotNull(prep)
        assertTrue(prep.rubyAndBopomofoSpread.isNotEmpty())
    }

    @Test
    fun lineLengthGridBodyAlignmentBranches() {
        testTrace.section("lineLengthGridBodyAlignmentBranches")
        val engine = ExplainableStubParagraphLayoutEngine()
        val text = "一二三四五六七八九十"

        for (align in listOf(LastLineAlignment.Start, LastLineAlignment.Center, LastLineAlignment.End)) {
            val input = LayoutInput(
                content = TiqianTextContent(text),
                textStyle = TextStyle(fontSize = 16.0f),
                paragraphStyle = ParagraphStyle(
                    lineLengthGrid = LineLengthGrid(enabled = true, bodyAlignment = align),
                ),
                constraints = LayoutConstraints(maxWidth = 100.0f),
            )
            val annotation = engine.prepareWidthIndependentAnnotation(input, emptyMap())
            val prep = engine.buildParagraphLayoutPrep(input, annotation, emptyMap())
            assertNotNull(prep)
            when (align) {
                LastLineAlignment.Start -> assertEquals(0.0f, prep.gridBodyOffset)
                LastLineAlignment.Center -> assertEquals(2.0f, prep.gridBodyOffset, 0.001f)
                LastLineAlignment.End -> assertEquals(4.0f, prep.gridBodyOffset, 0.001f)
            }
        }
    }

    @Test
    fun dynamicShapingTriggersAndEmphasisItalic() {
        testTrace.section("dynamicShapingTriggersAndEmphasisItalic")
        val engine = ExplainableStubParagraphLayoutEngine()
        // 1. Fast path (no progressive spans, no rejected tiers, no over-measure)
        val simpleInput = LayoutInput(
            content = TiqianTextContent("中文正文排版"),
            constraints = LayoutConstraints(maxWidth = 500.0f),
        )
        val simpleAnnotation = engine.prepareWidthIndependentAnnotation(simpleInput, emptyMap())
        val simplePrep = engine.buildParagraphLayoutPrep(simpleInput, simpleAnnotation, emptyMap())
        assertNotNull(simplePrep)

        // 2. Dynamic path with rejectedTechnicalTiersBySpan and ProgressiveTechnical policy and emphasis
        val input = LayoutInput(
            content = TiqianTextContent(
                text = "Hello World with English Words",
                lineBreakSpans = listOf(
                    LineBreakSpan(range = TextRange(0, 11), policy = LineBreakPolicy.ProgressiveTechnical),
                ),
            ),
            decorations = listOf(
                DecorationSpan(range = TextRange(0, 5), kind = DecorationKind.Emphasis),
                DecorationSpan(range = TextRange(6, 11), kind = DecorationKind.ProperNoun),
            ),
            constraints = LayoutConstraints(maxWidth = 50.0f),
        )
        val annotation = engine.prepareWidthIndependentAnnotation(input, emptyMap())
        val prep = engine.buildParagraphLayoutPrep(
            input,
            annotation,
            rejectedTechnicalTiersBySpan = mapOf(TextRange(0, 11) to setOf(ProgressiveBreakTier.Structural)),
        )
        assertNotNull(prep)

        // 3. Dynamic path via baseShapingStage shapingResults total advance > measure
        val overMeasureInput = LayoutInput(
            content = TiqianTextContent("VeryLongEnglishWordThatExceedsMeasure"),
            constraints = LayoutConstraints(maxWidth = 30.0f),
        )
        val overMeasureAnnotation = engine.prepareWidthIndependentAnnotation(overMeasureInput, emptyMap())
        val overMeasurePrep = engine.buildParagraphLayoutPrep(overMeasureInput, overMeasureAnnotation, emptyMap())
        assertNotNull(overMeasurePrep)
    }

    @Test
    fun conflictingOpenTypeFeaturesThrows() {
        testTrace.section("conflictingOpenTypeFeaturesThrows")
        val customShaper = object : TextShaper {
            override fun shape(input: ShapingInput): ShapingResult {
                val cluster = Cluster(
                    range = input.range,
                    text = input.text.substring(input.range.start, input.range.end),
                    displayText = input.displayText,
                    fontKey = "test",
                    advance = 16.0f,
                )
                val glyph1 = Glyph(id = 1u, clusterRange = input.range, advance = 8.0f, x = 0.0f)
                val glyph2 = Glyph(id = 2u, clusterRange = input.range, advance = 8.0f, x = 8.0f)
                val run1 = GlyphRun(range = input.range, fontKey = "test", glyphs = listOf(glyph1), advance = 8.0f, openTypeFeatures = listOf("feat1"))
                val run2 = GlyphRun(range = input.range, fontKey = "test", glyphs = listOf(glyph2), advance = 8.0f, openTypeFeatures = listOf("feat2"))
                return ShapingResult(clusters = listOf(cluster), glyphRuns = listOf(run1, run2))
            }
        }
        val engine = ExplainableStubParagraphLayoutEngine(textShaper = customShaper)
        val input = LayoutInput(
            content = TiqianTextContent("测试"),
            constraints = LayoutConstraints(maxWidth = 300.0f),
        )
        val annotation = engine.prepareWidthIndependentAnnotation(input, emptyMap())

        val error = assertFailsWith<IllegalArgumentException> {
            engine.buildParagraphLayoutPrep(input, annotation, emptyMap())
        }
        assertTrue(error.message?.contains("Conflicting OpenType features") == true)
    }

    @Test
    fun adjacentInlineObjectBoundariesMergingAndConflicts() {
        testTrace.section("adjacentInlineObjectBoundariesMergingAndConflicts")
        val engine = ExplainableStubParagraphLayoutEngine()
        val text = "一二三四"

        for (uniform1 in listOf(true, false)) {
            for (uniform2 in listOf(true, false)) {
                for (prevent1 in listOf(true, false)) {
                    for (prevent2 in listOf(true, false)) {
                        val obj1 = InlineObjectSpan(
                            range = TextRange(1, 2),
                            advance = 20.0f,
                            ascent = 12.0f,
                            descent = 4.0f,
                            trailingBoundary = InlineObjectBoundaryAdjustment(
                                participatesInUniformStretch = uniform1,
                                preferredStretch = InlineObjectPreferredStretch(
                                    naturalWidth = 10.0f,
                                    targetWidth = 15.0f,
                                    kind = InlineObjectPreferredStretchKind.PunctuationTrailing,
                                ),
                                shrinkCapacity = 2.0f,
                                lineEndDiscardableAdvance = 1.0f,
                                preventsLineBreak = prevent1,
                            ),
                        )
                        val obj2 = InlineObjectSpan(
                            range = TextRange(2, 3),
                            advance = 20.0f,
                            ascent = 12.0f,
                            descent = 4.0f,
                            leadingBoundary = InlineObjectBoundaryAdjustment(
                                participatesInUniformStretch = uniform2,
                                preferredStretch = InlineObjectPreferredStretch(
                                    naturalWidth = 10.0f,
                                    targetWidth = 20.0f,
                                    kind = InlineObjectPreferredStretchKind.PunctuationTrailing,
                                ),
                                preventsLineBreak = prevent2,
                            ),
                        )
                        val input = LayoutInput(
                            content = TiqianTextContent(text),
                            inlineObjects = listOf(obj1, obj2),
                            constraints = LayoutConstraints(maxWidth = 300.0f),
                        )
                        val annotation = engine.prepareWidthIndependentAnnotation(input, emptyMap())
                        val prep = engine.buildParagraphLayoutPrep(input, annotation, emptyMap())
                        assertNotNull(prep)
                    }
                }
            }
        }

        val obj1 = InlineObjectSpan(
            range = TextRange(1, 2),
            advance = 20.0f,
            ascent = 12.0f,
            descent = 4.0f,
            trailingBoundary = InlineObjectBoundaryAdjustment(
                preferredStretch = InlineObjectPreferredStretch(
                    naturalWidth = 10.0f,
                    targetWidth = 15.0f,
                    kind = InlineObjectPreferredStretchKind.PunctuationTrailing,
                ),
            ),
        )
        val conflictingObj2 = InlineObjectSpan(
            range = TextRange(2, 3),
            advance = 20.0f,
            ascent = 12.0f,
            descent = 4.0f,
            leadingBoundary = InlineObjectBoundaryAdjustment(
                preferredStretch = InlineObjectPreferredStretch(
                    naturalWidth = 10.0f,
                    targetWidth = 20.0f,
                    kind = InlineObjectPreferredStretchKind.Relation,
                ),
            ),
        )
        val conflictInput = LayoutInput(
            content = TiqianTextContent(text),
            inlineObjects = listOf(obj1, conflictingObj2),
            constraints = LayoutConstraints(maxWidth = 300.0f),
        )
        val conflictAnnotation = engine.prepareWidthIndependentAnnotation(conflictInput, emptyMap())
        val error = assertFailsWith<IllegalArgumentException> {
            engine.buildParagraphLayoutPrep(conflictInput, conflictAnnotation, emptyMap())
        }
        assertTrue(error.message?.contains("Conflicting inline-object stretch classes") == true)
    }

    @Test
    fun verbatimRangesAndAutoSpaceDecisions() {
        testTrace.section("verbatimRangesAndAutoSpaceDecisions")
        val engine = ExplainableStubParagraphLayoutEngine()
        val text = "中文 English 混排测试 12345"
        val input = LayoutInput(
            content = TiqianTextContent(
                text = text,
                autoSpaceSuppressedRanges = listOf(TextRange(0, 15)),
            ),
            inlineBoxes = listOf(
                InlineBoxSpan(range = TextRange(2, 9), outerSpacing = InlineBoxOuterSpacing.Narrow),
            ),
            constraints = LayoutConstraints(maxWidth = 300.0f),
        )
        val annotation = engine.prepareWidthIndependentAnnotation(input, emptyMap())
        val prep = engine.buildParagraphLayoutPrep(input, annotation, emptyMap())
        assertNotNull(prep)
    }

    @Test
    fun rubySpreadAccumulationAndEdges() {
        testTrace.section("rubySpreadAccumulationAndEdges")
        val engine = ExplainableStubParagraphLayoutEngine()
        val text = "中文测试段落"
        val ruby0 = RubySpan(baseRange = TextRange(0, 2), text = "zhōngwén", kind = RubyKind.Pinyin)
        val ruby1 = RubySpan(baseRange = TextRange(2, 4), text = "cèshìchángdà", kind = RubyKind.Pinyin)
        val ruby2 = RubySpan(baseRange = TextRange(4, 6), text = "duànluòchángdà", kind = RubyKind.Pinyin)
        val rubyInvalid = RubySpan(baseRange = TextRange(99, 100), text = "invalid", kind = RubyKind.Pinyin)

        val input = LayoutInput(
            content = TiqianTextContent(
                text = text,
                sourceBoundaries = setOf(1, 2, 3, 4, 5),
            ),
            rubySpans = listOf(ruby0, ruby1, ruby2, rubyInvalid),
            constraints = LayoutConstraints(maxWidth = 300.0f),
        )
        val annotation = engine.prepareWidthIndependentAnnotation(input, emptyMap())
        val prep = engine.buildParagraphLayoutPrep(input, annotation, emptyMap())
        assertNotNull(prep)
    }

    @Test
    fun shrinkOpportunitiesCoverAllPunctuationClassesAndSpaces() {
        testTrace.section("shrinkOpportunitiesCoverAllPunctuationClassesAndSpaces")
        val nonGbResolver = object : ClreqProfileResolver {
            override fun resolve(profileId: LayoutProfileId): ClreqProfile =
                ClreqProfile.TaiwanHorizontal
        }
        val engine = ExplainableStubParagraphLayoutEngine(clreqProfileResolver = nonGbResolver)
        val text = "「引用」·中点‧间隔•中点，逗号。句号！问号？．点号、顿号以及 English words 间距"
        val spans = text.indices.map { TextSpan(range = TextRange(it, it + 1), style = TextStyle(fontSize = 16.0f)) }
        for (allowInlineStop in listOf(true, false)) {
            for (allowSinoWestern in listOf(true, false)) {
                val input = LayoutInput(
                    content = TiqianTextContent(
                        text = text,
                        spans = spans,
                        sourceBoundaries = text.indices.toSet(),
                    ),
                    inlineObjects = listOf(
                        InlineObjectSpan(
                            range = TextRange(0, 1),
                            advance = 20.0f,
                            ascent = 12.0f,
                            descent = 4.0f,
                            trailingBoundary = InlineObjectBoundaryAdjustment(shrinkCapacity = 5.0f),
                        ),
                    ),
                    constraints = LayoutConstraints(maxWidth = 300.0f),
                )
                val annotation = engine.prepareWidthIndependentAnnotation(input, emptyMap())
                val customProfile = annotation.clreqProfile.copy(
                    adjustment = annotation.clreqProfile.adjustment.copy(
                        allowInlineStopCompression = allowInlineStop,
                        allowSinoWesternGapAdjustment = allowSinoWestern,
                    ),
                )
                val modifiedAnnotation = WidthIndependentParagraphAnnotation(
                    text = annotation.text,
                    fontSize = annotation.fontSize,
                    styleAt = annotation.styleAt,
                    fontSizeAt = annotation.fontSizeAt,
                    bopomofoFontWeightAt = annotation.bopomofoFontWeightAt,
                    rubyFontSize = annotation.rubyFontSize,
                    rubyStackGap = annotation.rubyStackGap,
                    rubyFontWeight = annotation.rubyFontWeight,
                    pinyinSpans = annotation.pinyinSpans,
                    clreqProfile = customProfile,
                    punctuationGlyphSubstitutor = annotation.punctuationGlyphSubstitutor,
                    quotePairs = annotation.quotePairs,
                    roleOverrideInfos = annotation.roleOverrideInfos,
                    fontDecisions = annotation.fontDecisions,
                    clusterRanges = annotation.clusterRanges,
                    fontDecisionByRange = annotation.fontDecisionByRange,
                    inlineObjectByRange = annotation.inlineObjectByRange,
                    segmentShapingCache = annotation.segmentShapingCache,
                    substitutionRollbacks = annotation.substitutionRollbacks,
                    rubyFontGeometryBySpan = annotation.rubyFontGeometryBySpan,
                    baseShapingStage = annotation.baseShapingStage,
                )
                val prep = engine.buildParagraphLayoutPrep(input, modifiedAnnotation, emptyMap())
                assertNotNull(prep)
                assertTrue(prep.shrinkOpportunities.isNotEmpty())
            }
        }
    }

    @Test
    fun styleAtAndEmphasisItalicAtAndDynamicShapingBranches() {
        testTrace.section("styleAtAndEmphasisItalicAtAndDynamicShapingBranches")
        val engine = ExplainableStubParagraphLayoutEngine()
        val text = "English 中文 混排 Latin 测试 样式"
        val spans = listOf(
            TextSpan(range = TextRange(8, 10), style = TextStyle(fontSize = 24.0f)),
        )
        val decorations = listOf(
            DecorationSpan(range = TextRange(0, 7), kind = DecorationKind.Emphasis),
            DecorationSpan(range = TextRange(11, 13), kind = DecorationKind.ProperNoun),
        )
        val lineBreakSpans = listOf(
            LineBreakSpan(range = TextRange(0, 7), policy = LineBreakPolicy.ProgressiveTechnical),
        )
        val input = LayoutInput(
            content = TiqianTextContent(
                text = text,
                spans = spans,
                lineBreakSpans = lineBreakSpans,
            ),
            decorations = decorations,
            constraints = LayoutConstraints(maxWidth = 50.0f),
        )

        // 1. prepareWidthIndependentAnnotation exercises styleAt and emphasisItalicAt
        val annotation = engine.prepareWidthIndependentAnnotation(input, emptyMap())
        assertEquals(24.0f, annotation.fontSizeAt(8))
        assertEquals(24.0f, annotation.fontSizeAt(9))
        assertEquals(input.textStyle.fontSize, annotation.fontSizeAt(-1))
        assertEquals(input.textStyle.fontSize, annotation.fontSizeAt(0))
        assertEquals(input.textStyle.fontSize, annotation.fontSizeAt(7))
        assertEquals(input.textStyle.fontSize, annotation.fontSizeAt(10))
        assertEquals(input.textStyle.fontSize, annotation.fontSizeAt(20))
        assertEquals(input.textStyle.fontSize, annotation.fontSizeAt(100))

        // 2. buildParagraphLayoutPrep with rejectedTechnicalTiersBySpan triggers dynamic shaping and emphasisItalicAt lambda
        val rejected = mapOf(TextRange(0, 7) to setOf(ProgressiveBreakTier.Structural))
        val prep = engine.buildParagraphLayoutPrep(input, annotation, rejected)
        assertNotNull(prep)

        // Line 474 & 475 branch paths
        val noBreakInput = LayoutInput(content = TiqianTextContent("English"), constraints = LayoutConstraints(maxWidth = 500.0f))
        val noBreakAnnotation = engine.prepareWidthIndependentAnnotation(noBreakInput, emptyMap())
        val prepNoDynamic = engine.buildParagraphLayoutPrep(noBreakInput, noBreakAnnotation, emptyMap())
        assertNotNull(prepNoDynamic)

        val smallMeasureInput = LayoutInput(content = TiqianTextContent("English"), constraints = LayoutConstraints(maxWidth = 1.0f))
        val smallAnnotation = engine.prepareWidthIndependentAnnotation(smallMeasureInput, emptyMap())
        val prepSmall = engine.buildParagraphLayoutPrep(smallMeasureInput, smallAnnotation, emptyMap())
        assertNotNull(prepSmall)

        // 3. clusterRoles when fontDecisions is missing some entries (fallback to FontRole.Unknown in line 681)
        val modifiedAnnotation = WidthIndependentParagraphAnnotation(
            text = annotation.text,
            fontSize = annotation.fontSize,
            styleAt = annotation.styleAt,
            fontSizeAt = annotation.fontSizeAt,
            bopomofoFontWeightAt = annotation.bopomofoFontWeightAt,
            rubyFontSize = annotation.rubyFontSize,
            rubyStackGap = annotation.rubyStackGap,
            rubyFontWeight = annotation.rubyFontWeight,
            pinyinSpans = annotation.pinyinSpans,
            clreqProfile = annotation.clreqProfile,
            punctuationGlyphSubstitutor = annotation.punctuationGlyphSubstitutor,
            quotePairs = annotation.quotePairs,
            roleOverrideInfos = annotation.roleOverrideInfos,
            fontDecisions = listOfNotNull(annotation.fontDecisions.firstOrNull()), // Covers first cluster, leaves rest null
            clusterRanges = annotation.clusterRanges,
            fontDecisionByRange = annotation.fontDecisionByRange,
            inlineObjectByRange = annotation.inlineObjectByRange,
            segmentShapingCache = annotation.segmentShapingCache,
            substitutionRollbacks = annotation.substitutionRollbacks,
            rubyFontGeometryBySpan = annotation.rubyFontGeometryBySpan,
            baseShapingStage = annotation.baseShapingStage,
        )
        val prepUnknownRoles = engine.buildParagraphLayoutPrep(input, modifiedAnnotation, emptyMap())
        assertNotNull(prepUnknownRoles)
    }

    @Test
    fun rubySpreadSecondVisitAndZeroFirstCluster() {
        testTrace.section("rubySpreadSecondVisitAndZeroFirstCluster")
        val engine = ExplainableStubParagraphLayoutEngine()
        val text = "一二三四五六七八"
        val ruby0a = RubySpan(baseRange = TextRange(0, 1), text = "chángdàchángdà", kind = RubyKind.Pinyin)
        val ruby0b = RubySpan(baseRange = TextRange(0, 1), text = "chángdàchángdà", kind = RubyKind.Pinyin)
        val ruby1 = RubySpan(baseRange = TextRange(2, 3), text = "chángdàchángdàchángdà", kind = RubyKind.Pinyin)
        val ruby2 = RubySpan(baseRange = TextRange(2, 3), text = "chángdàchángdàchángdà", kind = RubyKind.Pinyin)

        val input = LayoutInput(
            content = TiqianTextContent(
                text = text,
                sourceBoundaries = text.indices.toSet(),
            ),
            rubySpans = listOf(ruby0a, ruby0b, ruby1, ruby2),
            constraints = LayoutConstraints(maxWidth = 300.0f),
        )
        val annotation = engine.prepareWidthIndependentAnnotation(input, emptyMap())
        val prep = engine.buildParagraphLayoutPrep(input, annotation, emptyMap())
        assertNotNull(prep)
    }

    @Test
    fun pairedPunctuationWithZeroCapacity() {
        testTrace.section("pairedPunctuationWithZeroCapacity")
        val engine = ExplainableStubParagraphLayoutEngine()
        val text = "（括号）"
        val input = LayoutInput(
            content = TiqianTextContent(text),
            constraints = LayoutConstraints(maxWidth = 300.0f),
        )
        val annotation = engine.prepareWidthIndependentAnnotation(input, emptyMap())
        val prep = engine.buildParagraphLayoutPrep(input, annotation, emptyMap())
        assertNotNull(prep)
    }

    @Test
    fun dynamicShapingEmphasisItalicAtAndZeroPairedCapacityBranches() {
        testTrace.section("dynamicShapingEmphasisItalicAtAndZeroPairedCapacityBranches")
        val engine = ExplainableStubParagraphLayoutEngine()
        val text = "Hello World Latin"
        val input = LayoutInput(
            content = TiqianTextContent(
                text = text,
                lineBreakSpans = listOf(
                    LineBreakSpan(range = TextRange(0, 17), policy = LineBreakPolicy.ProgressiveTechnical),
                ),
            ),
            decorations = listOf(
                DecorationSpan(kind = DecorationKind.ProperNoun, range = TextRange(0, 5)), // kind != Emphasis
                DecorationSpan(kind = DecorationKind.Emphasis, range = TextRange(6, 11)), // Emphasis at "World"
            ),
            constraints = LayoutConstraints(maxWidth = 100.0f),
        )
        val annotation = engine.prepareWidthIndependentAnnotation(input, emptyMap())
        // Construct annotation with empty segmentShapingCache so dynamic shaping calls shapeSegment and invokes emphasisItalicAt
        val uncachedAnnotation = WidthIndependentParagraphAnnotation(
            text = annotation.text,
            fontSize = annotation.fontSize,
            styleAt = annotation.styleAt,
            fontSizeAt = annotation.fontSizeAt,
            bopomofoFontWeightAt = annotation.bopomofoFontWeightAt,
            rubyFontSize = annotation.rubyFontSize,
            rubyStackGap = annotation.rubyStackGap,
            rubyFontWeight = annotation.rubyFontWeight,
            pinyinSpans = annotation.pinyinSpans,
            clreqProfile = annotation.clreqProfile,
            punctuationGlyphSubstitutor = annotation.punctuationGlyphSubstitutor,
            quotePairs = annotation.quotePairs,
            roleOverrideInfos = annotation.roleOverrideInfos,
            fontDecisions = annotation.fontDecisions,
            clusterRanges = annotation.clusterRanges,
            fontDecisionByRange = annotation.fontDecisionByRange,
            inlineObjectByRange = annotation.inlineObjectByRange,
            segmentShapingCache = emptyMap(),
            substitutionRollbacks = annotation.substitutionRollbacks,
            rubyFontGeometryBySpan = annotation.rubyFontGeometryBySpan,
            baseShapingStage = annotation.baseShapingStage,
        )

        val rejected = mapOf(TextRange(0, 17) to setOf(ProgressiveBreakTier.Structural))
        val prep = engine.buildParagraphLayoutPrep(input, uncachedAnnotation, rejected)
        assertNotNull(prep)
    }

    @Test
    fun centeredPunctBeforeAttachedReferenceKeepsLeadingGlueOnly() {
        testTrace.section("centeredPunctBeforeAttachedReferenceKeepsLeadingGlueOnly")
        // Centered ink makes the middle dot a center-anchored (paired) atom
        // with symmetric glue. The attached-inline boundary consumes its
        // trailing glue in full, so the paired capacity degenerates to
        // 2 * min(leading, 0) = 0 and the cluster must not add a
        // LeadingAndTrailingGlue shrink opportunity.
        val text = "正文：“内容\u00b7[1]，后文"
        val attachAt = text.indexOf("[1]")
        val result = ExplainableStubParagraphLayoutEngine(textShaper = narrowInkShaper()).layout(
            LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic.Zero),
                content = TiqianTextContent(
                    text = text,
                    spans = listOf(
                        TextSpan(
                            range = TextRange(attachAt, attachAt + 3),
                            style = TextStyle(inlineAttachment = InlineAttachment.Previous),
                        ),
                    ),
                ),
                constraints = LayoutConstraints(maxWidth = 320.0f),
            ),
        )
        val boundary = result.debug.spacingDecisions.single {
            it.reason.startsWith("AttachedInlineVirtualPunctuationBoundary")
        }
        assertEquals("AttachedInlineVirtualPunctuationBoundary:adjacent-punctuation", boundary.reason)
        assertEquals('\u00b7', boundary.leftChar)
        assertEquals('，', boundary.rightChar)
        assertTrue(boundary.naturalInnerGlue > 0.0f)
        // The half-em reduction on an adjacent-punctuation boundary removes
        // the dot's entire trailing side.
        assertTrue(boundary.reduction > 0.0f)
        assertEquals(text.indexOf('\u00b7'), boundary.reductionTargetRange.start)
    }

    private fun narrowInkShaper(): TextShaper = object : TextShaper {
        private val delegate = ExplainableStubTextShaper()
        override fun shape(input: ShapingInput): ShapingResult {
            val res = delegate.shape(input)
            return res.copy(
                glyphRuns = res.glyphRuns.map { run ->
                    run.copy(glyphs = run.glyphs.map { it.copy(bounds = Rect(left = 4.0f, top = 2.0f, right = 12.0f, bottom = 10.0f)) })
                },
            )
        }
    }

    @AfterTest
    fun flushTestTrace() {
        testTrace.flush()
    }
}
