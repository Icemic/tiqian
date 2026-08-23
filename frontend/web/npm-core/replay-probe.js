import { normalizeReplayNumber } from "./replay-entry-codec.js";

/**
 * CanvasMeasureReplayProbePolicy (ADR 0053 A5b).
 *
 * Implements a heuristic replay probe policy for shaping and metric table misses
 * during unbaked sessions. When a table miss occurs, the session delegates to a
 * host-injected synchronous canvas measureText probe. The probed measurements are
 * canonicalized from px to em via `normalizeReplayNumber` and backfilled into the
 * session table for subsequent lookups.
 *
 * Evidence provenance is recorded in `faceId` using `REPLAY_PROBE_FACE_PREFIX`.
 * Due to worker environments lacking DOM Range access, ink bounds and typography
 * features cannot be measured via canvas measureText alone and remain empty/null/zero.
 */

export const REPLAY_PROBE_FACE_PREFIX = "canvas-probe:";
export const CJK_METRIC_PROBE_TEXT = "中";
export const LATIN_METRIC_PROBE_TEXT = "Hg";
export const ZERO_ADVANCE_EPSILON = 0.01;

export function replayProbeCssFont(serializedFamilies, fontWeight, italic, fontSize) {
  const stack = (typeof serializedFamilies === "string" ? serializedFamilies : "")
    .split("\u001f")
    .filter((family) => family.length > 0)
    .join(", ");
  const style = italic ? "italic" : "normal";
  return `${style} ${fontWeight} ${fontSize}px ${stack}`;
}

export function probeShapeReplayResult(
  { displayText, serializedFamilies, fontSize, fontWeight, italic },
  measure,
) {
  if (typeof measure !== "function") return null;
  const cssFont = replayProbeCssFont(serializedFamilies, fontWeight, italic, fontSize);
  let m;
  try {
    m = measure(cssFont, displayText);
  } catch {
    return null;
  }
  if (!m || typeof m !== "object" || typeof m.width !== "number" || !Number.isFinite(m.width)) {
    return null;
  }
  const advanceEm = normalizeReplayNumber(m.width, fontSize);
  if (advanceEm == null) return null;
  const stack = (typeof serializedFamilies === "string" ? serializedFamilies : "")
    .split("\u001f")
    .filter((family) => family.length > 0)
    .join(", ");
  return {
    faceId: `${REPLAY_PROBE_FACE_PREFIX}${stack}`,
    fontInstanceId: "",
    script: "",
    features: [],
    unsafeBreakCount: 0,
    advanceEm,
    glyphs: [
      {
        id: 0,
        advanceEm,
        xEm: 0,
        yEm: 0,
        boundsEm: null,
      },
    ],
  };
}

export function probeMetricReplayValues(
  { serializedFamilies, fontSize, fontWeight, italic, role },
  measure,
) {
  if (typeof measure !== "function") return null;
  const cjkBox = role === "CjkText" || role === "CjkPunctuation";
  const probeText = cjkBox ? CJK_METRIC_PROBE_TEXT : LATIN_METRIC_PROBE_TEXT;
  const cssFont = replayProbeCssFont(serializedFamilies, fontWeight, italic, fontSize);
  let m;
  try {
    m = measure(cssFont, probeText);
  } catch {
    return null;
  }
  if (!m || typeof m !== "object") return null;
  if (typeof m.width !== "number" || !Number.isFinite(m.width) || m.width <= ZERO_ADVANCE_EPSILON) {
    return null;
  }
  const ascentPx = m.fontBoundingBoxAscent ?? m.actualBoundingBoxAscent;
  if (typeof ascentPx !== "number" || !Number.isFinite(ascentPx)) return null;
  const descentPx = m.fontBoundingBoxDescent ?? m.actualBoundingBoxDescent;
  if (typeof descentPx !== "number" || !Number.isFinite(descentPx)) return null;
  const leadingPx = 0;
  let typoAscentPx = null;
  let typoDescentPx = null;
  if (cjkBox && typeof m.ideographicBaseline === "number" && Number.isFinite(m.ideographicBaseline)) {
    const ideographicDescent = -m.ideographicBaseline;
    typoAscentPx = Math.max(fontSize - ideographicDescent, 0);
    typoDescentPx = Math.max(ideographicDescent, 0);
  }
  const ascentEm = normalizeReplayNumber(ascentPx, fontSize);
  const descentEm = normalizeReplayNumber(descentPx, fontSize);
  const leadingEm = normalizeReplayNumber(leadingPx, fontSize);
  if (ascentEm == null || descentEm == null || leadingEm == null) return null;
  const typoAscentEm = typoAscentPx != null ? normalizeReplayNumber(typoAscentPx, fontSize) : null;
  const typoDescentEm = typoDescentPx != null ? normalizeReplayNumber(typoDescentPx, fontSize) : null;
  return [ascentEm, descentEm, leadingEm, typoAscentEm, typoDescentEm];
}
