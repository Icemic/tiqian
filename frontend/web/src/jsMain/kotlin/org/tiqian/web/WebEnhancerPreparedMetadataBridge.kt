package org.tiqian.web

// Bridge to the JS prepared metadata JSON builders
// (npm/core/engine/prepared-metadata.js). The runtime bundle embeds that
// script via PreparedMetadataBridgeGenerated.kt; the dispatcher below
// installs it on first use and returns the installed API. The TS
// orchestrators resolve these builders themselves; Kotlin installs the
// script eagerly so the embedded process-paragraph module finds its global.

internal external interface PreparedMetadataBridgeJs {
    fun preparedSemanticReplayJson(lowered: JsAny?): String
    fun preparedInlineObjectMetaJson(lowered: JsAny?): String
    fun preparedCjkStrongSemanticsJson(lowered: JsAny?): String
}

@JsFun("(install) => (globalThis.__TiqianPreparedMetadata || (install(), globalThis.__TiqianPreparedMetadata))")
private external fun requirePreparedMetadataBridgeJs(install: () -> Unit): PreparedMetadataBridgeJs

internal fun preparedMetadataBridge(): PreparedMetadataBridgeJs =
    requirePreparedMetadataBridgeJs { installEmbeddedPreparedMetadataScript() }
