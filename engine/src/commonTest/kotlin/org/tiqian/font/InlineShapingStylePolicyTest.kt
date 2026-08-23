package org.tiqian.font

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InlineShapingStylePolicyTest {
    private val properties = InlineShapingStylePolicy.unsupportedInlineShapingProperties

    private fun identicalValues(count: Int = properties.size): List<String> = List(count) { "value" }

    @Test
    fun reportsFirstPropertyWhenItDiverges() {
        val elementValues = identicalValues().toMutableList()
        elementValues[0] = "divergent"
        assertEquals(
            properties[0],
            InlineShapingStylePolicy.firstDivergentProperty(elementValues, identicalValues()),
        )
    }

    @Test
    fun reportsMiddlePropertyWhenItIsFirstDivergence() {
        val elementValues = identicalValues().toMutableList()
        elementValues[3] = "divergent"
        assertEquals(
            properties[3],
            InlineShapingStylePolicy.firstDivergentProperty(elementValues, identicalValues()),
        )
    }

    @Test
    fun returnsNullWhenAllValuesMatch() {
        assertNull(
            InlineShapingStylePolicy.firstDivergentProperty(identicalValues(), identicalValues()),
        )
    }

    @Test
    fun returnsNullForEmptyLists() {
        assertNull(InlineShapingStylePolicy.firstDivergentProperty(emptyList(), emptyList()))
    }

    @Test
    fun longerValueListsStopAtThePropertyListBoundary() {
        val elementValues = identicalValues(count = properties.size + 4)
        val paragraphValues = identicalValues(count = properties.size + 4)
        // The extra indices have no property name to report; the comparison
        // must stop at the property list boundary instead of indexing past it.
        assertNull(InlineShapingStylePolicy.firstDivergentProperty(elementValues, paragraphValues))
    }
}