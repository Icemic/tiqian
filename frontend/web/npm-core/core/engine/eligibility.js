// Paragraph eligibility for the enhance pipeline.
//
// Plain script, no exports: running it installs globalThis.__TiqianEligibility.
// Two consumers share this file as the single source of truth: the npm host
// (importing it for the side effect) and the Kotlin runtime bundle, into
// which the generateEligibilityBridge gradle task embeds this source verbatim.
// Double installation is guarded.
//
// Embedding constraint: the generator wraps this file in a Kotlin raw string,
// so the source must contain no dollar sign and no triple double-quote
// sequence. Use string concatenation, never template literals.

(function () {
  if (globalThis.__TiqianEligibility) return;

  var skippedAncestorSelector =
    ".not-prose, pre, table, .katex, .katex-display, .expressive-code, .tq-paragraph, [data-tiqian-skip]";

  var nonTextInlineTags = new Set([
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

  var opaqueInlineDisplays = new Set(["inline-block", "inline-flex", "inline-grid"]);

  var opaqueInlineLevelDisplays = new Set([
    "inline-block",
    "inline-flex",
    "inline-grid",
    "inline",
  ]);

  function isBlank(text) {
    return text === null || text === undefined || text.trim() === "";
  }

  function isNonTextInlineTag(tag) {
    if (typeof tag !== "string") return false;
    return nonTextInlineTags.has(tag.toUpperCase());
  }

  function isOpaqueInlineDisplay(display) {
    if (typeof display !== "string") return false;
    return opaqueInlineDisplays.has(display.trim().toLowerCase());
  }

  function isOpaqueInlineLevelDisplay(display) {
    if (typeof display !== "string") return false;
    return opaqueInlineLevelDisplays.has(display.trim().toLowerCase());
  }

  function isPureBlockImageParagraph(paragraph) {
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
    var children = paragraph.querySelectorAll(":scope > *");
    if (children.length === 0) return false;
    for (var index = 0; index < children.length; index++) {
      var child = children[index];
      if (
        child.tagName.toUpperCase() !== "IMG" ||
        (globalThis.getComputedStyle(child).getPropertyValue("display") || "").trim().toLowerCase() !== "block"
      ) {
        return false;
      }
    }
    return true;
  }

  function hasOpaqueInlineCandidate(paragraph) {
    if (!paragraph) return false;
    var descendants = paragraph.querySelectorAll("*");
    for (var index = 0; index < descendants.length; index++) {
      var element = descendants[index];
      var tag = element.tagName.toUpperCase();
      var display = (globalThis.getComputedStyle(element).getPropertyValue("display") || "").trim().toLowerCase();
      if (isNonTextInlineTag(tag) || tag.indexOf("-") !== -1 || isOpaqueInlineDisplay(display)) {
        return true;
      }
    }
    return false;
  }

  function shouldTryParagraph(paragraph) {
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

  globalThis.__TiqianEligibility = {
    shouldTryParagraph: shouldTryParagraph,
    isPureBlockImageParagraph: isPureBlockImageParagraph,
    hasOpaqueInlineCandidate: hasOpaqueInlineCandidate,
    isNonTextInlineTag: isNonTextInlineTag,
    isOpaqueInlineDisplay: isOpaqueInlineDisplay,
    isOpaqueInlineLevelDisplay: isOpaqueInlineLevelDisplay,
  };
})();
