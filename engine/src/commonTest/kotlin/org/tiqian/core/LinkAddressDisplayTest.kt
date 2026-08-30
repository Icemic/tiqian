package org.tiqian.core

import kotlin.test.Test
import org.tiqian.test.trace.assertFalse
import org.tiqian.test.trace.assertTrue
import kotlin.test.AfterTest
import org.tiqian.test.trace.TestTraceRecorder

/**
 * `LinkAddressDisplayGate`: only link text that IS its own address should take the
 * technical line-break policy; ordinary link text keeps prose breaking (issue #9).
 */
class LinkAddressDisplayTest {
    private val testTrace = TestTraceRecorder("LinkAddressDisplayTest")


    @Test
    fun identicalDisplayAndTargetIsAnAddress() {
        testTrace.section("identicalDisplayAndTargetIsAnAddress")
        assertTrue(LinkAddressDisplay.displaysAddress("https://example.com/a", "https://example.com/a"))
        assertTrue(LinkAddressDisplay.displaysAddress("footnote-1", "footnote-1"))
    }

    @Test
    fun schemeLessDisplayOfTheTargetIsAnAddress() {
        testTrace.section("schemeLessDisplayOfTheTargetIsAnAddress")
        assertTrue(LinkAddressDisplay.displaysAddress("example.com/b", "https://example.com/b"))
        assertTrue(LinkAddressDisplay.displaysAddress("example.com", "http://example.com"))
        assertTrue(LinkAddressDisplay.displaysAddress("a@example.com", "mailto:a@example.com"))
    }

    @Test
    fun proseDisplayTextIsNotAnAddress() {
        testTrace.section("proseDisplayTextIsNotAnAddress")
        assertFalse(LinkAddressDisplay.displaysAddress("Example", "https://example.com"))
        assertFalse(LinkAddressDisplay.displaysAddress("示例站", "https://example.com"))
        assertFalse(LinkAddressDisplay.displaysAddress("action", "generic"))
        assertFalse(LinkAddressDisplay.displaysAddress("", "https://example.com"))
        assertFalse(LinkAddressDisplay.displaysAddress("Example", ""))
    }

    @AfterTest
    fun flushTestTrace() {
        testTrace.flush()
    }
}
