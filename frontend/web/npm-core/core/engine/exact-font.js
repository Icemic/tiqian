// Exact font session state machines (ADR 0053 batch 4; decomposition report
// section 8). Both host entries share one session entry shape and one
// release ordering: the custom element holds its session in a per-root field
// (live revalidation and render fonts), the module API holds sessions in a
// per-root WeakMap registry superseded by generation. Currency predicates
// stay with the callers; entry construction and release live here.

const SNAPSHOT_LAYOUT_OVERRIDE_KEYS = [
  "fontSize",
  "lineHeight",
  "cjkFontFamily",
  "latinFontFamily",
  "monospaceFontFamily",
  "cjkSerifFontFamily",
  "latinSerifFontFamily",
];

export function createExactFontSessionEntry(reference, handle, loader) {
  return {
    reference,
    handle,
    revalidate: loader.revalidateBrowserFontSession,
    prepareRenderFont: loader.prepareBrowserRenderFonts,
    release: loader.releaseBrowserFontSession,
    installRenderFont: loader.installPreparedRenderFontStyle,
    releaseRenderFont: loader.releasePreparedRenderFontStyle,
  };
}

export function releaseExactFontSession(entry, root) {
  entry.releaseRenderFont(root);
  return entry.release(entry.handle);
}

export function hasSnapshotLayoutOverride(options) {
  if (!options || typeof options !== "object") return false;
  if (SNAPSHOT_LAYOUT_OVERRIDE_KEYS.some((key) => options[key] != null)) return true;
  return options.firstLineIndentIc != null && Number(options.firstLineIndentIc) !== 0;
}
