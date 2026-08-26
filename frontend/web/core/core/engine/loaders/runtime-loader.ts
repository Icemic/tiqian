import * as preparedDom from "../../sampler/snapshot/prepared-dom.js";
import type { PreparedDomValidatorInterface } from "../../sampler/snapshot/precomputed.js";
import * as tsRuntime from "./ts-runtime.js";
import type { TiqianRuntimeGraph } from "./ts-runtime.js";
// Runtime loader for the Tiqian engine. The engine graph is built by
// ts-runtime.js through the concrete composition root; this module owns the
// page-wide load memo and the accessor surface its consumers read.
//
// Consumer map (audited 2026-08-26, R10 dissolution):
// - loadTiqianRuntime: element.ts (enhance waits on the load), npm/api.ts
//   (every public entry awaits the graph before driving the named engine
//   functions).
// - tiqianRuntimeGraph: element.ts fire-and-forget paths (destroy, detach,
//   relayout, reconcile, cancel) that run from observer callbacks after the
//   load resolved and read the graph synchronously.
// - currentTiqianRuntime: npm/api.ts restore-path check.
// - copyInstaller: one per-page instance, memoized here. The element layer
//   installs it at module scope (element.ts, api.ts), the named engine
//   functions install it again at enhance time; both must share the same
//   per-document WeakSet.
// - prepared-dom record: the renderer module reference consumed by the
//   prepared pipeline (raw-dom, prepare-paragraph-layout,
//   commit-prepared-paragraph, content-reconcile, lifecycle, font-loader)
//   with its test override, plus the commit validator oracle.
//
// R10 ruling 4: loadTiqianRuntime memoizes the plain runtime graph
// {rawDom, copyInstaller, rootState, layoutJobPool}; there is no engine
// facade object and no engineApi/workerApi/setEngineOverride surface. The
// load memo lives in this module's closure; the memo survives module
// re-evaluation within one document through the Symbol.for registry (client
// routers, dev HMR and duplicated package chunks can evaluate this module
// more than once), mirroring the global-services container rationale. The
// prepared-dom state stays registered in globalServices because it is
// consumed from modules that never import the loader for its load memo.

import { createCopyInstaller } from "../../utils/copy.js";
import type { CopyInstaller } from "../../utils/copy.js";
import { globalServices } from "../../services/global-services.js";

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

// RuntimeLoadMemo: the per-document load state shared across module copies
// through the Symbol.for registry. graphOverride is the library-internal
// test seam installed by installTiqianRuntimeGraphForTesting.
interface RuntimeLoadMemo {
  load: Promise<TiqianRuntimeGraph> | undefined;
  graph: TiqianRuntimeGraph | null;
  graphOverride: TiqianRuntimeGraph | null | undefined;
  copyInstaller: CopyInstaller | null;
}

const RUNTIME_LOAD_MEMO_KEY: unique symbol = Symbol.for("@tiqian/prose.runtime-load-memo.v1");

type RuntimeLoadMemoRegistry = Record<symbol, RuntimeLoadMemo | undefined>;

function loadMemo(): RuntimeLoadMemo {
  const registry = globalThis as RuntimeLoadMemoRegistry;
  return registry[RUNTIME_LOAD_MEMO_KEY] ??= {
    load: undefined,
    graph: null,
    graphOverride: undefined,
    copyInstaller: null,
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

// The prepared-dom record is registered in globalServices (S5-bc): module
// copies in one document reach the same record, and consumers that never
// load the runtime still resolve the renderer through it.
globalServices().preparedDom = createPreparedDomState();

function preparedDomState(): PreparedDomState {
  const state = globalServices().preparedDom;
  if (!state) {
    throw new Error("prepared-dom state not registered (runtime-loader.js must be imported first)");
  }
  return state;
}

// Shared copy installer (one-listener-per-document invariant): the element
// layer installs the handler at module scope and the named engine functions
// install it again at enhance time; both must share the same per-document
// WeakSet, so the loader owns one instance and hands it to the composition
// root.
export function copyInstaller(): CopyInstaller {
  const memo = loadMemo();
  memo.copyInstaller ??= createCopyInstaller();
  return memo.copyInstaller;
}

// Loads the runtime graph once per document and memoizes it. A repeated call
// returns the memoized first graph; it does not build a second one. An
// installed test graph wins over a fresh build.
export function loadTiqianRuntime(): Promise<TiqianRuntimeGraph> {
  const memo = loadMemo();
  memo.load ??= Promise.resolve(memo.graphOverride ?? buildRuntimeGraph());
  return memo.load;
}

function buildRuntimeGraph(): TiqianRuntimeGraph {
  const memo = loadMemo();
  const graph = tsRuntime.buildTiqianRuntimeGraph({ copyInstaller: copyInstaller() });
  memo.graph = graph;
  return graph;
}

export function currentTiqianRuntime(): Promise<TiqianRuntimeGraph> | undefined {
  return loadMemo().load;
}

// Synchronous graph accessor for fire-and-forget element paths that run from
// observer callbacks after the load resolved. Answers null before the first
// load (or override install); callers treat a null answer as "runtime not
// ready" and stop there.
export function tiqianRuntimeGraph(): TiqianRuntimeGraph | null {
  const memo = loadMemo();
  return memo.graphOverride ?? memo.graph;
}

export type RuntimeGraphRestoreFn = () => void;

// Library-internal, test-only injection: installs a substitute runtime graph
// (the timing-golden drive records through graph products) and returns a
// restore function. An installed graph also answers loadTiqianRuntime until
// restored. Hosts must not touch it.
export function installTiqianRuntimeGraphForTesting(graph: TiqianRuntimeGraph | null): RuntimeGraphRestoreFn {
  const memo = loadMemo();
  const previousOverride = memo.graphOverride;
  const previousLoad = memo.load;
  memo.graphOverride = graph;
  if (graph) memo.load ??= Promise.resolve(graph);
  return () => {
    memo.graphOverride = previousOverride;
    memo.load = previousLoad;
  };
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
