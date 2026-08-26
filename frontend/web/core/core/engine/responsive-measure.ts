// Responsive measure helpers for the enhance pipeline.
//
// Pure functions deriving the line measure from live host metrics. They read
// `globalThis.getComputedStyle` and element geometry inside function bodies
// only.

export function effectiveLineMeasure(width: number, fontSize: number): number {
  // InvalidTypographyPreservesCapabilityDiagnosis: a zero or non-finite
  // host font size has no meaningful character grid. Keep the positive
  // host width so shaping can report its precise zero-advance capability
  // issue instead of failing earlier with an unrelated maxWidth error.
  if (!Number.isFinite(fontSize) || fontSize <= 0) return width;
  const gridCells = Math.max(1, Math.floor(width / fontSize));
  return Math.min(gridCells * fontSize, width);
}

export function elementContentWidth(element: Element | null): number {
  if (!element) return 0;
  const style = globalThis.getComputedStyle(element);
  const number = function (value: string): number {
    return Number.parseFloat(value) || 0;
  };
  // FractionalFragmentContentMeasure: clientWidth rounds to integer
  // pixels, so a width change below 0.5px can go undetected and a
  // font-size grid crossing at a fractional width can be missed.
  // Inline-style probes cannot see padding declared in a stylesheet,
  // such as li { padding-inline-start }. getBoundingClientRect()
  // returns the union of all CSS column fragments. Take the widest
  // live client rect instead; it is the border box of a single
  // fragment. Then subtract the computed padding and borders.
  const fallback = element.getBoundingClientRect().width;
  const rects = Array.from(element.getClientRects()).filter(function (rect) {
    return rect.width > 0;
  });
  const borderBoxWidth = rects.length <= 1
    ? fallback
    : Math.max.apply(null, rects.map(function (rect) { return rect.width; }));
  return borderBoxWidth - number(style.paddingLeft) - number(style.paddingRight) -
    number(style.borderLeftWidth) - number(style.borderRightWidth);
}

export function sourceParagraphWidth(paragraph: Element): number {
  // ContentBoxLineMeasure: LayoutConstraints describe the inline content
  // box where glyphs are placed. A host may add padding directly to a
  // paragraph-shaped list item; using its border-box width lays the line
  // out once through that padding and then starts it after the padding,
  // causing a real right-edge overflow. Font backend selection does not
  // change which CSS box owns the available line measure.
  const own = elementContentWidth(paragraph);
  if (own > 0) return own;
  const target = paragraph.parentElement || paragraph;
  const parentWidth = elementContentWidth(target);
  if (parentWidth > 0) return parentWidth;
  return 320;
}

export function isCurrentResponsiveMeasure(preparedWidth: number, currentWidth: number, fontSize: number): boolean {
  return effectiveLineMeasure(preparedWidth, fontSize) ===
    effectiveLineMeasure(currentWidth, fontSize);
}

// HostInlineSizeStateProbe: a responsive pass must refresh captured inline
// sizes whenever any rendered paragraph carries the host-inline-size marker.
export function hasHostInlineSizeParagraph(root: Element): boolean {
  return root.querySelector("[data-tq-host-inline-size]") !== null;
}