// prepareParagraphLayout (TsHost runtime port, Slice 4b). Ports the paragraph
// layout PREPARATION step of the web host from
// WebEnhancerParagraphPipeline.kt (prepareParagraphLayout, lines 319-498).
// The module computes the responsive measure, gates the prepared-DOM bridge,
// checks prepared-DOM eligibility, serializes the lowered paragraph onto the
// shared ffi wire, runs the exact-session or browser-metric layout call, and
// re-checks the plan envelope facts into three named capability verdicts.
//
// Stateless module: prepareParagraphLayout() is exported directly and reads
// the installed prepared-DOM renderer from globalThis inside the body.

// Ambient global declarations pulled in via import type from owner modules.
import type { LoweredParagraph } from "./lowered-paragraph.js";
import type { PreparedDomRendererApi } from "../sampler/snapshot/prepared-dom.js";
import {
  precomputeParagraphWithBrowserMetrics,
  precomputeParagraphWithDiagnostics,
} from "@tiqian/ffi";
import { effectiveLineMeasure, sourceParagraphWidth } from "./responsive-measure.js";

export interface WireArguments {
  text: string;
  fontFamilies: string;
  fontSize: number;
  lineHeight: number;
  locale: string;
  fontWeight: number;
  italic: boolean;
  sourceBoundaries: string;
  textSpans: string;
  inlineBoxes: string;
  lineBreakSpans: string;
  inlineObjects: string;
  decorations: string;
}

interface LayoutPlanLine {
  rangeStart: number;
  rangeEnd: number;
}

interface LayoutPlan {
  lines: LayoutPlanLine[];
}

interface LayoutCapabilityIssue {
  name: string;
  reason: string;
}

interface LayoutAdvanceSuspect {
  displayText: string;
  advance: number;
  reason: string;
}

interface LayoutDiagnostics {
  capabilityIssues: LayoutCapabilityIssue[];
  advanceSuspects: LayoutAdvanceSuspect[];
}

// The prepare verdict is a discriminated union on kind. Every original
// construction site sets the fields of its own member only.
interface PrepareUnchangedResult {
  kind: "unchanged";
}

interface PrepareUnsupportedResult {
  kind: "unsupported";
  name: string;
  detail?: string;
  element?: Element;
  rawEnvelope?: string;
  reportToConsole?: boolean;
  [key: string]: unknown;
}

export interface PrepareReadyResult {
  kind: "ready";
  rawEnvelope: string;
  planJson: string;
  plan: LayoutPlan;
  diagnostics: LayoutDiagnostics;
  width: number;
  measure: number;
  exactFontSessionUsed: boolean;
  [key: string]: unknown;
}

export type PrepareLayoutResult =
  | PrepareUnchangedResult
  | PrepareUnsupportedResult
  | PrepareReadyResult;

type BrowserBridgeShapeJsonFn = (input: string) => string;
type BrowserBridgeMetricsJsonFn = (input: string) => string;

interface BrowserBridgeDescriptor {
  shapeJson: BrowserBridgeShapeJsonFn;
  metricsJson: BrowserBridgeMetricsJsonFn;
}

interface PrepareParagraphTarget {
  source: Element;
  lowered: LoweredParagraph;
  lastMeasure: number | null;
}

interface PrepareExactSessionDescriptor {
  sessionId: string;
}

interface PrepareParagraphLayoutInvocation {
  paragraph: PrepareParagraphTarget;
  options: Record<string, unknown>;
  exactSession: PrepareExactSessionDescriptor | null;
  browserFallback: Record<string, unknown> | null;
  widthOverride?: number | null;
  ignoreUnchangedMeasure?: boolean;
}

// Wire separators named after the Kotlin constants in WebEnhancerSupport.kt:
  // records join by U+001E, fields by U+001D, families by U+001F. Twin of the
  // worker-request.js serializers, which use the same values.
  const PREPARE_RECORD_SEPARATOR = '\u001e';
  const PREPARE_FIELD_SEPARATOR = '\u001d';
  const PREPARE_FAMILY_SEPARATOR = '\u001f';
  // WebEnhancerSupport.kt INLINE_EDGE_EPSILON: a clone box whose edges stay
  // below this epsilon remains eligible for prepared-DOM preparation.
  const INLINE_EDGE_EPSILON = 0.01;
  // WebEnhancerSupport.kt ZERO_ADVANCE_EPSILON: the host threshold passed to
  // the ffi diagnostics export, which pre-filters advance suspects.
  const ZERO_ADVANCE_EPSILON = 0.01;
  // PreparedParagraph.kt PREPARED_PARAGRAPH_LAYOUT_REVISION: the plan wire
  // revision the installed prepared-DOM renderer must report.
  const PREPARED_LAYOUT_REVISION = 'tiqian-layout-v2';
  // WebEnhancerSupport.kt EXACT_FONT_SESSION_CAPABILITY_FAILURES: substrings
  // that mark an exact-session layout failure as a font capability issue,
  // after which the whole paragraph retries through the browser bridge.
  const EXACT_FONT_SESSION_CAPABILITY_FAILURES = [
    'NoExactFontFace',
    'MissingGlyph',
    'MissingServerShapingReplay',
    'NoExactMetricFace',
    'NonUniformUnicodeRangeMetrics',
  ];

  // Serialize the lowered paragraph onto the shared ffi wire. Twins of the
  // worker-request.js serializer functions (lines 96-153), copied locally so
  // both files stay embeddable and import-free.
  export function wireArguments(lowered: LoweredParagraph): WireArguments {
    const textSpans = lowered.spans.map(function (span) {
      return [
        String(span.start),
        String(span.end),
        span.style.fontFamilies.join(PREPARE_FAMILY_SEPARATOR),
        String(span.style.fontSize),
        String(span.style.fontWeight),
        String(span.style.italic),
        String(span.style.baselineShift),
      ].join(PREPARE_FIELD_SEPARATOR);
    }).join(PREPARE_RECORD_SEPARATOR);

    // InlineBoxOuterSpacing default chain: the wire never carries outer
    // spacing, so every inlineBoxes join field emits the string Narrow.
    const inlineBoxes = lowered.inlineBoxes.map(function (box) {
      return [
        String(box.start),
        String(box.end),
        String(box.inlineStart),
        String(box.inlineEnd),
        'Narrow',
      ].join(PREPARE_FIELD_SEPARATOR);
    }).join(PREPARE_RECORD_SEPARATOR);

    // LineBreakPolicy decode maps every wire policy string to the same
    // member, so the join always emits ProgressiveTechnical.
    const lineBreakSpans = lowered.lineBreakSpans.map(function (span) {
      return [
        String(span.start),
        String(span.end),
        'ProgressiveTechnical',
      ].join(PREPARE_FIELD_SEPARATOR);
    }).join(PREPARE_RECORD_SEPARATOR);

    const inlineObjects = lowered.inlineObjects.map(function (span) {
      return [
        String(span.start),
        String(span.end),
        String(span.advance),
        String(span.ascent),
        String(span.descent),
      ].join(PREPARE_FIELD_SEPARATOR);
    }).join(PREPARE_RECORD_SEPARATOR);

    // Decorations (mirrors the Kotlin parseDecorations landed in 33c5106):
    // each entry carries start, end, and the member-name kind string.
    const decorations = lowered.decorations.map(function (decoration) {
      return [
        String(decoration.start),
        String(decoration.end),
        decoration.kind,
      ].join(PREPARE_FIELD_SEPARATOR);
    }).join(PREPARE_RECORD_SEPARATOR);

    // SourceBoundary wire: dedupe into a Set, sort ascending, join by comma.
    const sourceBoundaries = Array.from(new Set(lowered.sourceBoundaries))
      .sort(function (a, b) { return a - b; })
      .join(',');

    return {
      text: lowered.text,
      fontFamilies: lowered.textStyle.fontFamilies.join(PREPARE_FAMILY_SEPARATOR),
      fontSize: lowered.textStyle.fontSize,
      lineHeight: lowered.lineHeight,
      locale: lowered.textStyle.locale,
      fontWeight: lowered.textStyle.fontWeight,
      italic: lowered.textStyle.italic,
      sourceBoundaries: sourceBoundaries,
      textSpans: textSpans,
      inlineBoxes: inlineBoxes,
      lineBreakSpans: lineBreakSpans,
      inlineObjects: inlineObjects,
      decorations: decorations,
    };
  }

  // RuntimeExactPreparedDomScope: inline the lowered-paragraph.js predicate
  // isRuntimeExactPreparedDomEligible, which requires every span to share the
  // paragraph locale. The plan wire carries one paragraph locale.
  function isPreparedDomEligible(lowered: LoweredParagraph): boolean {
    return lowered.spans.every(function (span) {
      return span.style.locale === lowered.textStyle.locale;
    });
  }

  // CanonicalPlainParagraphEvidence: twin of isCanonicalPlainParagraph in
  // lowered-paragraph.js (six collections). The wire predicate inside the
  // layout module cannot see sourceSpans or domInlineObjects because they
  // never travel the wire, so the host passes this full-model verdict as the
  // render-evidence override on both layout calls.
  function hasRenderEvidence(lowered: LoweredParagraph): boolean {
    return lowered.spans.length > 0 ||
      lowered.decorations.length > 0 ||
      lowered.inlineBoxes.length > 0 ||
      lowered.inlineObjects.length > 0 ||
      lowered.domInlineObjects.length > 0 ||
      lowered.sourceSpans.length > 0;
  }

  // PreparedDomUnifiedEligibility: inline the WebEnhancerSupport.kt
  // isPreparedDomBridgeAvailable @JsFun body, gating on the installed renderer
  // shape, schema, and matching layout revision.
  function isPreparedDomBridgeAvailable(): boolean {
    const renderer = globalThis.__TiqianPreparedDomRenderer;
    return !!(renderer &&
      typeof renderer.render === 'function' &&
      typeof renderer.release === 'function' &&
      typeof renderer.releaseRoot === 'function' &&
      renderer.schema === 1 &&
      renderer.layoutRevision === PREPARED_LAYOUT_REVISION);
  }

  function isExactSessionCapabilityFailure(error: unknown): boolean {
    const message = String(error && (error as { message?: string }).message);
    for (let i = 0; i < EXACT_FONT_SESSION_CAPABILITY_FAILURES.length; i += 1) {
      if (message.indexOf(EXACT_FONT_SESSION_CAPABILITY_FAILURES[i]) !== -1) {
        return true;
      }
    }
    return false;
  }

  // BrowserMetricsCallArguments: the browser-metric export is the diagnostics
  // list without the leading sessionId, plus the shape and metrics callbacks
  // inserted before the trailing decorations and emphasis dot gap.
  export type BrowserMetricsArguments = [
    text: string, maxWidthPx: number, fontFamilies: string, fontSizePx: number,
    lineHeightPx: number, locale: string, fontWeight: number, italic: boolean,
    firstLineIndentIc: number, lineLengthGridEnabled: boolean, sourceBoundaries: string,
    textSpans: string, inlineBoxes: string, lineBreakSpans: string, inlineObjects: string | null,
    zeroAdvanceEpsilonPx: number, shapeJson: (p0: string) => string,
    metricsJson: (p0: string) => string, decorations?: string | null,
    emphasisDotGapEm?: number | null, renderEvidenceOverride?: boolean | null,
  ];
  export function browserMetricsArguments(browserFallback: Record<string, unknown>, paragraphArguments: unknown[], wire: WireArguments, emphasisDotGapEm: number | null, renderEvidenceOverride: boolean): BrowserMetricsArguments {
    return paragraphArguments.concat([
      ZERO_ADVANCE_EPSILON,
      (browserFallback.bridge as BrowserBridgeDescriptor).shapeJson,
      (browserFallback.bridge as BrowserBridgeDescriptor).metricsJson,
      wire.decorations,
      emphasisDotGapEm,
      renderEvidenceOverride,
    ]) as BrowserMetricsArguments;
  }

  // The exact-session diagnostics export argument tuple, byte-locked so tests
  // can assert the full positional list the wire sends to ffi. The direct ffi
  // call spreads this tuple unchanged; no value is reordered or recomputed.
  export function precomputeDiagnosticsArguments(
    sessionId: string,
    paragraphArguments: unknown[],
    wire: WireArguments,
    emphasisDotGapEm: number | null,
    renderEvidenceOverride: boolean,
  ): [string, string, number, string, number, number, string, number, boolean, number, boolean, string, string, string, string, string, number, string, number | null, boolean] {
    return [
      sessionId,
      paragraphArguments[0] as string,
      paragraphArguments[1] as number,
      paragraphArguments[2] as string,
      paragraphArguments[3] as number,
      paragraphArguments[4] as number,
      paragraphArguments[5] as string,
      paragraphArguments[6] as number,
      paragraphArguments[7] as boolean,
      paragraphArguments[8] as number,
      paragraphArguments[9] as boolean,
      paragraphArguments[10] as string,
      paragraphArguments[11] as string,
      paragraphArguments[12] as string,
      paragraphArguments[13] as string,
      paragraphArguments[14] as string,
      ZERO_ADVANCE_EPSILON,
      wire.decorations,
      emphasisDotGapEm,
      renderEvidenceOverride,
    ];
  }

  /**
   * Prepare a paragraph for layout. See the slice header for the verdict
   * shapes and the Kotlin order this follows.
   *
   * @param {Object} argument
   * @returns {Object}
   */
  export function prepareParagraphLayout(argument: PrepareParagraphLayoutInvocation): PrepareLayoutResult {
    const paragraph = argument.paragraph;
    const options = argument.options;
    const exactSession = argument.exactSession;
    const browserFallback = argument.browserFallback;
    const widthOverride = argument.widthOverride;
    const ignoreUnchangedMeasure = argument.ignoreUnchangedMeasure;
    const lowered = paragraph.lowered;
    const element = paragraph.source;

    const width = widthOverride != null
      ? widthOverride
      : sourceParagraphWidth(paragraph.source);
    const fontSize = lowered.textStyle.fontSize;
    const measure = effectiveLineMeasure(width, fontSize);

    if (!ignoreUnchangedMeasure && paragraph.lastMeasure === measure) {
      return { kind: 'unchanged' };
    }

    if (!isPreparedDomBridgeAvailable()) {
      return {
        kind: 'unsupported',
        name: 'PreparedDomBridgeUnavailable',
        detail: 'expectedLayoutRevision=' + PREPARED_LAYOUT_REVISION,
        element: element,
      };
    }

    if (!isPreparedDomEligible(lowered)) {
      let firstMismatch = null;
      for (let i = 0; i < lowered.spans.length; i += 1) {
        if (lowered.spans[i].style.locale !== lowered.textStyle.locale) {
          firstMismatch = lowered.spans[i];
          break;
        }
      }
      return {
        kind: 'unsupported',
        name: 'SpanLocaleMismatchUnsupported',
        detail: 'spanRange=' + (firstMismatch ? firstMismatch.start : 0) +
          '..' + (firstMismatch ? firstMismatch.end : 0) +
          '; spanLocale=' + (firstMismatch ? firstMismatch.style.locale : 'unknown') +
          '; paragraphLocale=' + lowered.textStyle.locale,
        element: element,
      };
    }

    const wire = wireArguments(lowered);
    const firstLineIndentIc = element.tagName.toUpperCase() === 'LI'
      ? 0
      : (options.firstLineIndentIc as number);
    // The Kotlin direct path builds ParagraphStyle without lineLengthGrid,
    // whose data-class default is LineLengthGrid(enabled = true).
    const lineLengthGridEnabled = true;
    const emphasisDotGapEm: number | null = options.emphasisDotGapEm == null
      ? null
      : (options.emphasisDotGapEm as number);
    const renderEvidenceOverride = hasRenderEvidence(lowered);

    // EngineLineMeasureMatchesResponsiveGrid: feed the quantized measure, not
    // the raw width, as maxWidthPx to every layout path.
    const paragraphArguments = [
      wire.text,
      measure,
      wire.fontFamilies,
      wire.fontSize,
      wire.lineHeight,
      wire.locale,
      wire.fontWeight,
      wire.italic,
      firstLineIndentIc,
      lineLengthGridEnabled,
      wire.sourceBoundaries,
      wire.textSpans,
      wire.inlineBoxes,
      wire.lineBreakSpans,
      wire.inlineObjects,
    ];

    let exactFontSessionUsed = browserFallback != null;

    let rawEnvelope;
    if (exactSession != null) {
      try {
        // ExactSessionSemanticLayout: one font session serves the canonical
        // plain paragraph and the semantic DOM, so no engine pair exists.
        rawEnvelope = precomputeParagraphWithDiagnostics(
          ...precomputeDiagnosticsArguments(exactSession.sessionId, paragraphArguments, wire, emphasisDotGapEm, renderEvidenceOverride),
        );
      } catch (error) {
        if (!isExactSessionCapabilityFailure(error)) throw error;
        // PreparedDomAfterSessionFailure: a capability failure retries the
        // whole paragraph through the browser bridge. The per-run
        // ExactSessionBrowserFallback* wrappers are deliberately not ported
        // (Slice 4a note); the whole paragraph re-runs instead.
        exactFontSessionUsed = false;
        rawEnvelope = precomputeParagraphWithBrowserMetrics(
          ...browserMetricsArguments(browserFallback!, paragraphArguments, wire, emphasisDotGapEm, renderEvidenceOverride),
        );
      }
    } else {
      if (browserFallback == null) {
        throw new Error('missing browserFallback descriptor for browser-metric layout');
      }
      exactFontSessionUsed = false;
      rawEnvelope = precomputeParagraphWithBrowserMetrics(
        ...browserMetricsArguments(browserFallback, paragraphArguments, wire, emphasisDotGapEm, renderEvidenceOverride),
      );
    }

    const envelope = JSON.parse(rawEnvelope);
    const planJson = envelope.plan;
    const plan = JSON.parse(planJson);
    const diagnostics = envelope.diagnostics;

    const capabilityIssue = diagnostics.capabilityIssues[0];
    if (capabilityIssue != null) {
      return {
        kind: 'unsupported',
        name: capabilityIssue.name,
        detail: capabilityIssue.reason,
        element: element,
      };
    }

    let invalidShaping = null;
    for (let s = 0; s < diagnostics.advanceSuspects.length; s += 1) {
      const suspect = diagnostics.advanceSuspects[s];
      if (suspect.displayText.length > 0 &&
          suspect.displayText.indexOf('\n') === -1 &&
          suspect.displayText.indexOf('\r') === -1) {
        invalidShaping = suspect;
        break;
      }
    }
    if (invalidShaping != null) {
      return {
        kind: 'unsupported',
        name: 'InvalidWebShapingAdvance',
        detail: 'text=' + invalidShaping.displayText +
          '; advance=' + invalidShaping.advance +
          '; ' + invalidShaping.reason,
        element: element,
      };
    }

    let clonedDecoration = null;
    for (let d = 0; d < lowered.sourceSpans.length; d += 1) {
      const sourceSpan = lowered.sourceSpans[d];
      let crossing = 0;
      for (let l = 0; l < plan.lines.length; l += 1) {
        const line = plan.lines[l];
        if (line.rangeStart < sourceSpan.end && line.rangeEnd > sourceSpan.start) {
          crossing += 1;
        }
      }
      if (sourceSpan.inlineBoxStyle.boxDecorationBreak === 'clone' &&
          (Math.abs(sourceSpan.inlineBoxStyle.inlineStart) >= INLINE_EDGE_EPSILON ||
            Math.abs(sourceSpan.inlineBoxStyle.inlineEnd) >= INLINE_EDGE_EPSILON) &&
          crossing > 1) {
        clonedDecoration = sourceSpan;
        break;
      }
    }
    if (clonedDecoration != null) {
      return {
        kind: 'unsupported',
        name: 'InlineCloneDecorationBreakUnsupported',
        detail: clonedDecoration.element.tagName.toLowerCase(),
        element: element,
      };
    }

    return {
      kind: 'ready',
      rawEnvelope: rawEnvelope,
      planJson: planJson,
      plan: plan,
      diagnostics: diagnostics,
      width: width,
      measure: measure,
      exactFontSessionUsed: exactFontSessionUsed,
    };
  }
