/**
 * LoweredParagraph wire model (TsHost runtime port, Slice 1). One module owns
 * the exact plain-object shape produced by `LoweringBuilder.prototype.build()`
 * in markdown-lowering.js and the two paragraph predicates that the Kotlin
 * decode layer in MarkdownParagraphLowering.kt currently implements as
 * extensions. Field names match the wire object character for character; the
 * Kotlin `LoweredParagraph` data class remains the decode target for now.
 */

export interface TextStyle {
  fontFamilies: string[];
  fontSize: number;
  fontWeight: number;
  italic: boolean;
  baselineShift: number;
  locale: string;
}

export interface TextSpan {
  start: number;
  end: number;
  style: TextStyle;
}

export interface DecorationSpan {
  start: number;
  end: number;
  /** Currently always "Emphasis". */
  kind: string;
}

export interface InlineBoxSpan {
  start: number;
  end: number;
  inlineStart: number;
  inlineEnd: number;
}

export interface InlineObjectSpan {
  start: number;
  end: number;
  advance: number;
  ascent: number;
  descent: number;
}

export interface DomInlineObject {
  start: number;
  end: number;
  element: Element;
  marginRight: number;
}

export interface DomInlineBoxStyle {
  inlineStart: number;
  inlineEnd: number;
  marginRight: number;
  letterSpacing: number;
  boxDecorationBreak: string;
}

export interface DomSourceSpan {
  start: number;
  end: number;
  element: Element;
  depth: number;
  cjkStrongBaseWeight: number | null;
  computedColor: string | null;
  inlineBoxStyle: DomInlineBoxStyle;
}

export interface LineBreakSpan {
  start: number;
  end: number;
  /** Currently always "ProgressiveTechnical". */
  policy: string;
}

export interface LoweredParagraph {
  text: string;
  textStyle: TextStyle;
  lineHeight: number;
  spans: TextSpan[];
  decorations: DecorationSpan[];
  inlineBoxes: InlineBoxSpan[];
  inlineObjects: InlineObjectSpan[];
  domInlineObjects: DomInlineObject[];
  sourceSpans: DomSourceSpan[];
  sourceBoundaries: number[];
  lineBreakSpans: LineBreakSpan[];
}

/**
 * CanonicalPlainParagraph: classifies the shape the prepared plain host path
 * and the re-lowerer promise treat as canonical plain (PreparedPlainHostPromise
 * in WebEnhancerParagraphPipeline.kt): every styled collection on the wire is
 * empty.
 */
export function isCanonicalPlainParagraph(lowered: LoweredParagraph): boolean {
  return lowered.spans.length === 0 &&
    lowered.decorations.length === 0 &&
    lowered.inlineBoxes.length === 0 &&
    lowered.inlineObjects.length === 0 &&
    lowered.domInlineObjects.length === 0 &&
    lowered.sourceSpans.length === 0;
}

/**
 * RuntimeSnapshotPreparedDomScope: the runtime prepared-DOM bridge replays styled
 * spans through plan evidence, and the plan wire carries one paragraph locale,
 * so the bridge cannot replay a span shaped under a different one.
 * Locale-mismatching spans fail closed with SpanLocaleMismatchUnsupported.
 */
export function isRuntimeSnapshotPreparedDomEligible(lowered: LoweredParagraph): boolean {
  return lowered.spans.every((span) => span.style.locale === lowered.textStyle.locale);
}

/**
 * Escape a string into JSON format matching worker JSON string serialization.
 */
function escapeJson(value: string): string {
  let result = '"';
  for (let i = 0; i < value.length; i += 1) {
    const ch = value[i];
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
          result += `\\u${code.toString(16).padStart(4, "0")}`;
        } else {
          result += ch;
        }
        break;
    }
  }
  result += '"';
  return result;
}

/**
 * RuntimeRichPreparedDomOptions (ADR 0053 B8.1): source spans become
 * live-source semantics whose sourceIndex addresses the same-order element
 * array and whose order carries the nesting depth as the tie-break.
 */
export function preparedSemanticReplayJson(lowered: LoweredParagraph): string {
  let result = "[";
  for (let i = 0; i < lowered.sourceSpans.length; i += 1) {
    if (i > 0) {
      result += ",";
    }
    const span = lowered.sourceSpans[i];
    result += `{"start":${String(span.start)},"end":${String(span.end)},"tagName":${escapeJson(span.element.tagName.toLowerCase())},"sourceIndex":${String(i)},"order":${String(span.depth)}}`;
  }
  result += "]";
  return result;
}

/**
 * Prepared DOM inline objects ride as {start, end, marginRight} metadata
 * paired with an element array. marginRight prints through plain number
 * toString: the compiled Kotlin/JS Float append is n.toString() with no
 * 32-bit rounding, so the wire value passes through unchanged.
 */
export function preparedInlineObjectMetaJson(lowered: LoweredParagraph): string {
  let result = "[";
  for (let i = 0; i < lowered.domInlineObjects.length; i += 1) {
    if (i > 0) {
      result += ",";
    }
    const objectSpan = lowered.domInlineObjects[i];
    result += `{"start":${String(objectSpan.start)},"end":${String(objectSpan.end)},"marginRight":${String(objectSpan.marginRight)}}`;
  }
  result += "]";
  return result;
}

/**
 * PreparedCjkStrongSemantics: strong-as-emphasis lowering records the
 * inherited base weight on each weighted source span; the prepared lowerer
 * replays the same marks from this metadata, matched by range equality.
 * Empty unless strong-as-emphasis lowering produced weighted spans.
 */
export function preparedCjkStrongSemanticsJson(lowered: LoweredParagraph): string {
  let result = "[";
  let first = true;
  for (let i = 0; i < lowered.sourceSpans.length; i += 1) {
    const span = lowered.sourceSpans[i];
    const weight = span.cjkStrongBaseWeight;
    if (weight == null) {
      continue;
    }
    if (!first) {
      result += ",";
    }
    first = false;
    result += `{"start":${String(span.start)},"end":${String(span.end)},"weight":${String(weight)}}`;
  }
  result += "]";
  return result;
}

/** Field-wise equality over the [TextStyle] wire shape, null-tolerant. */
export function textStylesEqual(left: TextStyle | null | undefined, right: TextStyle | null | undefined): boolean {
  if (left === right) return true;
  if (!left || !right) return false;
  if (
    left.fontSize !== right.fontSize ||
    left.fontWeight !== right.fontWeight ||
    left.italic !== right.italic ||
    left.baselineShift !== right.baselineShift ||
    left.locale !== right.locale ||
    left.fontFamilies.length !== right.fontFamilies.length
  ) {
    return false;
  }
  for (let i = 0; i < left.fontFamilies.length; i++) {
    if (left.fontFamilies[i] !== right.fontFamilies[i]) return false;
  }
  return true;
}
