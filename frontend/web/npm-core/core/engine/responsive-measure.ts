// Responsive measure helpers for the enhance pipeline.
//
// Plain script, no exports: running it installs globalThis.__TiqianResponsiveMeasure.
// Two consumers share this file as the single source of truth: the npm host
// (importing it for the side effect) and the Kotlin runtime bundle, into
// which the generateResponsiveMeasureBridge gradle task embeds this source
// verbatim. Double installation is guarded.
//
// Embedding constraint: the generator wraps this file in a Kotlin raw string,
// so the source must contain no dollar sign and no triple double-quote
// sequence. Use string concatenation, never template literals.

type ResponsiveMeasureLineMeasureFn = (width: number, fontSize: number) => number;

type ResponsiveMeasureElementWidthFn = (element: Element | null) => number;

type ResponsiveMeasureParagraphWidthFn = (paragraph: Element) => number;

type ResponsiveMeasureIsCurrentFn = (preparedWidth: number, currentWidth: number, fontSize: number) => boolean;

export interface ResponsiveMeasureGlobal {
  effectiveLineMeasure: ResponsiveMeasureLineMeasureFn;
  elementContentWidth: ResponsiveMeasureElementWidthFn;
  sourceParagraphWidth: ResponsiveMeasureParagraphWidthFn;
  isCurrentResponsiveMeasure: ResponsiveMeasureIsCurrentFn;
}

declare global {
  var __TiqianResponsiveMeasure: ResponsiveMeasureGlobal | undefined;
}

(function () {
  if (globalThis.__TiqianResponsiveMeasure) return;

  function effectiveLineMeasure(width: number, fontSize: number): number {
    // InvalidTypographyPreservesCapabilityDiagnosis: a zero or non-finite
    // host font size has no meaningful character grid. Keep the positive
    // host width so shaping can report its precise zero-advance capability
    // issue instead of failing earlier with an unrelated maxWidth error.
    if (!Number.isFinite(fontSize) || fontSize <= 0) return width;
    var gridCells = Math.max(1, Math.floor(width / fontSize));
    return Math.min(gridCells * fontSize, width);
  }

  function elementContentWidth(element: Element | null): number {
    if (!element) return 0;
    var style = globalThis.getComputedStyle(element);
    var number = function (value: string): number {
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
    var fallback = element.getBoundingClientRect().width;
    var rects = Array.from(element.getClientRects()).filter(function (rect) {
      return rect.width > 0;
    });
    var borderBoxWidth = rects.length <= 1
      ? fallback
      : Math.max.apply(null, rects.map(function (rect) { return rect.width; }));
    return borderBoxWidth - number(style.paddingLeft) - number(style.paddingRight) -
      number(style.borderLeftWidth) - number(style.borderRightWidth);
  }

  function sourceParagraphWidth(paragraph: Element): number {
    // ContentBoxLineMeasure: LayoutConstraints describe the inline content
    // box where glyphs are placed. A host may add padding directly to a
    // paragraph-shaped list item; using its border-box width lays the line
    // out once through that padding and then starts it after the padding,
    // causing a real right-edge overflow. Font backend selection does not
    // change which CSS box owns the available line measure.
    var own = elementContentWidth(paragraph);
    if (own > 0) return own;
    var target = paragraph.parentElement || paragraph;
    var parentWidth = elementContentWidth(target);
    if (parentWidth > 0) return parentWidth;
    return 320;
  }

  function isCurrentResponsiveMeasure(preparedWidth: number, currentWidth: number, fontSize: number): boolean {
    return effectiveLineMeasure(preparedWidth, fontSize) ===
      effectiveLineMeasure(currentWidth, fontSize);
  }

  globalThis.__TiqianResponsiveMeasure = {
    effectiveLineMeasure: effectiveLineMeasure,
    elementContentWidth: elementContentWidth,
    sourceParagraphWidth: sourceParagraphWidth,
    isCurrentResponsiveMeasure: isCurrentResponsiveMeasure,
  };
})();

export {};
