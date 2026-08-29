// Browser metrics bridge adapter (TsHost runtime port, Slice 4a part 4).
// Adapts the canvas font metrics resolver and canvas text shaper to the
// typed BrowserMetricsCallbacks DTO expected by precomputeParagraphWithBrowserMetrics.
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

/**
 * Typed callback DTO matching the Kotlin BrowserMetricsCallbacks interface.
 * Carries shapeJson and metricsJson as direct function properties, replacing
 * the previous individual parameter approach and the adapter classes.
 *
 * Corrective wave 5 (#106): the JSON stringify/parse round-trip at this seam
 * is deleted — callbacks are constructed directly as typed properties.
 */
export interface BrowserMetricsCallbacks {
  shapeJson: BridgeJsonWireFn;
  metricsJson: BridgeJsonWireFn;
}

export interface BrowserMetricsBridgeInstance extends BrowserMetricsCallbacks {}

/**
 * Create a browser metrics bridge instance that implements the
 * BrowserMetricsCallbacks DTO.
 *
 * @param {{ fonts: Object, cjkDashCapability: Object|null, env: { createCanvasContext: Function, createProbeElement: Function, attachProbe: Function } }} options
 * @returns BrowserMetricsCallbacks
 */
export function createBrowserMetricsBridge(options: BrowserMetricsBridgeOptions): BrowserMetricsCallbacks {
  const fonts = options.fonts;
  const cjkDashCapability = options.cjkDashCapability != null ? options.cjkDashCapability : null;
  const env = options.env;

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
