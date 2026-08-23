// Kotlin runtime loader (ADR 0053 batch 3). The compiled bundle stays at
// runtime/ in the package root (publishing layout), so the dynamic import
// below is resolved relative to the root from this subdirectory.
let runtimePromise;
let runtimeModule;

function resolveExport(name) {
  // Kotlin IR object singletons sit behind getInstance(); the UMD branch
  // exposes them as globalThis.web. Bundler imports carry them on the
  // module namespace directly.
  const facade = runtimeModule?.[name] ??
    runtimeModule?.default?.[name] ??
    globalThis.web?.[name];
  return facade?.getInstance?.() ?? facade ?? null;
}

let engineInstance;
let workerInstance;
let engineOverride;

// Hosts that run the element layer against their own engine implementation
// (the timing-golden drive substitutes a recording stub) install it here.
// The override wins over every resolved runtime export.
export function setEngineOverride(engine) {
  engineOverride = engine ?? null;
}

// Direct engine call face (ADR 0053 C1): the TiqianEngine JsExport facade
// replaces the document-level event channel for host-to-engine calls. Both
// accessors answer null until the runtime exports resolve, so callers treat
// a null answer as "engine not ready" and stop there.
export function engineApi() {
  if (engineOverride) return engineOverride;
  engineInstance ??= resolveExport("TiqianEngine");
  return engineInstance;
}

// Polled worker facade (WorkerPolledScheduling): an IR object singleton
// behind getInstance; the UMD branch exposes it as globalThis.web.
export function workerApi() {
  workerInstance ??= resolveExport("TiqianWebWorkers");
  return workerInstance;
}

export function loadTiqianRuntime() {
  runtimePromise ??= import("../../../runtime/tiqian-web.js").then((module) => {
    runtimeModule = module;
    return module;
  });
  return runtimePromise;
}

export function currentTiqianRuntime() {
  return runtimePromise;
}

export async function withTiqianRuntime(action) {
  await loadTiqianRuntime();
  return action(engineApi());
}
