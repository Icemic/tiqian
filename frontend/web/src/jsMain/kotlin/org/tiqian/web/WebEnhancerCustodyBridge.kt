package org.tiqian.web

// Bridge to the JS custody engine (npm/core/engine/custody.js). The runtime
// bundle embeds that script via CustodyBridgeGenerated.kt; the dispatcher
// below installs it on first use and returns the installed API object.

internal external interface CustodyBridgeJs {
    fun begin(
        source: org.w3c.dom.HTMLElement,
        renderedAttribute: String?,
        preparedFlowAttribute: String?,
        canonicalSourceAttribute: String?,
        exactPreparedDomAttribute: String?,
        langAttribute: String?,
        styleAttribute: String?,
        position: String,
        positionPriority: String,
        inlineSize: String,
        inlineSizePriority: String,
        fontSize: String,
        fontSizePriority: String,
        hostInlineSizeAttribute: String?,
    )
    fun take(source: org.w3c.dom.HTMLElement, hostFontSizeApplied: String?)
    fun commit(source: org.w3c.dom.HTMLElement, hostInlineSizeApplied: String?)
    fun stampRendered(source: org.w3c.dom.HTMLElement)
    fun renderedMatches(source: org.w3c.dom.HTMLElement): Boolean
    fun custodyMatches(source: org.w3c.dom.HTMLElement): Boolean
    fun captureLive(
        source: org.w3c.dom.HTMLElement,
        lastMeasure: Float?,
    ): CustodyLiveSnapshotJs
    fun rollback(vararg snapshots: CustodyLiveSnapshotJs): kotlin.js.JsArray<CustodyRollbackResultJs>
    fun restoreParagraph(source: org.w3c.dom.HTMLElement)
    fun restoreShell(source: org.w3c.dom.HTMLElement)
    fun ensureContainingBlock(source: org.w3c.dom.HTMLElement)
}

internal external interface CustodyLiveSnapshotJs {
    val source: org.w3c.dom.HTMLElement
    val lastMeasure: Float?
}

internal external interface CustodyRollbackResultJs {
    val source: org.w3c.dom.HTMLElement
    val lastMeasure: Float?
}

@JsFun("(install) => (globalThis.__TiqianCustody || (install(), globalThis.__TiqianCustody))")
private external fun requireCustodyBridgeJs(install: () -> Unit): CustodyBridgeJs

internal fun custodyBridge(): CustodyBridgeJs =
    requireCustodyBridgeJs { installEmbeddedCustodyScript() }
