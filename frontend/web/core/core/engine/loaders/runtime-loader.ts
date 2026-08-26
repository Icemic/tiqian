import * as preparedDom from "../../sampler/snapshot/prepared-dom.js";
import type { PreparedDomValidatorInterface } from "../../sampler/snapshot/precomputed.js";
import * as tsRuntime from "./ts-runtime.js";
// Runtime loader for the Tiqian engine. The engine graph is built by
// ts-runtime.js through the concrete composition root; this module owns the
// page-wide bootstrap state and the accessor surface its consumers read.
//
// Consumer map (audited 2026-08-26):
// - loadTiqianRuntime/currentTiqianRuntime: element.ts (enhance waits on the
//   load), npm/api.ts (withTiqianRuntime drive, restore-path check).
// - engineApi/workerApi: face.ts (every engine call, ADR 0053 C1 direct
//   call face) and worker-channel.ts; workerApi additionally feeds the
//   workerRuntime facade for element.js.
// - setEngineOverride: test infrastructure only; the timing-golden drive
//   substitutes a recording stub engine through it.
// - copyInstaller: one per-page instance, memoized here. The element layer
//   installs it at module scope (element.ts, api.ts), the engine graph
//   installs it again at enhance time (engine-entry, progressive-drivers);
//   both must share the same per-document WeakSet.
// - prepared-dom record: the renderer module reference consumed by the
//   prepared pipeline (raw-dom, prepare-paragraph-layout,
//   commit-prepared-paragraph, content-reconcile, engine-entry,
//   font-loader) with its test override, plus the commit validator oracle.
//
// S5-bc: both records are registered in globalServices instead of living as
// module-scope singletons, so module copies in one document share them. A
// repeated loadTiqianRuntime call returns the memoized first engine, it does
// not build a second graph.

import type { TiqianEngineInstance, TiqianEngineWorkersInstance } from "../engine-entry.js";
import { createCopyInstaller } from "../../utils/copy.js";
import type { CopyInstaller } from "../../utils/copy.js";
import { globalServices } from "../../services/global-services.js";

export type RuntimeAction<T> = (engine: TiqianEngineInstance | null) => T;

// EngineLoadState: the record behind the loader accessors. The load memo and
// the installed engine/workers are per-bootstrap function-scope state;
// engineApi/workerApi read it, loadTiqianRuntime fills it.
type EngineLoadFn = () => Promise<unknown>;
type EngineCurrentFn = () => Promise<unknown> | undefined;
type EngineApiFn = () => TiqianEngineInstance | null;
type WorkerApiFn = () => TiqianEngineWorkersInstance | null;
type SetOverrideFn = (engine: TiqianEngineInstance | null | undefined) => void;
type GetCopyInstallerFn = () => CopyInstaller;

export type EngineLoadState = {
  load: EngineLoadFn;
  current: EngineCurrentFn;
  engineApi: EngineApiFn;
  workerApi: WorkerApiFn;
  setOverride: SetOverrideFn;
  getCopyInstaller: GetCopyInstallerFn;
};

export type RendererModuleFn = () => typeof preparedDom | null;
export type CommitValidatorFn = () => PreparedDomValidatorInterface | null;
export type SetRendererForTestFn = (renderer: typeof preparedDom | null | undefined) => void;
export type SetCommitValidatorForTestFn = (validator: PreparedDomValidatorInterface | null | undefined) => void;

// PreparedDomState: accessors for the prepared pipeline. The renderer
// reference is a static import, not a lazy load; the test override swaps the
// module and an explicit null makes rendererModule answer null. The commit
// validator oracle is a test-world instrument: it reads live geometry per
// node, production commits run without one (QA3 parity policy), test worlds
// install it through setCommitValidatorForTest.
export type PreparedDomState = {
  rendererModule: RendererModuleFn;
  commitValidator: CommitValidatorFn;
  setRendererForTest: SetRendererForTestFn;
  setCommitValidatorForTest: SetCommitValidatorForTestFn;
};

function createLoaderState(): EngineLoadState {
  let runtimePromise: Promise<unknown> | undefined;
  let engineInstance: TiqianEngineInstance | null = null;
  let workerInstance: TiqianEngineWorkersInstance | null = null;
  let engineOverride: TiqianEngineInstance | null = null;
  let copyInstallerInstance: CopyInstaller | null = null;

  // Builds the engine graph once and installs the resulting engine/workers
  // into this loader state.
  function buildRuntime(): Promise<unknown> {
    const entry = tsRuntime.engineEntry({ copyInstaller: getCopyInstaller() });
    engineInstance = entry.engine;
    workerInstance = entry.workers;
    return Promise.resolve(entry);
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

function createPreparedDomState(): PreparedDomState {
  let rendererOverride: typeof preparedDom | null | undefined = undefined;
  let validatorOverride: PreparedDomValidatorInterface | null | undefined = undefined;

  return {
    rendererModule: () => rendererOverride !== undefined ? rendererOverride : preparedDom,
    commitValidator: () => validatorOverride !== undefined ? validatorOverride : null,
    setRendererForTest: (renderer) => { rendererOverride = renderer; },
    setCommitValidatorForTest: (validator) => { validatorOverride = validator; },
  };
}

// S5-bc: the records are registered in globalServices instead of living as
// module-scope singletons. The accessor functions replace the former
// module-level consts; module copies in one document reach the same records.
globalServices().runtimeLoader = createLoaderState();
globalServices().preparedDom = createPreparedDomState();

function runtimeLoader(): EngineLoadState {
  const state = globalServices().runtimeLoader;
  if (!state) {
    throw new Error("runtime loader state not registered (runtime-loader.js must be imported first)");
  }
  return state;
}

function preparedDomState(): PreparedDomState {
  const state = globalServices().preparedDom;
  if (!state) {
    throw new Error("prepared-dom state not registered (runtime-loader.js must be imported first)");
  }
  return state;
}

// Hosts that run the element layer against their own engine implementation
// (the timing-golden drive substitutes a recording stub) install it here.
// The override wins over every resolved runtime export.
export function setEngineOverride(engine: TiqianEngineInstance | null | undefined): void {
  runtimeLoader().setOverride(engine);
}

// Direct engine call face (ADR 0053 C1): the engine entry built by the
// composition root replaces the document-level event channel for host-to-engine
// calls. Both accessors answer null until the runtime install resolves, so
// callers treat a null answer as "engine not ready" and stop there.
export function engineApi(): TiqianEngineInstance | null {
  return runtimeLoader().engineApi();
}

// Polled worker facade (WorkerPolledScheduling): the worker-prefixed methods
// installed on the engine entry by ts-runtime.
export function workerApi(): TiqianEngineWorkersInstance | null {
  return runtimeLoader().workerApi();
}

// Shared copy installer (one-listener-per-document invariant): the element
// layer installs the handler at module scope and the engine graph installs it
// again at enhance time; both must share the same per-document WeakSet, so the
// loader owns one instance and hands it to the composition root.
export function copyInstaller(): CopyInstaller {
  return runtimeLoader().getCopyInstaller();
}

export function loadTiqianRuntime(): Promise<unknown> {
  return runtimeLoader().load();
}

export function currentTiqianRuntime(): Promise<unknown> | undefined {
  return runtimeLoader().current();
}

export async function withTiqianRuntime<T>(action: RuntimeAction<T>): Promise<T> {
  await loadTiqianRuntime();
  return action(engineApi());
}

// Nullable slot read: the test override when installed (explicit null
// included), otherwise the statically imported module.
export function preparedDomRendererModule(): typeof preparedDom | null {
  return preparedDomState().rendererModule();
}

// The renderer module for consumers that need it: the override when present,
// otherwise the static import. Never null; font-loader's bridges resolve
// through this.
export function preparedDomRenderer(): typeof preparedDom {
  return preparedDomState().rendererModule() ?? preparedDom;
}

// Commit validator oracle for the prepared pipeline: null in production,
// installed by test worlds.
export function commitValidator(): PreparedDomValidatorInterface | null {
  return preparedDomState().commitValidator();
}

export function setPreparedDomRendererForTest(renderer: typeof preparedDom | null | undefined): void {
  preparedDomState().setRendererForTest(renderer);
}

export function setCommitValidatorForTest(validator: PreparedDomValidatorInterface | null | undefined): void {
  preparedDomState().setCommitValidatorForTest(validator);
}
