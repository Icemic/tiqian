import assert from "node:assert/strict";
import test from "node:test";
import v8 from "node:v8";
import vm from "node:vm";

import {
  preserveGlobals,
  restoreGlobals,
  installFakeClock,
  CLOCK_GLOBALS as globalNames,
} from "./test-clock.js";

async function importCoordinator() {
  const module = await import(`../element.js?coordinator=${Math.random()}`);
  return module.CoordinationService;
}

test("offscreen frame tasks wait out the debounce instead of running each frame", async () => {
  const globals = preserveGlobals(globalNames);
  const clock = installFakeClock();
  try {
    const Coordinator = await importCoordinator();
    const coordinator = new Coordinator();
    const session = coordinator.register();
    coordinator.update(session, { inViewport: false });

    let runs = 0;
    const callback = () => {
      runs += 1;
    };
    coordinator.requestFrame(callback, session);

    // Several frames inside the debounce window: nothing runs, and repeated
    // requests keep pushing the due time later (trailing debounce).
    for (let frame = 0; frame < 6; frame += 1) {
      clock.advance(16);
      coordinator.requestFrame(callback, session);
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
    const session = coordinator.register();
    coordinator.update(session, { inViewport: false });

    let runs = 0;
    coordinator.requestFrame(() => {
      runs += 1;
    }, session);
    clock.advance(16);
    assert.equal(runs, 0);

    // Back on screen: the pending task runs on the next frame, long before
    // the 200ms timer would have fired.
    coordinator.update(session, { inViewport: true });
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
    const session = coordinator.register();
    const gone = coordinator.register();
    coordinator.update(session, { inViewport: false });
    coordinator.update(gone, { inViewport: false });

    let ran = 0;
    const callback = () => {
      ran += 1;
    };
    coordinator.requestFrame(callback, session);
    coordinator.requestFrame(callback, gone);
    coordinator.cancelFrame(callback);
    clock.advance(400);
    assert.equal(ran, 0);

    coordinator.requestFrame(callback, session);
    coordinator.unregister(session);
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
    const session = coordinator.register();

    let runs = 0;
    coordinator.requestFrame(() => {
      runs += 1;
    }, session);
    clock.advance(16);
    assert.equal(runs, 1);
  } finally {
    restoreGlobals(globals);
  }
});

// A fake LayoutJobPool: pending counts per tier, runSlice drains up to the
// grant's quota from the lowest non-empty tier at or below minTier, mirroring
// the real pool's done-scan and its obedience to the quota stop term.
// runSlice receives one grant controller per call; the fake reads the
// recipient root off it, like the real pool does. The service owns the pool
// now, so each test installs the fake on coordinator.layoutJobPool.
function fakeWorkerRuntime(pendingByElement, grants, controllers) {
  return {
    hasJob: (element) => pendingByElement.has(element),
    jobGeneration: (element) => (pendingByElement.has(element) ? 1 : 0),
    pendingInTier: (element, tier) => pendingByElement.get(element)[tier - 1],
    runSlice: (controller, minTier) => {
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
    const sessionA = coordinator.register();
    const sessionB = coordinator.register();
    const pending = new Map([
      [rootA, [1, 0, 0]],
      [rootB, [0, 1, 0]],
    ]);
    const grants = [];
    const controllers = [];
    const runtime = fakeWorkerRuntime(pending, grants, controllers);
    coordinator.layoutJobPool = runtime;
    coordinator.registerWorker(sessionA, rootA);
    coordinator.registerWorker(sessionB, rootB);
    coordinator.setWorkerActive(sessionA, true);

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
    assert.equal(controllers[0].admissionClass, "grant");
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
    const session = coordinator.register();
    coordinator.update(session, { inViewport: false });
    const pending = new Map([[root, [2, 0, 0]]]);
    const grants = [];
    coordinator.layoutJobPool = fakeWorkerRuntime(pending, grants);
    coordinator.registerWorker(session, root);
    coordinator.setWorkerActive(session, true);

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
    const session = coordinator.register();
    coordinator.update(session, { inViewport: false });
    const pending = new Map([[root, [1, 0, 0]]]);
    const grants = [];
    coordinator.layoutJobPool = fakeWorkerRuntime(pending, grants);
    coordinator.registerWorker(session, root);
    coordinator.setWorkerActive(session, true);
    clock.advance(32);
    assert.equal(grants.length, 0);

    // Back on screen: the next frame grants without waiting out the window.
    coordinator.update(session, { inViewport: true });
    coordinator.clearWorkerDeferred(session);
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
    const session = coordinator.register();
    const pending = new Map([[root, [0, 0, 0]]]);
    const controllers = [];
    coordinator.layoutJobPool = fakeWorkerRuntime(pending, [], controllers);
    coordinator.registerWorker(session, root);
    coordinator.setWorkerActive(session, true);

    const feed = (n) => { pending.get(root)[0] = n; };
    const frameQuota = () => {
      const quotas = controllers.map((c) => c.quota);
      controllers.length = 0;
      return quotas;
    };
    const runFrame = (quota, stepMs) => {
      feed(quota);
      coordinator.requestWorkerFrame(session);
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
    const sessionA = coordinator.register();
    const sessionB = coordinator.register();
    const pending = new Map([
      [rootA, [0, 0, 0]],
      [rootB, [0, 0, 0]],
    ]);
    const controllers = [];
    coordinator.layoutJobPool = fakeWorkerRuntime(pending, [], controllers);
    coordinator.registerWorker(sessionA, rootA);
    coordinator.registerWorker(sessionB, rootB);
    coordinator.setWorkerActive(sessionA, true);
    coordinator.setWorkerActive(sessionB, true);

    const feed = (element, n) => { pending.get(element)[0] = n; };
    const frameQuotas = () => {
      const quotas = controllers.map((c) => [c.root.name, c.quota]);
      controllers.length = 0;
      return quotas;
    };
    const runFrame = (a, b, stepMs) => {
      feed(rootA, a);
      feed(rootB, b);
      coordinator.requestWorkerFrame(sessionA);
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

test("runPrepare advances candidate jobs inside the frame loop and handles reregistration", async () => {
  const globals = preserveGlobals(globalNames);
  const clock = installFakeClock();
  try {
    const Coordinator = await importCoordinator();
    const coordinator = new Coordinator();
    const session = coordinator.register();

    function createFakeJob(totalCandidates = 3) {
      let index = 0;
      let done = false;
      let finishedResolve = null;
      const finished = new Promise((resolve) => { finishedResolve = resolve; });
      const job = {
        get done() { return done; },
        onFinished: null,
        finished,
        step(shouldYield) {
          let dispatched = 0;
          while (index < totalCandidates) {
            if (dispatched > 0 && shouldYield()) return dispatched;
            index += 1;
            dispatched += 1;
            if (index >= totalCandidates) {
              done = true;
              finishedResolve(totalCandidates);
            }
            if (job.onFinished) job.onFinished(job);
            return dispatched;
          }
          if (index >= totalCandidates) {
            done = true;
            finishedResolve(totalCandidates);
            if (job.onFinished) job.onFinished(job);
          }
          return dispatched;
        },
      };
      return job;
    }

    const job = createFakeJob(3);
    const promise = coordinator.runPrepare(session, job);

    // Advancing the clock through 3 frames steps each candidate and finishes the job.
    clock.advance(48);
    assert.equal(await promise, 3);

    // A second registration for the same root session resolves the previous promise with 0.
    const firstJob = createFakeJob(3);
    const secondJob = createFakeJob(3);
    const firstPromise = coordinator.runPrepare(session, firstJob);
    const secondPromise = coordinator.runPrepare(session, secondJob);
    assert.equal(await firstPromise, 0);

    // Advancing through the second job finishes it cleanly.
    clock.advance(48);
    assert.equal(await secondPromise, 3);
  } finally {
    restoreGlobals(globals);
  }
});

test("a prepare job cancelled by its staleness guard finishes and retires its member", async () => {
  const globals = preserveGlobals(globalNames);
  const clock = installFakeClock();
  // Count rAF requests so the retirement assertion can see the frame loop
  // disarm instead of spinning on a member that will never advance. The
  // fake clock fires every queued frame immediately, so each fake job must
  // finish inside the advance that steps it.
  const realRaf = globalThis.requestAnimationFrame;
  let rafRequests = 0;
  globalThis.requestAnimationFrame = (callback) => {
    rafRequests += 1;
    return realRaf(callback);
  };
  try {
    const Coordinator = await importCoordinator();
    const coordinator = new Coordinator();
    const session = coordinator.register();

    // Phase one: a healthy job finishes through the frame loop.
    let remaining = 2;
    let done = false;
    let finishedResolve = null;
    const finished = new Promise((resolve) => { finishedResolve = resolve; });
    const healthy = {
      get done() { return done; },
      onFinished: null,
      finished,
      step() {
        remaining -= 1;
        if (remaining <= 0) {
          done = true;
          finishedResolve(remaining === 0 ? 2 : 1);
        }
        return 1;
      },
    };
    const healthyPromise = coordinator.runPrepare(session, healthy);
    clock.advance(16);
    assert.equal(await healthyPromise, 2);
    assert.ok(rafRequests > 0);

    // Phase two: a job already stale finishes from inside its first step the
    // way CancelledPrepareFinishesEarly does, and the member retires.
    let cancelledDone = false;
    let cancelledResolve = null;
    const cancelledFinished = new Promise((resolve) => { cancelledResolve = resolve; });
    const cancelled = {
      get done() { return cancelledDone; },
      onFinished: null,
      finished: cancelledFinished,
      step() {
        cancelledDone = true;
        cancelledResolve(0);
        return 0;
      },
    };
    const cancelledPromise = coordinator.runPrepare(session, cancelled);
    clock.advance(16);
    assert.equal(await cancelledPromise, 0);

    // The member is gone: further frames arm no loop at all.
    const rafAtRetirement = rafRequests;
    clock.advance(160);
    assert.equal(rafRequests, rafAtRetirement);
  } finally {
    globalThis.requestAnimationFrame = realRaf;
    restoreGlobals(globals);
  }
});

// WeakSlotElement retirement (spec wc-s6 scope 5: no cleanup path may depend
// on disconnectedCallback). The slot holds its root through a WeakRef, so a
// root object that nobody else references is collectable; the next frame scan
// retires the slot and the worker wake timer stops being re-armed. Node's
// test runner starts without --expose-gc, so the test enables the flag at
// runtime and runs a full collection twice (WeakRef targets clear on the
// collection after they become unreachable).
test("worker slot retires without unregister once the root object is collected", async () => {
  const globals = preserveGlobals(globalNames);
  const clock = installFakeClock();
  try {
    v8.setFlagsFromString("--expose-gc");
    const gc = vm.runInNewContext("gc");
    const Coordinator = await importCoordinator();
    const coordinator = new Coordinator();
    const session = coordinator.register();
    coordinator.update(session, { inViewport: false });
    let root = { name: "collected" };
    const pending = new Map([[root, [2, 0, 0]]]);
    coordinator.layoutJobPool = fakeWorkerRuntime(pending, []);
    coordinator.registerWorker(session, root);
    coordinator.setWorkerActive(session, true);

    // Inside the debounce window the off-screen slot holds one pending wake
    // timer. Dropping every strong reference to the root and collecting it
    // leaves only the slot's WeakRef.
    clock.advance(16);
    assert.ok(clock.pendingTimerCount() >= 1, "off-screen slot keeps a wake timer armed");
    coordinator.layoutJobPool = fakeWorkerRuntime(new Map(), []);
    root = null;
    gc();
    gc();

    // The wake timer fires, the frame scan meets a dead WeakRef, and the
    // slot retires without unregister: no further timer is armed for it.
    clock.advance(300);
    assert.equal(clock.pendingTimerCount(), 0, "no wake timer survives the retired slot");
  } finally {
    restoreGlobals(globals);
  }
});
