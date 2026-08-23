// Engine call face (ADR 0053 batch 5; decomposition report section 10). The
// custom element and the worker channel reach the engine only through these
// methods. Every entry prefers the TiqianEngine JsExport facade (ADR 0053 C1
// direct call channel), falls back to a TiqianWeb bridge method, and finally
// to dispatching the document event, so hosts that loaded the runtime
// themselves keep the compat event stream until that channel is retired.
import { dispatch } from "./coordinator/coordinator.js";
import { engineApi, workerApi } from "./loaders/runtime-loader.js";

export function tiqianBridge() {
  return globalThis.TiqianWeb;
}

// Polled worker facade for element.js and the coordinator: the TiqianWebWorkers
// export when resolved, the bridge-mounted methods otherwise.
export function workerRuntime() {
  return workerApi() ?? tiqianBridge();
}

export function destroy(root) {
  const engine = engineApi();
  if (engine) return engine.destroy(root);
  const bridge = tiqianBridge();
  if (typeof bridge?.destroy === "function") return bridge.destroy(root);
  dispatch("tiqian:destroy", root);
}

export function enhanceProgressively(root, options) {
  const engine = engineApi();
  if (engine) return engine.enhanceProgressively(root, options);
  const bridge = tiqianBridge();
  if (typeof bridge?.enhanceProgressively === "function") {
    return bridge.enhanceProgressively(root, options);
  }
  dispatch("tiqian:enhance-progressively", root, options);
}

export function relayout(root) {
  const engine = engineApi();
  if (engine) return engine.relayout(root);
  dispatch("tiqian:relayout", root);
}

export function detach(root) {
  const engine = engineApi();
  if (engine) return engine.detach(root);
  dispatch("tiqian:detach", root);
}

export function cancelLayoutWork(root) {
  const engine = engineApi();
  if (engine) return engine.cancelLayoutWork(root);
  dispatch("tiqian:cancel-layout-work", root);
}

export function probeContentDrift(root) {
  const engine = engineApi();
  if (engine) {
    try {
      return JSON.parse(engine.probeContentDrift(root) ?? "null");
    } catch {
      return null;
    }
  }
  const event = new CustomEvent("tiqian:probe-content-drift", { detail: { root } });
  document.dispatchEvent(event);
  try {
    return event.detail?.result ? JSON.parse(event.detail.result) : null;
  } catch {
    return null;
  }
}

export function reconcileContent(root, paragraphs) {
  const engine = engineApi();
  if (engine) {
    try {
      return JSON.parse(engine.reconcileContent(root, paragraphs) ?? "null");
    } catch {
      return null;
    }
  }
  const event = new CustomEvent("tiqian:reconcile-content", {
    detail: { root, options: { paragraphs } },
  });
  document.dispatchEvent(event);
  try {
    return event.detail?.result ? JSON.parse(event.detail.result) : null;
  } catch {
    return null;
  }
}
