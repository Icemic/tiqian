// Canvas font metrics measurement and stub fallback (TsHost runtime port,
// Slice 4a part 1). Ports WebCanvasFontMetricsResolver and StubFontMetricsResolver
// from WebCanvasTextShaper.kt and FontMetrics.kt.
//
// Role values on this layer are the Kotlin FontRole enum NAMES:
// "CjkText", "CjkPunctuation", "LatinText", "Symbol", "Emoji", "Unknown".
// They are NOT the markdown-lowering role strings ("cjk-text" etc.) — that is
// a different layer mapped through classifyFontRole.
//
// Number formatting: arithmetic is plain JS numbers (Kotlin/JS Float math
// compiles without 32-bit rounding in this product).
//
// Plain script, no exports: running it installs globalThis.__TiqianCanvasMetrics.
// The single consumer is the npm host (importing it for the side effect);
// duplicate installation is guarded.

import type { FontRoleName, WebFontFamiliesInstance } from "./canvas-fonts.js";

interface MetricsCanvasSize {
  width: number;
  height: number;
}

type MetricsMeasureTextFn = (text: string) => CanvasTextMetricsLike | TextMetrics;

type MetricsSetTransformFn = (a: number, b: number, c: number, d: number, e: number, f: number) => void;

type MetricsClearRectFn = (x: number, y: number, w: number, h: number) => void;

type MetricsFillTextFn = (text: string, x: number, y: number) => void;

interface MetricsImageDataLike {
  data: Uint8ClampedArray | ArrayLike<number>;
}

type MetricsGetImageDataFn = (sx: number, sy: number, sw: number, sh: number) => ImageData | MetricsImageDataLike;

type MetricsResolveFn = (request: CanvasFontMetricsRequest) => CanvasFontMetricsResult;

type MetricsCreateResolverFn = (
  fonts: WebFontFamiliesInstance,
  createCanvasContext: MetricsCreateCanvasContextFn,
) => CanvasMetricsResolverInstance;

type MetricsCreateCanvasContextFn = () => CanvasContextLike;

interface CanvasMetricsGlobal {
  stubFontMetrics: MetricsResolveFn;
  createMetricsResolver: MetricsCreateResolverFn;
  ZERO_ADVANCE_EPSILON: number;
}

export interface CanvasFontMetricsRequest {
  role: FontRoleName | string;
  fontSize: number;
  fontWeight?: number;
  italic?: boolean;
  locale?: string;
  fontKey?: string;
  fontFamilies?: string[];
  faceSelectionText?: string;
}

export interface CanvasFontMetricsResult {
  ascent: number;
  descent: number;
  leading: number;
  source: string;
  typoAscent: number | null;
  typoDescent: number | null;
}

export interface CanvasTextMetricsLike {
  width: number;
  actualBoundingBoxLeft: number;
  actualBoundingBoxAscent: number;
  actualBoundingBoxRight: number;
  actualBoundingBoxDescent: number;
  fontBoundingBoxAscent?: number | null;
  fontBoundingBoxDescent?: number | null;
  ideographicBaseline?: number | null;
}

export interface CanvasContextLike {
  font: string;
  canvas: MetricsCanvasSize;
  measureText: MetricsMeasureTextFn;
  setTransform: MetricsSetTransformFn;
  clearRect: MetricsClearRectFn;
  fillText: MetricsFillTextFn;
  getImageData: MetricsGetImageDataFn;
}

export interface CanvasMetricsResolverInstance {
  resolve: MetricsResolveFn;
}

declare global {
  var __TiqianCanvasMetrics: CanvasMetricsGlobal;
}

(function () {
  if (globalThis.__TiqianCanvasMetrics) return;

  var CJK_METRIC_PROBE_TEXT = "\u4e2d"; // "中"
  var LATIN_METRIC_PROBE_TEXT = "Hg";
  var ZERO_ADVANCE_EPSILON = 0.01;

  /**
   * Return the value as a number if finite and strictly positive, or null.
   * Mirrors Kotlin Double.toFloatOrNull() semantics.
   *
   * @param {number|undefined|null} value
   * @returns {number|null}
   */
  function positiveOrNull(value: number | undefined | null): number | null {
    return typeof value === "number" && Number.isFinite(value) && value > 0 ? value : null;
  }

  /**
   * Stub metrics for fallback when canvas measurement is unavailable or fails.
   *
   * @param {{ role: string, fontSize: number, fontWeight?: number, italic?: boolean, locale?: string, fontKey?: string, fontFamilies?: string[], faceSelectionText?: string }} request
   * @returns {{ ascent: number, descent: number, leading: number, source: string, typoAscent: number|null, typoDescent: number|null }}
   */
  function stubFontMetrics(request: CanvasFontMetricsRequest): CanvasFontMetricsResult {
    var fontSize = request.fontSize;
    var role = request.role;

    if (role === "CjkText" || role === "CjkPunctuation") {
      // hhea-style inflated box (kept for the no-OS/2 fallback path);
      // typo* is the font-declared ideographic em the layout uses.
      // Mirrors Source Han Sans CN (see FontProvidedMetricsProbe).
      return {
        ascent: fontSize * 1.16,
        descent: fontSize * 0.288,
        leading: 0,
        source: "RawTables",
        typoAscent: fontSize * 0.88,
        typoDescent: fontSize * 0.12,
      };
    }

    if (role === "LatinText") {
      return {
        ascent: fontSize * 0.8,
        descent: fontSize * 0.2,
        leading: 0,
        source: "RawTables",
        typoAscent: null,
        typoDescent: null,
      };
    }

    // FontRole.Symbol, FontRole.Emoji, FontRole.Unknown (and unclassified roles)
    return {
      ascent: fontSize * 0.9,
      descent: fontSize * 0.25,
      leading: 0,
      source: "RawTables",
      typoAscent: null,
      typoDescent: null,
    };
  }

  /**
   * Compute a cache key for a FontMetricsRequest, intentionally excluding
   * faceSelectionText so one cache entry serves all ideographs in the same
   * typography instance.
   *
   * @param {{ role: string, fontSize: number, fontWeight?: number, italic?: boolean, locale?: string, fontKey?: string, fontFamilies?: string[] }} request
   * @returns {string}
   */
  function fontMetricsCacheKey(request: CanvasFontMetricsRequest): string {
    var families = request.fontFamilies ? request.fontFamilies.join("\u001f") : "";
    var weight = request.fontWeight != null ? request.fontWeight : 400;
    var italic = request.italic ? "1" : "0";
    var fontKey = request.fontKey != null ? request.fontKey : "";
    var locale = request.locale != null ? request.locale : "";
    return fontKey + "\u001d" +
      request.fontSize + "\u001d" +
      request.role + "\u001d" +
      locale + "\u001d" +
      weight + "\u001d" +
      italic + "\u001d" +
      families;
  }

  /**
   * Create a canvas font metrics resolver instance.
   *
   * @param {WebFontFamiliesInstance} fonts
   * @param {() => CanvasContextLike} createCanvasContext
   * @returns {MetricsResolverInstance}
   */
  function createMetricsResolver(
    fonts: WebFontFamiliesInstance,
    createCanvasContext: MetricsCreateCanvasContextFn,
  ): CanvasMetricsResolverInstance {
    var ctx: CanvasContextLike | null = null;
    var currentCanvasFont: string | null = null;
    var cache: Record<string, CanvasFontMetricsResult> = {};

    function getContext(): CanvasContextLike {
      if (!ctx) {
        ctx = createCanvasContext();
      }
      return ctx;
    }

    function resolve(request: CanvasFontMetricsRequest): CanvasFontMetricsResult {
      // Canvas selects metrics from the role probe and CSS stack, not from
      // the source cluster. Excluding faceSelectionText keeps the cache at
      // one entry per actual typography instance instead of per ideograph.
      var cacheKey = fontMetricsCacheKey(request);
      if (Object.prototype.hasOwnProperty.call(cache, cacheKey)) {
        return cache[cacheKey];
      }

      var cjkBox = request.role === "CjkText" || request.role === "CjkPunctuation";
      var probe = cjkBox ? CJK_METRIC_PROBE_TEXT : LATIN_METRIC_PROBE_TEXT;
      var stacks = fonts.fallbackStacks(request.role, request.fontFamilies);
      var context = getContext();

      for (var i = 0; i < stacks.length; i += 1) {
        var family = stacks[i];
        var cssStyle = request.italic ? "italic" : "normal";
        var fontWeight = request.fontWeight != null ? request.fontWeight : 400;
        var fontSize = request.fontSize;
        var cssFont = cssStyle + " " + fontWeight + " " + fontSize + "px " + family;

        if (cssFont !== currentCanvasFont) {
          context.font = cssFont;
          currentCanvasFont = context.font;
        }

        var m = context.measureText(probe);
        if (!m || !Number.isFinite(m.width) || m.width <= ZERO_ADVANCE_EPSILON) {
          continue;
        }

        var ascent = positiveOrNull(m.fontBoundingBoxAscent);
        if (ascent == null) {
          ascent = positiveOrNull(m.actualBoundingBoxAscent);
        }
        if (ascent == null) {
          continue;
        }

        var descent = positiveOrNull(m.fontBoundingBoxDescent);
        if (descent == null) {
          descent = positiveOrNull(m.actualBoundingBoxDescent);
        }
        if (descent == null) {
          continue;
        }

        var ideographicDescent = (m.ideographicBaseline != null)
          ? positiveOrNull(-m.ideographicBaseline)
          : null;

        var typoAscent: number | null = null;
        var typoDescent: number | null = null;
        if (cjkBox && ideographicDescent != null) {
          typoAscent = Math.max(fontSize - ideographicDescent, 0);
          typoDescent = Math.max(ideographicDescent, 0);
        }

        var result: CanvasFontMetricsResult = {
          ascent: ascent,
          descent: descent,
          leading: 0,
          source: "GlyphSampling",
          typoAscent: typoAscent,
          typoDescent: typoDescent,
        };
        cache[cacheKey] = result;
        return result;
      }

      var fallbackResult = stubFontMetrics(request);
      cache[cacheKey] = fallbackResult;
      return fallbackResult;
    }

    return {
      resolve: resolve,
    };
  }

  globalThis.__TiqianCanvasMetrics = {
    stubFontMetrics: stubFontMetrics,
    createMetricsResolver: createMetricsResolver,
    ZERO_ADVANCE_EPSILON: ZERO_ADVANCE_EPSILON,
  };
})();

