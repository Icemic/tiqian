// Session-level font session helpers (wc-s5 scope 9). Extracted from the
// dissolved npm package api.ts entry so the core engine can reuse these
// operations without depending on the npm facade.

import { restoreAdoptedSnapshot } from "../sampler/snapshot/loaded-snapshots.js";
import { ensureTiqianStyles } from "./loaders/styles.js";
import { prepareCjkDashShapingIfNeeded } from "./loaders/cjk-dash.js";
import { ensurePreparedDomBridge, loadSnapshotFontFallback } from "./loaders/font-loader.js";
import { createSnapshotFontSessionEntry, hasSnapshotLayoutOverride, releaseSnapshotFontSession } from "./snapshot-font.js";
import type { CjkDashShapingOutcome } from "./loaders/cjk-dash.js";
import type { BrowserFontSessionHandle } from "../measurement/browser-fonts.js";
import type { SnapshotFontSessionEntry } from "./snapshot-font.js";
import type { EnhancedElementContext } from "./context/enhance-context.js";
import { getOrCreateEnhanceContext } from "./context/enhance-context.js";

interface TiqianSnapshotFontMissCandidate {
  code?: string;
  detail?: string;
}

interface TiqianWebOptions {
  trace?: { maxEntries?: number };
  cjkFontFamily?: string;
  latinFontFamily?: string;
  monospaceFontFamily?: string;
  cjkSerifFontFamily?: string;
  latinSerifFontFamily?: string;
  fontSize?: number;
  lineHeight?: number;
  firstLineIndentIc?: number;
  emphasisDotGapEm?: number;
  strongAsEmphasisMarks?: boolean;
  paragraphSelector?: string;
}

interface TiqianWebSnapshotFontSessionWire {
  status: "conforming";
  sessionId: string;
  detail: "SnapshotFontBytes";
}

type TiqianPreparedWebOptions = TiqianWebOptions & {
  cjkDashCapability: CjkDashShapingOutcome;
  snapshotFontSession?: TiqianWebSnapshotFontSessionWire;
};

interface TiqianCjkDashPrepareOptions extends TiqianWebOptions {
  snapshotFontSession?: unknown;
}

export function snapshotFontMissDatasetValue(error: TiqianSnapshotFontMissCandidate): string {
  if (error?.code === "SnapshotFontContractMismatch" && typeof error?.detail === "string") {
    const pipeIndex = error.detail.indexOf("|");
    if (pipeIndex !== -1) {
      const detailSuffix = error.detail.slice(pipeIndex + 1);
      if (detailSuffix === "EmptyCandidateSet" || detailSuffix.startsWith("FieldMismatch|")) {
        return `${error.code}|${detailSuffix}`;
      }
    }
  }
  return error?.code ?? "SnapshotFontSessionUnavailable";
}

export const ANY_FONT_SESSION = Symbol("tiqian.anyFontSession");

export function releaseContextFontSession(
  context: EnhancedElementContext,
  root: HTMLElement,
  expectedHandle: BrowserFontSessionHandle | null | typeof ANY_FONT_SESSION = ANY_FONT_SESSION,
): boolean {
  const entry = context.snapshotFontSession.entry;
  if (!entry || (expectedHandle !== ANY_FONT_SESSION && entry.handle !== expectedHandle)) return false;
  context.snapshotFontSession.entry = null;
  return releaseSnapshotFontSession(entry, root);
}

export async function prepareRootFontSession(
  root: HTMLElement,
  generation: number,
  options: TiqianWebOptions,
  context: EnhancedElementContext,
): Promise<BrowserFontSessionHandle | null> {
  if (!root?.getAttribute?.("snapshot-ref")) {
    if (context.generation === generation) {
      const entry = context.snapshotFontSession.entry;
      if (entry) {
        releaseSnapshotFontSession(entry, root);
        context.snapshotFontSession.entry = null;
      }
    }
    return null;
  }
  if (hasSnapshotLayoutOverride(options as Record<string, unknown>)) {
    if (context.generation === generation) {
      const entry = context.snapshotFontSession.entry;
      if (entry) {
        releaseSnapshotFontSession(entry, root);
        context.snapshotFontSession.entry = null;
      }
    }
    context.diagnosis.set("tiqianSnapshotFontMiss", "SnapshotLayoutOptionsOverride");
    return null;
  }
  const reference = root.getAttribute("snapshot-ref");
  const existing = context.snapshotFontSession.entry;
  try {
    const loader = await loadSnapshotFontFallback();
    const handle = await loader.prepareBrowserFontSession(root);
    if (context.generation !== generation) {
      loader.releaseBrowserFontSession(handle);
      return null;
    }
    const next = createSnapshotFontSessionEntry(reference, handle, loader);
    context.snapshotFontSession.entry = next;
    if (existing && existing !== next) existing.release(existing.handle);
    context.diagnosis.clear("tiqianSnapshotFontMiss");
    return handle;
  } catch (error) {
    if (context.generation === generation && context.snapshotFontSession.entry === existing) {
      const entry = context.snapshotFontSession.entry;
      if (entry) {
        releaseSnapshotFontSession(entry, root);
        context.snapshotFontSession.entry = null;
      }
    }
    context.diagnosis.set("tiqianSnapshotFontMiss", snapshotFontMissDatasetValue(error as TiqianSnapshotFontMissCandidate));
    console.warn("Tiqian Web snapshot font session unavailable; using browser metrics", error);
    return null;
  }
}

export async function withTiqianWeb<T>(
  root: HTMLElement,
  options: TiqianWebOptions,
  action: (context: EnhancedElementContext, prepared: TiqianPreparedWebOptions) => T,
): Promise<HTMLElement | T> {
  const context = getOrCreateEnhanceContext(root);
  await restoreAdoptedSnapshot(root);
  const generation = context.update();
  let fontSession: BrowserFontSessionHandle | null = null;
  let cjkDashCapability: CjkDashShapingOutcome;
  try {
    await Promise.all([
      ensureTiqianStyles(root.ownerDocument ?? globalThis.document, root),
      ensurePreparedDomBridge(),
    ]);
    cjkDashCapability = await prepareCjkDashShapingIfNeeded(root, options as TiqianCjkDashPrepareOptions);
    fontSession = await prepareRootFontSession(root, generation, options, context);
    if (context.generation !== generation) {
      if (fontSession) {
        const entry = context.snapshotFontSession.entry;
        if (entry && entry.handle === fontSession) {
          releaseSnapshotFontSession(entry, root);
          context.snapshotFontSession.entry = null;
        }
      }
      return root;
    }
    return action(context, {
      ...options,
      cjkDashCapability,
      ...(fontSession ? {
        snapshotFontSession: {
          status: "conforming",
          sessionId: fontSession.id,
          detail: "SnapshotFontBytes",
        },
      } : {}),
    });
  } catch (error) {
    if (context.generation === generation) {
      const entry = context.snapshotFontSession.entry;
      if (entry && (!fontSession || entry.handle === fontSession)) {
        releaseSnapshotFontSession(entry, root);
        context.snapshotFontSession.entry = null;
      }
    }
    throw error;
  }
}
