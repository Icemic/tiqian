import assert from "node:assert/strict";
import test from "node:test";

import { createFontFamilies } from "../src/engine/canvas-fonts.js";
import type { WebFontFamiliesInstance } from "../src/engine/canvas-fonts.js";
import { stubFontMetrics, createMetricsResolver, ZERO_ADVANCE_EPSILON } from "../src/engine/canvas-metrics.js";
import type {
  CanvasFontMetricsRequest,
  CanvasFontMetricsResult,
  CanvasTextMetricsLike,
  CanvasContextLike,
  CanvasMetricsResolverInstance,
} from "../src/engine/canvas-metrics.js";

interface FakeCanvasOptions {
  measure?: FakeMeasureFn;
}

type FakeMeasureFn = (text: string, font: string, count: number) => CanvasTextMetricsLike;

interface FakeMeasureCall {
  font: string;
  text: string;
}

interface FakeCanvasContext extends CanvasContextLike {
  fontAssignments: string[];
  measureCount: number;
  measureCalls: FakeMeasureCall[];
}

interface FakeImageDataLike {
  data: Uint8ClampedArray;
}

function createFakeCanvasContext(options: FakeCanvasOptions = {}): FakeCanvasContext {
  let fontValue: string = "10px sans-serif";
  const fontAssignments: string[] = [];
  let measureCount: number = 0;
  const measureCalls: FakeMeasureCall[] = [];

  const ctx: FakeCanvasContext = {
    get font(): string {
      return fontValue;
    },
    set font(value: string) {
      fontAssignments.push(value);
      fontValue = value;
    },
    canvas: { width: 0, height: 0 },
    fontAssignments,
    get measureCount(): number {
      return measureCount;
    },
    measureCalls,
    measureText(text: string): CanvasTextMetricsLike {
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
        actualBoundingBoxLeft: 0,
        actualBoundingBoxRight: 10,
      };
    },
    setTransform(_a: number, _b: number, _c: number, _d: number, _e: number, _f: number): void {},
    clearRect(_x: number, _y: number, _w: number, _h: number): void {},
    fillText(_text: string, _x: number, _y: number): void {},
    getImageData(_sx: number, _sy: number, _sw: number, _sh: number): FakeImageDataLike {
      return { data: new Uint8ClampedArray(0) };
    },
  };
  return ctx;
}

test("canvas-metrics module exports resolvers and the epsilon", () => {
  assert.equal(typeof stubFontMetrics, "function");
  assert.equal(typeof createMetricsResolver, "function");
  assert.equal(ZERO_ADVANCE_EPSILON, 0.01);
});

test("stubFontMetrics computes constants for CJK roles with typo pair", () => {
  const cjkRequest: CanvasFontMetricsRequest = {
    role: "CjkText",
    fontSize: 20,
    fontWeight: 400,
    italic: false,
  };
  const cjkMetrics: CanvasFontMetricsResult = stubFontMetrics(cjkRequest);
  assert.equal(cjkMetrics.ascent, 20 * 1.16);
  assert.equal(cjkMetrics.descent, 20 * 0.288);
  assert.equal(cjkMetrics.leading, 0);
  assert.equal(cjkMetrics.source, "RawTables");
  assert.equal(cjkMetrics.typoAscent, 20 * 0.88);
  assert.equal(cjkMetrics.typoDescent, 20 * 0.12);

  const punctRequest: CanvasFontMetricsRequest = {
    role: "CjkPunctuation",
    fontSize: 25,
  };
  const punctMetrics: CanvasFontMetricsResult = stubFontMetrics(punctRequest);
  assert.equal(punctMetrics.ascent, 25 * 1.16);
  assert.equal(punctMetrics.descent, 25 * 0.288);
  assert.equal(punctMetrics.typoAscent, 25 * 0.88);
  assert.equal(punctMetrics.typoDescent, 25 * 0.12);
});

test("stubFontMetrics computes constants for LatinText without typo pair", () => {
  const latinRequest: CanvasFontMetricsRequest = {
    role: "LatinText",
    fontSize: 20,
  };
  const latinMetrics: CanvasFontMetricsResult = stubFontMetrics(latinRequest);
  assert.equal(latinMetrics.ascent, 16);
  assert.equal(latinMetrics.descent, 4);
  assert.equal(latinMetrics.leading, 0);
  assert.equal(latinMetrics.source, "RawTables");
  assert.equal(latinMetrics.typoAscent, null);
  assert.equal(latinMetrics.typoDescent, null);
});

test("stubFontMetrics computes constants for Symbol, Emoji, and Unknown roles without typo pair", () => {
  const roles: string[] = ["Symbol", "Emoji", "Unknown", "Other"];
  for (const role of roles) {
    const metrics: CanvasFontMetricsResult = stubFontMetrics({ role, fontSize: 20 });
    assert.equal(metrics.ascent, 18);
    assert.equal(metrics.descent, 5);
    assert.equal(metrics.leading, 0);
    assert.equal(metrics.source, "RawTables");
    assert.equal(metrics.typoAscent, null);
    assert.equal(metrics.typoDescent, null);
  }
});

test("createMetricsResolver selects probe character by role", () => {
  let fakeCtx: FakeCanvasContext | null = null;
  const fonts: WebFontFamiliesInstance = createFontFamilies({
    cjk: '"PingFang SC", sans-serif',
    latin: '"Inter", sans-serif',
  });

  const resolver: CanvasMetricsResolverInstance = createMetricsResolver(fonts, () => {
    fakeCtx = createFakeCanvasContext();
    return fakeCtx;
  });

  resolver.resolve({ role: "CjkText", fontSize: 16 });
  assert.equal(fakeCtx!.measureCalls[0].text, "\u4e2d");

  resolver.resolve({ role: "CjkPunctuation", fontSize: 16 });
  assert.equal(fakeCtx!.measureCalls[1].text, "\u4e2d");

  resolver.resolve({ role: "LatinText", fontSize: 16 });
  assert.equal(fakeCtx!.measureCalls[2].text, "Hg");

  resolver.resolve({ role: "Symbol", fontSize: 16 });
  assert.equal(fakeCtx!.measureCalls[3].text, "Hg");
});

test("resolve prioritizes fontBoundingBox over actualBoundingBox", () => {
  const fakeCtx: FakeCanvasContext = createFakeCanvasContext({
    measure: (): CanvasTextMetricsLike => ({
      width: 16,
      fontBoundingBoxAscent: 15,
      fontBoundingBoxDescent: 4,
      actualBoundingBoxAscent: 11,
      actualBoundingBoxDescent: 2,
      ideographicBaseline: -2,
      actualBoundingBoxLeft: 0,
      actualBoundingBoxRight: 16,
    }),
  });
  const fonts: WebFontFamiliesInstance = createFontFamilies({
    cjk: '"PingFang SC", sans-serif',
    latin: '"Inter", sans-serif',
  });
  const resolver: CanvasMetricsResolverInstance = createMetricsResolver(fonts, () => fakeCtx);

  const metrics: CanvasFontMetricsResult = resolver.resolve({ role: "CjkText", fontSize: 16 });
  assert.equal(metrics.ascent, 15);
  assert.equal(metrics.descent, 4);
  assert.equal(metrics.source, "GlyphSampling");
  assert.equal(metrics.leading, 0);
  assert.equal(metrics.typoAscent, 14);
  assert.equal(metrics.typoDescent, 2);
});

test("resolve falls back to actualBoundingBox when fontBoundingBox is missing or non-positive", () => {
  const fakeCtx: FakeCanvasContext = createFakeCanvasContext({
    measure: (): CanvasTextMetricsLike => ({
      width: 16,
      fontBoundingBoxAscent: null,
      fontBoundingBoxDescent: 0,
      actualBoundingBoxAscent: 11.5,
      actualBoundingBoxDescent: 2.5,
      ideographicBaseline: -2,
      actualBoundingBoxLeft: 0,
      actualBoundingBoxRight: 16,
    }),
  });
  const fonts: WebFontFamiliesInstance = createFontFamilies({
    cjk: '"PingFang SC", sans-serif',
    latin: '"Inter", sans-serif',
  });
  const resolver: CanvasMetricsResolverInstance = createMetricsResolver(fonts, () => fakeCtx);

  const metrics: CanvasFontMetricsResult = resolver.resolve({ role: "CjkText", fontSize: 16 });
  assert.equal(metrics.ascent, 11.5);
  assert.equal(metrics.descent, 2.5);
});

test("resolve skips family when probe width is zero, non-finite, or <= 0.01", () => {
  const measuredFonts: string[] = [];
  const fakeCtx: FakeCanvasContext = createFakeCanvasContext({
    measure: (_text: string, font: string, _count: number): CanvasTextMetricsLike => {
      measuredFonts.push(font);
      if (font.includes("ZeroWidthFont")) {
        return { width: 0, fontBoundingBoxAscent: 12, fontBoundingBoxDescent: 3, actualBoundingBoxLeft: 0, actualBoundingBoxRight: 0, actualBoundingBoxAscent: 0, actualBoundingBoxDescent: 0 };
      }
      if (font.includes("EpsilonFont")) {
        return { width: 0.01, fontBoundingBoxAscent: 12, fontBoundingBoxDescent: 3, actualBoundingBoxLeft: 0, actualBoundingBoxRight: 0, actualBoundingBoxAscent: 0, actualBoundingBoxDescent: 0 };
      }
      if (font.includes("NaNFont")) {
        return { width: Number.NaN, fontBoundingBoxAscent: 12, fontBoundingBoxDescent: 3, actualBoundingBoxLeft: 0, actualBoundingBoxRight: 0, actualBoundingBoxAscent: 0, actualBoundingBoxDescent: 0 };
      }
      return {
        width: 16,
        fontBoundingBoxAscent: 14,
        fontBoundingBoxDescent: 3,
        ideographicBaseline: -2,
        actualBoundingBoxLeft: 0,
        actualBoundingBoxRight: 16,
        actualBoundingBoxAscent: 0,
        actualBoundingBoxDescent: 0,
      };
    },
  });

  const fonts: WebFontFamiliesInstance = createFontFamilies({
    cjk: '"DefaultCJK", sans-serif',
    latin: '"DefaultLatin", sans-serif',
  });
  const resolver: CanvasMetricsResolverInstance = createMetricsResolver(fonts, () => fakeCtx);

  const result: CanvasFontMetricsResult = resolver.resolve({
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
  const measuredFonts: string[] = [];
  const fakeCtx: FakeCanvasContext = createFakeCanvasContext({
    measure: (_text: string, font: string, _count: number): CanvasTextMetricsLike => {
      measuredFonts.push(font);
      if (font.includes("NoAscentFont")) {
        return { width: 16, fontBoundingBoxAscent: null, actualBoundingBoxAscent: 0, fontBoundingBoxDescent: 4, actualBoundingBoxLeft: 0, actualBoundingBoxRight: 16, actualBoundingBoxDescent: 0 };
      }
      if (font.includes("NoDescentFont")) {
        return { width: 16, fontBoundingBoxAscent: 12, fontBoundingBoxDescent: null, actualBoundingBoxDescent: -1, actualBoundingBoxLeft: 0, actualBoundingBoxRight: 16, actualBoundingBoxAscent: 0 };
      }
      return {
        width: 16,
        fontBoundingBoxAscent: 13,
        fontBoundingBoxDescent: 3,
        actualBoundingBoxLeft: 0,
        actualBoundingBoxRight: 16,
        actualBoundingBoxAscent: 0,
        actualBoundingBoxDescent: 0,
      };
    },
  });

  const fonts: WebFontFamiliesInstance = createFontFamilies({
    cjk: '"DefaultCJK", sans-serif',
    latin: '"DefaultLatin", sans-serif',
  });
  const resolver: CanvasMetricsResolverInstance = createMetricsResolver(fonts, () => fakeCtx);

  const result: CanvasFontMetricsResult = resolver.resolve({
    role: "LatinText",
    fontSize: 16,
    fontFamilies: ["NoAscentFont", "NoDescentFont", "GoodFont"],
  });

  assert.equal(result.ascent, 13);
  assert.equal(result.descent, 3);
  assert.equal(measuredFonts.length, 3);
});

test("resolve calculates CJK typo pair from ideographicBaseline and clamps at 0", () => {
  const fakeCtx: FakeCanvasContext = createFakeCanvasContext({
    measure: (): CanvasTextMetricsLike => ({
      width: 16,
      fontBoundingBoxAscent: 15,
      fontBoundingBoxDescent: 4,
      ideographicBaseline: -25,
      actualBoundingBoxLeft: 0,
      actualBoundingBoxRight: 16,
      actualBoundingBoxAscent: 0,
      actualBoundingBoxDescent: 0,
    }),
  });
  const fonts: WebFontFamiliesInstance = createFontFamilies({
    cjk: '"PingFang SC", sans-serif',
    latin: '"Inter", sans-serif',
  });
  const resolver: CanvasMetricsResolverInstance = createMetricsResolver(fonts, () => fakeCtx);

  const metrics: CanvasFontMetricsResult = resolver.resolve({ role: "CjkText", fontSize: 20 });
  assert.equal(metrics.typoAscent, 0);
  assert.equal(metrics.typoDescent, 25);
});

test("resolve emits null typo pair for non-CJK roles even if ideographicBaseline is present", () => {
  const fakeCtx: FakeCanvasContext = createFakeCanvasContext({
    measure: (): CanvasTextMetricsLike => ({
      width: 16,
      fontBoundingBoxAscent: 14,
      fontBoundingBoxDescent: 3,
      ideographicBaseline: -2.5,
      actualBoundingBoxLeft: 0,
      actualBoundingBoxRight: 16,
      actualBoundingBoxAscent: 0,
      actualBoundingBoxDescent: 0,
    }),
  });
  const fonts: WebFontFamiliesInstance = createFontFamilies({
    cjk: '"PingFang SC", sans-serif',
    latin: '"Inter", sans-serif',
  });
  const resolver: CanvasMetricsResolverInstance = createMetricsResolver(fonts, () => fakeCtx);

  const latinMetrics: CanvasFontMetricsResult = resolver.resolve({ role: "LatinText", fontSize: 16 });
  assert.equal(latinMetrics.typoAscent, null);
  assert.equal(latinMetrics.typoDescent, null);

  const symbolMetrics: CanvasFontMetricsResult = resolver.resolve({ role: "Symbol", fontSize: 16 });
  assert.equal(symbolMetrics.typoAscent, null);
  assert.equal(symbolMetrics.typoDescent, null);
});

test("resolve falls back to stub metrics when all stack families fail", () => {
  const fakeCtx: FakeCanvasContext = createFakeCanvasContext({
    measure: (): CanvasTextMetricsLike => ({
      width: 0,
      fontBoundingBoxAscent: 0,
      fontBoundingBoxDescent: 0,
      actualBoundingBoxLeft: 0,
      actualBoundingBoxRight: 0,
      actualBoundingBoxAscent: 0,
      actualBoundingBoxDescent: 0,
    }),
  });
  const fonts: WebFontFamiliesInstance = createFontFamilies({
    cjk: '"FailedCJK", sans-serif',
    latin: '"FailedLatin", sans-serif',
  });
  const resolver: CanvasMetricsResolverInstance = createMetricsResolver(fonts, () => fakeCtx);

  const metrics: CanvasFontMetricsResult = resolver.resolve({
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
  const fakeCtx: FakeCanvasContext = createFakeCanvasContext();
  const fonts: WebFontFamiliesInstance = createFontFamilies({
    cjk: '"PingFang SC", sans-serif',
    latin: '"Inter", sans-serif',
  });
  const resolver: CanvasMetricsResolverInstance = createMetricsResolver(fonts, () => fakeCtx);

  const request1: CanvasFontMetricsRequest = {
    fontKey: "primary",
    fontSize: 16,
    role: "CjkText",
    locale: "zh-Hans",
    fontWeight: 400,
    italic: false,
    faceSelectionText: "\u4e00",
  };
  const request2: CanvasFontMetricsRequest = {
    fontKey: "primary",
    fontSize: 16,
    role: "CjkText",
    locale: "zh-Hans",
    fontWeight: 400,
    italic: false,
    faceSelectionText: "\u4e8c",
  };

  const res1: CanvasFontMetricsResult = resolver.resolve(request1);
  assert.equal(fakeCtx.measureCount, 1);

  const res2: CanvasFontMetricsResult = resolver.resolve(request2);
  assert.equal(fakeCtx.measureCount, 1);
  assert.strictEqual(res1, res2);
});

test("ctx.font is not reassigned when the cssFont string repeats across queries", () => {
  const fakeCtx: FakeCanvasContext = createFakeCanvasContext();
  const fonts: WebFontFamiliesInstance = createFontFamilies({
    cjk: '"PingFang SC", sans-serif',
    latin: '"Inter", sans-serif',
  });
  const resolver: CanvasMetricsResolverInstance = createMetricsResolver(fonts, () => fakeCtx);

  resolver.resolve({ role: "CjkText", fontSize: 16 });
  assert.equal(fakeCtx.fontAssignments.length, 1);
  assert.equal(fakeCtx.fontAssignments[0], 'normal 400 16px "PingFang SC", sans-serif');

  resolver.resolve({ role: "CjkText", fontSize: 20 });
  assert.equal(fakeCtx.fontAssignments.length, 2);
  assert.equal(fakeCtx.fontAssignments[1], 'normal 400 20px "PingFang SC", sans-serif');

  resolver.resolve({ fontKey: "differentKey", role: "CjkText", fontSize: 16 });
  assert.equal(fakeCtx.fontAssignments.length, 3);
  assert.equal(fakeCtx.fontAssignments[2], 'normal 400 16px "PingFang SC", sans-serif');

  resolver.resolve({ fontKey: "yetAnotherKey", role: "CjkText", fontSize: 16 });
  assert.equal(fakeCtx.fontAssignments.length, 3);
});
