// Paragraph grid-metric seeds and the observed-width measure signature
// (ADR 0053 batch 1; decomposition report section 7). The state object is
// owned by the element; these functions take it explicitly.
// Grid cell math moved here from lazy-capabilities.js in ADR 0053 batch 6.
import { DEFAULT_PARAGRAPH_SELECTOR, paragraphMeasureEntry } from "./signatures.js";

type ReadBasedSignature = () => string;

export interface ParagraphGridMetricsState {
  rootFontSize: string;
  metrics: WeakMap<Element, ParagraphGridMetricEntry> | null;
}

interface ParagraphGridMetricEntry {
  fontSize: number;
  fontSizePx: string;
  inset: number;
}

export function lineLengthGridCellCount(containerWidth: number, fontSize: number) {
  const width = Math.fround(Number(containerWidth));
  const size = Math.fround(Number(fontSize));
  if (!Number.isFinite(width) || width < 0 || !Number.isFinite(size) || size <= 0) return null;
  // Match Kotlin's Float division before floor(containerWidth / fontSize).
  return Math.max(1, Math.floor(Math.fround(width / size)));
}

export function lineLengthGridMeasure(containerWidth: number, fontSize: number) {
  const width = Math.fround(Number(containerWidth));
  const size = Math.fround(Number(fontSize));
  const cells = lineLengthGridCellCount(width, size);
  if (cells == null) return null;
  return Math.min(width, Math.fround(cells * size));
}

export function createParagraphGridMetricsState(): ParagraphGridMetricsState {
  return { rootFontSize: "", metrics: null };
}

export function seedParagraphGridMetrics(state: ParagraphGridMetricsState, paragraph: Element) {
  const style = getComputedStyle(paragraph);
  const number = (value: string) => Number.parseFloat(value) || 0;
  (state.metrics ??= new WeakMap()).set(paragraph, {
    fontSize: Number.parseFloat(style.fontSize),
    fontSizePx: style.fontSize,
    inset: number(style.paddingLeft) + number(style.paddingRight) +
      number(style.borderLeftWidth) + number(style.borderRightWidth),
  });
}

// ObservedMeasureSignature: the same entries as
// paragraphMeasureSignature (from signatures.js), built from
// ResizeObserver-delivered widths and seeded font metrics — zero layout reads
// on the per-width-event hot paths (the ResponsiveFinishSkipsDoomedSignatureReads
// budget). Unseeded or zero-width paragraphs fall back to the read-based entry;
// observed widths may trail live layout by one delivery, so a crossing commits
// at most one frame later than the pre-paint lane.
export function paragraphMeasureSignatureFromObserved(
  root: Element,
  state: ParagraphGridMetricsState,
  widths: WeakMap<Element, number> | null,
  snapshotFontLayout: boolean,
  readBased: ReadBasedSignature,
) {
  // Seeded metrics freeze each paragraph's fontSize at observation time,
  // which goes blind when a media or container breakpoint rescales type in
  // the same resize that crosses it. One root read per call catches the
  // inherited case and drops the seeds so this pass reads live values; a
  // paragraph whose font responds independently of the root still goes
  // through the typography lane.
  const rootFontSize = getComputedStyle(root).fontSize;
  if (rootFontSize !== state.rootFontSize) {
    state.rootFontSize = rootFontSize;
    state.metrics = null;
  }
  const metrics = state.metrics;
  if (!widths || !metrics) return readBased();
  const paragraphs = root.querySelectorAll(DEFAULT_PARAGRAPH_SELECTOR);
  let signature = "";
  for (let i = 0; i < paragraphs.length; i++) {
    const paragraph = paragraphs[i];
    if (i > 0) signature += "\u001f";
    const m = metrics.get(paragraph);
    let width = widths.get(paragraph);
    if (m == null || width == null) {
      signature += paragraphMeasureEntry(paragraph, snapshotFontLayout);
      continue;
    }
    if (snapshotFontLayout) width -= m.inset;
    if (!(width > 0)) {
      signature += paragraphMeasureEntry(paragraph, snapshotFontLayout);
      continue;
    }
    const measure = lineLengthGridMeasure(width, m.fontSize);
    signature += measure == null
      ? `invalid:${width.toFixed(3)}:${m.fontSizePx}`
      : `${Math.fround(m.fontSize)}:${measure}`;
  }
  return signature;
}
