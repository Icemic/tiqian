// CJK dash shaping capability gate. Detection stays eager and synchronous;
// the shaping outcome fails closed until a conforming glyph source exists.
// Moved from lazy-capabilities.js in ADR 0053 batch 6. Issue naming belongs
// to the engine font policy (font module CjkDashCapabilityPolicy); this host
// only produces the status/detail evidence envelope.

interface TextBearingRoot {
  textContent: string | null;
}

interface CjkDashPrepareOptions {
  snapshotFontSession?: unknown;
}

const CJK_DASH_SOURCE = "——";
const TWO_EM_DASH = "⸺";

export function needsCjkDashShaping(root: TextBearingRoot | null | undefined): boolean {
  const text = root?.textContent ?? "";
  return text.includes(CJK_DASH_SOURCE) || text.includes(TWO_EM_DASH);
}

/** Outcome envelope of the dash shaping gate; detail names the reason. */
export interface CjkDashShapingOutcome {
  status: string;
  detail: string | null;
}

/**
 * Synchronously compute the CJK dash shaping outcome against the current
 * root textContent and options. This is the pure evidence function that both
 * the coordinated initial capture and the reconcile refresh must call so they
 * agree on the same evidence basis.
 */
export function computeCjkDashOutcome(root: TextBearingRoot | null | undefined, options: CjkDashPrepareOptions = {}): CjkDashShapingOutcome {
  if (!needsCjkDashShaping(root)) return { status: "not-needed", detail: null };
  return {
    status: "unavailable",
    detail: options?.snapshotFontSession
      ? "ServerShapingReplayRequired"
      : "BrowserHarfBuzzDisabled",
  };
}

export function prepareCjkDashShapingIfNeeded(root: TextBearingRoot | null | undefined, options: CjkDashPrepareOptions = {}): Promise<CjkDashShapingOutcome> {
  return Promise.resolve(computeCjkDashOutcome(root, options));
}
