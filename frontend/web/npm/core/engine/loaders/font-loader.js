// Typography font readiness gate and initial-enhancement retry control
// (ADR 0053 batch 3; decomposition report sections 8 and 11). The gate
// decides whether the first enhancement may run now or must wait for the
// prose fonts; the retry controller re-enters the connected lifecycle when
// a bounded wait times out and the fonts settle later.

import { fontLoadingAffectsTypography } from "../../../lazy-capabilities.js";

export const DEFAULT_TYPOGRAPHY_FONT_WAIT_MS = 3_000;

function typographyFontDescriptor(style) {
  const family = style?.getPropertyValue?.("font-family")?.trim();
  const size = style?.getPropertyValue?.("font-size")?.trim();
  if (!family || !size) return null;
  const fontStyle = style.getPropertyValue("font-style").trim() || "normal";
  const weight = style.getPropertyValue("font-weight").trim() || "400";
  const stretch = style.getPropertyValue("font-stretch").trim();
  return [fontStyle, weight, stretch, size, family].filter(Boolean).join(" ");
}

export async function waitForTypographyFonts(
  fonts,
  elements,
  getStyle = globalThis.getComputedStyle,
  { timeoutMs = DEFAULT_TYPOGRAPHY_FONT_WAIT_MS } = {},
) {
  if (typeof fonts?.load !== "function" || typeof getStyle !== "function") {
    return { status: "unsupported", completion: Promise.resolve() };
  }
  const requests = new Map();
  for (const element of elements ?? []) {
    const descriptor = typographyFontDescriptor(getStyle(element));
    if (!descriptor) continue;
    let sample = requests.get(descriptor);
    if (!sample) {
      sample = new Set();
      requests.set(descriptor, sample);
    }
    for (const character of element?.textContent ?? "") sample.add(character);
  }
  const completion = Promise.all(Array.from(requests, ([descriptor, characters]) => {
    if (characters.size === 0) return Promise.resolve();
    // TypographyFontReadyGate: wait only for faces and unicode-range subsets
    // used by the prose instead of unrelated document fonts.
    return Promise.resolve()
      .then(() => fonts.load(descriptor, Array.from(characters).join("")))
      // A rejected face has settled on its CSS fallback. The fallback is a
      // stable layout input; only a still-pending load may race measurement.
      .catch(() => []);
  }));
  if (requests.size === 0) return { status: "settled", completion };

  const boundedTimeout = Number(timeoutMs);
  if (!Number.isFinite(boundedTimeout) || boundedTimeout < 0) {
    await completion;
    return { status: "settled", completion };
  }

  let timer = 0;
  const status = await Promise.race([
    completion.then(() => "settled"),
    new Promise((resolve) => {
      timer = setTimeout(() => resolve("timeout"), boundedTimeout);
    }),
  ]);
  if (timer) clearTimeout(timer);
  // BoundedTypographyFontReadyGate: callers can keep native SSR after the
  // deadline while retaining `completion` as a race-free eventual retry seam.
  return { status, completion };
}

export async function awaitInitialTypographyFonts(root, context) {
  if (!context.isCurrent()) return false;
  // Snapshot validation loads and probes the exact declared faces itself.
  // Repeating a per-paragraph computed-style scan here delayed the first
  // layout read and did no additional validation work.
  if (context.bypassesFontWait()) return true;
  const fontWait = await waitForTypographyFonts(
    document.fonts,
    context.typographyElements(),
    globalThis.getComputedStyle,
    { timeoutMs: DEFAULT_TYPOGRAPHY_FONT_WAIT_MS },
  );
  if (!context.isCurrent()) return false;
  if (fontWait.status !== "timeout") return true;
  // BoundedInitialFontGate: a slow or stuck FontFaceSet must not leave an
  // invisible transition in flight. Native SSR remains authoritative;
  // the exact completion promise and relevant font/style events restart
  // the whole gate against the latest host state.
  root.dataset.tiqianFontWait = "timeout";
  context.deferUntilFontsSettle(context.generation, fontWait.completion);
  return false;
}

export function createInitialFontRetryController(root, context) {
  let token = 0;
  let listener = null;
  let observer = null;

  function clear() {
    token += 1;
    observer?.disconnect();
    observer = null;
    if (listener) {
      document.fonts?.removeEventListener?.("loadingdone", listener);
      document.fonts?.removeEventListener?.("loadingerror", listener);
      listener = null;
    }
  }

  function deferUntilFontsSettle(generation, completion) {
    clear();
    const captured = token;
    const restart = () => {
      if (
        captured !== token || !root.isConnected ||
        !context.isGenerationCurrent(generation)
      ) return;
      context.restartConnectedLifecycle();
    };
    listener = (event) => {
      if (fontLoadingAffectsTypography(event, context.typographyElements())) restart();
    };
    document.fonts?.addEventListener?.("loadingdone", listener);
    document.fonts?.addEventListener?.("loadingerror", listener);

    if (typeof MutationObserver === "function") {
      observer = new MutationObserver(restart);
      observer.observe(root, {
        attributes: true,
        subtree: true,
        attributeFilter: ["class", "style", "data-theme", "data-color-mode"],
      });
      for (let ancestor = root.parentElement; ancestor; ancestor = ancestor.parentElement) {
        observer.observe(ancestor, {
          attributes: true,
          attributeFilter: ["class", "data-theme", "data-color-mode", "lang", "dir"],
        });
      }
    }

    Promise.resolve(completion).then(restart);
  }

  return { deferUntilFontsSettle, clear };
}
