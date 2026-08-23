@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package org.tiqian.web

import kotlinx.browser.document

// Bridge to the clipboard projection script (npm/core/utils/copy.js).
// The runtime bundle embeds that script via CopyBridgeGenerated.kt; the
// installer below runs the embedded script once and returns the installed
// handler installer from copy.js itself.

@JsFun("(install) => (install(), globalThis.__TiqianInstallCopyHandler)")
private external fun requireCopyHandlerInstallerJs(install: () -> Unit): dynamic

internal fun installTiqianCopyHandler() {
    val installer = requireCopyHandlerInstallerJs { installEmbeddedCopyScript() }
    installer(document)
}
