// Typography and paragraph signatures plus the shared inline-size read
// helpers (ADR 0053 batch 1; decomposition report section 7). Extracted
// verbatim from element.js; every function takes its inputs explicitly.
import { lineLengthGridMeasure } from "./grid-metrics.js";

const DEFAULT_PARAGRAPH_SELECTOR = "p, li";
const TYPOGRAPHY_PROPERTIES = [
  "display",
  "font-family",
  "font-size",
  "font-weight",
  "font-style",
  "font-stretch",
  "font-size-adjust",
  "font-variant-alternates",
  "font-variant-caps",
  "font-variant-east-asian",
  "font-variant-ligatures",
  "font-variant-numeric",
  "font-variant-position",
  "font-language-override",
  "font-variation-settings",
  "font-feature-settings",
  "font-kerning",
  "font-optical-sizing",
  "letter-spacing",
  "word-spacing",
  "line-height",
  "text-indent",
  "text-transform",
  "text-rendering",
  "direction",
  "writing-mode",
  "margin-left",
  "margin-right",
  "border-left-width",
  "border-right-width",
  "padding-left",
  "padding-right",
  "position",
  "top",
  "bottom",
  "vertical-align",
  "box-decoration-break",
  "transform",
  "column-count",
  "column-width",
  "zoom",
];
const TYPOGRAPHY_PSEUDO_SELECTORS = [
  "::before",
  "::after",
  "::first-letter",
  "::first-line",
];
const ROOT_VIEWPORT_TYPOGRAPHY_PROPERTIES = TYPOGRAPHY_PROPERTIES.filter(
  (property) => property !== "margin-left" && property !== "margin-right",
);

export interface TypographyViewportEntry {
  element: Element;
  includeGenerated: boolean;
  properties: string[];
  signature: string;
}

// CssFragmentedBlockInlineMeasure: plain getBoundingClientRect().width — for
// a block fragmented by CSS columns this is the union of every fragment, not
// a per-fragment measure. Every caller uses it only for coarse ≥0.5px drift
// detection, where the union error is dwarfed by the tolerance (see the ADR
// 0039 fractional fragment-aware amendment). A caller that needs the widest
// live fragment must use the elementContentWidth pattern in
// WebEnhancerSupport.kt instead of this function.
function fragmentedBorderBoxInlineSize(element: Element) {
  if (!element) return 0;
  return Number(element.getBoundingClientRect?.().width) || 0;
}

function styleLengthPx(value: string) {
  return Number.parseFloat(value) || 0;
}

// Shared by the measure-signature builders: exact-font sessions measure the
// content box, browser-metric sessions the border box. Module scope keeps
// the AllocationFreeSignatureIteration promise — no per-paragraph closures.
function paragraphLayoutWidth(element: Element, elementStyle: CSSStyleDeclaration, exactFontLayout: boolean) {
  const value = fragmentedBorderBoxInlineSize(element);
  if (!exactFontLayout) return value;
  return value - styleLengthPx(elementStyle.paddingLeft) - styleLengthPx(elementStyle.paddingRight) -
    styleLengthPx(elementStyle.borderLeftWidth) - styleLengthPx(elementStyle.borderRightWidth);
}

export { DEFAULT_PARAGRAPH_SELECTOR, fragmentedBorderBoxInlineSize, TYPOGRAPHY_PROPERTIES };

export function typographySignature(root: Element, includeGenerated = true) {
  const elements = typographyElements(root);
  let sig = "";
  for (let i = 0; i < elements.length; i++) {
    if (i > 0) sig += "\u001e";
    sig += elementTypographySignature(elements[i], includeGenerated);
  }
  return sig;
}

export function elementTypographySignature(
  element: Element,
  includeGenerated = true,
  properties = TYPOGRAPHY_PROPERTIES,
) {
  const style = getComputedStyle(element);
  let sig = element.tagName;
  for (let i = 0; i < properties.length; i++) {
    sig += "\u001f" + style.getPropertyValue(properties[i]);
  }
  if (includeGenerated) {
    for (let i = 0; i < TYPOGRAPHY_PSEUDO_SELECTORS.length; i++) {
      const selector = TYPOGRAPHY_PSEUDO_SELECTORS[i];
      const pseudo = getComputedStyle(element, selector);
      sig += "\u001f" +
        pseudo.getPropertyValue("content") + "\u001d" +
        pseudo.getPropertyValue("font-family") + "\u001d" +
        pseudo.getPropertyValue("font-size") + "\u001d" +
        pseudo.getPropertyValue("font-weight") + "\u001d" +
        pseudo.getPropertyValue("font-style") + "\u001d" +
        pseudo.getPropertyValue("font-feature-settings") + "\u001d" +
        pseudo.getPropertyValue("font-variation-settings") + "\u001d" +
        pseudo.getPropertyValue("font-variant") + "\u001d" +
        pseudo.getPropertyValue("font-language-override") + "\u001d" +
        pseudo.getPropertyValue("letter-spacing") + "\u001d" +
        pseudo.getPropertyValue("word-spacing");
    }
  }
  return sig;
}

export function captureLayoutWorkViewportTypographyEntries(root: Element) {
  const entries: TypographyViewportEntry[] = [{
    element: root,
    includeGenerated: false,
    properties: ROOT_VIEWPORT_TYPOGRAPHY_PROPERTIES,
    signature: elementTypographySignature(
      root,
      false,
      ROOT_VIEWPORT_TYPOGRAPHY_PROPERTIES,
    ),
  }];
  const elements = typographyElements(root);
  for (let i = 0; i < elements.length; i++) {
    const element = elements[i];
    entries.push({
      element,
      includeGenerated: true,
      properties: TYPOGRAPHY_PROPERTIES,
      signature: elementTypographySignature(element, true, TYPOGRAPHY_PROPERTIES),
    });
  }
  return entries;
}

export function layoutWorkViewportTypographyChanged(root: Element, entries: TypographyViewportEntry[]) {
  // NativeSourceViewportTypographySignature: progressive renderer output is
  // not a layout input. Compare the root plus only source elements that have
  // not yet been replaced, using their pre-work computed typography. This
  // catches viewport media-query changes without treating Tiqian's own
  // line-height/font projection/containing-block CSS as a host mutation.
  for (let i = 0; i < entries.length; i++) {
    const { element, includeGenerated, properties, signature } = entries[i];
    if (element !== root && (
      !element.isConnected || element.closest("[data-tq-rendered='true']")
    )) continue;
    if (elementTypographySignature(element, includeGenerated, properties) !== signature) {
      return true;
    }
  }
  return false;
}

export function typographyElements(root: Element) {
  const elements: Element[] = [];
  const seenGroups = new Set();
  const paragraphs = root.querySelectorAll(DEFAULT_PARAGRAPH_SELECTOR);
  for (let i = 0; i < paragraphs.length; i++) {
    const paragraph = paragraphs[i];
    elements.push(paragraph);
    const rendered = paragraph.hasAttribute("data-tq-rendered");
    const descendants = rendered
      ? paragraph.querySelectorAll("[data-tq-source-semantic], [data-tq-inline-object]")
      : paragraph.querySelectorAll("*");
    for (let j = 0; j < descendants.length; j++) {
      const element = descendants[j];
      const group =
        element.getAttribute("data-tq-link-group") ??
        element.getAttribute("data-tq-inline-group");
      if (group && seenGroups.has(group)) continue;
      if (group) seenGroups.add(group);
      elements.push(element);
    }
  }
  return elements;
}

// AllocationFreeSignatureIteration: the signature builders run on every
// responsive commit and layout-work finish. Indexed loops with direct
// concatenation avoid intermediate arrays and per-paragraph closures, and
// keep the builders on the same shape as #typographySignature.
export function paragraphWidthSignature(root: Element) {
  const paragraphs = root.querySelectorAll(DEFAULT_PARAGRAPH_SELECTOR);
  let signature = "";
  for (let i = 0; i < paragraphs.length; i++) {
    if (i > 0) signature += "\u001f";
    signature += fragmentedBorderBoxInlineSize(paragraphs[i]).toFixed(3);
  }
  return signature;
}

export function responsiveGeometrySignature(root: Element) {
  const paragraphs = root.querySelectorAll(DEFAULT_PARAGRAPH_SELECTOR);
  let signature = String(fragmentedBorderBoxInlineSize(root));
  for (let i = 0; i < paragraphs.length; i++) {
    signature += "\u001f";
    signature += fragmentedBorderBoxInlineSize(paragraphs[i]);
  }
  return signature;
}

export function paragraphMeasureSignature(root: Element, exactFontLayout: boolean) {
  const paragraphs = root.querySelectorAll(DEFAULT_PARAGRAPH_SELECTOR);
  let signature = "";
  for (let i = 0; i < paragraphs.length; i++) {
    if (i > 0) signature += "\u001f";
    signature += paragraphMeasureEntry(paragraphs[i], exactFontLayout);
  }
  return signature;
}

export function paragraphMeasureEntry(paragraph: Element, exactFontLayout: boolean) {
  const style = getComputedStyle(paragraph);
  const fontSize = Number.parseFloat(style.fontSize);
  let width = paragraphLayoutWidth(paragraph, style, exactFontLayout);
  if (!(width > 0)) {
    const parent = paragraph.parentElement;
    if (parent) width = paragraphLayoutWidth(parent, getComputedStyle(parent), exactFontLayout);
  }
  const measure = lineLengthGridMeasure(width, fontSize);
  return measure == null
    ? `invalid:${width.toFixed(3)}:${style.fontSize}`
    : `${Math.fround(fontSize)}:${measure}`;
}
