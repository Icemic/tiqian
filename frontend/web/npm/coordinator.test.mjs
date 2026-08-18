import assert from "node:assert/strict";
import test from "node:test";

function preserveGlobals(names) {
  return names.map((name) => ({
    name,
    own: Object.prototype.hasOwnProperty.call(globalThis, name),
    value: globalThis[name],
  }));
}

function restoreGlobals(entries) {
  for (const { name, own, value } of entries) {
    if (own) globalThis[name] = value;
    else delete globalThis[name];
  }
}

// Manual rAF + timer clock so tests can drive the coordinator's deferred
// lane without real-time waits.
function installFakeClock() {
  let now = 0;
  let rafId = 0;
  let timerId = 0;
  const rafQueue = new Map();
  const timers = new Map();
  const RealDate = Date;

  globalThis.performance = { now: () => now };
  // Coarse lanes (debounce due times, duration stats) read Date.now, so the
  // fake timeline drives them too.
  globalThis.Date = class FakeDate extends RealDate {
    static now() {
      return now;
    }
  };
  globalThis.requestAnimationFrame = (callback) => {
    const id = ++rafId;
    rafQueue.set(id, callback);
    return id;
  };
  globalThis.cancelAnimationFrame = (id) => rafQueue.delete(id);
  globalThis.setTimeout = (callback, delay = 0) => {
    const id = ++timerId;
    timers.set(id, { callback, dueAt: now + delay });
    return id;
  };
  globalThis.clearTimeout = (id) => timers.delete(id);

  return {
    advance(ms) {
      const target = now + ms;
      for (;;) {
        const dueTimer = [...timers.entries()].filter(([, t]) => t.dueAt <= target)
          .sort((a, b) => a[1].dueAt - b[1].dueAt)[0];
        const dueFrame = [...rafQueue.entries()][0];
        const timerTime = dueTimer ? dueTimer[1].dueAt : Infinity;
        const frameTime = dueFrame ? now : Infinity;
        if (timerTime === Infinity && frameTime === Infinity) break;
        if (frameTime <= timerTime) {
          now = Math.max(now, frameTime);
          const [, callback] = dueFrame;
          rafQueue.delete(dueFrame[0]);
          callback(now);
        } else {
          now = Math.max(now, timerTime);
          const [, { callback }] = dueTimer;
          timers.delete(dueTimer[0]);
          callback();
        }
        if (now > target) break;
      }
      now = target;
    },
  };
}

async function importCoordinator() {
  const module = await import(`./element.js?coordinator=${Math.random()}`);
  return module.TiqianLayoutCoordinator;
}

const globalNames = [
  "performance",
  "requestAnimationFrame",
  "cancelAnimationFrame",
  "setTimeout",
  "clearTimeout",
  "Date",
];

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

// A fake Kotlin facade: pending counts per tier, runSlice drains one item from
// the lowest non-empty tier at or below minTier, mirroring the real job's
// done-scan.
function fakeWorkerRuntime(pendingByElement, grants) {
  return {
    workerHasJob: (element) => pendingByElement.has(element),
    workerPendingInTier: (element, tier) => pendingByElement.get(element)[tier - 1],
    workerRunSlice: (element, budgetMs, minTier) => {
      const tiers = pendingByElement.get(element);
      grants.push([element.name, minTier]);
      for (let tier = 1; tier <= minTier; tier++) {
        if (tiers[tier - 1] > 0) {
          tiers[tier - 1] -= 1;
          return 1;
        }
      }
      return 0;
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
    const runtime = fakeWorkerRuntime(pending, grants);
    coordinator.registerWorker(rootA, runtime);
    coordinator.registerWorker(rootB, runtime);
    coordinator.setWorkerActive(rootA, true);

    clock.advance(16);
    // TierOrderedGrants: every visible root's tier 1 drains before the first
    // tier 2 grant, across roots.
    assert.deepEqual(grants, [["a", 1], ["b", 2]]);
    assert.deepEqual(pending.get(rootA), [0, 0, 0]);
    assert.deepEqual(pending.get(rootB), [0, 0, 0]);
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

    // Past the window the deferred wake runs the slices in tier order.
    clock.advance(200);
    assert.deepEqual(grants, [["offscreen", 1], ["offscreen", 1]]);
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
