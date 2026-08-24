import assert from "node:assert/strict";
import test from "node:test";

import { loadTiqianRuntime, engineApi, workerApi, setEngineOverride } from "./core/engine/loaders/runtime-loader.js";
import { tsFfiFacade } from "./core/engine/loaders/ts-runtime.js";

// Global names installed by the 21 engine scripts (source-of-truth from each module).
const EXPECTED_GLOBALS = [
  "__TiqianCustody",
  "__TiqianEligibility",
  "__TiqianProgressiveJob",
  "__TiqianInstallCopyHandler",
  "__TiqianContentReconcile",
  "__TiqianResponsiveMeasure",
  "__TiqianMarkdownLowering",
  "__TiqianLifecycle",
  "__TiqianWorkerRequest",
  "__TiqianPrepareParagraphLayout",
  "__TiqianCommitPreparedParagraph",
  "__TiqianProcessParagraph",
  "__TiqianCanvasFonts",
  "__TiqianCanvasMetrics",
  "__TiqianCanvasShaping",
  "__TiqianBrowserMetricsBridge",
  "__TiqianPreparedMetadata",
  "__TiqianProgressiveRelayoutSession",
  "__TiqianRootState",
  "__TiqianProgressiveDrivers",
  "__TiqianEngine",
  "__TiqianEngineWorkers",
];

test("loadTiqianRuntime installs all engine globals", async () => {
  await loadTiqianRuntime();

  const missing = EXPECTED_GLOBALS.filter((name) => {
    const val = globalThis[name];
    return val === undefined || val === null;
  });

  assert.deepEqual(missing, [], "Missing globals: " + missing.join(", "));
});

test("engineApi returns __TiqianEngine", () => {
  const api = engineApi();
  assert.ok(api, "engineApi() should be truthy");
  assert.equal(api, globalThis.__TiqianEngine);
});

test("setEngineOverride and restore", () => {
  const original = engineApi();
  const fakeEngine = { x: 1 };
  setEngineOverride(fakeEngine);
  assert.equal(engineApi(), fakeEngine);
  setEngineOverride(null);
  assert.equal(engineApi(), original);
});

test("workerApi returns __TiqianEngineWorkers", () => {
  const api = workerApi();
  assert.ok(api, "workerApi() should be truthy");
  assert.equal(api, globalThis.__TiqianEngineWorkers);
});

test("tsFfiFacade has all 5 members as functions", () => {
  assert.equal(typeof tsFfiFacade.classifyFontRole, "function");
  assert.equal(typeof tsFfiFacade.unsupportedInlineShapingProperties, "function");
  assert.equal(typeof tsFfiFacade.firstDivergentInlineShapingProperty, "function");
  assert.equal(typeof tsFfiFacade.precomputeParagraphWithDiagnostics, "function");
  assert.equal(typeof tsFfiFacade.precomputeParagraphWithBrowserMetrics, "function");
});

test("classifyFontRole executes the real engine through the linked ffi package", () => {
  // A deterministic role answer proves the @tiqian/ffi link resolves to the
  // real engine rather than an unbound stub.
  assert.equal(tsFfiFacade.classifyFontRole("汉", 0, 1, "zh-Hans"), "cjk-text");
});
