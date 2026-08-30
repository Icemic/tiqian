package org.tiqian.layout

import org.tiqian.core.LayoutConstraints
import org.tiqian.core.LayoutInput
import org.tiqian.core.ParagraphStyle
import org.tiqian.core.TiqianTextContent
import org.tiqian.shaping.jvm.AwtTextShaper
import kotlin.system.measureNanoTime
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A paragraph whose content is one giant unbreakable ASCII token — the shape of a 100KB TeX
 * pixel-art source demoted to prose — must lay out in near-linear time. Planning used to be
 * quadratic on this shape (per-range cluster scans, per-boundary decision scans, per-break
 * unbreakable-range scans) and a real 106KB Zhihu formula ANR'd the app; see UnbreakableRanges
 * and clusterIndexRangeFor. The ceiling is generous against CI noise but far below the
 * quadratic behavior it guards against.
 */
class GiantTokenScalingProbe {

    private fun giantTokenInput(length: Int): LayoutInput {
        val unit = "\\rlap{\\color{#BB9}{\\rule{4px}{320px}}}{"
        val token = buildString(length + unit.length) {
            while (this.length < length) append(unit)
        }.substring(0, length)
        return LayoutInput(
            content = TiqianTextContent(token),
            constraints = LayoutConstraints(maxWidth = 1248f),
            paragraphStyle = ParagraphStyle(),
        )
    }

    @Test
    fun giantTokenLayoutStaysFarBelowTheQuadraticCeiling() {
        val engine = ExplainableStubParagraphLayoutEngine(
            lineBreaker = GreedyLineBreaker(),
            textShaper = AwtTextShaper(),
        )
        val input = giantTokenInput(80_000)
        engine.layout(input)
        val warmMs = (1..3).minOf { measureNanoTime { engine.layout(input) } } / 1_000_000.0
        println("giant-token 80k warm layout: $warmMs ms")
        assertTrue(
            warmMs < 1_500.0,
            "80k single-token layout took $warmMs ms; the quadratic planning regression is back",
        )
    }

    @Test
    fun measureGiantTokenScalingMatrix() {
        if (System.getenv("TIQIAN_RUN_EXPERIMENTS") != "1") {
            println("GiantTokenScalingProbe: set TIQIAN_RUN_EXPERIMENTS=1 to run the matrix.")
            return
        }
        for (breaker in listOf("lookahead", "greedy")) {
            for (length in listOf(5_000, 10_000, 20_000, 40_000, 80_000)) {
                val engine = ExplainableStubParagraphLayoutEngine(
                    lineBreaker = if (breaker == "lookahead") LookaheadLineBreaker() else GreedyLineBreaker(),
                    textShaper = AwtTextShaper(),
                    annotationCache = LruWidthIndependentAnnotationCache(maxEntries = 8),
                )
                val input = giantTokenInput(length)
                repeat(if (length <= 10_000) 2 else 1) { engine.layout(input) }
                val warm = (1..3).minOf { measureNanoTime { engine.layout(input) } }
                val cold = measureNanoTime {
                    engine.annotationCache.clear()
                    engine.layout(input)
                }
                println(
                    "giant-token[$breaker] length=$length  warm=${warm / 1_000_000.0} ms  " +
                        "cold=${cold / 1_000_000.0} ms",
                )
            }
        }
    }
}
