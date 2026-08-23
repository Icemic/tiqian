package org.tiqian.web

// Bridge to the JS eligibility engine (npm/core/engine/eligibility.js). The runtime
// bundle embeds that script via EligibilityBridgeGenerated.kt; the dispatcher
// below installs it on first use and returns the installed API object.

internal external interface EligibilityBridgeJs {
    fun shouldTryParagraph(paragraph: org.w3c.dom.HTMLElement): Boolean
    fun isPureBlockImageParagraph(paragraph: org.w3c.dom.HTMLElement): Boolean
    fun hasOpaqueInlineCandidate(paragraph: org.w3c.dom.HTMLElement): Boolean
    fun isNonTextInlineTag(tag: String): Boolean
    fun isOpaqueInlineDisplay(display: String): Boolean
    fun isOpaqueInlineLevelDisplay(display: String): Boolean
}

@JsFun("(install) => (globalThis.__TiqianEligibility || (install(), globalThis.__TiqianEligibility))")
private external fun requireEligibilityBridgeJs(install: () -> Unit): EligibilityBridgeJs

internal fun eligibilityBridge(): EligibilityBridgeJs =
    requireEligibilityBridgeJs { installEmbeddedEligibilityScript() }
