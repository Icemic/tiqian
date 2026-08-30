package org.tiqian.trace

import org.tiqian.clreq.BopomofoParser
import org.tiqian.clreq.ClreqPunctuationPolicies
import org.tiqian.clreq.InteriorPunctuationStyle
import org.tiqian.clreq.KinsokuLevel
import org.tiqian.clreq.PunctuationWidthPolicy
import org.tiqian.test.trace.TraceRecorder

/**
 * Process-trace scenarios for the clreq cluster: Bopomofo tone parsing,
 * punctuation classification, kinsoku start/end policy, and punctuation
 * width policy. Case matrices mirror the coverage tests; every field is a
 * primitive or a stable enum name so the Haxe port can replay the bytes.
 */
internal object ClreqTraceScenarios {

    val all: List<TraceScenario> = listOf(
        bopomofoParser(),
        punctuationClassify(),
        kinsokuPolicies(),
        punctuationWidthPolicy(),
    )

    private fun header(id: String): String = "scenario: $id\n"

    private fun bopomofoParser(): TraceScenario = TraceScenario(
        id = "clreq.bopomofo-parser",
        notes = "BopomofoParser tone marks: suffix tones, prefixed neutral dot, empty input, near-miss marks",
    ) {
        val t = TraceRecorder()
        val cases = listOf(
            "plain-yinping" to "\u3113\u3128\u3125",
            "yangping" to "\u3114\u3124\u02CA",
            "shang" to "\u310B\u3127\u02C7",
            "qu" to "\u3111\u3129\u02CB",
            "explicit-yinping-macron" to "\u3107\u311A\u02C9",
            "prefixed-neutral" to "\u02D9\u3109\u311C",
            "single-symbol-yangping" to "\u3126\u02CA",
            "empty" to "",
            "in-range-default-u02C8" to "\u3105\u311A\u02C8",
            "range-miss-u02CC" to "\u3105\u311A\u02CC",
        )
        for ((label, input) in cases) {
            val reading = BopomofoParser.parse(input)
            t.event(
                "parse", "case" to label, "input" to input,
                "symbols" to reading.symbols.joinToString("|"),
                "tone" to reading.tone.name,
            )
        }
        header("clreq.bopomofo-parser") + t.text()
    }

    private fun punctuationClassify(): TraceScenario = TraceScenario(
        id = "clreq.punctuation-classify",
        notes = "ClreqPunctuationPolicies.classify over every punctuation class arm",
    ) {
        val t = TraceRecorder()
        val cases = listOf(
            '\u201C', '\u2018', '\uFF08', '\u300A', '\u3008', '\u300C', '\u300E', '\u3010', '\u3014', '\u3016', '\u3018', '\u301A',
            '\u201D', '\u2019', '\uFF09', '\u300B', '\u3009', '\u300D', '\u300F', '\u3011', '\u3015', '\u3017', '\u3019', '\u301B',
            '\uFF0C', '\u3001', '\u3002', '\uFF1B', '\uFF1A', '\uFF01', '\uFF1F',
            '\u00B7',
            '\u30FB', '\u2027', '\u2022',
            '\uFF5E', '~', '-', '\u2013',
            '/', '\uFF0F',
            '\u2026', '\u22EF',
            '\u2014', '\u2E3A',
            '\u4E2D',
        )
        for (ch in cases) {
            t.event("classify", "char" to ch.toString(), "class" to ClreqPunctuationPolicies.classify(ch).name)
        }
        header("clreq.punctuation-classify") + t.text()
    }

    private fun kinsokuPolicies(): TraceScenario = TraceScenario(
        id = "clreq.kinsoku-policies",
        notes = "forbiddenAtLineStart and forbiddenAtLineEnd across kinsoku levels and punctuation classes",
    ) {
        val t = TraceRecorder()
        val levels = listOf(
            "none" to KinsokuLevel.None,
            "basic" to KinsokuLevel.Basic,
            "gb" to KinsokuLevel.GbStyle,
            "strict" to KinsokuLevel.Strict,
        )
        val chars = listOf('\uFF0C', '\u201D', '\u00B7', '\u30FB', '\uFF5E', '/', '\u2014', '\u2026', '\u201C', '\uFF08', '\uFF09', '\u6587')
        for ((levelName, level) in levels) {
            for (ch in chars) {
                t.event(
                    "kinsoku",
                    "level" to levelName, "char" to ch.toString(),
                    "start-forbidden" to ClreqPunctuationPolicies.forbiddenAtLineStart(ch, level),
                    "end-forbidden" to ClreqPunctuationPolicies.forbiddenAtLineEnd(ch, level),
                )
            }
        }
        header("clreq.kinsoku-policies") + t.text()
    }

    private fun punctuationWidthPolicy(): TraceScenario = TraceScenario(
        notes = "forcedHalfWidth under default, gbFixedSeparators, and Kaiming interior styles; policyFor body/advance em",
        id = "clreq.punctuation-width-policy",
    ) {
        val t = TraceRecorder()
        val default = PunctuationWidthPolicy()
        val gb = PunctuationWidthPolicy(gbFixedSeparators = true)
        val kaiming = PunctuationWidthPolicy(
            gbFixedSeparators = false,
            interior = InteriorPunctuationStyle.Kaiming,
        )
        val cases = listOf(
            '-' to default, '\u2013' to default,
            '\uFF5E' to gb, '\u00B7' to gb, '\u2022' to gb, '/' to gb, '\uFF0C' to gb,
            '\uFF08' to kaiming, '\uFF09' to kaiming, '\uFF0C' to kaiming, '\uFF1B' to kaiming,
            '\u3002' to kaiming, '\uFF01' to kaiming, '\uFF1F' to kaiming, '\uFF0E' to kaiming, '\u4E2D' to kaiming,
        )
        for ((ch, policy) in cases) {
            val style = when (policy.interior) {
                InteriorPunctuationStyle.Kaiming -> "kaiming"
                else -> if (policy.gbFixedSeparators) "gb" else "default"
            }
            t.event(
                "forced-half-width",
                "style" to style, "char" to ch.toString(),
                "forced" to ClreqPunctuationPolicies.forcedHalfWidth(ch, policy),
            )
        }
        for (ch in listOf('\u2E3A', '-', '\uFF0C', '\uFF08', '\uFF09', '\u5B57')) {
            val policy = ClreqPunctuationPolicies.policyFor(ch)
            t.event(
                "policy-for",
                "char" to ch.toString(),
                "body-em" to policy.defaultBodyEm,
                "advance-em" to policy.defaultAdvanceEm,
            )
        }
        header("clreq.punctuation-width-policy") + t.text()
    }
}
