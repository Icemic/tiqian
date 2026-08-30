package org.tiqian.core

import kotlin.test.Test
import org.tiqian.test.trace.assertEquals
import kotlin.test.AfterTest
import org.tiqian.test.trace.TestTraceRecorder

class UnicodeScriptEvidenceTest {
    private val testTrace = TestTraceRecorder("UnicodeScriptEvidenceTest")

    @Test
    fun commonAndInheritedScalarsDoNotVote() {
        testTrace.section("commonAndInheritedScalarsDoNotVote")
        for (codePoint in listOf(0x20, 0x30, 0x201C, 0xFF1F, 0x0301, 0x1F600)) {
            assertEquals(
                UnicodeScriptEvidence.Neutral,
                UnicodeScriptEvidenceClassifier.classify(codePoint),
                "U+${codePoint.toString(16)}",
            )
        }
    }

    @Test
    fun eastAsianScriptsAreDistinctFromOtherStrongScripts() {
        testTrace.section("eastAsianScriptsAreDistinctFromOtherStrongScripts")
        for (codePoint in listOf('中'.code, 0x3105, 0x3042, 0x30A2, 0xAC00, 0x20000)) {
            assertEquals(
                UnicodeScriptEvidence.EastAsian,
                UnicodeScriptEvidenceClassifier.classify(codePoint),
                "U+${codePoint.toString(16)}",
            )
        }
        for (codePoint in listOf('A'.code, 0x03C0, 0x0416, 0x0627)) {
            assertEquals(
                UnicodeScriptEvidence.Other,
                UnicodeScriptEvidenceClassifier.classify(codePoint),
                "U+${codePoint.toString(16)}",
            )
        }
    }

    @AfterTest
    fun flushTestTrace() {
        testTrace.flush()
    }
}
