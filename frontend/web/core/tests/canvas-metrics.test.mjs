import assert from "node:assert/strict";
import test from "node:test";

import { createFontFamilies } from "../core/engine/canvas-fonts.js";
import { stubFontMetrics, createMetricsResolver, ZERO_ADVANCE_EPSILON } from "../core/engine/canvas-metrics.js";

function createFakeCanvasContext(options = {}) {
  let fontValue = "10px sans-serif";
  const fontAssignments = [];
  let measureCount = 0;
  const measureCalls = [];

  return {
    get font() {
      return fontValue;
    },
    set font(value) {
      fontAssignments.push(value);
      fontValue = value;
    },
    fontAssignments,
    get measureCount() {
      return measureCount;
    },
    measureCalls,
    measureText(text) {
      measureCount += 1;
      measureCalls.push({ font: fontValue, text });
      if (typeof options.measure === "function") {
        return options.measure(text, fontValue, measureCount);
      }
      return {
        width: 10,
        fontBoundingBoxAscent: 12,
        fontBoundingBoxDescent: 3,
        actualBoundingBoxAscent: 10,
        actualBoundingBoxDescent: 2,
        ideographicBaseline: -2.4,
      };
    },
  };
}

test("canvas-metrics module exports resolvers and the epsilon", () => {
  assert.equal(typeof stubFontMetrics, "function");
  assert.equal(typeof createMetricsResolver, "function");
  assert.equal(ZERO_ADVANCE_EPSILON, 0.01);
});

test("stubFontMetrics computes constants for CJK roles with typo pair", () => {
  const cjkRequest = {
    role: "CjkText",
    fontSize: 20,
    fontWeight: 400,
    italic: false,
  };
  const cjkMetrics = stubFontMetrics(cjkRequest);
  assert.equal(cjkMetrics.ascent, 20 * 1.16);
  assert.equal(cjkMetrics.descent, 20 * 0.288);
  assert.equal(cjkMetrics.leading, 0);
  assert.equal(cjkMetrics.source, "RawTables");
  assert.equal(cjkMetrics.typoAscent, 20 * 0.88);
  assert.equal(cjkMetrics.typoDescent, 20 * 0.12);

  const punctRequest = {
    role: "CjkPunctuation",
    fontSize: 25,
  };
  const punctMetrics = stubFontMetrics(punctRequest);
  assert.equal(punctMetrics.ascent, 25 * 1.16);
  assert.equal(punctMetrics.descent, 25 * 0.288);
  assert.equal(punctMetrics.typoAscent, 25 * 0.88);
  assert.equal(punctMetrics.typoDescent, 25 * 0.12);
});

test("stubFontMetrics computes constants for LatinText without typo pair", () => {
  const latinRequest = {
    role: "LatinText",
    fontSize: 20,
  };
  const latinMetrics = stubFontMetrics(latinRequest);
  assert.equal(latinMetrics.ascent, 16); // 20 * 0.8
  assert.equal(latinMetrics.descent, 4); // 20 * 0.2
  assert.equal(latinMetrics.leading, 0);
  assert.equal(latinMetrics.source, "RawTables");
  assert.equal(latinMetrics.typoAscent, null);
  assert.equal(latinMetrics.typoDescent, null);
});

test("stubFontMetrics computes constants for Symbol, Emoji, and Unknown roles without typo pair", () => {
  for (const role of ["Symbol", "Emoji", "Unknown", "Other"]) {
    const metrics = stubFontMetrics({ role, fontSize: 20 });
    assert.equal(metrics.ascent, 18); // 20 * 0.9
    assert.equal(metrics.descent, 5); // 20 * 0.25
    assert.equal(metrics.leading, 0);
    assert.equal(metrics.source, "RawTables");
    assert.equal(metrics.typoAscent, null);
    assert.equal(metrics.typoDescent, null);
  }
});

test("createMetricsResolver selects probe character by role", () => {
  let fakeCtx = null;
  const fonts = createFontFamilies({
    cjk: '"PingFang SC", sans-serif',
    latin: '"Inter", sans-serif',
  });

  const resolver = createMetricsResolver(fonts, () => {
    fakeCtx = createFakeCanvasContext();
    return fakeCtx;
  });

  // CJK probe is "中"
  resolver.resolve({ role: "CjkText", fontSize: 16 });
  assert.equal(fakeCtx.measureCalls[0].text, "中");

  // CjkPunctuation probe is "中"
  resolver.resolve({ role: "CjkPunctuation", fontSize: 16 });
  assert.equal(fakeCtx.measureCalls[1].text, "中");

  // LatinText probe is "Hg"
  resolver.resolve({ role: "LatinText", fontSize: 16 });
  assert.equal(fakeCtx.measureCalls[2].text, "Hg");

  // Symbol probe is "Hg"
  resolver.resolve({ role: "Symbol", fontSize: 16 });
  assert.equal(fakeCtx.measureCalls[3].text, "Hg");
});

test("resolve prioritizes fontBoundingBox over actualBoundingBox", () => {
  const fakeCtx = createFakeCanvasContext({
    measure: () => ({
      width: 16,
      fontBoundingBoxAscent: 15,
      fontBoundingBoxDescent: 4,
      actualBoundingBoxAscent: 11,
      actualBoundingBoxDescent: 2,
      ideographicBaseline: -2,
    }),
  });
  const fonts = createFontFamilies({
    cjk: '"PingFang SC", sans-serif',
    latin: '"Inter", sans-serif',
  });
  const resolver = createMetricsResolver(fonts, () => fakeCtx);

  const metrics = resolver.resolve({ role: "CjkText", fontSize: 16 });
  assert.equal(metrics.ascent, 15);
  assert.equal(metrics.descent, 4);
  assert.equal(metrics.source, "GlyphSampling");
  assert.equal(metrics.leading, 0);
  assert.equal(metrics.typoAscent, 14); // 16 - 2
  assert.equal(metrics.typoDescent, 2);
});

test("resolve falls back to actualBoundingBox when fontBoundingBox is missing or non-positive", () => {
  const fakeCtx = createFakeCanvasContext({
    measure: () => ({
      width: 16,
      fontBoundingBoxAscent: null,
      fontBoundingBoxDescent: 0,
      actualBoundingBoxAscent: 11.5,
      actualBoundingBoxDescent: 2.5,
      ideographicBaseline: -2,
    }),
  });
  const fonts = createFontFamilies({
    cjk: '"PingFang SC", sans-serif',
    latin: '"Inter", sans-serif',
  });
  const resolver = createMetricsResolver(fonts, () => fakeCtx);

  const metrics = resolver.resolve({ role: "CjkText", fontSize: 16 });
  assert.equal(metrics.ascent, 11.5);
  assert.equal(metrics.descent, 2.5);
});

test("resolve skips family when probe width is zero, non-finite, or <= 0.01", () => {
  const measuredFonts = [];
  const fakeCtx = createFakeCanvasContext({
    measure: (_text, font) => {
      measuredFonts.push(font);
      if (font.includes("ZeroWidthFont")) {
        return { width: 0, fontBoundingBoxAscent: 12, fontBoundingBoxDescent: 3 };
      }
      if (font.includes("EpsilonFont")) {
        return { width: 0.01, fontBoundingBoxAscent: 12, fontBoundingBoxDescent: 3 };
      }
      if (font.includes("NaNFont")) {
        return { width: Number.NaN, fontBoundingBoxAscent: 12, fontBoundingBoxDescent: 3 };
      }
      return {
        width: 16,
        fontBoundingBoxAscent: 14,
        fontBoundingBoxDescent: 3,
        ideographicBaseline: -2,
      };
    },
  });

  const fonts = createFontFamilies({
    cjk: '"DefaultCJK", sans-serif',
    latin: '"DefaultLatin", sans-serif',
  });
  const resolver = createMetricsResolver(fonts, () => fakeCtx);

  const result = resolver.resolve({
    role: "CjkText",
    fontSize: 16,
    fontFamilies: ["ZeroWidthFont", "EpsilonFont", "NaNFont", "ValidFont"],
  });

  assert.equal(result.source, "GlyphSampling");
  assert.equal(result.ascent, 14);
  assert.equal(result.descent, 3);
  assert.equal(measuredFonts.length, 4);
  assert.ok(measuredFonts[0].includes("ZeroWidthFont"));
  assert.ok(measuredFonts[1].includes("EpsilonFont"));
  assert.ok(measuredFonts[2].includes("NaNFont"));
  assert.ok(measuredFonts[3].includes("ValidFont"));
});

test("resolve skips family when ascent or descent is unusable", () => {
  const measuredFonts = [];
  const fakeCtx = createFakeCanvasContext({
    measure: (_text, font) => {
      measuredFonts.push(font);
      if (font.includes("NoAscentFont")) {
        return { width: 16, fontBoundingBoxAscent: null, actualBoundingBoxAscent: 0, fontBoundingBoxDescent: 4 };
      }
      if (font.includes("NoDescentFont")) {
        return { width: 16, fontBoundingBoxAscent: 12, fontBoundingBoxDescent: null, actualBoundingBoxDescent: -1 };
      }
      return {
        width: 16,
        fontBoundingBoxAscent: 13,
        fontBoundingBoxDescent: 3,
      };
    },
  });

  const fonts = createFontFamilies({
    cjk: '"DefaultCJK", sans-serif',
    latin: '"DefaultLatin", sans-serif',
  });
  const resolver = createMetricsResolver(fonts, () => fakeCtx);

  const result = resolver.resolve({
    role: "LatinText",
    fontSize: 16,
    fontFamilies: ["NoAscentFont", "NoDescentFont", "GoodFont"],
  });

  assert.equal(result.ascent, 13);
  assert.equal(result.descent, 3);
  assert.equal(measuredFonts.length, 3);
});

test("resolve calculates CJK typo pair from ideographicBaseline and clamps at 0", () => {
  const fakeCtx = createFakeCanvasContext({
    measure: () => ({
      width: 16,
      fontBoundingBoxAscent: 15,
      fontBoundingBoxDescent: 4,
      // ideographicDescent = -(-25) = 25; fontSize = 20 => typoAscent = max(20 - 25, 0) = 0
      ideographicBaseline: -25,
    }),
  });
  const fonts = createFontFamilies({
    cjk: '"PingFang SC", sans-serif',
    latin: '"Inter", sans-serif',
  });
  const resolver = createMetricsResolver(fonts, () => fakeCtx);

  const metrics = resolver.resolve({ role: "CjkText", fontSize: 20 });
  assert.equal(metrics.typoAscent, 0);
  assert.equal(metrics.typoDescent, 25);
});

test("resolve emits null typo pair for non-CJK roles even if ideographicBaseline is present", () => {
  const fakeCtx = createFakeCanvasContext({
    measure: () => ({
      width: 16,
      fontBoundingBoxAscent: 14,
      fontBoundingBoxDescent: 3,
      ideographicBaseline: -2.5,
    }),
  });
  const fonts = createFontFamilies({
    cjk: '"PingFang SC", sans-serif',
    latin: '"Inter", sans-serif',
  });
  const resolver = createMetricsResolver(fonts, () => fakeCtx);

  const latinMetrics = resolver.resolve({ role: "LatinText", fontSize: 16 });
  assert.equal(latinMetrics.typoAscent, null);
  assert.equal(latinMetrics.typoDescent, null);

  const symbolMetrics = resolver.resolve({ role: "Symbol", fontSize: 16 });
  assert.equal(symbolMetrics.typoAscent, null);
  assert.equal(symbolMetrics.typoDescent, null);
});

test("resolve falls back to stub metrics when all stack families fail", () => {
  const fakeCtx = createFakeCanvasContext({
    measure: () => ({
      width: 0,
      fontBoundingBoxAscent: 0,
      fontBoundingBoxDescent: 0,
    }),
  });
  const fonts = createFontFamilies({
    cjk: '"FailedCJK", sans-serif',
    latin: '"FailedLatin", sans-serif',
  });
  const resolver = createMetricsResolver(fonts, () => fakeCtx);

  const metrics = resolver.resolve({
    role: "CjkText",
    fontSize: 20,
    fontFamilies: ["Broken1", "Broken2"],
  });

  assert.equal(metrics.source, "RawTables");
  assert.equal(metrics.ascent, 20 * 1.16);
  assert.equal(metrics.descent, 20 * 0.288);
  assert.equal(metrics.typoAscent, 20 * 0.88);
  assert.equal(metrics.typoDescent, 20 * 0.12);
});

test("cache behavior: identical request hits cache and faceSelectionText is ignored in cache key", () => {
  const fakeCtx = createFakeCanvasContext();
  const fonts = createFontFamilies({
    cjk: '"PingFang SC", sans-serif',
    latin: '"Inter", sans-serif',
  });
  const resolver = createMetricsResolver(fonts, () => fakeCtx);

  const request1 = {
    fontKey: "primary",
    fontSize: 16,
    role: "CjkText",
    locale: "zh-Hans",
    fontWeight: 400,
    italic: false,
    faceSelectionText: "一",
  };
  const request2 = {
    fontKey: "primary",
    fontSize: 16,
    role: "CjkText",
    locale: "zh-Hans",
    fontWeight: 400,
    italic: false,
    faceSelectionText: "二",
  };

  const res1 = resolver.resolve(request1);
  assert.equal(fakeCtx.measureCount, 1);

  const res2 = resolver.resolve(request2);
  assert.equal(fakeCtx.measureCount, 1);
  assert.strictEqual(res1, res2);
});

test("ctx.font is not reassigned when the cssFont string repeats across queries", () => {
  const fakeCtx = createFakeCanvasContext();
  const fonts = createFontFamilies({
    cjk: '"PingFang SC", sans-serif',
    latin: '"Inter", sans-serif',
  });
  const resolver = createMetricsResolver(fonts, () => fakeCtx);

  // Request 1: fontSize 16, normal 400
  resolver.resolve({ role: "CjkText", fontSize: 16 });
  assert.equal(fakeCtx.fontAssignments.length, 1);
  assert.equal(fakeCtx.fontAssignments[0], 'normal 400 16px "PingFang SC", sans-serif');

  // Request 2: different font size -> assigns ctx.font
  resolver.resolve({ role: "CjkText", fontSize: 20 });
  assert.equal(fakeCtx.fontAssignments.length, 2);
  assert.equal(fakeCtx.fontAssignments[1], 'normal 400 20px "PingFang SC", sans-serif');

  // Request 3: Back to fontSize 16, different fontKey so not in cache, but same cssFont as before
  resolver.resolve({ fontKey: "differentKey", role: "CjkText", fontSize: 16 });
  assert.equal(fakeCtx.fontAssignments.length, 3);
  assert.equal(fakeCtx.fontAssignments[2], 'normal 400 16px "PingFang SC", sans-serif');

  // Request 4: Same cssFont again with yet another fontKey
  resolver.resolve({ fontKey: "yetAnotherKey", role: "CjkText", fontSize: 16 });
  // Since currentCanvasFont was already 'normal 400 16px "PingFang SC", sans-serif', ctx.font was not set again!
  assert.equal(fakeCtx.fontAssignments.length, 3);
});
