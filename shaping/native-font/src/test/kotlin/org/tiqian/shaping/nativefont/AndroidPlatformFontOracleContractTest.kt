package org.tiqian.shaping.nativefont

import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidPlatformFontOracleContractTest {
    @Test
    fun platformOverridesReplaceOnlyTheirCorrespondingAxes() {
        assertEquals(
            mapOf(
                "ital" to 1f,
                "opsz" to 32f,
                "wdth" to 100f,
                "wght" to 412f,
            ),
            applyPlatformStyleOverrides(
                fontAxes = mapOf(
                    "opsz" to 32f,
                    "wdth" to 100f,
                    "wght" to 400f,
                ),
                overrides = PlatformStyleOverrides(weight = 412f, italic = 1f),
            ),
        )
    }

    @Test
    fun absentOverridesPreserveTheCompleteReportedAxisInstance() {
        val axes = mapOf("opsz" to 14f, "wdth" to 75f, "wght" to 310f)

        assertEquals(
            axes.toSortedMap(),
            applyPlatformStyleOverrides(axes, PlatformStyleOverrides()),
        )
    }
}
