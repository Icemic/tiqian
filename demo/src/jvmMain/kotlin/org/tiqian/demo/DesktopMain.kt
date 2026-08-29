package org.tiqian.demo

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.window.singleWindowApplication

private const val DEMO_DENSITY_PROPERTY = "tiqian.demo.density"

fun main() = singleWindowApplication(title = "Tiqian Compose Demo") {
    val inheritedDensity = LocalDensity.current
    val densityOverride = System.getProperty(DEMO_DENSITY_PROPERTY)?.toFloatOrNull()

    if (densityOverride == null) {
        TiqianDemoScreen()
    } else {
        CompositionLocalProvider(
            LocalDensity provides Density(densityOverride, inheritedDensity.fontScale),
        ) {
            TiqianDemoScreen()
        }
    }
}
