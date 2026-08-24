package org.tiqian.web

// Bridge to the JS lifecycle helpers (npm/core/engine/lifecycle.js). The
// runtime bundle embeds that script via LifecycleBridgeGenerated.kt; the
// dispatcher below installs it on first use and returns the installed API.

internal external interface LifecycleBridgeJs {
    fun optionsFromJs(options: JsAny?): JsAny?
    fun optionFloat(options: JsAny?, name: String): Double?
    fun conformingExactFontSessionId(options: JsAny?): String?
    fun allowsSnapshotExactLayout(options: JsAny?): Boolean
    fun withoutExactFontSession(options: JsAny?): JsAny?
    fun withRootDefaults(options: JsAny?, root: org.w3c.dom.HTMLElement): JsAny?
    fun reportIssue(issue: JsAny?)
    fun clearIssue(issue: JsAny?)
    fun captureSourceInlineSize(paragraph: org.w3c.dom.HTMLElement): JsAny?
    fun applyConfiguredHostFontSize(paragraph: org.w3c.dom.HTMLElement, fontSize: Double?): Boolean
    fun stabilizeContentSizedItemInlineSize(paragraph: org.w3c.dom.HTMLElement, source: JsAny?): JsAny?
}

@JsFun("(install) => (globalThis.__TiqianLifecycle || (install(), globalThis.__TiqianLifecycle))")
private external fun requireLifecycleBridgeJs(install: () -> Unit): LifecycleBridgeJs

internal fun lifecycleBridge(): LifecycleBridgeJs =
    requireLifecycleBridgeJs { installEmbeddedLifecycleScript() }
