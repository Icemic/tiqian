// Pure TS installer for the Tiqian engine (Slice 7 Lane A).
// This module replaces the bundle init block. It is the concrete composition
// root: it builds the four stateful collaborators and hands the assembled
// engine entry to the runtime loader. After bundle retirement this is the
// sole installation entry point; calling engineEntry() again builds a fresh
// graph with its own rawDom, job pool and root-state.

// Engine scripts - order matches build.gradle.kts bridge list for review.
// The modules below no longer self-install on globalThis; the composition
// root wires the products into the engine entry instead.
import { deriveRawDom } from "../raw-dom.js";
import { getOrCreateEnhanceContext } from "../context/enhance-context.js";
import { createCopyInstaller } from "../../utils/copy.js";
import type { CopyInstaller } from "../../utils/copy.js";
import { createLayoutJobPool } from "../layout-job-pool.js";
import { createRootState } from "../root-state.js";
import {
  createEngineEntry,
} from "../engine-entry.js";
import type { EngineEntryHandle } from "../engine-entry.js";
import { createFontFamilies } from "../canvas-fonts.js";
import { createBrowserMetricsBridge } from "../browser-metrics-bridge.js";

import { preparedDomRendererModule } from "./runtime-loader.js";

// EngineEntryOptions: the concrete composition root accepts an optional
// externally-owned copy installer so the page-level copy handler is shared
// with the element layer's own module-scope install.
export interface EngineEntryOptions {
  copyInstaller?: CopyInstaller;
}

// Construct the whole engine object graph: rawDom, copy installer,
// root-state, layout-job-pool, and the engine+worker facade. Every product is
// built here; the engine entry only wires what it receives.
export function engineEntry(options?: EngineEntryOptions): EngineEntryHandle {
  const rawDom = deriveRawDom({ 
    getEnhanceContext: getOrCreateEnhanceContext,
    getPreparedDomRendererModule: preparedDomRendererModule,
  });
  const copyInstaller = options && options.copyInstaller
    ? options.copyInstaller
    : createCopyInstaller();
  const rootState = createRootState({
    createFontFamilies: createFontFamilies,
    createBrowserMetricsBridge: createBrowserMetricsBridge,
  });
  const layoutJobPool = createLayoutJobPool();
  return createEngineEntry(rawDom, copyInstaller, rootState, layoutJobPool);
}