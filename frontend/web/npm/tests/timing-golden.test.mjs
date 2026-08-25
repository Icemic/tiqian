import { globalServices } from "@tiqian/core/core/services/global-services.js";
// Timing-anchor goldens for the web prose host refactor (ADR 0053 batch 0,
// decomposition report section 11). Every journey runs real module code under
// the shared fake clock (test-clock.mjs) so dispatch order and record
// structure are deterministic. Wall-clock-derived numbers are deliberately
// not frozen: the element module's lazy imports do real I/O whose interleaving
// with the fake-clock pump varies per process, so frame counts and every
// duration derived from them vary too (timing-golden-host.mjs normalizes them
// away at recording time). Golden updates go through
// TIQIAN_UPDATE_TIMING_GOLDEN=1; each diff is reviewed before the fixture is
// committed, mirroring the JVM LayoutDumpGoldenTest discipline.

import assert from "node:assert/strict";
import test from "node:test";
import { readFileSync, writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

import {
  preserveGlobals,
  restoreGlobals,
  installFakeClock,
  CLOCK_GLOBALS,
} from "./test-clock.mjs";
import {
  digest,
  faceEvidence,
  harness,
  manifestWithFaces,
} from "./browser-fonts-fixtures.mjs";
import {
  driveElementTimeline,
  ELEMENT_DRIVE_GLOBALS,
  FRAME_STEP_MS,
} from "./timing-golden-host.mjs";
import { setEngineOverride } from "@tiqian/core/core/engine/loaders/runtime-loader.js";

const FIXTURE_PATH = fileURLToPath(new URL("./timing-golden.fixture.json", import.meta.url));
const GOLDEN_VERSION = 1;
const UPDATE_ENV = "TIQIAN_UPDATE_TIMING_GOLDEN";
const FRAME_TRACE_LIMIT = 600;
const SLOW_FRAME_STEP_MS = 60;
const SUSPEND_GAP_MS = 300;

// ---------------------------------------------------------------------------
// Golden store
// ---------------------------------------------------------------------------

function loadFixture() {
  try {
    const parsed = JSON.parse(readFileSync(FIXTURE_PATH, "utf8"));
    assert.equal(parsed.version, GOLDEN_VERSION, "fixture version");
    return parsed;
  } catch (error) {
    if (error.code === "ENOENT") return { version: GOLDEN_VERSION, journeys: {} };
    throw error;
  }
}

function storeGolden(journeys) {
  const ordered = {};
  for (const id of Object.keys(journeys).sort()) ordered[id] = journeys[id];
  writeFileSync(
    FIXTURE_PATH,
    `${JSON.stringify({ version: GOLDEN_VERSION, journeys: ordered }, null, 2)}\n`,
  );
}

function firstDifferencePath(expected, actual, prefix = "$") {
  if (typeof expected !== typeof actual) return prefix;
  if (expected === null || actual === null || typeof expected !== "object") {
    return expected === actual ? null : prefix;
  }
  if (Array.isArray(expected) !== Array.isArray(actual)) return prefix;
  const keys = new Set([...Object.keys(expected), ...Object.keys(actual)]);
  for (const key of keys) {
    const path = `${prefix}.${key}`;
    if (!(key in expected) || !(key in actual)) return path;
    const diff = firstDifferencePath(expected[key], actual[key], path);
    if (diff !== null) return diff;
  }
  return null;
}

// ---------------------------------------------------------------------------
// Element-journey projections (four record shapes from the shared drive)
// ---------------------------------------------------------------------------

function projectEventDispatch(full) {
  // frameAdvanceCounts is intentionally excluded: the pump frame counts ride
  // the same lazy-import I/O interleaving (see timing-golden-host.mjs), so
  // this journey freezes dispatch order and detail structure only. The
  // engineCalls stream is the successor observable of the retired internal
  // document events (ADR 0053 C1): the drive substitutes a recording engine
  // stub, and each host-to-engine call lands here in phase order.
  return {
    elementEvents: full.elementEvents,
    documentEvents: full.documentEvents,
    engineCalls: full.engineCalls,
  };
}

function projectDatasetFirstWrites(full) {
  return {
    datasetWrites: full.datasetWrites,
    attributeWrites: full.attributeWrites,
  };
}

// Each verdict is derived from the recorded observables (events, dataset
// writes, paragraph state) so a behavior change moves the golden; nothing is
// hardcoded. Complementary branch names keep a suppressed dispatch or a
// missing write visible instead of silently absent.
function deriveTransitions(full) {
  const elementEventsIn = (phase) => full.elementEvents.filter((e) => e.phase === phase);
  const has = (phase, type) => elementEventsIn(phase).some((e) => e.type === type);
  const docHas = (phase, type) =>
    full.documentEvents.some((e) => e.phase === phase && e.type === type);
  const engineHas = (phase, method) =>
    full.engineCalls.some((call) => call.phase === phase && call.method === method);
  const dsHas = (phase, key) =>
    full.datasetWrites.some((w) => w.phase === phase && w.key === key);
  const adopted = (phase) => full.paragraphStates[phase]?.firstChildNodeType === 1;
  const restored = (phase) => full.paragraphStates[phase]?.firstChildText === "中国";

  return [
    {
      phase: "s1-adopt",
      trigger: "connect",
      verdicts: [
        has("s1-adopt", "tiqian:ready") ? "ready-dispatched" : "ready-missing",
        adopted("s1") ? "paragraph-adopted" : "paragraph-not-adopted",
        dsHas("s1-adopt", "tiqianSnapshot") ? "dataset-snapshot-written" : "dataset-snapshot-missing",
      ],
    },
    {
      phase: "s2-resize",
      trigger: "width-change",
      verdicts: [
        has("s2-resize", "tiqian:relayout-ready")
          ? "relayout-event-dispatched"
          : "relayout-event-suppressed",
        restored("s2") ? "paragraph-restored" : "paragraph-not-restored",
        engineHas("s2-resize", "enhanceProgressively")
          ? "enhance-progressively-engine-called"
          : "enhance-progressively-engine-missing",
        dsHas("s2-resize", "tiqianSnapshotFontMiss")
          ? "snapshot-font-miss-recorded"
          : "snapshot-font-miss-missing",
      ],
    },
    {
      phase: "s3-midflight-disconnect",
      trigger: "midflight-disconnect",
      verdicts: [
        engineHas("s3-midflight-disconnect", "detach")
          ? "detach-engine-called"
          : "detach-engine-missing",
        elementEventsIn("s3-midflight-disconnect").length === 0
          ? "element-events-suppressed"
          : "element-events-present",
      ],
    },
    {
      phase: "s4-reconnect",
      trigger: "reconnect",
      verdicts: [
        engineHas("s4-reconnect", "destroy") ? "destroy-engine-called" : "destroy-engine-missing",
        has("s4-reconnect", "tiqian:ready") ? "ready-dispatched" : "ready-missing",
        adopted("s4") ? "paragraph-adopted" : "paragraph-not-adopted",
      ],
    },
  ];
}

function projectTokenTransitions(full) {
  return { transitions: deriveTransitions(full) };
}

// Cache-invalidation scope: the cancel paths that require an active
// Kotlin-runtime capture are unreachable in this no-runtime drive. Recorded
// here is the JS-reachable side only: geometry-change consequences (restore,
// observer teardown) and disconnect-driven release.
function projectCacheInvalidation(full) {
  return {
    observerActivity: full.observerActivity,
    fetchCalls: full.fetchCalls,
    releaseVerdicts: {
      s2Restore: full.paragraphStates.s2?.firstChildText === "中国",
      s3Release: full.observerActivity.some((o) =>
        o.ops.some((op) => op.op === "disconnect")),
    },
  };
}

const ELEMENT_JOURNEYS = {
  "event-dispatch": projectEventDispatch,
  "dataset-first-writes": projectDatasetFirstWrites,
  "token-transitions": projectTokenTransitions,
  "cache-invalidation": projectCacheInvalidation,
  "snapshot-font-contract-mismatch": projectDatasetFirstWrites,
};

// Each element journey runs the full S1-S4 drive in a pristine global
// environment and projects the shared record into its own frozen shape.
async function recordElementJourney(journeyKey) {
  const globals = preserveGlobals([...CLOCK_GLOBALS, ...ELEMENT_DRIVE_GLOBALS]);
  const clock = installFakeClock();
  try {
    const options = journeyKey === "snapshot-font-contract-mismatch"
      ? { fontFaceSrc: "url(\"/assets/mismatch-deadbeef.woff2\")" }
      : {};
    const full = await driveElementTimeline(clock, journeyKey, options);
    return ELEMENT_JOURNEYS[journeyKey](full);
  } finally {
    restoreGlobals(globals);
  }
}

// ---------------------------------------------------------------------------
// Journey: grant rounds (anchor class: GrantController vouchers per frame)
// ---------------------------------------------------------------------------

// The recording runtime wraps the slice protocol the coordinator drives. It
// answers from a pending-count map exactly like the fake runtime the
// coordinator suite uses, and records every voucher the coordinator hands
// out: root, job generation, Date-domain deadline, quota, the tier the grant
// addressed, the processed count returned, and the tier triple after the
// grant. The DeadlineGate terms are sampled at the quota boundary: the gate
// formula (quota reached OR Date-domain deadline passed) is part of the
// frozen contract even though the fake clock never trips the deadline inside
// a single frame. Every voucher also carries the lane that issued it ("grant" for polled grants).
function recordingRuntime(pendingByRoot, grants) {
  return {
    workerHasJob: (root) => pendingByRoot.has(root),
    workerJobGeneration: (root) => (pendingByRoot.has(root) ? 1 : 0),
    workerPendingInTier: (root, tier) => pendingByRoot.get(root)[tier - 1],
    workerRunSlice: (controller, minTier) => {
      const tiers = pendingByRoot.get(controller.root);
      const pendingBefore = [...tiers];
      let processed = 0;
      for (let tier = 1; tier <= minTier && processed < controller.quota; tier += 1) {
        while (tiers[tier - 1] > 0 && processed < controller.quota) {
          tiers[tier - 1] -= 1;
          processed += 1;
        }
      }
      // Conservation invariant of the voucher protocol: a slice moves items
      // from pending to processed and creates or drops nothing. The task-pool
      // unification slice must keep this true at every grant.
      const pendingBeforeSum = pendingBefore.reduce((sum, n) => sum + n, 0);
      const pendingAfterSum = tiers.reduce((sum, n) => sum + n, 0);
      assert.equal(
        pendingBeforeSum,
        processed + pendingAfterSum,
        "grant must conserve pending+processed",
      );
      grants.push({
        root: controller.root.name,
        lane: controller.lane,
        tier: minTier,
        generation: controller.generation,
        deadline: controller.deadline,
        quota: controller.quota,
        processed,
        pendingAfter: [...tiers],
        gateStopsAtQuota: controller.shouldStop(controller.quota),
        gateStopsPastQuota: controller.shouldStop(controller.quota + 1),
      });
      return processed;
    },
  };
}

// Two visible roots. Segment 1 anchors tier-ordered grants across roots;
// segment 2 anchors the adaptive quota on alpha (cold start, growth, ceiling,
// slow-frame halving, suspend-gap immunity, floor walk-down, recovery) while
// beta stays dry.
const GRANT_SCHEDULE = [
  [2, FRAME_STEP_MS],
  [3, FRAME_STEP_MS],
  [4, FRAME_STEP_MS],
  [5, FRAME_STEP_MS],
  [6, FRAME_STEP_MS],
  [7, FRAME_STEP_MS],
  [8, FRAME_STEP_MS],
  [8, FRAME_STEP_MS],
  [8, SLOW_FRAME_STEP_MS],
  [4, FRAME_STEP_MS],
  [5, FRAME_STEP_MS],
  [6, SUSPEND_GAP_MS],
  [6, SLOW_FRAME_STEP_MS],
  [3, SLOW_FRAME_STEP_MS],
  [1, SLOW_FRAME_STEP_MS],
  [1, SLOW_FRAME_STEP_MS],
  [1, FRAME_STEP_MS],
  [2, FRAME_STEP_MS],
];

async function runGrantRoundsJourney(clock) {
  const module = await import("../element.js?timing-golden=grants");
  const Coordinator = module.CoordinationService;
  const coordinator = new Coordinator();
  const alpha = { name: "alpha" };
  const beta = { name: "beta" };
  coordinator.register(alpha);
  coordinator.register(beta);
  const pending = new Map([
    [alpha, [1, 0, 0]],
    [beta, [0, 2, 0]],
  ]);
  const grants = [];
  const runtime = recordingRuntime(pending, grants);
  coordinator.registerWorker(alpha, runtime);
  coordinator.registerWorker(beta, runtime);
  coordinator.setWorkerActive(alpha, true);
  coordinator.setWorkerActive(beta, true);

  // FrameTraceDiagnostics: opt in before the first frame so the ring records
  // one row per frame from the start.
  coordinator.traceConfig = { maxEntries: FRAME_TRACE_LIMIT };

  // Segment 1: tier-ordered grants across the two roots.
  coordinator.requestWorkerFrame(alpha);
  coordinator.requestWorkerFrame(beta);
  clock.advance(FRAME_STEP_MS);

  // Segment 2: adaptive quota schedule on alpha.
  for (const [alphaPending, stepMs] of GRANT_SCHEDULE) {
    pending.get(alpha)[0] = alphaPending;
    coordinator.requestWorkerFrame(alpha);
    clock.advance(stepMs);
  }

  return {
    grants,
    frameTrace: coordinator.frameTrace ?? [],
    finalPending: {
      alpha: pending.get(alpha),
      beta: pending.get(beta),
    },
  };
}

async function recordGrantRounds() {
  const globals = preserveGlobals(CLOCK_GLOBALS);
  const clock = installFakeClock();
  try {
    return await runGrantRoundsJourney(clock);
  } finally {
    restoreGlobals(globals);
  }
}

// ---------------------------------------------------------------------------
// Journey: worker messages (anchor class: init/layout/release order, bridge
// take/issue/release against the plan cache)
// ---------------------------------------------------------------------------

// A Worker double that answers like layout-worker.js (canned plan keyed by the
// request text, error for the failure text) while recording every posted
// message in order. The reply rides a microtask, matching the real Worker's
// asynchronous postMessage.
function recordingWorker(messages) {
  return class RecordingWorker {
    listeners = new Map();

    addEventListener(type, listener) {
      this.listeners.set(type, listener);
    }

    postMessage(message) {
      messages.push(
        message.type === "layout"
          ? { type: message.type, text: message.request.text }
          : { type: message.type },
      );
      queueMicrotask(() => this.listeners.get("message")?.({
        data: message.type === "layout"
          ? message.request.text === "failure"
            ? { id: message.id, ok: false, error: "fixture replay miss" }
            : { id: message.id, ok: true, plan: { fixture: message.request.text } }
          : { id: message.id, ok: true },
      }));
    }

    terminate() {}
  };
}

// One root with one candidate paragraph drives the full protocol: init then
// layout on the first prepare, layout-only on later prepares (the coordinator
// singleton survives module re-evaluation), plan-cache hits and misses through
// the bridge, the failed-request issue string, and release evicting the
// session-prefixed plans.
async function runWorkerMessagesJourney() {
  const bytes = new TextEncoder().encode("fixture-font-source");
  const state = harness(manifestWithFaces([[faceEvidence(digest(bytes))]]), { bytes });
  const handle = await state.loader.prepare(state.root);
  const element = {
    closest: () => state.root,
    getBoundingClientRect: () => ({ top: 0, bottom: 24 }),
  };
  state.root.querySelectorAll = () => [element];

  const messages = [];
  const ops = [];
  const savedWorker = globalThis.Worker;
  const savedInnerHeight = globalThis.innerHeight;
  const savedBridge = globalServices().coordination.layoutWorker;
  const coordinatorKey = Symbol.for("@tiqian/prose.layout-worker-coordinator.v1");
  const savedCoordinator = globalThis[coordinatorKey];
  let requestText = "first";
  const requestJson = () => JSON.stringify({
    text: requestText,
    maxWidthPx: 320,
    semantics: [],
    renderInlineBoxes: [],
  });
  const compactTake = (resultText) => {
    if (resultText === null) return null;
    const record = JSON.parse(resultText);
    return {
      plan: record.plan.fixture,
      semanticReplay: record.semanticReplay,
      semantics: record.semantics,
      inlineBoxes: record.inlineBoxes,
    };
  };

  try {
    delete globalThis[coordinatorKey];
    delete globalServices().coordination.layoutWorker;
    globalThis.Worker = recordingWorker(messages);
    globalThis.innerHeight = 800;
    // C1: the worker channel reads the engine call face, so the fixture
    // request source is an engine override rather than a bridge global.
    setEngineOverride({ workerLayoutRequest: () => requestJson() });

    const module = await import(
      "@tiqian/core/core/engine/web-worker/worker-channel.js?timing-golden=worker-messages"
    );
    const bridge = globalServices().coordination.layoutWorker;
    const prepare = async () => {
      const job = await module.createPrepareJob(
        state.root,
        handle,
        { paragraphSelector: ":is(p, li):not([data-tq-snapshot-key])" },
        () => true,
      );
      let prepared = 0;
      if (job) {
        while (!job.done) {
          job.step(() => false);
          await Promise.resolve();
        }
        prepared = await job.settled;
      }
      ops.push({ op: "prepare", text: requestText, prepared });
      return prepared;
    };
    ops.push({ op: "bridge", version: bridge.version, semanticReplayRevision: bridge.semanticReplayRevision });

    // First prepare initializes the session, then sends one layout message.
    await prepare();
    ops.push({ op: "take", text: "first", out: compactTake(bridge.take(element, handle.id, requestJson())) });

    // Semantic-only changes replay the cached plan with the new semantics.
    const semanticChange = JSON.stringify({
      ...JSON.parse(requestJson()),
      semantics: [{ start: 0, end: 5, tagName: "a", attributes: [["href", "/latest"]] }],
      renderInlineBoxes: [{ start: 0, end: 5, inlineStart: 1, inlineEnd: 2 }],
    });
    ops.push({ op: "take-semantic", out: compactTake(bridge.take(element, handle.id, semanticChange)) });

    // A measure change is a different plan key: miss.
    const changedMeasure = JSON.stringify({ ...JSON.parse(requestJson()), maxWidthPx: 319 });
    ops.push({ op: "take-miss", out: compactTake(bridge.take(element, handle.id, changedMeasure)) });

    // A second prepare for a new text sends a layout message without a new init.
    requestText = "second";
    await prepare();
    ops.push({ op: "issue-clean", out: bridge.issue(element, handle.id, requestJson()) });

    // A failed layout request reports nothing prepared; the issue string
    // survives in the plan cache for the runtime bridge to read.
    requestText = "failure";
    await prepare();
    ops.push({ op: "take-failed", out: compactTake(bridge.take(element, handle.id, requestJson())) });
    ops.push({ op: "issue-failed", out: bridge.issue(element, handle.id, requestJson()) });

    // Release evicts the session-prefixed plans and releases the worker
    // session; the previously cached plan no longer replays.
    ops.push({ op: "release", out: bridge.release(handle.id) });
    requestText = "first";
    ops.push({ op: "take-after-release", out: compactTake(bridge.take(element, handle.id, requestJson())) });
    ops.push({ op: "release-again", out: bridge.release(handle.id) });

    return { messages, ops };
  } finally {
    if (savedWorker === undefined) delete globalThis.Worker;
    else globalThis.Worker = savedWorker;
    if (savedInnerHeight === undefined) delete globalThis.innerHeight;
    else globalThis.innerHeight = savedInnerHeight;
    setEngineOverride(null);
    if (savedBridge === undefined) delete globalServices().coordination.layoutWorker;
    else globalServices().coordination.layoutWorker = savedBridge;
    if (savedCoordinator === undefined) delete globalThis[coordinatorKey];
    else globalThis[coordinatorKey] = savedCoordinator;
    state.loader.release(handle);
  }
}

async function recordWorkerMessages() {
  const globals = preserveGlobals(CLOCK_GLOBALS);
  const clock = installFakeClock();
  try {
    return await runWorkerMessagesJourney(clock);
  } finally {
    restoreGlobals(globals);
  }
}

// ---------------------------------------------------------------------------
// Suite
// ---------------------------------------------------------------------------

const JOURNEYS = {
  "grant-rounds": recordGrantRounds,
  "worker-messages": recordWorkerMessages,
  "event-dispatch": () => recordElementJourney("event-dispatch"),
  "dataset-first-writes": () => recordElementJourney("dataset-first-writes"),
  "token-transitions": () => recordElementJourney("token-transitions"),
  "cache-invalidation": () => recordElementJourney("cache-invalidation"),
  "snapshot-font-contract-mismatch": () => recordElementJourney("snapshot-font-contract-mismatch"),
};

test("timing goldens match the frozen fixture", async () => {
  const updating = process.env[UPDATE_ENV] === "1";
  const fixture = loadFixture();
  const journeys = {};
  let failed = false;
  for (const [id, run] of Object.entries(JOURNEYS)) {
    const recorded = await run();
    journeys[id] = recorded;
    if (updating) continue;
    const expected = fixture.journeys[id];
    if (expected === undefined) {
      console.error(`golden journey ${id} has no frozen record; rerun with ${UPDATE_ENV}=1`);
      failed = true;
      continue;
    }
    try {
      assert.deepStrictEqual(recorded, expected);
    } catch {
      const path = firstDifferencePath(expected, recorded);
      console.error(`golden journey ${id} diverges at ${path}`);
      console.error(`expected: ${JSON.stringify(expected)?.slice(0, 400)}`);
      console.error(`recorded: ${JSON.stringify(recorded)?.slice(0, 400)}`);
      failed = true;
    }
  }
  if (updating) {
    storeGolden(journeys);
    // Round-trip: the stored fixture must equal what was recorded.
    const stored = loadFixture();
    for (const [id, recorded] of Object.entries(journeys)) {
      assert.deepStrictEqual(stored.journeys[id], recorded);
    }
    return;
  }
  assert.equal(failed, false, "timing goldens diverged; review the diff before updating");
});
