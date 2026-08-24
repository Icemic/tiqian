package org.tiqian.web

// Bridge to the JS paragraph layout commit
// (npm/core/engine/commit-prepared-paragraph.js). The runtime bundle embeds
// that script via CommitPreparedParagraphBridgeGenerated.kt; the dispatcher
// below installs it on first use and returns the installed API.

internal external interface CommitPreparedParagraphBridgeJs {
    fun commitWorkerPreparedParagraph(argument: JsAny?): JsAny?
    fun commitPreparedParagraph(argument: JsAny?): JsAny?
}

@JsFun("(install) => (globalThis.__TiqianCommitPreparedParagraph || (install(), globalThis.__TiqianCommitPreparedParagraph))")
private external fun requireCommitPreparedParagraphBridgeJs(install: () -> Unit): CommitPreparedParagraphBridgeJs

internal fun commitPreparedParagraphBridge(): CommitPreparedParagraphBridgeJs =
    requireCommitPreparedParagraphBridgeJs { installEmbeddedCommitPreparedParagraphScript() }
