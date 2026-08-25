import assert from "node:assert/strict";
import test from "node:test";

import {
  allowsSnapshotLayout,
  applyConfiguredHostFontSize,
  captureSourceInlineSize,
  clearIssue,
  conformingSnapshotFontSessionId,
  optionFloat,
  optionsFromJs,
  reportIssue,
  responsiveSourceMeasure,
  restoreAttribute,
  stabilizeContentSizedItemInlineSize,
  withoutSnapshotFontSession,
  withRootDefaults,
} from "./core/engine/lifecycle.js";
import { effectiveLineMeasure } from "./core/engine/responsive-measure.js";

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

const globalNames = ["getComputedStyle", "console"];

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
    snapshotFontSession: null,
    requireSnapshotLayoutWorker: false,
    ...rest,
  };
}

// Minimal fake element: attributes and style declarations back a Map. The
// geometry surface the responsive-measure module reads (getBoundingClientRect,
// getClientRects) defaults to a zero rect and is overridden per test.
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
    getBoundingClientRect: () => ({ width: 0 }),
    getClientRects: () => [],
    parentElement: null,
  };
}

const computedStyles = new Map();

function installFakeComputedStyle() {
  globalThis.getComputedStyle = (element) => {
    const styles = computedStyles.get(element);
    const get = (property) => styles?.get(property) ?? "";
    return {
      getPropertyValue: get,
      get paddingLeft() {
        return get("padding-left");
      },
      get paddingRight() {
        return get("padding-right");
      },
      get borderLeftWidth() {
        return get("border-left-width");
      },
      get borderRightWidth() {
        return get("border-right-width");
      },
    };
  };
}

function setComputedStyle(element, property, value) {
  let styles = computedStyles.get(element);
  if (!styles) {
    styles = new Map();
    computedStyles.set(element, styles);
  }
  styles.set(property, value);
}

test("optionsFromJs decodes the full options object", () => {
  const parsed = optionsFromJs({
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
    requireSnapshotLayoutWorker: true,
    cjkDashCapability: { status: "available", detail: "dash ok" },
    snapshotFontSession: { status: "conforming", sessionId: "s-1", detail: "session" },
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
    snapshotFontSession: { status: "conforming", sessionId: "s-1", detail: "session" },
    requireSnapshotLayoutWorker: true,
  });
});

test("optionsFromJs defaults every field for empty or null input", () => {
  for (const input of [undefined, null, {}]) {
    assert.deepEqual(optionsFromJs(input), fullOptions());
  }
});

test("optionsFromJs turns non-finite fontSize and lineHeight into null", () => {
  assert.equal(optionsFromJs({ fontSize: Infinity }).fontSize, null);
  assert.equal(optionsFromJs({ fontSize: Number.NaN }).fontSize, null);
  assert.equal(optionsFromJs({ fontSize: "abc" }).fontSize, null);
  assert.equal(optionsFromJs({ fontSize: "17" }).fontSize, 17);
  assert.equal(optionsFromJs({ lineHeight: Infinity }).lineHeight, null);
});

test("optionsFromJs decodes capability objects with unavailable as the default status", () => {
  const parsed = optionsFromJs({
    cjkDashCapability: { detail: "no face" },
    snapshotFontSession: { sessionId: "s", detail: "d" },
  });
  assert.deepEqual(parsed.cjkDashCapability, { status: "unavailable", detail: "no face" });
  assert.deepEqual(parsed.snapshotFontSession, { status: "unavailable", sessionId: "s", detail: "d" });

  const withStatus = optionsFromJs({
    cjkDashCapability: { status: "available" },
    snapshotFontSession: { status: "conforming" },
  });
  assert.equal(withStatus.cjkDashCapability.status, "available");
  assert.equal(withStatus.cjkDashCapability.detail, null);
  assert.equal(withStatus.snapshotFontSession.status, "conforming");
  assert.equal(withStatus.snapshotFontSession.sessionId, null);

  assert.equal(optionsFromJs({}).cjkDashCapability, null);
  assert.equal(optionsFromJs({}).snapshotFontSession, null);
});

test("optionFloat returns a finite number and null otherwise", () => {
  assert.equal(optionFloat({ size: 1.5 }, "size"), 1.5);
  assert.equal(optionFloat({ size: "2.25" }, "size"), 2.25);
  assert.equal(optionFloat({ size: Infinity }, "size"), null);
  assert.equal(optionFloat({ size: "abc" }, "size"), null);
  assert.equal(optionFloat({}, "size"), null);
  assert.equal(optionFloat(null, "size"), null);
});

test("conformingSnapshotFontSessionId returns only a conforming, non-blank session id", () => {
  assert.equal(
    conformingSnapshotFontSessionId({ snapshotFontSession: { status: "conforming", sessionId: "s-1" } }),
    "s-1",
  );
  assert.equal(
    conformingSnapshotFontSessionId({ snapshotFontSession: { status: "conforming", sessionId: "  " } }),
    null,
  );
  assert.equal(
    conformingSnapshotFontSessionId({ snapshotFontSession: { status: "conforming", sessionId: "" } }),
    null,
  );
  assert.equal(
    conformingSnapshotFontSessionId({ snapshotFontSession: { status: "conforming", sessionId: null } }),
    null,
  );
  assert.equal(
    conformingSnapshotFontSessionId({ snapshotFontSession: { status: "mismatch", sessionId: "s-1" } }),
    null,
  );
  assert.equal(conformingSnapshotFontSessionId({}), null);
  assert.equal(conformingSnapshotFontSessionId(null), null);
});

test("allowsSnapshotLayout is true only for the all-null snapshot shape", () => {
  assert.equal(allowsSnapshotLayout(fullOptions()), true);
  assert.equal(allowsSnapshotLayout(fullOptions({ fontSize: 16 })), false);
  assert.equal(allowsSnapshotLayout(fullOptions({ lineHeight: 1.5 })), false);
  assert.equal(allowsSnapshotLayout(fullOptions({ firstLineIndentIc: 2 })), false);
  assert.equal(allowsSnapshotLayout(fullOptions({ fontFamilies: { cjk: "CJK" } })), false);
  assert.equal(allowsSnapshotLayout(fullOptions({ fontFamilies: { latinSerif: "Serif" } })), false);
});

test("withoutSnapshotFontSession nulls the session on a shallow copy", () => {
  const options = fullOptions({ snapshotFontSession: { status: "conforming", sessionId: "s-1" } });
  const copy = withoutSnapshotFontSession(options);
  assert.notEqual(copy, options);
  assert.equal(copy.snapshotFontSession, null);
  assert.equal(options.snapshotFontSession.sessionId, "s-1");
  assert.equal(copy.fontFamilies, options.fontFamilies);
  assert.equal(copy.fontSize, options.fontSize);
});

test("withRootDefaults uses the inherited font-family when an option family is null", () => {
  const globals = preserveGlobals(globalNames);
  installFakeComputedStyle();
  const root = {};
  setComputedStyle(root, "font-family", "Inherited, sans-serif");
  try {
    const resolved = withRootDefaults(fullOptions(), root);
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
    const resolved = withRootDefaults(fullOptions(), root);
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
    const resolved = withRootDefaults(options, root);
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
      assert.throws(() => withRootDefaults(fullOptions({ fontSize }), root), /InvalidFontSize/);
    }
    assert.equal(withRootDefaults(fullOptions({ fontSize: 17 }), root).fontSize, 17);
    assert.equal(withRootDefaults(fullOptions(), root).fontSize, null);
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
    const resolved = withRootDefaults(options, root);
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
    reportIssue(issue);
    reportIssue(issue);
    assert.equal(issue.markerCaptured, true);
    assert.equal(issue.originalNameAttribute, "pre-name");
    assert.equal(issue.originalDetailAttribute, "pre-detail");
    assert.equal(element.getAttribute("data-tiqian-capability-issue"), "NoExactFontFace");
    assert.equal(element.getAttribute("data-tiqian-capability-detail"), "x".repeat(512));
    assert.equal(warns.length, 2);
    assert.equal(warns[0], "TiqianWeb skipped paragraph: NoExactFontFace (" + detail + ")");

    clearIssue(issue);
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
    reportIssue(issue);
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
    clearIssue(issue);
    assert.equal(element.getAttribute("data-tiqian-capability-issue"), "keep");
  } finally {
    restoreGlobals(globals);
  }
});

test("restoreAttribute removes for null and sets for a string", () => {
  const element = makeElement({ "data-x": "1" });
  restoreAttribute(element, "data-x", null);
  assert.equal(element.getAttribute("data-x"), null);
  restoreAttribute(element, "data-x", "2");
  assert.equal(element.getAttribute("data-x"), "2");
});

test("applyConfiguredHostFontSize passes null through and returns the set value", () => {
  const element = makeElement();
  assert.equal(applyConfiguredHostFontSize(element, null), null);
  const returned = applyConfiguredHostFontSize(element, 17.5);
  assert.equal(returned, "17.5px");
  assert.equal(element.style.getPropertyValue("font-size"), "17.5px");
  assert.equal(element.style.getPropertyPriority("font-size"), "important");
});

test("responsiveSourceMeasure restores the style attribute even when the measure throws", () => {
  const globals = preserveGlobals(globalNames);
  installFakeComputedStyle();
  globalThis.getComputedStyle = () => {
    throw new Error("measure boom");
  };
  try {
    const absent = makeElement();
    assert.throws(() => responsiveSourceMeasure(absent, 16), /measure boom/);
    assert.equal(absent.getAttribute("style"), null);

    const present = makeElement();
    present.setAttribute("style", "color: red");
    assert.throws(() => responsiveSourceMeasure(present, 16), /measure boom/);
    assert.equal(present.getAttribute("style"), "color: red");
  } finally {
    restoreGlobals(globals);
  }
});

test("responsiveSourceMeasure reads the computed font-size with a 19px default", () => {
  const globals = preserveGlobals(globalNames);
  installFakeComputedStyle();
  try {
    const element = makeElement();
    element.getBoundingClientRect = () => ({ width: 320 });
    // A parsed "16px" font-size is used directly; a non-px value ("2em")
    // falls back to the DEFAULT_FONT_SIZE of 19.
    setComputedStyle(element, "font-size", "16px");
    const parsed = responsiveSourceMeasure(element, null);
    setComputedStyle(element, "font-size", "2em");
    const defaulted = responsiveSourceMeasure(element, null);
    assert.equal(parsed, effectiveLineMeasure(320, 16));
    assert.equal(defaulted, effectiveLineMeasure(320, 19));
    assert.notEqual(parsed, defaulted);
  } finally {
    restoreGlobals(globals);
  }
});

test("stabilizeContentSizedItemInlineSize leaves a stable auto-sized item alone", () => {
  const globals = preserveGlobals(globalNames);
  installFakeComputedStyle();
  const paragraph = makeElement();
  paragraph.getBoundingClientRect = () => ({ width: 300 });
  try {
    const source = { borderBoxSizing: true, borderBoxWidth: 300, contentBoxWidth: 280 };
    assert.equal(stabilizeContentSizedItemInlineSize(paragraph, source), null);
    assert.equal(paragraph.getAttribute("data-tq-host-inline-size"), null);
  } finally {
    restoreGlobals(globals);
  }
});

test("stabilizeContentSizedItemInlineSize pins the host inline size when rawDom shrinks the item", () => {
  const globals = preserveGlobals(globalNames);
  installFakeComputedStyle();
  const paragraph = makeElement();
  paragraph.getBoundingClientRect = () => ({ width: 260 });
  try {
    const source = { borderBoxSizing: true, borderBoxWidth: 300, contentBoxWidth: 280 };
    assert.equal(stabilizeContentSizedItemInlineSize(paragraph, source), "300px");
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
  const paragraph = makeElement();
  paragraph.getBoundingClientRect = () => ({ width: 260 });
  try {
    const source = { borderBoxSizing: false, borderBoxWidth: 300, contentBoxWidth: 280 };
    assert.equal(stabilizeContentSizedItemInlineSize(paragraph, source), "280px");
  } finally {
    restoreGlobals(globals);
  }
});

test("stabilizeContentSizedItemInlineSize declines non-finite or non-positive sizes", () => {
  const globals = preserveGlobals(globalNames);
  installFakeComputedStyle();
  const paragraph = makeElement();
  paragraph.getBoundingClientRect = () => ({ width: 260 });
  try {
    const nonFinite = { borderBoxSizing: true, borderBoxWidth: Number.NaN, contentBoxWidth: 0 };
    assert.equal(stabilizeContentSizedItemInlineSize(paragraph, nonFinite), null);
    const nonPositive = { borderBoxSizing: true, borderBoxWidth: 0, contentBoxWidth: 0 };
    assert.equal(stabilizeContentSizedItemInlineSize(paragraph, nonPositive), null);

    const broken = makeElement();
    broken.getBoundingClientRect = () => ({ width: Number.NaN });
    const source = { borderBoxSizing: true, borderBoxWidth: 300, contentBoxWidth: 280 };
    assert.equal(stabilizeContentSizedItemInlineSize(broken, source), null);
  } finally {
    restoreGlobals(globals);
  }
});

test("captureSourceInlineSize reports the border-box sizing flag from computed style", () => {
  const globals = preserveGlobals(globalNames);
  installFakeComputedStyle();
  const paragraph = makeElement();
  paragraph.getBoundingClientRect = () => ({ width: 300 });
  try {
    setComputedStyle(paragraph, "box-sizing", "border-box");
    const size = captureSourceInlineSize(paragraph);
    assert.equal(size.borderBoxWidth, 300);
    assert.equal(size.contentBoxWidth, 300);
    assert.equal(size.borderBoxSizing, true);

    setComputedStyle(paragraph, "box-sizing", "content-box");
    assert.equal(captureSourceInlineSize(paragraph).borderBoxSizing, false);
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
    const size = captureSourceInlineSize(paragraph);
    assert.equal(size.contentBoxWidth, 278);
  } finally {
    restoreGlobals(globals);
  }
});