package org.tiqian.trace

import org.tiqian.clreq.ClreqPunctuationAdvancePolicy
import org.tiqian.core.RichTextBackgroundDrawStyle
import org.tiqian.core.RichTextBackgroundPaint
import org.tiqian.core.RichTextLinePattern
import org.tiqian.core.RichTextLineSegment
import org.tiqian.core.RichTextPaint
import org.tiqian.core.RichTextRole
import org.tiqian.core.RichTextSpan
import org.tiqian.core.TextRange
import org.tiqian.core.resolvedBackgroundCornerRadii
import org.tiqian.test.trace.TraceRecorder

/**
 * Seed corpus for the Haxe port test lane. Every scenario body is written
 * so the same statements translate into the Haxe test tree; the golden
 * files under `golden/process-traces/` are the byte-identical reference.
 */
object TraceScenarios {

    val all: List<TraceScenario> = listOf(
        cornerRadii(),
        richTextPaintValidation(),
        punctuationAdvance(),
    ) + ClreqTraceScenarios.all + FontTraceScenarios.all +
        LinebreakTraceScenarios.all + CoreTraceScenarios.all + ShapingTraceScenarios.all +
        LayoutTraceScenarios.all

    private fun header(id: String): String = "scenario: $id\n"

    private fun cornerRadii(): TraceScenario = TraceScenario(
        id = "core.corner-radii",
        notes = "resolvedBackgroundCornerRadii over closed, continued, inset-clamped, and square segments",
    ) {
        val t = TraceRecorder()
        fun segment(
            spanStart: Int,
            spanEnd: Int,
            segStart: Int,
            segEnd: Int,
            width: Float,
            height: Float,
            paint: RichTextBackgroundPaint,
        ) = RichTextLineSegment(
            span = RichTextSpan(
                range = TextRange(spanStart, spanEnd),
                role = RichTextRole.Background,
                paint = RichTextPaint(background = paint),
            ),
            lineIndex = 0,
            range = TextRange(segStart, segEnd),
            left = 0.0f,
            top = 0.0f,
            right = width,
            bottom = height,
            baseline = height,
        )

        val cases = listOf(
            "closed" to segment(0, 8, 0, 8, 120.0f, 40.0f, RichTextBackgroundPaint(cornerRadius = 10.0f)),
            "lead-cont" to segment(0, 8, 4, 8, 120.0f, 40.0f, RichTextBackgroundPaint(cornerRadius = 10.0f, continuationCornerRadius = 3.0f)),
            "trail-cont" to segment(0, 8, 0, 4, 120.0f, 40.0f, RichTextBackgroundPaint(cornerRadius = 10.0f, continuationCornerRadius = 3.0f)),
            "mid-cont" to segment(0, 8, 2, 6, 120.0f, 40.0f, RichTextBackgroundPaint(cornerRadius = 10.0f, continuationCornerRadius = 3.0f)),
            "clamp" to segment(0, 8, 0, 8, 20.0f, 8.0f, RichTextBackgroundPaint(cornerRadius = 90.0f)),
        )
        for ((label, seg) in cases) {
            val r = seg.resolvedBackgroundCornerRadii()
            t.event(
                "radii", "case" to label, "tl" to r.topLeft, "tr" to r.topRight,
                "br" to r.bottomRight, "bl" to r.bottomLeft,
                "square" to r.isSquare, "uniform" to r.isUniform,
            )
        }
        val inset = cases[0].second.resolvedBackgroundCornerRadii(inset = 4.0f)
        t.event(
            "radii-inset", "tl" to inset.topLeft, "tr" to inset.topRight,
            "br" to inset.bottomRight, "bl" to inset.bottomLeft,
            "square" to inset.isSquare, "uniform" to inset.isUniform,
        )
        header("core.corner-radii") + t.text()
    }

    private fun richTextPaintValidation(): TraceScenario = TraceScenario(
        id = "core.rich-text-paint-validation",
        notes = "constructor guards of RichTextLinePattern, draw styles, and background paint",
    ) {
        val t = TraceRecorder()
        fun rejected(action: () -> Unit): Boolean =
            try {
                action()
                false
            } catch (e: IllegalArgumentException) {
                true
            }

        t.event("pattern", "kind" to "dashed", "stroke" to 1.0f, "dash" to 6.0f, "gap" to 3.0f, "rejected" to rejected {
            RichTextLinePattern.Dashed(strokeWidth = 1.0f, dashLength = 6.0f, gapLength = 3.0f)
        })
        t.event("pattern", "kind" to "dashed", "stroke" to 0.0f, "dash" to 6.0f, "gap" to 3.0f, "rejected" to rejected {
            RichTextLinePattern.Dashed(strokeWidth = 0.0f, dashLength = 6.0f, gapLength = 3.0f)
        })
        t.event("pattern", "kind" to "dashed", "stroke" to 1.0f, "dash" to Float.NaN, "gap" to 3.0f, "rejected" to rejected {
            RichTextLinePattern.Dashed(strokeWidth = 1.0f, dashLength = Float.NaN, gapLength = 3.0f)
        })
        t.event("pattern", "kind" to "dotted", "dot" to 2.0f, "gap" to 4.0f, "rejected" to rejected {
            RichTextLinePattern.Dotted(dotDiameter = 2.0f, gapLength = 4.0f)
        })
        t.event("pattern", "kind" to "dotted", "dot" to -1.0f, "gap" to 4.0f, "rejected" to rejected {
            RichTextLinePattern.Dotted(dotDiameter = -1.0f, gapLength = 4.0f)
        })

        t.event("draw-style", "kind" to "border", "stroke" to 2.0f, "rejected" to rejected {
            RichTextBackgroundDrawStyle.Border(strokeWidth = 2.0f)
        })
        t.event("draw-style", "kind" to "border", "stroke" to 0.0f, "rejected" to rejected {
            RichTextBackgroundDrawStyle.Border(strokeWidth = 0.0f)
        })
        t.event("draw-style", "kind" to "border", "stroke" to Float.POSITIVE_INFINITY, "rejected" to rejected {
            RichTextBackgroundDrawStyle.Border(strokeWidth = Float.POSITIVE_INFINITY)
        })

        t.event("background", "pad" to -1.0f, "rejected" to rejected {
            RichTextBackgroundPaint(horizontalPadding = -1.0f)
        })
        t.event("background", "pad" to 2.0f, "rejected" to rejected {
            RichTextBackgroundPaint(horizontalPadding = 2.0f)
        })
        t.event("clearance", "value" to -0.5f, "rejected" to rejected {
            RichTextPaint(adjacentSameStyleClearance = -0.5f)
        })
        t.event("clearance", "value" to 1.5f, "rejected" to rejected {
            RichTextPaint(adjacentSameStyleClearance = 1.5f)
        })
        header("core.rich-text-paint-validation") + t.text()
    }

    private fun punctuationAdvance(): TraceScenario = TraceScenario(
        id = "clreq.punctuation-advance",
        notes = "ClreqPunctuationAdvancePolicy over substitution pairs, BMP, and supplementary-plane text",
    ) {
        val t = TraceRecorder()
        val cases = listOf(
            "two-em-dash-display" to ("——" to "⸺"),
            "two-em-dash-source" to ("⸺" to "⋯"),
            "ellipsis" to ("…" to "⋯"),
            "han" to ("中" to "中"),
            "pause" to ("，" to "，"),
            "supplementary-single" to ("𝐀" to "𝐀"),
            "mixed-bmp-supplementary" to ("中𝐀文" to "中𝐀文"),
            "empty" to ("" to ""),
        )
        for ((label, pair) in cases) {
            val (source, display) = pair
            t.event(
                "advance-em", "case" to label, "source" to source, "display" to display,
                "em" to ClreqPunctuationAdvancePolicy.advanceEm(source, display),
            )
        }
        header("clreq.punctuation-advance") + t.text()
    }
}
