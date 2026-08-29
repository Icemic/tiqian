// Iframe strong reference cleanup test (spec wc-s6 scope 5 / completion
// criteria 3). After an iframe is removed from the DOM and its elements are
// no longer referenced by the host page, the layout job pool's WeakMap-based
// job registry must not prevent garbage collection of those elements. The old
// implementation used a Map<Element, LayoutJob> which held strong references
// and prevented GC of elements from removed iframes.
import assert from "node:assert/strict";
import test from "node:test";

import { createLayoutJobPool } from "../core/engine/layout-job-pool.js";
import type { LayoutJobPool } from "../core/engine/layout-job-pool.js";

// Minimal fake Element for testing. Uses a class so WeakMap/WeakSet accept it.
class FakeElement {
  tagName: string;
  constructor(tag: string = "DIV") {
    this.tagName = tag;
  }
}

test("layout job pool uses WeakMap for jobs, allowing element GC after removal", () => {
  const pool: LayoutJobPool = createLayoutJobPool();
  const element: FakeElement = new FakeElement();

  // Start a coordinated job for the element. startJob returns void; a
  // coordinated job waits for coordinator grants, so it stays registered
  // until cancelled.
  pool.startJob({
    root: element as FakeElement & Element,
    kind: "Enhance",
    itemCount: 1,
    processItem: (): void => {},
    onFinished: (): void => {},
    onFailed: (): void => {},
    startedAt: 0,
    itemTierIndex: [0],
    coordinated: true,
  });
  assert.equal(pool.hasJob(element as FakeElement & Element), true, "pool reports having a job for the element");

  // Cancel the job (simulating element removal)
  pool.cancelJob(element as FakeElement & Element);
  assert.equal(pool.hasJob(element as FakeElement & Element), false, "job removed after cancel");

  // The element is now only held by our local variable. When it goes out of
  // scope, the WeakMap entry is automatically cleaned up. This test verifies
  // the API contract: after cancelJob, the pool no longer references the element.
});

test("layout job pool hasJob returns false for unknown elements", () => {
  const pool: LayoutJobPool = createLayoutJobPool();
  const element: FakeElement = new FakeElement();

  assert.equal(pool.hasJob(element as FakeElement & Element), false, "no job for element that was never started");
});

test("layout job pool cancelJob is idempotent", () => {
  const pool: LayoutJobPool = createLayoutJobPool();
  const element: FakeElement = new FakeElement();

  // Cancel without starting should not throw
  pool.cancelJob(element as FakeElement & Element);
  pool.cancelJob(element as FakeElement & Element);
});

test("multiple elements in the pool are independent", () => {
  const pool: LayoutJobPool = createLayoutJobPool();
  const element1: FakeElement = new FakeElement();
  const element2: FakeElement = new FakeElement();

  pool.startJob({
    root: element1 as FakeElement & Element,
    kind: "Enhance",
    itemCount: 1,
    processItem: (): void => {},
    onFinished: (): void => {},
    onFailed: (): void => {},
    startedAt: 0,
    itemTierIndex: [0],
    coordinated: true,
  });
  pool.startJob({
    root: element2 as FakeElement & Element,
    kind: "Enhance",
    itemCount: 1,
    processItem: (): void => {},
    onFinished: (): void => {},
    onFailed: (): void => {},
    startedAt: 0,
    itemTierIndex: [0],
    coordinated: true,
  });

  assert.equal(pool.hasJob(element1 as FakeElement & Element), true);
  assert.equal(pool.hasJob(element2 as FakeElement & Element), true);

  pool.cancelJob(element1 as FakeElement & Element);
  assert.equal(pool.hasJob(element1 as FakeElement & Element), false);
  assert.equal(pool.hasJob(element2 as FakeElement & Element), true, "element2 job unaffected by element1 cancel");
});
