package org.tiqian.compose

import org.tiqian.core.LayoutConstraints
import org.tiqian.core.LayoutInput
import org.tiqian.core.TextRange
import org.tiqian.core.TextStyle
import org.tiqian.core.TiqianTextContent
import org.tiqian.font.FontCandidate
import org.tiqian.font.FontDecision
import org.tiqian.font.FontMetricsRequest
import org.tiqian.font.FontMetricsResolver
import org.tiqian.font.FontRole
import org.tiqian.font.RawFontMetrics
import org.tiqian.font.StubFontMetricsResolver
import org.tiqian.layout.ExplainableStubParagraphLayoutEngine
import org.tiqian.shaping.ExplainableStubTextShaper
import org.tiqian.shaping.ShapingInput
import org.tiqian.shaping.TextShaper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ComposeBackendCacheTest {

    @Test
    fun exactShapingInputIsReusedAndTypographyChangeMisses() {
        var calls = 0
        val delegate = ExplainableStubTextShaper()
        val cache = BoundedComposeTextShaperCache(
            object : TextShaper {
                override fun shape(input: ShapingInput) = delegate.shape(input).also { calls += 1 }
            },
        )
        val input = shapingInput()

        cache.shape(input)
        cache.shape(input.copy())
        assertEquals(1, calls)

        cache.shape(input.copy(style = input.style.copy(fontSize = 22f)))
        assertEquals(2, calls, "typography must remain part of the shaping cache key")
    }

    @Test
    fun shapingCacheEvictsAtItsConfiguredBound() {
        var calls = 0
        val delegate = ExplainableStubTextShaper()
        val cache = BoundedComposeTextShaperCache(
            object : TextShaper {
                override fun shape(input: ShapingInput) = delegate.shape(input).also { calls += 1 }
            },
            maxEntries = 1,
        )
        val first = shapingInput()
        val second = first.copy(text = "文", displayText = "文")

        cache.shape(first)
        cache.shape(second)
        cache.shape(first)

        assertEquals(3, calls, "the oldest shaping entry must be evicted once the bound is full")
    }

    @Test
    fun measurementSessionSharesCompletedShapingAcrossMeasurers() {
        var firstCalls = 0
        var secondCalls = 0
        val session = ParagraphMeasurementSession(shapingCacheEntries = 8)
        val delegate = ExplainableStubTextShaper()
        val first = BoundedComposeTextShaperCache(
            delegate = object : TextShaper {
                override fun shape(input: ShapingInput) = delegate.shape(input).also { firstCalls += 1 }
            },
            sharedCache = session.shapingCache,
        )
        val second = BoundedComposeTextShaperCache(
            delegate = object : TextShaper {
                override fun shape(input: ShapingInput) = delegate.shape(input).also { secondCalls += 1 }
            },
            sharedCache = session.shapingCache,
        )

        first.shape(shapingInput())
        second.shape(shapingInput().copy())

        assertEquals(1, firstCalls)
        assertEquals(0, secondCalls, "a foreground measurer must consume the worker's shaping result")
    }

    @Test
    fun widthReflowReusesShapingAndFontMetricsButStillRebreaks() {
        var shapingCalls = 0
        var metricsCalls = 0
        val shapingDelegate = ExplainableStubTextShaper()
        val metricsDelegate = StubFontMetricsResolver()
        val engine = ExplainableStubParagraphLayoutEngine(
            textShaper = BoundedComposeTextShaperCache(
                object : TextShaper {
                    override fun shape(input: ShapingInput) = shapingDelegate.shape(input).also {
                        shapingCalls += 1
                    }
                },
            ),
            fontMetricsResolver = BoundedComposeFontMetricsCache(
                object : FontMetricsResolver {
                    override fun resolve(request: FontMetricsRequest): RawFontMetrics =
                        metricsDelegate.resolve(request).also { metricsCalls += 1 }
                },
            ),
        )
        val input = LayoutInput(
            content = TiqianTextContent("宽度变化仍然重新断行，但不应重复塑形和读取字体度量。"),
            textStyle = TextStyle(fontSize = 20f),
            constraints = LayoutConstraints(maxWidth = 220f),
        )

        val wide = engine.layout(input)
        val coldShapingCalls = shapingCalls
        val coldMetricsCalls = metricsCalls
        val narrow = engine.layout(input.copy(constraints = LayoutConstraints(maxWidth = 140f)))

        assertNotEquals(
            wide.lines.map { it.range },
            narrow.lines.map { it.range },
            "the width-dependent stages must still produce a fresh line solution",
        )
        assertEquals(coldShapingCalls, shapingCalls, "rebreak must reuse exact cluster shaping")
        assertEquals(coldMetricsCalls, metricsCalls, "rebreak must reuse exact face metrics")
    }

    private fun shapingInput(): ShapingInput {
        val range = TextRange(0, 1)
        val role = FontRole.CjkText
        return ShapingInput(
            text = "中",
            range = range,
            style = TextStyle(fontSize = 20f),
            fontDecision = FontDecision(
                range = range,
                candidate = FontCandidate("cjk-primary", "cjk-primary", role),
                role = role,
                reason = "test",
            ),
        )
    }
}
