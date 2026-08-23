// Lazy access to the precomputed snapshot module: one dynamic import is
// memoized and every accessor delegates to it, failing closed before the
// module loads. Moved from lazy-capabilities.js in ADR 0053 batch 6.

let precomputedModule;
let precomputedPromise;

export function loadPrecomputedSnapshots() {
  precomputedPromise ??= import("./precomputed.js").then((module) => {
    precomputedModule = module;
    return module;
  });
  return precomputedPromise;
}

export function loadedPrecomputedSnapshots() {
  return precomputedModule ?? null;
}

export function isLoadedSnapshotAdopted(root) {
  return precomputedModule?.isPrecomputedSnapshotAdopted(root) ?? false;
}

export async function loadedAdoptedSnapshotLiveIssue(root, isCurrent = () => true) {
  return precomputedModule
    ? precomputedModule.adoptedPrecomputedSnapshotLiveIssue(root, isCurrent)
    : "SnapshotModuleUnavailable";
}

export function loadedSnapshotMaximumMeasureMatches(root) {
  return precomputedModule?.precomputedSnapshotMaximumMeasureMatches(root) ?? false;
}

export function restoreLoadedSnapshot(root) {
  return precomputedModule?.restorePrecomputedSnapshot(root) ?? false;
}

export function detachLoadedSnapshot(root) {
  return precomputedModule?.detachPrecomputedSnapshot(root) ?? false;
}

export async function restoreAdoptedSnapshot(root) {
  const snapshots = precomputedModule ?? (
    root?.dataset?.tiqianSnapshot ? await loadPrecomputedSnapshots() : null
  );
  return snapshots?.restorePrecomputedSnapshot(root) ?? false;
}

export async function tryAdoptRequestedSnapshot(root, isCurrent = () => true) {
  if (!root?.getAttribute?.("snapshot-ref")) {
    return { adopted: false, reason: "not-requested" };
  }
  const snapshots = await loadPrecomputedSnapshots();
  return snapshots.tryAdoptPrecomputedSnapshot(root, isCurrent);
}
