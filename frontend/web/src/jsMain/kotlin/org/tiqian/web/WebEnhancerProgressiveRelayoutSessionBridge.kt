package org.tiqian.web

// Bridge to the JS progressive relayout session
// (npm/core/engine/progressive-relayout-session.js). The runtime bundle
// embeds that script via ProgressiveRelayoutSessionBridgeGenerated.kt; the
// dispatcher below installs it on first use and returns the installed API.

internal external interface ProgressiveRelayoutSessionJs {
    fun processItem(index: Int, preparation: JsAny?)
    fun finish()
    fun rollback()
    var stale: Boolean
}

internal external interface ProgressiveRelayoutSessionBridgeJs {
    fun createProgressiveRelayoutSession(argument: JsAny?): ProgressiveRelayoutSessionJs
}

@JsFun("(install) => (globalThis.__TiqianProgressiveRelayoutSession || (install(), globalThis.__TiqianProgressiveRelayoutSession))")
private external fun requireProgressiveRelayoutSessionBridgeJs(
    install: () -> Unit,
): ProgressiveRelayoutSessionBridgeJs

internal fun progressiveRelayoutSessionBridge(): ProgressiveRelayoutSessionBridgeJs =
    requireProgressiveRelayoutSessionBridgeJs { installEmbeddedProgressiveRelayoutSessionScript() }
