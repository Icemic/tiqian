// Lazy access to the precomputed snapshot module: one dynamic import is
// memoized and every accessor delegates to it, failing closed before the
// module loads. Moved from lazy-capabilities.js in ADR 0053 batch 6.

import type { SnapshotAdoptAnchors, SnapshotAdoptOutcome } from "./precomputed.js";

type SnapshotIsCurrent = () => boolean;
type PrecomputedSnapshotModule = typeof import("./precomputed.js");

let precomputedModule!: PrecomputedSnapshotModule | null;
let precomputedPromise!: Promise<PrecomputedSnapshotModule> | null;

export function loadPrecomputedSnapshots(): Promise<PrecomputedSnapshotModule> {
  precomputedPromise ??= import("./precomputed.js").then((module) => {
    precomputedModule = module as PrecomputedSnapshotModule;
    return module as PrecomputedSnapshotModule;
  });
  return precomputedPromise;
}

export function isLoadedSnapshotAdopted(root: HTMLElement): boolean {
  return precomputedModule?.isPrecomputedSnapshotAdopted(root) ?? false;
}

export async function loadedAdoptedSnapshotLiveIssue(
  root: HTMLElement,
  isCurrent: SnapshotIsCurrent = () => true,
): Promise<string | null> {
  return precomputedModule
    ? precomputedModule.adoptedPrecomputedSnapshotLiveIssue(root, isCurrent)
    : "SnapshotModuleUnavailable";
}

export function loadedSnapshotMaximumMeasureMatches(root: HTMLElement): boolean {
  return precomputedModule?.precomputedSnapshotMaximumMeasureMatches(root) ?? false;
}

export function restoreLoadedSnapshot(root: HTMLElement): boolean {
  return precomputedModule?.restorePrecomputedSnapshot(root) ?? false;
}

export function detachLoadedSnapshot(root: HTMLElement): boolean {
  return precomputedModule?.detachPrecomputedSnapshot(root) ?? false;
}

export async function restoreAdoptedSnapshot(root: HTMLElement): Promise<boolean> {
  const snapshots = precomputedModule ?? (
    root?.dataset?.tiqianSnapshot ? await loadPrecomputedSnapshots() : null
  );
  return snapshots?.restorePrecomputedSnapshot(root) ?? false;
}

export async function tryAdoptRequestedSnapshot(
  root: HTMLElement,
  isCurrent: SnapshotIsCurrent = () => true,
  anchors: SnapshotAdoptAnchors | null = null,
): Promise<SnapshotAdoptOutcome> {
  if (!root?.getAttribute?.("snapshot-ref")) {
    return { adopted: false, reason: "not-requested" };
  }
  const snapshots = await loadPrecomputedSnapshots();
  return snapshots.tryAdoptPrecomputedSnapshot(root, isCurrent, anchors);
}