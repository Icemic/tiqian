package org.tiqian.trace

import org.tiqian.linebreak.EnglishHyphenation
import org.tiqian.linebreak.UnicodePunctuationLineBreak
import org.tiqian.test.trace.TraceFormat
import org.tiqian.test.trace.TraceRecorder

/**
 * Process-trace scenarios for the linebreak cluster: the UAX#14 tailorable
 * punctuation classes behind classOf, and the English hyphenation offsets
 * (pattern rules, margins, and the exception list). Inputs are plain ASCII
 * or integer code points, so no escaping hazards apply.
 */
internal object LinebreakTraceScenarios {

    val all: List<TraceScenario> = listOf(
        punctuationClass(),
        englishHyphenation(),
    )

    private fun header(id: String): String = "scenario: $id\n"

    private fun rejected(action: () -> Unit): Boolean =
        try {
            action()
            false
        } catch (e: IllegalArgumentException) {
            true
        }

    private fun punctuationClass(): TraceScenario = TraceScenario(
        id = "linebreak.punctuation-class",
        notes = "UnicodePunctuationLineBreak.classOf: tailorable classes and scalar-value guards",
    ) {
        val t = TraceRecorder()
        val cases = listOf(
            "0x7C" to 0x7C,
            "0x2014" to 0x2014,
            "0x058A" to 0x58A,
            "0x203C" to 0x203C,
            "0x0021" to 0x21,
            "0x0000" to 0x0,
            "0x10FFFF" to 0x10FFFF,
        )
        for ((label, cp) in cases) {
            t.event("class-of", "cp" to label, "class" to UnicodePunctuationLineBreak.classOf(cp).name)
        }
        t.event("class-of", "cp" to "-1", "rejected" to rejected { UnicodePunctuationLineBreak.classOf(-1) })
        t.event("class-of", "cp" to "0x110000", "rejected" to rejected { UnicodePunctuationLineBreak.classOf(0x110000) })
        t.event("class-of", "cp" to "0xD800", "rejected" to rejected { UnicodePunctuationLineBreak.classOf(0xD800) })
        header("linebreak.punctuation-class") + t.text()
    }

    private fun englishHyphenation(): TraceScenario = TraceScenario(
        id = "linebreak.english-hyphenation",
        notes = "EnglishHyphenation.enUs offsets: syllable breaks, leftMin/rightMin margins, exception words",
    ) {
        val t = TraceRecorder()
        val words = listOf(
            "hyphenation", "computer", "international", "supercalifragilistic",
            "the", "a", "project", "present",
        )
        for (word in words) {
            val offsets = EnglishHyphenation.enUs.hyphenate(word)
            t.event(
                "hyphenate", "word" to word, "count" to TraceFormat.i(offsets.size),
                "offsets" to offsets.joinToString("|"),
            )
        }
        header("linebreak.english-hyphenation") + t.text()
    }
}
