package org.tiqian.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `LinkAddressDisplayGate`: only link text that IS its own address should take the
 * technical line-break policy; ordinary link text keeps prose breaking (issue #9).
 */
class LinkAddressDisplayTest {

    @Test
    fun identicalDisplayAndTargetIsAnAddress() {
        assertTrue(LinkAddressDisplay.displaysAddress("https://example.com/a", "https://example.com/a"))
        assertTrue(LinkAddressDisplay.displaysAddress("footnote-1", "footnote-1"))
    }

    @Test
    fun schemeLessDisplayOfTheTargetIsAnAddress() {
        assertTrue(LinkAddressDisplay.displaysAddress("example.com/b", "https://example.com/b"))
        assertTrue(LinkAddressDisplay.displaysAddress("example.com", "http://example.com"))
        assertTrue(LinkAddressDisplay.displaysAddress("a@example.com", "mailto:a@example.com"))
    }

    @Test
    fun proseDisplayTextIsNotAnAddress() {
        assertFalse(LinkAddressDisplay.displaysAddress("Example", "https://example.com"))
        assertFalse(LinkAddressDisplay.displaysAddress("示例站", "https://example.com"))
        assertFalse(LinkAddressDisplay.displaysAddress("action", "generic"))
        assertFalse(LinkAddressDisplay.displaysAddress("", "https://example.com"))
        assertFalse(LinkAddressDisplay.displaysAddress("Example", ""))
    }
}
