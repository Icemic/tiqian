package org.tiqian.web

// Bridge to the JS responsive measure helpers (npm/core/engine/responsive-measure.js).
// The runtime bundle embeds that script via ResponsiveMeasureBridgeGenerated.kt;
// the dispatcher below installs it on first use and returns the installed API object.

internal external interface ResponsiveMeasureBridgeJs {
    fun effectiveLineMeasure(width: Double, fontSize: Double): Double
    fun elementContentWidth(element: org.w3c.dom.HTMLElement): Double
    fun sourceParagraphWidth(paragraph: org.w3c.dom.HTMLElement): Double
    fun isCurrentResponsiveMeasure(preparedWidth: Double, currentWidth: Double, fontSize: Double): Boolean
}

@JsFun("(install) => (globalThis.__TiqianResponsiveMeasure || (install(), globalThis.__TiqianResponsiveMeasure))")
private external fun requireResponsiveMeasureBridgeJs(install: () -> Unit): ResponsiveMeasureBridgeJs

internal fun responsiveMeasureBridge(): ResponsiveMeasureBridgeJs =
    requireResponsiveMeasureBridgeJs { installEmbeddedResponsiveMeasureScript() }