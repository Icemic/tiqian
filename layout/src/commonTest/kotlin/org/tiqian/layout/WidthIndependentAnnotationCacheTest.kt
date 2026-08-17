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
        val testText = "提椠是一个面向中文正文的段落排版引擎，遵循中文排版需求规范，支持两端对齐与标点挤压。"
        val cachedEngine = ExplainableStubParagraphLayoutEngine(
            annotationCache = LruWidthIndependentAnnotationCache(),
        )

        val testWidths = listOf(80f, 120f, 160f, 240f, 320f, 480f)
        for (width in testWidths) {
            val uncachedEngine = ExplainableStubParagraphLayoutEngine(
                annotationCache = object : WidthIndependentAnnotationCache {
                    override fun get(key: WidthIndependentAnnotationKey): Any? = null
                    override fun put(key: WidthIndependentAnnotationKey, annotation: Any) {}
                    override fun clear() {}
                    override val size: Int = 0
                },
            )
            val input = LayoutInput(
                paragraphStyle = ParagraphStyle(firstLineIndent = Ic(2f)),
                content = TiqianTextContent(testText),
                constraints = LayoutConstraints(maxWidth = width),
            )

            val expected = uncachedEngine.layout(input)
            val actual = cachedEngine.layout(input)

            assertEquals(expected.lines.size, actual.lines.size, "Line count mismatch at width $width")
            for (i in expected.lines.indices) {
                assertEquals(expected.lines[i].range, actual.lines[i].range, "Line $i range mismatch at width $width")
                assertEquals(expected.lines[i].visualWidth, actual.lines[i].visualWidth, 0.01f, "Line $i width mismatch at width $width")
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
