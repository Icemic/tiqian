// Enhancement state model (ADR 0053 wc-s4a): the mount states, pipeline
// stages, invalidation reasons and the layout-work record that the enhanced
// element context and its hierarchical state machine share.
//
// Renamed from prose-host-state.ts in the core-neutral wave:
// ProseHostMountState -> EnhancementMountState,
// ProsePipelineStage -> EnhancementPipelineStage,
// ProsePipelineStageInputs -> EnhancementPipelineStageInputs,
// ProseLayoutWorkKind/Record/Inputs -> LayoutWorkKind/Record/Inputs.
// InvalidationReason keeps its name.
//
// Pure model module: no DOM access, no side effects. The derivation helpers
// are total functions over their inputs, so tests can assert the state
// matrix without constructing a machine.
//
// The two levels:
// - EnhancementMountState tracks the enhanced element's mount lifecycle
//   (connection, the deferred raw-DOM-move teardown window, the disabled
//   opt-out).
// - EnhancementPipelineStage tracks the layout pipeline of one connected
//   root (source, enhancement, relayout, settled output).
// The two are orthogonal: a disconnected root keeps its weak pipeline
// backing, and a connected root can sit in any pipeline stage.
//
// InvalidationReason is a bitmask orthogonal to both levels: each bit marks
// one pending dirty reason the commit paths must consume.

import type { TypographyViewportEntry } from "../../sampler/signatures.js";

export type EnhancementMountState =
  | "connected"
  | "disabled"
  | "deferred-teardown"
  | "disconnected";

export type EnhancementPipelineStage =
  | "idle"
  | "enhancing"
  | "relayouting"
  | "steady";

// The job kind labels mirror the layout job pool's per-root kind strings so
// the pipeline stage derivation and the pool's coordination counters agree.
export type LayoutWorkKind = "Enhance" | "Relayout";

// Pending invalidation reasons. Each bit replaces one former private
// boolean flag on the host element:
//   ResponsiveCommit        responsive commit queued or re-armed
//   ResponsiveRelayout      the pending commit must run a width relayout
//   ContentDrift            host content mutation awaiting reconcile/probe
//   DeferredTypographyCheck typography check deferred while fonts load
//   TypographyRefreshForced next typography check bypasses the signature gate
export interface InvalidationReasonTable {
  readonly None: 0;
  readonly ResponsiveCommit: number;
  readonly ResponsiveRelayout: number;
  readonly ContentDrift: number;
  readonly DeferredTypographyCheck: number;
  readonly TypographyRefreshForced: number;
}

export const InvalidationReason: InvalidationReasonTable = {
  None: 0,
  ResponsiveCommit: 1 << 0,
  ResponsiveRelayout: 1 << 1,
  ContentDrift: 1 << 2,
  DeferredTypographyCheck: 1 << 3,
  TypographyRefreshForced: 1 << 4,
} as const;

export type InvalidationReason =
  InvalidationReasonTable[keyof InvalidationReasonTable];

const INVALIDATION_REASON_BITS: readonly InvalidationReason[] = [
  InvalidationReason.ResponsiveCommit,
  InvalidationReason.ResponsiveRelayout,
  InvalidationReason.ContentDrift,
  InvalidationReason.DeferredTypographyCheck,
  InvalidationReason.TypographyRefreshForced,
];

/** Lists every single reason set in the mask, in stable bit order. */
export function invalidationReasons(mask: number): InvalidationReason[] {
  const reasons: InvalidationReason[] = [];
  for (const bit of INVALIDATION_REASON_BITS) {
    if ((mask & bit) === bit) reasons.push(bit);
  }
  return reasons;
}

// Persistent record of the latest layout work. The record outlives its
// finish: a stale-read finish path still observes the last work's inputs,
// exactly as the former per-element fields did.
export interface LayoutWorkRecord {
  readonly operation: number;
  kind: LayoutWorkKind;
  readonly revision: number;
  usesCapturedMeasure: boolean;
  signaturesCaptured: boolean;
  geometrySignature: string;
  measureSignature: string;
  typographySignature: string;
  maximumMeasure: boolean;
  viewportTypographyEntries: TypographyViewportEntry[];
}

// DOM-derived inputs the context computes and hands to the machine when it
// begins a layout work round.
export interface LayoutWorkInputs {
  usesCapturedMeasure: boolean;
  signaturesCaptured: boolean;
  geometrySignature: string;
  measureSignature: string;
  typographySignature: string;
  maximumMeasure: boolean;
  viewportTypographyEntries: TypographyViewportEntry[];
}

export interface EnhancementPipelineStageInputs {
  workInFlight: boolean;
  workKind: LayoutWorkKind;
  dispatched: boolean;
}

export function deriveEnhancementPipelineStage(
  inputs: EnhancementPipelineStageInputs,
): EnhancementPipelineStage {
  if (inputs.workInFlight) {
    return inputs.workKind === "Relayout" ? "relayouting" : "enhancing";
  }
  return inputs.dispatched ? "steady" : "idle";
}
