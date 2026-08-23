package org.tiqian.web

// Bridge to the JS markdown lowering engine (npm/core/engine/markdown-lowering.js).
// The runtime bundle embeds that script via MarkdownLoweringBridgeGenerated.kt;
// the switchover (ADR 0053 B9 phase 2) installs it and decodes the lowered
// paragraph. Phase 1 ships the module and the embed wiring only.

internal external interface MarkdownLoweringBridgeJs {
    fun lower(
        paragraph: org.w3c.dom.HTMLElement,
        options: dynamic,
        helpers: dynamic,
    ): dynamic
}

@JsFun("(install) => (globalThis.__TiqianMarkdownLowering || (install(), globalThis.__TiqianMarkdownLowering))")
private external fun requireMarkdownLoweringBridgeJs(install: () -> Unit): MarkdownLoweringBridgeJs

internal fun markdownLoweringBridge(): MarkdownLoweringBridgeJs =
    requireMarkdownLoweringBridgeJs { installEmbeddedMarkdownLoweringScript() }