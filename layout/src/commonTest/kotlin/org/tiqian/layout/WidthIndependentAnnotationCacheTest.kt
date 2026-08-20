package org.tiqian.layout

import org.tiqian.core.DecorationKind
import org.tiqian.core.DecorationSpan
import org.tiqian.core.Ic
import org.tiqian.core.InlineBoxSpan
import org.tiqian.core.LayoutConstraints
import org.tiqian.core.LayoutInput
import org.tiqian.core.ParagraphStyle
import org.tiqian.core.RubyKind
import org.tiqian.core.RubySpan
import org.tiqian.core.TextRange
import org.tiqian.core.TextStyle
import org.tiqian.core.TiqianTextContent
import org.tiqian.shaping.ExplainableStubTextShaper
import org.tiqian.shaping.ShapingInput
import org.tiqian.shaping.ShapingResult
import org.tiqian.shaping.TextShaper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class WidthIndependentAnnotationCacheTest {

    private class CountingTextShaper(private val delegate: TextShaper = ExplainableStubTextShaper()) : TextShaper {
        var shapeCallCount: Int = 0
            private set

        override fun shape(input: ShapingInput): ShapingResult {
            shapeCallCount += 1
            return delegate.shape(input)
        }
    }

    @Test
    fun relayoutWithDifferentWidthHitsCacheAndSkipsShaper() {
        val shaper = CountingTextShaper()
        val cache = LruWidthIndependentAnnotationCache(maxEntries = 64)
        val engine = ExplainableStubParagraphLayoutEngine(
            textShaper = shaper,
            annotationCache = cache,
        )

        val inputWidth1 = LayoutInput(
            paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0f)),
            content = TiqianTextContent("提椠是一个面向中文正文的 CJK 段落布局引擎。"),
            constraints = LayoutConstraints(maxWidth = 300f),
        )

        assertEquals(0, cache.size)
        assertEquals(0, shaper.shapeCallCount)

        // 1. Initial layout at 300px
        val result1 = engine.layout(inputWidth1)
        assertTrue(result1.lines.isNotEmpty())
        assertEquals(1, cache.size)
        val initialShapeCalls = shaper.shapeCallCount
        assertTrue(initialShapeCalls > 0, "Initial layout must shape segments")

        // 2. Resize to 180px - should hit cache and NOT invoke text shaper
        val inputWidth2 = inputWidth1.copy(constraints = LayoutConstraints(maxWidth = 180f))
        val result2 = engine.layout(inputWidth2)
        assertEquals(initialShapeCalls, shaper.shapeCallCount, "Relayout at new width must reuse cached annotation without shaping")
        assertEquals(1, cache.size)

        // 3. Resize to 500px - should hit cache again
        val inputWidth3 = inputWidth1.copy(constraints = LayoutConstraints(maxWidth = 500f))
        val result3 = engine.layout(inputWidth3)
        assertEquals(initialShapeCalls, shaper.shapeCallCount, "Relayout at third width must also reuse cached annotation")

        // 4. Verify widths affected line breaks as expected
        assertTrue(result2.lines.size >= result1.lines.size, "Narrower width should have at least as many lines")
        assertTrue(result1.lines.size >= result3.lines.size, "Wider width should have fewer or equal lines")
    }

    @Test
    fun cachedAndUncachedEnginesProduceIdenticalLayoutResultsAcrossWidths() {
        val testFixtures = listOf(
            "提椠是一个面向中文正文的段落排版引擎，遵循中文排版需求规范，支持两端对齐与标点挤压。",
            "在《中文排版需求》（CLREQ）中，要求正文「两端对齐」；当遇到『标点符号』与西文（如 OpenType / CSS Grid）混排时，应正确执行挤压与推入推出——即使在 120Hz 高频拖拽下也是如此！",
            "第一行缩进两个字身框。标点符号如……省略号、破折号——不应出现在行首，逗号、句号。也不得出现在行首。这就是避头尾（Kinsoku）规则的严格要求。",
        )
        val cachedEngine = ExplainableStubParagraphLayoutEngine(
            annotationCache = LruWidthIndependentAnnotationCache(),
        )
        val uncachedEngine = ExplainableStubParagraphLayoutEngine(
            annotationCache = object : WidthIndependentAnnotationCache {
                override fun get(key: WidthIndependentAnnotationKey): Any? = null
                override fun put(key: WidthIndependentAnnotationKey, annotation: Any) {}
                override fun clear() {}
                override val size: Int = 0
            },
        )

        // Continuous fractional width sweep from 80px to 650px (simulating real slider drag)
        val sweepWidths = generateSequence(80f) { if (it + 7.3f <= 650f) it + 7.3f else null }.toList()
        for (fixture in testFixtures) {
            for (width in sweepWidths) {
                val input = LayoutInput(
                    paragraphStyle = ParagraphStyle(firstLineIndent = Ic(2f)),
                    content = TiqianTextContent(fixture),
                    constraints = LayoutConstraints(maxWidth = width),
                )

                val expected = uncachedEngine.layout(input)
                val actual = cachedEngine.layout(input)

                assertEquals(expected.lines.size, actual.lines.size, "Line count mismatch for fixture at width $width")
                for (i in expected.lines.indices) {
                    assertEquals(expected.lines[i].range, actual.lines[i].range, "Line $i range mismatch at width $width")
                    assertEquals(expected.lines[i].visualWidth, actual.lines[i].visualWidth, 0.001f, "Line $i visualWidth mismatch at width $width")
                    assertEquals(expected.lines[i].adjustedWidth, actual.lines[i].adjustedWidth, 0.001f, "Line $i adjustedWidth mismatch at width $width")
                    assertEquals(expected.lines[i].naturalWidth, actual.lines[i].naturalWidth, 0.001f, "Line $i naturalWidth mismatch at width $width")
                    assertEquals(expected.lines[i].indent, actual.lines[i].indent, 0.001f, "Line $i indent mismatch at width $width")
                    assertEquals(expected.lines[i].hangingPunctuationAdvance, actual.lines[i].hangingPunctuationAdvance, 0.001f, "Line $i hanging mismatch at width $width")
                    assertEquals(expected.lines[i].endReason, actual.lines[i].endReason, "Line $i endReason mismatch at width $width")
                }
            }
        }
    }

    @Test
    fun reflowFuzzingRandomSequenceProducesExactOutput() {
        val fixture = "提椠段落排版：严格遵循简体中文 CLREQ 规范。包含“双引号”、‘单引号’、以及（括号）与【括号】；汉字与 English words 混排时自动添加 0.25em 间距，最后一行保持左对齐。"
        val cachedEngine = ExplainableStubParagraphLayoutEngine(
            annotationCache = LruWidthIndependentAnnotationCache(),
        )
        val uncachedEngine = ExplainableStubParagraphLayoutEngine(
            annotationCache = object : WidthIndependentAnnotationCache {
                override fun get(key: WidthIndependentAnnotationKey): Any? = null
                override fun put(key: WidthIndependentAnnotationKey, annotation: Any) {}
                override fun clear() {}
                override val size: Int = 0
            },
        )

        // Pseudo-random pseudo-dragging width sequence bouncing between narrow and wide
        val randomSequenceWidths = listOf(
            320f, 150f, 480.5f, 95.2f, 210f, 600f, 120.3f, 450f, 180.7f, 300f,
            75f, 520f, 133.3f, 266.6f, 399.9f, 110f, 470f, 195f, 345f, 580f,
        )
        for (width in randomSequenceWidths) {
            val input = LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(2f)),
                content = TiqianTextContent(fixture),
                constraints = LayoutConstraints(maxWidth = width),
            )
            val expected = uncachedEngine.layout(input)
            val actual = cachedEngine.layout(input)

            assertEquals(expected.lines.size, actual.lines.size, "Fuzz line count mismatch at width $width")
            for (i in expected.lines.indices) {
                assertEquals(expected.lines[i].range, actual.lines[i].range, "Fuzz line $i range mismatch at width $width")
                assertEquals(expected.lines[i].visualWidth, actual.lines[i].visualWidth, 0.001f, "Fuzz line $i width mismatch at width $width")
            }
        }
    }

    @Test
    fun cacheKeyDistinguishesTypographyDecorationsAndSpans() {
        val cache = LruWidthIndependentAnnotationCache()
        val engine = ExplainableStubParagraphLayoutEngine(annotationCache = cache)

        val baseInput = LayoutInput(
            content = TiqianTextContent("中西混合排版与测试文本。"),
            constraints = LayoutConstraints(maxWidth = 300f),
        )
        engine.layout(baseInput)
        assertEquals(1, cache.size)

        // 1. Text change
        val textChanged = baseInput.copy(content = TiqianTextContent("中西混合排版与变动文本。"))
        engine.layout(textChanged)
        assertEquals(2, cache.size)

        // 2. Font size change
        val fontChanged = baseInput.copy(textStyle = TextStyle(fontSize = 24f))
        engine.layout(fontChanged)
        assertEquals(3, cache.size)

        // 3. Emphasis decoration change
        val emphasisChanged = baseInput.copy(
            decorations = listOf(
                DecorationSpan(range = TextRange(0, 4), kind = DecorationKind.Emphasis),
            ),
        )
        engine.layout(emphasisChanged)
        assertEquals(4, cache.size)

        // 4. Ruby change
        val rubyChanged = baseInput.copy(
            rubySpans = listOf(
                RubySpan(baseRange = TextRange(0, 2), text = "zhōngxī", kind = RubyKind.Pinyin),
            ),
        )
        engine.layout(rubyChanged)
        assertEquals(5, cache.size)

        // 5. Inline box change
        val inlineBoxChanged = baseInput.copy(
            inlineBoxes = listOf(
                InlineBoxSpan(range = TextRange(2, 4), inlineStart = 4f, inlineEnd = 4f),
            ),
        )
        engine.layout(inlineBoxChanged)
        assertEquals(6, cache.size)
    }

    @Test
    fun lruCacheEvictsOldestEntriesWhenCapacityExceeded() {
        val cache = LruWidthIndependentAnnotationCache(maxEntries = 2)
        val engine = ExplainableStubParagraphLayoutEngine(annotationCache = cache)

        val input1 = LayoutInput(content = TiqianTextContent("段落一文本内容"), constraints = LayoutConstraints(maxWidth = 300f))
        val input2 = LayoutInput(content = TiqianTextContent("段落二文本内容"), constraints = LayoutConstraints(maxWidth = 300f))
        val input3 = LayoutInput(content = TiqianTextContent("段落三文本内容"), constraints = LayoutConstraints(maxWidth = 300f))

        engine.layout(input1)
        engine.layout(input2)
        assertEquals(2, cache.size)

        val key1 = input1.toWidthIndependentAnnotationKey()
        val key2 = input2.toWidthIndependentAnnotationKey()
        assertTrue(cache.get(key1) != null)
        assertTrue(cache.get(key2) != null)

        // Insert third -> key1 should be evicted
        engine.layout(input3)
        assertEquals(2, cache.size)
        val key3 = input3.toWidthIndependentAnnotationKey()
        assertTrue(cache.get(key3) != null)
        assertTrue(cache.get(key2) != null)
        assertEquals(null, cache.get(key1), "Oldest entry key1 should be evicted")
    }
}
