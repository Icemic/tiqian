// Runtime loader for the Tiqian engine (Slice 7 Lane A). After bundle
// retirement the 21 engine scripts and ffi binding are installed by
// ts-runtime.js; this module re-exports the installation promise and
// provides the engine/worker accessors that element.js depends on.

import type { TiqianEngineInstance, TiqianEngineWorkersInstance } from "../engine-entry.js";

let runtimePromise: Promise<unknown> | undefined;

let engineInstance: TiqianEngineInstance | null | undefined;
let workerInstance: TiqianEngineWorkersInstance | null | undefined;
let engineOverride: TiqianEngineInstance | null | undefined;

export type RuntimeAction<T> = (engine: TiqianEngineInstance | null) => T;

// Hosts that run the element layer against their own engine implementation
// (the timing-golden drive substitutes a recording stub) install it here.
// The override wins over every resolved runtime export.
export function setEngineOverride(engine: TiqianEngineInstance | null | undefined): void {
  engineOverride = engine ?? null;
}

// Direct engine call face (ADR 0053 C1): the engine entry installed by
// ts-runtime.js replaces the document-level event channel for host-to-engine
// calls. Both accessors answer null until the runtime install resolves, so
// callers treat a null answer as "engine not ready" and stop there.
export function engineApi(): TiqianEngineInstance | null {
  if (engineOverride) return engineOverride;
  const tsEngine = globalThis.__TiqianEngine;
  if (tsEngine) return tsEngine;
  return null;
}

// Polled worker facade (WorkerPolledScheduling): the worker-prefixed methods
// installed on globalThis.__TiqianEngineWorkers by engine-entry.js.
export function workerApi(): TiqianEngineWorkersInstance | null {
  const tsWorkers = globalThis.__TiqianEngineWorkers;
  if (tsWorkers) return tsWorkers;
  return null;
}

export function loadTiqianRuntime(): Promise<unknown> {
  runtimePromise ??= import("./ts-runtime.js");
  return runtimePromise;
}

export function currentTiqianRuntime(): Promise<unknown> | undefined {
  return runtimePromise;
}

export async function withTiqianRuntime<T>(action: RuntimeAction<T>): Promise<T> {
  await loadTiqianRuntime();
  return action(engineApi());
}
