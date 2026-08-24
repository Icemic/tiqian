package org.tiqian.web

// Bridge to the JS paragraph layout preparation
// (npm/core/engine/prepare-paragraph-layout.js). The runtime bundle embeds
// that script via PrepareParagraphLayoutBridgeGenerated.kt; the dispatcher
// below installs it on first use and returns the installed API.

internal external interface PrepareParagraphLayoutBridgeJs {
    fun prepareParagraphLayout(ffi: JsAny?, argument: JsAny?): JsAny?
}

@JsFun("(install) => (globalThis.__TiqianPrepareParagraphLayout || (install(), globalThis.__TiqianPrepareParagraphLayout))")
private external fun requirePrepareParagraphLayoutBridgeJs(install: () -> Unit): PrepareParagraphLayoutBridgeJs

internal fun prepareParagraphLayoutBridge(): PrepareParagraphLayoutBridgeJs =
    requirePrepareParagraphLayoutBridgeJs { installEmbeddedPrepareParagraphLayoutScript() }
