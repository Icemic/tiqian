// Typography font readiness gate and initial-enhancement retry control
// (ADR 0053 batch 3; decomposition report sections 8 and 11). The gate
// decides whether the first enhancement may run now or must wait for the
// prose fonts; the retry controller re-enters the connected lifecycle when
// a bounded wait times out and the fonts settle later.

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

// Exact font fallback loader (merged from element.js and api.js in ADR 0053
// batch 4; decomposition report section 9). Both host entries share one lazy
// import gate; the module API path and the custom element path resolve the
// same browser-font and prepared-dom adapters.

let exactFontFallbackPromise;

export function loadExactFontFallback() {
  exactFontFallbackPromise ??= Promise.all([
    import("../../measurement/browser-fonts.js"),
    import("../../sampler/snapshot/prepared-dom.js"),
  ]).then(([fonts, preparedDom]) => {
    preparedDom.installPreparedDomRendererBridge();
    return {
      prepareBrowserFontSession: fonts.prepareBrowserFontSession,
      revalidateBrowserFontSession: fonts.revalidateBrowserFontSession,
      prepareBrowserRenderFonts: fonts.prepareBrowserRenderFonts,
      releaseBrowserFontSession: fonts.releaseBrowserFontSession,
      installPreparedRenderFontStyle: preparedDom.installPreparedRenderFontStyle,
      releasePreparedRenderFontStyle: preparedDom.releasePreparedRenderFontStyle,
    };
  });
  return exactFontFallbackPromise;
}

// PlainHostPreparedBridge: every paragraph lowers through the prepared DOM
// (ADR 0053 B8.3c), so a host without an exact font session still needs the
// renderer bridge before its first enhance. The dynamic import keeps the
// renderer out of the entry chunk; the module self-installs the bridge. An
// already-occupied slot belongs to a test fixture or an exact-session
// install and is left untouched — loadExactFontFallback keeps its own
// monotonic upgrade for a stale legacy occupant.
let preparedBridgePromise;

export function ensurePreparedDomBridge() {
  preparedBridgePromise ??= globalThis.__TiqianPreparedDomRenderer
    ? Promise.resolve(globalThis.__TiqianPreparedDomRenderer)
    : import("../../sampler/snapshot/prepared-dom.js").then(
        () => globalThis.__TiqianPreparedDomRenderer,
      );
  return preparedBridgePromise;
}

// CSS font-family parsing and the document font-loading filter moved here
// from lazy-capabilities.js in ADR 0053 batch 6.

function normalizeFontFamily(value) {
  const trimmed = String(value ?? "").trim();
  const unquoted = trimmed.length >= 2 && (
    (trimmed.startsWith("\"") && trimmed.endsWith("\"")) ||
    (trimmed.startsWith("'") && trimmed.endsWith("'"))
  ) ? trimmed.slice(1, -1) : trimmed;
  return unquoted.normalize("NFC").toLocaleLowerCase("en-US");
}

export function parseCssFontFamilies(value) {
  const families = [];
  let token = "";
  let quote = "";
  let escaped = false;
  for (const char of String(value ?? "")) {
    if (escaped) {
      token += char;
      escaped = false;
    } else if (char === "\\") {
      escaped = true;
    } else if (quote) {
      if (char === quote) quote = "";
      else token += char;
    } else if (char === "\"" || char === "'") {
      quote = char;
    } else if (char === ",") {
      if (token.trim()) families.push(normalizeFontFamily(token));
      token = "";
    } else {
      token += char;
    }
  }
  if (token.trim()) families.push(normalizeFontFamily(token));
  return families;
}

function numericFontWeight(value) {
  const normalized = String(value ?? "normal").trim().toLowerCase();
  if (normalized === "normal") return 400;
  if (normalized === "bold") return 700;
  const parsed = Number.parseFloat(normalized);
  return Number.isFinite(parsed) ? parsed : null;
}

function fontFaceCoversWeight(faceWeight, requestedWeight) {
  const requested = numericFontWeight(requestedWeight);
  if (requested == null || faceWeight == null || String(faceWeight).trim() === "") return true;
  const bounds = String(faceWeight).trim().split(/\s+/u).map(numericFontWeight);
  if (bounds.some((value) => value == null)) return true;
  return requested >= Math.min(...bounds) && requested <= Math.max(...bounds);
}

function fontFaceCoversStyle(faceStyle, requestedStyle) {
  if (faceStyle == null || String(faceStyle).trim() === "") return true;
  const available = String(faceStyle).trim().toLowerCase();
  const requested = String(requestedStyle || "normal").trim().toLowerCase();
  if (requested.startsWith("italic")) return available.startsWith("italic");
  if (requested.startsWith("oblique")) return available.startsWith("oblique");
  return available === "normal";
}

export function fontLoadingAffectsTypography(event, elements, getStyle = globalThis.getComputedStyle) {
  const faces = Array.from(event?.fontfaces ?? []);
  if (faces.length === 0 || typeof getStyle !== "function") return true;
  const usages = Array.from(elements ?? []).flatMap((element) => [
    null,
    "::before",
    "::after",
    "::first-letter",
    "::first-line",
  ].map((pseudo) => {
    const style = getStyle(element, pseudo);
    return {
      families: new Set(parseCssFontFamilies(style.getPropertyValue("font-family"))),
      weight: style.getPropertyValue("font-weight"),
      fontStyle: style.getPropertyValue("font-style"),
    };
  }));
  return faces.some((face) => {
    const family = normalizeFontFamily(face?.family);
    return usages.some((usage) =>
      usage.families.has(family) &&
      fontFaceCoversWeight(face?.weight, usage.weight) &&
      fontFaceCoversStyle(face?.style, usage.fontStyle));
  });
}
