// Hierarchical enhancement state machine transitions (port of the dissolved
// prose-host-state-machine.test.ts in the core-neutral wave; spec wc-s4a
// item 4, ruling R8). createProseHostStateMachine was renamed to
// createEnhancementStateMachine and the state model moved to
// core/engine/enhance/state.ts. The machine is a pure state holder: these
// tests drive it through the three lifecycle paths the enhanced element
// runs and assert every observable state change, including the structured
// transition log that forms the debug channel.

import assert from "node:assert/strict";
import test from "node:test";

import { createEnhancementStateMachine } from "../core/engine/enhance/state-machine.js";
import { InvalidationReason, invalidationReasons } from "../core/engine/enhance/state.js";
import type { LayoutWorkInputs } from "../core/engine/enhance/state.js";

function workInputs(): LayoutWorkInputs {
  return {
    usesCapturedMeasure: true,
    signaturesCaptured: true,
    geometrySignature: "geometry-1",
    measureSignature: "measure-1",
    typographySignature: "typography-1",
    maximumMeasure: false,
    viewportTypographyEntries: [],
  };
}

test("connected and disconnected lifecycle settles every facet", () => {
  const machine = createEnhancementStateMachine();
  assert.equal(machine.hostState, "disconnected");
  assert.equal(machine.connected, false);
  assert.equal(machine.pipelineStage, "idle");
  assert.equal(machine.transaction.enhanceRequest, 0);
  assert.equal(machine.transaction.layoutOperation, 0);

  // connectedCallback: mount, adopt facets, dispatch the initial enhance.
  machine.connect(false);
  assert.equal(machine.hostState, "connected");
  assert.equal(machine.connected, true);
  machine.runtimeActive = true;
  const request = machine.claimEnhanceRequest();
  assert.equal(request, 1);
  machine.dispatched = true;
  machine.completionGateOpen = true;
  const operation = machine.beginLayoutWork(workInputs());
  assert.equal(operation, 1);
  assert.equal(machine.workInFlight, true);
  assert.equal(machine.pipelineStage, "enhancing");
  assert.equal(machine.transaction.layoutWorkRevision, machine.transaction.geometryRevision);

  // Responsive signals dirty the mask while the job runs.
  machine.invalidate(InvalidationReason.ResponsiveCommit);
  machine.invalidate(InvalidationReason.ContentDrift);
  assert.equal(
    invalidationReasons(machine.invalidationMask).length,
    2,
  );

  // disconnectedCallback: deferred teardown window, then a real settle.
  machine.enterDeferredTeardown();
  assert.equal(machine.hostState, "deferred-teardown");
  assert.equal(machine.deferredTeardown, true);
  assert.equal(machine.connected, false);
  machine.closeDeferredTeardownWindow();
  assert.equal(machine.hostState, "disconnected");
  machine.settleDisconnection();

  // The settle supersedes every token and drops the responsive bits, but a
  // content drift recorded by the host survives until the next connection.
  assert.equal(machine.hostState, "disconnected");
  assert.equal(machine.transaction.enhanceRequest, 2);
  assert.equal(machine.transaction.layoutOperation, 2);
  assert.equal(machine.dispatched, false);
  assert.equal(machine.completionGateOpen, false);
  assert.equal(machine.workInFlight, false);
  assert.equal(machine.isInvalidated(InvalidationReason.ResponsiveCommit), false);
  assert.equal(machine.isInvalidated(InvalidationReason.ContentDrift), true);
  assert.equal(machine.pipelineStage, "idle");

  // The debug channel recorded the whole path with monotone sequence ids.
  const rows = machine.transitions;
  assert.equal(rows[0].transition, "connect");
  assert.equal(rows[rows.length - 1].transition, "settleDisconnection");
  for (let i = 1; i < rows.length; i++) {
    assert.equal(rows[i].sequence, rows[i - 1].sequence + 1);
  }
  assert.equal(rows[rows.length - 1].hostState, "disconnected");
});

test("raw-dom-move reconnection adopts inside the deferred window", () => {
  const machine = createEnhancementStateMachine();
  machine.connect(false);
  machine.runtimeActive = true;
  machine.claimEnhanceRequest();
  machine.dispatched = true;
  machine.beginLayoutWork(workInputs());
  machine.finishLayoutWork();
  machine.completionGateOpen = true;
  const operationBeforeMove = machine.transaction.layoutOperation;
  const enhanceRequestBeforeMove = machine.transaction.enhanceRequest;

  // The reconciler removes and re-inserts the element in one synchronous
  // commit; the element enters the deferred window and reconnects before
  // the microtask closes it.
  machine.enterDeferredTeardown();
  assert.equal(machine.deferredTeardown, true);
  machine.adoptRawDomMoveReconnection();
  assert.equal(machine.hostState, "connected");
  assert.equal(machine.connected, true);

  // The late microtask must not undo an adopted reconnection.
  machine.closeDeferredTeardownWindow();
  assert.equal(machine.hostState, "connected");

  // Adoption keeps the whole pipeline alive: no token bump, no facet reset,
  // no invalidation churn.
  assert.equal(machine.transaction.layoutOperation, operationBeforeMove);
  assert.equal(machine.transaction.enhanceRequest, enhanceRequestBeforeMove);
  assert.equal(machine.runtimeActive, true);
  assert.equal(machine.dispatched, true);
  assert.equal(machine.completionGateOpen, true);
  assert.equal(machine.pipelineStage, "steady");
  assert.equal(machine.invalidationMask, InvalidationReason.None);

  // A width change arriving through the adoption re-arms the commit path.
  machine.invalidate(InvalidationReason.ResponsiveCommit);
  assert.equal(machine.isInvalidated(InvalidationReason.ResponsiveCommit), true);
});

test("disable crossing an in-flight typography pass restarts the lifecycle", () => {
  const machine = createEnhancementStateMachine();
  machine.connect(false);
  machine.runtimeActive = true;
  machine.claimEnhanceRequest();
  machine.dispatched = true;
  machine.completionGateOpen = true;
  machine.beginLayoutWork(workInputs());
  assert.equal(machine.pipelineStage, "enhancing");

  // A typography pass is pending while the enhance job is in flight.
  machine.invalidate(InvalidationReason.TypographyRefreshForced);
  machine.invalidate(InvalidationReason.DeferredTypographyCheck);

  // The disabled attribute restarts the connected lifecycle: supersede the
  // request token, drop every pipeline facet, and stop the typography
  // observation (which clears its two bits).
  machine.bumpEnhanceRequest();
  machine.dispatched = false;
  machine.completionGateOpen = false;
  machine.snapshotAdopted = false;
  machine.runtimeActive = false;
  machine.clearInvalidation(InvalidationReason.TypographyRefreshForced);
  machine.clearInvalidation(InvalidationReason.DeferredTypographyCheck);

  // connectedCallback re-runs with the attribute present.
  machine.connect(true);
  assert.equal(machine.hostState, "disabled");
  assert.equal(machine.connected, true);
  assert.equal(machine.pipelineStage, "enhancing");

  // The superseded job keeps its in-flight slot; its completion dies on the
  // enhance-request guard instead of a flag reset.
  assert.equal(machine.workInFlight, true);
  assert.equal(machine.transaction.enhanceRequest, 2);
  assert.equal(machine.completionGateOpen, false);
  assert.equal(machine.dispatched, false);
  assert.equal(machine.invalidationMask, InvalidationReason.None);

  // Removing the attribute restarts once more into the plain connected
  // state; the disabled state itself never began layout work.
  machine.bumpEnhanceRequest();
  machine.connect(false);
  assert.equal(machine.hostState, "connected");
  assert.equal(machine.transaction.enhanceRequest, 3);
  assert.equal(machine.transaction.abortController, null);
});
