// Pure TS installer for the Tiqian engine (Slice 7 Lane A).
// This module replaces the bundle init block. It is the concrete composition
// root: it builds the ffi facade and the mid-module product graph and hands
// the assembled engine entry to the runtime loader. After bundle retirement
// this is the sole installation entry point; calling engineEntry() again
// builds a fresh graph with its own custody, job pool and root-state.

// Engine scripts - order matches build.gradle.kts bridge list for review.
// The modules below no longer self-install on globalThis; the composition
// root wires the products into the engine entry instead.
import { createCustody } from "../custody.js";
import { createCopyInstaller } from "../../utils/copy.js";
import type { CopyInstaller } from "../../utils/copy.js";
import { createProgressiveJob } from "../progressive-job.js";
import type { ContentReconcileDeps } from "../content-reconcile.js";
import {
  workerLayoutRequestForRoot,
} from "../worker-request.js";
import {
  commitPreparedParagraph,
  commitWorkerPreparedParagraph,
} from "../commit-prepared-paragraph.js";
import type {
  CommitPreparedParagraphBundle,
  CommitPreparedParagraphDeps,
} from "../commit-prepared-paragraph.js";
import { processParagraph } from "../process-paragraph.js";
import type { ProcessParagraphDeps } from "../process-paragraph.js";
import { openProgressiveRelayoutSession } from "../progressive-relayout-session.js";
import type { ProgressiveRelayoutSessionDeps } from "../progressive-relayout-session.js";
import { createRootState } from "../root-state.js";
import type { ProgressiveDriversDeps } from "../progressive-drivers.js";
import {
  createEngineEntry,
} from "../engine-entry.js";
import type { EngineEntryHandle } from "../engine-entry.js";
import { createFontFamilies } from "../canvas-fonts.js";
import { createBrowserMetricsBridge } from "../browser-metrics-bridge.js";

// Ffi facade - direct module references from @tiqian/ffi (no Kotlin wrapper).
import {
  classifyFontRole,
  unsupportedInlineShapingProperties,
  firstDivergentInlineShapingProperty,
  precomputeParagraphWithDiagnostics,
  precomputeParagraphWithBrowserMetrics,
} from "@tiqian/ffi";
import type { EngineFfiFacade } from "../ffi-face.js";

// EngineEntryOptions: the concrete composition root accepts an optional
// externally-owned copy installer so the page-level copy handler is shared
// with the element layer's own module-scope install.
export interface EngineEntryOptions {
  copyInstaller?: CopyInstaller;
}

// Build the ffi facade the layout lanes consume. Member set mirrors the Kotlin
// tsFfiFacade; built fresh per engineEntry() call.
export function buildFfiFacade(): EngineFfiFacade {
  return {
    classifyFontRole: classifyFontRole,
    unsupportedInlineShapingProperties: unsupportedInlineShapingProperties,
    firstDivergentInlineShapingProperty: firstDivergentInlineShapingProperty,
    precomputeParagraphWithDiagnostics: precomputeParagraphWithDiagnostics,
    precomputeParagraphWithBrowserMetrics: precomputeParagraphWithBrowserMetrics,
  };
}

// Construct the whole engine object graph: custody, copy installer,
// root-state, the progressive job/session/driver cluster, process-paragraph,
// content-reconcile, and the engine+worker facade. Every product is built
// here; the engine entry only wires what it receives.
export function engineEntry(options?: EngineEntryOptions): EngineEntryHandle {
  const custody = createCustody();
  const copyInstaller = options && options.copyInstaller
    ? options.copyInstaller
    : createCopyInstaller();
  const rootState = createRootState({
    createFontFamilies: createFontFamilies,
    createBrowserMetricsBridge: createBrowserMetricsBridge,
  });
  // The root-state ffi slot is bound once at startup (root-state owns the
  // bindFfi seam; the facade must be the same instance the engine entry holds).
  const ffiFacade = buildFfiFacade();
  rootState.bindFfi(ffiFacade);
  const progressiveJob = createProgressiveJob();
  const commitPreparedParagraphDeps: CommitPreparedParagraphDeps = { custody: custody };
  const commitPreparedParagraphBundle: CommitPreparedParagraphBundle = {
    commitWorkerPreparedParagraph: commitWorkerPreparedParagraph,
    commitPreparedParagraph: commitPreparedParagraph,
  };
  const processParagraphDeps: ProcessParagraphDeps = {
    custody: custody,
    commitPreparedParagraph: commitPreparedParagraphBundle,
  };
  const progressiveRelayoutSessionDeps: ProgressiveRelayoutSessionDeps = {
    custody: custody,
    commitPreparedParagraph: commitPreparedParagraphBundle,
  };
  const reconcileDeps: ContentReconcileDeps = { custody: custody };

  // DriverDepsEngineBackfill: progressive-drivers reads deps.engine at call
  // time inside enhanceProgressively, so the drivers deps are built with a
  // null engine slot and the engine instance is back-filled once the entry
  // exists. This breaks the drivers <-> engine construction cycle at the root.
  const driversDeps: ProgressiveDriversDeps = {
    rootState: rootState,
    engine: null,
    copyInstaller: copyInstaller,
    progressiveJob: progressiveJob,
    progressiveRelayoutSession: progressiveRelayoutSessionDeps,
    processParagraph: processParagraphDeps,
  };

  const entry = createEngineEntry({
    ffi: ffiFacade,
    custody: custody,
    copyInstaller: copyInstaller,
    rootState: rootState,
    progressiveJob: progressiveJob,
    progressiveDriversDeps: driversDeps,
    processParagraphDeps: processParagraphDeps,
    reconcileDeps: reconcileDeps,
    workerLayoutRequestForRoot: workerLayoutRequestForRoot,
  });
  driversDeps.engine = entry.engine;
  return entry;
}