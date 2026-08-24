// Pure TS installer for the Tiqian engine (Slice 7 Lane A).
// This module replaces the bundle init block: it imports all 21 engine scripts
// for side effect and wires the ffi facade into the root state. After bundle
// retirement this is the sole installation entry point.

// Engine scripts — order matches build.gradle.kts bridge list for review.
import "../custody.js";
import "../eligibility.js";
import "../progressive-job.js";
import "../../utils/copy.js";
import "../content-reconcile.js";
import "../responsive-measure.js";
import "../markdown-lowering.js";
import "../lifecycle.js";
import "../worker-request.js";
import "../prepare-paragraph-layout.js";
import "../commit-prepared-paragraph.js";
import "../process-paragraph.js";
import "../canvas-fonts.js";
import "../canvas-metrics.js";
import "../canvas-shaping.js";
import "../browser-metrics-bridge.js";
import "../prepared-metadata.js";
import "../progressive-relayout-session.js";
import "../root-state.js";
import "../progressive-drivers.js";
import "../engine-entry.js";

// Ffi facade — direct module references from @tiqian/ffi (no Kotlin wrapper).
import {
  classifyFontRole,
  unsupportedInlineShapingProperties,
  firstDivergentInlineShapingProperty,
  precomputeParagraphWithDiagnostics,
  precomputeParagraphWithBrowserMetrics,
} from "@tiqian/ffi";

const tsFfiFacade = {
  classifyFontRole,
  unsupportedInlineShapingProperties,
  firstDivergentInlineShapingProperty,
  precomputeParagraphWithDiagnostics,
  precomputeParagraphWithBrowserMetrics,
};

globalThis.__TiqianRootState.bindFfi(tsFfiFacade);

export { tsFfiFacade };
