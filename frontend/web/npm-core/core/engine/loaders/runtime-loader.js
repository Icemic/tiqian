// Runtime loader for the Tiqian engine (Slice 7 Lane A). After bundle
// retirement the 21 engine scripts and ffi binding are installed by
// ts-runtime.js; this module re-exports the installation promise and
// provides the engine/worker accessors that element.js depends on.

let runtimePromise;

let engineInstance;
let workerInstance;
let engineOverride;

// Hosts that run the element layer against their own engine implementation
// (the timing-golden drive substitutes a recording stub) install it here.
// The override wins over every resolved runtime export.
export function setEngineOverride(engine) {
  engineOverride = engine ?? null;
}

// Direct engine call face (ADR 0053 C1): the engine entry installed by
// ts-runtime.js replaces the document-level event channel for host-to-engine
// calls. Both accessors answer null until the runtime install resolves, so
// callers treat a null answer as "engine not ready" and stop there.
export function engineApi() {
  if (engineOverride) return engineOverride;
  var tsEngine = globalThis.__TiqianEngine;
  if (tsEngine) return tsEngine;
  return null;
}

// Polled worker facade (WorkerPolledScheduling): the worker-prefixed methods
// installed on globalThis.__TiqianEngineWorkers by engine-entry.js.
export function workerApi() {
  var tsWorkers = globalThis.__TiqianEngineWorkers;
  if (tsWorkers) return tsWorkers;
  return null;
}

export function loadTiqianRuntime() {
  runtimePromise ??= import("./ts-runtime.js");
  return runtimePromise;
}

export function currentTiqianRuntime() {
  return runtimePromise;
}

export async function withTiqianRuntime(action) {
  await loadTiqianRuntime();
  return action(engineApi());
}
