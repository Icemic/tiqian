package org.tiqian.web

// Bridge to the JS canvas font stack resolution and browser metrics bridge
// (npm/core/engine/canvas-fonts.js and browser-metrics-bridge.js). The
// runtime bundle embeds those scripts via CanvasFontsBridgeGenerated.kt and
// BrowserMetricsBridgeGenerated.kt; the dispatchers below install them on
// first use and return the installed APIs.
//
// Dependency order: browser-metrics-bridge.js reads the canvas-fonts,
// canvas-metrics and canvas-shaping globals at call time, and canvas-metrics
// reads canvas-fonts. Install in that load order.

internal external interface CanvasFontsBridgeJs {
    fun createFontFamilies(config: JsAny?): JsAny?
    fun cssFamilyToken(family: String): String
}

internal external interface BrowserMetricsBridgeJs {
    fun createBrowserMetricsBridge(options: JsAny?): JsAny?
}

@JsFun("(install) => (globalThis.__TiqianCanvasFonts || (install(), globalThis.__TiqianCanvasFonts))")
private external fun requireCanvasFontsBridgeJs(install: () -> Unit): CanvasFontsBridgeJs

@JsFun("(install) => (globalThis.__TiqianBrowserMetricsBridge || (install(), globalThis.__TiqianBrowserMetricsBridge))")
private external fun requireBrowserMetricsBridgeJs(install: () -> Unit): BrowserMetricsBridgeJs

internal fun canvasFontsBridge(): CanvasFontsBridgeJs =
    requireCanvasFontsBridgeJs { installEmbeddedCanvasFontsScript() }

internal fun browserMetricsBridge(): BrowserMetricsBridgeJs =
    requireBrowserMetricsBridgeJs {
        installEmbeddedCanvasFontsScript()
        installEmbeddedCanvasMetricsScript()
        installEmbeddedCanvasShapingScript()
        installEmbeddedBrowserMetricsBridgeScript()
    }
