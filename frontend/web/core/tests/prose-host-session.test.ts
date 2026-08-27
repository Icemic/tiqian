// Prose host session public API contract (spec wc-s5 item 3, ruling R6,
// ruling R8 TS-ifies new tests). The session is driven against a plain fake
// root: the fakeOf builder returns an untyped object and the
// createProseHostSession root parameter is the typed seam. The tests cover
// construction, option reflection and its ledger dedup, the disabled
// lifecycle, and the completion event subscription surface.

import assert from "node:assert/strict";
import test from "node:test";

import {
  createProseHostSession,
  ProseHostSession,
} from "../core/engine/prose-host-session.js";
import type { ProseHostEvent } from "../core/engine/prose-host-session.js";

function fakeOf(members: Record<string, unknown>) {
  return Object.assign(Object.create(null), members);
}

function fakeRoot(extras: Record<string, unknown> = {}) {
  const attributes = new Map<string, string>();
  const listeners = new Map<string, EventListener>();
  const attributeWrites: string[] = [];
  return Object.assign(Object.create(null), {
    nodeType: 1,
    isConnected: false,
    attributes,
    listeners,
    attributeWrites,
    dataset: {},
    ownerDocument: null,
    textContent: "",
    getAttribute: (name: string) => attributes.get(name) ?? null,
    setAttribute: (name: string, value: string) => {
      attributes.set(name, String(value));
      attributeWrites.push(name);
    },
    removeAttribute: (name: string) => {
      attributes.delete(name);
      attributeWrites.push(name);
    },
    hasAttribute: (name: string) => attributes.has(name),
    addEventListener: (name: string, listener: EventListener) => {
      listeners.set(name, listener);
    },
    removeEventListener: (name: string, listener: EventListener) => {
      if (listeners.get(name) === listener) listeners.delete(name);
    },
    dispatchEvent: () => true,
    querySelectorAll: () => [],
    getBoundingClientRect: () => ({ width: 360, height: 24 }),
  }, extras);
}

test("createProseHostSession reflects the initial options onto the root", () => {
  const root = fakeRoot();
  const session = createProseHostSession(root, {
    emphasisDotGapEm: 0.2,
    strongAsEmphasisMarks: true,
    snapshotRef: "snapshot-1",
  });

  assert.ok(session instanceof ProseHostSession);
  assert.equal(session.root, root);
  assert.equal(session.isConnected, false);
  assert.deepEqual(session.diagnostics, {});

  // A disconnected root takes no attribute reaction, so the option apply is
  // the only write path: each reflected attribute lands once on the root.
  assert.deepEqual(root.attributeWrites.sort(), [
    "emphasis-dot-gap-em",
    "snapshot-ref",
    "strong-as-emphasis-marks",
  ]);
  assert.equal(root.getAttribute("emphasis-dot-gap-em"), "0.2");
  assert.equal(root.hasAttribute("strong-as-emphasis-marks"), true);
  assert.equal(root.getAttribute("snapshot-ref"), "snapshot-1");
  assert.equal(root.hasAttribute("disabled"), false);
});

test("updateOptions dedups through the applied ledger and parses back", () => {
  const root = fakeRoot();
  const session = createProseHostSession(root, { emphasisDotGapEm: 0.2 });
  const writesAfterFirst = root.attributeWrites.length;

  // An identical update produces no attribute write and no reaction.
  session.updateOptions({ emphasisDotGapEm: 0.2 });
  assert.equal(root.attributeWrites.length, writesAfterFirst);

  // A changed value writes exactly once and parses back through the getter.
  session.updateOptions({ emphasisDotGapEm: 0.3 });
  assert.equal(root.attributeWrites.length, writesAfterFirst + 1);
  assert.equal(session.emphasisDotGapEm, 0.3);

  // Null clears the attribute and the getter.
  session.updateOptions({ emphasisDotGapEm: null });
  assert.equal(root.hasAttribute("emphasis-dot-gap-em"), false);
  assert.equal(session.emphasisDotGapEm, null);

  // Disabled toggles the boolean attribute in both directions.
  session.updateOptions({ disabled: true });
  assert.equal(root.hasAttribute("disabled"), true);
  assert.equal(session.disabled, true);
  session.updateOptions({ disabled: false });
  assert.equal(root.hasAttribute("disabled"), false);
  assert.equal(session.disabled, false);
});

test("a disabled mount stops before font, stylesheet and observer work", async () => {
  const root = fakeRoot({ isConnected: true });
  const session = createProseHostSession(root, { disabled: true });

  session.mount();
  await new Promise((resolve) => setImmediate(resolve));

  assert.equal(session.disabled, true);
  assert.equal(session.isConnected, true);
  // No completion listeners reach the root on the disabled path.
  assert.equal(root.listeners.has("tiqian:ready"), false);
  assert.equal(root.listeners.has("tiqian:relayout-ready"), false);
  // The telemetry channel stays clean without a font wait.
  assert.equal(root.dataset.tiqianFontWait, undefined);

  session.unmount();
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(root.dataset.tiqianFontWait, undefined);
});

test("completion subscriptions register, unsubscribe and stay silent early", () => {
  const root = fakeRoot();
  const session = createProseHostSession(root);

  const seen: ProseHostEvent[] = [];
  const offReady = session.onReady(() => seen.push("ready"));
  const offRelayout = session.onRelayoutReady(() => seen.push("relayout-ready"));
  const offGeneric = session.on("ready", () => seen.push("ready"));

  assert.equal(typeof offReady, "function");
  assert.equal(typeof offRelayout, "function");
  assert.equal(typeof offGeneric, "function");

  // Before any mount there is no completion funnel: nothing fires.
  assert.deepEqual(seen, []);

  offReady();
  offRelayout();
  offGeneric();
  // A second unsubscribe is a harmless no-op.
  offReady();
  assert.deepEqual(seen, []);
});

test("relayout and invalidate stay inert before the first dispatch", () => {
  const root = fakeRoot({ isConnected: true });
  const session = createProseHostSession(root);

  // Neither entry may schedule work ahead of a dispatched lifecycle; the
  // disabled-free unmounted root exposes no frame task surface, so the guard
  // is observable through the untouched root.
  session.relayout();
  session.invalidate(1);
  assert.equal(root.listeners.size, 0);
  assert.deepEqual(session.diagnostics, {});
});
