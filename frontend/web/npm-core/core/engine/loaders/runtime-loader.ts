// Runtime loader for the Tiqian engine (Slice 7 Lane A). After bundle
// retirement the engine graph is built by ts-runtime.js through the concrete
// composition root; this module re-exports the installation promise and
// provides the engine/worker accessors that element.js depends on.
//
// Per-document state: the loader is the host-boundary anchor of the single
// engine bootstrap - one load memo, the installed engine/worker handles, the
// timing-golden override seam, and the copy installer shared between the
// element layer and the engine graph (one-listener-per-document invariant).
// All of it lives in the closure-scoped loader state; a repeated
// loadTiqianRuntime call returns the memoized first engine, it does not
// build a second graph.

import type { TiqianEngineInstance, TiqianEngineWorkersInstance } from "../engine-entry.js";
import { createCopyInstaller } from "../../utils/copy.js";
import type { CopyInstaller } from "../../utils/copy.js";

export type RuntimeAction<T> = (engine: TiqianEngineInstance | null) => T;

type EngineLoadFn = () => Promise<unknown>;
type EngineCurrentFn = () => Promise<unknown> | undefined;
type EngineApiFn = () => TiqianEngineInstance | null;
type WorkerApiFn = () => TiqianEngineWorkersInstance | null;
type SetOverrideFn = (engine: TiqianEngineInstance | null | undefined) => void;
type GetCopyInstallerFn = () => CopyInstaller;

// EngineLoadState: the mutable record behind the loader accessors. The load
// memo and the installed engine/workers are per-bootstrap function-scope
// state; engineApi/workerApi read it, loadTiqianRuntime fills it.
type EngineLoadState = {
  load: EngineLoadFn;
  current: EngineCurrentFn;
  engineApi: EngineApiFn;
  workerApi: WorkerApiFn;
  setOverride: SetOverrideFn;
  getCopyInstaller: GetCopyInstallerFn;
};

function createLoaderState(): EngineLoadState {
  let runtimePromise: Promise<unknown> | undefined;
  let engineInstance: TiqianEngineInstance | null = null;
  let workerInstance: TiqianEngineWorkersInstance | null = null;
  let engineOverride: TiqianEngineInstance | null = null;
  let copyInstallerInstance: CopyInstaller | null = null;

  // Builds the engine graph once and installs the resulting engine/workers
  // into this loader state.
  async function buildRuntime(): Promise<unknown> {
    const tsRuntime = await import("./ts-runtime.js");
    const entry = tsRuntime.engineEntry({ copyInstaller: getCopyInstaller() });
    engineInstance = entry.engine;
    workerInstance = entry.workers;
    return entry;
  }

  function load(): Promise<unknown> {
    runtimePromise ??= buildRuntime();
    return runtimePromise;
  }

  function current(): Promise<unknown> | undefined {
    return runtimePromise;
  }

  function engineApi(): TiqianEngineInstance | null {
    return engineOverride ?? engineInstance;
  }

  function workerApi(): TiqianEngineWorkersInstance | null {
    return workerInstance;
  }

  function setOverride(engine: TiqianEngineInstance | null | undefined): void {
    engineOverride = engine ?? null;
  }

  function getCopyInstaller(): CopyInstaller {
    copyInstallerInstance ??= createCopyInstaller();
    return copyInstallerInstance;
  }

  return {
    load: load,
    current: current,
    engineApi: engineApi,
    workerApi: workerApi,
    setOverride: setOverride,
    getCopyInstaller: getCopyInstaller,
  };
}

const loaderState: EngineLoadState = createLoaderState();

// Hosts that run the element layer against their own engine implementation
// (the timing-golden drive substitutes a recording stub) install it here.
// The override wins over every resolved runtime export.
export function setEngineOverride(engine: TiqianEngineInstance | null | undefined): void {
  loaderState.setOverride(engine);
}

// Direct engine call face (ADR 0053 C1): the engine entry built by the
// composition root replaces the document-level event channel for host-to-engine
// calls. Both accessors answer null until the runtime install resolves, so
// callers treat a null answer as "engine not ready" and stop there.
export function engineApi(): TiqianEngineInstance | null {
  return loaderState.engineApi();
}

// Polled worker facade (WorkerPolledScheduling): the worker-prefixed methods
// installed on the engine entry by ts-runtime.
export function workerApi(): TiqianEngineWorkersInstance | null {
  return loaderState.workerApi();
}

// Shared copy installer (one-listener-per-document invariant): the element
// layer installs the handler at module scope and the engine graph installs it
// again at enhance time; both must share the same per-document WeakSet, so the
// loader owns one instance and hands it to the composition root.
export function copyInstaller(): CopyInstaller {
  return loaderState.getCopyInstaller();
}

export function loadTiqianRuntime(): Promise<unknown> {
  return loaderState.load();
}

export function currentTiqianRuntime(): Promise<unknown> | undefined {
  return loaderState.current();
}

export async function withTiqianRuntime<T>(action: RuntimeAction<T>): Promise<T> {
  await loadTiqianRuntime();
  return action(engineApi());
}