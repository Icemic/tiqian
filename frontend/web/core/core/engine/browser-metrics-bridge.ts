// Browser metrics bridge adapter (TsHost runtime port, Slice 4a part 4).
// Adapts the canvas font metrics resolver and canvas text shaper to the
// JSON-string ABI expected by precomputeParagraphWithBrowserMetrics.
//
// ES module: exports createBrowserMetricsBridge as a named binding. The
// shaper and resolver factories come from the named exports of
// canvas-shaping.js and canvas-metrics.js.
//
// Embedding constraint: the generator wraps this file in a Kotlin raw string,
// so the source must contain no dollar sign and no triple double-quote

import type { WebFontFamiliesInstance } from "./canvas-fonts.js";
import type { CanvasFontMetricsRequest } from "./canvas-metrics.js";
import type { CanvasShapingEnv, CjkDashCapability } from "./canvas-shaping.js";
import { createMetricsResolver } from "./canvas-metrics.js";
import { createTextShaper } from "./canvas-shaping.js";

interface BridgeTextRange {
  start: number;
  end: number;
}

interface BridgeWireStyle {
  fontSize: number;
  fontWeight: number;
  italic: boolean;
  fontFamilies: string[];
  locale?: string;
}

interface BridgeWireFontDecision {
  role: string;
  candidateKey: string;
}

type BridgeJsonWireFn = (requestJson: string) => string;

export interface BridgeShapeWireRequest {
  text: string;
  range: BridgeTextRange;
  style: BridgeWireStyle;
  fontDecision?: BridgeWireFontDecision | null;
  displayText?: string;
  openTypeFeatures?: string[];
}

export interface BridgeMetricsWireRequest extends CanvasFontMetricsRequest {}

export interface BrowserMetricsBridgeOptions {
  fonts: WebFontFamiliesInstance;
  cjkDashCapability?: CjkDashCapability | null;
  env: CanvasShapingEnv;
}

export interface BrowserMetricsBridgeInstance {
  shapeJson: BridgeJsonWireFn;
  metricsJson: BridgeJsonWireFn;
}

/**
 * Create a browser metrics bridge instance.
 *
 * @param {{ fonts: Object, cjkDashCapability: Object|null, env: { createCanvasContext: Function, createProbeElement: Function, attachProbe: Function } }} options
 * @returns {{ shapeJson: (requestJson: string) => string, metricsJson: (requestJson: string) => string }}
 */
export function createBrowserMetricsBridge(options?: BrowserMetricsBridgeOptions): BrowserMetricsBridgeInstance {
  const opts = options || {} as Partial<BrowserMetricsBridgeOptions>;
  const fonts = opts.fonts!;
  const cjkDashCapability = opts.cjkDashCapability != null ? opts.cjkDashCapability : null;
  const env = opts.env || {} as CanvasShapingEnv;

  const shaper = createTextShaper(fonts, cjkDashCapability, env);
  const resolver = createMetricsResolver(fonts, env.createCanvasContext);

  function shapeJson(requestJson: string): string {
    const wireInput = JSON.parse(requestJson) as BridgeShapeWireRequest;
    const fontDecision = wireInput.fontDecision;
    const shaperInput = {
      text: wireInput.text,
      range: wireInput.range,
      style: wireInput.style,
      fontDecision: {
        role: fontDecision ? fontDecision.role : "",
        candidate: {
          key: fontDecision ? fontDecision.candidateKey : "",
        },
      },
      displayText: wireInput.displayText,
    };
    const result = shaper.shape(shaperInput);
    return JSON.stringify(result);
  }

  function metricsJson(requestJson: string): string {
    const request = JSON.parse(requestJson) as BridgeMetricsWireRequest;
    const result = resolver.resolve(request);
    return JSON.stringify(result);
  }

  return {
    shapeJson: shapeJson,
    metricsJson: metricsJson,
  };
}
