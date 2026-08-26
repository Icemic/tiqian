// Pure TS installer for the Tiqian engine (Slice 7 Lane A).
// This module replaces the bundle init block. It is the concrete composition
// root: it builds the four stateful engine graph products (raw-DOM, copy
// installer, root-state, layout-job-pool) that the named engine functions
// receive as explicit dependencies. After bundle retirement this is the
// sole installation entry point; calling buildTiqianRuntimeGraph() again
// builds a fresh graph with its own rawDom, job pool and root-state.
// R10 dissolved the former engineEntry()/createEngineEntry facade assembly:
// buildTiqianRuntimeGraph replaces engineEntry and returns the plain graph.

// Engine scripts - order matches build.gradle.kts bridge list for review.
// The modules below no longer self-install on globalThis; the composition
// root builds the graph products instead.
import { deriveRawDom } from "../raw-dom.js";
import type { RawDomApi } from "../raw-dom.js";
import { createCopyInstaller } from "../../utils/copy.js";
import type { CopyInstaller } from "../../utils/copy.js";
import { createLayoutJobPool } from "../layout-job-pool.js";
import type { LayoutJobPool } from "../layout-job-pool.js";
import { createRootState } from "../root-state.js";
import type { RootStateApi } from "../root-state.js";

// The four stateful engine graph products (R10 ruling 4): every named engine
// function receives the products it needs from this record; no facade object
// bundles them behind method closures.
export interface TiqianRuntimeGraph {
  rawDom: RawDomApi;
  copyInstaller: CopyInstaller;
  rootState: RootStateApi;
  layoutJobPool: LayoutJobPool;
}

// RuntimeGraphOptions: the concrete composition root accepts an optional
// externally-owned copy installer so the page-level copy handler is shared
// with the element layer's own module-scope install.
export interface RuntimeGraphOptions {
  copyInstaller?: CopyInstaller;
}

// Construct the whole engine object graph: rawDom, copy installer,
// root-state and layout-job-pool. Every product is built here.
export function buildTiqianRuntimeGraph(options?: RuntimeGraphOptions): TiqianRuntimeGraph {
  return {
    rawDom: deriveRawDom(),
    copyInstaller: options && options.copyInstaller
      ? options.copyInstaller
      : createCopyInstaller(),
    rootState: createRootState(),
    layoutJobPool: createLayoutJobPool(),
  };
}
