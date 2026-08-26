// Snapshot font session state machines (ADR 0053 batch 4; decomposition report
// section 8). Both host entries share one session entry shape and one
// release ordering: the custom element holds its session in a per-root field
// (live revalidation and render fonts), the module API holds sessions in a
// per-root WeakMap registry superseded by generation. Currency predicates
// stay with the callers; entry construction and release live here.

import type {
  BrowserFontSessionHandle,
  BrowserFontSessionReleaser,
  BrowserFontSessionRevalidator,
  BrowserRenderFontPreparer,
} from "../measurement/browser-fonts.js";

export type PreparedRenderFontStyleInstaller = (root: Element, renderFontFamilies: readonly string[]) => boolean;
export type PreparedRenderFontStyleReleaser = (root: Element) => boolean;

// Loader operations carry the browser font session implementation; their
// precise signatures arrive with the browser-fonts conversion wave.
type BrowserFontSessionOperation =
  | BrowserFontSessionRevalidator
  | BrowserRenderFontPreparer
  | BrowserFontSessionReleaser
  | PreparedRenderFontStyleInstaller
  | PreparedRenderFontStyleReleaser;

export interface SnapshotFontLoader {
  revalidateBrowserFontSession: BrowserFontSessionRevalidator;
  prepareBrowserRenderFonts: BrowserRenderFontPreparer;
  releaseBrowserFontSession: BrowserFontSessionReleaser;
  installPreparedRenderFontStyle: PreparedRenderFontStyleInstaller;
  releasePreparedRenderFontStyle: PreparedRenderFontStyleReleaser;
}

export interface SnapshotFontSessionEntry {
  reference: string | null;
  handle: BrowserFontSessionHandle;
  revalidate: BrowserFontSessionRevalidator;
  prepareRenderFont: BrowserRenderFontPreparer;
  release: BrowserFontSessionReleaser;
  installRenderFont: PreparedRenderFontStyleInstaller;
  releaseRenderFont: PreparedRenderFontStyleReleaser;
}

const SNAPSHOT_LAYOUT_OVERRIDE_KEYS = [
  "fontSize",
  "lineHeight",
  "cjkFontFamily",
  "latinFontFamily",
  "monospaceFontFamily",
  "cjkSerifFontFamily",
  "latinSerifFontFamily",
] as const;

export function createSnapshotFontSessionEntry(reference: string | null, handle: BrowserFontSessionHandle, loader: SnapshotFontLoader): SnapshotFontSessionEntry {
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

export function releaseSnapshotFontSession(entry: SnapshotFontSessionEntry, root: HTMLElement): boolean {
  entry.releaseRenderFont(root);
  return entry.release(entry.handle);
}

export function hasSnapshotLayoutOverride(options: Record<string, unknown> | null | undefined): boolean {
  if (!options || typeof options !== "object") return false;
  if (SNAPSHOT_LAYOUT_OVERRIDE_KEYS.some((key) => options[key] != null)) return true;
  return options.firstLineIndentIc != null && Number(options.firstLineIndentIc) !== 0;
}
