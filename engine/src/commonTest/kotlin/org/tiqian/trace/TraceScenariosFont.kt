package org.tiqian.trace

import org.tiqian.core.TextRange
import org.tiqian.font.CjkFontRoleClassifier
import org.tiqian.font.FontMetricsNormalizationInput
import org.tiqian.font.FontMetricsRequest
import org.tiqian.font.FontRole
import org.tiqian.font.RawFontMetrics
import org.tiqian.font.ScriptAwareFontMetricsNormalizer
import org.tiqian.font.StubFontMetricsResolver
import org.tiqian.test.trace.TraceFormat
import org.tiqian.test.trace.TraceRecorder

/**
 * Process-trace scenarios for the font cluster: context-sensitive role
 * classification and script-aware metrics normalization. Floats that need
 * more than the default one decimal are pre-formatted with TraceFormat.fd
 * so both targets and the Haxe port render identical text.
 */
internal object FontTraceScenarios {

    val all: List<TraceScenario> = listOf(
        roleClassifier(),
        metricsNormalizer(),
    )

    private fun header(id: String): String = "scenario: $id\n"

    private fun roleClassifier(): TraceScenario = TraceScenario(
        id = "font.role-classifier",
        notes = "CjkFontRoleClassifier over CJK, punctuation, Latin, emoji, symbols, and curly-quote context rules",
    ) {
        val t = TraceRecorder()
        val classifier = CjkFontRoleClassifier()
        val cases = listOf(
            // (label, text, rangeStart, rangeEnd)
            "cjk-text" to listOf("\u63D0", 0, 1),
            "ellipsis-punct" to listOf("\u2026\u2026", 0, 1),
            "midline-dot-punct" to listOf("\u22EF\u22EF", 0, 1),
            "em-dash-punct" to listOf("\u2014\u2014", 0, 1),
            "two-em-dash-punct" to listOf("\u2E3A", 0, 1),
            "ideographic-full-stop" to listOf("\u3002", 0, 1),
            "katakana-middle-dot" to listOf("\u30FB", 0, 1),
            "han-era-dot" to listOf("\u2027", 0, 1),
            "fullwidth-tilde" to listOf("\uFF5E", 0, 1),
            "fullwidth-solidus" to listOf("\uFF0F", 0, 1),
            "latin-text" to listOf("English", 0, 1),
            "emoji-watch" to listOf("\u231A", 0, 1),
            "emoji-mahjong" to listOf("\uD83C\uDC04", 0, 1),
            "plain-keycap-base-stays-latin" to listOf("1", 0, 1),
            "heart-symbol" to listOf("\u2764", 0, 1),
            "ascii-percent" to listOf("%", 0, 1),
            "ascii-period" to listOf(".", 0, 1),
            "ascii-ampersand" to listOf("&", 0, 1),
            "ascii-pipe" to listOf("|", 0, 1),
            "ascii-underscore" to listOf("_", 0, 1),
            "percent-between-cjk" to listOf("\u4E2D%\u6587", 1, 2),
            "hyphen-in-latin" to listOf("well-known", 4, 5),
            "slash-in-url" to listOf("https://example", 6, 7),
            "slash-between-cjk" to listOf("\u4E2D\u6587/\u4E2D\u6587", 2, 3),
            "hyphen-between-cjk" to listOf("\u4E2D\u6587-\u4E2D\u6587", 2, 3),
            "double-quote-cjk-left" to listOf("\u4ED6\u8BF4\u201C\u4F60\u597D\u201D", 2, 3),
            "double-quote-cjk-right" to listOf("\u4ED6\u8BF4\u201C\u4F60\u597D\u201D", 5, 6),
            "single-quote-cjk-left" to listOf("\u4ED6\u8BF4\u2018\u4F60\u597D\u2019", 2, 3),
            "single-quote-cjk-right" to listOf("\u4ED6\u8BF4\u2018\u4F60\u597D\u2019", 5, 6),
            "double-quote-latin-left" to listOf("said \u201Chello\u201D end", 5, 6),
            "double-quote-latin-right" to listOf("said \u201Chello\u201D end", 11, 12),
            "apostrophe-latin" to listOf("it\u2019s", 2, 3),
            "quote-mixed-cjk-left" to listOf("\u4ED6\u8BF4\u201Chello\u201D", 2, 3),
            "quote-mixed-latin-close" to listOf("\u4ED6\u8BF4\u201Chello\u201D", 8, 9),
            "ascii-paren-open" to listOf("(", 0, 1),
            "ascii-bracket-inside-cjk" to listOf("\u4E2D(\u6587", 1, 2),
            "quote-at-text-start" to listOf("\u201C\u4F60\u597D\u201D", 0, 1),
            "quote-at-text-end" to listOf("\u201C\u4F60\u597D\u201D", 3, 4),
        )
        for ((label, spec) in cases) {
            val text = spec[0] as String
            val start = spec[1] as Int
            val end = spec[2] as Int
            t.event(
                "classify",
                "case" to label,
                "role" to classifier.classify(text, TextRange(start, end)).name,
            )
        }
        header("font.role-classifier") + t.text()
    }

    private fun metricsNormalizer(): TraceScenario = TraceScenario(
        id = "font.metrics-normalizer",
        notes = "ScriptAwareFontMetricsNormalizer: typo box for CJK, hhea fallback, raw Roman metrics for Latin",
    ) {
        val t = TraceRecorder()
        val resolver = StubFontMetricsResolver()
        val normalizer = ScriptAwareFontMetricsNormalizer()
        fun fd2(v: Float?): Any = if (v == null) "-" else TraceFormat.fd(v, 2)

        val typoRequest = FontMetricsRequest(
            fontKey = "cjk-primary",
            fontSize = 16.0f,
            role = FontRole.CjkText,
            locale = "zh-Hans",
        )
        val typoRaw = resolver.resolve(typoRequest)
        val typoLayout = normalizer.normalize(FontMetricsNormalizationInput(typoRequest, typoRaw))
        t.event(
            "normalize", "case" to "cjk-typo-box",
            "raw-typo-ascent" to fd2(typoRaw.typoAscent),
            "raw-typo-descent" to fd2(typoRaw.typoDescent),
            "ascent" to TraceFormat.fd(typoLayout.ascent, 2),
            "descent" to TraceFormat.fd(typoLayout.descent, 2),
            "baseline" to typoLayout.baselineClass.name,
            "metric-box" to typoLayout.metricBox.name,
            "source" to typoLayout.source.name,
        )

        val hheaRequest = FontMetricsRequest(
            fontKey = "cjk-bad",
            fontSize = 16.0f,
            role = FontRole.CjkText,
            locale = "zh-Hans",
        )
        val hheaRaw = RawFontMetrics(ascent = 18.4f, descent = 4.0f)
        val hheaLayout = normalizer.normalize(FontMetricsNormalizationInput(hheaRequest, hheaRaw))
        t.event(
            "normalize", "case" to "cjk-hhea-fallback",
            "ascent" to TraceFormat.fd(hheaLayout.ascent, 2),
            "descent" to TraceFormat.fd(hheaLayout.descent, 2),
            "policy" to hheaLayout.policy.name,
        )

        val latinRequest = FontMetricsRequest(
            fontKey = "latin-primary",
            fontSize = 16.0f,
            role = FontRole.LatinText,
            locale = "en",
        )
        val latinRaw = resolver.resolve(latinRequest)
        val latinLayout = normalizer.normalize(FontMetricsNormalizationInput(latinRequest, latinRaw))
        t.event(
            "normalize", "case" to "latin-raw",
            "ascent" to TraceFormat.fd(latinLayout.ascent, 2),
            "descent" to TraceFormat.fd(latinLayout.descent, 2),
            "baseline" to latinLayout.baselineClass.name,
            "metric-box" to latinLayout.metricBox.name,
        )
        header("font.metrics-normalizer") + t.text()
    }
}
