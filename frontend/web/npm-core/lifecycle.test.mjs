import assert from "node:assert/strict";
import test from "node:test";

import "./core/engine/lifecycle.js";

const lifecycle = globalThis.__TiqianLifecycle;

const DEFAULT_CJK_FONT_FAMILY = '"MiSans VF", "PingFang SC", "Noto Sans CJK SC", sans-serif';
const DEFAULT_LATIN_FONT_FAMILY = '"InterVariable", "Inter", "MiSans VF", sans-serif';
const DEFAULT_MONOSPACE_FONT_FAMILY =
  '"JetBrains Mono Variable", "SFMono-Regular", Menlo, Consolas, "MiSans VF", monospace';
const DEFAULT_CJK_SERIF_FONT_FAMILY = '"MetroSungPlus-SC", "Songti SC", serif';
const DEFAULT_LATIN_SERIF_FONT_FAMILY = 'Georgia, "Times New Roman", serif';

function preserveGlobals(names) {
  return names.map((name) => ({
    name,
    own: Object.prototype.hasOwnProperty.call(globalThis, name),
    value: globalThis[name],
  }));
}

function restoreGlobals(entries) {
  for (const { name, own, value } of entries) {
    if (own) globalThis[name] = value;
    else delete globalThis[name];
  }
}

const globalNames = ["getComputedStyle", "__TiqianResponsiveMeasure", "console"];

// Full plain-object EnhanceOptions shape, matching the module's optionsFromJs
// output. fontFamilies is deep-merged so a partial override keeps the nulls.
function fullOptions(overrides = {}) {
  const { fontFamilies, ...rest } = overrides;
  return {
    fontFamilies: {
      cjk: null,
      latin: null,
      monospace: null,
      cjkSerif: null,
      latinSerif: null,
      ...(fontFamilies ?? {}),
    },
    fontSize: null,
    lineHeight: null,
    firstLineIndentIc: 0,
    emphasisDotGapEm: 0.1,
    strongAsEmphasisMarks: false,
    paragraphSelector: "p, li",
    cjkDashCapability: null,
    exactFontSession: null,
    requireExactLayoutWorker: false,
    ...rest,
  };
}

// Minimal fake element: attributes and style declarations back a Map.
function makeElement(initialAttributes = {}) {
  const attributes = new Map(Object.entries(initialAttributes));
  const styleProperties = new Map();
  const style = {
    getPropertyValue: (name) => styleProperties.get(name)?.value ?? "",
    getPropertyPriority: (name) => styleProperties.get(name)?.priority ?? "",
    setProperty(name, value, priority = "") {
      styleProperties.set(name, { value, priority });
    },
    removeProperty(name) {
      styleProperties.delete(name);
    },
  };
  return {
    getAttribute: (name) => attributes.get(name) ?? null,
    setAttribute: (name, value) => attributes.set(name, String(value)),
    removeAttribute: (name) => attributes.delete(name),
    style,
    styleProperties,
  };
}

const computedStyles = new Map();

function installFakeComputedStyle() {
  globalThis.getComputedStyle = (element) => ({
    getPropertyValue: (property) => computedStyles.get(element)?.get(property) ?? "",
  });
}

function setComputedStyle(element, property, value) {
  let styles = computedStyles.get(element);
  if (!styles) {
    styles = new Map();
    computedStyles.set(element, styles);
  }
  styles.set(property, value);
}

function installFakeResponsiveMeasure(overrides = {}) {
  const config = {
    elementContentWidth: 0,
    sourceParagraphWidth: 0,
    effectiveLineMeasure: (width, fontSize) => width,
    ...overrides,
  };
  globalThis.__TiqianResponsiveMeasure = {
    effectiveLineMeasure: config.effectiveLineMeasure,
    elementContentWidth: () => config.elementContentWidth,
    sourceParagraphWidth: () => config.sourceParagraphWidth,
    isCurrentResponsiveMeasure: () => true,
  };
}

test("optionsFromJs decodes the full options object", () => {
  const parsed = lifecycle.optionsFromJs({
    cjkFontFamily: "CJK",
    latinFontFamily: "Latin",
    monospaceFontFamily: "Mono",
    cjkSerifFontFamily: "CJK Serif",
    latinSerifFontFamily: "Latin Serif",
    fontSize: 17,
    lineHeight: 1.6,
    firstLineIndentIc: 2,
    emphasisDotGapEm: 0.25,
    strongAsEmphasisMarks: true,
    paragraphSelector: "article p",
    requireExactLayoutWorker: true,
    cjkDashCapability: { status: "available", detail: "dash ok" },
    exactFontSession: { status: "conforming", sessionId: "s-1", detail: "session" },
  });
  assert.deepEqual(parsed, {
    fontFamilies: {
      cjk: "CJK",
      latin: "Latin",
      monospace: "Mono",
      cjkSerif: "CJK Serif",
      latinSerif: "Latin Serif",
    },
    fontSize: 17,
    lineHeight: 1.6,
    firstLineIndentIc: 2,
    emphasisDotGapEm: 0.25,
    strongAsEmphasisMarks: true,
    paragraphSelector: "article p",
    cjkDashCapability: { status: "available", detail: "dash ok" },
    exactFontSession: { status: "conforming", sessionId: "s-1", detail: "session" },
    requireExactLayoutWorker: true,
  });
});

test("optionsFromJs defaults every field for empty or null input", () => {
  for (const input of [undefined, null, {}]) {
    assert.deepEqual(lifecycle.optionsFromJs(input), fullOptions());
  }
});

test("optionsFromJs turns non-finite fontSize and lineHeight into null", () => {
  assert.equal(lifecycle.optionsFromJs({ fontSize: Infinity }).fontSize, null);
  assert.equal(lifecycle.optionsFromJs({ fontSize: Number.NaN }).fontSize, null);
  assert.equal(lifecycle.optionsFromJs({ fontSize: "abc" }).fontSize, null);
  assert.equal(lifecycle.optionsFromJs({ fontSize: "17" }).fontSize, 17);
  assert.equal(lifecycle.optionsFromJs({ lineHeight: Infinity }).lineHeight, null);
});

test("optionsFromJs decodes capability objects with unavailable as the default status", () => {
  const parsed = lifecycle.optionsFromJs({
    cjkDashCapability: { detail: "no face" },
    exactFontSession: { sessionId: "s", detail: "d" },
  });
  assert.deepEqual(parsed.cjkDashCapability, { status: "unavailable", detail: "no face" });
  assert.deepEqual(parsed.exactFontSession, { status: "unavailable", sessionId: "s", detail: "d" });

  const withStatus = lifecycle.optionsFromJs({
    cjkDashCapability: { status: "available" },
    exactFontSession: { status: "conforming" },
  });
  assert.equal(withStatus.cjkDashCapability.status, "available");
  assert.equal(withStatus.cjkDashCapability.detail, null);
  assert.equal(withStatus.exactFontSession.status, "conforming");
  assert.equal(withStatus.exactFontSession.sessionId, null);

  assert.equal(lifecycle.optionsFromJs({}).cjkDashCapability, null);
  assert.equal(lifecycle.optionsFromJs({}).exactFontSession, null);
});

test("optionFloat returns a finite number and null otherwise", () => {
  assert.equal(lifecycle.optionFloat({ size: 1.5 }, "size"), 1.5);
  assert.equal(lifecycle.optionFloat({ size: "2.25" }, "size"), 2.25);
  assert.equal(lifecycle.optionFloat({ size: Infinity }, "size"), null);
  assert.equal(lifecycle.optionFloat({ size: "abc" }, "size"), null);
  assert.equal(lifecycle.optionFloat({}, "size"), null);
  assert.equal(lifecycle.optionFloat(null, "size"), null);
});

test("conformingExactFontSessionId returns only a conforming, non-blank session id", () => {
  assert.equal(
    lifecycle.conformingExactFontSessionId({ exactFontSession: { status: "conforming", sessionId: "s-1" } }),
    "s-1",
  );
  assert.equal(
    lifecycle.conformingExactFontSessionId({ exactFontSession: { status: "conforming", sessionId: "  " } }),
    null,
  );
  assert.equal(
    lifecycle.conformingExactFontSessionId({ exactFontSession: { status: "conforming", sessionId: "" } }),
    null,
  );
  assert.equal(
    lifecycle.conformingExactFontSessionId({ exactFontSession: { status: "conforming", sessionId: null } }),
    null,
  );
  assert.equal(
    lifecycle.conformingExactFontSessionId({ exactFontSession: { status: "mismatch", sessionId: "s-1" } }),
    null,
  );
  assert.equal(lifecycle.conformingExactFontSessionId({}), null);
  assert.equal(lifecycle.conformingExactFontSessionId(null), null);
});

test("allowsSnapshotExactLayout is true only for the all-null snapshot shape", () => {
  assert.equal(lifecycle.allowsSnapshotExactLayout(fullOptions()), true);
  assert.equal(lifecycle.allowsSnapshotExactLayout(fullOptions({ fontSize: 16 })), false);
  assert.equal(lifecycle.allowsSnapshotExactLayout(fullOptions({ lineHeight: 1.5 })), false);
  assert.equal(lifecycle.allowsSnapshotExactLayout(fullOptions({ firstLineIndentIc: 2 })), false);
  assert.equal(lifecycle.allowsSnapshotExactLayout(fullOptions({ fontFamilies: { cjk: "CJK" } })), false);
  assert.equal(lifecycle.allowsSnapshotExactLayout(fullOptions({ fontFamilies: { latinSerif: "Serif" } })), false);
});

test("withoutExactFontSession nulls the session on a shallow copy", () => {
  const options = fullOptions({ exactFontSession: { status: "conforming", sessionId: "s-1" } });
  const copy = lifecycle.withoutExactFontSession(options);
  assert.notEqual(copy, options);
  assert.equal(copy.exactFontSession, null);
  assert.equal(options.exactFontSession.sessionId, "s-1");
  assert.equal(copy.fontFamilies, options.fontFamilies);
  assert.equal(copy.fontSize, options.fontSize);
});

test("withRootDefaults uses the inherited font-family when an option family is null", () => {
  const globals = preserveGlobals(globalNames);
  installFakeComputedStyle();
  const root = {};
  setComputedStyle(root, "font-family", "Inherited, sans-serif");
  try {
    const resolved = lifecycle.withRootDefaults(fullOptions(), root);
    assert.equal(resolved.fontFamilies.cjk, "Inherited, sans-serif");
    assert.equal(resolved.fontFamilies.latin, "Inherited, sans-serif");
    assert.equal(resolved.fontFamilies.monospace, DEFAULT_MONOSPACE_FONT_FAMILY);
    assert.equal(resolved.fontFamilies.cjkSerif, DEFAULT_CJK_SERIF_FONT_FAMILY);
    assert.equal(resolved.fontFamilies.latinSerif, DEFAULT_LATIN_SERIF_FONT_FAMILY);
  } finally {
    restoreGlobals(globals);
  }
});

test("withRootDefaults falls back to the default families when nothing is inherited", () => {
  const globals = preserveGlobals(globalNames);
  installFakeComputedStyle();
  const root = {};
  setComputedStyle(root, "font-family", "   ");
  try {
    const resolved = lifecycle.withRootDefaults(fullOptions(), root);
    assert.equal(resolved.fontFamilies.cjk, DEFAULT_CJK_FONT_FAMILY);
    assert.equal(resolved.fontFamilies.latin, DEFAULT_LATIN_FONT_FAMILY);
    assert.equal(resolved.fontFamilies.monospace, DEFAULT_MONOSPACE_FONT_FAMILY);
    assert.equal(resolved.fontFamilies.cjkSerif, DEFAULT_CJK_SERIF_FONT_FAMILY);
    assert.equal(resolved.fontFamilies.latinSerif, DEFAULT_LATIN_SERIF_FONT_FAMILY);
  } finally {
    restoreGlobals(globals);
  }
});

test("withRootDefaults lets an explicit option family win over the inherited one", () => {
  const globals = preserveGlobals(globalNames);
  installFakeComputedStyle();
  const root = {};
  setComputedStyle(root, "font-family", "Inherited, sans-serif");
  try {
    const options = fullOptions({ fontFamilies: { cjk: "Option CJK", latin: "Option Latin" } });
    const resolved = lifecycle.withRootDefaults(options, root);
    assert.equal(resolved.fontFamilies.cjk, "Option CJK");
    assert.equal(resolved.fontFamilies.latin, "Option Latin");
    assert.equal(resolved.fontFamilies.monospace, DEFAULT_MONOSPACE_FONT_FAMILY);
    assert.equal(resolved.fontFamilies.cjkSerif, DEFAULT_CJK_SERIF_FONT_FAMILY);
    assert.equal(resolved.fontFamilies.latinSerif, DEFAULT_LATIN_SERIF_FONT_FAMILY);
  } finally {
    restoreGlobals(globals);
  }
});

test("withRootDefaults rejects a non-positive or non-finite fontSize", () => {
  const globals = preserveGlobals(globalNames);
  installFakeComputedStyle();
  const root = {};
  try {
    for (const fontSize of [0, -3, Number.NaN]) {
      assert.throws(() => lifecycle.withRootDefaults(fullOptions({ fontSize }), root), /InvalidFontSize/);
    }
    assert.equal(lifecycle.withRootDefaults(fullOptions({ fontSize: 17 }), root).fontSize, 17);
    assert.equal(lifecycle.withRootDefaults(fullOptions(), root).fontSize, null);
  } finally {
    restoreGlobals(globals);
  }
});

test("withRootDefaults returns a copy and never mutates the input", () => {
  const globals = preserveGlobals(globalNames);
  installFakeComputedStyle();
  const root = {};
  setComputedStyle(root, "font-family", "Inherited, sans-serif");
  try {
    const options = fullOptions();
    const resolved = lifecycle.withRootDefaults(options, root);
    assert.notEqual(resolved, options);
    assert.notEqual(resolved.fontFamilies, options.fontFamilies);
    assert.equal(options.fontFamilies.cjk, null);
    assert.equal(options.fontSize, null);
    assert.equal(options.firstLineIndentIc, 0);
  } finally {
    restoreGlobals(globals);
  }
});

test("reportIssue truncates the detail marker to 512 chars and clearIssue restores originals", () => {
  const globals = preserveGlobals(globalNames);
  const element = makeElement({
    "data-tiqian-capability-issue": "pre-name",
    "data-tiqian-capability-detail": "pre-detail",
  });
  const warns = [];
  globalThis.console.warn = (message) => warns.push(message);
  try {
    const detail = "x".repeat(600);
    const issue = { name: "NoExactFontFace", detail, element, reportToConsole: true };
    lifecycle.reportIssue(issue);
    lifecycle.reportIssue(issue);
    assert.equal(issue.markerCaptured, true);
    assert.equal(issue.originalNameAttribute, "pre-name");
    assert.equal(issue.originalDetailAttribute, "pre-detail");
    assert.equal(element.getAttribute("data-tiqian-capability-issue"), "NoExactFontFace");
    assert.equal(element.getAttribute("data-tiqian-capability-detail"), "x".repeat(512));
    assert.equal(warns.length, 2);
    assert.equal(warns[0], "TiqianWeb skipped paragraph: NoExactFontFace (" + detail + ")");

    lifecycle.clearIssue(issue);
    assert.equal(issue.markerCaptured, false);
    assert.equal(element.getAttribute("data-tiqian-capability-issue"), "pre-name");
    assert.equal(element.getAttribute("data-tiqian-capability-detail"), "pre-detail");
  } finally {
    restoreGlobals(globals);
  }
});

test("reportIssue keeps the console silent when reportToConsole is false", () => {
  const globals = preserveGlobals(globalNames);
  const element = makeElement();
  const warns = [];
  globalThis.console.warn = (message) => warns.push(message);
  try {
    const issue = { name: "MissingGlyph", detail: "d", element, reportToConsole: false };
    lifecycle.reportIssue(issue);
    assert.equal(element.getAttribute("data-tiqian-capability-issue"), "MissingGlyph");
    assert.equal(warns.length, 0);
  } finally {
    restoreGlobals(globals);
  }
});

test("clearIssue is a no-op when no marker was captured", () => {
  const globals = preserveGlobals(globalNames);
  const element = makeElement({ "data-tiqian-capability-issue": "keep" });
  try {
    const issue = { name: "X", detail: "y", element, reportToConsole: false };
    lifecycle.clearIssue(issue);
    assert.equal(element.getAttribute("data-tiqian-capability-issue"), "keep");
  } finally {
    restoreGlobals(globals);
  }
});

test("restoreAttribute removes for null and sets for a string", () => {
  const element = makeElement({ "data-x": "1" });
  lifecycle.restoreAttribute(element, "data-x", null);
  assert.equal(element.getAttribute("data-x"), null);
  lifecycle.restoreAttribute(element, "data-x", "2");
  assert.equal(element.getAttribute("data-x"), "2");
});

test("applyConfiguredHostFontSize passes null through and returns the set value", () => {
  const element = makeElement();
  assert.equal(lifecycle.applyConfiguredHostFontSize(element, null), null);
  const returned = lifecycle.applyConfiguredHostFontSize(element, 17.5);
  assert.equal(returned, "17.5px");
  assert.equal(element.style.getPropertyValue("font-size"), "17.5px");
  assert.equal(element.style.getPropertyPriority("font-size"), "important");
});

test("responsiveSourceMeasure restores the style attribute even when the measure throws", () => {
  const globals = preserveGlobals(globalNames);
  installFakeComputedStyle();
  installFakeResponsiveMeasure({
    effectiveLineMeasure: () => {
      throw new Error("measure boom");
    },
  });
  try {
    const absent = makeElement();
    assert.throws(() => lifecycle.responsiveSourceMeasure(absent, 16), /measure boom/);
    assert.equal(absent.getAttribute("style"), null);

    const present = makeElement();
    present.setAttribute("style", "color: red");
    assert.throws(() => lifecycle.responsiveSourceMeasure(present, 16), /measure boom/);
    assert.equal(present.getAttribute("style"), "color: red");
  } finally {
    restoreGlobals(globals);
  }
});

test("responsiveSourceMeasure reads the computed font-size with a 19px default", () => {
  const globals = preserveGlobals(globalNames);
  installFakeComputedStyle();
  installFakeResponsiveMeasure({
    sourceParagraphWidth: 320,
    effectiveLineMeasure: (width, fontSize) => width + fontSize,
  });
  try {
    const element = makeElement();
    setComputedStyle(element, "font-size", "16px");
    assert.equal(lifecycle.responsiveSourceMeasure(element, null), 336);
    setComputedStyle(element, "font-size", "2em");
    assert.equal(lifecycle.responsiveSourceMeasure(element, null), 339);
  } finally {
    restoreGlobals(globals);
  }
});

test("stabilizeContentSizedItemInlineSize leaves a stable auto-sized item alone", () => {
  const globals = preserveGlobals(globalNames);
  installFakeComputedStyle();
  installFakeResponsiveMeasure({ elementContentWidth: 280 });
  const paragraph = makeElement();
  paragraph.getBoundingClientRect = () => ({ width: 300 });
  try {
    const source = { borderBoxSizing: true, borderBoxWidth: 300, contentBoxWidth: 280 };
    assert.equal(lifecycle.stabilizeContentSizedItemInlineSize(paragraph, source), null);
    assert.equal(paragraph.getAttribute("data-tq-host-inline-size"), null);
  } finally {
    restoreGlobals(globals);
  }
});

test("stabilizeContentSizedItemInlineSize pins the host inline size when custody shrinks the item", () => {
  const globals = preserveGlobals(globalNames);
  installFakeComputedStyle();
  installFakeResponsiveMeasure({ elementContentWidth: 240 });
  const paragraph = makeElement();
  paragraph.getBoundingClientRect = () => ({ width: 260 });
  try {
    const source = { borderBoxSizing: true, borderBoxWidth: 300, contentBoxWidth: 280 };
    assert.equal(lifecycle.stabilizeContentSizedItemInlineSize(paragraph, source), "300px");
    assert.equal(paragraph.style.getPropertyValue("inline-size"), "300px");
    assert.equal(paragraph.style.getPropertyPriority("inline-size"), "important");
    assert.equal(paragraph.getAttribute("data-tq-host-inline-size"), "true");
  } finally {
    restoreGlobals(globals);
  }
});

test("stabilizeContentSizedItemInlineSize selects the content-box width for content-box sizing", () => {
  const globals = preserveGlobals(globalNames);
  installFakeComputedStyle();
  installFakeResponsiveMeasure({ elementContentWidth: 240 });
  const paragraph = makeElement();
  paragraph.getBoundingClientRect = () => ({ width: 260 });
  try {
    const source = { borderBoxSizing: false, borderBoxWidth: 300, contentBoxWidth: 280 };
    assert.equal(lifecycle.stabilizeContentSizedItemInlineSize(paragraph, source), "280px");
  } finally {
    restoreGlobals(globals);
  }
});

test("stabilizeContentSizedItemInlineSize declines non-finite or non-positive sizes", () => {
  const globals = preserveGlobals(globalNames);
  installFakeComputedStyle();
  installFakeResponsiveMeasure({ elementContentWidth: 240 });
  const paragraph = makeElement();
  paragraph.getBoundingClientRect = () => ({ width: 260 });
  try {
    const nonFinite = { borderBoxSizing: true, borderBoxWidth: Number.NaN, contentBoxWidth: 0 };
    assert.equal(lifecycle.stabilizeContentSizedItemInlineSize(paragraph, nonFinite), null);
    const nonPositive = { borderBoxSizing: true, borderBoxWidth: 0, contentBoxWidth: 0 };
    assert.equal(lifecycle.stabilizeContentSizedItemInlineSize(paragraph, nonPositive), null);

    const broken = makeElement();
    broken.getBoundingClientRect = () => ({ width: Number.NaN });
    const source = { borderBoxSizing: true, borderBoxWidth: 300, contentBoxWidth: 280 };
    assert.equal(lifecycle.stabilizeContentSizedItemInlineSize(broken, source), null);
  } finally {
    restoreGlobals(globals);
  }
});

test("captureSourceInlineSize reports the border-box sizing flag from computed style", () => {
  const globals = preserveGlobals(globalNames);
  installFakeComputedStyle();
  installFakeResponsiveMeasure({ elementContentWidth: 260 });
  const paragraph = makeElement();
  paragraph.getBoundingClientRect = () => ({ width: 300 });
  try {
    setComputedStyle(paragraph, "box-sizing", "border-box");
    const size = lifecycle.captureSourceInlineSize(paragraph);
    assert.equal(size.borderBoxWidth, 300);
    assert.equal(size.contentBoxWidth, 260);
    assert.equal(size.borderBoxSizing, true);

    setComputedStyle(paragraph, "box-sizing", "content-box");
    assert.equal(lifecycle.captureSourceInlineSize(paragraph).borderBoxSizing, false);
  } finally {
    restoreGlobals(globals);
  }
});

test("captureSourceInlineSize falls back to computed paddings without the measure bridge", () => {
  const globals = preserveGlobals(globalNames);
  installFakeComputedStyle();
  const paragraph = makeElement();
  paragraph.getBoundingClientRect = () => ({ width: 300 });
  setComputedStyle(paragraph, "padding-left", "10px");
  setComputedStyle(paragraph, "padding-right", "10px");
  setComputedStyle(paragraph, "border-left-width", "1px");
  setComputedStyle(paragraph, "border-right-width", "1px");
  try {
    const size = lifecycle.captureSourceInlineSize(paragraph);
    assert.equal(size.contentBoxWidth, 278);
  } finally {
    restoreGlobals(globals);
  }
});