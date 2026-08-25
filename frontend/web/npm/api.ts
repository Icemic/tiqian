import {
  copyInstaller,
  currentTiqianRuntime,
  loadTiqianRuntime,
  withTiqianRuntime,
} from "@tiqian/prose-core/core/engine/loaders/runtime-loader.js";
import { prepareCjkDashShapingIfNeeded } from "@tiqian/prose-core/core/engine/loaders/cjk-dash.js";
import { restoreAdoptedSnapshot } from "@tiqian/prose-core/core/sampler/snapshot/loaded-snapshots.js";
import { ensureTiqianStyles } from "@tiqian/prose-core/core/engine/loaders/styles.js";
import {
  createExactFontSessionEntry,
  hasSnapshotLayoutOverride,
  releaseExactFontSession,
} from "@tiqian/prose-core/core/engine/exact-font.js";
import {
  ensurePreparedDomBridge,
  loadExactFontFallback,
} from "@tiqian/prose-core/core/engine/loaders/font-loader.js";
import type { TiqianEngineInstance } from "@tiqian/prose-core/core/engine/engine-entry.js";
import type { CjkDashShapingOutcome } from "@tiqian/prose-core/core/engine/loaders/cjk-dash.js";
import type { BrowserFontSessionHandle } from "@tiqian/prose-core/core/measurement/browser-fonts.js";
import type { ExactFontSessionEntry } from "@tiqian/prose-core/core/engine/exact-font.js";
import { createEnhanceContext, getOrCreateEnhanceContext } from "@tiqian/prose-core/core/engine/context/enhance-context.js";

export { loadTiqianRuntime };
export { declareTiqianFontFaces } from "@tiqian/prose-core/core/sampler/snapshot/declared-faces.js";

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

interface TiqianWebExactFontSessionWire {
  status: "conforming";
  sessionId: string;
  detail: "SnapshotExactFontBytes";
}

type TiqianPreparedWebOptions = TiqianWebOptions & {
  cjkDashCapability: CjkDashShapingOutcome;
  exactFontSession?: TiqianWebExactFontSessionWire;
};

type TiqianWebAction<T> = (api: TiqianEngineInstance, prepared: TiqianPreparedWebOptions) => T;

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

interface TiqianExactFontMissCandidate {
  code?: string;
  detail?: string;
}

interface TiqianCjkDashPrepareOptions extends TiqianWebOptions {
  exactFontSession?: unknown;
}

copyInstaller().install(globalThis.document);

function exactFontMissDatasetValue(error: TiqianExactFontMissCandidate): string {
  if (error?.code === "SnapshotExactFontContractMismatch" && typeof error?.detail === "string") {
    const pipeIndex = error.detail.indexOf("|");
    if (pipeIndex !== -1) {
      const detailSuffix = error.detail.slice(pipeIndex + 1);
      if (detailSuffix === "EmptyCandidateSet" || detailSuffix.startsWith("FieldMismatch|")) {
        return `${error.code}|${detailSuffix}`;
      }
    }
  }
  return error?.code ?? "ExactFontSessionUnavailable";
}

const ANY_FONT_SESSION = Symbol("tiqian.anyFontSession");

function releaseContextFontSession(
  context: ReturnType<typeof createEnhanceContext>,
  root: HTMLElement,
  expectedHandle: BrowserFontSessionHandle | null | typeof ANY_FONT_SESSION = ANY_FONT_SESSION,
): boolean {
  const entry = context.exactFontSession.entry;
  if (!entry || (expectedHandle !== ANY_FONT_SESSION && entry.handle !== expectedHandle)) return false;
  context.exactFontSession.entry = null;
  return releaseExactFontSession(entry, root);
}

async function prepareRootFontSession(
  root: HTMLElement,
  generation: number,
  options: TiqianWebOptions,
  context: ReturnType<typeof createEnhanceContext>
): Promise<BrowserFontSessionHandle | null> {
  if (!root?.getAttribute?.("snapshot-ref")) {
    if (context.generation === generation) {
      const entry = context.exactFontSession.entry;
      if (entry) {
        releaseExactFontSession(entry, root);
        context.exactFontSession.entry = null;
      }
    }
    return null;
  }
  if (hasSnapshotLayoutOverride(options)) {
    if (context.generation === generation) {
      const entry = context.exactFontSession.entry;
      if (entry) {
        releaseExactFontSession(entry, root);
        context.exactFontSession.entry = null;
      }
    }
    root.dataset.tiqianExactFontMiss = "SnapshotLayoutOptionsOverride";
    return null;
  }
  const reference = root.getAttribute("snapshot-ref");
  const existing = context.exactFontSession.entry;
  try {
    const loader = await loadExactFontFallback();
    const handle = await loader.prepareBrowserFontSession(root);
    if (context.generation !== generation) {
      loader.releaseBrowserFontSession(handle);
      return null;
    }
    const next = createExactFontSessionEntry(reference, handle, loader);
    context.exactFontSession.entry = next;
    if (existing && existing !== next) existing.release(existing.handle);
    delete root.dataset.tiqianExactFontMiss;
    return handle;
  } catch (error) {
    if (context.generation === generation && context.exactFontSession.entry === existing) {
      const entry = context.exactFontSession.entry;
      if (entry) {
        releaseExactFontSession(entry, root);
        context.exactFontSession.entry = null;
      }
    }
    root.dataset.tiqianExactFontMiss = exactFontMissDatasetValue(error as TiqianExactFontMissCandidate);
    console.warn("Tiqian Web exact snapshot font session unavailable; using browser metrics", error);
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
  const generation = context.beginEnhanceCycle();
  let fontSession: BrowserFontSessionHandle | null = null;
  let cjkDashCapability: CjkDashShapingOutcome;
  try {
    await Promise.all([loadTiqianRuntime(), ensureTiqianStyles(), ensurePreparedDomBridge()]);
    cjkDashCapability = await prepareCjkDashShapingIfNeeded(root, options as TiqianCjkDashPrepareOptions);
    fontSession = await prepareRootFontSession(root, generation, options, context);
    if (context.generation !== generation) {
      if (fontSession) {
        const entry = context.exactFontSession.entry;
        if (entry && entry.handle === fontSession) {
          releaseExactFontSession(entry, root);
          context.exactFontSession.entry = null;
        }
      }
      return root;
    }
    return await withTiqianRuntime((api) => {
      if (context.generation !== generation) return root;
      return action(api!, {
        ...options,
        cjkDashCapability,
        ...(fontSession ? {
          exactFontSession: {
            status: "conforming",
            sessionId: fontSession.id,
            detail: "SnapshotExactFontBytes",
          },
        } : {}),
      });
    });
  } catch (error) {
    if (context.generation === generation) {
      const entry = context.exactFontSession.entry;
      if (entry && (!fontSession || entry.handle === fontSession)) {
        releaseExactFontSession(entry, root);
        context.exactFontSession.entry = null;
      }
    }
    throw error;
  }
}

export function enhance(root: HTMLElement = document.body, options: TiqianWebOptions = {}): Promise<HTMLElement | number> {
  return withTiqianWeb(root, options, (api, prepared) => api.enhance(root, prepared));
}

export function enhanceProgressively(root: HTMLElement = document.body, options: TiqianWebOptions = {}): Promise<HTMLElement | void> {
  return withTiqianWeb(root, options, (api, prepared) => api.enhanceProgressively(root, prepared));
}

export function destroy(root: HTMLElement = document.body): Promise<void> {
  const context = getOrCreateEnhanceContext(root);
  const generation = context.beginEnhanceCycle();
  return restoreAdoptedSnapshot(root).then((restored) => {
    if (restored && !currentTiqianRuntime()) {
      releaseContextFontSession(context, root);
      return;
    }
    return withTiqianRuntime((api) => {
      if (context.generation !== generation) return;
      try {
        return api!.destroy(root);
      } finally {
        releaseContextFontSession(context, root);
      }
    });
  }).catch((error) => {
    if (context.generation === generation) releaseContextFontSession(context, root);
    throw error;
  });
}

export function enhanceAll(options: TiqianWebOptions = {}): Promise<Array<HTMLElement | number>> {
  const roots = [...document.querySelectorAll<HTMLElement>("tiqian-prose, [data-tiqian-root]")];
  return Promise.all(roots.map((root) => enhance(root, options)));
}