// Pure TS installer for the Tiqian engine (ADR 0053 Slice 7 installation
// path).
// This module replaces the bundle init block. It is the concrete composition
// root: it builds the root-state product that the named engine functions
// receive as an explicit dependency. After bundle retirement this is the sole
// installation entry point; calling buildTiqianRuntimeGraph() again builds a
// fresh graph with its own root-state.
// R10 dissolved the former engineEntry()/createEngineEntry facade assembly:
// buildTiqianRuntimeGraph replaces engineEntry and returns the plain graph.

// Engine scripts - order matches build.gradle.kts bridge list for review.
// The modules below no longer self-install on globalThis; the composition
// root builds the graph products instead.
import { createRootState } from "../root-state.js";
import type { RootStateApi } from "../root-state.js";

// The stateful engine graph product (R10 ruling 4): every named engine
// function receives the products it needs from this record; no facade object
// bundles them behind method closures.
export interface TiqianRuntimeGraph {
  rootState: RootStateApi;
}

// Construct the engine object graph: root-state.
// Every product is built here.
export function buildTiqianRuntimeGraph(): TiqianRuntimeGraph {
  return {
    rootState: createRootState(),
  };
}
