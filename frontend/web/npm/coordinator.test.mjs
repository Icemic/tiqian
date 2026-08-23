import assert from "node:assert/strict";
import test from "node:test";

import {
  preserveGlobals,
  restoreGlobals,
  installFakeClock,
  CLOCK_GLOBALS as globalNames,
} from "./test-clock.mjs";

async function importCoordinator() {
  const module = await import(`./element.js?coordinator=${Math.random()}`);
  return module.TiqianLayoutCoordinator;
}

test("offscreen frame tasks wait out the debounce instead of running each frame", async () => {
  const globals = preserveGlobals(globalNames);
  const clock = installFakeClock();
  try {
    const Coordinator = await importCoordinator();
    const coordinator = new Coordinator();
    const element = {};
    coordinator.register(element);
    coordinator.update(element, { inViewport: false });

    let runs = 0;
    const callback = () => {
      runs += 1;
    };
    coordinator.requestFrame(callback, element);

    // Several frames inside the debounce window: nothing runs, and repeated
    // requests keep pushing the due time later (trailing debounce).
    for (let frame = 0; frame < 6; frame += 1) {
      clock.advance(16);
      coordinator.requestFrame(callback, element);
    }
    assert.equal(runs, 0);

    // Past the debounce window with no further requests: exactly one run.
    clock.advance(250);
    assert.equal(runs, 1);
  } finally {
    restoreGlobals(globals);
  }
});

test("returning to the viewport promotes a deferred task without waiting for the debounce", async () => {
  const globals = preserveGlobals(globalNames);
  const clock = installFakeClock();
  try {
    const Coordinator = await importCoordinator();
    const coordinator = new Coordinator();
    const element = {};
    coordinator.register(element);
    coordinator.update(element, { inViewport: false });

    let runs = 0;
    coordinator.requestFrame(() => {
      runs += 1;
    }, element);
    clock.advance(16);
    assert.equal(runs, 0);

    // Back on screen: the pending task runs on the next frame, long before
    // the 200ms timer would have fired.
    coordinator.update(element, { inViewport: true });
    clock.advance(16);
    assert.equal(runs, 1);
  } finally {
    restoreGlobals(globals);
  }
});

test("cancelFrame and unregister drop deferred tasks and the shared timer", async () => {
  const globals = preserveGlobals(globalNames);
  const clock = installFakeClock();
  try {
    const Coordinator = await importCoordinator();
    const coordinator = new Coordinator();
    const element = {};
    const gone = {};
    coordinator.register(element);
    coordinator.register(gone);
    coordinator.update(element, { inViewport: false });
    coordinator.update(gone, { inViewport: false });

    let ran = 0;
    const callback = () => {
      ran += 1;
    };
    coordinator.requestFrame(callback, element);
    coordinator.requestFrame(callback, gone);
    coordinator.cancelFrame(callback);
    clock.advance(400);
    assert.equal(ran, 0);

    coordinator.requestFrame(callback, element);
    coordinator.unregister(element);
    clock.advance(400);
    assert.equal(ran, 0);
  } finally {
    restoreGlobals(globals);
  }
});

test("in-viewport frame tasks keep running on the next frame", async () => {
  const globals = preserveGlobals(globalNames);
  const clock = installFakeClock();
  try {
    const Coordinator = await importCoordinator();
    const coordinator = new Coordinator();
    const element = {};
    coordinator.register(element);

    let runs = 0;
    coordinator.requestFrame(() => {
      runs += 1;
    }, element);
    clock.advance(16);
    assert.equal(runs, 1);
  } finally {
    restoreGlobals(globals);
  }
});

// A fake Kotlin facade: pending counts per tier, runSlice drains up to the
// grant's quota from the lowest non-empty tier at or below minTier, mirroring
// the real job's done-scan and its obedience to the quota stop term.
// runSlice receives one grant controller per call; the fake reads the
// recipient root off it, like the real facade does.
function fakeWorkerRuntime(pendingByElement, grants, controllers) {
  return {
    workerHasJob: (element) => pendingByElement.has(element),
    workerJobGeneration: (element) => (pendingByElement.has(element) ? 1 : 0),
    workerPendingInTier: (element, tier) => pendingByElement.get(element)[tier - 1],
    workerRunSlice: (controller, minTier) => {
      const element = controller.root;
      const tiers = pendingByElement.get(element);
      grants.push([element.name, minTier]);
      if (controllers) controllers.push(controller);
      let processed = 0;
      for (let tier = 1; tier <= minTier && processed < controller.quota; tier++) {
        while (tiers[tier - 1] > 0 && processed < controller.quota) {
          tiers[tier - 1] -= 1;
          processed += 1;
        }
      }
      return processed;
    },
  };
}

test("visible workers drain tier 1 before any worker runs tier 2", async () => {
  const globals = preserveGlobals(globalNames);
  const clock = installFakeClock();
  try {
    const Coordinator = await importCoordinator();
    const coordinator = new Coordinator();
    const rootA = { name: "a" };
    const rootB = { name: "b" };
    coordinator.register(rootA);
    coordinator.register(rootB);
    const pending = new Map([
      [rootA, [1, 0, 0]],
      [rootB, [0, 1, 0]],
    ]);
    const grants = [];
    const controllers = [];
    const runtime = fakeWorkerRuntime(pending, grants, controllers);
    coordinator.registerWorker(rootA, runtime);
    coordinator.registerWorker(rootB, runtime);
    coordinator.setWorkerActive(rootA, true);

    clock.advance(16);
    // TierOrderedGrants: every visible root's tier 1 drains before the first
    // tier 2 grant, across roots.
    assert.deepEqual(grants, [["a", 1], ["b", 2]]);
    assert.deepEqual(pending.get(rootA), [0, 0, 0]);
    assert.deepEqual(pending.get(rootB), [0, 0, 0]);
    // GrantController: each grant carries value-copied stop terms addressed to
    // one recipient. The fake clock keeps both clocks on the same reading, so
    // the deadline lands in the frame's domain. The quota alone must be enough
    // to stop the slice; the deadline is time-dependent and stays unchecked.
    // AdaptiveGrantQuota starts cold at 2 paragraphs per grant.
    assert.equal(controllers.length, 2);
    assert.equal(controllers[0].root, rootA);
    assert.equal(controllers[0].generation, 1);
    assert.equal(controllers[0].quota, 2);
    assert.equal(typeof controllers[0].shouldStop, "function");
    assert.equal(controllers[0].shouldStop(controllers[0].quota), true);
    assert.equal(controllers[1].root, rootB);
  } finally {
    restoreGlobals(globals);
  }
});

test("offscreen workers wait out the debounce before receiving grants", async () => {
  const globals = preserveGlobals(globalNames);
  const clock = installFakeClock();
  try {
    const Coordinator = await importCoordinator();
    const coordinator = new Coordinator();
    const root = { name: "offscreen" };
    coordinator.register(root);
    coordinator.update(root, { inViewport: false });
    const pending = new Map([[root, [2, 0, 0]]]);
    const grants = [];
    coordinator.registerWorker(root, fakeWorkerRuntime(pending, grants));
    coordinator.setWorkerActive(root, true);

    // Frames inside the debounce window: no grant at all.
    clock.advance(16);
    clock.advance(100);
    assert.equal(grants.length, 0);

    // Past the window the deferred wake runs the slice; the cold-start quota
    // of 2 covers both pending items in one grant.
    clock.advance(200);
    assert.deepEqual(grants, [["offscreen", 1]]);
    assert.deepEqual(pending.get(root), [0, 0, 0]);
  } finally {
    restoreGlobals(globals);
  }
});

test("returning to the viewport clears the worker debounce immediately", async () => {
  const globals = preserveGlobals(globalNames);
  const clock = installFakeClock();
  try {
    const Coordinator = await importCoordinator();
    const coordinator = new Coordinator();
    const root = { name: "returning" };
    coordinator.register(root);
    coordinator.update(root, { inViewport: false });
    const pending = new Map([[root, [1, 0, 0]]]);
    const grants = [];
    coordinator.registerWorker(root, fakeWorkerRuntime(pending, grants));
    coordinator.setWorkerActive(root, true);
    clock.advance(32);
    assert.equal(grants.length, 0);

    // Back on screen: the next frame grants without waiting out the window.
    coordinator.update(root, { inViewport: true });
    coordinator.clearWorkerDeferred(root);
    clock.advance(16);
    assert.deepEqual(grants, [["returning", 1]]);
  } finally {
    restoreGlobals(globals);
  }
});

// The fake clock consumes a queued frame at the start of the next advance()
// with the previous advance's end time, so an advance step of S shows up as
// the frame delta of the frame consumed by the following advance. Each step
// below feeds exactly one grant's worth of work, so one frame equals one
// grant and the quota each controller carries is directly observable.
test("grant quota grows on healthy frames and halves on slow ones", async () => {
  const globals = preserveGlobals(globalNames);
  const clock = installFakeClock();
  try {
    const Coordinator = await importCoordinator();
    const coordinator = new Coordinator();
    const root = { name: "adaptive" };
    coordinator.register(root);
    const pending = new Map([[root, [0, 0, 0]]]);
    const controllers = [];
    coordinator.registerWorker(root, fakeWorkerRuntime(pending, [], controllers));
    coordinator.setWorkerActive(root, true);

    const feed = (n) => { pending.get(root)[0] = n; };
    const frameQuota = () => {
      const quotas = controllers.map((c) => c.quota);
      controllers.length = 0;
      return quotas;
    };
    const runFrame = (quota, stepMs) => {
      feed(quota);
      coordinator.requestWorkerFrame(root);
      clock.advance(stepMs);
      return frameQuota();
    };

    // Cold start: the first frame has no predecessor to judge.
    assert.deepEqual(runFrame(2, 16), [2]);
    // Healthy frames climb the quota one step each.
    assert.deepEqual(runFrame(3, 16), [3]);
    assert.deepEqual(runFrame(4, 16), [4]);
    assert.deepEqual(runFrame(5, 16), [5]);
    assert.deepEqual(runFrame(6, 16), [6]);
    assert.deepEqual(runFrame(7, 16), [7]);
    // The ceiling holds on further healthy frames.
    assert.deepEqual(runFrame(8, 16), [8]);
    assert.deepEqual(runFrame(8, 16), [8]);

    // A 60ms step lands as the next frame's delta: the frame the 60ms step
    // itself consumes still sees 16ms, then the following frame is judged
    // slow and halves the quota.
    assert.deepEqual(runFrame(8, 60), [8]);
    assert.deepEqual(runFrame(4, 16), [4]);
    // Healthy frames climb back.
    assert.deepEqual(runFrame(5, 16), [5]);

    // A suspend-sized gap (300ms) judges nobody: the quota survives it.
    assert.deepEqual(runFrame(6, 300), [6]);
    assert.deepEqual(runFrame(6, 60), [6]);

    // Consecutive slow frames walk the quota down to the floor and hold it.
    assert.deepEqual(runFrame(3, 60), [3]);
    assert.deepEqual(runFrame(1, 60), [1]);
    assert.deepEqual(runFrame(1, 60), [1]);
    assert.deepEqual(runFrame(1, 16), [1]);
    // Recovery resumes from the floor.
    assert.deepEqual(runFrame(2, 16), [2]);
    assert.deepEqual(pending.get(root), [0, 0, 0]);
  } finally {
    restoreGlobals(globals);
  }
});

test("a slow frame halves only roots that committed in the previous frame", async () => {
  const globals = preserveGlobals(globalNames);
  const clock = installFakeClock();
  try {
    const Coordinator = await importCoordinator();
    const coordinator = new Coordinator();
    const rootA = { name: "heavy" };
    const rootB = { name: "quiet" };
    coordinator.register(rootA);
    coordinator.register(rootB);
    const pending = new Map([
      [rootA, [0, 0, 0]],
      [rootB, [0, 0, 0]],
    ]);
    const controllers = [];
    coordinator.registerWorker(rootA, fakeWorkerRuntime(pending, [], controllers));
    coordinator.registerWorker(rootB, fakeWorkerRuntime(pending, [], controllers));
    coordinator.setWorkerActive(rootA, true);
    coordinator.setWorkerActive(rootB, true);

    const feed = (element, n) => { pending.get(element)[0] = n; };
    const frameQuotas = () => {
      const quotas = controllers.map((c) => [c.root.name, c.quota]);
      controllers.length = 0;
      return quotas;
    };
    const runFrame = (a, b, stepMs) => {
      feed(rootA, a);
      feed(rootB, b);
      coordinator.requestWorkerFrame(rootA);
      clock.advance(stepMs);
    };

    // Both roots climb together on healthy frames.
    runFrame(2, 2, 16);
    assert.deepEqual(frameQuotas(), [["heavy", 2], ["quiet", 2]]);
    runFrame(3, 3, 16);
    assert.deepEqual(frameQuotas(), [["heavy", 3], ["quiet", 3]]);
    runFrame(4, 4, 16);
    assert.deepEqual(frameQuotas(), [["heavy", 4], ["quiet", 4]]);

    // Only the heavy root commits this frame; the quiet root runs dry. The
    // healthy verdict still raises both roots, because both committed in the
    // previous frame.
    runFrame(5, 0, 60);
    assert.deepEqual(frameQuotas(), [["heavy", 5]]);

    // The 60ms step lands as this frame's delta: a slow frame. The heavy
    // root committed in the previous frame and halves; the quiet root ran
    // dry and keeps its quota.
    runFrame(2, 0, 16);
    assert.deepEqual(frameQuotas(), [["heavy", 2]]);

    // The quiet root's next grant carries the quota it earned before the
    // slow frame; a spillover verdict would have halved it to 2.
    runFrame(3, 5, 16);
    assert.deepEqual(frameQuotas(), [["heavy", 3], ["quiet", 5]]);
    assert.deepEqual(pending.get(rootA), [0, 0, 0]);
    assert.deepEqual(pending.get(rootB), [0, 0, 0]);
  } finally {
    restoreGlobals(globals);
  }
});
