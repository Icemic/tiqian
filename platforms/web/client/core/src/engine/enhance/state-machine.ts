// EnhancementStateMachine — the hierarchical state machine of one enhanced
// element (ADR 0053 wc-s4a). It owns the outer mount lifecycle
// (EnhancementMountState), the inner pipeline facets and work record that
// derive EnhancementPipelineStage, the orthogonal InvalidationReason mask,
// and the transaction context holding the revision counters.
//
// Renamed from prose-host-state-machine.ts in the core-neutral wave:
// ProseHostStateMachine -> EnhancementStateMachine,
// createProseHostStateMachine -> createEnhancementStateMachine,
// ProseHostTransaction -> EnhancementTransaction,
// ProseHostTransitionRow -> EnhancementTransitionRow.
//
// The machine is the single source of state for its enhanced element: it
// reads no DOM and dispatches no events; the context supplies DOM-derived
// inputs and performs every side effect around the machine calls.
//
// Explainability: every recorded transition appends one row to a bounded
// in-memory transition log exposed as `transitions`. The log is the
// structured debug channel; nothing is written to element attributes.

import {
  InvalidationReason,
  deriveEnhancementPipelineStage,
} from "./state.js";
import type {
  EnhancementMountState,
  LayoutWorkInputs,
  LayoutWorkRecord,
  EnhancementPipelineStage,
} from "./state.js";

// Transaction context: the token and revision counters one connection
// generation accumulates. The lifecycle wires the AbortController into the
// engine abort surface through this slot.
export interface EnhancementTransaction {
  layoutOperation: number;
  layoutWorkRevision: number;
  enhanceRequest: number;
  geometryRevision: number;
  abortController: AbortController | null;
}

export interface EnhancementTransitionRow {
  readonly sequence: number;
  readonly transition: string;
  readonly hostState: EnhancementMountState;
  readonly pipelineStage: EnhancementPipelineStage;
  readonly invalidation: number;
}

const TRANSITION_LOG_CAPACITY = 256;

function freshLayoutWorkRecord(
  operation: number,
  revision: number,
  inputs: LayoutWorkInputs,
): LayoutWorkRecord {
  return {
    operation,
    kind: "Enhance",
    revision,
    usesCapturedMeasure: inputs.usesCapturedMeasure,
    signaturesCaptured: inputs.signaturesCaptured,
    geometrySignature: inputs.geometrySignature,
    measureSignature: inputs.measureSignature,
    typographySignature: inputs.typographySignature,
    maximumMeasure: inputs.maximumMeasure,
    viewportTypographyEntries: inputs.viewportTypographyEntries,
  };
}

export class EnhancementStateMachine {
  #hostState: EnhancementMountState = "disconnected";
  #runtimeActive = false;
  #snapshotAdopted = false;
  #dispatched = false;
  #completionGateOpen = false;
  #workerAttached = false;
  #inViewport = true;
  #workInFlight = false;
  #work: LayoutWorkRecord = freshLayoutWorkRecord(0, 0, {
    usesCapturedMeasure: false,
    signaturesCaptured: false,
    geometrySignature: "",
    measureSignature: "",
    typographySignature: "",
    maximumMeasure: false,
    viewportTypographyEntries: [],
  });
  #invalidation: number = InvalidationReason.None;
  #log: EnhancementTransitionRow[] = [];
  #sequence = 0;

  readonly transaction: EnhancementTransaction = {
    layoutOperation: 0,
    layoutWorkRevision: 0,
    enhanceRequest: 0,
    geometryRevision: 0,
    abortController: null,
  };

  // ---------------------------------------------------------------------
  // mount lifecycle
  // ---------------------------------------------------------------------

  get hostState(): EnhancementMountState {
    return this.#hostState;
  }

  /** True while the enhanced element lives in the document lifecycle. */
  get connected(): boolean {
    return this.#hostState === "connected" || this.#hostState === "disabled";
  }

  /** True inside the deferred raw-DOM-move teardown window. */
  get deferredTeardown(): boolean {
    return this.#hostState === "deferred-teardown";
  }

  connect(disabled: boolean): void {
    this.#hostState = disabled ? "disabled" : "connected";
    this.#record("connect");
  }

  enterDeferredTeardown(): void {
    this.#hostState = "deferred-teardown";
    this.#record("enterDeferredTeardown");
  }

  /** Closes the deferred window; a reconnection already adopted wins. */
  closeDeferredTeardownWindow(): void {
    if (this.#hostState !== "deferred-teardown") return;
    this.#hostState = "disconnected";
    this.#record("closeDeferredTeardownWindow");
  }

  adoptRawDomMoveReconnection(): void {
    this.#hostState = "connected";
    this.#record("adoptRawDomMoveReconnection");
  }

  settleDisconnection(): void {
    this.#hostState = "disconnected";
    this.transaction.enhanceRequest += 1;
    this.transaction.layoutOperation += 1;
    this.#completionGateOpen = false;
    this.#dispatched = false;
    this.#workInFlight = false;
    this.#work.viewportTypographyEntries = [];
    this.clearInvalidation(InvalidationReason.ResponsiveCommit);
    this.clearInvalidation(InvalidationReason.ResponsiveRelayout);
    this.#record("settleDisconnection");
  }

  // ---------------------------------------------------------------------
  // pipeline facets
  // ---------------------------------------------------------------------

  get runtimeActive(): boolean {
    return this.#runtimeActive;
  }

  set runtimeActive(value: boolean) {
    if (this.#runtimeActive === value) return;
    this.#runtimeActive = value;
    this.#record("setRuntimeActive");
  }

  get snapshotAdopted(): boolean {
    return this.#snapshotAdopted;
  }

  set snapshotAdopted(value: boolean) {
    if (this.#snapshotAdopted === value) return;
    this.#snapshotAdopted = value;
    this.#record("setSnapshotAdopted");
  }

  get dispatched(): boolean {
    return this.#dispatched;
  }

  set dispatched(value: boolean) {
    if (this.#dispatched === value) return;
    this.#dispatched = value;
    this.#record("setDispatched");
  }

  get completionGateOpen(): boolean {
    return this.#completionGateOpen;
  }

  set completionGateOpen(value: boolean) {
    if (this.#completionGateOpen === value) return;
    this.#completionGateOpen = value;
    this.#record("setCompletionGateOpen");
  }

  get workerAttached(): boolean {
    return this.#workerAttached;
  }

  set workerAttached(value: boolean) {
    if (this.#workerAttached === value) return;
    this.#workerAttached = value;
    this.#record("setWorkerAttached");
  }

  get inViewport(): boolean {
    return this.#inViewport;
  }

  set inViewport(value: boolean) {
    if (this.#inViewport === value) return;
    this.#inViewport = value;
    this.#record("setInViewport");
  }

  get pipelineStage(): EnhancementPipelineStage {
    return deriveEnhancementPipelineStage({
      workInFlight: this.#workInFlight,
      workKind: this.#work.kind,
      dispatched: this.#dispatched,
    });
  }

  // ---------------------------------------------------------------------
  // layout work
  // ---------------------------------------------------------------------

  get workInFlight(): boolean {
    return this.#workInFlight;
  }

  /** The persistent record of the latest layout work round. */
  get work(): LayoutWorkRecord {
    return this.#work;
  }

  beginLayoutWork(inputs: LayoutWorkInputs): number {
    this.transaction.layoutOperation += 1;
    this.transaction.layoutWorkRevision = this.transaction.geometryRevision;
    this.#workInFlight = true;
    this.#work = freshLayoutWorkRecord(
      this.transaction.layoutOperation,
      this.transaction.layoutWorkRevision,
      inputs,
    );
    this.clearInvalidation(InvalidationReason.ResponsiveCommit);
    this.clearInvalidation(InvalidationReason.ResponsiveRelayout);
    this.#completionGateOpen = false;
    this.#record("beginLayoutWork");
    return this.transaction.layoutOperation;
  }

  finishLayoutWork(): void {
    this.#workInFlight = false;
    this.#work.signaturesCaptured = false;
    this.#work.viewportTypographyEntries = [];
    this.#record("finishLayoutWork");
  }

  /** Cancels the in-flight round and supersedes its operation. */
  abortLayoutWork(): void {
    this.transaction.layoutOperation += 1;
    this.#completionGateOpen = false;
    this.#workInFlight = false;
    this.#work.viewportTypographyEntries = [];
    this.#record("abortLayoutWork");
  }

  /** Drops the in-flight round without superseding its operation. */
  failLayoutWork(): void {
    this.#completionGateOpen = false;
    this.#workInFlight = false;
    this.#work.viewportTypographyEntries = [];
    this.#record("failLayoutWork");
  }

  markWorkAsRelayout(): void {
    this.#work.kind = "Relayout";
    this.#record("markWorkAsRelayout");
  }

  // SnapshotValidationConsumesObservedGeometry: adoption rechecks the live
  // inputs immediately before its atomic commit, so the geometry observed
  // while validating is already represented by the adopted result.
  consumeObservedGeometry(): void {
    this.transaction.layoutWorkRevision = this.transaction.geometryRevision;
    this.clearInvalidation(InvalidationReason.ResponsiveCommit);
    this.clearInvalidation(InvalidationReason.ResponsiveRelayout);
    this.#record("consumeObservedGeometry");
  }

  bumpGeometryRevision(): void {
    this.transaction.geometryRevision += 1;
    this.#record("bumpGeometryRevision");
  }

  claimEnhanceRequest(): number {
    this.transaction.enhanceRequest += 1;
    this.#record("claimEnhanceRequest");
    return this.transaction.enhanceRequest;
  }

  bumpEnhanceRequest(): void {
    this.transaction.enhanceRequest += 1;
    this.#record("bumpEnhanceRequest");
  }

  // ---------------------------------------------------------------------
  // invalidation mask
  // ---------------------------------------------------------------------

  get invalidationMask(): number {
    return this.#invalidation;
  }

  invalidate(reason: number): void {
    if ((this.#invalidation & reason) === reason) return;
    this.#invalidation |= reason;
    this.#record("invalidate");
  }

  clearInvalidation(reason: number): void {
    if ((this.#invalidation & reason) === InvalidationReason.None) return;
    this.#invalidation &= ~reason;
    this.#record("clearInvalidation");
  }

  isInvalidated(reason: number): boolean {
    return (this.#invalidation & reason) === reason;
  }

  // ---------------------------------------------------------------------
  // debug channel
  // ---------------------------------------------------------------------

  get transitions(): readonly EnhancementTransitionRow[] {
    return this.#log;
  }

  #record(transition: string): void {
    this.#sequence += 1;
    this.#log.push({
      sequence: this.#sequence,
      transition,
      hostState: this.#hostState,
      pipelineStage: this.pipelineStage,
      invalidation: this.#invalidation,
    });
    if (this.#log.length > TRANSITION_LOG_CAPACITY) {
      this.#log.splice(0, this.#log.length - TRANSITION_LOG_CAPACITY);
    }
  }
}

export function createEnhancementStateMachine(): EnhancementStateMachine {
  return new EnhancementStateMachine();
}
