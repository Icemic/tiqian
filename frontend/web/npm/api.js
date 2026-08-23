import { currentTiqianRuntime, loadTiqianRuntime, withTiqianRuntime } from "./core/engine/loaders/runtime-loader.js";
import { installTiqianCopyHandler } from "./core/utils/copy.js";
import { prepareCjkDashShapingIfNeeded } from "./core/engine/loaders/cjk-dash.js";
import { restoreAdoptedSnapshot } from "./core/sampler/snapshot/loaded-snapshots.js";
import { ensureTiqianStyles } from "./core/engine/loaders/styles.js";
import {
  createExactFontSessionEntry,
  hasSnapshotLayoutOverride,
  releaseExactFontSession,
} from "./core/engine/exact-font.js";
import { loadExactFontFallback } from "./core/engine/loaders/font-loader.js";

export { loadTiqianRuntime };

const rootGenerations = new WeakMap();
const rootFontSessions = new WeakMap();
const ANY_FONT_SESSION = Symbol("tiqian.anyFontSession");
installTiqianCopyHandler();

function supersedeRootWork(root) {
  const generation = (rootGenerations.get(root) ?? 0) + 1;
  rootGenerations.set(root, generation);
  return generation;
}

async function withTiqianWeb(root, options, action) {
  await restoreAdoptedSnapshot(root);
  const generation = supersedeRootWork(root);
  let fontSession = null;
  let cjkDashCapability;
  try {
    // Finish installing the runtime and shared CSS before swapping the session
    // retained by an already-enhanced root. This keeps a rejected preparation
    // from stranding a closed session inside the Kotlin/JS root state.
    await Promise.all([loadTiqianRuntime(), ensureTiqianStyles()]);
    cjkDashCapability = await prepareCjkDashShapingIfNeeded(root, options);
    fontSession = await prepareRootFontSession(root, generation, options);
    // AsyncPreparationCancellation: navigation/destroy may happen while fonts
    // are loading. A superseded request must never re-enhance detached DOM.
    if (rootGenerations.get(root) !== generation) {
      if (fontSession) releaseRootFontSession(root, fontSession);
      return root;
    }
    return await withTiqianRuntime((api) => {
      if (rootGenerations.get(root) !== generation) return root;
      return action(api, {
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

async function prepareRootFontSession(root, generation, options) {
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
    root.dataset.tiqianExactFontMiss = error?.code ?? "ExactFontSessionUnavailable";
    console.warn("Tiqian Web exact snapshot font session unavailable; using browser metrics", error);
    return null;
  }
}

function releaseRootFontSession(root, expectedHandle = ANY_FONT_SESSION) {
  const entry = rootFontSessions.get(root);
  if (!entry || (expectedHandle !== ANY_FONT_SESSION && entry.handle !== expectedHandle)) return false;
  rootFontSessions.delete(root);
  return releaseExactFontSession(entry, root);
}

export function enhance(root = document.body, options = {}) {
  return withTiqianWeb(root, options, (api, prepared) => api.enhance(root, prepared));
}

export function enhanceProgressively(root = document.body, options = {}) {
  return withTiqianWeb(root, options, (api, prepared) => api.enhanceProgressively(root, prepared));
}

export function destroy(root = document.body) {
  const generation = supersedeRootWork(root);
  return restoreAdoptedSnapshot(root).then((restored) => {
    if (restored && !currentTiqianRuntime()) {
      releaseRootFontSession(root);
      return;
    }
    return withTiqianRuntime((api) => {
      if (rootGenerations.get(root) !== generation) return;
      try {
        return api.destroy(root);
      } finally {
        releaseRootFontSession(root);
      }
    });
  }).catch((error) => {
    if (rootGenerations.get(root) === generation) releaseRootFontSession(root);
    throw error;
  });
}

export function enhanceAll(options = {}) {
  const roots = [...document.querySelectorAll("tiqian-prose, [data-tiqian-root]")];
  return Promise.all(roots.map((root) => enhance(root, options)));
}
