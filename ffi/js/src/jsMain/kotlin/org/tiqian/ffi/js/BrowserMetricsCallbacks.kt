package org.tiqian.ffi.js

import kotlin.js.JsName

/**
 * Typed callback interface for browser metrics shaping and font metrics resolution.
 * Replaces the previous adapter classes (JsCallbackTextShaper, JsCallbackFontMetricsResolver)
 * at the precomputeParagraphWithBrowserMetrics boundary.
 *
 * This interface is NOT @JsExport because @JsExport interfaces do not support
 * function-typed properties (corrective wave 5, #106 CONSTRAINT). At the JS
 * boundary the caller passes a plain JS object matching this shape; Kotlin
 * accesses the functions via property calls on the received object.
 *
 * @JsName annotations pin the JS property names so the compiled lambdas
 * call the correct methods on the plain JS object.
 *
 * Measurement and drawing stay same-source (constraint 5): the callbacks
 * still consume the same font/glyph/advance evidence, only the carrier changes.
 */
interface BrowserMetricsCallbacks {
    @JsName("shapeJson")
    fun shapeJson(requestJson: String): String
    @JsName("metricsJson")
    fun metricsJson(requestJson: String): String
}
