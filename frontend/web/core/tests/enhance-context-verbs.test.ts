// EnhancedElementContext public API contract (port of the dissolved
// prose-host-session.test.ts in the core-neutral wave; spec wc-s5 item 3,
// ruling R6). The session object no longer exists: createEnhanceContext
// builds the sole per-root context, and the former session verbs
// (mount/unmount/updateOptions/on/onReady/onRelayoutReady/relayout/
// invalidate) live on the context. The context is driven against a plain
// fake root: the fakeRoot builder returns an untyped object and the
// createEnhanceContext element parameter is the typed seam. The tests cover
// construction, option reflection and its ledger dedup, the disabled
// lifecycle, the completion event subscription surface, and the
// pre-dispatch relayout/invalidate guards.

import assert from "node:assert/strict";
import test from "node:test";

import { createEnhanceContext } from "../core/engine/context/enhance-context.js";
import { InvalidationReason } from "../core/engine/enhance/state.js";
import { initializeGlobalServices } from "../core/services/global-services.js";
initializeGlobalServices();


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

test("createEnhanceContext reflects the initial options onto the root", () => {
  const root = fakeRoot();
  const context = createEnhanceContext(root, {
    emphasisDotGapEm: 0.2,
    strongAsEmphasisMarks: true,
    snapshotRef: "snapshot-1",
  });

  assert.equal(context.element, root);
  assert.equal(context.isConnected, false);
  assert.deepEqual(context.diagnostics, {});

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
  const context = createEnhanceContext(root, { emphasisDotGapEm: 0.2 });
  const writesAfterFirst = root.attributeWrites.length;

  // An identical update produces no attribute write and no reaction.
  context.updateOptions({ emphasisDotGapEm: 0.2 });
  assert.equal(root.attributeWrites.length, writesAfterFirst);

  // A changed value writes exactly once and parses back through the ledger.
  context.updateOptions({ emphasisDotGapEm: 0.3 });
  assert.equal(root.attributeWrites.length, writesAfterFirst + 1);
  assert.equal(context.optionsLedger.emphasisDotGapEm, 0.3);

  // Null clears the attribute and the ledger value.
  context.updateOptions({ emphasisDotGapEm: null });
  assert.equal(root.hasAttribute("emphasis-dot-gap-em"), false);
  assert.equal(context.optionsLedger.emphasisDotGapEm, null);

  // Disabled toggles the boolean attribute in both directions.
  context.updateOptions({ disabled: true });
  assert.equal(root.hasAttribute("disabled"), true);
  assert.equal(context.optionsLedger.disabled, true);
  context.updateOptions({ disabled: false });
  assert.equal(root.hasAttribute("disabled"), false);
  assert.equal(context.optionsLedger.disabled, false);
});

test("a disabled mount stops before font, stylesheet and observer work", async () => {
  const root = fakeRoot({ isConnected: true });
  const context = createEnhanceContext(root, { disabled: true });

  await context.mount();

  assert.equal(context.optionsLedger.disabled, true);
  assert.equal(context.isConnected, true);
  // The mount reaches the disabled opt-out and stops there.
  assert.equal(context.stateMachine.hostState, "disabled");
  // No completion listeners reach the root on the disabled path.
  assert.equal(root.listeners.has("tiqian:ready"), false);
  assert.equal(root.listeners.has("tiqian:relayout-ready"), false);
  // The telemetry channel stays clean without a font wait.
  assert.equal(root.dataset.tiqianFontWait, undefined);

  context.unmount();
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(root.dataset.tiqianFontWait, undefined);
});

test("completion subscriptions register, unsubscribe and stay silent early", () => {
  const root = fakeRoot();
  const context = createEnhanceContext(root);

  const seen: string[] = [];
  const offReady = context.onReady(() => seen.push("ready"));
  const offRelayout = context.onRelayoutReady(() => seen.push("relayout-ready"));
  const offGeneric = context.on("ready", () => seen.push("ready"));

  assert.equal(typeof offReady, "function");
  assert.equal(typeof offRelayout, "function");
  assert.equal(typeof offGeneric, "function");

  // Before any completion is reported through the funnel: nothing fires.
  assert.deepEqual(seen, []);

  // The funnel's emit stands in for the mount-driven completion report and
  // reaches every live subscriber with the reported diagnostics.
  context.eventChannel.emit("ready", { enhanceMs: 12 });
  assert.deepEqual(seen, ["ready", "ready"]);
  assert.deepEqual(context.diagnostics, { enhanceMs: 12 });

  offReady();
  offRelayout();
  offGeneric();
  // A second unsubscribe is a harmless no-op.
  offReady();
  context.eventChannel.emit("ready", {});
  context.eventChannel.emit("relayout-ready", {});
  assert.deepEqual(seen, ["ready", "ready"]);
});

test("relayout and invalidate stay inert before the first dispatch", () => {
  const root = fakeRoot({ isConnected: true });
  const context = createEnhanceContext(root);

  // Neither entry may schedule work ahead of a dispatched lifecycle; the
  // unmounted root exposes no frame task surface, so the guard is
  // observable through the untouched root.
  context.relayout();
  context.invalidate(InvalidationReason.ResponsiveCommit);
  assert.equal(root.listeners.size, 0);
  assert.deepEqual(context.diagnostics, {});
  // The invalidate bit still lands on the state machine; relayout leaves
  // no bit at all because its guard runs first.
  assert.equal(
    context.stateMachine.isInvalidated(InvalidationReason.ResponsiveCommit),
    true,
  );
  assert.equal(
    context.stateMachine.invalidationMask,
    InvalidationReason.ResponsiveCommit,
  );
});
