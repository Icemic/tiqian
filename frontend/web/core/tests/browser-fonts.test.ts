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
import { optionsFromJs } from "../core/engine/lifecycle.js";
import { workerLayoutRequestForRoot } from "../core/engine/worker-request.js";
import { initializeGlobalServices } from "../core/services/global-services.js";

initializeGlobalServices();

interface PrepareJobModule {
  createPrepareJob: (root: Element, handle: unknown, options: Record<string, unknown>, shouldContinue: () => boolean) => Promise<{
    done: boolean;
    step: (isStale: () => boolean) => void;
    finished: Promise<number>;
  } | null>;
}

// PrepareJob driver for channel tests: steps the job without a budget and
// awaits the stored-plan count.
async function drivePrepareJob(module: PrepareJobModule, root: Element, handle: unknown, options: Record<string, unknown>): Promise<number> {
  const job = await module.createPrepareJob(root, handle, options, () => true);
  if (!job) return 0;
  while (!job.done) {
    job.step(() => false);
    await Promise.resolve();
  }
  return await job.finished;
}

function assertCode(code: string): (error: unknown) => boolean {
  return (error: unknown): boolean => {
    assert.ok(error instanceof BrowserFontSessionError);
    assert.equal((error as BrowserFontSessionError).code, code);
    return true;
  };
}

interface HarnessState {
  loader: {
    prepare: (root: Element) => Promise<unknown>;
    release: (handle: unknown) => boolean;
    revalidate: (root: Element, handle: unknown) => Promise<unknown>;
    prepareRenderFonts: (root: Element, handle: unknown) => Promise<boolean>;
  };
  root: Element;
  requests: Array<{ url: string }>;
  createCalls: Array<{ specs: Array<{ publicUrl: string; sourceOrder?: number }>; options: { sessionPrefix: string; baseFeatures: string[] } }>;
  contractCalls: Array<{ matches: boolean; reason: string | null }>;
  preparedContractCalls: Array<unknown>;
  closeCount: () => number;
  renderFaceDeletes: unknown[];
  renderFaceCreates: unknown[];
  renderFaceAdds: unknown[];
  fontLoads: Array<{ descriptor: string; text: string }>;
  mutateSession?: (session: unknown) => void;
}

test("browser font sessions aggregate manifest evidence and close after the final release", async () => {
  const bytes = new TextEncoder().encode("fixture-font-source");
  const sourceSha256 = digest(bytes);
  const manifest = manifestWithFaces([
    [faceEvidence(sourceSha256)],
    [faceEvidence(sourceSha256, { fontWeight: 500, axes: { wght: 500 } })],
  ]);
  const state = harness(manifest, { bytes }) as unknown as HarnessState;

  const [first, second] = await Promise.all([
    state.loader.prepare(state.root),
    state.loader.prepare(state.root),
  ]);

  assert.equal((first as { id: string }).id, "browser-session-1");
  assert.equal((second as { id: string }).id, (first as { id: string }).id);
  assert.equal((first as { paragraphSelector: string }).paragraphSelector, "p[data-tq-snapshot-key]");
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
  assert.equal((next as { id: string }).id, "browser-session-2");
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
  }) as unknown as HarnessState;

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
  const state = harness(manifest, { bytes }) as unknown as HarnessState;

  const handle = await state.loader.prepare(state.root);
  const contract = browserFontSessionWorkerContract(handle as any);

  assert.deepEqual(contract, {
    sessionKey: (handle as { id: string }).id,
    manifestText: JSON.stringify(manifest),
    tablesBytes: getCurrentTable()!.bytes,
  });
  assert.equal(state.loader.release(handle), true);
  assert.throws(() => browserFontSessionWorkerContract(handle as any), assertCode("BrowserFontSessionHandleInvalid"));
});

interface FixtureElement {
  tagName: string;
  textContent: string;
  childNodes: Array<{ nodeType: number; textContent: string }>;
  getAttribute: (name: string) => string | null;
  setAttribute: (name: string, value: string) => void;
  removeAttribute: (name: string) => void;
  style: {
    setProperty: (name: string, value: string) => void;
    removeProperty: (name: string) => void;
    getPropertyValue: (name: string) => string;
    getPropertyPriority: () => string;
  };
  closest: (selector: string) => Element | null;
  querySelectorAll: (selector: string) => Element[];
  querySelector: (selector: string) => Element | null;
  getBoundingClientRect: () => Partial<DOMRect>;
  getClientRects: () => DOMRectList;
  parentElement: Element | null;
}

interface InvalidFixtureElement {
  tagName: string;
  textContent: string;
  closest: (selector: string) => Element | null;
  getBoundingClientRect: () => Partial<DOMRect>;
}

interface FixtureWorker {
  listeners: Map<string, (message: { data: unknown }) => void>;
  addEventListener: (type: string, listener: (message: { data: unknown }) => void) => void;
  postMessage: (message: { type: string; request: { text: string }; id: string }) => void;
  terminate: () => void;
}

test("layout Worker plans survive duplicate module instances and reach the layout Worker bridge", async () => {
  const bytes = new TextEncoder().encode("fixture-font-source");
  const state = harness(manifestWithFaces([[faceEvidence(digest(bytes))]]), { bytes }) as unknown as HarnessState;
  const handle = await state.loader.prepare(state.root);
  const originalWorker = globalThis.Worker;
  const originalInnerHeight = globalThis.innerHeight;
  const originalBridge = globalServices().coordination.layoutWorker;
  const coordinatorKey = Symbol.for("@tiqian/prose.layout-worker-coordinator.v1");
  const originalCoordinator = (globalThis as Record<symbol, unknown>)[coordinatorKey];
  const originalComputedStyle = globalThis.getComputedStyle;
  const ROOT_SELECTOR = "tiqian-prose, [data-tiqian-root]";
  let requestText = "first";
  // R10: the prepare path builds requests through the pure
  // workerLayoutRequestForRoot, so the candidate is a lowerable paragraph
  // double whose text follows requestText, and the take/issue calls reuse
  // that candidate's serialized build.
  const element: FixtureElement = {
    tagName: "P",
    get textContent() { return requestText; },
    get childNodes() { return [{ nodeType: 3, textContent: requestText }]; },
    getAttribute: (): null => null,
    setAttribute: (): void => {},
    removeAttribute: (): void => {},
    style: {
      setProperty: (): void => {},
      removeProperty: (): void => {},
      getPropertyValue: (): string => "",
      getPropertyPriority: (): string => "",
    },
    closest: (selector: string): Element | null => (selector === ROOT_SELECTOR ? state.root : null),
    querySelectorAll: (): Element[] => [],
    querySelector: (): Element | null => null,
    getBoundingClientRect: (): Partial<DOMRect> => ({ width: 323, top: 0, bottom: 24 }),
    getClientRects: (): DOMRectList => [] as unknown as DOMRectList,
    parentElement: null,
  } as unknown as FixtureElement;
  // No childNodes: lowering fails, so the candidate stays native without
  // blocking the following paragraphs (ParagraphAtomicNativeRollback).
  const invalidElement: InvalidFixtureElement = {
    tagName: "P",
    textContent: requestText,
    closest: (selector: string): Element | null => (selector === ROOT_SELECTOR ? state.root : null),
    getBoundingClientRect: (): Partial<DOMRect> => ({ width: 323, top: 0, bottom: 24 }),
  } as unknown as InvalidFixtureElement;
  let queriedElements: Element[] = [element as unknown as Element];
  const selectors: string[] = [];
  (state.root as any).querySelectorAll = (selector: string): Element[] => {
    selectors.push(selector);
    return queriedElements;
  };
  const completionSelector = ":is(p, li):not([data-tq-snapshot-key])";
  // SnapshotLayoutGate: the snapshot eligibility gate requires the option
  // fontSize/lineHeight/families to stay unset; lowering defaults them.
  const sessionOptions: Record<string, unknown> = {
    snapshotFontSession: { status: "conforming", sessionId: (handle as { id: string }).id, detail: "test" },
  };
  const preparedOptions: Record<string, unknown> = { paragraphSelector: completionSelector, ...sessionOptions };
  const canonicalOptions = optionsFromJs(preparedOptions);
  const requestJson = (): string => {
    const built = workerLayoutRequestForRoot(state.root, element as unknown as Element, canonicalOptions);
    return JSON.stringify({ ...built, semantics: [], renderInlineBoxes: [] });
  };

  class FixtureWorker implements FixtureWorker {
    listeners = new Map<string, (message: { data: unknown }) => void>();

    addEventListener(type: string, listener: (message: { data: unknown }) => void): void {
      this.listeners.set(type, listener);
    }

    postMessage(message: { type: string; request: { text: string }; id: string }): void {
      queueMicrotask(() => {
        const listener = this.listeners.get("message");
        if (listener) {
          listener({
            data: message.type === "layout"
              ? message.request.text === "failure"
                ? { id: message.id, ok: false, error: "fixture replay miss" }
                : { id: message.id, ok: true, plan: { fixture: message.request.text } }
              : { id: message.id, ok: true },
          });
        }
      });
    }

    terminate(): void {}
  }

  try {
    delete (globalThis as Record<symbol, unknown>)[coordinatorKey];
    delete (globalServices().coordination as any).layoutWorker;
    const legacyBridge = Object.freeze({
      version: 1,
      take: (): string => "legacy",
      issue: (): string => "legacy",
      release: (): boolean => false,
    });
    globalServices().coordination.layoutWorker = legacyBridge as never;
    (globalThis as Record<string, unknown>).Worker = FixtureWorker;
    globalThis.innerHeight = 800;
    // Zero padding/borders so the pure request builder measures the
    // candidate's rect width.
    globalThis.getComputedStyle = ((): Partial<CSSStyleDeclaration> & { getPropertyValue: (name: string) => string } => ({
      paddingLeft: "0px",
      paddingRight: "0px",
      borderLeftWidth: "0px",
      borderRightWidth: "0px",
      position: "static",
      transform: "none",
      marginLeft: "0px",
      marginRight: "0px",
      marginTop: "0px",
      marginBottom: "0px",
      getPropertyValue: (): string => "",
    })) as unknown as typeof globalThis.getComputedStyle;

    const firstModule = await import(
      `../core/engine/web-worker/worker-channel.js?fixture=first-${Date.now()}`
    ) as unknown as PrepareJobModule;
    assert.notEqual(globalServices().coordination.layoutWorker, legacyBridge);
    assert.equal((globalServices().coordination.layoutWorker as { version: number }).version, 1);
    assert.equal((globalServices().coordination.layoutWorker as { semanticReplayRevision: number }).semanticReplayRevision, 1);
    assert.equal(await drivePrepareJob(firstModule, state.root, handle, preparedOptions), 1);
    const firstRequest = requestJson();
    assert.equal(
      JSON.parse(globalServices().coordination.layoutWorker!.take(element as unknown as Element, (handle as { id: string }).id, firstRequest) as string).plan.fixture,
      "first",
    );
    const semanticOnlyChange = JSON.stringify({
      ...JSON.parse(firstRequest),
      semantics: [{ start: 0, end: 5, tagName: "a", attributes: [["href", "/latest"]] }],
      renderInlineBoxes: [{ start: 0, end: 5, inlineStart: 1, inlineEnd: 2 }],
    });
    const semanticRecord = JSON.parse(
      globalServices().coordination.layoutWorker!.take(element as unknown as Element, (handle as { id: string }).id, semanticOnlyChange) as string,
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
      maxWidthPx: JSON.parse(firstRequest).maxWidthPx - 1,
    });
    assert.equal(
      globalServices().coordination.layoutWorker!.take(element as unknown as Element, (handle as { id: string }).id, changedMeasure),
      null,
    );

    requestText = "second";
    const secondModule = await import(
      `../core/engine/web-worker/worker-channel.js?fixture=second-${Date.now()}`
    ) as unknown as PrepareJobModule;
    assert.equal(await drivePrepareJob(secondModule, state.root, handle, preparedOptions), 1);
    const secondRequest = requestJson();
    assert.equal(
      JSON.parse(globalServices().coordination.layoutWorker!.take(element as unknown as Element, (handle as { id: string }).id, secondRequest) as string).plan.fixture,
      "second",
    );
    assert.equal(globalServices().coordination.layoutWorker!.issue(element as unknown as Element, (handle as { id: string }).id, secondRequest), null);

    requestText = "failure";
    assert.equal(await drivePrepareJob(secondModule, state.root, handle, preparedOptions), 0);
    const failedRequest = requestJson();
    assert.equal(globalServices().coordination.layoutWorker!.take(element as unknown as Element, (handle as { id: string }).id, failedRequest), null);
    assert.equal(
      globalServices().coordination.layoutWorker!.issue(element as unknown as Element, (handle as { id: string }).id, failedRequest),
      "fixture replay miss",
    );

    requestText = "live semantic";
    assert.equal(await drivePrepareJob(secondModule, state.root, handle, preparedOptions), 1);
    const unsupportedSemanticRequest = JSON.stringify({
      ...JSON.parse(requestJson()),
      semantics: [{
        start: 0,
        end: 4,
        tagName: "spoiler",
        attributes: [],
      }],
      renderInlineBoxes: [],
    });
    const unsupportedSemanticRecord = JSON.parse(
      globalServices().coordination.layoutWorker!.take(element as unknown as Element, (handle as { id: string }).id, unsupportedSemanticRequest) as string,
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
      globalServices().coordination.layoutWorker!.issue(element as unknown as Element, (handle as { id: string }).id, unsupportedSemanticRequest),
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
        globalServices().coordination.layoutWorker!.take(element as unknown as Element, (handle as { id: string }).id, nestedLiveSemanticRequest) as string,
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
      globalServices().coordination.layoutWorker!.take(element as unknown as Element, (handle as { id: string }).id, sameLayoutSafeSemanticRequest) as string,
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
        globalServices().coordination.layoutWorker!.take(
          element as unknown as Element,
          (handle as { id: string }).id,
          sameLayoutStyledSemanticRequest,
        ) as string,
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
        globalServices().coordination.layoutWorker!.take(element as unknown as Element, (handle as { id: string }).id, sameLayoutLiveHrefRequest) as string,
      ).semanticReplay,
      "live-source",
    );

    const invalidSemanticRequest = JSON.stringify({
      ...JSON.parse(unsupportedSemanticRequest),
      semantics: [{ start: 0, end: 99, tagName: "spoiler", attributes: [] }],
    });
    assert.equal(
      globalServices().coordination.layoutWorker!.take(element as unknown as Element, (handle as { id: string }).id, invalidSemanticRequest),
      null,
    );
    assert.equal(
      globalServices().coordination.layoutWorker!.issue(element as unknown as Element, (handle as { id: string }).id, invalidSemanticRequest),
      "InvalidSnapshotSemanticRange",
    );

    queriedElements = [invalidElement as unknown as Element, element as unknown as Element];
    requestText = "after invalid candidate";
    assert.equal(await drivePrepareJob(secondModule, state.root, handle, preparedOptions), 1);
    const afterInvalidRequest = requestJson();
    assert.equal(
      JSON.parse(
        globalServices().coordination.layoutWorker!.take(element as unknown as Element, (handle as { id: string }).id, afterInvalidRequest) as string,
      ).plan.fixture,
      "after invalid candidate",
    );
    queriedElements = [element as unknown as Element];

    requestText = "default-runtime-set";
    assert.equal(await drivePrepareJob(secondModule, state.root, handle, { ...sessionOptions }), 1);
    const defaultRequest = requestJson();
    assert.equal(
      JSON.parse(globalServices().coordination.layoutWorker!.take(element as unknown as Element, (handle as { id: string }).id, defaultRequest) as string).plan.fixture,
      "default-runtime-set",
    );

    assert.equal(state.loader.release(handle), true);
    assert.equal(globalServices().coordination.layoutWorker!.take(element as unknown as Element, (handle as { id: string }).id, firstRequest), null);
    assert.equal(globalServices().coordination.layoutWorker!.take(element as unknown as Element, (handle as { id: string }).id, secondRequest), null);
    assert.equal(globalServices().coordination.layoutWorker!.issue(element as unknown as Element, (handle as { id: string }).id, failedRequest), null);
    assert.deepEqual(selectors, [
      completionSelector,
      completionSelector,
      completionSelector,
      completionSelector,
      completionSelector,
      "p, li",
    ]);
  } finally {
    const coordinatorValue = (globalThis as Record<symbol, unknown>)[coordinatorKey];
    if (coordinatorValue && typeof coordinatorValue === "object" && "worker" in coordinatorValue && coordinatorValue.worker && typeof (coordinatorValue.worker as { terminate?: () => void }).terminate === "function") {
      (coordinatorValue.worker as { terminate: () => void }).terminate();
    }
    if (originalCoordinator === undefined) {
      delete (globalThis as Record<symbol, unknown>)[coordinatorKey];
    } else {
      (globalThis as Record<symbol, unknown>)[coordinatorKey] = originalCoordinator;
    }
    if (originalBridge === undefined) {
      delete (globalServices().coordination as any).layoutWorker;
    } else {
      globalServices().coordination.layoutWorker = originalBridge;
    }
    if (originalWorker === undefined) {
      delete (globalThis as Record<string, unknown>).Worker;
    } else {
      globalThis.Worker = originalWorker;
    }
    if (originalInnerHeight === undefined) {
      delete (globalThis as Record<string, unknown>).innerHeight;
    } else {
      globalThis.innerHeight = originalInnerHeight;
    }
    if (originalComputedStyle === undefined) {
      delete (globalThis as Record<string, unknown>).getComputedStyle;
    } else {
      globalThis.getComputedStyle = originalComputedStyle;
    }
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
  const state = harness(manifest, { bytes }) as unknown as HarnessState;

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
  }) as unknown as HarnessState;

  const handle = await state.loader.prepare(state.root);

  assert.equal(state.requests.length, 0);
  assert.equal(state.loader.release(handle), true);
});

test("lining numeric snapshots preserve the server lnum replay contract", async () => {
  const bytes = new TextEncoder().encode("fixture-font-source");
  const manifest = manifestWithFaces(
    [[faceEvidence(digest(bytes), { probe: {
      ...faceEvidence(digest(bytes)).probe,
      ...( { features: ["lnum"] } as any ),
    } })]],
    undefined,
    { fontVariantNumeric: "lining-nums" },
  );
  const state = harness(manifest, { bytes }) as unknown as HarnessState;

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
  (manifest as any).fontContractEntries = [(manifest as { entries: unknown[] }).entries.pop()];
  const state = harness(manifest, { bytes }) as unknown as HarnessState;

  const handle = await state.loader.prepare(state.root);

  assert.equal(state.createCalls[0].specs.length, 2);
  assert.equal(state.requests.length, 0);
  assert.equal(state.loader.release(handle), true);
});

test("runtime replay loads and preserves the host render family", async () => {
  const bytes = new TextEncoder().encode("fixture-font-source");
  const manifest = manifestWithFaces([[faceEvidence(digest(bytes))]]);
  const state = harness(manifest, { bytes }) as unknown as HarnessState;
  const handle = await state.loader.prepare(state.root);

  assert.deepEqual((handle as { renderFontFamilies: string[] }).renderFontFamilies, ["Fixture CJK"]);
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
  }) as unknown as HarnessState;

  await assert.rejects(
    state.loader.prepare(state.root),
    (error: unknown) => {
      assertCode("SnapshotFontContractMismatch")(error);
      assert.match((error as Error).message, /SnapshotSourceMismatch/u);
      return true;
    },
  );
  assert.equal(state.requests.length, 0);
  assert.equal(state.createCalls.length, 0);
});

interface MutationObserverConstructor {
  new (callback: () => void): { observe: () => void; disconnect: () => void };
}

test("parser-time source mismatch retries as soon as the snapshot source becomes complete", async () => {
  const bytes = new TextEncoder().encode("fixture-font-source");
  const manifest = manifestWithFaces([[faceEvidence(digest(bytes))]]);
  let mutationCallback: (() => void) | undefined;
  class FixtureMutationObserver {
    constructor(callback: () => void) {
      mutationCallback = callback;
    }
    observe(): void {}
    disconnect(): void {}
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
      ...( { readyState: "loading" } as any ),
      defaultView: { MutationObserver: FixtureMutationObserver as unknown as MutationObserverConstructor },
      addEventListener(): void {},
      removeEventListener(): void {},
    },
  }) as unknown as HarnessState;

  const pending = state.loader.prepare(state.root);
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(state.requests.length, 0);
  mutationCallback?.();
  const handle = await pending;

  assert.equal(state.requests.length, 0);
  assert.equal(state.createCalls.length, 1);
  assert.equal(state.loader.release(handle), true);
});

test("parser completion makes an unresolved source mismatch fail closed", async () => {
  const bytes = new TextEncoder().encode("fixture-font-source");
  const manifest = manifestWithFaces([[faceEvidence(digest(bytes))]]);
  let parserComplete: (() => void) | undefined;
  class FixtureMutationObserver {
    observe(): void {}
    disconnect(): void {}
  }
  const state = harness(manifest, {
    bytes,
    contractResults: [
      { matches: false, reason: "SnapshotSourceMismatch" },
      { matches: false, reason: "SnapshotSourceMismatch" },
      { matches: false, reason: "SnapshotSourceMismatch" },
    ],
    documentOverrides: {
      ...( { readyState: "loading" } as any ),
      defaultView: { MutationObserver: FixtureMutationObserver as unknown as MutationObserverConstructor },
      addEventListener(name: string, listener: () => void): void {
        if (name === "DOMContentLoaded") parserComplete = listener;
      },
      removeEventListener(): void {},
    },
  }) as unknown as HarnessState;

  const pending = state.loader.prepare(state.root);
  await new Promise((resolve) => setImmediate(resolve));
  parserComplete?.();

  await assert.rejects(pending, (error: unknown) => {
    assertCode("SnapshotFontContractMismatch")(error);
    assert.match((error as Error).message, /SnapshotSourceMismatch/u);
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
  }) as unknown as HarnessState;

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
  }) as unknown as HarnessState;
  await assert.rejects(
    fieldMismatchState.loader.prepare(fieldMismatchState.root),
    (error: unknown) => {
      assertCode("SnapshotFontContractMismatch")(error);
      assert.match(
        (error as Error).message,
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
  }) as unknown as HarnessState;
  await assert.rejects(
    emptyCandidateState.loader.prepare(emptyCandidateState.root),
    (error: unknown) => {
      assertCode("SnapshotFontContractMismatch")(error);
      assert.match(
        (error as Error).message,
        /SnapshotFontContractMismatch:FontFaceContractMismatch\|EmptyCandidateSet/u,
      );
      return true;
    },
  );
});

test("same-document navigation keeps a root-relative font session valid", async () => {
  const bytes = new TextEncoder().encode("fixture-font-source");
  const manifest = manifestWithFaces([[faceEvidence(digest(bytes))]]);
  let state: HarnessState;
  state = harness(manifest, {
    bytes,
    mutateSession(session: unknown): void {
      (state.root.ownerDocument as { baseURI: string }).baseURI = "https://example.test/another/article/";
    },
  }) as unknown as HarnessState;

  const handle = await state.loader.prepare(state.root);

  assert.equal(state.requests.length, 0);
  assert.equal(state.loader.release(handle), true);
});

test("an existing font session revalidates live inputs without loading bytes again", async () => {
  const bytes = new TextEncoder().encode("fixture-font-source");
  const manifest = manifestWithFaces([[faceEvidence(digest(bytes))]]);
  const state = harness(manifest, { bytes }) as unknown as HarnessState;
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
  const state = harness(manifest) as unknown as HarnessState;

  const handle = await state.loader.prepare(state.root);
  assert.equal(state.requests.length, 0);
  assert.equal(state.createCalls.length, 1);
  assert.equal(state.loader.release(handle), true);
});

for (const [name, expectedCode, options] of [
  [
    "decompressed sfnt digest",
    "FontSessionFaceMetadataMismatch",
    { mutateSession: (session: unknown): void => { (session as { faces: Array<{ sfntSha256: string }> }).faces[0].sfntSha256 = "c".repeat(64); } },
  ],
  [
    "face family metadata",
    "FontSessionFaceMetadataMismatch",
    { mutateSession: (session: unknown): void => { (session as { faces: Array<{ family: string }> }).faces[0].family = "Wrong Family"; } },
  ],
  [
    "variable axes",
    "FontSessionFaceMetadataMismatch",
    { mutateSession: (session: unknown): void => { (session as { faces: Array<{ axisTags: string[] }> }).faces[0].axisTags = []; } },
  ],
  [
    "OpenType local names",
    "FontSessionFaceMetadataMismatch",
    { mutateSession: (session: unknown): void => { (session as { faces: Array<{ localNames: string[] }> }).faces[0].localNames = ["Wrong Name"]; } },
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
] as Array<[string, string, Record<string, unknown>]>) {
  test(`browser font session rejects mismatched ${name} and closes it`, async () => {
    const bytes = new TextEncoder().encode("fixture-font-source");
    const manifest = manifestWithFaces([[faceEvidence(digest(bytes))]]);
    const state = harness(manifest, { bytes, ...options }) as unknown as HarnessState;

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
  const state = harness(manifest, { bytes }) as unknown as HarnessState;

  await assert.rejects(state.loader.prepare(state.root), assertCode("SnapshotFontEvidenceConflict"));

  assert.equal(state.requests.length, 0);
  assert.equal(state.createCalls.length, 0);
});

test("the shared manifest HarfBuzz version must match the loaded session", async () => {
  const bytes = new TextEncoder().encode("fixture-font-source");
  const evidence = faceEvidence(digest(bytes));
  const manifest = manifestWithFaces([[evidence], [evidence]], ["hb-one", "hb-two"]);
  const state = harness(manifest, { bytes }) as unknown as HarnessState;

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
  const state = harness(manifestWithFaces([[later], [earlier]]), { bytes }) as unknown as HarnessState;

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
  const state = harness(manifestWithFaces([[first], [second]]), { bytes }) as unknown as HarnessState;

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
  const state = harness(manifest, { bytes }) as unknown as HarnessState;

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
  const state = harness(manifest, { bytes, useDefaultSession: true }) as unknown as HarnessState;

  const handle = await state.loader.prepare(state.root);
  // The default session resolves through the coordination registry, so the
  // callbacks for the prepared handle id address the same replay tables the
  // former handle-based global backend exposed.
  const { shapeJson, metricsJson } = snapshotSessionCallbacks((handle as { id: string }).id);
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
