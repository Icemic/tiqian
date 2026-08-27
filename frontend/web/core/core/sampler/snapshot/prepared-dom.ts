// Prepared-DOM value-style rendering (ADR 0053 batch 6). Moved verbatim
// from the package root; root paths remain as compatibility re-export shims.

import {
  LAYOUT_REVISION,
  RENDER_REVISION,
  SNAPSHOT_SCHEMA,
} from "./snapshot-schema.js";
import {
  normalizeLiveSemantics,
  normalizeSnapshotSemantics,
} from "./snapshot-source.js";
import type {
  LiveSemanticSpan,
  SnapshotSemanticSpan,
} from "./snapshot-source.js";
import type {
  MarkupArtifact,
  MarkupAttributes,
  MarkupContainer,
  MarkupNode,
} from "./prepared-dom-markup.js";
import {
  applyDynamicStyles,
  cssString,
  px,
  renderedContainer,
  renderedElement,
  renderedText,
} from "./prepared-dom-markup.js";
import type {
  InlineObjectPlaceholderCell,
  RubyAnnotation,
  BopomofoZone,
  EvidenceOverlayPlan,
  EvidenceOverlayOptions,
  EvidenceDecorationSegment,
  EvidenceEmphasisDot,
} from "./prepared-dom-evidence.js";
import {
  appendEvidenceOverlays,
  bopomofoAnnotationSpan,
  inlineObjectPlaceholder,
  rubyAnnotationSpan,
} from "./prepared-dom-evidence.js";
import type { EnhancedElementContext } from "../../engine/context/enhance-context.js";

// --- Internal types ---

/** Style owner key: either a host Element or the snapshot sentinel marker. */
type StyleOwner = Element | typeof SNAPSHOT_STYLE_OWNER;

/** Style delta from the prepared layout plan cell. */
interface StyleDelta {
  fontSize?: number;
  fontWeight?: number;
  italic?: boolean;
}

/** Inline edge entry from the prepared layout plan. */
interface InlineEdgeEntry {
  offset: number;
  inlineStart?: number;
  inlineEnd?: number;
}

/** QuerySelectorAll function signature. */
type QuerySelectorAllFn = (sel: string) => NodeListOf<Element>;

/** Host element shape for renderPreparedParagraphInto. */
interface PreparedParagraphHost extends Element {
  innerHTML: string;
  querySelectorAll: QuerySelectorAllFn;
}

/** Bridge candidate shape for revision probing. */
interface BridgeCandidateShape {
  version?: unknown;
  semanticReplayRevision?: unknown;
}

/** Style class mapper callback. */
type StyleClassForFn = (declaration: string) => string;

/** Snapshot semantic span option entry. */
interface SnapshotSemanticOption {
  start: number;
  end: number;
  sourceIndex: number;
  tagName: string;
  attributes: [string, string][];
  order?: number;
}

/** Emphasis dot color resolver callback. */
type EmphasisDotColorFn = (offset: number) => string | null;

/** Prepared style scope state held per root element. */
export interface PreparedStyleState {
  root: Element;
  scope: string;
  styleElement: HTMLStyleElement;
  originalScope: string | null;
  declarations: string[];
  indexes: Map<string, number>;
  owners: Map<StyleOwner, Set<number>>;
  dirty: boolean;
}

export {};


/** Spacing classification for a prepared run. */
interface PreparedSpacingResult {
  kind: string;
  px: number;
}

/** Cell shape from the prepared layout plan JSON. */
interface PreparedLayoutPlanCell {
  rangeStart: number;
  rangeEnd: number;
  source: string;
  display: string;
  drawX: number;
  naturalWidth: number;
  shapingBoundary?: boolean;
  openTypeFeatures?: string[];
  style?: StyleDelta | null;
  latin?: boolean;
  dashStrategy?: string | null;
  shapingLanguage?: string | null;
  resolvedFace?: string | null;
  glyphIds?: string | null;
  shapingEvidence?: string | null;
  punctuationInkFloor?: number | null;
  punctuationBodyWidth?: number | null;
  renderFontFamily?: string | null;
  inlineObject?: number | null;
  advance?: number | null;
  leadingLayoutAdvance?: number;
}

/** Line shape from the prepared layout plan JSON. */
interface PreparedLayoutPlanLine {
  cells: PreparedLayoutPlanCell[];
  top: number;
  bottom: number;
  baseline: number;
  indent: number;
  visualWidth: number;
  hyphenAdvance: number;
  endReason: string;
  rangeStart: number;
  rangeEnd: number;
}

/** Ruby annotation with plan-specific base range. */
interface PlanRubyAnnotation extends RubyAnnotation {
  baseRangeEnd: number;
}

/** Bopomofo zone with plan-specific base range. */
interface PlanBopomofoZone extends BopomofoZone {
  baseRangeEnd: number;
}

/** The canonical prepared layout plan JSON shape. */
interface PreparedLayoutPlan {
  schema: number;
  layoutRevision: string;
  height: number;
  lines: PreparedLayoutPlanLine[];
  emphasisRanges?: [number, number][];
  rubyDecisions?: PlanRubyAnnotation[];
  bopomofoDecisions?: PlanBopomofoZone[];
  inlineEdges?: InlineEdgeEntry[];
  decorationSegments?: EvidenceDecorationSegment[];
  emphasisDots?: EvidenceEmphasisDot[];
  fontSize?: number;
  overlayWidth?: number;
}

/** Options bag for renderPreparedParagraphArtifact and renderPreparedParagraphInto. */
interface PreparedParagraphRenderOptions {
  styleClassFor?: StyleClassForFn | null;
  semanticReplay?: string;
  sourceText?: string;
  semantics?: SnapshotSemanticOption[];
  liveSemanticElements?: Element[];
  renderTextSpans?: RenderTextSpanEntry[];
  cjkStrongSemantics?: CjkStrongSemanticEntry[];
  inlineBoxes?: InlineBoxEntry[];
  emphasisDotColor?: EmphasisDotColorFn | null;
  inlineObjects?: InlineObjectOptionEntry[];
  evidenceOverlay?: EvidenceOverlayOptions;
}

/** Return value of renderPreparedParagraphArtifact. */
interface PreparedParagraphArtifactResult {
  readonly html: string;
  readonly artifact: MarkupArtifact[];
  readonly liveSemanticCount: number;
  readonly inlineObjectCount: number;
  readonly markerCount: number;
}

/** Return value of renderPreparedParagraphInto. */
interface PreparedParagraphIntoResult {
  readonly html: string;
  readonly markers: Element[];
}

/** Run object built during prepared paragraph rendering. */
interface PreparedRun {
  rangeStart: number;
  rangeEnd: number;
  source: string;
  display: string;
  drawX: number;
  naturalWidth: number;
  shapingBoundary: boolean;
  openTypeFeatures?: string[];
  renderFontFamilies: string[];
  trailingGap: number;
  spacing: PreparedSpacingResult;
  semanticPath: (SnapshotSemanticSpan | LiveSemanticSpan)[];
  styleDelta: StyleDelta | null;
  italicEffect: boolean;
  dashStrategy: string | null;
  shapingLanguage: string | null;
  resolvedFace: string | null;
  glyphIds: string | null;
  shapingEvidence: string | null;
  punctuationInkFloor: number | null;
  punctuationBodyWidth: number | null;
  evidenceRenderFontFamily: string | null;
  inlineObjectAdvance: number | null;
  bopomofoAdvanceWidth: number;
  styleSignature: string;
  punctuationSignature: string;
  semanticSignature?: string;
}

/** Inline box entry from plan or options. */
interface InlineBoxEntry {
  start: number;
  end: number;
  inlineStartPx: number;
  inlineEndPx: number;
}

/** Render text span entry from options. */
interface RenderTextSpanEntry {
  start: number;
  end: number;
  fontFamilies: string[];
}

/** CJK strong emphasis entry. */
interface CjkStrongSemanticEntry {
  start: number;
  end: number;
  weight: number;
}

/** Inline object entry from options. */
interface InlineObjectOptionEntry {
  start: number;
  end: number;
  element?: Element;
  marginRight?: number;
}

/** Child entry in the per-line ordered children array. */
interface LineChildRun {
  readonly kind: "run";
  run: PreparedRun;
  semanticPath: readonly (SnapshotSemanticSpan | LiveSemanticSpan)[];
}
interface LineChildInlineObject {
  readonly kind: "inlineObject";
  cell: PreparedLayoutPlanCell;
  carrierMargin: number;
  semanticPath: readonly (SnapshotSemanticSpan | LiveSemanticSpan)[];
}
interface LineChildRuby {
  readonly kind: "ruby";
  ruby: RubyAnnotation;
  lineTop: number;
  semanticPath: readonly (SnapshotSemanticSpan | LiveSemanticSpan)[];
}
interface LineChildBopomofo {
  readonly kind: "bopomofo";
  z: BopomofoZone;
  width: number;
  lineTop: number;
  lineHeight: number;
  semanticPath: readonly (SnapshotSemanticSpan | LiveSemanticSpan)[];
}
type LineChildEntry = LineChildRun | LineChildInlineObject | LineChildRuby | LineChildBopomofo;

const SPACING_EPSILON = 0.01;
// FloatDustSpacingZeroing: justification can leave real stretch of a few
// thousandths of a pixel per cluster boundary; zeroing those breaks the line
// flow identity once they accumulate. Only arithmetic dust zeroes here.
// SPACING_EPSILON still gates merge tolerance and carrier visibility.
const SPACING_DUST_EPSILON = 1e-6;
const RENDER_FLOW_EPSILON_PX = 0.01;
const DEFAULT_LOCALE = "zh-Hans";
const LINE_MARKER_SELECTOR = "[data-tq-line-flow-width]";
const ROOT_SELECTOR = "tiqian-prose, [data-tiqian-root]";
const VALUE_STYLE_SCOPE_ATTRIBUTE = "data-tq-value-style-scope";
const VALUE_STYLE_ELEMENT_ATTRIBUTE = "data-tq-prepared-value-styles";
const LIVE_SEMANTIC_INDEX_ATTRIBUTE = "data-tq-live-semantic-index";
// Snapshot style owner sentinel. A string marker: host Elements never
// compare equal to it, so owner discrimination is unchanged.
const SNAPSHOT_STYLE_OWNER = "tiqian-snapshot-style-owner";

function preparedPlan(value: string | PreparedLayoutPlan): PreparedLayoutPlan {
  return typeof value === "string" ? JSON.parse(value) : value;
}

function preparedLocale(value: string | Record<string, unknown>): string {
  if (typeof value === "string") return value;
  return String(value?.locale ?? DEFAULT_LOCALE);
}

function snapshotValueStyleClass(index: number) {
  return `tqv-${index.toString(36)}`;
}

// RuntimeValueStyleContentAddressing: runtime lowering must mint the same
// class name for the same declaration in every session, so a fresh one-shot
// enhance over a root reproduces the coordinated DOM byte for byte even
// though the coordinated registry still holds declarations from earlier
// widths. The snapshot namespace (tqv-) stays index based because frozen
// snapshot tables persist those indexes across builds. Two independent
// FNV-1a hashes make the name a pure function of the declaration string with
// collision odds far below any realistic declaration count, so no probe or
// registry state participates in naming.
function runtimeValueStyleKey(declaration: string) {
  let a = 0x811c9dc5;
  let b = 0x01000193;
  for (let i = 0; i < declaration.length; i++) {
    const code = declaration.charCodeAt(i);
    a ^= code;
    a = Math.imul(a, 0x01000193);
    b ^= code + i;
    b = Math.imul(b, 0x811c9dc5);
  }
  a >>>= 0;
  b >>>= 0;
  return `${a.toString(36)}${b.toString(36)}`;
}

function runtimeValueStyleClass(key: string) {
  return `tqvr-${key}`;
}

function createPreparedStyleState(root: Element, context: EnhancedElementContext) {
  const documentObject = root?.ownerDocument ?? globalThis.document;
  const parent = documentObject?.head ?? documentObject?.documentElement ?? documentObject?.body;
  if (!documentObject?.createElement || !parent?.appendChild || !root?.setAttribute) return null;
  const styleElement = documentObject.createElement("style");
  const scope = context.scope;
  styleElement.setAttribute(VALUE_STYLE_ELEMENT_ATTRIBUTE, scope);
  const originalScope = root.getAttribute(VALUE_STYLE_SCOPE_ATTRIBUTE);
  root.setAttribute(VALUE_STYLE_SCOPE_ATTRIBUTE, scope);
  parent.appendChild(styleElement);
  const state = {
    root,
    scope,
    styleElement,
    originalScope,
    declarations: [],
    indexes: new Map(),
    owners: new Map(),
    dirty: false,
  };
  context.preparedStyle = state;
  return state;
}

function preparedStyleState(root: Element, context: EnhancedElementContext) {
  if (context.preparedStyle) return context.preparedStyle;
  return createPreparedStyleState(root, context);
}

function registerPreparedValueStyle(state: PreparedStyleState, declaration: string) {
  const existing = state.indexes.get(declaration);
  if (existing != null) return existing;
  const index = state.declarations.length;
  state.declarations.push(declaration);
  state.indexes.set(declaration, index);
  state.dirty = true;
  return index;
}

function syncPreparedValueStyles(state: PreparedStyleState) {
  if (!state.dirty) return;
  const rootScope = `[${VALUE_STYLE_SCOPE_ATTRIBUTE}="${state.scope}"]`;
  const snapshotValuesActive = state.owners.has(SNAPSHOT_STYLE_OWNER);
  const runtimeValuesActive = Array.from(state.owners.keys()).some((owner) =>
    owner !== SNAPSHOT_STYLE_OWNER);
  state.styleElement.textContent = state.declarations.map((declaration: string, index: number) => {
    // PreparedValueNamespaceIsolation: build-time snapshot CSS remains live in
    // the document so it can restore exact first-paint nodes. Runtime lowering
    // must use a distinct class namespace; otherwise the same compact index can
    // combine unrelated important properties (for example snapshot
    // letter-spacing plus runtime margin-right) even when the scoped runtime
    // rule has higher specificity.
    const snapshotRule = snapshotValuesActive
      ? `${rootScope} [data-tq-rendered="true"] .${snapshotValueStyleClass(index)}{${declaration}}`
      : "";
    const runtimeRule = runtimeValuesActive
      ? `${rootScope}[${VALUE_STYLE_SCOPE_ATTRIBUTE}] [data-tq-rendered="true"] .${runtimeValueStyleClass(runtimeValueStyleKey(declaration))}{${declaration}}`
      : "";
    return snapshotRule + runtimeRule;
  }).join("");
  state.dirty = false;
}

function removePreparedStyleState(state: PreparedStyleState, context: EnhancedElementContext) {
  context.preparedStyle = null;
  state.styleElement.remove?.();
  if (state.styleElement.parentNode) state.styleElement.parentNode.removeChild(state.styleElement);
  if (state.originalScope == null) state.root.removeAttribute(VALUE_STYLE_SCOPE_ATTRIBUTE);
  else state.root.setAttribute(VALUE_STYLE_SCOPE_ATTRIBUTE, state.originalScope);
}

/** Installs the compact snapshot's dynamic declarations before DOM adoption. */
export function installPreparedValueStyles(root: Element, context: EnhancedElementContext, declarations: string[], renderFontFamilies: string[] = []) {
  if (!Array.isArray(declarations)) throw new Error("InvalidPreparedValueStyles");
  if (!Array.isArray(renderFontFamilies) || renderFontFamilies.some((family) =>
    typeof family !== "string" || !family.trim())) throw new Error("InvalidPreparedRenderFontFamilies");
  releasePreparedValueStyleRoot(root, context);
  if (declarations.length === 0) return false;
  const state = preparedStyleState(root, context);
  if (!state) throw new Error("PreparedValueStyleHostUnavailable");
  try {
    const indexes = declarations.map((declaration, expectedIndex) => {
      if (typeof declaration !== "string" || !declaration) {
        throw new Error("InvalidPreparedValueStyleDeclaration");
      }
      const index = registerPreparedValueStyle(state, declaration);
      if (index !== expectedIndex) throw new Error("DuplicatePreparedValueStyleDeclaration");
      return index;
    });
    state.owners.set(SNAPSHOT_STYLE_OWNER, new Set(indexes));
    syncPreparedValueStyles(state);
    return true;
  } catch (error) {
    removePreparedStyleState(state, context);
    throw error;
  }
}

export function releasePreparedParagraphStyles(host: Element, context: EnhancedElementContext) {
  const state = context.preparedStyle;
  if (!state) return false;
  state.owners.delete(host);
  if (state.owners.size === 0) removePreparedStyleState(state, context);
  return true;
}

export function releasePreparedValueStyleRoot(root: Element, context: EnhancedElementContext) {
  const state = context.preparedStyle;
  if (!state) return false;
  removePreparedStyleState(state, context);
  return true;
}

export function releasePreparedStyleState(state: PreparedStyleState, context: EnhancedElementContext): void {
  removePreparedStyleState(state, context);
}

function preparedSpacing(display: string, naturalWidth: number, trailingGap: number) {
  if (Math.abs(trailingGap) < SPACING_DUST_EPSILON) return { kind: "none", px: 0 };
  // NegativeSingleCellFlowAdvance: browsers clamp the border-box width of a
  // one-character inline span at zero when negative letter-spacing exceeds the
  // glyph advance. Preserve the selectable source glyph at its natural width
  // and carry the overtake in margin-right, which is also how multi-character
  // overlap is represented. The line sentinel still verifies the total flow.
  if (display.length === 1 && naturalWidth + trailingGap >= 0) {
    return { kind: "letter", px: trailingGap };
  }
  if (trailingGap < 0) return { kind: "overlap", px: trailingGap };
  // MultiCharacterSelectableGapCarrier: this is one layout gap after the whole
  // shaping cluster, not tracking distributed through the word. A dedicated
  // selectable carrier preserves the uninterrupted shaping run and owns the
  // full flow advance; splitting off the final grapheme would break kerning.
  return { kind: "trailing-letter", px: trailingGap };
}

// The renderer replays exactly the feature sets the engine emits: Latin
// curly quotes shape proportional (pwid,palt), CJK-context curly quotes
// shape full-width (fwid, CjkContextCurlyQuoteFullWidthVariant). Any
// other signature has no CSS replay rule and must not be silently painted.
const PREPARED_OPEN_TYPE_FEATURE_SIGNATURES = new Set(["pwid,palt", "fwid"]);

function preparedFeatureSignature(run: PreparedRun) {
  return Array.from(run.openTypeFeatures ?? [], String).join(",");
}

function preparedRenderFontSignature(run: PreparedRun) {
  return Array.from(run.renderFontFamilies ?? [], String).join("\u001f");
}

function canMergePreparedRun(left: PreparedRun, right: PreparedRun) {
  if (left.rangeEnd !== right.rangeStart ||
      left.semanticSignature !== right.semanticSignature ||
      left.shapingBoundary || right.shapingBoundary ||
      preparedFeatureSignature(left) !== preparedFeatureSignature(right) ||
      preparedRenderFontSignature(left) !== preparedRenderFontSignature(right) ||
      left.dashStrategy != null || right.dashStrategy != null ||
      left.styleSignature !== right.styleSignature ||
      left.punctuationSignature !== right.punctuationSignature ||
      left.italicEffect !== right.italicEffect ||
      left.evidenceRenderFontFamily !== right.evidenceRenderFontFamily) {
    return false;
  }
  if (left.spacing.kind === "none" && right.spacing.kind === "none") return true;
  return left.spacing.kind === "letter" && right.spacing.kind === "letter" &&
    Math.abs(left.spacing.px - right.spacing.px) < SPACING_EPSILON;
}

function mergePreparedRun(left: PreparedRun, right: PreparedRun) {
  left.rangeEnd = right.rangeEnd;
  left.source += right.source;
  left.display += right.display;
  left.naturalWidth += right.naturalWidth;
  left.trailingGap += right.trailingGap;
}

function renderRun(run: PreparedRun, styleClassFor: StyleClassForFn | null) {
  const featureSignature = preparedFeatureSignature(run);
  const renderFontFamilies = Array.from(run.renderFontFamilies ?? [], String);
  const needsElement = run.shapingBoundary || featureSignature ||
    renderFontFamilies.length > 0 || run.source !== run.display || run.spacing.kind !== "none" ||
    run.styleDelta != null || run.italicEffect || run.evidenceRenderFontFamily != null ||
    run.dashStrategy != null || run.punctuationInkFloor != null;
  if (!needsElement) return renderedText(run.display);
  const attributes: Record<string, string | null> = {
    "data-tq-advance": String(
      run.spacing.kind === "letter" || run.spacing.kind === "trailing-letter"
        ? run.naturalWidth + run.trailingGap
        : run.naturalWidth,
    ),
    "data-tq-geometry": "true",
    "data-tq-x": String(run.drawX),
  };
  if (run.shapingBoundary || featureSignature) {
    attributes["data-tq-shaping-boundary"] = "";
  }
  if (featureSignature) {
    if (!PREPARED_OPEN_TYPE_FEATURE_SIGNATURES.has(featureSignature)) {
      throw new Error(`UnsupportedPreparedOpenTypeFeatures: ${featureSignature}`);
    }
    attributes["data-tq-open-type-features"] = featureSignature;
  }
  if (run.source !== run.display) attributes["data-tq-src"] = run.source;
  if (run.dashStrategy != null) {
    attributes["data-tq-dash-strategy"] = String(run.dashStrategy);
    attributes["data-tq-dash-advance"] = String(run.naturalWidth);
    if (run.evidenceRenderFontFamily != null) {
      attributes["data-tq-dash-font-family"] = String(run.evidenceRenderFontFamily);
    }
    if (run.resolvedFace != null) attributes["data-tq-dash-face"] = String(run.resolvedFace);
    if (run.glyphIds != null) attributes["data-tq-dash-glyph-ids"] = String(run.glyphIds);
    if (run.shapingEvidence != null) {
      attributes["data-tq-dash-evidence"] = String(run.shapingEvidence);
    }
    if (run.shapingLanguage != null) attributes.lang = String(run.shapingLanguage);
  }
  if (run.punctuationInkFloor != null) {
    attributes["data-tq-punctuation-ink-floor"] = String(run.punctuationInkFloor);
    if (run.punctuationBodyWidth != null) {
      attributes["data-tq-punctuation-body-width"] = String(run.punctuationBodyWidth);
    }
  }
  const styles = [];
  if (renderFontFamilies.length > 0) {
    attributes["data-tq-render-font-projection"] = "true";
    styles.push(`font-family:${renderFontFamilies.map(cssString).join(",")}!important`);
  }
  if (run.evidenceRenderFontFamily != null) {
    attributes["data-tq-render-font-projection"] = "true";
    styles.push(`font-family:${cssString(run.evidenceRenderFontFamily)}!important`);
  }
  if (run.italicEffect && run.styleDelta?.italic !== true) {
    styles.push("font-style:italic!important");
  }
  if (run.styleDelta?.fontSize != null) {
    styles.push(`font-size:${px(run.styleDelta.fontSize)}!important`);
  }
  if (run.styleDelta?.fontWeight != null) {
    styles.push(`font-weight:${run.styleDelta.fontWeight}!important`);
  }
  if (run.styleDelta?.italic === true) styles.push("font-style:italic!important");
  else if (run.styleDelta?.italic === false) styles.push("font-style:normal!important");
  if (run.spacing.kind === "letter") {
    styles.push(`letter-spacing:${px(run.spacing.px)}!important`);
  } else if (run.spacing.kind === "overlap") {
    styles.push(`margin-right:${px(run.spacing.px)}!important`);
  }
  applyDynamicStyles(attributes, styles, styleClassFor);
  if (run.spacing.kind === "trailing-letter") {
    const container = renderedContainer("span", attributes);
    container.children.push(renderedText(run.display));
    const carrierAttributes = {
      "aria-hidden": "true",
      "data-tq-copy-ignore": "true",
      "data-tq-geometry": "true",
      "data-tq-spacing-carrier": "true",
    };
    applyDynamicStyles(
      carrierAttributes,
      [
        "display:inline-block!important",
        `inline-size:${px(run.spacing.px)}!important`,
        "height:0!important",
        "line-height:0!important",
        `letter-spacing:${px(run.spacing.px)}!important`,
        "overflow:hidden!important",
        "vertical-align:baseline!important",
        "white-space:pre!important",
      ],
      styleClassFor,
    );
    container.children.push(renderedElement("span", carrierAttributes, "\u00A0"));
    return container;
  }
  return renderedElement("span", attributes, run.display);
}

/**
 * Lowers the canonical prepared-layout wire format to the sparse DOM wire used
 * by both build-time snapshots and browser runtime rendering.
 */
export function renderPreparedParagraphArtifact(
  planOrJson: string | PreparedLayoutPlan,
  typographyOrLocale: string | Record<string, unknown> = DEFAULT_LOCALE,
  options: PreparedParagraphRenderOptions = {},
): PreparedParagraphArtifactResult {
  const plan = preparedPlan(planOrJson);
  const locale = preparedLocale(typographyOrLocale);
  const styleClassFor = typeof options.styleClassFor === "function" ? options.styleClassFor : null;
  if (plan?.schema !== SNAPSHOT_SCHEMA || plan?.layoutRevision !== LAYOUT_REVISION) {
    throw new Error("UnsupportedPreparedLayoutRevision");
  }
  const paragraphHeight = Number(plan.height);
  if (!Number.isFinite(paragraphHeight) || paragraphHeight < 0 || !Array.isArray(plan.lines)) {
    throw new Error("InvalidPreparedParagraphGeometry");
  }
  const sourceText = plan.lines.flatMap((line) => line.cells).map((cell) => cell.source).join("");
  const semanticReplay = options.semanticReplay ?? "snapshot-safe";
  if (semanticReplay !== "snapshot-safe" && semanticReplay !== "live-source") {
    throw new Error(`UnsupportedPreparedSemanticReplay:${semanticReplay}`);
  }
  const liveSemanticElements = Array.from(options.liveSemanticElements ?? []);
  const liveSemantics = semanticReplay === "live-source"
    ? normalizeLiveSemantics(options.sourceText ?? sourceText, options.semantics ?? [])
    : null;
  const semantics = liveSemantics ??
    normalizeSnapshotSemantics(options.sourceText ?? sourceText, options.semantics ?? []);
  if (liveSemantics) {
    const seenSourceIndices = new Set<number>();
    for (const semantic of liveSemantics) {
      const sourceElement = liveSemanticElements[semantic.sourceIndex];
      if (!sourceElement || typeof sourceElement.cloneNode !== "function" ||
          String(sourceElement.tagName ?? "").toLowerCase() !== semantic.tagName) {
        throw new Error(`LiveSemanticSourceMismatch:${semantic.sourceIndex}:${semantic.tagName}`);
      }
      if (seenSourceIndices.has(semantic.sourceIndex)) {
        throw new Error(`DuplicateLiveSemanticSource:${semantic.sourceIndex}`);
      }
      seenSourceIndices.add(semantic.sourceIndex);
    }
  }
  const renderTextSpans = Array.from(options.renderTextSpans ?? [], (span) => {
    const start = Number(span?.start);
    const end = Number(span?.end);
    const fontFamilies = Array.from(span?.fontFamilies ?? [], String)
      .map((family) => family.trim())
      .filter(Boolean);
    if (!Number.isSafeInteger(start) || !Number.isSafeInteger(end) || start < 0 || end <= start ||
        end > (options.sourceText ?? sourceText).length || fontFamilies.length === 0) {
      throw new Error("InvalidPreparedRenderTextSpan");
    }
    return { start, end, fontFamilies };
  });
  const cjkStrongSemantics = Array.from(options.cjkStrongSemantics ?? []);
  const emphasisRanges = Array.from(plan.emphasisRanges ?? [], (range) => [
    Number(range[0]),
    Number(range[1]),
  ]);
  const rubyByBaseEnd = new Map();
  for (const ruby of plan.rubyDecisions ?? []) {
    rubyByBaseEnd.set(Number(ruby.baseRangeEnd), ruby);
  }
  const bopomofoByBaseEnd = new Map();
  for (const z of plan.bopomofoDecisions ?? []) {
    const end = Number(z.baseRangeEnd);
    if (!bopomofoByBaseEnd.has(end)) bopomofoByBaseEnd.set(end, []);
    bopomofoByBaseEnd.get(end).push(z);
  }
  const planInlineEdges = Array.from(plan.inlineEdges ?? []);
  const inlineBoxes = planInlineEdges.length > 0
    ? planInlineEdges.flatMap((edge) => {
        const entries = [];
        if (edge.inlineStart != null) {
          entries.push({ start: Number(edge.offset), end: Number(edge.offset), inlineStartPx: Number(edge.inlineStart), inlineEndPx: 0 });
        }
        if (edge.inlineEnd != null) {
          entries.push({ start: Number(edge.offset), end: Number(edge.offset), inlineStartPx: 0, inlineEndPx: Number(edge.inlineEnd) });
        }
        return entries;
      })
    : Array.from(options.inlineBoxes ?? []);
  const inlineStartByOffset = new Map();
  const inlineEndByOffset = new Map();
  for (const box of inlineBoxes) {
    inlineStartByOffset.set(box.start, (inlineStartByOffset.get(box.start) ?? 0) + Number(box.inlineStartPx));
    inlineEndByOffset.set(box.end, (inlineEndByOffset.get(box.end) ?? 0) + Number(box.inlineEndPx));
  }
  const semanticSpansFor = (rangeStart: number, rangeEnd: number) => semantics.filter((span) =>
    rangeStart >= span.start && rangeEnd <= span.end);
  const semanticSpansCrossing = (offset: number) => semantics.filter((span) =>
    offset > span.start && offset < span.end);
  const renderFontFamiliesFor = (rangeStart: number, rangeEnd: number) => {
    const owners = renderTextSpans.filter((span) =>
      rangeStart >= span.start && rangeEnd <= span.end);
    if (owners.length === 0) return [];
    const signature = JSON.stringify(owners[0].fontFamilies);
    if (owners.some((span) => JSON.stringify(span.fontFamilies) !== signature)) {
      throw new Error("ConflictingPreparedRenderTextSpan");
    }
    return owners[0].fontFamilies;
  };
  const nodes: MarkupNode[] = [];
  let inlineObjectCount = 0;
  let activeSemantics: (SnapshotSemanticSpan | LiveSemanticSpan)[] = [];
  let activeContainers: MarkupContainer[] = [];
  const semanticContainerFor = (semanticPath: readonly (SnapshotSemanticSpan | LiveSemanticSpan)[]) => {
    const commonLimit = Math.min(activeSemantics.length, semanticPath.length);
    let common = 0;
    while (common < commonLimit && activeSemantics[common] === semanticPath[common]) common += 1;
    activeSemantics = activeSemantics.slice(0, common);
    activeContainers = activeContainers.slice(0, common);
    let container = activeContainers.at(-1)?.children ?? nodes;
    for (let index = common; index < semanticPath.length; index += 1) {
      const semantic = semanticPath[index];
      const cjkStrong = cjkStrongSemantics.find(
        (entry) => Number(entry?.start) === semantic.start && Number(entry?.end) === semantic.end,
      );
      const attributes = semanticReplay === "live-source"
        ? {
          [LIVE_SEMANTIC_INDEX_ATTRIBUTE]: String((semantic as LiveSemanticSpan).sourceIndex),
        }
        : {
          ...Object.fromEntries((semantic as SnapshotSemanticSpan).attributes),
          "data-tq-source-semantic": "true",
          ...(cjkStrong ? {
            "data-tq-cjk-emphasis": "true",
            style: `font-weight:${cjkStrong.weight}!important`,
          } : {}),
        };
      const wrapper = semanticReplay === "live-source"
        ? renderedContainer("span", attributes)
        : renderedContainer(semantic.tagName, attributes);
      container.push(wrapper);
      activeSemantics.push(semantic);
      activeContainers.push(wrapper);
      container = wrapper.children;
    }
    return container;
  };
  for (let lineIndex = 0; lineIndex < plan.lines.length; lineIndex += 1) {
    const line = plan.lines[lineIndex];
    const height = line.bottom - line.top;
    const first = line.cells[0];
    const flowStart = first ? first.drawX - first.leadingLayoutAdvance! : 0;
    const firstInlineStart = first ? inlineStartByOffset.get(first.rangeStart) ?? 0 : 0;
    if (first && Math.abs(first.leadingLayoutAdvance! - firstInlineStart) > RENDER_FLOW_EPSILON_PX) {
      throw new Error(`SnapshotRenderFlowMismatch:line=${lineIndex};leading-layout-advance`);
    }
    const cells: PreparedRun[] = line.cells.map((cell, index) => {
      const next = line.cells[index + 1];
      const trailingInlineEdge = inlineEndByOffset.get(cell.rangeEnd) ?? 0;
      const nextLeadingInlineEdge = next ? inlineStartByOffset.get(next.rangeStart) ?? 0 : 0;
      const rawTrailingGap = next
        ? next.drawX - cell.drawX - cell.naturalWidth - trailingInlineEdge - nextLeadingInlineEdge
        : line.hyphenAdvance > 0
          ? 0
          : line.indent + line.visualWidth - cell.drawX - cell.naturalWidth - trailingInlineEdge;
      const layoutTrailingGap = Math.abs(rawTrailingGap) < SPACING_DUST_EPSILON ? 0 : rawTrailingGap;
      const bopomofoAtEnd = bopomofoByBaseEnd.get(cell.rangeEnd) ?? null;
      const bopomofoAdvanceWidth = bopomofoAtEnd == null
        ? 0
        : next
          ? Math.max(layoutTrailingGap, 0)
          : Math.max(
              (cell.advance != null ? Number(cell.advance) : cell.naturalWidth) -
                cell.naturalWidth - trailingInlineEdge,
              0,
            );
      const trailingGap = bopomofoAtEnd == null ? layoutTrailingGap : 0;
      return {
        rangeStart: cell.rangeStart,
        rangeEnd: cell.rangeEnd,
        source: cell.source,
        display: cell.display,
        drawX: cell.drawX,
        naturalWidth: cell.naturalWidth,
        shapingBoundary: cell.shapingBoundary === true,
        openTypeFeatures: cell.openTypeFeatures,
        renderFontFamilies: renderFontFamiliesFor(cell.rangeStart, cell.rangeEnd),
        trailingGap,
        spacing: preparedSpacing(cell.display, cell.naturalWidth, trailingGap),
        semanticPath: semanticSpansFor(cell.rangeStart, cell.rangeEnd),
        styleDelta: cell.style ?? null,
        italicEffect: cell.style?.italic === true ||
          (cell.latin === true &&
            emphasisRanges.some(([start, end]) => cell.rangeStart >= start && cell.rangeStart < end)),
        dashStrategy: cell.dashStrategy ?? null,
        shapingLanguage: cell.shapingLanguage ?? null,
        resolvedFace: cell.resolvedFace ?? null,
        glyphIds: cell.glyphIds ?? null,
        shapingEvidence: cell.shapingEvidence ?? null,
        punctuationInkFloor: cell.punctuationInkFloor ?? null,
        punctuationBodyWidth: cell.punctuationBodyWidth ?? null,
        evidenceRenderFontFamily: cell.renderFontFamily ?? null,
        inlineObjectAdvance: cell.inlineObject ?? null,
        bopomofoAdvanceWidth,
        styleSignature: JSON.stringify(cell.style ?? null),
        punctuationSignature: JSON.stringify([
          cell.punctuationInkFloor ?? null,
          cell.punctuationBodyWidth ?? null,
        ]),
      };
    });
    for (const cell of cells) cell.semanticSignature = JSON.stringify(cell.semanticPath);
    // Ordered line children: runs merge as before, but inline-object cells and
    // annotation boundaries flush the pending run so DOM order is preserved.
    const children: LineChildEntry[] = [];
    let pendingRun: PreparedRun | null = null;
    const flushRun = () => {
      if (pendingRun == null) return;
      children.push({ kind: "run", run: pendingRun, semanticPath: pendingRun.semanticPath });
      pendingRun = null;
    };
    for (const cell of cells) {
      if (cell.inlineObjectAdvance != null) {
        flushRun();
        children.push({
          kind: "inlineObject",
          cell,
          carrierMargin: cell.trailingGap,
          semanticPath: cell.semanticPath,
        });
        inlineObjectCount += 1;
        continue;
      }
      const record = { ...cell, spacing: { ...cell.spacing } };
      if (pendingRun && canMergePreparedRun(pendingRun, record)) {
        mergePreparedRun(pendingRun, record);
      } else {
        flushRun();
        pendingRun = record;
      }
      const rubyAtEnd = rubyByBaseEnd.get(cell.rangeEnd) ?? null;
      const bopomofoAtEnd = bopomofoByBaseEnd.get(cell.rangeEnd) ?? null;
      if (rubyAtEnd != null || bopomofoAtEnd != null) flushRun();
      if (rubyAtEnd != null) {
        children.push({ kind: "ruby", ruby: rubyAtEnd, lineTop: line.top, semanticPath: cell.semanticPath });
      }
      for (const z of bopomofoAtEnd ?? []) {
        children.push({
          kind: "bopomofo",
          z,
          width: cell.bopomofoAdvanceWidth,
          lineTop: line.top,
          lineHeight: line.bottom - line.top,
          semanticPath: cell.semanticPath,
        });
      }
    }
    flushRun();

    const last = line.cells.at(-1);
    const flowEnd = last
      ? last.drawX + last.naturalWidth + (inlineEndByOffset.get(last.rangeEnd) ?? 0)
      : 0;
    const hyphenLeadingGap = line.hyphenAdvance > 0
      ? line.indent + line.visualWidth - flowEnd
      : 0;
    const inlineEdgeWidth = line.cells.reduce((sum: number, cell: PreparedLayoutPlanCell) =>
      sum + (inlineStartByOffset.get(cell.rangeStart) ?? 0) +
        (inlineEndByOffset.get(cell.rangeEnd) ?? 0), 0);
    const expectedFlowWidth = flowStart + inlineEdgeWidth + children.reduce((sum, child) => {
      if (child.kind === "run") return sum + child.run.naturalWidth + child.run.trailingGap;
      if (child.kind === "inlineObject") {
        return sum + child.cell.naturalWidth +
          (Math.abs(child.carrierMargin) >= SPACING_EPSILON ? child.carrierMargin : 0);
      }
      if (child.kind === "bopomofo") return sum + child.width;
      return sum; // ruby rides absolute positioning and takes no flow
    }, 0) + hyphenLeadingGap + line.hyphenAdvance;
    const coreLineWidth = line.indent + line.visualWidth + line.hyphenAdvance;
    if (Math.abs(expectedFlowWidth - coreLineWidth) > RENDER_FLOW_EPSILON_PX) {
      throw new Error(`SnapshotRenderFlowMismatch:line=${lineIndex}`);
    }
    const markerStyles = [
      `--tq-line-height:${px(height)}!important`,
      `--tq-line-baseline-offset:${px(-(line.bottom - line.baseline))}!important`,
    ];
    if (Math.abs(flowStart) >= SPACING_EPSILON) {
      markerStyles.push(`--tq-line-flow-start:${px(flowStart)}!important`);
    }
    const markerAttributes = {
      "aria-hidden": "true",
      class: "tq-line",
      "data-tq-copy-ignore": "true",
      "data-tq-geometry": "true",
      "data-tq-line-empty": String(line.cells.length === 0),
      "data-tq-line-end": line.endReason,
      "data-tq-line-top": String(line.top),
      "data-tq-line-bottom": String(line.bottom),
      "data-tq-line-baseline": String(line.baseline),
      "data-tq-line-flow-width": String(expectedFlowWidth),
      "data-tq-line-index": String(lineIndex),
      "data-tq-line-range": `${line.rangeStart}-${line.rangeEnd}`,
      "data-tq-line-shift": Math.abs(flowStart) >= SPACING_EPSILON ? "true" : null,
      "data-tq-line-width": String(coreLineWidth),
      "data-tq-paragraph-height": String(paragraphHeight),
    };
    applyDynamicStyles(markerAttributes, markerStyles, styleClassFor);
    semanticContainerFor(activeSemantics).push(renderedElement("span", markerAttributes));

    for (const child of children) {
      const container = semanticContainerFor(child.semanticPath);
      if (child.kind === "run") {
        container.push(renderRun(child.run, styleClassFor));
      } else if (child.kind === "inlineObject") {
        container.push(inlineObjectPlaceholder(child.cell, child.carrierMargin, styleClassFor));
      } else if (child.kind === "ruby") {
        container.push(rubyAnnotationSpan(child.ruby, child.lineTop, styleClassFor));
      } else {
        container.push(
          bopomofoAnnotationSpan(child.z, child.width, child.lineTop, child.lineHeight, styleClassFor),
        );
      }
    }

    if (line.hyphenAdvance > 0) {
      const hyphenAttributes = {
        "aria-hidden": "true",
        "data-tq-advance": String(line.hyphenAdvance),
        "data-tq-copy-ignore": "true",
        "data-tq-engine-hyphen": "true",
        "data-tq-geometry": "true",
        "data-tq-x": String(line.indent + line.visualWidth),
        lang: locale,
      };
      applyDynamicStyles(
        hyphenAttributes,
        Math.abs(hyphenLeadingGap) >= SPACING_EPSILON
          ? [`margin-left:${px(hyphenLeadingGap)}!important`]
          : [],
        styleClassFor,
      );
      semanticContainerFor(activeSemantics).push(renderedElement("span", hyphenAttributes, "-"));
    }
    const boundaryContainer = semanticContainerFor(semanticSpansCrossing(line.rangeEnd));
    boundaryContainer.push(renderedElement("span", {
      "aria-hidden": "true",
      "data-tq-copy-ignore": "true",
      "data-tq-geometry": "true",
      "data-tq-line-end-sentinel": String(lineIndex),
    }));
    if (line.endReason === "MandatoryBreak") {
      boundaryContainer.push(renderedElement("span", {
        "data-tq-geometry": "true",
        "data-tq-hard-break": "true",
        "data-tq-src": "\n",
      }));
    }
    if (lineIndex < plan.lines.length - 1) {
      const breakAttributes: Record<string, string | null> = {
        "data-tq-engine-break": line.endReason,
      };
      if (line.endReason !== "MandatoryBreak") {
        // AccessibilitySoftWrapExclusion: only MandatoryBreak represents a
        // source newline. Other BRs replay visual geometry and stay out of AX
        // and source-faithful copy semantics.
        breakAttributes["aria-hidden"] = "true";
        breakAttributes["data-tq-copy-ignore"] = "true";
      }
      boundaryContainer.push(renderedElement("br", breakAttributes, null, true));
    }
  }
  semanticContainerFor([]);
  if (plan.lines.length > 0) {
    // ParagraphSelectionEndSentinel mirrors the runtime DOM renderer. The
    // zero-width character keeps Chromium's cross-block selection terminator
    // outside compressed closing-punctuation letter spacing, while remaining
    // absent from copy, accessibility, and layout width.
    nodes.push(renderedElement("span", {
      "aria-hidden": "true",
      "data-tq-copy-ignore": "true",
      "data-tq-selection-end": "true",
    }, "\u200B"));
  }
  appendEvidenceOverlays(nodes, plan as EvidenceOverlayPlan, options as EvidenceOverlayOptions);
  return Object.freeze({
    html: nodes.map((node) => node.html).join(""),
    artifact: nodes.map((node) => node.artifact),
    liveSemanticCount: semanticReplay === "live-source" ? semantics.length : 0,
    inlineObjectCount,
    markerCount: plan.lines.length,
  });
}

export function renderPreparedParagraph(planOrJson: string | PreparedLayoutPlan, typographyOrLocale: string | Record<string, unknown> = DEFAULT_LOCALE) {
  return renderPreparedParagraphArtifact(planOrJson, typographyOrLocale).html;
}

/**
 * LiveSourceSemanticReplay: replace inert placeholder spans with shallow clones
 * of the current source elements. Host attributes and CSS behavior never pass
 * through snapshot HTML, while Worker-owned geometry stays intact.
 */
function restoreLiveSemanticElements(
  host: Element,
  sourceElements: Element[],
  expectedCount: number,
  semantics: LiveSemanticSpan[] = [],
  cjkStrongSemantics: CjkStrongSemanticEntry[] = [],
) {
  if (expectedCount === 0) return;
  const placeholders = Array.from(host.querySelectorAll(`[${LIVE_SEMANTIC_INDEX_ATTRIBUTE}]`));
  if (placeholders.length !== expectedCount) {
    throw new Error(
      `LiveSemanticMarkerCountMismatch:expected=${expectedCount};actual=${placeholders.length}`,
    );
  }
  const seen = new Set();
  const cjkStrongList = Array.from(cjkStrongSemantics ?? []);
  const cjkBySourceIndex = new Map();
  if (cjkStrongList.length > 0) {
    for (const semantic of semantics) {
      const match = cjkStrongList.find(
        (entry) => Number(entry?.start) === semantic.start && Number(entry?.end) === semantic.end,
      );
      if (match != null) {
        const sourceIndex = Number.isSafeInteger(Number(semantic.sourceIndex))
          ? Number(semantic.sourceIndex)
          : semantics.indexOf(semantic);
        cjkBySourceIndex.set(sourceIndex, match);
      }
    }
  }
  for (const placeholder of placeholders) {
    const sourceIndex = Number(placeholder.getAttribute(LIVE_SEMANTIC_INDEX_ATTRIBUTE));
    const source = sourceElements[sourceIndex];
    if (!Number.isSafeInteger(sourceIndex) || seen.has(sourceIndex) ||
        !source || typeof source.cloneNode !== "function") {
      throw new Error(`LiveSemanticSourceUnavailable:${sourceIndex}`);
    }
    seen.add(sourceIndex);
    const clone = source.cloneNode(false) as HTMLElement;
    clone.setAttribute("data-tq-source-semantic", "true");
    const cjkStrong = cjkBySourceIndex.get(sourceIndex);
    if (cjkStrong != null) {
      clone.setAttribute("data-tq-cjk-emphasis", "true");
      clone.style.setProperty("font-weight", String(cjkStrong.weight), "important");
    }
    while (placeholder.firstChild) clone.appendChild(placeholder.firstChild);
    placeholder.replaceWith(clone);
  }
}

/**
 * InlineObjectCloneSwap (ADR 0053 B7.3): replace pending placeholders with
 * deep clones of the caller's live inline elements, mirroring the renderer's
 * appendInlineObject: data-tq-inline-object="true", data-tq-object-range, and
 * margin-right = marginRight + trailingGap applied as important only in the
 * trailing-margin branch. Elements cannot ride the Worker plan, so the caller
 * supplies them per range, exactly as it supplies liveSemanticElements.
 */
function swapInlineObjectClones(host: Element, inlineObjects: InlineObjectOptionEntry[]) {
  const placeholders = Array.from(host.querySelectorAll('[data-tq-inline-object="pending"]'));
  if (placeholders.length === 0) return 0;
  const byRange = new Map();
  for (const entry of inlineObjects) {
    const key = `${Number(entry.start)}-${Number(entry.end)}`;
    if (byRange.has(key)) throw new Error(`ConflictingInlineObjectRange:${key}`);
    byRange.set(key, entry);
  }
  for (const placeholder of placeholders) {
    const key = placeholder.getAttribute("data-tq-object-range");
    const entry = byRange.get(key);
    if (!entry || !entry.element || typeof entry.element.cloneNode !== "function") {
      throw new Error(`InlineObjectSourceUnavailable:${key}`);
    }
    const clone = entry.element.cloneNode(true);
    clone.setAttribute("data-tq-inline-object", "true");
    clone.setAttribute("data-tq-object-range", key);
    const trailingMargin = placeholder.getAttribute("data-tq-object-trailing-margin");
    if (trailingMargin != null) {
      const margin = Number(entry.marginRight ?? 0) + Number(trailingMargin);
      clone.style.setProperty("margin-right", `${margin}px`, "important");
    }
    placeholder.replaceWith(clone);
  }
  return placeholders.length;
}

/**
 * Replays the canonical markup in a browser host. `innerHTML` deliberately uses
 * the browser HTML parser, matching the DOM produced when the same string is
 * delivered inside an SSR snapshot template.
 */
export function renderPreparedParagraphInto(
  host: PreparedParagraphHost,
  planOrJson: string | PreparedLayoutPlan,
  typographyOrLocale: string | Record<string, unknown> = DEFAULT_LOCALE,
  options: PreparedParagraphRenderOptions = {},
  context: EnhancedElementContext,
): PreparedParagraphIntoResult {
  if (host == null || !("innerHTML" in Object(host)) || typeof host.querySelectorAll !== "function") {
    throw new Error("InvalidPreparedParagraphHost");
  }
  const root = host.closest?.(ROOT_SELECTOR) ?? host;
  const state = preparedStyleState(root, context);
  const usedStyles = new Set<number>();
  const emphasisDotColor = options.emphasisDotColor ?? (
    options.semantics && options.liveSemanticElements && typeof globalThis.getComputedStyle === "function"
      ? (offset) => {
          if (!Number.isFinite(offset)) return null;
          let maxOrder = -Infinity;
          let selected = null;
          for (const semantic of options.semantics!) {
            const start = Number(semantic.start);
            const end = Number(semantic.end);
            if (offset >= start && offset < end) {
              const order = Number(semantic.order ?? 0);
              if (order > maxOrder) {
                maxOrder = order;
                selected = semantic;
              }
            }
          }
          if (!selected) return null;
          const element = options.liveSemanticElements![selected.sourceIndex];
          if (!element) return null;
          try {
            const color = globalThis.getComputedStyle(element)?.color;
            return typeof color === "string" && color.trim().length > 0 ? color.trim() : null;
          } catch {
            return null;
          }
        }
      : null
  );
  let lowered;
  try {
    lowered = renderPreparedParagraphArtifact(planOrJson, typographyOrLocale, {
      ...options,
      emphasisDotColor,
      styleClassFor: state
        ? (declaration: string) => {
          const index = registerPreparedValueStyle(state, declaration);
          usedStyles.add(index);
          return runtimeValueStyleClass(runtimeValueStyleKey(declaration));
        }
        : null,
    });
  } catch (error) {
    if (state?.owners.size === 0) removePreparedStyleState(state, context);
    throw error;
  }
  if (state) {
    state.owners.set(host, usedStyles);
    state.dirty = true;
    syncPreparedValueStyles(state);
  }
  host.innerHTML = lowered.html;
  if (lowered.liveSemanticCount > 0) {
    restoreLiveSemanticElements(
      host,
      Array.from(options.liveSemanticElements ?? []),
      lowered.liveSemanticCount,
      Array.from(options.semantics ?? []),
      Array.from(options.cjkStrongSemantics ?? []),
    );
  }
  const swappedInlineObjects = swapInlineObjectClones(
    host,
    Array.from(options.inlineObjects ?? []),
  );
  const markers = Array.from(host.querySelectorAll(LINE_MARKER_SELECTOR));
  if (markers.length !== lowered.markerCount) {
    throw new Error(
      `PreparedDomMarkerCountMismatch:expected=${lowered.markerCount};actual=${markers.length}`,
    );
  }
  return Object.freeze({
    html: lowered.liveSemanticCount > 0 || swappedInlineObjects > 0
      ? host.innerHTML
      : lowered.html,
    markers,
  });
}


export const version = 1;
export const semanticReplayRevision = 1;
