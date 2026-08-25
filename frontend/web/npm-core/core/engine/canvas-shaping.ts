// Canvas text shaping adapter (TsHost runtime port, Slice 4a part 2). Ports
// WebCanvasTextShaper (OffscreenMeasureTextShaping, ADR 0039) from
// WebCanvasTextShaper.kt, plus its file-level helpers. The shaping decision
// reason strings are byte-compared downstream (dumps, capability details), so
// their construction replicates the Kotlin buildString exactly.
//
// Role values on this layer are the Kotlin FontRole enum NAMES:
// "CjkText", "CjkPunctuation", "LatinText", "Symbol", "Emoji", "Unknown".
// They are NOT the markdown-lowering role strings ("cjk-text" etc.) — that is
// a different layer mapped through classifyFontRole.
//
// Number formatting: arithmetic is plain JS numbers (Kotlin/JS Float math
// compiles without 32-bit rounding in this product). No Math.fround.
//
// The module never touches the document: env is the DOM injection surface and
// cjkDashCapability is the host-side dash capability evidence. fonts is a
// WebFontFamilies instance and MUST be the same instance the DOM renderer
// draws with, or advances will not match the drawn glyphs.
//
// ES module: exports createTextShaper, installFontLoadInvalidation,
// clearMeasurementCache, and measurementCacheSize as named bindings.

import type { FontRoleName, WebFontFamiliesInstance } from "./canvas-fonts.js";
import type { CanvasContextLike } from "./canvas-metrics.js";
import { globalServices } from "../services/global-services.js";
import {
  clearMeasurementState,
  measurementCacheEntryCount,
} from "./coordination/measurement.js";

export interface TextMetricsLike {
  width: number;
  actualBoundingBoxLeft: number;
  actualBoundingBoxAscent: number;
  actualBoundingBoxRight: number;
  actualBoundingBoxDescent: number;
}

export interface RectLike {
  left: number;
  top: number;
  right: number;
  bottom: number;
}

interface ShapingProbeRectWidth {
  width: number;
}

type ShapingSetAttributeFn = (name: string, value: string) => void;

type ShapingSetPropertyFn = (name: string, value: string, priority?: string) => void;

interface ShapingProbeElementStyle {
  setProperty: ShapingSetPropertyFn;
}

type ShapingProbeRectFn = () => ShapingProbeRectWidth;

type ShapingCreateCanvasContextFn = () => CanvasContextLike;

type ShapingCreateProbeElementFn = () => ProbeElementLike;

type ShapingAttachProbeFn = (element: ProbeElementLike) => void;

interface ShapingTextRange {
  start: number;
  end: number;
}

interface ShapingInputStyle {
  fontSize: number;
  fontWeight: number;
  italic: boolean;
  fontFamilies: string[];
  locale?: string;
}

interface ShapingFontCandidate {
  key: string;
}

interface ShapingFontDecision {
  role: FontRoleName | string;
  candidate: ShapingFontCandidate;
}

export interface ProbeElementLike {
  setAttribute: ShapingSetAttributeFn;
  style: ShapingProbeElementStyle;
  parentNode: HTMLElement | Record<string, unknown> | null;
  textContent: string;
  getBoundingClientRect: ShapingProbeRectFn;
}

export interface CanvasShapingEnv {
  createCanvasContext: ShapingCreateCanvasContextFn;
  createProbeElement: ShapingCreateProbeElementFn;
  attachProbe: ShapingAttachProbeFn;
}

export interface ShapeInput {
  text: string;
  range: ShapingTextRange;
  style: ShapingInputStyle;
  fontDecision: ShapingFontDecision;
  displayText?: string;
  openTypeFeatures?: string[];
}

export interface MeasuredTextLike {
  advance: number;
  bounds: RectLike | null;
  requestedFont: string;
  actualFont: string;
  boundsAdjustment: string | null;
}

export interface ShapingCluster {
  range: ShapingTextRange;
  text: string;
  displayText: string;
  fontKey: string;
  advance: number;
}

export interface ShapingGlyph {
  id: number;
  clusterRange: ShapingTextRange;
  advance: number;
  x: number;
  bounds: RectLike | null;
}

export interface ShapingGlyphRun {
  range: ShapingTextRange;
  fontKey: string;
  glyphs: ShapingGlyph[];
  advance: number;
  openTypeFeatures: string[];
}

export interface ShapingDecision {
  range: ShapingTextRange;
  sourceText: string;
  displayText: string;
  fontKey: string;
  glyphCount: number;
  advance: number;
  source: string;
  reason: string;
  glyphsWithoutInkBounds: number;
  capabilityIssue: string | null;
  featureEvidence: string | null;
}

export interface CanvasShapingResult {
  clusters: ShapingCluster[];
  glyphRuns: ShapingGlyphRun[];
  decisions: ShapingDecision[];
}

type ShapingShapeFn = (input: ShapeInput) => CanvasShapingResult;

export interface CanvasTextShaperInstance {
  shape: ShapingShapeFn;
}

export interface CjkDashCapability {
  status: string;
  detail: string | null;
}

interface CapabilityIssue {
  name: string;
  detail: string;
}

type ShapingComputeMeasurementFn = () => MeasuredTextLike;

interface ShapingOverhangNormalization {
  bounds: RectLike;
  adjustment: string | null;
}

interface ShapingImageDataLike {
  data: Uint8ClampedArray | ArrayLike<number>;
}

type ShapingVoidCallbackFn = () => void;

type ShapingAddEventListenerFn = (type: string, listener: ShapingVoidCallbackFn) => void;

interface ShapingFontSetLike {
  addEventListener: ShapingAddEventListenerFn;
}

const DEGENERATE_INK_PROBE_TEXT = "\u3002"; // "。"
const DEGENERATE_INK_EPSILON_PX = 0.1;
const RASTER_INK_SCALE = 4.0;
const ADVANCE_PARITY_PROBE_TEXT = "Benjamini-Hochberg WAVE fjord, 0x7f.";
const ADVANCE_PARITY_RELATIVE_EPSILON = 0.01;
const ADVANCE_PARITY_ABSOLUTE_EPSILON_PX = 0.25;
const FONT_PX_SIZE_REGEX = /(\d+(?:\.\d+)?)px/;
const CJK_DASH_SOURCE = "\u2014\u2014"; // two U+2014
const TWO_EM_DASH = "\u2e3a"; // "⸺"
const ZERO_ADVANCE_EPSILON = 0.01;
const PROPORTIONAL_CURLY_QUOTE_FEATURE_SIGNATURE = "pwid,palt";
const CANVAS_INK_OVERHANG_EVIDENCE_THRESHOLD_PX = 1;
const UNVERIFIED_DISPLAY_SUBSTITUTION_COVERAGE_ISSUE =
  "UnverifiedDisplaySubstitutionCoverage";
const CANVAS_CANNOT_VERIFY_SAME_FACE_U22EF_COVERAGE =
  "CanvasCannotVerifySameFaceU+22EFCoverage";
// BoundedSharedMeasurementCache: shared across every shaper instance so
// cross-root resizes stay warm (ADR 0039), and bounded so a long-lived page
// cannot retain every glyph run it has ever measured. A hit reinserts its
// entry, so eviction drops the least recently used key. The cache and the
// per-font probe verdicts live on the service-owned
// MeasurementCoordinationState (page-level single, see measurement.ts).
const MEASUREMENT_CACHE_MAX_ENTRIES = 2048;

// MeasurementKey is structural equality over four strings; JSON round-trips
// that exactly. The separator joins used elsewhere are unsafe here because
// display text is arbitrary user input that could contain the separator.
function measurementCacheKey(actualFont: string, display: string, featureSignature: string, role: string): string {
  return JSON.stringify([actualFont, display, featureSignature, role]);
}

function measurementCacheGetOrPut(key: string, compute: ShapingComputeMeasurementFn): MeasuredTextLike {
  const measurementCache = globalServices().measurement.measurementCache;
  if (measurementCache.has(key)) {
    const hit = measurementCache.get(key)!;
    measurementCache.delete(key);
    measurementCache.set(key, hit);
    return hit;
  }
  const value = compute();
  measurementCache.set(key, value);
  while (measurementCache.size > MEASUREMENT_CACHE_MAX_ENTRIES) {
    const eldestKey = measurementCache.keys().next().value!;
    measurementCache.delete(eldestKey);
  }
  return value;
}

/**
 * `WebfontArrivalMeasurementInvalidation`: cache keys carry the serialized
 * font string, which cannot tell a fallback-face measurement taken mid-load
 * from one against the loaded face. One FontFaceSet listener drops the cache
 * and probe verdicts when a load batch completes; the runtime's loadingdone
 * re-enhancement re-measures.
 *
 * @param {{ addEventListener: Function }} fontSet
 * @returns {void}
 */
export function installFontLoadInvalidation(fontSet?: ShapingFontSetLike | FontFaceSet | null): void {
  const state = globalServices().measurement;
  if (state.fontLoadInvalidationInstalled) return;
  state.fontLoadInvalidationInstalled = true;
  if (!fontSet || typeof fontSet.addEventListener !== "function") return;
  fontSet.addEventListener("loadingdone", function () {
    clearMeasurementCache();
  });
}

/**
 * Drop the shared measurement cache and both per-font probe verdict caches.
 *
 * @returns {void}
 */
export function clearMeasurementCache(): void {
  clearMeasurementState(globalServices().measurement);
}

/**
 * Current number of entries in the shared bounded measurement cache.
 *
 * @returns {number}
 */
export function measurementCacheSize(): number {
  return measurementCacheEntryCount(globalServices().measurement);
}

// CjkDashCapabilityPolicy ports inline (see the font module): the CJK dash
// shaping outcome fails closed while no conforming glyph source exists.
// "conforming" names the missing exact font session; any other status
// (including null) reports the absence of a conforming CJK dash glyph.
function dashIssueNameFor(status?: string | null): string {
  return status === "conforming"
    ? "ConformingCjkDashRequiresExactFontSession"
    : "NoConformingCjkDashGlyph";
}

// issueDetailFor: a null status means shaping was never prepared. A null or
// blank detail keeps only the status prefix; otherwise the host detail is
// appended after "; ".
function dashIssueDetailFor(status?: string | null, detail?: string | null): string {
  if (status == null) return "CjkDashFontShapingNotPrepared";
  if (detail == null || String(detail).trim() === "") return "status=" + status;
  return "status=" + status + "; " + detail;
}

/**
 * EllipsisDisplaySubstitutionCheck: source and display have equal non-zero
 * length and every source code unit is U+2026 while every display code unit
 * is U+22EF.
 *
 * @param {string} source
 * @param {string} display
 * @returns {boolean}
 */
function isUnverifiedEllipsisDisplaySubstitution(source: string, display?: string): boolean {
  if (source.length === 0 || source.length !== display!.length) return false;
  for (let i = 0; i < source.length; i += 1) {
    if (source.charCodeAt(i) !== 0x2026) return false;
  }
  for (let j = 0; j < display!.length; j += 1) {
    if (display!.charCodeAt(j) !== 0x22ef) return false;
  }
  return true;
}

/**
 * ContextualWebCurlyQuoteFeatures: the common classifier has already resolved
 * whether a shared curly quote belongs to Latin or CJK context. The browser
 * adapter requests proportional forms only for the Latin decision and reports
 * that feature list in GlyphRun so DOM paint can replay the same measurement.
 *
 * @param {string} role
 * @param {string} display
 * @returns {string[]}
 */
function contextualWebOpenTypeFeatures(role: string, display: string): string[] {
  if (role === "LatinText") {
    for (let i = 0; i < display.length; i += 1) {
      const code = display.charCodeAt(i);
      if (code >= 0x2018 && code <= 0x201d) return ["pwid", "palt"];
    }
  }
  return [];
}

/**
 * `SubpixelCanvasInkOverhangClamp`: Canvas `actualBoundingBox*` is rasterizer
 * evidence rather than an outline bound. Firefox can offset the reported CJK
 * punctuation box by one CSS pixel even when Canvas and DOM advances are
 * identical, leaving a subpixel edge as a false glyph overhang. Feeding that
 * noise into the fitted punctuation body can falsely enlarge it and make the
 * DOM replay under-compress punctuation gaps.
 *
 * Keep real overhangs of one CSS pixel or more (italic and synthetic-slant
 * safety still applies). Only clamp smaller excursions back to the measured
 * advance box; the named adjustment is copied into the shaping decision.
 *
 * @param {RectLike} bounds
 * @param {number} advance
 * @returns {{ bounds: RectLike, adjustment: (string|null) }}
 */
function normalizeSubpixelCanvasInkOverhang(bounds: RectLike, advance: number): ShapingOverhangNormalization {
  const leftOverhang = Math.max(-bounds.left, 0);
  const rightOverhang = Math.max(bounds.right - advance, 0);
  const clampLeft = leftOverhang > 0 && leftOverhang < CANVAS_INK_OVERHANG_EVIDENCE_THRESHOLD_PX;
  const clampRight = rightOverhang > 0 && rightOverhang < CANVAS_INK_OVERHANG_EVIDENCE_THRESHOLD_PX;
  if (!clampLeft && !clampRight) return { bounds: bounds, adjustment: null };

  let adjustment = "SubpixelCanvasInkOverhangClamp";
  if (clampLeft) adjustment += "(left=" + leftOverhang + ")";
  if (clampRight) adjustment += "(right=" + rightOverhang + ")";
  return {
    bounds: {
      left: clampLeft ? 0 : bounds.left,
      top: bounds.top,
      right: clampRight ? advance : bounds.right,
      bottom: bounds.bottom,
    },
    adjustment: adjustment,
  };
}

function buildCssFont(style: string, fontWeight: number, size: number, family: string): string {
  return style + " " + fontWeight + " " + size + "px " + family;
}

function hasUsableAdvance(advance: number): boolean {
  return Number.isFinite(advance) && advance > ZERO_ADVANCE_EPSILON;
}

/**
 * Create an OffscreenMeasureTextShaping adapter (ADR 0039) that MEASURES
 * with an offscreen 2D canvas — `measureText` for advance, `TextMetrics`
 * ink-box extents for ink bounds — and never rasterizes to screen (that is
 * the DOM renderer's job). The measuring fonts MUST be the same instance the
 * DOM renderer draws with.
 *
 * @param {WebFontFamiliesInstance} fonts
 * @param {{ status: string, detail: (string|null) }} cjkDashCapability
 * @param {CanvasShapingEnv} env
 * @returns {{ shape: (input: ShapeInput) => Object }}
 */
export function createTextShaper(
  fonts: WebFontFamiliesInstance,
  cjkDashCapability: CjkDashCapability | null,
  env: CanvasShapingEnv,
): CanvasTextShaperInstance {
  let currentCanvasFont: string | null = null;
  let measureCtx: CanvasContextLike | null = null;
  let inkProbeCtx: CanvasContextLike | null = null;
  let parityMeasureProbe: ProbeElementLike | null = null;
  let featureMeasureProbe: ProbeElementLike | null = null;

  function getMeasureCtx(): CanvasContextLike {
    if (!measureCtx) measureCtx = env.createCanvasContext();
    return measureCtx;
  }

  function getInkProbeCtx(): CanvasContextLike {
    if (!inkProbeCtx) inkProbeCtx = env.createCanvasContext();
    return inkProbeCtx;
  }

  // A dedicated probe element: sharing featureMeasureProbe with the
  // curly-quote feature measurement would leave each function's style pins
  // visible to the other in browsers whose font shorthand does not reset
  // every longhand. Kerning is left at the browser default (auto) to match
  // how non-canonical paragraphs paint; canonical paragraphs pin normal,
  // which resolves identically in the engines this gate serves.
  function getParityMeasureProbe(): ProbeElementLike {
    if (!parityMeasureProbe) {
      parityMeasureProbe = env.createProbeElement();
      parityMeasureProbe.setAttribute("aria-hidden", "true");
      parityMeasureProbe.style.setProperty("position", "absolute", "important");
      parityMeasureProbe.style.setProperty("left", "-100000px", "important");
      parityMeasureProbe.style.setProperty("visibility", "hidden", "important");
      parityMeasureProbe.style.setProperty("white-space", "pre", "important");
    }
    return parityMeasureProbe;
  }

  function getFeatureMeasureProbe(): ProbeElementLike {
    if (!featureMeasureProbe) {
      featureMeasureProbe = env.createProbeElement();
      featureMeasureProbe.setAttribute("aria-hidden", "true");
      featureMeasureProbe.style.setProperty("position", "absolute", "important");
      featureMeasureProbe.style.setProperty("left", "-100000px", "important");
      featureMeasureProbe.style.setProperty("top", "0", "important");
      featureMeasureProbe.style.setProperty("visibility", "hidden", "important");
      featureMeasureProbe.style.setProperty("white-space", "pre", "important");
      featureMeasureProbe.style.setProperty("margin", "0", "important");
      featureMeasureProbe.style.setProperty("padding", "0", "important");
      featureMeasureProbe.style.setProperty("border", "0", "important");
      env.attachProbe(featureMeasureProbe);
    }
    return featureMeasureProbe;
  }

  function measureViaHiddenDom(display: string, cssFont: string): number {
    const probe = getParityMeasureProbe();
    env.attachProbe(probe);
    probe.textContent = display;
    probe.style.setProperty("font", cssFont, "important");
    return probe.getBoundingClientRect().width;
  }

  function measureProportionalCurlyQuote(display: string, cssFont: string): number {
    const probe = getFeatureMeasureProbe();
    env.attachProbe(probe);
    probe.textContent = display;
    probe.style.setProperty("font", cssFont, "important");
    probe.style.setProperty("font-variant-east-asian", "proportional-width", "important");
    probe.style.setProperty("font-feature-settings", "\"palt\" 1", "important");
    return probe.getBoundingClientRect().width;
  }

  /**
   * `DegenerateCanvasInkBoundsProbe`: WebKit's `actualBoundingBox*`
   * mirrors the advance box for CJK text, which drives the
   * ink-containment floor to the full cell and disables every compression
   * decision. Probe U+3002 (real ink is a corner dot) once per resolved
   * font; degenerate fonts get their punctuation ink measured through
   * `RasterizedInkBoundsMeasure` below instead.
   *
   * @param {string} actualFont
   * @returns {boolean}
   */
  function canvasInkBoundsDegenerate(actualFont: string): boolean {
    const degenerateInkBoundsByFont = globalServices().measurement.degenerateInkBoundsByFont;
    if (Object.prototype.hasOwnProperty.call(degenerateInkBoundsByFont, actualFont)) {
      return degenerateInkBoundsByFont[actualFont];
    }
    const probe = getMeasureCtx().measureText(DEGENERATE_INK_PROBE_TEXT);
    const advance = probe.width;
    const verdict = advance > 0 &&
      Math.abs(probe.actualBoundingBoxLeft) <= DEGENERATE_INK_EPSILON_PX &&
      Math.abs(probe.actualBoundingBoxRight - advance) <= DEGENERATE_INK_EPSILON_PX;
    degenerateInkBoundsByFont[actualFont] = verdict;
    return verdict;
  }

  /**
   * `RasterizedInkBoundsMeasure`: when the metrics API cannot be trusted,
   * draw the glyph into a scratch canvas at RASTER_INK_SCALE (advance
   * plus one em of overhang margin per side) and scan alpha for the true
   * ink extents on both axes. One rasterization per (font, glyph), shared
   * through the measurement cache.
   *
   * @param {string} display
   * @param {number} advance
   * @param {number} fontSizePx
   * @returns {(RectLike|null)}
   */
  function rasterizedInlineInkBounds(display: string, advance: number, fontSizePx: number): RectLike | null {
    const scale = RASTER_INK_SCALE;
    const margin = fontSizePx;
    const width = Math.max(Math.floor((advance + 2 * margin) * scale), 1);
    const height = Math.max(Math.floor(fontSizePx * 2.0 * scale), 1);
    const canvas = getInkProbeCtx().canvas;
    if (canvas.width < width) canvas.width = width;
    if (canvas.height < height) canvas.height = height;
    const probeCtx = getInkProbeCtx();
    probeCtx.setTransform(1, 0, 0, 1, 0, 0);
    probeCtx.clearRect(0, 0, canvas.width, canvas.height);
    probeCtx.setTransform(scale, 0, 0, scale, margin * scale, fontSizePx * 1.25 * scale);
    if (currentCanvasFont == null) return null;
    probeCtx.font = currentCanvasFont;
    probeCtx.fillText(display, 0, 0);
    let image: ImageData | ShapingImageDataLike | undefined;
    try {
      image = probeCtx.getImageData(0, 0, width, height);
    } catch (error) {
      return null;
    }
    const data = image!.data;
    let minX = -1;
    let maxX = -1;
    const stride = width * 4;
    for (let x = 0; x < width; x += 1) {
      let inked = false;
      let offset = x * 4 + 3;
      const end = height * stride;
      while (offset < end) {
        if (data[offset] !== 0) {
          inked = true;
          break;
        }
        offset += stride;
      }
      if (!inked) continue;
      if (minX < 0) minX = x;
      maxX = x;
    }
    if (minX < 0) return null;
    // Vertical extents come from the same pixels: scan rows across the
    // inked column range so the block axis is measured, not assumed.
    let minY = -1;
    let maxY = -1;
    for (let y = 0; y < height; y += 1) {
      let rowInked = false;
      let rowOffset = y * stride + minX * 4 + 3;
      const rowEnd = y * stride + (maxX + 1) * 4;
      while (rowOffset < rowEnd) {
        if (data[rowOffset] !== 0) {
          rowInked = true;
          break;
        }
        rowOffset += 4;
      }
      if (!rowInked) continue;
      if (minY < 0) minY = y;
      maxY = y;
    }
    const baseline = fontSizePx * 1.25;
    return {
      left: minX / scale - margin,
      top: minY / scale - baseline,
      right: (maxX + 1) / scale - margin,
      bottom: (maxY + 1) / scale - baseline,
    };
  }

  /**
   * `CanvasDomAdvanceParityGate`: Firefox resolves the font stack
   * differently between the canvas parser and DOM style, so `measureText`
   * can report Latin advances from a face the DOM never paints. Probe
   * each resolved font once (canvas vs hidden DOM, same string); past one
   * percent divergence every measurement for that font routes through the
   * hidden-DOM path, still keyed into the bounded measurement cache.
   *
   * @param {string} role
   * @param {string} cssFont
   * @param {string} actualFont
   * @returns {boolean}
   */
  function canvasAdvanceTrusted(role: string, cssFont: string, actualFont: string): boolean {
    // Gated by role, not code points: the divergence lives in the
    // Latin-side stack every non-CJK role shares. CJK roles are exempt —
    // their advances agree across parsers, a Latin probe string would
    // measure a CJK face's fallback instead of what the run paints, and
    // the exemption keeps CjkPunctuation on the canvas path where the
    // raster ink measurement stays reachable.
    if (role === "CjkText" || role === "CjkPunctuation") return true;
    const canvasAdvanceParityByFont = globalServices().measurement.canvasAdvanceParityByFont;
    if (Object.prototype.hasOwnProperty.call(canvasAdvanceParityByFont, actualFont)) {
      return canvasAdvanceParityByFont[actualFont];
    }
    const canvasWidth = getMeasureCtx().measureText(ADVANCE_PARITY_PROBE_TEXT).width;
    const domWidth = measureViaHiddenDom(ADVANCE_PARITY_PROBE_TEXT, cssFont);
    const verdict = domWidth <= 0 ||
      Math.abs(canvasWidth - domWidth) <=
        domWidth * ADVANCE_PARITY_RELATIVE_EPSILON + ADVANCE_PARITY_ABSOLUTE_EPSILON_PX;
    canvasAdvanceParityByFont[actualFont] = verdict;
    return verdict;
  }

  /**
   * Measure one display string for one CSS font and role, cached under the
   * shared measurement cache. The four branches in order: hidden-DOM parity
   * gate, plain measureText (curly-quote signature measured through the
   * feature probe), degenerate CjkPunctuation ink probe with optional
   * rasterized ink bounds, and the plain canvas ink box with subpixel
   * overhang normalization.
   *
   * @param {string} display
   * @param {string} cssFont
   * @param {string[]} openTypeFeatures
   * @param {string} role
   * @returns {MeasuredTextLike}
   */
  function measure(display: string, cssFont: string, openTypeFeatures: string[], role: string): MeasuredTextLike {
    const context = getMeasureCtx();
    if (cssFont !== currentCanvasFont) {
      context.font = cssFont;
      currentCanvasFont = context.font;
    }
    const actualFont = context.font;
    const featureSignature = openTypeFeatures.join(",");
    const cacheKey = measurementCacheKey(actualFont, display, featureSignature, role);
    return measurementCacheGetOrPut(cacheKey, function () {
      if (featureSignature !== PROPORTIONAL_CURLY_QUOTE_FEATURE_SIGNATURE &&
          !canvasAdvanceTrusted(role, cssFont, actualFont)) {
        return {
          advance: measureViaHiddenDom(display, cssFont),
          bounds: null,
          requestedFont: cssFont,
          actualFont: actualFont,
          boundsAdjustment: "CanvasDomAdvanceParityGate",
        };
      }
      const m = context.measureText(display);
      let advance: number;
      if (featureSignature === PROPORTIONAL_CURLY_QUOTE_FEATURE_SIGNATURE) {
        advance = measureProportionalCurlyQuote(display, cssFont);
      } else {
        advance = m.width;
      }
      if (role === "CjkPunctuation" && canvasInkBoundsDegenerate(actualFont)) {
        const match = FONT_PX_SIZE_REGEX.exec(actualFont);
        const parsedPx = match ? Number(match[1]) : Number.NaN;
        const fontSizePx = Number.isFinite(parsedPx) ? parsedPx : null;
        let rasterized: RectLike | null = null;
        if (fontSizePx != null && fontSizePx > 0) {
          rasterized = rasterizedInlineInkBounds(display, advance, fontSizePx);
        }
        return {
          advance: advance,
          bounds: rasterized,
          requestedFont: cssFont,
          actualFont: actualFont,
          boundsAdjustment: rasterized != null
            ? "DegenerateCanvasInkBoundsProbe;RasterizedInkBoundsMeasure"
            : "DegenerateCanvasInkBoundsProbe",
        };
      }
      const canvasBounds: RectLike = {
        left: -m.actualBoundingBoxLeft,
        top: -m.actualBoundingBoxAscent,
        right: m.actualBoundingBoxRight,
        bottom: m.actualBoundingBoxDescent,
      };
      let normalized: ShapingOverhangNormalization;
      if (role === "CjkPunctuation") {
        normalized = normalizeSubpixelCanvasInkOverhang(canvasBounds, advance);
      } else {
        normalized = { bounds: canvasBounds, adjustment: null };
      }
      return {
        advance: advance,
        bounds: normalized.bounds,
        requestedFont: cssFont,
        actualFont: actualFont,
        boundsAdjustment: normalized.adjustment,
      };
    });
  }

  function shapeWithCanvas(input: ShapeInput, capabilityIssue?: CapabilityIssue | null): CanvasShapingResult {
    const size = input.style.fontSize;
    const key = input.fontDecision.candidate.key;
    const source = input.text.substring(input.range.start, input.range.end);
    const display = input.displayText != null
      ? input.displayText
      : input.text.substring(input.range.start, input.range.end);

    const style = input.style.italic ? "italic" : "normal";
    const stacks = fonts.fallbackStacks(input.fontDecision.role, input.style.fontFamilies);
    const openTypeFeatures = contextualWebOpenTypeFeatures(input.fontDecision.role, display);
    let chosenIndex = 0;
    const requiresAdvance = display.length > 0 &&
      !display.includes("\n") && !display.includes("\r");
    let measured = measure(
      display,
      buildCssFont(style, input.style.fontWeight, size, stacks[0]),
      openTypeFeatures,
      input.fontDecision.role,
    );
    if (requiresAdvance && !hasUsableAdvance(measured.advance)) {
      for (let index = 1; index < stacks.length; index += 1) {
        const candidate = measure(
          display,
          buildCssFont(style, input.style.fontWeight, size, stacks[index]),
          openTypeFeatures,
          input.fontDecision.role,
        );
        measured = candidate;
        chosenIndex = index;
        if (hasUsableAdvance(candidate.advance)) break;
      }
    }
    const advance = measured.advance;
    const bounds = measured.bounds;

    const cluster: ShapingCluster = {
      range: input.range,
      text: source,
      displayText: display,
      fontKey: key,
      advance: advance,
    };
    const glyph: ShapingGlyph = {
      id: 0,
      clusterRange: input.range,
      advance: advance,
      x: 0,
      bounds: bounds,
    };
    const run: ShapingGlyphRun = {
      range: input.range,
      fontKey: key,
      glyphs: [glyph],
      advance: advance,
      openTypeFeatures: openTypeFeatures,
    };
    let reason = "web-canvas-measureText" +
      "; stackIndex=" + chosenIndex +
      "; requestedFont=" + measured.requestedFont +
      "; actualFont=" + measured.actualFont;
    if (measured.boundsAdjustment != null) {
      reason += "; inkBounds=" + measured.boundsAdjustment;
    }
    if (openTypeFeatures.length > 0) {
      reason += "; features=" + openTypeFeatures.join(",") + "; featureMeasure=HiddenDomRange";
    }
    if (capabilityIssue != null) {
      reason += "; " + capabilityIssue.detail;
    }
    const decision: ShapingDecision = {
      range: input.range,
      sourceText: source,
      displayText: display,
      fontKey: key,
      glyphCount: 1,
      advance: advance,
      source: "OffscreenMeasureTextShaping",
      reason: reason,
      glyphsWithoutInkBounds: bounds == null ? 1 : 0,
      capabilityIssue: capabilityIssue != null ? capabilityIssue.name : null,
      featureEvidence: openTypeFeatures.length > 0 ? openTypeFeatures.join(",") : null,
    };
    return { clusters: [cluster], glyphRuns: [run], decisions: [decision] };
  }

  function dashCapabilityIssue(): CapabilityIssue {
    const status = cjkDashCapability != null ? cjkDashCapability.status : null;
    const detail = cjkDashCapability != null ? cjkDashCapability.detail : null;
    return {
      name: dashIssueNameFor(status),
      detail: dashIssueDetailFor(status, detail),
    };
  }

  /**
   * Shape one segment, mirroring ShapingResult: ellipsis display
   * substitution carries the UNVERIFIED issue pair, a CJK dash source (or
   * the two-em-dash display) carries the CjkDashCapabilityPolicy issue, and
   * everything else shapes plainly.
   *
   * @param {ShapeInput} input
   * @returns {Object}
   */
  function shape(input: ShapeInput): CanvasShapingResult {
    const source = input.text.substring(input.range.start, input.range.end);
    if (isUnverifiedEllipsisDisplaySubstitution(source, input.displayText)) {
      return shapeWithCanvas(input, {
        name: UNVERIFIED_DISPLAY_SUBSTITUTION_COVERAGE_ISSUE,
        detail: CANVAS_CANNOT_VERIFY_SAME_FACE_U22EF_COVERAGE,
      });
    }
    if (source === CJK_DASH_SOURCE || input.displayText === TWO_EM_DASH) {
      return shapeWithCanvas(input, dashCapabilityIssue());
    }
    return shapeWithCanvas(input);
  }

  return { shape: shape };
}
