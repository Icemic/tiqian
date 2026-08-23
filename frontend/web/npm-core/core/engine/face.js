// Engine call face (ADR 0053 batch 5; decomposition report section 10). The
// custom element and the worker channel reach the engine only through these
// methods, exclusively via the TiqianEngine JsExport facade (ADR 0053 C1
// direct call channel). Calls before the runtime resolves are no-ops: every
// caller enhances only after loadTiqianRuntime has finished.
import { engineApi, workerApi } from "./loaders/runtime-loader.js";

// Polled worker facade for element.js: the TiqianWebWorkers export when
// resolved, null before that.
export function workerRuntime() {
  return workerApi();
}

export function destroy(root) {
  engineApi()?.destroy(root);
}

export function enhanceProgressively(root, options) {
  return engineApi()?.enhanceProgressively(root, options);
}

export function relayout(root) {
  engineApi()?.relayout(root);
}

export function detach(root) {
  engineApi()?.detach(root);
}

export function cancelLayoutWork(root) {
  engineApi()?.cancelLayoutWork(root);
}

function parseEngineJson(value) {
  try {
    return JSON.parse(value ?? "null");
  } catch {
    return null;
  }
}

export function probeContentDrift(root) {
  return parseEngineJson(engineApi()?.probeContentDrift(root));
}

export function reconcileContent(root, paragraphs) {
  return parseEngineJson(engineApi()?.reconcileContent(root, paragraphs));
}
