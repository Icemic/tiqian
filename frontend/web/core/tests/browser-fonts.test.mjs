import { globalServices } from "../core/services/global-services.js";
import { snapshotSessionCallbacks } from "../core/measurement/browser-font-replay.js";
import assert from "node:assert/strict";
import test from "node:test";

import {
  BrowserFontSessionError,
  browserFontSessionWorkerContract,
} from "../core/measurement/browser-fonts.js";
import {
  digest,
  faceEvidence,
  getCurrentTable,
  harness,
  manifestWithFaces,
} from "./browser-fonts-fixtures.mjs";
import { setEngineOverride } from "../core/engine/loaders/runtime-loader.js";

// PrepareJob driver for channel tests: steps the job without a budget and
// awaits the stored-plan count.
async function drivePrepareJob(module, root, handle, options) {
  const job = await module.createPrepareJob(root, handle, options, () => true);
  if (!job) return 0;
  while (!job.done) {
    job.step(() => false);
    await Promise.resolve();
  }
  return await job.settled;
}

function assertCode(code) {
  return (error) => {
    assert.ok(error instanceof BrowserFontSessionError);
    assert.equal(error.code, code);
    return true;
  };
}

test("browser font sessions aggregate manifest evidence and close after the final release", async () => {
  const bytes = new TextEncoder().encode("fixture-font-source");
  const sourceSha256 = digest(bytes);
  const manifest = manifestWithFaces([
    [faceEvidence(sourceSha256)],
    [faceEvidence(sourceSha256, { fontWeight: 500, axes: { wght: 500 } })],
  ]);
  const state = harness(manifest, { bytes });

  const [first, second] = await Promise.all([
    state.loader.prepare(state.root),
    state.loader.prepare(state.root),
  ]);

  assert.equal(first.id, "browser-session-1");
  assert.equal(second.id, first.id);
  assert.equal(first.paragraphSelector, "p[data-tq-snapshot-key]");
  assert.equal(state.requests.length, 0);
  assert.equal(state.createCalls.length, 1);
  assert.equal(state.contractCalls.length, 4);
  assert.equal(state.createCalls[0].specs.length, 1);
  assert.equal(state.createCalls[0].options.sessionPrefix, "tq-browser-font");
  assert.deepEqual(state.createCalls[0].options.baseFeatures, []);
  assert.equal(state.closeCount(), 0);

  assert.equal(state.loader.release(first), true);
  assert.equal(state.loader.release(first), false);
  assert.equal(state.closeCount(), 0);
  assert.equal(state.renderFaceDeletes.length, 0);
  assert.equal(state.loader.release(second), true);
  assert.equal(state.closeCount(), 1);
  assert.equal(state.renderFaceDeletes.length, 0);

  const next = await state.loader.prepare(state.root);
  assert.equal(next.id, "browser-session-2");
  assert.equal(state.requests.length, 0);
  assert.equal(state.createCalls.length, 2);
  assert.equal(state.loader.release(next), true);
  assert.equal(state.closeCount(), 2);
});

test("browser font sessions reuse a live adoption proof before probing again", async () => {
  const bytes = new TextEncoder().encode("fixture-font-source");
  const sourceSha256 = digest(bytes);
  const state = harness(manifestWithFaces([[faceEvidence(sourceSha256)]]), {
    bytes,
    preparedContractResults: [
      { matches: true, reason: null },
      { matches: true, reason: null },
      { matches: true, reason: null },
    ],
  });

  const handle = await state.loader.prepare(state.root);
  assert.equal(state.contractCalls.length, 0);
  assert.equal(state.preparedContractCalls.length, 2);

  assert.strictEqual(await state.loader.revalidate(state.root, handle), handle);
  assert.equal(state.contractCalls.length, 0);
  assert.equal(state.preparedContractCalls.length, 3);
  assert.equal(state.loader.release(handle), true);
});

test("browser font sessions expose only replay identity to the layout Worker", async () => {
  const bytes = new TextEncoder().encode("fixture-font-source");
  const manifest = manifestWithFaces([[faceEvidence(digest(bytes))]]);
  const state = harness(manifest, { bytes });

  const handle = await state.loader.prepare(state.root);
  const contract = browserFontSessionWorkerContract(handle);

  assert.deepEqual(contract, {
    sessionKey: handle.id,
    manifestText: JSON.stringify(manifest),
    tablesBytes: getCurrentTable().bytes,
  });
  assert.equal(state.loader.release(handle), true);
  assert.throws(() => browserFontSessionWorkerContract(handle), assertCode("BrowserFontSessionHandleInvalid"));
});

test("layout Worker plans survive duplicate module instances and reach the engine call face", async () => {
  const bytes = new TextEncoder().encode("fixture-font-source");
  const state = harness(manifestWithFaces([[faceEvidence(digest(bytes))]]), { bytes });
  const handle = await state.loader.prepare(state.root);
  const originalWorker = globalThis.Worker;
  const originalInnerHeight = globalThis.innerHeight;
  const originalBridge = globalServices().coordination.layoutWorker;
  const coordinatorKey = Symbol.for("@tiqian/prose.layout-worker-coordinator.v1");
  const originalCoordinator = globalThis[coordinatorKey];
  const element = {
    closest: () => state.root,
    getBoundingClientRect: () => ({ top: 0, bottom: 24 }),
  };
  const invalidElement = {
    closest: () => state.root,
    getBoundingClientRect: () => ({ top: 0, bottom: 24 }),
  };
  let queriedElements = [element];
  const selectors = [];
  state.root.querySelectorAll = (selector) => {
    selectors.push(selector);
    return queriedElements;
  };
  let requestText = "first";
  const completionSelector = ":is(p, li):not([data-tq-snapshot-key])";

  class FixtureWorker {
    listeners = new Map();

    addEventListener(type, listener) {
      this.listeners.set(type, listener);
    }

    postMessage(message) {
      queueMicrotask(() => this.listeners.get("message")?.({
        data: message.type === "layout"
          ? message.request.text === "failure"
            ? { id: message.id, ok: false, error: "fixture replay miss" }
            : { id: message.id, ok: true, plan: { fixture: message.request.text } }
          : { id: message.id, ok: true },
      }));
    }

    terminate() {}
  }

  try {
    delete globalThis[coordinatorKey];
    delete globalServices().coordination.layoutWorker;
    const legacyBridge = Object.freeze({
      version: 1,
      take: () => "legacy",
      issue: () => "legacy",
      release: () => false,
    });
    globalServices().coordination.layoutWorker = legacyBridge;
    globalThis.Worker = FixtureWorker;
    globalThis.innerHeight = 800;
    // C1: the worker channel reads the engine call face, so the fixture
    // request source is an engine override rather than a bridge global.
    const engineStub = {
      workerLayoutRequest: () => JSON.stringify({
        text: requestText,
        maxWidthPx: 320,
        semantics: [],
        renderInlineBoxes: [],
      }),
    };
    setEngineOverride(engineStub);

    const firstModule = await import(
      `../core/engine/web-worker/worker-channel.js?fixture=first-${Date.now()}`
    );
    assert.notEqual(globalServices().coordination.layoutWorker, legacyBridge);
    assert.equal(globalServices().coordination.layoutWorker.version, 1);
    assert.equal(globalServices().coordination.layoutWorker.semanticReplayRevision, 1);
    assert.equal(await drivePrepareJob(firstModule, state.root, handle, {
      paragraphSelector: completionSelector,
    }), 1);
    const firstRequest = engineStub.workerLayoutRequest();
    assert.equal(
      JSON.parse(globalServices().coordination.layoutWorker.take(element, handle.id, firstRequest)).plan.fixture,
      "first",
    );
    const semanticOnlyChange = JSON.stringify({
      ...JSON.parse(firstRequest),
      semantics: [{ start: 0, end: 5, tagName: "a", attributes: [["href", "/latest"]] }],
      renderInlineBoxes: [{ start: 0, end: 5, inlineStart: 1, inlineEnd: 2 }],
    });
    const semanticRecord = JSON.parse(
      globalServices().coordination.layoutWorker.take(element, handle.id, semanticOnlyChange),
    );
    assert.equal(semanticRecord.plan.fixture, "first");
    assert.deepEqual(semanticRecord.semantics, [{
      start: 0,
      end: 5,
      tagName: "a",
      attributes: [["href", "/latest"]],
    }]);
    assert.deepEqual(semanticRecord.inlineBoxes, [
      { start: 0, end: 5, inlineStart: 1, inlineEnd: 2 },
    ]);
    const changedMeasure = JSON.stringify({
      ...JSON.parse(firstRequest),
      maxWidthPx: 319,
    });
    assert.equal(
      globalServices().coordination.layoutWorker.take(element, handle.id, changedMeasure),
      null,
    );

    requestText = "second";
    const secondModule = await import(
      `../core/engine/web-worker/worker-channel.js?fixture=second-${Date.now()}`
    );
    assert.equal(await drivePrepareJob(secondModule, state.root, handle, {
      paragraphSelector: completionSelector,
    }), 1);
    const secondRequest = engineStub.workerLayoutRequest();
    assert.equal(
      JSON.parse(globalServices().coordination.layoutWorker.take(element, handle.id, secondRequest)).plan.fixture,
      "second",
    );
    assert.equal(globalServices().coordination.layoutWorker.issue(element, handle.id, secondRequest), null);

    requestText = "failure";
    assert.equal(await drivePrepareJob(secondModule, state.root, handle, {
      paragraphSelector: completionSelector,
    }), 0);
    const failedRequest = engineStub.workerLayoutRequest();
    assert.equal(globalServices().coordination.layoutWorker.take(element, handle.id, failedRequest), null);
    assert.equal(
      globalServices().coordination.layoutWorker.issue(element, handle.id, failedRequest),
      "fixture replay miss",
    );

    engineStub.workerLayoutRequest = () => JSON.stringify({
      text: "live semantic",
      maxWidthPx: 320,
      semantics: [{
        start: 0,
        end: 4,
        tagName: "spoiler",
        attributes: [],
      }],
      renderInlineBoxes: [],
    });
    assert.equal(await drivePrepareJob(secondModule, state.root, handle, {
      paragraphSelector: completionSelector,
    }), 1);
    const unsupportedSemanticRequest = engineStub.workerLayoutRequest();
    const unsupportedSemanticRecord = JSON.parse(
      globalServices().coordination.layoutWorker.take(element, handle.id, unsupportedSemanticRequest),
    );
    assert.equal(unsupportedSemanticRecord.plan.fixture, "live semantic");
    assert.equal(unsupportedSemanticRecord.semanticReplay, "live-source");
    assert.deepEqual(unsupportedSemanticRecord.semantics, [{
      start: 0,
      end: 4,
      tagName: "spoiler",
      sourceIndex: 0,
    }]);
    assert.equal(
      globalServices().coordination.layoutWorker.issue(element, handle.id, unsupportedSemanticRequest),
      null,
    );

    const nestedLiveSemanticRequest = JSON.stringify({
      ...JSON.parse(unsupportedSemanticRequest),
      semantics: [{
        start: 0,
        end: 4,
        tagName: "em",
        attributes: [],
        sourceIndex: 0,
        order: 1,
      }, {
        start: 0,
        end: 4,
        tagName: "spoiler",
        attributes: [],
        sourceIndex: 1,
        order: 0,
      }],
    });
    assert.deepEqual(
      JSON.parse(
        globalServices().coordination.layoutWorker.take(element, handle.id, nestedLiveSemanticRequest),
      ).semantics,
      [{ start: 0, end: 4, tagName: "spoiler", sourceIndex: 1 },
        { start: 0, end: 4, tagName: "em", sourceIndex: 0 }],
    );

    const sameLayoutSafeSemanticRequest = JSON.stringify({
      ...JSON.parse(unsupportedSemanticRequest),
      semantics: [{
        start: 0,
        end: 4,
        tagName: "span",
        attributes: [],
      }],
    });
    const sameLayoutSafeSemanticRecord = JSON.parse(
      globalServices().coordination.layoutWorker.take(element, handle.id, sameLayoutSafeSemanticRequest),
    );
    assert.equal(sameLayoutSafeSemanticRecord.plan.fixture, "live semantic");
    assert.equal(sameLayoutSafeSemanticRecord.semanticReplay, "snapshot-safe");

    const sameLayoutStyledSemanticRequest = JSON.stringify({
      ...JSON.parse(unsupportedSemanticRequest),
      semantics: [{
        start: 0,
        end: 4,
        tagName: "span",
        attributes: [["style", "padding:4px"]],
      }],
    });
    assert.equal(
      JSON.parse(
        globalServices().coordination.layoutWorker.take(
          element,
          handle.id,
          sameLayoutStyledSemanticRequest,
        ),
      ).semanticReplay,
      "live-source",
    );

    const sameLayoutLiveHrefRequest = JSON.stringify({
      ...JSON.parse(unsupportedSemanticRequest),
      semantics: [{
        start: 0,
        end: 4,
        tagName: "a",
        attributes: [["href", "javascript:hostOwnedAction()"]],
      }],
    });
    assert.equal(
      JSON.parse(
        globalServices().coordination.layoutWorker.take(element, handle.id, sameLayoutLiveHrefRequest),
      ).semanticReplay,
      "live-source",
    );

    const invalidSemanticRequest = JSON.stringify({
      ...JSON.parse(unsupportedSemanticRequest),
      semantics: [{ start: 0, end: 99, tagName: "spoiler", attributes: [] }],
    });
    assert.equal(
      globalServices().coordination.layoutWorker.take(element, handle.id, invalidSemanticRequest),
      null,
    );
    assert.equal(
      globalServices().coordination.layoutWorker.issue(element, handle.id, invalidSemanticRequest),
      "InvalidSnapshotSemanticRange",
    );

    queriedElements = [invalidElement, element];
    engineStub.workerLayoutRequest = (_root, candidate) => {
      if (candidate === invalidElement) return "{";
      return JSON.stringify({
        text: "after invalid candidate",
        maxWidthPx: 320,
        semantics: [],
        renderInlineBoxes: [],
      });
    };
    assert.equal(await drivePrepareJob(secondModule, state.root, handle, {
      paragraphSelector: completionSelector,
    }), 1);
    const afterInvalidRequest = engineStub.workerLayoutRequest(state.root, element);
    assert.equal(
      JSON.parse(
        globalServices().coordination.layoutWorker.take(element, handle.id, afterInvalidRequest),
      ).plan.fixture,
      "after invalid candidate",
    );
    queriedElements = [element];

    requestText = "default-runtime-set";
    engineStub.workerLayoutRequest = () => JSON.stringify({
      text: requestText,
      maxWidthPx: 320,
      semantics: [],
      renderInlineBoxes: [],
    });
    assert.equal(await drivePrepareJob(secondModule, state.root, handle, {}), 1);
    const defaultRequest = engineStub.workerLayoutRequest();
    assert.equal(
      JSON.parse(globalServices().coordination.layoutWorker.take(element, handle.id, defaultRequest)).plan.fixture,
      "default-runtime-set",
    );

    assert.equal(state.loader.release(handle), true);
    assert.equal(globalServices().coordination.layoutWorker.take(element, handle.id, firstRequest), null);
    assert.equal(globalServices().coordination.layoutWorker.take(element, handle.id, secondRequest), null);
    assert.equal(globalServices().coordination.layoutWorker.issue(element, handle.id, failedRequest), null);
    assert.deepEqual(selectors, [
      completionSelector,
      completionSelector,
      completionSelector,
      completionSelector,
      completionSelector,
      "p, li",
    ]);
  } finally {
    globalThis[coordinatorKey]?.worker?.terminate?.();
    if (originalCoordinator === undefined) delete globalThis[coordinatorKey];
    else globalThis[coordinatorKey] = originalCoordinator;
    if (originalBridge === undefined) delete globalServices().coordination.layoutWorker;
    else globalServices().coordination.layoutWorker = originalBridge;
    if (originalWorker === undefined) delete globalThis.Worker;
    else globalThis.Worker = originalWorker;
    if (originalInnerHeight === undefined) delete globalThis.innerHeight;
    else globalThis.innerHeight = originalInnerHeight;
    setEngineOverride(null);
    state.loader.release(handle);
  }
});

test("browser font sessions retain whitespace-only glyph evidence", async () => {
  const bytes = new TextEncoder().encode("fixture-font-source");
  const sourceSha256 = digest(bytes);
  const manifest = manifestWithFaces([[
    faceEvidence(sourceSha256, {
      unicodeRange: "U+0020",
      coverageText: " ",
      probe: {
        ...faceEvidence(sourceSha256).probe,
        text: " ",
        advancePx: 4,
        script: "Latn",
      },
    }),
  ]]);
  const state = harness(manifest, { bytes });

  const handle = await state.loader.prepare(state.root);

  assert.equal(state.createCalls.length, 1);
  assert.equal(state.loader.release(handle), true);
});

test("browser font sessions never fetch font bytes for server replay", async () => {
  const bytes = new TextEncoder().encode("fixture-font-source");
  const manifest = manifestWithFaces([[faceEvidence(digest(bytes))]]);
  const state = harness(manifest, {
    bytes,
    fetchErrors: [new TypeError("conditional cache race")],
  });

  const handle = await state.loader.prepare(state.root);

  assert.equal(state.requests.length, 0);
  assert.equal(state.loader.release(handle), true);
});

test("lining numeric snapshots preserve the server lnum replay contract", async () => {
  const bytes = new TextEncoder().encode("fixture-font-source");
  const manifest = manifestWithFaces(
    [[faceEvidence(digest(bytes), { probe: {
      ...faceEvidence(digest(bytes)).probe,
      features: ["lnum"],
    } })]],
    undefined,
    { fontVariantNumeric: "lining-nums" },
  );
  const state = harness(manifest, { bytes });

  const handle = await state.loader.prepare(state.root);

  assert.deepEqual(state.createCalls[0].options.baseFeatures, ["lnum"]);
  assert.equal(state.loader.release(handle), true);
});

test("browser font sessions include runtime-only semantic contract entries", async () => {
  const bytes = new TextEncoder().encode("fixture-font-source");
  const sourceSha256 = digest(bytes);
  const manifest = manifestWithFaces([
    [faceEvidence(sourceSha256)],
    [faceEvidence(sourceSha256, {
      publicUrl: "/assets/semantic-deadbeef.woff2",
      sourceOrder: 1,
      unicodeRange: "U+6E32",
      coverageText: "渲",
      probe: {
        ...faceEvidence(sourceSha256).probe,
        text: "渲",
      },
    })],
  ]);
  manifest.fontContractEntries = [manifest.entries.pop()];
  const state = harness(manifest, { bytes });

  const handle = await state.loader.prepare(state.root);

  assert.equal(state.createCalls[0].specs.length, 2);
  assert.equal(state.requests.length, 0);
  assert.equal(state.loader.release(handle), true);
});

test("runtime replay loads and preserves the host render family", async () => {
  const bytes = new TextEncoder().encode("fixture-font-source");
  const manifest = manifestWithFaces([[faceEvidence(digest(bytes))]]);
  const state = harness(manifest, { bytes });
  const handle = await state.loader.prepare(state.root);

  assert.deepEqual(handle.renderFontFamilies, ["Fixture CJK"]);
  assert.equal(state.renderFaceCreates.length, 0);
  assert.equal(state.renderFaceAdds.length, 0);
  assert.equal(await state.loader.prepareRenderFonts(state.root, handle), true);
  assert.deepEqual(state.fontLoads, [{
    descriptor: 'normal 400 16px "Fixture CJK"',
    text: "中国",
  }]);
  assert.equal(state.loader.release(handle), true);
  assert.equal(state.renderFaceDeletes.length, 0);
});

test("live snapshot font contract is required before creating replay state", async () => {
  const bytes = new TextEncoder().encode("fixture-font-source");
  const manifest = manifestWithFaces([[faceEvidence(digest(bytes))]]);
  const state = harness(manifest, {
    bytes,
    contractResults: [{ matches: false, reason: "SnapshotSourceMismatch" }],
  });

  await assert.rejects(
    state.loader.prepare(state.root),
    (error) => {
      assertCode("SnapshotFontContractMismatch")(error);
      assert.match(error.message, /SnapshotSourceMismatch/u);
      return true;
    },
  );
  assert.equal(state.requests.length, 0);
  assert.equal(state.createCalls.length, 0);
});

test("parser-time source mismatch retries as soon as the snapshot source becomes complete", async () => {
  const bytes = new TextEncoder().encode("fixture-font-source");
  const manifest = manifestWithFaces([[faceEvidence(digest(bytes))]]);
  let mutationCallback;
  class FixtureMutationObserver {
    constructor(callback) {
      mutationCallback = callback;
    }
    observe() {}
    disconnect() {}
  }
  const state = harness(manifest, {
    bytes,
    contractResults: [
      { matches: false, reason: "SnapshotSourceMismatch" },
      { matches: false, reason: "SnapshotSourceMismatch" },
      { matches: true, reason: null },
      { matches: true, reason: null },
    ],
    documentOverrides: {
      readyState: "loading",
      defaultView: { MutationObserver: FixtureMutationObserver },
      addEventListener() {},
      removeEventListener() {},
    },
  });

  const pending = state.loader.prepare(state.root);
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(state.requests.length, 0);
  mutationCallback();
  const handle = await pending;

  assert.equal(state.requests.length, 0);
  assert.equal(state.createCalls.length, 1);
  assert.equal(state.loader.release(handle), true);
});

test("parser completion makes an unresolved source mismatch fail closed", async () => {
  const bytes = new TextEncoder().encode("fixture-font-source");
  const manifest = manifestWithFaces([[faceEvidence(digest(bytes))]]);
  let parserComplete;
  class FixtureMutationObserver {
    observe() {}
    disconnect() {}
  }
  const state = harness(manifest, {
    bytes,
    contractResults: [
      { matches: false, reason: "SnapshotSourceMismatch" },
      { matches: false, reason: "SnapshotSourceMismatch" },
      { matches: false, reason: "SnapshotSourceMismatch" },
    ],
    documentOverrides: {
      readyState: "loading",
      defaultView: { MutationObserver: FixtureMutationObserver },
      addEventListener(name, listener) {
        if (name === "DOMContentLoaded") parserComplete = listener;
      },
      removeEventListener() {},
    },
  });

  const pending = state.loader.prepare(state.root);
  await new Promise((resolve) => setImmediate(resolve));
  parserComplete();

  await assert.rejects(pending, (error) => {
    assertCode("SnapshotFontContractMismatch")(error);
    assert.match(error.message, /SnapshotSourceMismatch/u);
    return true;
  });
  assert.equal(state.requests.length, 0);
  assert.equal(state.createCalls.length, 0);
});

test("live snapshot font contract is revalidated after asynchronous font preparation", async () => {
  const bytes = new TextEncoder().encode("fixture-font-source");
  const manifest = manifestWithFaces([[faceEvidence(digest(bytes))]]);
  const state = harness(manifest, {
    bytes,
    contractResults: [
      { matches: true, reason: null },
      { matches: false, reason: "FontFaceContractMismatch" },
    ],
  });

  await assert.rejects(
    state.loader.prepare(state.root),
    assertCode("SnapshotFontContractMismatch"),
  );
  assert.equal(state.requests.length, 0);
  assert.equal(state.createCalls.length, 1);
  assert.equal(state.closeCount(), 1);
});

test("SnapshotFontContractMismatch message carries structured suffix for FieldMismatch and EmptyCandidateSet", async () => {
  const bytes = new TextEncoder().encode("fixture-font-source");
  const manifest = manifestWithFaces([[faceEvidence(digest(bytes))]]);

  // FieldMismatch: structured suffix with expectedFaces, actualFaces, firstField
  const fieldMismatchState = harness(manifest, {
    bytes,
    contractResults: [
      {
        matches: false,
        reason: "FontFaceContractMismatch",
        detail: {
          kind: "FieldMismatch",
          expectedFaces: 1,
          actualFaces: 1,
          firstField: "style",
        },
      },
    ],
  });
  await assert.rejects(
    fieldMismatchState.loader.prepare(fieldMismatchState.root),
    (error) => {
      assertCode("SnapshotFontContractMismatch")(error);
      assert.match(
        error.message,
        /SnapshotFontContractMismatch:FontFaceContractMismatch\|FieldMismatch\|expectedFaces=1\|actualFaces=1\|firstField=style/u,
      );
      return true;
    },
  );

  // EmptyCandidateSet: bare |EmptyCandidateSet suffix
  const emptyCandidateState = harness(manifest, {
    bytes,
    contractResults: [
      {
        matches: false,
        reason: "FontFaceContractMismatch",
        detail: {
          kind: "EmptyCandidateSet",
        },
      },
    ],
  });
  await assert.rejects(
    emptyCandidateState.loader.prepare(emptyCandidateState.root),
    (error) => {
      assertCode("SnapshotFontContractMismatch")(error);
      assert.match(
        error.message,
        /SnapshotFontContractMismatch:FontFaceContractMismatch\|EmptyCandidateSet/u,
      );
      return true;
    },
  );
});

test("same-document navigation keeps a root-relative font session valid", async () => {
  const bytes = new TextEncoder().encode("fixture-font-source");
  const manifest = manifestWithFaces([[faceEvidence(digest(bytes))]]);
  let state;
  state = harness(manifest, {
    bytes,
    mutateSession() {
      state.root.ownerDocument.baseURI = "https://example.test/another/article/";
    },
  });

  const handle = await state.loader.prepare(state.root);

  assert.equal(state.requests.length, 0);
  assert.equal(state.loader.release(handle), true);
});

test("an existing font session revalidates live inputs without loading bytes again", async () => {
  const bytes = new TextEncoder().encode("fixture-font-source");
  const manifest = manifestWithFaces([[faceEvidence(digest(bytes))]]);
  const state = harness(manifest, { bytes });
  const handle = await state.loader.prepare(state.root);

  assert.strictEqual(await state.loader.revalidate(state.root, handle), handle);
  assert.equal(state.contractCalls.length, 3);
  assert.equal(state.requests.length, 0);
  assert.equal(state.createCalls.length, 1);
  assert.equal(state.closeCount(), 0);

  assert.equal(state.loader.release(handle), true);
  await assert.rejects(
    state.loader.revalidate(state.root, handle),
    assertCode("BrowserFontSessionHandleInvalid"),
  );
});

test("runtime replay trusts captured manifest digests without refetching font bytes", async () => {
  const manifest = manifestWithFaces([[faceEvidence("0".repeat(64))]]);
  const state = harness(manifest);

  const handle = await state.loader.prepare(state.root);
  assert.equal(state.requests.length, 0);
  assert.equal(state.createCalls.length, 1);
  assert.equal(state.loader.release(handle), true);
});

for (const [name, expectedCode, options] of [
  [
    "decompressed sfnt digest",
    "FontSessionFaceMetadataMismatch",
    { mutateSession: (session) => { session.faces[0].sfntSha256 = "c".repeat(64); } },
  ],
  [
    "face family metadata",
    "FontSessionFaceMetadataMismatch",
    { mutateSession: (session) => { session.faces[0].family = "Wrong Family"; } },
  ],
  [
    "variable axes",
    "FontSessionFaceMetadataMismatch",
    { mutateSession: (session) => { session.faces[0].axisTags = []; } },
  ],
  [
    "OpenType local names",
    "FontSessionFaceMetadataMismatch",
    { mutateSession: (session) => { session.faces[0].localNames = ["Wrong Name"]; } },
  ],
  [
    "backend revision",
    "FontBackendRevisionMismatch",
    { backendRevision: "other-backend" },
  ],
  [
    "HarfBuzz version",
    "HarfBuzzVersionMismatch",
    { harfbuzzVersion: "other-hb" },
  ],
]) {
  test(`browser font session rejects mismatched ${name} and closes it`, async () => {
    const bytes = new TextEncoder().encode("fixture-font-source");
    const manifest = manifestWithFaces([[faceEvidence(digest(bytes))]]);
    const state = harness(manifest, { bytes, ...options });

    await assert.rejects(state.loader.prepare(state.root), assertCode(expectedCode));

    assert.equal(state.createCalls.length, 1);
    assert.equal(state.closeCount(), 1);
  });
}

test("conflicting duplicate face evidence misses before fetching font bytes", async () => {
  const bytes = new TextEncoder().encode("fixture-font-source");
  const sourceSha256 = digest(bytes);
  const conflicting = faceEvidence(sourceSha256, { sfntSha256: "c".repeat(64) });
  const manifest = manifestWithFaces([
    [faceEvidence(sourceSha256)],
    [conflicting],
  ]);
  const state = harness(manifest, { bytes });

  await assert.rejects(state.loader.prepare(state.root), assertCode("SnapshotFontEvidenceConflict"));

  assert.equal(state.requests.length, 0);
  assert.equal(state.createCalls.length, 0);
});

test("the shared manifest HarfBuzz version must match the loaded session", async () => {
  const bytes = new TextEncoder().encode("fixture-font-source");
  const evidence = faceEvidence(digest(bytes));
  const manifest = manifestWithFaces([[evidence], [evidence]], ["hb-one", "hb-two"]);
  const state = harness(manifest, { bytes });

  await assert.rejects(state.loader.prepare(state.root), assertCode("HarfBuzzVersionMismatch"));
  assert.equal(state.requests.length, 0);
});

test("sourceOrder restores the build face priority before creating the browser session", async () => {
  const bytes = new TextEncoder().encode("fixture-font-source");
  const sourceSha256 = digest(bytes);
  const later = faceEvidence(sourceSha256, {
    publicUrl: "/assets/later-deadbeef.woff2",
    sourceOrder: 9,
  });
  const earlier = faceEvidence(sourceSha256, {
    publicUrl: "/assets/earlier-deadbeef.woff2",
    sourceOrder: 2,
  });
  const state = harness(manifestWithFaces([[later], [earlier]]), { bytes });

  const handle = await state.loader.prepare(state.root);

  assert.deepEqual(
    state.createCalls[0].specs.map((face) => [face.publicUrl, face.sourceOrder]),
    [
      ["/assets/earlier-deadbeef.woff2", 2],
      ["/assets/later-deadbeef.woff2", 9],
    ],
  );
  assert.equal(state.loader.release(handle), true);
});

test("duplicate sourceOrder across distinct faces misses before fetching", async () => {
  const bytes = new TextEncoder().encode("fixture-font-source");
  const sourceSha256 = digest(bytes);
  const first = faceEvidence(sourceSha256, {
    publicUrl: "/assets/first-deadbeef.woff2",
    sourceOrder: 3,
  });
  const second = faceEvidence(sourceSha256, {
    publicUrl: "/assets/second-deadbeef.woff2",
    sourceOrder: 3,
  });
  const state = harness(manifestWithFaces([[first], [second]]), { bytes });

  await assert.rejects(
    state.loader.prepare(state.root),
    assertCode("SnapshotFontEvidenceConflict"),
  );
  assert.equal(state.requests.length, 0);
});

test("manifest backend revisions must agree with the browser backend", async () => {
  const bytes = new TextEncoder().encode("fixture-font-source");
  const sourceSha256 = digest(bytes);
  const manifest = manifestWithFaces([[faceEvidence(sourceSha256)]], undefined, {}, {
    backendRevision: "stale-backend",
  });
  const state = harness(manifest, { bytes });

  await assert.rejects(
    state.loader.prepare(state.root),
    assertCode("FontBackendRevisionMismatch"),
  );
  assert.equal(state.requests.length, 0);
});

test("the default browser session scales server shaping evidence without loading HarfBuzz", async () => {
  const bytes = new TextEncoder().encode("fixture-font-source");
  const families = "Fixture CJK";
  const shapeKey = JSON.stringify([
    "正文",
    families,
    400,
    false,
    "zh-Hans",
    "CjkText",
    "正文",
  ]);
  const metricKey = JSON.stringify([
    families,
    400,
    false,
    "CjkText",
    "正文",
  ]);
  const manifest = manifestWithFaces([[faceEvidence(digest(bytes))]], undefined, {}, {
    replayShapes: [{
      key: shapeKey,
      result: {
        faceId: "fixture-face",
        fontInstanceId: "fixture-instance",
        script: "Hani",
        features: [],
        unsafeBreakCount: 0,
        advanceEm: 2,
        glyphs: [{
          id: 42,
          advanceEm: 2,
          xEm: 0,
          yEm: 0,
          boundsEm: [0, -0.8, 2, 0.2],
        }],
      },
    }],
    replayMetrics: [{
      key: metricKey,
      valuesEm: [0.8, 0.2, 0, 0.8, 0.2],
    }],
  });
  const state = harness(manifest, { bytes, useDefaultSession: true });

  const handle = await state.loader.prepare(state.root);
  // The default session resolves through the coordination registry, so the
  // callbacks for the prepared handle id address the same replay tables the
  // former handle-based global backend exposed.
  const { shapeJson, metricsJson } = snapshotSessionCallbacks(handle.id);
  const shapeResponse = JSON.parse(shapeJson(JSON.stringify({
    text: "正文",
    sourceText: "正文",
    range: { start: 0, end: 2 },
    style: { fontFamilies: [families], fontSize: 20, fontWeight: 400, italic: false, locale: "zh-Hans" },
    fontDecision: { role: "CjkText", candidateKey: "cjk-primary" },
    displayText: "正文",
    openTypeFeatures: [],
  })));
  assert.equal(shapeResponse.glyphRuns[0].advance, 40);
  assert.equal(shapeResponse.glyphRuns[0].glyphs[0].advance, 40);
  assert.equal(shapeResponse.glyphRuns[0].glyphs[0].bounds.top, -16);
  const metricsResponse = JSON.parse(metricsJson(JSON.stringify({
    fontFamilies: [families],
    fontSize: 20,
    fontWeight: 400,
    italic: false,
    role: "CjkText",
    locale: "zh-Hans",
    faceSelectionText: "正文",
  })));
  assert.deepEqual(
    [metricsResponse.ascent, metricsResponse.descent, metricsResponse.leading,
     metricsResponse.typoAscent, metricsResponse.typoDescent],
    [16, 4, 0, 16, 4],
  );
  assert.equal(state.createCalls.length, 0);
  assert.equal(state.loader.release(handle), true);
});
