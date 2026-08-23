// Timing-anchor goldens for the web prose host refactor (ADR 0053 batch 0,
// decomposition report section 11). Every journey runs real module code under
// the shared fake clock (test-clock.mjs) so event order AND numeric durations
// are deterministic. Golden updates go through TIQIAN_UPDATE_TIMING_GOLDEN=1;
// each diff is reviewed before the fixture is committed, mirroring the JVM
// LayoutDumpGoldenTest discipline.

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

const FIXTURE_PATH = fileURLToPath(new URL("./timing-golden.fixture.json", import.meta.url));
const GOLDEN_VERSION = 1;
const UPDATE_ENV = "TIQIAN_UPDATE_TIMING_GOLDEN";
const FRAME_TRACE_LIMIT = 600;
const FRAME_STEP_MS = 16;
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
// a single frame.
function recordingRuntime(pendingByRoot, grants) {
  return {
    workerHasJob: (root) => pendingByRoot.has(root),
    workerJobGeneration: (root) => (pendingByRoot.has(root) ? 1 : 0),
    workerPendingInTier: (root, tier) => pendingByRoot.get(root)[tier - 1],
    workerRunSlice: (controller, minTier) => {
      const tiers = pendingByRoot.get(controller.root);
      let processed = 0;
      for (let tier = 1; tier <= minTier && processed < controller.quota; tier += 1) {
        while (tiers[tier - 1] > 0 && processed < controller.quota) {
          tiers[tier - 1] -= 1;
          processed += 1;
        }
      }
      grants.push({
        root: controller.root.name,
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
  const module = await import("./element.js?timing-golden=grants");
  const Coordinator = module.TiqianLayoutCoordinator;
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
  globalThis.__tqTrace = { maxEntries: FRAME_TRACE_LIMIT };

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
    frameTrace: globalThis.__tqFrameTrace ?? [],
    finalPending: {
      alpha: pending.get(alpha),
      beta: pending.get(beta),
    },
  };
}

async function recordGrantRounds() {
  const globals = preserveGlobals(CLOCK_GLOBALS);
  const savedTrace = globalThis.__tqTrace;
  const savedRing = globalThis.__tqFrameTrace;
  delete globalThis.__tqTrace;
  delete globalThis.__tqFrameTrace;
  const clock = installFakeClock();
  try {
    return await runGrantRoundsJourney(clock);
  } finally {
    restoreGlobals(globals);
    if (savedTrace === undefined) delete globalThis.__tqTrace;
    else globalThis.__tqTrace = savedTrace;
    if (savedRing === undefined) delete globalThis.__tqFrameTrace;
    else globalThis.__tqFrameTrace = savedRing;
  }
}

// ---------------------------------------------------------------------------
// Suite
// ---------------------------------------------------------------------------

const JOURNEYS = {
  "grant-rounds": recordGrantRounds,
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
