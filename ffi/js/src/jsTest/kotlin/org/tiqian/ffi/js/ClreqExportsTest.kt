package org.tiqian.ffi.js

import kotlin.test.Test
import kotlin.test.assertEquals

class ClreqExportsTest {

    @Test
    fun bopomofoParseReturnsSymbolsAndToneAsJson() {
        val json = bopomofoParse("ㄔㄤˊ")
        val parsed = kotlin.js.JSON.parse<dynamic>(json)
        assertEquals("ㄔ", parsed.symbols[0] as String)
        assertEquals("ㄤ", parsed.symbols[1] as String)
        assertEquals("Yangping", parsed.tone as String)

        val neutral = kotlin.js.JSON.parse<dynamic>(bopomofoParse("˙ㄉㄜ"))
        assertEquals("ㄉ", neutral.symbols[0] as String)
        assertEquals("ㄜ", neutral.symbols[1] as String)
        assertEquals("Neutral", neutral.tone as String)

        val explicitYinping = kotlin.js.JSON.parse<dynamic>(bopomofoParse("ㄇㄚˉ"))
        assertEquals("Yinping", explicitYinping.tone as String)
        assertEquals(2, (explicitYinping.symbols as Array<dynamic>).size)
    }

    @Test
    fun numberSymbolCohesionUnbreakableRangesReturnsSourcePairs() {
        assertEquals("[[2,4]]", numberSymbolCohesionUnbreakableRanges("增长50%了"))

        val parsed = kotlin.js.JSON.parse<dynamic>(numberSymbolCohesionUnbreakableRanges("共1,000人"))
        assertEquals(1, (parsed as Array<dynamic>).size)
        assertEquals(1.0, parsed[0][0] as Double)
        assertEquals(5.0, parsed[0][1] as Double)

        assertEquals("[]", numberSymbolCohesionUnbreakableRanges("纯中文没有数字"))
    }
}