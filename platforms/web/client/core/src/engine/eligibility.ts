// Paragraph eligibility for the enhance pipeline.
//
// Pure predicates over paragraph elements and computed styles. The tag and
// display lookup tables are module-scope constants; the functions read host
// APIs inside their bodies only.

export const skippedAncestorSelector =
  ".not-prose, pre, table, .katex, .katex-display, .expressive-code, .tq-paragraph, [data-tiqian-skip]";

const nonTextInlineTags = new Set([
  "AREA",
  "AUDIO",
  "BUTTON",
  "CANVAS",
  "EMBED",
  "IFRAME",
  "IMG",
  "INPUT",
  "MATH",
  "OBJECT",
  "PICTURE",
  "SCRIPT",
  "SELECT",
  "STYLE",
  "SVG",
  "TEMPLATE",
  "TEXTAREA",
  "VIDEO",
]);

const opaqueInlineDisplays = new Set(["inline-block", "inline-flex", "inline-grid"]);

const opaqueInlineLevelDisplays = new Set([
  "inline-block",
  "inline-flex",
  "inline-grid",
  "inline",
]);

function isBlank(text: string | null | undefined): boolean {
  return text === null || text === undefined || text.trim() === "";
}

export function isNonTextInlineTag(tag: string): boolean {
  if (typeof tag !== "string") return false;
  return nonTextInlineTags.has(tag.toUpperCase());
}

export function isOpaqueInlineDisplay(display: string): boolean {
  if (typeof display !== "string") return false;
  return opaqueInlineDisplays.has(display.trim().toLowerCase());
}

export function isOpaqueInlineLevelDisplay(display: string): boolean {
  if (typeof display !== "string") return false;
  return opaqueInlineLevelDisplays.has(display.trim().toLowerCase());
}

// Computed display resolution prefers the element's owning document view so a
// root inside an iframe reads through its own window; the page global stays
// the fallback. A host without a usable view answers an empty display.
function computedDisplay(element: Element): string {
  const view = element.ownerDocument?.defaultView;
  const getStyle = view?.getComputedStyle ?? globalThis.getComputedStyle;
  if (typeof getStyle !== "function") return "";
  return (getStyle.call(view, element).getPropertyValue("display") || "").trim().toLowerCase();
}

export function isPureBlockImageParagraph(paragraph: Element | null): boolean {
  // A null textContent answers not-blank, matching the Kotlin original's
  // `textContent?.isBlank() != true` early return.
  if (
    !paragraph ||
    paragraph.tagName.toUpperCase() !== "P" ||
    paragraph.textContent === null ||
    !isBlank(paragraph.textContent)
  ) {
    return false;
  }
  const children = paragraph.querySelectorAll(":scope > *");
  if (children.length === 0) return false;
  for (let index = 0; index < children.length; index++) {
    if (children[index].tagName.toUpperCase() !== "IMG" || computedDisplay(children[index]) !== "block") {
      return false;
    }
  }
  return true;
}

export function hasOpaqueInlineCandidate(paragraph: Element | null): boolean {
  if (!paragraph) return false;
  const descendants = paragraph.querySelectorAll("*");
  for (let index = 0; index < descendants.length; index++) {
    const element = descendants[index];
    const tag = element.tagName.toUpperCase();
    if (isNonTextInlineTag(tag) || tag.indexOf("-") !== -1 || isOpaqueInlineDisplay(computedDisplay(element))) {
      return true;
    }
  }
  return false;
}

// OptInStrongSnapshotExclusion probe: v1 snapshots contain only plain
// paragraphs, so a root carrying semantic <strong> content cannot adopt one
// when the host asks for emphasis-mark mapping.
export function hasStrongEmphasis(root: Element): boolean {
  return root.querySelector("strong") !== null;
}

export function shouldTryParagraph(paragraph: Element | null): boolean {
  if (!paragraph) return false;
  if (paragraph.closest(skippedAncestorSelector)) return false;
  if (paragraph.getAttribute("data-tiqian-skip") !== null) return false;
  // `LeafListItemParagraph`: Markdown commonly emits list text directly
  // inside <li>, so a list item is a paragraph-shaped flow owner and must
  // enter the same pipeline. An outer item that owns a nested block stays
  // native as a container; its leaf descendants are still independent
  // candidates. This avoids replacing a nested <ul>/<ol> while preserving
  // list markers and host list semantics.
  if (
    paragraph.tagName.toUpperCase() === "LI" &&
    paragraph.querySelector(":scope > p, :scope > ul, :scope > ol, :scope > blockquote, :scope > pre, :scope > table") !== null
  ) {
    return false;
  }
  // PureBlockImageParagraphExclusion: Markdown commonly wraps a
  // standalone image in <p>. A block image owns no inline text flow for
  // Tiqian to lay out, so leave the host wrapper native without reporting
  // a capability issue. Text mixed with a block image still enters the
  // lowerer and fails atomically as an unsupported formatting context.
  if (isPureBlockImageParagraph(paragraph)) return false;
  if (isBlank(paragraph.textContent) && !hasOpaqueInlineCandidate(paragraph)) return false;
  return true;
}