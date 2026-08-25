// Access to the precomputed snapshot module: imported statically at module
// scope; accessors delegate directly to precomputed snapshot methods.
// Moved from lazy-capabilities.js in ADR 0053 batch 6.

import * as precomputed from "./precomputed.js";
import type { SnapshotAdoptAnchors, SnapshotAdoptOutcome } from "./precomputed.js";

type SnapshotIsCurrent = () => boolean;
export type PrecomputedSnapshotModule = typeof precomputed;

export function loadPrecomputedSnapshots(): Promise<PrecomputedSnapshotModule> {
  return Promise.resolve(precomputed);
}

export function isLoadedSnapshotAdopted(root: HTMLElement): boolean {
  return precomputed.isPrecomputedSnapshotAdopted(root);
}

export async function loadedAdoptedSnapshotLiveIssue(
  root: HTMLElement,
  isCurrent: SnapshotIsCurrent = () => true,
): Promise<string | null> {
  return precomputed.adoptedPrecomputedSnapshotLiveIssue(root, isCurrent);
}

export function loadedSnapshotMaximumMeasureMatches(root: HTMLElement): boolean {
  return precomputed.precomputedSnapshotMaximumMeasureMatches(root);
}

export function restoreLoadedSnapshot(root: HTMLElement): boolean {
  return precomputed.restorePrecomputedSnapshot(root);
}

export function detachLoadedSnapshot(root: HTMLElement): boolean {
  return precomputed.detachPrecomputedSnapshot(root);
}

export async function restoreAdoptedSnapshot(root: HTMLElement): Promise<boolean> {
  return precomputed.restorePrecomputedSnapshot(root);
}

export async function tryAdoptRequestedSnapshot(
  root: HTMLElement,
  isCurrent: SnapshotIsCurrent = () => true,
  anchors: SnapshotAdoptAnchors | null = null,
): Promise<SnapshotAdoptOutcome> {
  if (!root?.getAttribute?.("snapshot-ref")) {
    return { adopted: false, reason: "not-requested" };
  }
  return precomputed.tryAdoptPrecomputedSnapshot(root, isCurrent, anchors);
}