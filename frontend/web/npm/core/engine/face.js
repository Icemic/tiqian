// Engine call face (ADR 0053 batch 5; decomposition report section 10).
// The custom element and the worker channel reach the engine only through
// these methods. Bridge-covered entries prefer the TiqianWeb method (the
// Kotlin bridge re-dispatches the same document event with the same detail);
// without a loaded runtime every method falls back to dispatching the
// document event itself, so hosts without the runtime keep the compat event
// stream. This module owns one of the two remaining globalThis.TiqianWeb
// reads (the other lives in web-worker/worker-channel.js).
import { dispatch } from "./coordinator/coordinator.js";

export function tiqianBridge() {
  return globalThis.TiqianWeb;
}

export function destroy(root) {
  const bridge = tiqianBridge();
  if (typeof bridge?.destroy === "function") return bridge.destroy(root);
  dispatch("tiqian:destroy", root);
}

export function enhanceProgressively(root, options) {
  const bridge = tiqianBridge();
  if (typeof bridge?.enhanceProgressively === "function") {
    return bridge.enhanceProgressively(root, options);
  }
  dispatch("tiqian:enhance-progressively", root, options);
}

export function relayout(root) {
  dispatch("tiqian:relayout", root);
}

export function detach(root) {
  dispatch("tiqian:detach", root);
}

export function cancelLayoutWork(root) {
  dispatch("tiqian:cancel-layout-work", root);
}

export function probeContentDrift(root) {
  const event = new CustomEvent("tiqian:probe-content-drift", { detail: { root } });
  document.dispatchEvent(event);
  try {
    return event.detail?.result ? JSON.parse(event.detail.result) : null;
  } catch {
    return null;
  }
}

export function reconcileContent(root, paragraphs) {
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
