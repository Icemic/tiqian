package org.tiqian.web

// Bridge to the JS worker layout request serialization
// (npm/core/engine/worker-request.js). The runtime bundle embeds that script
// via WorkerRequestBridgeGenerated.kt; the dispatcher below installs it on
// first use and returns the installed API.

internal external interface WorkerRequestBridgeJs {
    fun workerLayoutRequest(paragraph: org.w3c.dom.HTMLElement, lowered: JsAny?, options: JsAny?): String?
}

@JsFun("(install) => (globalThis.__TiqianWorkerRequest || (install(), globalThis.__TiqianWorkerRequest))")
private external fun requireWorkerRequestBridgeJs(install: () -> Unit): WorkerRequestBridgeJs

internal fun workerRequestBridge(): WorkerRequestBridgeJs =
    requireWorkerRequestBridgeJs { installEmbeddedWorkerRequestScript() }
