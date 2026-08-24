package org.tiqian.web

// Bridge to the JS paragraph processing orchestration
// (npm/core/engine/process-paragraph.js). The runtime bundle embeds that
// script via ProcessParagraphBridgeGenerated.kt; the dispatcher below installs
// it on first use and returns the installed API.

internal external interface ProcessParagraphBridgeJs {
    fun processParagraph(argument: JsAny?)
}

@JsFun("(install) => (globalThis.__TiqianProcessParagraph || (install(), globalThis.__TiqianProcessParagraph))")
private external fun requireProcessParagraphBridgeJs(install: () -> Unit): ProcessParagraphBridgeJs

internal fun processParagraphBridge(): ProcessParagraphBridgeJs =
    requireProcessParagraphBridgeJs { installEmbeddedProcessParagraphScript() }
