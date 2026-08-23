// Markdown paragraph lowering for the enhance pipeline.
//
// Plain script, no exports: running it installs globalThis.__TiqianMarkdownLowering
// with the single entry point lower(paragraph, options, helpers). Two consumers
// share this file as the single source of truth: the npm host (importing it for the
// side effect) and the Kotlin runtime bundle, into which the
// generateMarkdownLoweringBridge gradle task embeds this source verbatim.
// Double installation is guarded, so a host that already imported the module cannot
// be re-installed by a second import or by the runtime bundle.
//
// Embedding constraint: the generator wraps this file in a Kotlin raw string,
// so the source must contain no dollar sign, no backtick and no triple
// double-quote sequence. Use string concatenation, never template literals.
//
// The lowerer returns a plain object carrying either the lowered paragraph
// ({ ok: true, lowered: ... }) or the first capability issue it hit
// ({ ok: false, issue: { name, detail } }). The Kotlin phase-2 switchover
// decodes the lowered object; phase 1 only ships and embeds this module.

(function () {
  if (globalThis.__TiqianMarkdownLowering) return;

  var DEFAULT_FONT_SIZE = 19;
  var DEFAULT_LINE_HEIGHT_MULTIPLIER = 1.75;
  var INLINE_EDGE_EPSILON = 0.01;
  var INLINE_OBJECT_REPLACEMENT_CHAR = "\uFFFC";

  var MODE_COLLAPSE = "collapse";
  var MODE_COLLAPSE_PRESERVE_BREAKS = "collapse-preserve-breaks";
  var MODE_PRESERVE = "preserve";

  // FallbackLocalEligibilityTable: the lowering engine classifies opaque
  // inline candidates through globalThis.__TiqianEligibility when that module
  // is installed (the Kotlin runtime installs it on first use). A standalone
  // import in a foreign host may have no eligibility engine yet, so the tag
  // and display tables are mirrored here with the same membership rules.
  var NON_TEXT_INLINE_TAGS = new Set([
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
  var OPAQUE_INLINE_DISPLAYS = new Set(["inline-block", "inline-flex", "inline-grid"]);
  var OPAQUE_INLINE_LEVEL_DISPLAYS = new Set([
    "inline-block",
    "inline-flex",
    "inline-grid",
    "inline",
  ]);

  function resolveEligibilityFunctions() {
    var eligibility = globalThis.__TiqianEligibility;
    if (
      eligibility &&
      typeof eligibility.isNonTextInlineTag === "function" &&
      typeof eligibility.isOpaqueInlineDisplay === "function" &&
      typeof eligibility.isOpaqueInlineLevelDisplay === "function"
    ) {
      return eligibility;
    }
    return {
      isNonTextInlineTag: function (tag) {
        return typeof tag === "string" && NON_TEXT_INLINE_TAGS.has(tag.toUpperCase());
      },
      isOpaqueInlineDisplay: function (display) {
        return typeof display === "string" && OPAQUE_INLINE_DISPLAYS.has(display.trim().toLowerCase());
      },
      isOpaqueInlineLevelDisplay: function (display) {
        return typeof display === "string" && OPAQUE_INLINE_LEVEL_DISPLAYS.has(display.trim().toLowerCase());
      },
    };
  }

  function computedStyle(element, property) {
    return globalThis.getComputedStyle(element).getPropertyValue(property);
  }

  function parseCssPx(value) {
    if (value === null || value === undefined) return null;
    var trimmed = String(value).trim();
    if (trimmed.length < 2 || trimmed.slice(-2) !== "px") return null;
    var number = Number(trimmed.slice(0, -2).trim());
    return Number.isFinite(number) ? number : null;
  }

  // NullCoalescingSubstitute: the Kotlin JS parser that validates the
  // embedded @JsFun body does not accept the ?? operator, so every
  // null-coalescing choice is spelled through this helper instead.
  function firstDefined(value, fallback) {
    return value !== null && value !== undefined ? value : fallback;
  }

  function parseCssLineHeight(value, fontSize) {
    if (value === null || value === undefined) return null;
    var trimmed = String(value).trim();
    var px = parseCssPx(trimmed);
    if (px !== null) return px;
    var number = Number(trimmed);
    return Number.isFinite(number) ? number * fontSize : null;
  }

  function parseCssFontWeight(value) {
    if (value === null || value === undefined) return null;
    var trimmed = String(value).trim().toLowerCase();
    if (trimmed === "normal") return 400;
    if (trimmed === "bold") return 700;
    if (trimmed === "lighter" || trimmed === "bolder") return null;
    var number = Number(trimmed);
    if (!Number.isFinite(number)) return null;
    var weight = Math.trunc(number);
    return Math.min(900, Math.max(1, weight));
  }

  function parseCssItalic(value) {
    if (value === null || value === undefined) return null;
    var trimmed = String(value).trim().toLowerCase();
    if (trimmed === "") return null;
    return trimmed.startsWith("italic") || trimmed.startsWith("oblique");
  }

  function parseCssFontFamilies(value) {
    var families = [];
    var token = "";
    var quote = null;
    var flush = function () {
      var family = token.trim();
      if (
        family.length >= 2 &&
        family[0] === '"' &&
        family[family.length - 1] === '"'
      ) {
        family = family.slice(1, -1);
      } else if (
        family.length >= 2 &&
        family[0] === "'" &&
        family[family.length - 1] === "'"
      ) {
        family = family.slice(1, -1);
      }
      if (family !== "") families.push(family);
      token = "";
    };
    for (var i = 0; i < value.length; i++) {
      var char = value[i];
      if (quote !== null && char === quote) {
        quote = null;
        token += char;
      } else if (quote !== null) {
        token += char;
      } else if (char === "'" || char === '"') {
        quote = char;
        token += char;
      } else if (char === ",") {
        flush();
      } else {
        token += char;
      }
    }
    flush();
    return families;
  }

  function cssWhiteSpaceMode(value, fallback) {
    if (fallback === null || fallback === undefined) fallback = MODE_COLLAPSE;
    var normalized = String(value).trim().toLowerCase();
    if (
      normalized === "normal" ||
      normalized === "nowrap" ||
      normalized === "collapse" ||
      normalized.startsWith("collapse ")
    ) {
      return MODE_COLLAPSE;
    }
    if (
      normalized === "pre-line" ||
      normalized.startsWith("preserve-breaks")
    ) {
      return MODE_COLLAPSE_PRESERVE_BREAKS;
    }
    if (
      normalized === "pre" ||
      normalized === "pre-wrap" ||
      normalized === "break-spaces" ||
      normalized.startsWith("preserve ")
    ) {
      return MODE_PRESERVE;
    }
    return fallback;
  }

  function isCssCollapsibleWhitespace(char) {
    return char === " " || char === "\t" || char === "\n" || char === "\r" || char === "\u000C";
  }

  // CssWhiteSpaceCollapseProjection: DOM source formatting is projected through
  // the host's 'white-space' semantics before it becomes Tiqian source text.
  // Only a real <br> is marked separately as a structural mandatory break.
  // The boundary map keeps every projected span's offsets in the source space
  // so ranges survive the projection for the returned lower object.
  function cssWhiteSpaceCollapseProjection(text, modes, hardBreakOffsets) {
    if (modes.length !== text.length) {
      throw new Error(
        "Whitespace mode count " + modes.length + " must match source length " + text.length,
      );
    }
    var hardBreakSet = new Set();
    for (var hb = 0; hb < hardBreakOffsets.length; hb++) hardBreakSet.add(hardBreakOffsets[hb]);
    var projected = "";
    var boundaryMap = new Int32Array(text.length + 1);
    var pendingStart = -1;
    var pendingEnd = -1;

    var resolvePendingWhitespace = function (emit) {
      if (pendingStart < 0) return;
      var before = projected.length;
      if (emit && projected.length > 0 && projected[projected.length - 1] !== "\n") {
        projected += " ";
      }
      var after = projected.length;
      boundaryMap[pendingStart] = before;
      for (var boundary = pendingStart + 1; boundary <= pendingEnd; boundary++) {
        boundaryMap[boundary] = after;
      }
      pendingStart = -1;
      pendingEnd = -1;
    };

    var deferCollapsedWhitespace = function (index) {
      if (pendingStart < 0) {
        pendingStart = index;
        boundaryMap[index] = projected.length;
      }
      pendingEnd = index + 1;
    };

    var appendPreserved = function (index, char) {
      resolvePendingWhitespace(true);
      boundaryMap[index] = projected.length;
      projected += char;
      boundaryMap[index + 1] = projected.length;
    };

    var index = 0;
    while (index < text.length) {
      if (hardBreakSet.has(index)) {
        resolvePendingWhitespace(false);
        boundaryMap[index] = projected.length;
        projected += "\n";
        boundaryMap[index + 1] = projected.length;
        index += 1;
        continue;
      }
      var char = text[index];
      var mode = modes[index];
      if (mode === MODE_COLLAPSE) {
        if (isCssCollapsibleWhitespace(char)) {
          deferCollapsedWhitespace(index);
        } else {
          appendPreserved(index, char);
        }
        index += 1;
      } else if (mode === MODE_COLLAPSE_PRESERVE_BREAKS) {
        if (char === "\r" || char === "\n") {
          resolvePendingWhitespace(false);
          boundaryMap[index] = projected.length;
          projected += "\n";
          boundaryMap[index + 1] = projected.length;
          if (
            char === "\r" &&
            index + 1 < text.length &&
            text[index + 1] === "\n" &&
            modes[index + 1] === MODE_COLLAPSE_PRESERVE_BREAKS &&
            !hardBreakSet.has(index + 1)
          ) {
            boundaryMap[index + 2] = projected.length;
            index += 2;
          } else {
            index += 1;
          }
        } else if (isCssCollapsibleWhitespace(char)) {
          deferCollapsedWhitespace(index);
          index += 1;
        } else {
          appendPreserved(index, char);
          index += 1;
        }
      } else {
        if (char === "\r") {
          resolvePendingWhitespace(true);
          boundaryMap[index] = projected.length;
          projected += "\n";
          boundaryMap[index + 1] = projected.length;
          if (
            index + 1 < text.length &&
            text[index + 1] === "\n" &&
            modes[index + 1] === MODE_PRESERVE &&
            !hardBreakSet.has(index + 1)
          ) {
            boundaryMap[index + 2] = projected.length;
            index += 2;
          } else {
            index += 1;
          }
        } else {
          appendPreserved(index, char);
          index += 1;
        }
      }
    }
    resolvePendingWhitespace(false);
    boundaryMap[text.length] = projected.length;
    return { text: projected, boundaryMap: boundaryMap };
  }

  function projectionRange(projection, start, end) {
    var projectedStart = projection.boundaryMap[start];
    var projectedEnd = projection.boundaryMap[end];
    if (projectedEnd > projectedStart) return [projectedStart, projectedEnd];
    return null;
  }

  // NestedInlineBoxEdgeOwnership: compare an inline's flow edge with its direct
  // in-flow content boundary. A descendant semantic box owns its own padding,
  // margins and pseudo content, so an outer <sup>/<span> must not reserve that
  // same edge again merely because Range.getClientRects() ends on a deep text leaf.
  function measuredInlineEdge(element, side) {
    var style = getComputedStyle(element);
    var margin = Number.parseFloat(
      side === "start" ? style.marginLeft : style.marginRight,
    ) || 0;
    var boxes = Array.from(element.getClientRects()).filter(function (rect) {
      return rect.width || rect.height;
    });
    if (!boxes.length) return margin;
    var boundary = function (node) {
      if (node.nodeType === Node.TEXT_NODE) {
        var range = document.createRange();
        range.selectNodeContents(node);
        var rects = Array.from(range.getClientRects()).filter(function (rect) {
          return rect.width || rect.height;
        });
        if (!rects.length) return null;
        return side === "start" ? rects[0].left : rects[rects.length - 1].right;
      }
      if (node.nodeType !== Node.ELEMENT_NODE) return null;
      var childStyle = getComputedStyle(node);
      if (
        childStyle.display === "none" ||
        childStyle.position === "absolute" ||
        childStyle.position === "fixed"
      ) {
        return null;
      }
      var rects = Array.from(node.getClientRects()).filter(function (rect) {
        return rect.width || rect.height;
      });
      if (rects.length) {
        var rect = side === "start" ? rects[0] : rects[rects.length - 1];
        var childMargin = Number.parseFloat(
          side === "start" ? childStyle.marginLeft : childStyle.marginRight,
        ) || 0;
        return side === "start" ? rect.left - childMargin : rect.right + childMargin;
      }
      var children = Array.from(node.childNodes);
      if (side === "end") children.reverse();
      for (var i = 0; i < children.length; i++) {
        var value = boundary(children[i]);
        if (value != null) return value;
      }
      return null;
    };
    var contentBoundary = null;
    var firstChildren = Array.from(element.childNodes);
    if (side === "end") firstChildren.reverse();
    for (var i = 0; i < firstChildren.length; i++) {
      contentBoundary = boundary(firstChildren[i]);
      if (contentBoundary != null) break;
    }
    if (contentBoundary == null) return margin;
    var flowEdge = side === "start"
      ? boxes[0].left - margin
      : boxes[boxes.length - 1].right + margin;
    return side === "start"
      ? Math.max(0, contentBoundary - flowEdge)
      : Math.max(0, flowEdge - contentBoundary);
  }

  function measuredInlineBaselineShift(element) {
    if (!element.parentNode || getComputedStyle(element).display === "contents") return 0;
    var makeProbe = function () {
      var probe = document.createElement("span");
      probe.setAttribute("data-tq-baseline-probe", "");
      probe.style.cssText = "display:inline-block!important;width:0!important;height:0!important;" +
        "margin:0!important;padding:0!important;border:0!important;font-size:0!important;" +
        "line-height:0!important;vertical-align:baseline!important;position:static!important;";
      return probe;
    };
    var outer = makeProbe();
    var inner = makeProbe();
    try {
      element.parentNode.insertBefore(outer, element);
      element.insertBefore(inner, element.firstChild);
      return inner.getBoundingClientRect().bottom - outer.getBoundingClientRect().bottom;
    } finally {
      inner.remove();
      outer.remove();
    }
  }

  function measuredOpaqueInlineObjectGeometry(element) {
    var parent = element.parentNode;
    if (!parent) return "";
    var style = getComputedStyle(element);
    if (
      style.position === "absolute" ||
      style.position === "fixed" ||
      style.getPropertyValue("float") !== "none" ||
      style.transform !== "none"
    ) {
      return "";
    }
    var rect = element.getBoundingClientRect();
    if (
      !Number.isFinite(rect.width) ||
      !Number.isFinite(rect.height) ||
      rect.width <= 0 ||
      rect.height <= 0
    ) {
      return "";
    }
    var number = function (value) {
      return Number.parseFloat(value) || 0;
    };
    var probe = document.createElement("span");
    probe.setAttribute("data-tq-baseline-probe", "");
    probe.style.cssText = "display:inline-block!important;width:0!important;height:0!important;" +
      "margin:0!important;padding:0!important;border:0!important;font-size:0!important;" +
      "line-height:0!important;vertical-align:baseline!important;position:static!important;";
    try {
      parent.insertBefore(probe, element.nextSibling);
      var baseline = probe.getBoundingClientRect().bottom;
      var advance = rect.width + number(style.marginLeft) + number(style.marginRight);
      var ascent = Math.max(0, baseline - rect.top + number(style.marginTop));
      var descent = Math.max(0, rect.bottom - baseline + number(style.marginBottom));
      return [advance, ascent, descent].join(",");
    } finally {
      probe.remove();
    }
  }

  function isCloneSafeOpaqueInlineObject(element) {
    if (element.hasAttribute("data-tiqian-static-inline-object")) return true;
    var name = element.localName || "";
    if (name.includes("-")) return false;
    var interactive = "a,button,input,select,textarea,iframe,object,embed,audio,video,canvas,[contenteditable='true'],[tabindex]";
    if (element.matches(interactive) || element.querySelector(interactive)) return false;
    var nodes = [element].concat(Array.from(element.querySelectorAll("*")));
    return !nodes.some(function (node) {
      return Array.from(node.attributes || []).some(function (attr) {
        return attr.name.toLowerCase().startsWith("on");
      });
    });
  }

  // RootGeneratedInlineContentMustStayNative: a pseudo directly on the paragraph
  // has no source range to which InlineBoxSpan can attach. Descendant semantic
  // elements are supported instead: measuredInlineEdge() reserves their actual
  // ::before/::after advance while the one cloned semantic element keeps the host
  // pseudo, copy, accessibility and interaction behavior intact.
  function flowParticipatingPseudoContent(element, pseudo) {
    var style = getComputedStyle(element, pseudo);
    var content = style.getPropertyValue("content").trim();
    if (!content || content === "none" || content === "normal" || content === "\"\"" || content === "''") {
      return null;
    }
    if (style.display === "none" || style.position === "absolute" || style.position === "fixed") {
      return null;
    }
    return content;
  }

  function generatedPseudoContentIssue(element) {
    var pseudos = ["::before", "::after"];
    for (var i = 0; i < pseudos.length; i++) {
      var content = flowParticipatingPseudoContent(element, pseudos[i]);
      if (content !== null) {
        return element.tagName.toLowerCase() + pseudos[i] + ":" + String(content).trim();
      }
    }
    return null;
  }

  // InlineShapingStyleParityContract: TextStyle currently models family, size,
  // weight, italic and baseline shift. The renderer preserves semantic wrappers,
  // so an inherited shaping property that changes only inside such a wrapper
  // would otherwise make browser glyph advances diverge from LayoutResult.
  var unsupportedInlineShapingProperties = [
    "font-feature-settings",
    "font-variation-settings",
    "font-stretch",
    "font-kerning",
    "font-optical-sizing",
    "font-variant-ligatures",
    "font-variant-alternates",
    "font-variant-east-asian",
    "font-variant-caps",
    "font-variant-numeric",
    "font-variant-position",
    "font-language-override",
    "font-size-adjust",
    "word-spacing",
    "text-transform",
    "text-rendering",
  ];

  function inlineShapingStyleIssue(element, paragraph) {
    for (var i = 0; i < unsupportedInlineShapingProperties.length; i++) {
      var property = unsupportedInlineShapingProperties[i];
      if (
        computedStyle(element, property).trim().toLowerCase() !==
        computedStyle(paragraph, property).trim().toLowerCase()
      ) {
        return property;
      }
    }
    return null;
  }

  var graphemeSegmenter = null;
  if (typeof Intl !== "undefined" && Intl.Segmenter) {
    try {
      graphemeSegmenter = new Intl.Segmenter(undefined, { granularity: "grapheme" });
    } catch (error) {
      graphemeSegmenter = null;
    }
  }

  // Grapheme boundaries as UTF-16 offsets, including 0 and the full length.
  // Falls back to code-point traversal when the host has no Intl.Segmenter,
  // mirroring lowererGraphemeBoundaries in WebEnhancerSupport.kt.
  function graphemeBoundaries(value) {
    var boundaries = [0];
    if (graphemeSegmenter) {
      var items = graphemeSegmenter.segment(value);
      for (var iterator = items[Symbol.iterator](), step = iterator.next(); !step.done; step = iterator.next()) {
        var index = step.value.index;
        if (index > 0 && index < value.length) boundaries.push(index);
      }
    } else {
      var offset = 0;
      var points = Array.from(value);
      for (var i = 0; i < points.length; i++) {
        offset += points[i].length;
        if (offset < value.length) boundaries.push(offset);
      }
    }
    boundaries.push(value.length);
    return boundaries;
  }

  // LocaleChannel: the lowerer must answer the same default locale as the
  // Kotlin core TextStyle ("zh-Hans") because locale travels into LayoutInput
  // and drives font selection plus the punctuation profile. An explicit
  // non-empty options.locale overrides that default.
  function resolveLocale(options) {
    return typeof options.locale === "string" && options.locale !== ""
      ? options.locale
      : "zh-Hans";
  }

  function defaultTextStyle(locale) {
    return {
      fontFamilies: [],
      fontSize: DEFAULT_FONT_SIZE,
      fontWeight: 400,
      italic: false,
      baselineShift: 0,
      locale: locale,
    };
  }

  function computedTextStyle(element, fallback) {
    var families = parseCssFontFamilies(computedStyle(element, "font-family"));
    var fontFamilies = families.length > 0 ? families : fallback.fontFamilies;
    var fontSize = firstDefined(parseCssPx(computedStyle(element, "font-size")), fallback.fontSize);
    var fontWeight = firstDefined(parseCssFontWeight(computedStyle(element, "font-weight")), fallback.fontWeight);
    var italic = firstDefined(parseCssItalic(computedStyle(element, "font-style")), fallback.italic);
    return {
      fontFamilies: fontFamilies,
      fontSize: fontSize,
      fontWeight: fontWeight,
      italic: italic,
      baselineShift: fallback.baselineShift,
      locale: fallback.locale,
    };
  }

  function computedInlineBaselineShift(element) {
    var relativeShift = 0;
    if (computedStyle(element, "position").trim().toLowerCase() === "relative") {
      var top = parseCssPx(computedStyle(element, "top"));
      var bottom = parseCssPx(computedStyle(element, "bottom"));
      relativeShift = top !== null ? top : (bottom !== null ? -bottom : 0);
    }
    var verticalAlign = computedStyle(element, "vertical-align").trim().toLowerCase();
    if (verticalAlign === "" || verticalAlign === "baseline") return relativeShift;
    var vaPx = parseCssPx(verticalAlign);
    if (vaPx !== null) return relativeShift - vaPx;
    var measured = measuredInlineBaselineShift(element);
    return Number.isFinite(measured) ? measured : 0;
  }

  function computedInlineStyle(element, fallback) {
    var computed = computedTextStyle(element, fallback.textStyle);
    var localBaselineShift = computedInlineBaselineShift(element);
    return {
      textStyle: {
        fontFamilies: computed.fontFamilies,
        fontSize: computed.fontSize,
        fontWeight: computed.fontWeight,
        italic: computed.italic,
        baselineShift: fallback.textStyle.baselineShift + localBaselineShift,
        locale: computed.locale,
      },
      whiteSpace: cssWhiteSpaceMode(computedStyle(element, "white-space"), fallback.whiteSpace),
      cjkStrongBaseWeight: fallback.cjkStrongBaseWeight,
    };
  }

  function textStylesEqual(left, right) {
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
    for (var i = 0; i < left.fontFamilies.length; i++) {
      if (left.fontFamilies[i] !== right.fontFamilies[i]) return false;
    }
    return true;
  }

  function parseOpaqueInlineObjectGeometry(value) {
    var rawParts = String(value).split(",");
    var parts = [];
    for (var i = 0; i < rawParts.length; i++) {
      var number = Number(rawParts[i]);
      if (Number.isFinite(number)) parts.push(number);
    }
    if (parts.length !== 3) return null;
    var advance = parts[0];
    var ascent = parts[1];
    var descent = parts[2];
    if (!Number.isFinite(advance) || advance <= INLINE_EDGE_EPSILON) return null;
    if (!Number.isFinite(ascent) || ascent < 0 || !Number.isFinite(descent) || descent < 0) {
      return null;
    }
    if (ascent + descent <= INLINE_EDGE_EPSILON) return null;
    return { advance: advance, ascent: ascent, descent: descent };
  }

  // CanonicalPreparedHostStyleProbe: a direct-SSR prepared paragraph carries
  // 'data-tq-rendered', so the public replay CSS intentionally gives it
  // 'line-height: 0' and 'white-space: pre'. When a width miss falls back to
  // runtime layout, those are renderer-owned values rather than host
  // typography. Suppress the replay selector only while sampling computed
  // paragraph styles, then restore the attribute synchronously before any
  // layout mutation can be painted.
  function withCanonicalPreparedHostStyleProbe(paragraph, block) {
    var rendered = paragraph.getAttribute("data-tq-rendered");
    paragraph.removeAttribute("data-tq-rendered");
    try {
      return block();
    } finally {
      if (rendered === null) {
        paragraph.removeAttribute("data-tq-rendered");
      } else {
        paragraph.setAttribute("data-tq-rendered", rendered);
      }
    }
  }

  // ConfiguredFontSizeSingleSource: an explicit engine font size must be live
  // while descendant computed styles are sampled. Otherwise inherited links
  // and code runs are lowered at the host size even though the base run is
  // measured at the override. The host is restored before custody transfer;
  // the renderer then applies the same size for the enhanced paragraph.
  function withConfiguredFontSizeProbe(paragraph, fontSize, block) {
    if (fontSize === null || fontSize === undefined) return block();
    var originalStyle = paragraph.getAttribute("style");
    paragraph.style.setProperty("font-size", String(fontSize) + "px", "important");
    try {
      return block();
    } finally {
      if (originalStyle === null) {
        paragraph.removeAttribute("style");
      } else {
        paragraph.setAttribute("style", originalStyle);
      }
    }
  }

  function canonicalPreparedPlainSource(parent) {
    var result = "";
    var appendNode = function (node) {
      if (node.nodeType === 3) {
        result += node.textContent || "";
        return;
      }
      if (node.nodeType !== 1) return;
      if (node.hasAttribute("data-tq-copy-ignore")) return;
      if (node.hasAttribute("data-tq-src")) {
        var following = node.nextSibling;
        var followingElement = following !== null && following.nodeType === 1 ? following : null;
        var pairedMandatoryBreak = node.hasAttribute("data-tq-hard-break") &&
          followingElement !== null &&
          followingElement.tagName.toUpperCase() === "BR" &&
          followingElement.getAttribute("data-tq-engine-break") === "MandatoryBreak";
        if (!pairedMandatoryBreak) result += node.getAttribute("data-tq-src") || "";
        return;
      }
      if (node.tagName.toUpperCase() === "BR") {
        if (node.getAttribute("data-tq-engine-break") === "MandatoryBreak") result += "\n";
        return;
      }
      var children = node.childNodes;
      for (var index = 0; index < children.length; index++) {
        var child = children.item ? children.item(index) : children[index];
        if (child) appendNode(child);
      }
    };
    var nodes = parent.childNodes;
    for (var index = 0; index < nodes.length; index++) {
      var node = nodes.item ? nodes.item(index) : nodes[index];
      if (node) appendNode(node);
    }
    return result;
  }

  function lowerWithCurrentStyles(paragraph, options, locale, helpers, canonicalPrepared, issue) {
    var fallbackStyle = defaultTextStyle(locale);
    var computedParagraphStyle = computedTextStyle(paragraph, fallbackStyle);
    var fontSize = options.fontSize !== null && options.fontSize !== undefined
      ? options.fontSize
      : computedParagraphStyle.fontSize;
    var baseStyle = {
      fontFamilies: computedParagraphStyle.fontFamilies,
      fontSize: fontSize,
      fontWeight: computedParagraphStyle.fontWeight,
      italic: computedParagraphStyle.italic,
      baselineShift: computedParagraphStyle.baselineShift,
      locale: computedParagraphStyle.locale,
    };
    var lineHeight = options.lineHeight !== null && options.lineHeight !== undefined
      ? options.lineHeight
      : firstDefined(
          parseCssLineHeight(computedStyle(paragraph, "line-height"), fontSize),
          fontSize * DEFAULT_LINE_HEIGHT_MULTIPLIER
        );
    var baseInlineStyle = {
      textStyle: baseStyle,
      whiteSpace: cssWhiteSpaceMode(computedStyle(paragraph, "white-space")),
      cjkStrongBaseWeight: null,
    };

    if (canonicalPrepared) {
      var source = canonicalPreparedPlainSource(paragraph);
      if (String(source).trim() === "") {
        issue.name = "EmptyParagraph";
        issue.detail = "paragraph has no text";
        return null;
      }
      return {
        text: source,
        textStyle: baseStyle,
        lineHeight: lineHeight,
        spans: [],
        decorations: [],
        inlineBoxes: [],
        inlineObjects: [],
        domInlineObjects: [],
        sourceSpans: [],
        sourceBoundaries: [],
        lineBreakSpans: [],
      };
    }

    var pseudoIssue = generatedPseudoContentIssue(paragraph);
    if (pseudoIssue !== null) {
      issue.name = "UnsupportedGeneratedInlineContent";
      issue.detail = pseudoIssue;
      return null;
    }

    var builder = new LoweringBuilder(
      paragraph,
      baseInlineStyle,
      lineHeight,
      options.strongAsEmphasisMarks === true,
      helpers,
      issue,
    );
    if (!builder.appendChildren(paragraph, baseInlineStyle, 0)) return null;
    var lowered = builder.build();
    if (String(lowered.text).trim() === "") {
      issue.name = "EmptyParagraph";
      issue.detail = "paragraph has no text";
      return null;
    }
    return lowered;
  }

  function LoweringBuilder(sourceElement, baseInlineStyle, baseLineHeight, strongAsEmphasisMarks, helpers, issue) {
    this.sourceElement = sourceElement;
    this.baseInlineStyle = baseInlineStyle;
    this.baseLineHeight = baseLineHeight;
    this.strongAsEmphasisMarks = strongAsEmphasisMarks;
    this.helpers = helpers;
    this.issue = issue;
    this.eligibility = resolveEligibilityFunctions();
    this.text = "";
    this.spans = [];
    this.decorations = [];
    this.inlineBoxes = [];
    this.inlineObjects = [];
    this.domInlineObjects = [];
    this.sourceSpans = [];
    this.sourceBoundaries = [];
    this.whitespaceModes = [];
    this.hardBreakOffsets = [];
  }

  LoweringBuilder.prototype.addBoundary = function (offset) {
    if (this.sourceBoundaries.indexOf(offset) < 0) this.sourceBoundaries.push(offset);
  };

  LoweringBuilder.prototype.unsupported = function (name, detail) {
    this.issue.name = name;
    this.issue.detail = detail;
    return false;
  };

  LoweringBuilder.prototype.appendRawText = function (value, whiteSpace) {
    this.text += value;
    for (var i = 0; i < value.length; i++) this.whitespaceModes.push(whiteSpace);
  };

  LoweringBuilder.prototype.appendChildren = function (element, style, depth) {
    var nodes = element.childNodes;
    for (var i = 0; i < nodes.length; i++) {
      var node = nodes.item ? nodes.item(i) : nodes[i];
      if (!node) continue;
      if (!this.appendNode(node, style, depth)) return false;
    }
    return true;
  };

  LoweringBuilder.prototype.appendNode = function (node, style, depth) {
    if (node.nodeType === 3) {
      this.appendText(node.textContent || "", style);
      return true;
    }
    if (node.nodeType === 1) return this.appendElement(node, style, depth);
    return true;
  };

  LoweringBuilder.prototype.appendElement = function (element, style, depth) {
    var tag = element.tagName.toUpperCase();
    if (tag === "BR") {
      this.hardBreakOffsets.push(this.text.length);
      this.appendRawText("\n", style.whiteSpace);
      return true;
    }
    var display = computedStyle(element, "display").trim().toLowerCase();
    var opaqueCandidate = this.eligibility.isNonTextInlineTag(tag) ||
      tag.indexOf("-") !== -1 ||
      this.eligibility.isOpaqueInlineDisplay(display);
    if (opaqueCandidate) {
      if (!this.eligibility.isOpaqueInlineLevelDisplay(display)) {
        return this.unsupported(
          "UnsupportedInlineFormattingContext",
          tag.toLowerCase() + ":" + display,
        );
      }
      if (!isCloneSafeOpaqueInlineObject(element)) {
        return this.unsupported("UnsupportedStatefulInlineObject", tag.toLowerCase());
      }
      return this.appendOpaqueInlineObject(element, style.whiteSpace);
    }
    if (display !== "inline" && display !== "contents") {
      return this.unsupported(
        "UnsupportedInlineFormattingContext",
        tag.toLowerCase() + ":" + display,
      );
    }
    // RuntimeInlineCodeUsesResolvedBrowserFont: build-time snapshots
    // must fail closed without exact monospace font/box evidence, but
    // the live browser already exposes the resolved host font and box
    // metrics. Lower that run normally and let the sliced browser
    // shaping fallback handle Worker-ineligible rich paragraphs.
    var shapingProperty = inlineShapingStyleIssue(element, this.sourceElement);
    if (shapingProperty !== null) {
      return this.unsupported(
        "UnsupportedInlineShapingStyle",
        tag.toLowerCase() + ":" + shapingProperty,
      );
    }
    var inheritedStrongWeight = style.cjkStrongBaseWeight;
    var strongBaseWeight = null;
    if (tag === "STRONG" && this.strongAsEmphasisMarks) {
      strongBaseWeight = inheritedStrongWeight !== null && inheritedStrongWeight !== undefined
        ? inheritedStrongWeight
        : style.textStyle.fontWeight;
    }
    var computed = computedInlineStyle(element, style);
    var elementStyle = computed;
    if (tag === "STRONG" && this.strongAsEmphasisMarks) {
      elementStyle = {
        textStyle: computed.textStyle,
        whiteSpace: computed.whiteSpace,
        cjkStrongBaseWeight: strongBaseWeight,
      };
    }
    return this.appendSemantic(element, elementStyle, depth, strongBaseWeight);
  };

  LoweringBuilder.prototype.appendOpaqueInlineObject = function (element, whiteSpace) {
    var geometry = parseOpaqueInlineObjectGeometry(measuredOpaqueInlineObjectGeometry(element));
    if (!geometry) {
      return this.unsupported("InvalidInlineObjectGeometry", element.tagName.toLowerCase());
    }
    var start = this.text.length;
    this.appendRawText(INLINE_OBJECT_REPLACEMENT_CHAR, whiteSpace);
    var end = this.text.length;
    this.addBoundary(start);
    this.addBoundary(end);
    this.inlineObjects.push({
      start: start,
      end: end,
      advance: geometry.advance,
      ascent: geometry.ascent,
      descent: geometry.descent,
    });
    this.domInlineObjects.push({
      start: start,
      end: end,
      element: element,
      marginRight: firstDefined(parseCssPx(computedStyle(element, "margin-right")), 0),
    });
    return true;
  };

  LoweringBuilder.prototype.appendSemantic = function (element, style, depth, cjkStrongBaseWeight) {
    var inlineStart = measuredInlineEdge(element, "start");
    var inlineEnd = measuredInlineEdge(element, "end");
    if (!Number.isFinite(inlineStart) || !Number.isFinite(inlineEnd)) {
      return this.unsupported("InvalidInlineBoxGeometry", element.tagName.toLowerCase());
    }
    var start = this.text.length;
    if (!this.appendChildren(element, style, depth + 1)) return false;
    var end = this.text.length;
    if (end > start) {
      this.addBoundary(start);
      this.addBoundary(end);
      if (
        Math.abs(inlineStart) >= INLINE_EDGE_EPSILON ||
        Math.abs(inlineEnd) >= INLINE_EDGE_EPSILON
      ) {
        this.inlineBoxes.push({
          start: start,
          end: end,
          inlineStart: inlineStart,
          inlineEnd: inlineEnd,
        });
      }
      var computedColor = computedStyle(element, "color");
      this.sourceSpans.push({
        start: start,
        end: end,
        element: element,
        depth: depth,
        cjkStrongBaseWeight: cjkStrongBaseWeight,
        computedColor: String(computedColor).trim() !== "" ? computedColor : null,
        inlineBoxStyle: {
          inlineStart: inlineStart,
          inlineEnd: inlineEnd,
          marginRight: firstDefined(parseCssPx(computedStyle(element, "margin-right")), 0),
          letterSpacing: firstDefined(parseCssPx(computedStyle(element, "letter-spacing")), 0),
          boxDecorationBreak: computedStyle(element, "box-decoration-break").trim().toLowerCase(),
        },
      });
    }
    return true;
  };

  LoweringBuilder.prototype.appendText = function (value, style) {
    if (value.length === 0) return;
    var strongBaseWeight = style.cjkStrongBaseWeight;
    if (strongBaseWeight === null || strongBaseWeight === undefined) {
      this.appendTextSegment(value, style.textStyle, style.whiteSpace, false);
      return;
    }
    var boundaries = graphemeBoundaries(value);
    var runStart = boundaries[0];
    var runIsCjk = false;
    var hasRun = false;
    for (var i = 0; i + 1 < boundaries.length; i++) {
      var start = boundaries[i];
      var end = boundaries[i + 1];
      if (end <= start) continue;
      var role = this.helpers.classifyRole(value, start, end, style.textStyle.locale);
      var isCjk = role === "cjk-text" || role === "cjk-punctuation";
      if (hasRun && isCjk !== runIsCjk) {
        this.appendStrongTextSegment(value.substring(runStart, start), style, runIsCjk, strongBaseWeight);
        runStart = start;
      }
      runIsCjk = isCjk;
      hasRun = true;
    }
    if (hasRun && runStart < value.length) {
      this.appendStrongTextSegment(value.substring(runStart), style, runIsCjk, strongBaseWeight);
    }
  };

  LoweringBuilder.prototype.appendStrongTextSegment = function (value, style, isCjk, strongBaseWeight) {
    var textStyle;
    if (isCjk) {
      textStyle = {
        fontFamilies: style.textStyle.fontFamilies,
        fontSize: style.textStyle.fontSize,
        fontWeight: strongBaseWeight,
        italic: style.textStyle.italic,
        baselineShift: style.textStyle.baselineShift,
        locale: style.textStyle.locale,
      };
    } else {
      textStyle = style.textStyle;
    }
    this.appendTextSegment(value, textStyle, style.whiteSpace, isCjk);
  };

  LoweringBuilder.prototype.appendTextSegment = function (value, style, whiteSpace, emphasis) {
    if (value.length === 0) return;
    var start = this.text.length;
    this.appendRawText(value, whiteSpace);
    var end = this.text.length;
    if (!textStylesEqual(style, this.baseInlineStyle.textStyle)) {
      this.spans.push({ start: start, end: end, style: style });
      this.addBoundary(start);
      this.addBoundary(end);
    }
    if (emphasis) {
      this.decorations.push({ start: start, end: end, kind: "Emphasis" });
      this.addBoundary(start);
      this.addBoundary(end);
    }
  };

  function mapProjectedRanges(items, projection, copy) {
    var result = [];
    for (var i = 0; i < items.length; i++) {
      var item = items[i];
      var range = projectionRange(projection, item.start, item.end);
      if (!range) continue;
      result.push(copy(item, range));
    }
    return result;
  }

  function buildLineBreakSpans(sourceSpans, projection) {
    var result = [];
    var seen = {};
    for (var i = 0; i < sourceSpans.length; i++) {
      var span = sourceSpans[i];
      var tag = span.element.tagName.toUpperCase();
      if (tag !== "A" && tag !== "CODE") continue;
      var range = projectionRange(projection, span.start, span.end);
      if (!range) continue;
      var key = range[0] + ":" + range[1] + ":ProgressiveTechnical";
      if (seen[key]) continue;
      seen[key] = true;
      result.push({ start: range[0], end: range[1], policy: "ProgressiveTechnical" });
    }
    return result;
  }

  function buildSourceBoundaries(sourceBoundaries, projection, loweredText) {
    var mapped = [];
    var seen = {};
    for (var i = 0; i < sourceBoundaries.length; i++) {
      var boundary = projection.boundaryMap[sourceBoundaries[i]];
      if (boundary > 0 && boundary < loweredText.length && !seen[boundary]) {
        seen[boundary] = true;
        mapped.push(boundary);
      }
    }
    mapped.sort(function (left, right) { return left - right; });
    return mapped;
  }

  LoweringBuilder.prototype.build = function () {
    var projection = cssWhiteSpaceCollapseProjection(this.text, this.whitespaceModes, this.hardBreakOffsets);
    var loweredText = projection.text;
    var spanCopy = function (item, range) {
      return { start: range[0], end: range[1], style: item.style };
    };
    var decorationCopy = function (item, range) {
      return { start: range[0], end: range[1], kind: item.kind };
    };
    var inlineBoxCopy = function (item, range) {
      return {
        start: range[0],
        end: range[1],
        inlineStart: item.inlineStart,
        inlineEnd: item.inlineEnd,
      };
    };
    var inlineObjectCopy = function (item, range) {
      return {
        start: range[0],
        end: range[1],
        advance: item.advance,
        ascent: item.ascent,
        descent: item.descent,
      };
    };
    var domInlineObjectCopy = function (item, range) {
      return {
        start: range[0],
        end: range[1],
        element: item.element,
        marginRight: item.marginRight,
      };
    };
    var sourceSpanCopy = function (item, range) {
      return {
        start: range[0],
        end: range[1],
        element: item.element,
        depth: item.depth,
        cjkStrongBaseWeight: item.cjkStrongBaseWeight,
        computedColor: item.computedColor,
        inlineBoxStyle: item.inlineBoxStyle,
      };
    };
    return {
      text: loweredText,
      textStyle: this.baseInlineStyle.textStyle,
      lineHeight: this.baseLineHeight,
      spans: mapProjectedRanges(this.spans, projection, spanCopy),
      decorations: mapProjectedRanges(this.decorations, projection, decorationCopy),
      inlineBoxes: mapProjectedRanges(this.inlineBoxes, projection, inlineBoxCopy),
      inlineObjects: mapProjectedRanges(this.inlineObjects, projection, inlineObjectCopy),
      domInlineObjects: mapProjectedRanges(this.domInlineObjects, projection, domInlineObjectCopy),
      sourceSpans: mapProjectedRanges(this.sourceSpans, projection, sourceSpanCopy),
      lineBreakSpans: buildLineBreakSpans(this.sourceSpans, projection),
      sourceBoundaries: buildSourceBoundaries(this.sourceBoundaries, projection, loweredText),
    };
  };

  function lower(paragraph, options, helpers) {
    options = options || {};
    var locale = resolveLocale(options);
    var classifyRole = (helpers && typeof helpers.classifyRole === "function")
      ? helpers.classifyRole
      : function () { return "other"; };
    var safeHelpers = { classifyRole: classifyRole };
    var issue = { name: null, detail: null };
    var canonicalPrepared =
      paragraph.getAttribute("data-tq-rendered") === "true" &&
      paragraph.getAttribute("data-tq-canonical-plain") === "true";
    var lowered = withConfiguredFontSizeProbe(paragraph, options.fontSize, function () {
      if (canonicalPrepared) {
        return withCanonicalPreparedHostStyleProbe(paragraph, function () {
          return lowerWithCurrentStyles(paragraph, options, locale, safeHelpers, true, issue);
        });
      }
      return lowerWithCurrentStyles(paragraph, options, locale, safeHelpers, false, issue);
    });
    if (lowered === null || lowered === undefined) {
      return { ok: false, issue: { name: issue.name, detail: issue.detail } };
    }
    return { ok: true, lowered: lowered };
  }

  globalThis.__TiqianMarkdownLowering = {
    lower: lower,
  };
})();