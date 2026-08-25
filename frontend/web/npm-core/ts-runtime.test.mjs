import assert from "node:assert/strict";
import test from "node:test";

import {
  currentTiqianRuntime,
  engineApi,
  loadTiqianRuntime,
  setEngineOverride,
  workerApi,
} from "./core/engine/loaders/runtime-loader.js";
import { engineEntry } from "./core/engine/loaders/ts-runtime.js";

// The runtime loader owns the single memoized engine graph. Before the load
// promise resolves the accessors answer null; after it resolves they stay
// stable for the process. The composition root's engineEntry() itself builds a
// fresh graph per call, so direct calls are distinct from the loader's engine.

test("engineApi and workerApi answer null before the runtime is loaded", () => {
  assert.equal(engineApi(), null);
  assert.equal(workerApi(), null);
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

test("engineApi and workerApi become non-null and stable after load", async () => {
  await loadTiqianRuntime();
  const engine = engineApi();
  const workers = workerApi();
  assert.ok(engine, "engineApi() must be non-null after load");
  assert.ok(workers, "workerApi() must be non-null after load");
  assert.equal(engineApi(), engine);
  assert.equal(workerApi(), workers);
});

test("setEngineOverride substitutes the engineApi face and restoring returns the loaded engine", () => {
  const original = engineApi();
  assert.ok(original, "loader engine must be installed by the prior load tests");
  const fakeEngine = { x: 1 };
  setEngineOverride(fakeEngine);
  assert.equal(engineApi(), fakeEngine);
  setEngineOverride(null);
  assert.equal(engineApi(), original);
});

test("engineEntry builds a fresh graph per call, distinct from the loader engine", () => {
  const loaded = engineApi();
  assert.ok(loaded, "loader engine must be installed by the prior load tests");
  const first = engineEntry();
  const second = engineEntry();
  assert.notEqual(first, second);
  assert.notEqual(first.engine, second.engine);
  assert.notEqual(first.workers, second.workers);
  assert.notEqual(first.engine, loaded);
});