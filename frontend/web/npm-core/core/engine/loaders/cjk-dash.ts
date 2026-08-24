// CJK dash shaping capability gate. Detection stays eager and synchronous;
// the shaping outcome fails closed until a conforming glyph source exists.
// Moved from lazy-capabilities.js in ADR 0053 batch 6. Issue naming belongs
// to the engine font policy (font module CjkDashCapabilityPolicy); this host
// only produces the status/detail evidence envelope.

const CJK_DASH_SOURCE = "——";
const TWO_EM_DASH = "⸺";

interface TextBearingRoot {
  textContent: string | null;
}

export function needsCjkDashShaping(root: TextBearingRoot | null | undefined): boolean {
  const text = root?.textContent ?? "";
  return text.includes(CJK_DASH_SOURCE) || text.includes(TWO_EM_DASH);
}

export function prepareCjkDashShapingIfNeeded(
  root: TextBearingRoot | null | undefined,
  options: { exactFontSession?: unknown } = {},
) {
  if (!needsCjkDashShaping(root)) return Promise.resolve({ status: "not-needed" });
  return Promise.resolve({
    status: "unavailable",
    detail: options?.exactFontSession
      ? "ServerShapingReplayRequired"
      : "BrowserHarfBuzzDisabled",
  });
}
