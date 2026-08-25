// Snapshot semantic source projection (ADR 0053 batch 6). Moved verbatim
// from the package root; root paths remain as compatibility re-export shims.

import { stableStringify } from "../../../snapshot-schema.js";

const SAFE_SEMANTIC_TAGS = new Set([
  "a", "abbr", "b", "bdi", "bdo", "cite", "code", "data", "del", "dfn", "em",
  "i", "ins", "kbd", "mark", "q", "s", "samp", "small", "span", "strong", "sub",
  "sup", "time", "u", "var",
]);
const INTERNAL_ATTRIBUTE_PREFIX = "data-tq-";
const ATTRIBUTE_NAME = /^[A-Za-z_:][A-Za-z0-9_.:-]*$/u;
const COLLAPSIBLE_WHITESPACE = /[ \t\n\r\f]/u;

export class SnapshotSemanticError extends Error {
  declare code: string;
  declare detail: string | null;
  constructor(code: string, detail: string | null = null) {
    super(detail == null ? code : `${code}:${detail}`);
    this.name = "SnapshotSemanticError";
    this.code = code;
    this.detail = detail;
  }
}

export interface SnapshotSemanticRange {
  start: number;
  end: number;
}

type SnapshotSemanticAttributes = readonly (readonly [string, string])[];

export interface SnapshotSemanticSpan extends SnapshotSemanticRange {
  tagName: string;
  attributes: SnapshotSemanticAttributes;
}

export interface LiveSemanticSpan extends SnapshotSemanticRange {
  tagName: string;
  sourceIndex: number;
}

export interface SnapshotSourceArtifact {
  text: string;
  semantics: SnapshotSemanticSpan[];
}

interface SnapshotSemanticSpanInput {
  start?: unknown;
  end?: unknown;
  order?: unknown;
  tagName?: unknown;
  attributes?: unknown;
  sourceIndex?: unknown;
}

function semanticError(code: string, detail: string | null = null): SnapshotSemanticError {
  return new SnapshotSemanticError(code, detail);
}

function normalizedAttributes(value: unknown): SnapshotSemanticAttributes {
  const entries = Array.isArray(value)
    ? value
    : value && typeof value === "object"
      ? Object.entries(value)
      : [];
  const attributes: [string, string][] = [];
  for (const item of entries) {
    if (!Array.isArray(item) || item.length !== 2) {
      throw semanticError("InvalidSnapshotSemanticAttributes");
    }
    const name = String(item[0]).trim().toLowerCase();
    const attributeValue = String(item[1]);
    if (!ATTRIBUTE_NAME.test(name) || name.startsWith("on") ||
        name.startsWith(INTERNAL_ATTRIBUTE_PREFIX) || name === "style") {
      throw semanticError("UnsupportedSnapshotSemanticAttribute", name);
    }
    if (name === "href" && /^\s*javascript:/iu.test(attributeValue)) {
      throw semanticError("UnsafeSnapshotSemanticHref");
    }
    attributes.push([name, attributeValue]);
  }
  attributes.sort(([left], [right]) => left < right ? -1 : left > right ? 1 : 0);
  if (new Set(attributes.map(([name]) => name)).size !== attributes.length) {
    throw semanticError("DuplicateSnapshotSemanticAttribute");
  }
  return attributes;
}

function assertUtf16Boundary(text: string, offset: number) {
  if (!Number.isSafeInteger(offset) || offset < 0 || offset > text.length) {
    throw semanticError("InvalidSnapshotSemanticRange");
  }
  if (offset > 0 && offset < text.length &&
      /[\uD800-\uDBFF]/u.test(text[offset - 1]) && /[\uDC00-\uDFFF]/u.test(text[offset])) {
    throw semanticError("SnapshotSemanticRangeSplitsSurrogatePair");
  }
}

type SemanticRangeLower<L extends Record<string, unknown>> = (
  span: SnapshotSemanticSpanInput,
  sourceIndex: number,
) => L;

type SnapshotMetricContractPredicate = (span: SnapshotSemanticMetricContractSpan) => boolean;

function normalizeSemanticRanges<L extends Record<string, unknown>>(
  textValue: unknown,
  value: SnapshotSemanticSpanInput[],
  lowerSpan: SemanticRangeLower<L>,
): readonly SnapshotSemanticRange[] {
  const text = String(textValue);
  if (!Array.isArray(value)) throw semanticError("InvalidSnapshotSemantics");
  const semantics = value.map((span, sourceIndex) => {
    const start = Number(span?.start);
    const end = Number(span?.end);
    assertUtf16Boundary(text, start);
    assertUtf16Boundary(text, end);
    if (end <= start) throw semanticError("InvalidSnapshotSemanticRange");
    return {
      start,
      end,
      ...lowerSpan(span, sourceIndex),
      _sortOrder: Number.isSafeInteger(span?.order) ? Number(span.order) : sourceIndex,
    };
  }).sort((left, right) =>
    left.start - right.start || right.end - left.end || left._sortOrder - right._sortOrder);

  const stack: SnapshotSemanticRange[] = [];
  for (const span of semantics) {
    while (stack.length > 0 && span.start >= stack.at(-1)!.end) stack.pop();
    const parent = stack.at(-1);
    if (parent && span.end > parent.end) throw semanticError("CrossingSnapshotSemanticRanges");
    stack.push(span);
  }
  return Object.freeze(semantics.map(({ _sortOrder, ...span }) => Object.freeze(span)));
}

/** Canonical controlled inline semantics shared by precompute and adoption. */
export function normalizeSnapshotSemantics(textValue: unknown, value: SnapshotSemanticSpanInput[] = []): SnapshotSemanticSpan[] {
  return normalizeSemanticRanges<{ tagName: string; attributes: SnapshotSemanticAttributes }>(
    textValue,
    value,
    (span) => {
      const tagName = String(span?.tagName ?? "").trim().toLowerCase();
      if (!SAFE_SEMANTIC_TAGS.has(tagName)) {
        throw semanticError("UnsupportedSnapshotSemanticTag", tagName);
      }
      return {
        tagName,
        attributes: Object.freeze(normalizedAttributes(span?.attributes)),
      };
    },
  ) as SnapshotSemanticSpan[];
}

/**
 * Structural live-DOM semantics for a Worker plan. Attributes are deliberately
 * absent: the browser renderer shallow-clones the already-trusted source node
 * instead of serializing host behavior through snapshot HTML.
 */
export function normalizeLiveSemantics(textValue: unknown, value: SnapshotSemanticSpanInput[] = []): LiveSemanticSpan[] {
  return normalizeSemanticRanges<{ tagName: string; sourceIndex: number }>(
    textValue,
    value,
    (span, sourceIndex) => {
      const tagName = String(span?.tagName ?? "").trim().toLowerCase();
      if (!tagName) throw semanticError("InvalidLiveSemanticTag");
      return {
        tagName,
        sourceIndex: Number.isSafeInteger(span?.sourceIndex)
          ? Number(span.sourceIndex)
          : sourceIndex,
      };
    },
  ) as LiveSemanticSpan[];
}

export function snapshotSourceArtifact(textValue: unknown, semanticsValue: SnapshotSemanticSpanInput[] = []): SnapshotSourceArtifact {
  const text = String(textValue);
  const semantics = normalizeSnapshotSemantics(text, semanticsValue);
  return Object.freeze({ text, semantics });
}

export function snapshotSourceArtifactString(textValue: unknown, semanticsValue: SnapshotSemanticSpanInput[] = []): string {
  return stableStringify(snapshotSourceArtifact(textValue, semanticsValue));
}

function snapshotRangeContract(
  spans: unknown,
  semantic: SnapshotSemanticSpan,
  predicate: SnapshotMetricContractPredicate,
) {
  return Array.isArray(spans) && spans.some((span) =>
    Number(span?.start) === semantic.start && Number(span?.end) === semantic.end && predicate(span));
}

interface SnapshotSemanticMetricContractSpan extends SnapshotSemanticRange {
  fontFamilies?: unknown;
  fontSizePx?: unknown;
  fontWeight?: unknown;
  italic?: unknown;
  baselineShiftPx?: unknown;
  inlineStartPx?: unknown;
  inlineEndPx?: unknown;
}

/** Inline code is snapshot-safe only when the host publishes its full metric contract. */
export function snapshotSemanticMetricContractIssue(
  semanticsValue: SnapshotSemanticSpan[] | null | undefined,
  textSpansValue: unknown,
  inlineBoxesValue: unknown,
): string | null {
  const codeSpans = Array.from(semanticsValue ?? []).filter((span) => span.tagName === "code");
  for (const semantic of codeSpans) {
    const hasTextStyle = snapshotRangeContract(textSpansValue, semantic, (span) =>
      Array.isArray(span.fontFamilies) && span.fontFamilies.length > 0 &&
      Number.isFinite(Number(span.fontSizePx)) &&
      Number.isSafeInteger(Number(span.fontWeight)) &&
      typeof span.italic === "boolean" &&
      Number.isFinite(Number(span.baselineShiftPx)));
    if (!hasTextStyle) return "InlineCodeFontContractUnavailable";
    const hasInlineBox = snapshotRangeContract(inlineBoxesValue, semantic, (box) =>
      Number.isFinite(Number(box.inlineStartPx)) && Number.isFinite(Number(box.inlineEndPx)));
    if (!hasInlineBox) return "InlineCodeBoxContractUnavailable";
  }
  return null;
}

interface ProjectedNormalFlowSpan extends SnapshotSemanticSpanInput {
  start: number;
  end: number;
}

interface ProjectedNormalFlowSemantics {
  spans: ProjectedNormalFlowSpan[];
  hardBreakOffsets: Set<number>;
}

function projectedNormalFlow(rawText: string, rawSemantics: ProjectedNormalFlowSemantics) {
  const text = String(rawText);
  const projected: string[] = [];
  const boundaryMap = new Array(text.length + 1).fill(0);
  let pendingStart = -1;
  let pendingEnd = -1;

  const resolvePending = (emit: boolean) => {
    if (pendingStart < 0) return;
    const before = projected.length;
    if (emit && projected.length > 0 && projected.at(-1) !== "\n") projected.push(" ");
    const after = projected.length;
    boundaryMap[pendingStart] = before;
    for (let boundary = pendingStart + 1; boundary <= pendingEnd; boundary += 1) {
      boundaryMap[boundary] = after;
    }
    pendingStart = -1;
    pendingEnd = -1;
  };

  for (let index = 0; index < text.length; index += 1) {
    const character = text[index];
    if (character === "\n" && rawSemantics.hardBreakOffsets.has(index)) {
      resolvePending(false);
      boundaryMap[index] = projected.length;
      projected.push("\n");
      boundaryMap[index + 1] = projected.length;
    } else if (COLLAPSIBLE_WHITESPACE.test(character)) {
      if (pendingStart < 0) {
        pendingStart = index;
        boundaryMap[index] = projected.length;
      }
      pendingEnd = index + 1;
    } else {
      resolvePending(true);
      boundaryMap[index] = projected.length;
      projected.push(character);
      boundaryMap[index + 1] = projected.length;
    }
  }
  resolvePending(false);
  boundaryMap[text.length] = projected.length;
  return snapshotSourceArtifact(
    projected.join(""),
    rawSemantics.spans.map((span) => ({
      ...span,
      start: boundaryMap[span.start],
      end: boundaryMap[span.end],
    })).filter((span) => span.end > span.start),
  );
}

/**
 * Canonicalizes a native, normal-flow paragraph before inert snapshot adoption.
 * Geometry generated by Tiqian is intentionally not accepted by this path.
 */
export function snapshotSourceArtifactFromDom(paragraph: Element): SnapshotSourceArtifact {
  const rawText: string[] = [];
  const spans: ProjectedNormalFlowSpan[] = [];
  const hardBreakOffsets = new Set<number>();
  let order = 0;
  let rawLength = 0;

  const appendNode = (node: Node) => {
    if (node.nodeType === 3) {
      const value = String(node.textContent ?? "");
      rawText.push(value);
      rawLength += value.length;
      return;
    }
    if (node.nodeType !== 1) return;
    const tagName = String((node as Element).tagName ?? "").toLowerCase();
    if (tagName === "br") {
      hardBreakOffsets.add(rawLength);
      rawText.push("\n");
      rawLength += 1;
      return;
    }
    if (!SAFE_SEMANTIC_TAGS.has(tagName)) {
      throw semanticError("UnsupportedSnapshotSemanticTag", tagName);
    }
    const start = rawLength;
    const sourceOrder = order++;
    for (const child of Array.from(node.childNodes ?? [])) appendNode(child);
    const end = rawLength;
    if (end <= start) return;
    spans.push({
      start,
      end,
      tagName,
      attributes: Array.from((node as Element).attributes ?? [], (attribute) => (
        Array.isArray(attribute)
          ? [String(attribute[0]), String(attribute[1])]
          : [String(attribute.name), String(attribute.value)]
      )),
      order: sourceOrder,
    });
  };

  for (const child of Array.from(paragraph.childNodes ?? [])) appendNode(child);
  return projectedNormalFlow(rawText.join(""), { spans, hardBreakOffsets });
}