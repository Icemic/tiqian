import * as preparedDom from "../../sampler/snapshot/prepared-dom.js";
import type { PreparedDomValidatorInterface } from "../../sampler/snapshot/precomputed.js";
import * as tsRuntime from "./ts-runtime.js";
// Runtime loader for the Tiqian engine (Slice 7 Lane A). After bundle
// retirement the engine graph is built by ts-runtime.js through the concrete
// composition root; this module re-exports the installation promise and
// provides the engine/worker accessors that element.js depends on.
//
// Per-document state lives in two records under globalServices (S5-bc): the
// engine loader is the host-boundary anchor of the single engine bootstrap -
// one load memo, the installed engine/worker handles, the timing-golden
// override seam, and the copy installer shared between the element layer and
// the engine graph (one-listener-per-document invariant). The prepared-dom
// record holds the renderer module accessors and the snapshot validator seam;
// it is not engine bootstrap state, so it keeps its own
// globalServices().preparedDom slot. A repeated loadTiqianRuntime call
// returns the memoized first engine, it does not build a second graph.

import type { TiqianEngineInstance, TiqianEngineWorkersInstance } from "../engine-entry.js";
import { createCopyInstaller } from "../../utils/copy.js";
import type { CopyInstaller } from "../../utils/copy.js";
import { globalServices } from "../../services/global-services.js";

export type RuntimeAction<T> = (engine: TiqianEngineInstance | null) => T;

// EngineLoadState: the mutable record behind the loader accessors. The load
// memo and the installed engine/workers are per-bootstrap function-scope
// state; engineApi/workerApi read it, loadTiqianRuntime fills it.
type EngineLoadFn = () => Promise<unknown>;
type EngineCurrentFn = () => Promise<unknown> | undefined;
type EngineApiFn = () => TiqianEngineInstance | null;
type WorkerApiFn = () => TiqianEngineWorkersInstance | null;
type SetOverrideFn = (engine: TiqianEngineInstance | null | undefined) => void;
type GetCopyInstallerFn = () => CopyInstaller;

export type LoadRendererFn = () => Promise<typeof preparedDom>;
export type RendererModuleFn = () => typeof preparedDom | null;
export type ValidatorFn = () => PreparedDomValidatorInterface | null;
export type SetRendererForTestFn = (renderer: typeof preparedDom | null | undefined) => void;
export type SetValidatorForTestFn = (validator: PreparedDomValidatorInterface | null | undefined) => void;

type EngineLoadState = {
  load: EngineLoadFn;
  current: EngineCurrentFn;
  engineApi: EngineApiFn;
  workerApi: WorkerApiFn;
  setOverride: SetOverrideFn;
  getCopyInstaller: GetCopyInstallerFn;
};

// PreparedDomState: the renderer module accessors for the prepared-dom
// pipeline plus the snapshot validator seam. The validator is a test-world
// oracle: it reads live geometry per node (gBCR/getComputedStyle per line)
// and releases valid commits on transient mismatches, so production runs
// without it and test worlds install it through setValidatorForTest.
type PreparedDomState = {
  loadRenderer: LoadRendererFn;
  rendererModule: RendererModuleFn;
  validator: ValidatorFn;
  setRendererForTest: SetRendererForTestFn;
  setValidatorForTest: SetValidatorForTestFn;
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

  function loadRenderer(): Promise<typeof preparedDom> {
    return Promise.resolve(rendererOverride !== undefined ? (rendererOverride ?? preparedDom) : preparedDom);
  }

  return {
    loadRenderer,
    rendererModule: () => rendererOverride !== undefined ? rendererOverride : preparedDom,
    validator: () => validatorOverride !== undefined ? validatorOverride : null,
    setRendererForTest: (renderer) => { rendererOverride = renderer; },
    setValidatorForTest: (validator) => { validatorOverride = validator; }
  };
}

// S5-bc: the loader state is registered in globalServices().runtimeLoader
// instead of living as a module-scope singleton. The accessor function
// replaces the former module-level const. The prepared-dom record registers
// into its own slot for the same reason: module copies in one document share
// one container, so the renderer/validator overrides stay page-wide.
globalServices().runtimeLoader = createLoaderState();
globalServices().preparedDom = createPreparedDomState();

function runtimeLoader(): EngineLoadState {
  return globalServices().runtimeLoader as EngineLoadState;
}

function preparedDomState(): PreparedDomState {
  return globalServices().preparedDom as PreparedDomState;
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
export type PreparedDomRendererModuleGetter = () => typeof preparedDom | null;
export const getPreparedDomRendererModule: PreparedDomRendererModuleGetter = () => preparedDomState().rendererModule();

export function loadPreparedDomRenderer(): Promise<typeof preparedDom> {
  return preparedDomState().loadRenderer();
}
export function preparedDomRendererModule(): typeof preparedDom | null {
  return preparedDomState().rendererModule();
}
export function preparedDomValidator(): PreparedDomValidatorInterface | null {
  return preparedDomState().validator();
}
export function setPreparedDomRendererForTest(renderer: typeof preparedDom | null | undefined): void {
  preparedDomState().setRendererForTest(renderer);
}
export function setPreparedDomValidatorForTest(validator: PreparedDomValidatorInterface | null | undefined): void {
  preparedDomState().setValidatorForTest(validator);
}
