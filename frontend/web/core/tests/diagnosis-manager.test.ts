// wc-s4c zero-allocation evidence: the diagnosis manager's event channel is
// demand-driven. Event objects are constructed inside broadcast() only when a
// listener is attached, and eventBroadcastCount increments exactly once per
// constructed event. The unlistened path must therefore finish with a zero
// broadcast count while dataset writes still land; subscribing turns the
// channel on, and the last unsubscribe (or dispose) turns it off again.

import assert from "node:assert/strict";
import test from "node:test";

import { createDiagnosisManager } from "../core/engine/context/diagnosis-manager.js";
import type { DiagnosisEvent } from "../core/engine/context/diagnosis-manager.js";
import { constructEnhanceContext } from "../core/engine/context/enhance-context.js";

// The fakes ride through the same any-typed Object.create seam the
// engine-plain-context suite uses: the typed contracts are satisfied by
// assignment without casts.
function fakeDataset() {
  return Object.assign(Object.create(null), {});
}

function fakeDatasetHost() {
  const dataset = fakeDataset();
  return { dataset, host: { dataset } };
}

test("unlistened diagnosis writes allocate no events but still write the dataset", () => {
  const { dataset, host } = fakeDatasetHost();
  const manager = createDiagnosisManager(host);

  manager.set("tiqianLoadMs", "12.3");
  manager.set("tiqianFontWait", "timeout");
  manager.clear("tiqianFontWait");
  manager.signal("tiqianSnapshotLiveIssue", "SnapshotFontLoadCycle");

  assert.equal(dataset.tiqianLoadMs, "12.3");
  assert.equal("tiqianFontWait" in dataset, false);
  // signal is event-only telemetry: no dataset record appears.
  assert.equal("tiqianSnapshotLiveIssue" in dataset, false);
  // Object-count evidence: broadcast constructs an event object and bumps
  // this counter only while listeners are attached. Zero proves the four
  // unlistened writes above constructed zero event objects.
  assert.equal(manager.eventBroadcastCount, 0);
});

test("subscribed listeners receive every event with set and clear semantics", () => {
  const { dataset, host } = fakeDatasetHost();
  const manager = createDiagnosisManager(host);
  const received: DiagnosisEvent[] = [];
  const unsubscribe = manager.subscribe((event) => received.push(event));

  manager.set("tiqianCapabilityIssue", "RuntimeLoadFailed");
  manager.clear("tiqianCapabilityIssue");
  manager.signal("tiqianSnapshotLiveIssue", "SnapshotFontLoadCycle");

  assert.equal(dataset.tiqianCapabilityIssue, undefined);
  assert.deepEqual(received, [
    { kind: "set", key: "tiqianCapabilityIssue", value: "RuntimeLoadFailed" },
    { kind: "clear", key: "tiqianCapabilityIssue", value: null },
    { kind: "set", key: "tiqianSnapshotLiveIssue", value: "SnapshotFontLoadCycle" },
  ]);
  assert.equal(manager.eventBroadcastCount, 3);

  unsubscribe();
  manager.set("tiqianEnhanceMs", "4.2");
  assert.equal(dataset.tiqianEnhanceMs, "4.2");
  // The last unsubscribe returns the channel to its unlistened shape: no
  // further events are constructed and the counter stays frozen.
  assert.equal(received.length, 3);
  assert.equal(manager.eventBroadcastCount, 3);
});

test("dispose detaches every remaining listener", () => {
  const { host } = fakeDatasetHost();
  const manager = createDiagnosisManager(host);
  const received: DiagnosisEvent[] = [];
  manager.subscribe((event) => received.push(event));
  manager.set("tiqianLoadMs", "1.0");

  manager.dispose();
  manager.set("tiqianRelayoutMs", "2.0");

  assert.equal(received.length, 1);
  assert.equal(manager.eventBroadcastCount, 1);
});

test("the context-owned manager resolves the host dataset live", () => {
  // constructEnhanceContext types the element as Element; the fake carries
  // only the readable `dataset` property the diagnosis host resolves.
  const dataset = fakeDataset();
  const element = Object.assign(Object.create(null), { dataset });
  const context = constructEnhanceContext(element);

  context.diagnosis.set("tiqianSnapshotCount", "3");
  assert.equal(dataset.tiqianSnapshotCount, "3");

  // The timing-golden harness installs its recording proxy on dataset after
  // element construction, so the manager must resolve the record per write
  // instead of capturing it once.
  const recorded: string[] = [];
  const proxied = new Proxy(dataset, {
    set(target, key, value) {
      recorded.push(String(key));
      target[String(key)] = value;
      return true;
    },
  });
  element.dataset = proxied;
  context.diagnosis.set("tiqianLoadMs", "9.9");
  assert.deepEqual(recorded, ["tiqianLoadMs"]);

  // destroy() detaches listeners but the dataset write path keeps working.
  context.destroy();
  context.diagnosis.set("tiqianEnhanceMs", "7.7");
  assert.equal(dataset.tiqianEnhanceMs, "7.7");
  assert.equal(context.diagnosis.eventBroadcastCount, 0);
});
