package org.tiqian.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UnicodeWordCharacterTest {
    @Test
    fun lettersAndNumbersAreWordCharactersAcrossScripts() {
        for (codePoint in listOf('A'.code, '2'.code, '中'.code, 0x0301, 0x03C0, 0x0416, 0x0662, 0x20000)) {
            assertTrue(UnicodeWordCharacter.contains(codePoint), "U+${codePoint.toString(16)}")
        }
        for (codePoint in listOf(0x20, 0x2019, 0xFF1F, 0x1F600)) {
            assertFalse(UnicodeWordCharacter.contains(codePoint), "U+${codePoint.toString(16)}")
        }
    }
}
