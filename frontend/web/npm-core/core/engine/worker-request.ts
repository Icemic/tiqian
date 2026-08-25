// Worker layout request serialization (TsHost runtime port, Slices 3b and 4b).
// Ports workerLayoutRequestJson from WebEnhancerSupport.kt and both
// workerLayoutRequest overloads from WebEnhancerParagraphPipeline.kt into one
// module: the lowered-paragraph overload in Slice 3b, and the root+paragraph
// overload (4b) that feeds Kotlin-core classifier hooks through the markdown
// lowering bridge.
//
// Stateless module: the three serializers are exported directly. The module
// reads the stateless lifecycle, eligibility, responsive-measure and
// markdown-lowering helpers inside function bodies.

// Ambient global declarations pulled in via import type from owner modules.
import type { LoweredParagraph } from "./lowered-paragraph.js";
import type { EnhanceOptions } from "./lifecycle.js";
import { allowsSnapshotExactLayout, conformingExactFontSessionId, withRootDefaults } from "./lifecycle.js";
import type { EngineFfiFacade } from "./ffi-face.js";
import { shouldTryParagraph } from "./eligibility.js";
import { effectiveLineMeasure, sourceParagraphWidth } from "./responsive-measure.js";
import { lowerMarkdown } from "./markdown-lowering.js";

interface WorkerInlineShapingDecisionResult {
  name: string;
  detail: string;
}

// Wire separators named after the Kotlin constants in WebEnhancerSupport.kt:
// records join by U+001E, fields by U+001D, families by U+001F.
  const WORKER_RECORD_SEPARATOR = '\u001e';
  const WORKER_FIELD_SEPARATOR = '\u001d';
  const WORKER_FAMILY_SEPARATOR = '\u001f';
  // WebEnhancerSupport.kt INLINE_EDGE_EPSILON: a clone box whose edges stay
  // below this epsilon remains eligible for Worker preparation.
  const INLINE_EDGE_EPSILON = 0.01;
  // WebEnhancer.kt ROOT_SELECTOR: the selector that identifies a Tiqian prose
  // root, matched against a paragraph's closest ancestor scope.
  const ROOT_SELECTOR = 'tiqian-prose, [data-tiqian-root]';

  /**
   * Escape a string into the Worker JSON string format.
   *
   * @param {string} value
   * @returns {string}
   */
  function escapeJson(value: string): string {
    let result = '"';
    for (let i = 0; i < value.length; i += 1) {
      const ch = value.charAt(i);
      const code = value.charCodeAt(i);
      switch (ch) {
        case '"':
          result += '\\"';
          break;
        case '\\':
          result += '\\\\';
          break;
        case '\b':
          result += '\\b';
          break;
        case '\f':
          result += '\\f';
          break;
        case '\n':
          result += '\\n';
          break;
        case '\r':
          result += '\\r';
          break;
        case '\t':
          result += '\\t';
          break;
        default:
          if (code < 0x20) {
            result += '\\u' + code.toString(16).padStart(4, '0');
          } else {
            result += ch;
          }
          break;
      }
    }
    result += '"';
    return result;
  }

  // elementAttributesJson in WebEnhancerSupport.kt is a @JsFun around
  // JSON.stringify of the [name, value] pairs; the result is already JSON and
  // is appended verbatim, never re-escaped.
  function elementAttributesJson(element: Element): string {
    return JSON.stringify(Array.from(element.attributes || [], function (attribute) {
      return [attribute.name, attribute.value];
    }));
  }

  // CanonicalPlainParagraphEvidence: twin of isCanonicalPlainParagraph in
  // lowered-paragraph.js (six collections). sourceSpans and domInlineObjects
  // never travel the layout wire, so the request carries the full-model verdict
  // for the worker to pass as the render-evidence override.
  function hasRenderEvidence(lowered: LoweredParagraph): boolean {
    return lowered.spans.length > 0 ||
      lowered.decorations.length > 0 ||
      lowered.inlineBoxes.length > 0 ||
      lowered.inlineObjects.length > 0 ||
      lowered.domInlineObjects.length > 0 ||
      lowered.sourceSpans.length > 0;
  }

  /**
   * Serialize a lowered paragraph into the Worker layout request text, matching
   * the Kotlin builder field for field.
   *
   * @param {Element} paragraph
   * @param {LoweredParagraph} lowered
   * @param {number} width
   * @param {number} firstLineIndentIc
   * @returns {string}
   */
  export function workerLayoutRequestJson(paragraph: Element, lowered: LoweredParagraph, width: number, firstLineIndentIc: number): string {
    const textSpans = lowered.spans.map(function (span) {
      return [
        String(span.start),
        String(span.end),
        span.style.fontFamilies.join(WORKER_FAMILY_SEPARATOR),
        String(span.style.fontSize),
        String(span.style.fontWeight),
        String(span.style.italic),
        String(span.style.baselineShift),
      ].join(WORKER_FIELD_SEPARATOR);
    }).join(WORKER_RECORD_SEPARATOR);

    // InlineBoxOuterSpacing default chain: the wire never carries outer
    // spacing. The Kotlin decode (MarkdownParagraphLowering.kt
    // decodeInlineBoxes) constructs InlineBoxSpan with the constructor default
    // InlineBoxOuterSpacing.Narrow (core TextModel.kt), so every inlineBoxes
    // join field and renderInlineBoxes entry emits the string Narrow.
    const inlineBoxes = lowered.inlineBoxes.map(function (box) {
      return [
        String(box.start),
        String(box.end),
        String(box.inlineStart),
        String(box.inlineEnd),
        'Narrow',
      ].join(WORKER_FIELD_SEPARATOR);
    }).join(WORKER_RECORD_SEPARATOR);

    // LineBreakPolicy decode: the Kotlin decode maps every wire policy string
    // to the same member, so the join always emits ProgressiveTechnical
    // regardless of the source span's policy value.
    const lineBreakSpans = lowered.lineBreakSpans.map(function (span) {
      return [
        String(span.start),
        String(span.end),
        'ProgressiveTechnical',
      ].join(WORKER_FIELD_SEPARATOR);
    }).join(WORKER_RECORD_SEPARATOR);

    // WorkerInlineObjectWire: the same measured geometry the runtime lowering
    // feeds its engine (advance, ascent, descent) so the Worker lays the
    // replacement character out identically; the live element stays on the
    // main thread and enters at commit time.
    const inlineObjects = lowered.inlineObjects.map(function (span) {
      return [
        String(span.start),
        String(span.end),
        String(span.advance),
        String(span.ascent),
        String(span.descent),
      ].join(WORKER_FIELD_SEPARATOR);
    }).join(WORKER_RECORD_SEPARATOR);

    // SourceBoundary wire: the Kotlin decode builds a deduped Set, then the
    // builder emits it sorted ascending joined by ",". Array.from(new Set(...))
    // dedupes; the numeric sort keeps the ascending order.
    const sourceBoundaries = Array.from(new Set(lowered.sourceBoundaries))
      .sort(function (a, b) { return a - b; })
      .join(',');

    // WorkerSemanticHierarchyOrder: sourceSpans are collected after their
    // children, so the list index identifies the live element but cannot also
    // describe outer-to-inner replay order.
    let semantics = '[';
    for (let i = 0; i < lowered.sourceSpans.length; i += 1) {
      if (i > 0) semantics += ',';
      const sourceSpan = lowered.sourceSpans[i];
      semantics += '{"start":' + String(sourceSpan.start) +
        ',"end":' + String(sourceSpan.end) +
        ',"tagName":' + escapeJson(sourceSpan.element.tagName.toLowerCase()) +
        ',"attributes":' + elementAttributesJson(sourceSpan.element) +
        ',"sourceIndex":' + String(i) +
        ',"order":' + String(sourceSpan.depth) + '}';
    }

    let renderInlineBoxes = '[';
    for (let j = 0; j < lowered.inlineBoxes.length; j += 1) {
      if (j > 0) renderInlineBoxes += ',';
      const inlineBox = lowered.inlineBoxes[j];
      renderInlineBoxes += '{"start":' + String(inlineBox.start) +
        ',"end":' + String(inlineBox.end) +
        ',"inlineStartPx":' + String(inlineBox.inlineStart) +
        ',"inlineEndPx":' + String(inlineBox.inlineEnd) +
        ',"outerSpacing":' + escapeJson('Narrow') + '}';
    }

    return '{' +
      '"text":' + escapeJson(lowered.text) + ',' +
      '"maxWidthPx":' + String(width) + ',' +
      '"fontFamilies":' + escapeJson(lowered.textStyle.fontFamilies.join(WORKER_FAMILY_SEPARATOR)) + ',' +
      '"fontSizePx":' + String(lowered.textStyle.fontSize) + ',' +
      '"lineHeightPx":' + String(lowered.lineHeight) + ',' +
      '"locale":' + escapeJson(lowered.textStyle.locale) + ',' +
      '"fontWeight":' + String(lowered.textStyle.fontWeight) + ',' +
      '"italic":' + String(lowered.textStyle.italic) + ',' +
      '"firstLineIndentIc":' + String(firstLineIndentIc) + ',' +
      '"sourceBoundaries":' + escapeJson(sourceBoundaries) + ',' +
      '"textSpans":' + escapeJson(textSpans) + ',' +
      '"inlineBoxes":' + escapeJson(inlineBoxes) + ',' +
      '"lineBreakSpans":' + escapeJson(lineBreakSpans) + ',' +
      '"inlineObjects":' + escapeJson(inlineObjects) + ',' +
      '"renderEvidence":' + (hasRenderEvidence(lowered) ? 'true' : 'false') + ',' +
      '"semantics":' + semantics + '],' +
      '"renderInlineBoxes":' + renderInlineBoxes + '],' +
      '"sourceTag":' + escapeJson(paragraph.tagName.toLowerCase()) +
      '}';
  }

  /**
   * Lower a live paragraph through the markdown bridge, then serialize it as a
   * Worker layout request. Ports the root+paragraph workerLayoutRequest
   * overload from WebEnhancerParagraphPipeline.kt (lines 28-44).
   *
   * ffi carries the injected 3c export surface: classifyFontRole,
   * unsupportedInlineShapingProperties, firstDivergentInlineShapingProperty.
   *
   * The root overload discards the lowering issue: ok !== true returns null
   * without reading result.issue. Only processParagraph reports lowering
   * issues, so the Worker path stays silent on failure.
   *
   * @param {Record<string, unknown>} ffi
   * @param {Element} root
   * @param {Element} paragraph
   * @param {Record<string, unknown>} options
   * @returns {(string|null)}
   */
  export function workerLayoutRequestForRoot(ffi: EngineFfiFacade, root: Element, paragraph: Element, options: EnhanceOptions): string | null {
    // RootScopeGate: a paragraph belongs when it has no owner, owns the root,
    // or lives outside the root. A nested owner under the root is not in this
    // paragraph's scope, so it returns null before anything else runs.
    const owner = paragraph.closest(ROOT_SELECTOR);
    if (owner && owner !== root && root.contains(owner)) {
      return null;
    }
    if (!shouldTryParagraph(paragraph)) return null;
    if (!allowsSnapshotExactLayout(options)) return null;
    const resolved = withRootDefaults(options, root);
    let lowered = null;
    try {
      // The options bag mirrors loweringOptionsJs in MarkdownParagraphLowering.kt:
      // fontSize and lineHeight are nullable, strongAsEmphasisMarks is a boolean,
      // and locale is fixed to LOWERING_DEFAULT_LOCALE ("zh-Hans").
      const result = lowerMarkdown(paragraph, {
        fontSize: resolved.fontSize as number | undefined,
        lineHeight: resolved.lineHeight as number | undefined,
        strongAsEmphasisMarks: resolved.strongAsEmphasisMarks as boolean | undefined,
        locale: 'zh-Hans',
      }, {
        // classifyRole is the ffi export verbatim.
        classifyRole: ffi.classifyFontRole,
        // The inlineShapingDecision wrapper reproduces the Kotlin closure in
        // MarkdownParagraphLowering.kt: a null divergence property returns null,
        // otherwise the inlineShapingDecisionResultJs shape is built.
        inlineShapingDecision: function (tag: string, elementValues: string[], paragraphValues: string[]): WorkerInlineShapingDecisionResult | null {
          const property = ffi.firstDivergentInlineShapingProperty(elementValues, paragraphValues);
          return property == null ? null : { name: 'UnsupportedInlineShapingStyle', detail: tag + ':' + property };
        },
        inlineShapingProperties: ffi.unsupportedInlineShapingProperties(),
      });
      if (result && result.ok === true) lowered = result.lowered;
    } catch (error) { lowered = null; }
    if (lowered == null) return null;
    return workerLayoutRequest(paragraph, lowered, resolved);
  }

  /**
   * Gate the lowered paragraph against Worker preparation eligibility, compute
   * the responsive line measure, and serialize the request. Returns null when
   * ineligible.
   *
   * @param {Element} paragraph
   * @param {LoweredParagraph} lowered
   * @param {Record<string, unknown>} options
   * @returns {(string|null)}
   */
  export function workerLayoutRequest(paragraph: Element, lowered: LoweredParagraph, options: EnhanceOptions): string | null {
    if (conformingExactFontSessionId(options) == null) return null;
    // WorkerRequestMatchesRuntimeEligibility: inline objects no longer exclude
    // a paragraph from Worker preparation; their measured geometry travels on
    // the request wire and the live elements enter at commit time, the same
    // split the runtime exact path uses. Decorated paragraphs stay excluded
    // because the request wire carries no decoration input; they lower on the
    // main thread, whose LayoutInput carries the decorations, and commit
    // through the same prepared bridge. Every other exclusion mirrors
    // isRuntimeExactPreparedDomEligible so both exact paths adopt one shape.
    if (lowered.decorations.length > 0 ||
        lowered.sourceSpans.some(function (span) {
          return span.inlineBoxStyle.boxDecorationBreak === 'clone' &&
            (Math.abs(span.inlineBoxStyle.inlineStart) >= INLINE_EDGE_EPSILON ||
              Math.abs(span.inlineBoxStyle.inlineEnd) >= INLINE_EDGE_EPSILON);
        }) ||
        lowered.spans.some(function (span) {
          return span.style.locale !== lowered.textStyle.locale;
        })) {
      return null;
    }
    const rawWidth = sourceParagraphWidth(paragraph);
    if (!Number.isFinite(rawWidth) || rawWidth <= 0) return null;
    // WorkerLineMeasureMatchesResponsiveGrid: the responsive coordinator
    // intentionally treats widths within the same floor(width / fontSize) cell
    // count as one layout input. Serialize that effective measure, not the
    // transient CSS width observed while a window is being dragged, so
    // preparation and commit use the same Worker plan inside the grid.
    const measure = effectiveLineMeasure(
      rawWidth,
      lowered.textStyle.fontSize,
    );
    const firstLineIndentIc = paragraph.tagName.toUpperCase() === 'LI'
      ? 0
      : (options.firstLineIndentIc as number);
    return workerLayoutRequestJson(paragraph, lowered, measure, firstLineIndentIc);
  }