import assert from "node:assert/strict";
import test from "node:test";

import {
  currentTiqianRuntime,
  installTiqianRuntimeGraphForTesting,
  loadTiqianRuntime,
  tiqianRuntimeGraph,
} from "../core/engine/loaders/runtime-loader.js";
import { buildTiqianRuntimeGraph } from "../core/engine/loaders/ts-runtime.js";

// The runtime loader owns the single memoized runtime graph. Before the load
// promise resolves the synchronous accessor answers null; after it resolves
// the graph stays stable for the process. The composition root's
// buildTiqianRuntimeGraph() itself builds a fresh graph per call, so direct
// builds are distinct from the loader's graph.

test("tiqianRuntimeGraph answers null before the runtime is loaded", () => {
  assert.equal(tiqianRuntimeGraph(), null);
});

test("currentTiqianRuntime is undefined before load and returns the memoized promise once started", async () => {
  assert.equal(currentTiqianRuntime(), undefined);
  const promise = loadTiqianRuntime();
  const current = currentTiqianRuntime();
  assert.equal(current, promise);
  await promise;
  assert.equal(currentTiqianRuntime(), promise);
});

test("loadTiqianRuntime memoizes: repeated calls return the same promise", async () => {
  const first = loadTiqianRuntime();
  const second = loadTiqianRuntime();
  assert.equal(first, second);
  await first;
  const after = loadTiqianRuntime();
  assert.equal(after, first);
});

test("tiqianRuntimeGraph becomes the loaded graph and stays stable after load", async () => {
  const graph = await loadTiqianRuntime();
  assert.ok(graph, "loadTiqianRuntime must resolve a graph");
  assert.equal(tiqianRuntimeGraph(), graph);
  assert.equal(tiqianRuntimeGraph(), graph);
});

test("the loaded graph exposes the four plain products", async () => {
  const graph = await loadTiqianRuntime();
  assert.ok(graph.rawDom, "rawDom product");
  assert.ok(graph.copyInstaller, "copyInstaller product");
  assert.ok(graph.rootState, "rootState product");
  assert.ok(graph.layoutJobPool, "layoutJobPool product");
});

test("installTiqianRuntimeGraphForTesting substitutes the graph and restore returns the loaded one", async () => {
  const loaded = await loadTiqianRuntime();
  const fakeGraph = { rawDom: {}, copyInstaller: {}, rootState: {}, layoutJobPool: {} };
  const restore = installTiqianRuntimeGraphForTesting(fakeGraph);
  assert.equal(tiqianRuntimeGraph(), fakeGraph);
  restore();
  assert.equal(tiqianRuntimeGraph(), loaded);
});

test("buildTiqianRuntimeGraph builds a fresh graph per call, distinct from the loader graph", async () => {
  const loaded = await loadTiqianRuntime();
  const first = buildTiqianRuntimeGraph();
  const second = buildTiqianRuntimeGraph();
  assert.notEqual(first, second);
  assert.notEqual(first.rootState, second.rootState);
  assert.notEqual(first.layoutJobPool, second.layoutJobPool);
  assert.notEqual(first.rootState, loaded.rootState);
});
