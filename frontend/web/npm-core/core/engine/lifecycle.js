// Lifecycle helpers for the enhance pipeline: options parsing, capability
// issue markers, and host sizing capture/stabilization (TsHost runtime port,
// Slice 2a).
//
// Plain script, no exports: running it installs globalThis.__TiqianLifecycle.
// Two consumers share this file as the single source of truth: the npm host
// (importing it for the side effect) and the Kotlin runtime bundle, into
// which a future gradle bridge task will embed this source verbatim. Double
// installation is guarded.
//
// Embedding constraint: the generator wraps this file in a Kotlin raw string,
// so the source must contain no dollar sign and no triple double-quote
// sequence. Use string concatenation, never template literals.

(function () {
  if (globalThis.__TiqianLifecycle) return;

  // Constants copied from the Kotlin sources: DEFAULT_EMPHASIS_DOT_GAP_EM in
  // core TextModel.kt, DEFAULT_FONT_SIZE and the default families in
  // WebEnhancerSupport.kt, DEFAULT_PARAGRAPH_SELECTOR in WebEnhancer.kt.
  var DEFAULT_EMPHASIS_DOT_GAP_EM = 0.1;
  var DEFAULT_FONT_SIZE = 19;
  var DEFAULT_PARAGRAPH_SELECTOR = "p, li";
  var CAPABILITY_DETAIL_LIMIT = 512;
  var HOST_INLINE_SIZE_ATTRIBUTE = "data-tq-host-inline-size";
  var DEFAULT_CJK_FONT_FAMILY = '"MiSans VF", "PingFang SC", "Noto Sans CJK SC", sans-serif';
  var DEFAULT_LATIN_FONT_FAMILY = '"InterVariable", "Inter", "MiSans VF", sans-serif';
  var DEFAULT_MONOSPACE_FONT_FAMILY =
    '"JetBrains Mono Variable", "SFMono-Regular", Menlo, Consolas, "MiSans VF", monospace';
  var DEFAULT_CJK_SERIF_FONT_FAMILY = '"MetroSungPlus-SC", "Songti SC", serif';
  var DEFAULT_LATIN_SERIF_FONT_FAMILY = 'Georgia, "Times New Roman", serif';

  // Plain-object reads mirroring the @JsFun option helpers in
  // WebEnhancerSupport.kt. Null and undefined both read as null.
  function optionString(options, name) {
    return options && options[name] != null ? String(options[name]) : null;
  }

  function optionNumber(options, name) {
    if (!options || options[name] == null) return Number.NaN;
    var number = Number(options[name]);
    return Number.isFinite(number) ? number : Number.NaN;
  }

  function optionFloat(options, name) {
    var number = optionNumber(options, name);
    return Number.isFinite(number) ? number : null;
  }

  function optionBoolean(options, name) {
    return options && typeof options[name] === "boolean" ? options[name] : null;
  }

  function optionObject(options, name) {
    return options && options[name] && typeof options[name] === "object" ? options[name] : null;
  }

  // ComputedStylePort: the Kotlin external reads getPropertyValue off the
  // computed style object.
  function computedStyle(element, property) {
    return globalThis.getComputedStyle(element).getPropertyValue(property);
  }

  // CssFragmentedBlockInlineMeasure: getBoundingClientRect().width is the
  // union of every CSS column fragment, so callers use it only for coarse
  // drift detection against the 0.5px tolerance.
  function elementFragmentBorderBoxInlineSize(element) {
    if (!element) return 0;
    return element.getBoundingClientRect ? element.getBoundingClientRect().width : 0;
  }

  // FragmentFractionalContentMeasureFallback: without the responsive measure
  // bridge, subtract the computed inline padding and borders from the border
  // box to approximate the content box.
  function computedContentBoxWidth(paragraph) {
    if (!paragraph) return 0;
    var style = globalThis.getComputedStyle(paragraph);
    var number = function (value) {
      return Number.parseFloat(value) || 0;
    };
    return elementFragmentBorderBoxInlineSize(paragraph) -
      number(style.getPropertyValue("padding-left")) -
      number(style.getPropertyValue("padding-right")) -
      number(style.getPropertyValue("border-left-width")) -
      number(style.getPropertyValue("border-right-width"));
  }

  // The responsive measure module is installed alongside; only the content
  // width read is defensive here, mirroring the Kotlin bridge contract.
  function elementContentWidth(paragraph) {
    var measure = globalThis.__TiqianResponsiveMeasure;
    if (measure && typeof measure.elementContentWidth === "function") {
      return measure.elementContentWidth(paragraph);
    }
    return computedContentBoxWidth(paragraph);
  }

  function effectiveLineMeasure(width, fontSize) {
    return globalThis.__TiqianResponsiveMeasure.effectiveLineMeasure(width, fontSize);
  }

  function sourceParagraphWidth(paragraph) {
    return globalThis.__TiqianResponsiveMeasure.sourceParagraphWidth(paragraph);
  }

  // Parse a "Npx" length; anything else reads as null.
  function parseCssPx(value) {
    var trimmed = value.trim();
    if (!trimmed.endsWith("px")) return null;
    var stripped = trimmed.slice(0, -2).trim();
    if (stripped.length === 0) return null;
    var number = Number(stripped);
    return Number.isNaN(number) ? null : number;
  }

  // EnhanceOptionsJsPort: decode the host options bag into the plain-object
  // EnhanceOptions shape (WebEnhancerParagraphLifecycle.kt optionsFromJs).
  function optionsFromJs(options) {
    var cjk = optionString(options, "cjkFontFamily");
    var latin = optionString(options, "latinFontFamily");
    var monospace = optionString(options, "monospaceFontFamily");
    var cjkSerif = optionString(options, "cjkSerifFontFamily");
    var latinSerif = optionString(options, "latinSerifFontFamily");
    var fontSize = optionFloat(options, "fontSize");
    var lineHeight = optionFloat(options, "lineHeight");
    var firstLineIndentIc = optionFloat(options, "firstLineIndentIc");
    if (firstLineIndentIc === null) firstLineIndentIc = 0;
    var emphasisDotGapEm = optionFloat(options, "emphasisDotGapEm");
    if (emphasisDotGapEm === null) emphasisDotGapEm = DEFAULT_EMPHASIS_DOT_GAP_EM;
    var strongAsEmphasisMarks = optionBoolean(options, "strongAsEmphasisMarks");
    if (strongAsEmphasisMarks === null) strongAsEmphasisMarks = false;
    var paragraphSelector = optionString(options, "paragraphSelector");
    if (paragraphSelector === null) paragraphSelector = DEFAULT_PARAGRAPH_SELECTOR;
    var requireExactLayoutWorker = optionBoolean(options, "requireExactLayoutWorker");
    if (requireExactLayoutWorker === null) requireExactLayoutWorker = false;
    var dashCapabilityObject = optionObject(options, "cjkDashCapability");
    var cjkDashCapability = null;
    if (dashCapabilityObject != null) {
      cjkDashCapability = {
        status: optionString(dashCapabilityObject, "status"),
        detail: optionString(dashCapabilityObject, "detail"),
      };
      if (cjkDashCapability.status === null) cjkDashCapability.status = "unavailable";
    }
    var exactFontSessionObject = optionObject(options, "exactFontSession");
    var exactFontSession = null;
    if (exactFontSessionObject != null) {
      exactFontSession = {
        status: optionString(exactFontSessionObject, "status"),
        sessionId: optionString(exactFontSessionObject, "sessionId"),
        detail: optionString(exactFontSessionObject, "detail"),
      };
      if (exactFontSession.status === null) exactFontSession.status = "unavailable";
    }
    return {
      fontFamilies: {
        cjk: cjk,
        latin: latin,
        monospace: monospace,
        cjkSerif: cjkSerif,
        latinSerif: latinSerif,
      },
      fontSize: fontSize,
      lineHeight: lineHeight,
      firstLineIndentIc: firstLineIndentIc,
      emphasisDotGapEm: emphasisDotGapEm,
      strongAsEmphasisMarks: strongAsEmphasisMarks,
      paragraphSelector: paragraphSelector,
      cjkDashCapability: cjkDashCapability,
      exactFontSession: exactFontSession,
      requireExactLayoutWorker: requireExactLayoutWorker,
    };
  }

  function conformingExactFontSessionId(options) {
    var session = options && options.exactFontSession;
    if (!session || session.status !== "conforming" ||
        typeof session.sessionId !== "string" || session.sessionId.trim().length === 0) {
      return null;
    }
    return session.sessionId;
  }

  function allowsSnapshotExactLayout(options) {
    return options.fontSize == null &&
      options.lineHeight == null &&
      options.firstLineIndentIc === 0 &&
      options.fontFamilies.cjk == null &&
      options.fontFamilies.latin == null &&
      options.fontFamilies.monospace == null &&
      options.fontFamilies.cjkSerif == null &&
      options.fontFamilies.latinSerif == null;
  }

  function withoutExactFontSession(options) {
    var copy = Object.assign({}, options);
    copy.exactFontSession = null;
    return copy;
  }

  // WithRootDefaultsPort: resolve the five families from the option, the
  // inherited font-family, or the defaults, without mutating the input.
  function withRootDefaults(options, root) {
    if (options.fontSize != null && (!Number.isFinite(options.fontSize) || options.fontSize <= 0)) {
      throw new Error("InvalidFontSize");
    }
    var inherited = computedStyle(root, "font-family").trim();
    if (inherited.length === 0) inherited = null;
    var families = options.fontFamilies || {};
    var resolvedCjk = families.cjk != null ? families.cjk : (inherited != null ? inherited : DEFAULT_CJK_FONT_FAMILY);
    var resolvedLatin = families.latin != null ? families.latin : (inherited != null ? inherited : DEFAULT_LATIN_FONT_FAMILY);
    var resolvedMonospace = families.monospace != null ? families.monospace : DEFAULT_MONOSPACE_FONT_FAMILY;
    var resolvedCjkSerif = families.cjkSerif != null ? families.cjkSerif : DEFAULT_CJK_SERIF_FONT_FAMILY;
    var resolvedLatinSerif = families.latinSerif != null ? families.latinSerif : DEFAULT_LATIN_SERIF_FONT_FAMILY;
    return Object.assign({}, options, {
      fontFamilies: {
        cjk: resolvedCjk,
        latin: resolvedLatin,
        monospace: resolvedMonospace,
        cjkSerif: resolvedCjkSerif,
        latinSerif: resolvedLatinSerif,
      },
    });
  }

  // PendingCapabilityIsObservableNotTerminal: the semantic paragraph is kept
  // native while the asynchronous dash-face probe is in flight; reserve the
  // console warning for the retry's final unavailable/mismatch result.
  function reportIssue(issue) {
    if (!issue.markerCaptured) {
      issue.originalNameAttribute = issue.element.getAttribute("data-tiqian-capability-issue");
      issue.originalDetailAttribute = issue.element.getAttribute("data-tiqian-capability-detail");
      issue.markerCaptured = true;
    }
    issue.element.setAttribute("data-tiqian-capability-issue", issue.name);
    issue.element.setAttribute("data-tiqian-capability-detail", issue.detail.slice(0, CAPABILITY_DETAIL_LIMIT));
    if (issue.reportToConsole) {
      console.warn("TiqianWeb skipped paragraph: " + issue.name + " (" + issue.detail + ")");
    }
  }

  function clearIssue(issue) {
    if (!issue.markerCaptured) return;
    restoreAttribute(issue.element, "data-tiqian-capability-issue", issue.originalNameAttribute);
    restoreAttribute(issue.element, "data-tiqian-capability-detail", issue.originalDetailAttribute);
    issue.markerCaptured = false;
  }

  function restoreAttribute(element, name, value) {
    if (value == null) {
      element.removeAttribute(name);
    } else {
      element.setAttribute(name, value);
    }
  }

  function captureSourceInlineSize(paragraph) {
    return {
      borderBoxWidth: elementFragmentBorderBoxInlineSize(paragraph),
      contentBoxWidth: elementContentWidth(paragraph),
      borderBoxSizing: computedStyle(paragraph, "box-sizing").trim().toLowerCase() === "border-box",
    };
  }

  function applyConfiguredHostFontSize(paragraph, fontSize) {
    if (fontSize == null) return null;
    paragraph.style.setProperty("font-size", fontSize + "px", "important");
    return paragraph.style.getPropertyValue("font-size");
  }

  function responsiveSourceMeasure(paragraph, configuredFontSize) {
    if (configuredFontSize == null) {
      var computedFontSize = parseCssPx(computedStyle(paragraph, "font-size"));
      if (computedFontSize === null) computedFontSize = DEFAULT_FONT_SIZE;
      return effectiveLineMeasure(sourceParagraphWidth(paragraph), computedFontSize);
    }
    var originalStyle = paragraph.getAttribute("style");
    paragraph.style.setProperty("font-size", configuredFontSize + "px", "important");
    try {
      return effectiveLineMeasure(sourceParagraphWidth(paragraph), configuredFontSize);
    } finally {
      if (originalStyle == null) {
        paragraph.removeAttribute("style");
      } else {
        paragraph.setAttribute("style", originalStyle);
      }
    }
  }

  // SourceMeasureBeforeCustodyTransfer: flex/grid items and descendants of
  // shrink-to-fit ancestors can derive their used inline size from the
  // semantic children that Tiqian moves into source custody, so the
  // before/after used size detects the real dependency instead of guessing
  // parent display modes. Ordinary blocks keep their host auto sizing; only a
  // custody-induced width change is stabilized.
  function stabilizeContentSizedItemInlineSize(paragraph, source) {
    var empty = captureSourceInlineSize(paragraph);
    var sourceUsedInlineSize = source.borderBoxSizing ? source.borderBoxWidth : source.contentBoxWidth;
    var emptyUsedInlineSize = source.borderBoxSizing ? empty.borderBoxWidth : empty.contentBoxWidth;
    if (!Number.isFinite(sourceUsedInlineSize) || sourceUsedInlineSize <= 0 ||
        !Number.isFinite(emptyUsedInlineSize) ||
        Math.abs(sourceUsedInlineSize - emptyUsedInlineSize) < 0.5) {
      return null;
    }
    var usedInlineSize = sourceUsedInlineSize;
    if (!Number.isFinite(usedInlineSize) || usedInlineSize <= 0) return null;
    var serialized = usedInlineSize + "px";
    paragraph.style.setProperty("inline-size", serialized, "important");
    paragraph.setAttribute(HOST_INLINE_SIZE_ATTRIBUTE, "true");
    return serialized;
  }

  globalThis.__TiqianLifecycle = {
    optionsFromJs: optionsFromJs,
    optionFloat: optionFloat,
    conformingExactFontSessionId: conformingExactFontSessionId,
    allowsSnapshotExactLayout: allowsSnapshotExactLayout,
    withoutExactFontSession: withoutExactFontSession,
    withRootDefaults: withRootDefaults,
    reportIssue: reportIssue,
    clearIssue: clearIssue,
    restoreAttribute: restoreAttribute,
    captureSourceInlineSize: captureSourceInlineSize,
    applyConfiguredHostFontSize: applyConfiguredHostFontSize,
    responsiveSourceMeasure: responsiveSourceMeasure,
    stabilizeContentSizedItemInlineSize: stabilizeContentSizedItemInlineSize,
  };
})();