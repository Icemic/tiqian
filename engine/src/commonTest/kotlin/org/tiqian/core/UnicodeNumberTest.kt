package org.tiqian.core

import kotlin.test.Test
import org.tiqian.test.trace.assertFailsWith
import org.tiqian.test.trace.assertFalse
import org.tiqian.test.trace.assertTrue
import kotlin.test.AfterTest
import org.tiqian.test.trace.TestTraceRecorder

class UnicodeNumberTest {
    private val testTrace = TestTraceRecorder("UnicodeNumberTest")

    @Test
    fun numbersAreMembersAcrossScriptsAndNonScalarsAreRejected() {
        testTrace.section("numbersAreMembersAcrossScriptsAndNonScalarsAreRejected")
        for (codePoint in listOf('0'.code, 0x0662, '½'.code)) {
            assertTrue(UnicodeNumber.contains(codePoint), "U+${codePoint.toString(16)}")
        }
        for (codePoint in listOf('a'.code, '中'.code, 0x2019)) {
            assertFalse(UnicodeNumber.contains(codePoint), "U+${codePoint.toString(16)}")
        }
        assertFailsWith<IllegalArgumentException> { UnicodeNumber.contains(0xDC00) }
        assertFailsWith<IllegalArgumentException> { UnicodeNumber.contains(-1) }
        assertFailsWith<IllegalArgumentException> { UnicodeNumber.contains(0x110000) }
    }

    @AfterTest
    fun flushTestTrace() {
        testTrace.flush()
    }
}
