// CJK dash shaping capability gate. Detection stays eager and synchronous;
// the shaping outcome fails closed until a conforming glyph source exists.
// Moved from lazy-capabilities.js in ADR 0053 batch 6.

const CJK_DASH_SOURCE = "——";
const TWO_EM_DASH = "⸺";

export function needsCjkDashShaping(root) {
  const text = root?.textContent ?? "";
  return text.includes(CJK_DASH_SOURCE) || text.includes(TWO_EM_DASH);
}

export function prepareCjkDashShapingIfNeeded(root, options = {}) {
  if (!needsCjkDashShaping(root)) return Promise.resolve({ status: "not-needed" });
  return Promise.resolve({
    status: "unavailable",
    issue: "NoConformingCjkDashGlyph",
    detail: options?.exactFontSession
      ? "ServerShapingReplayRequired"
      : "BrowserHarfBuzzDisabled",
  });
}
