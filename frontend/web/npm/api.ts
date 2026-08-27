import {
  currentTiqianRuntime,
  loadTiqianRuntime,
} from "@tiqian/core/core/engine/loaders/runtime-loader.js";
import { globalServices } from "@tiqian/core/core/services/global-services.js";
import { prepareCjkDashShapingIfNeeded } from "@tiqian/core/core/engine/loaders/cjk-dash.js";
import { restoreAdoptedSnapshot } from "@tiqian/core/core/sampler/snapshot/loaded-snapshots.js";
import { ensureTiqianStyles } from "@tiqian/core/core/engine/loaders/styles.js";
import {
  createSnapshotFontSessionEntry,
  hasSnapshotLayoutOverride,
  releaseSnapshotFontSession,
} from "@tiqian/core/core/engine/snapshot-font.js";
import {
  ensurePreparedDomBridge,
  loadSnapshotFontFallback,
} from "@tiqian/core/core/engine/loaders/font-loader.js";
import {
  enhance as enhanceRoot,
  enhanceProgressively as enhanceProgressivelyRoot,
} from "@tiqian/core/core/engine/progressive-drivers.js";
import { destroyRoot } from "@tiqian/core/core/engine/lifecycle.js";
import { createRootState } from "@tiqian/core/core/engine/root-state.js";
import type { RootStateApi } from "@tiqian/core/core/engine/root-state.js";
import type { CjkDashShapingOutcome } from "@tiqian/core/core/engine/loaders/cjk-dash.js";
import type { BrowserFontSessionHandle } from "@tiqian/core/core/measurement/browser-fonts.js";
import type { SnapshotFontSessionEntry } from "@tiqian/core/core/engine/snapshot-font.js";
import { getOrCreateEnhanceContext, getContextForElement } from "@tiqian/core/core/engine/context/enhance-context.js";

// Per-root rootState holder for the npm API (scope 9 deletes this file).
// The session layer holds its own rootState; this map is for the direct API.
const apiRootStates = new WeakMap<HTMLElement, RootStateApi>();

function getOrCreateApiRootState(root: HTMLElement): RootStateApi {
  let rootState = apiRootStates.get(root);
  if (!rootState) {
    rootState = createRootState();
    apiRootStates.set(root, rootState);
  }
  return rootState;
}

export { loadTiqianRuntime };
export { declareTiqianFontFaces } from "@tiqian/core/core/sampler/snapshot/declared-faces.js";

export type TraceConfig = { maxEntries?: number; };

export type TiqianWebOptions = {
  trace?: TraceConfig;
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
};

interface TiqianWebSnapshotFontSessionWire {
  status: "conforming";
  sessionId: string;
  detail: "SnapshotFontBytes";
}

type TiqianPreparedWebOptions = TiqianWebOptions & {
  cjkDashCapability: CjkDashShapingOutcome;
  snapshotFontSession?: TiqianWebSnapshotFontSessionWire;
};

type TiqianWebAction<T> = (rootState: RootStateApi, prepared: TiqianPreparedWebOptions, context: ReturnType<typeof getOrCreateEnhanceContext>) => T;

export interface TiqianWebGlobalApi {
  enhance(root?: HTMLElement, options?: TiqianWebOptions): Promise<HTMLElement>;
  enhanceProgressively(root?: HTMLElement, options?: TiqianWebOptions): HTMLElement;
  destroy(root?: HTMLElement): void;
  enhanceAll(options?: TiqianWebOptions): void;
}

declare global {
  interface Window {
    TiqianWeb?: TiqianWebGlobalApi;
  }
}

interface TiqianSnapshotFontMissCandidate {
  code?: string;
  detail?: string;
}

interface TiqianCjkDashPrepareOptions extends TiqianWebOptions {
  snapshotFontSession?: unknown;
}

function snapshotFontMissDatasetValue(error: TiqianSnapshotFontMissCandidate): string {
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

const ANY_FONT_SESSION = Symbol("tiqian.anyFontSession");

function releaseContextFontSession(
  context: ReturnType<typeof getOrCreateEnhanceContext>,
  root: HTMLElement,
  expectedHandle: BrowserFontSessionHandle | null | typeof ANY_FONT_SESSION = ANY_FONT_SESSION,
): boolean {
  const entry = context.snapshotFontSession.entry;
  if (!entry || (expectedHandle !== ANY_FONT_SESSION && entry.handle !== expectedHandle)) return false;
  context.snapshotFontSession.entry = null;
  return releaseSnapshotFontSession(entry, root);
}

async function prepareRootFontSession(
  root: HTMLElement,
  generation: number,
  options: TiqianWebOptions,
  context: ReturnType<typeof getOrCreateEnhanceContext>
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
  if (hasSnapshotLayoutOverride(options)) {
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

async function withTiqianWeb<T>(
  root: HTMLElement,
  options: TiqianWebOptions,
  action: TiqianWebAction<T>,
): Promise<HTMLElement | T> {
  const context = getOrCreateEnhanceContext(root);
  await restoreAdoptedSnapshot(root);
  const generation = context.update();
  let fontSession: BrowserFontSessionHandle | null = null;
  let cjkDashCapability: CjkDashShapingOutcome;
  try {
    await Promise.all([
      loadTiqianRuntime(),
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
    const rootState = getOrCreateApiRootState(root);
    if (context.generation !== generation) return root;
    return action(rootState, {
      ...options,
      cjkDashCapability,
      ...(fontSession ? {
        snapshotFontSession: {
          status: "conforming",
          sessionId: fontSession.id,
          detail: "SnapshotFontBytes",
        },
      } : {}),
    }, context);
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

export function enhance(root: HTMLElement = document.body, options: TiqianWebOptions = {}): Promise<HTMLElement | number> {
  return withTiqianWeb(root, options, (rootState, prepared, context) =>
    enhanceRoot(rootState, globalServices().coordination.layoutJobPool, context, root, prepared));
}

export function enhanceProgressively(root: HTMLElement = document.body, options: TiqianWebOptions = {}): Promise<HTMLElement | void> {
  return withTiqianWeb(root, options, (rootState, prepared, context) =>
    enhanceProgressivelyRoot(rootState, globalServices().coordination.layoutJobPool, context, root, prepared));
}

export async function destroy(root: HTMLElement = document.body): Promise<void> {
  const context = getContextForElement(root);
  if (!context) return;
  const generation = context.update();
  try {
    const restored = await restoreAdoptedSnapshot(root);
    if (restored && !currentTiqianRuntime()) {
      releaseContextFontSession(context, root);
      context.destroy();
      return;
    }
    const rootState = getOrCreateApiRootState(root);
    if (context.generation !== generation) return;
    try {
      destroyRoot(rootState, globalServices().coordination.layoutJobPool, context, root);
    } finally {
      apiRootStates.delete(root);
      releaseContextFontSession(context, root);
      context.destroy();
    }
  } catch (error) {
    if (context.generation === generation) {
      releaseContextFontSession(context, root);
      context.destroy();
    }
    throw error;
  }
}

export function enhanceAll(options: TiqianWebOptions = {}): Promise<Array<HTMLElement | number>> {
  const roots = [...document.querySelectorAll<HTMLElement>("tiqian-prose, [data-tiqian-root]")];
  return Promise.all(roots.map((root) => enhance(root, options)));
}