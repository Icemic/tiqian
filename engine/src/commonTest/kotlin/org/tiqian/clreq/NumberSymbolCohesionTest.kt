package org.tiqian.clreq

import kotlin.test.Test
import org.tiqian.test.trace.assertEquals
import kotlin.test.AfterTest
import org.tiqian.test.trace.TestTraceRecorder

class NumberSymbolCohesionTest {
    private val testTrace = TestTraceRecorder("NumberSymbolCohesionTest")


    private fun groups(s: String): List<String> =
        NumberSymbolCohesion.unbreakableRanges(s).map { s.substring(it.first, it.last + 1) }

    @Test
    fun bindsDigitsWithSuffixUnitPrefixSignAndCurrency() {
        testTrace.section("bindsDigitsWithSuffixUnitPrefixSignAndCurrency")
        assertEquals(listOf("50%"), groups("增长50%了"))
        assertEquals(listOf("37℃"), groups("温37℃高"))
        assertEquals(listOf("90°"), groups("转90°角"))
        assertEquals(listOf("+5"), groups("是+5度"))
        assertEquals(listOf("±2"), groups("误差±2毫米"))
        assertEquals(listOf("¥100"), groups("价¥100元"))
        assertEquals(listOf("100₫"), groups("约100₫的"))
    }

    @Test
    fun keepsInteriorDecimalAndThousandsSeparators() {
        testTrace.section("keepsInteriorDecimalAndThousandsSeparators")
        assertEquals(listOf("3.14"), groups("π≈3.14啦"))
        assertEquals(listOf("1,000"), groups("共1,000人"))
        // A trailing '.'/',' that is NOT between digits is a 句号/comma, excluded.
        assertEquals(listOf("100"), groups("有100。"))
    }

    @Test
    fun bareNumberIsItsOwnGroup() {
        testTrace.section("bareNumberIsItsOwnGroup")
        assertEquals(listOf("2024"), groups("在2024年"))
        assertEquals(emptyList(), groups("纯中文没有数字"))
    }

    @AfterTest
    fun flushTestTrace() {
        testTrace.flush()
    }
}
