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

export { loadTiqianRuntime };
export { declareTiqianFontFaces } from "@tiqian/prose-core/core/sampler/snapshot/declared-faces.js";

export type TiqianWebOptions = {
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

const rootGenerations = new WeakMap<HTMLElement, number>();
const rootFontSessions = new WeakMap<HTMLElement, ExactFontSessionEntry>();
const ANY_FONT_SESSION = Symbol("tiqian.anyFontSession");
copyInstaller().install(globalThis.document);

function supersedeRootWork(root: HTMLElement): number {
  const generation = (rootGenerations.get(root) ?? 0) + 1;
  rootGenerations.set(root, generation);
  return generation;
}

async function withTiqianWeb<T>(
  root: HTMLElement,
  options: TiqianWebOptions,
  action: TiqianWebAction<T>,
): Promise<HTMLElement | T> {
  await restoreAdoptedSnapshot(root);
  const generation = supersedeRootWork(root);
  let fontSession: BrowserFontSessionHandle | null = null;
  let cjkDashCapability: CjkDashShapingOutcome;
  try {
    // Finish installing the runtime and shared CSS before swapping the session
    // retained by an already-enhanced root. This keeps a rejected preparation
    // from stranding a closed session inside the Kotlin/JS root state. The
    // prepared-DOM bridge rides along so plain hosts without an exact font
    // session can still render (ADR 0053 B8.3c).
    await Promise.all([loadTiqianRuntime(), ensureTiqianStyles(), ensurePreparedDomBridge()]);
    cjkDashCapability = await prepareCjkDashShapingIfNeeded(root, options as TiqianCjkDashPrepareOptions);
    fontSession = await prepareRootFontSession(root, generation, options);
    // AsyncPreparationCancellation: navigation/destroy may happen while fonts
    // are loading. A superseded request must never re-enhance detached DOM.
    if (rootGenerations.get(root) !== generation) {
      if (fontSession) releaseRootFontSession(root, fontSession);
      return root;
    }
    return await withTiqianRuntime((api) => {
      if (rootGenerations.get(root) !== generation) return root;
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
    if (rootGenerations.get(root) === generation) {
      releaseRootFontSession(root, fontSession);
    }
    throw error;
  }
}

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

async function prepareRootFontSession(
  root: HTMLElement,
  generation: number,
  options: TiqianWebOptions,
): Promise<BrowserFontSessionHandle | null> {
  if (!root?.getAttribute?.("snapshot-ref")) {
    if (rootGenerations.get(root) === generation) releaseRootFontSession(root);
    return null;
  }
  if (hasSnapshotLayoutOverride(options)) {
    if (rootGenerations.get(root) === generation) releaseRootFontSession(root);
    root.dataset.tiqianExactFontMiss = "SnapshotLayoutOptionsOverride";
    return null;
  }
  const reference = root.getAttribute("snapshot-ref");
  const existing = rootFontSessions.get(root);
  try {
    const loader = await loadExactFontFallback();
    const handle = await loader.prepareBrowserFontSession(root);
    if (rootGenerations.get(root) !== generation) {
      loader.releaseBrowserFontSession(handle);
      return null;
    }
    const next = createExactFontSessionEntry(reference, handle, loader);
    rootFontSessions.set(root, next);
    if (existing && existing !== next) existing.release(existing.handle);
    delete root.dataset.tiqianExactFontMiss;
    return handle;
  } catch (error) {
    if (rootGenerations.get(root) === generation && rootFontSessions.get(root) === existing) {
      releaseRootFontSession(root);
    }
    root.dataset.tiqianExactFontMiss = exactFontMissDatasetValue(error as TiqianExactFontMissCandidate);
    console.warn("Tiqian Web exact snapshot font session unavailable; using browser metrics", error);
    return null;
  }
}

function releaseRootFontSession(
  root: HTMLElement,
  expectedHandle: BrowserFontSessionHandle | null | typeof ANY_FONT_SESSION = ANY_FONT_SESSION,
): boolean {
  const entry = rootFontSessions.get(root);
  if (!entry || (expectedHandle !== ANY_FONT_SESSION && entry.handle !== expectedHandle)) return false;
  rootFontSessions.delete(root);
  return releaseExactFontSession(entry, root);
}

export function enhance(root: HTMLElement = document.body, options: TiqianWebOptions = {}): Promise<HTMLElement | number> {
  return withTiqianWeb(root, options, (api, prepared) => api.enhance(root, prepared));
}

export function enhanceProgressively(root: HTMLElement = document.body, options: TiqianWebOptions = {}): Promise<HTMLElement | void> {
  return withTiqianWeb(root, options, (api, prepared) => api.enhanceProgressively(root, prepared));
}

export function destroy(root: HTMLElement = document.body): Promise<void> {
  const generation = supersedeRootWork(root);
  return restoreAdoptedSnapshot(root).then((restored) => {
    if (restored && !currentTiqianRuntime()) {
      releaseRootFontSession(root);
      return;
    }
    return withTiqianRuntime((api) => {
      if (rootGenerations.get(root) !== generation) return;
      try {
        return api!.destroy(root);
      } finally {
        releaseRootFontSession(root);
      }
    });
  }).catch((error) => {
    if (rootGenerations.get(root) === generation) releaseRootFontSession(root);
    throw error;
  });
}

export function enhanceAll(options: TiqianWebOptions = {}): Promise<Array<HTMLElement | number>> {
  const roots = [...document.querySelectorAll<HTMLElement>("tiqian-prose, [data-tiqian-root]")];
  return Promise.all(roots.map((root) => enhance(root, options)));
}