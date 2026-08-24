// Single home of the engine-side ffi facade contract. Member types derive
// from the @tiqian/ffi declarations, so the engine view cannot drift from
// the package the runtime consumes. root-state, worker-request,
// process-paragraph, prepare-paragraph-layout, and commit-prepared-paragraph
// import this type instead of keeping local copies.

import type * as TiqianFfi from "@tiqian/ffi";

// The layout lanes pass the browser-metrics argument list as an array, so
// the facade keeps the Function.prototype.apply contract next to the plain
// call signature.
type EngineFfiApplyFn = (thisArg: null, args: unknown[]) => string;

export type EngineFfiBrowserMetricsFn = typeof TiqianFfi.precomputeParagraphWithBrowserMetrics & {
  apply: EngineFfiApplyFn;
};

// The ffi facade bound once at startup (bindFfi) and passed opaquely
// through engineState, processParagraphArgument, and sessionArgument to the
// orchestrator modules. Member set mirrors the Kotlin tsFfiFacade consumed
// by the layout lanes.
export type EngineFfiFacade = {
  classifyFontRole: typeof TiqianFfi.classifyFontRole;
  unsupportedInlineShapingProperties: typeof TiqianFfi.unsupportedInlineShapingProperties;
  firstDivergentInlineShapingProperty: typeof TiqianFfi.firstDivergentInlineShapingProperty;
  precomputeParagraphWithDiagnostics: typeof TiqianFfi.precomputeParagraphWithDiagnostics;
  precomputeParagraphWithBrowserMetrics: EngineFfiBrowserMetricsFn;
};
