// Canvas runtime seam for the canvas cluster (G2 module boundary). root-state
// reads the font families and browser metrics bridge factories through this
// module. Tests substitute fakes here instead of installing globals; null
// restores the production factories. Same seam shape as setEngineOverride in
// runtime-loader.js.

import { createFontFamilies } from "./canvas-fonts.js";
import { createBrowserMetricsBridge } from "./browser-metrics-bridge.js";

export type CanvasRuntime = {
  createFontFamilies: typeof createFontFamilies;
  createBrowserMetricsBridge: typeof createBrowserMetricsBridge;
};

const defaultCanvasRuntime: CanvasRuntime = {
  createFontFamilies: createFontFamilies,
  createBrowserMetricsBridge: createBrowserMetricsBridge,
};

export let canvasRuntime: CanvasRuntime = defaultCanvasRuntime;

export function setCanvasRuntimeForTest(partial: Partial<CanvasRuntime> | null): void {
  canvasRuntime = partial != null
    ? { ...defaultCanvasRuntime, ...partial }
    : defaultCanvasRuntime;
}
