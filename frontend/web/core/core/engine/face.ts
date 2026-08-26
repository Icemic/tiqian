// Engine call face (ADR 0053 batch 5; decomposition report section 10). The
// custom element and the worker channel reach the engine only through these
// methods, exclusively via the TiqianEngine JsExport facade (ADR 0053 C1
// direct call channel). Calls before the runtime resolves are no-ops: every
// caller enhances only after loadTiqianRuntime has finished.
import type { TiqianEngineWorkersInstance } from "./engine-entry.js";
import { engineApi, workerApi } from "./loaders/runtime-loader.js";

export interface ContentDriftProbeResult {
  unknown: number;
  drifted: number;
  dead: number;
  rawDom: number;
}

export interface ContentReconcileResult {
  outcome: string;
  drifted: number;
  rawDom: number;
  tainted: number;
  stranded: number;
  dead: number;
}

// Polled worker facade for element.js: the TiqianWebWorkers export when
// resolved, null before that.
export function workerRuntime(): TiqianEngineWorkersInstance | null {
  return workerApi();
}

export function destroy(root: HTMLElement): void {
  engineApi()?.destroy(root);
}

export function enhanceProgressively(root: HTMLElement, options?: unknown): void {
  return engineApi()?.enhanceProgressively(root, options);
}

export function relayout(root: HTMLElement): void {
  engineApi()?.relayout(root);
}

export function detach(root: HTMLElement): void {
  engineApi()?.detach(root);
}

export function cancelLayoutWork(root: HTMLElement): void {
  engineApi()?.cancelLayoutWork(root);
}

function parseEngineJson<T>(value: string | undefined): T | null {
  try {
    return JSON.parse(value ?? "null") as T;
  } catch {
    return null;
  }
}

export function probeContentDrift(root: HTMLElement): ContentDriftProbeResult | null {
  return parseEngineJson<ContentDriftProbeResult>(engineApi()?.probeContentDrift(root));
}

export function reconcileContent(root: HTMLElement, paragraphs?: Element[]): ContentReconcileResult | null {
  return parseEngineJson<ContentReconcileResult>(engineApi()?.reconcileContent(root, paragraphs ?? []));
}
