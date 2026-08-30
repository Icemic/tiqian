package org.tiqian.trace

import org.tiqian.core.Ic
import org.tiqian.core.LayoutConstraints
import org.tiqian.core.LayoutInput
import org.tiqian.core.ParagraphStyle
import org.tiqian.core.TiqianTextContent
import org.tiqian.layout.ExplainableStubParagraphLayoutEngine
import org.tiqian.layout.QuotePairAnalyzer
import org.tiqian.test.trace.TraceFormat
import org.tiqian.test.trace.TraceFormat.fd
import org.tiqian.test.trace.TraceFormat.i
import org.tiqian.test.trace.TraceRecorder

/**
 * Process-trace scenarios for the layout cluster: the contextual quote
 * role analyzer over paired, nested, and unmatched quotes, and the stub
 * engine's full pipeline (shaping through adjustment) over fixed inputs.
 * Widths use two decimals; all non-ASCII inputs are written as escapes.
 */
internal object LayoutTraceScenarios {

    val all: List<TraceScenario> = listOf(
        quoteRoleAnalyzer(),
        stubPipeline(),
    )

    private fun header(id: String): String = "scenario: $id\n"

    private fun quoteRoleAnalyzer(): TraceScenario = TraceScenario(
        id = "layout.quote-role-analyzer",
        notes = "QuotePairAnalyzer pairing and contextual role classification over nested and unmatched quotes",
    ) {
        val t = TraceRecorder()
        val analyzer = QuotePairAnalyzer()
        val texts = listOf(
            "nested-cjk" to "\u4ED6\u8BF4\uFF1A\u201C\u5979\u8BF4\u2018\u4F60\u597D\u2019\u3002\u201D",
            "nested-latin-inner" to "\u4ED6\u8BF4\uFF1A\u201Chello\u201D",
            "unmatched-right-single-latin" to "abc\u2019def",
            "unmatched-right-double" to "abc\u201D",
            "unmatched-left-double" to "\u201Cabc",
            "unmatched-left-single" to "\u2018abc",
            "conflicting-scripts" to "\u03B1\u2019\u4E2D",
            "surrogate-content" to "\uD83D\uDE00\u2019\u4E2D",
            "supplementary-pairs" to "\uD83D\uDE00\u201C\uD83D\uDE00\u201D",
        )
        for ((label, text) in texts) {
            val pairs = analyzer.analyze(text)
            for (pair in pairs) {
                t.event(
                    "pair", "case" to label,
                    "open" to i(pair.openIndex), "close" to i(pair.closeIndex),
                    "type" to pair.quoteType.name,
                )
            }
            if (pairs.isEmpty()) {
                t.event("pair", "case" to label, "none" to true)
            }
            for (decision in analyzer.classifyQuoteRoles(text, pairs)) {
                t.event(
                    "role", "case" to label,
                    "index" to i(decision.index), "role" to decision.role.name,
                    "source" to decision.source, "reason" to decision.reason,
                )
            }
        }
        header("layout.quote-role-analyzer") + t.text()
    }

    private fun stubPipeline(): TraceScenario = TraceScenario(
        id = "engine.stub-pipeline",
        notes = "ExplainableStubParagraphLayoutEngine end to end: single line, mixed-script wrapping, bracket-heavy line ends",
    ) {
        val t = TraceRecorder()
        val engine = ExplainableStubParagraphLayoutEngine()
        val cases = listOf(
            Triple("single-line", "\u63D0\u6920", 240.0f),
            Triple("mixed-wrap", "\u63D0\u6920\u2026\u2026English\u2014\u2014\u4E16\u754C\u3002", 120.0f),
            Triple("bracket-ends", "\u201C\u63D0\u6920\u201D\uFF08Tiqian\uFF09\u3002", 100.0f),
        )
        for ((label, text, width) in cases) {
            val result = engine.layout(
                LayoutInput(
                    paragraphStyle = ParagraphStyle(firstLineIndent = Ic(0.0f)),
                    content = TiqianTextContent(text),
                    constraints = LayoutConstraints(maxWidth = width),
                ),
            )
            t.event(
                "layout", "case" to label,
                "w" to fd(result.size.width, 2), "h" to fd(result.size.height, 2),
                "clusters" to i(result.clusters.size), "lines" to i(result.lines.size),
            )
            for ((index, line) in result.lines.withIndex()) {
                t.event(
                    "line", "case" to label, "i" to i(index),
                    "start" to i(line.range.start), "end" to i(line.range.end),
                    "end-reason" to line.endReason.name,
                    "indent" to fd(line.indent, 2),
                    "natural" to fd(line.naturalWidth, 2), "visual" to fd(line.visualWidth, 2),
                    "hanging" to fd(line.hangingPunctuationAdvance, 2),
                    "hyphen" to fd(line.hyphenAdvance, 2),
                )
            }
            for ((index, decision) in result.debug.lineDecisions.withIndex()) {
                t.event(
                    "line-decision", "case" to label, "i" to i(index),
                    "kind" to decision.kind, "repair" to decision.repair,
                )
            }
            for ((index, justification) in result.debug.justificationDecisions.withIndex()) {
                t.event(
                    "justification", "case" to label, "i" to i(index),
                    "start" to i(justification.lineRange.start), "end" to i(justification.lineRange.end),
                    "deficit-before" to fd(justification.deficitBefore, 2),
                    "deficit-after" to fd(justification.deficitAfter, 2),
                    "allocations" to i(justification.allocations.size),
                )
            }
        }
        header("engine.stub-pipeline") + t.text()
    }
}
