// GlobalServices: the page-wide, once-per-document container for services
// that page-level policy owns (user ruling 2026-08-25). The coordination
// service holds the page frame clock, admission ordering and worker grants,
// all per-page by definition: one document gets one timeline and one grant
// ledger no matter how many roots attach.
//
// The container must be one per document rather than one per module copy
// because client routers, dev HMR and duplicated package chunks can evaluate
// this module more than once in the same document. The Symbol.for key shares
// one container across those copies; the former worker-channel coordinator
// precedent (S5-bc: consolidated into coordination.channel) established
// this pattern. Members are resolved through the globalServices() accessor
// rather than passed as parameters because hosts reach the services from
// async callbacks that never see an assembly scope: ResizeObserver,
// IntersectionObserver and requestAnimationFrame entry points all invoke
// the library without a construction context.
//
// Library-internal, test-only injection: installGlobalServicesForTesting
// swaps the container for an explicit replacement and returns a restore
// function. Hosts must not touch it.
import { CoordinationService } from "../engine/coordination/coordination-service.js";
import type { FontCoordinationState } from "../engine/coordination/fonts.js";
import type { MeasurementCoordinationState } from "../engine/coordination/measurement.js";
import type { PreparedDomState } from "../engine/loaders/runtime-loader.js";

/** Per-document scope counter for prepared-style CSS scoping. */
export interface PreparedScopeCounter {
  next: number;
}

/** Per-document prepared-style lookups (S3-a Part 2). */
export interface PreparedStylesState {
  rootsByHost: WeakMap<Element, Element>;
  scopeCounters: WeakMap<Document, PreparedScopeCounter>;
}

/** Snapshot-table deduplication caches (S5-tail). One global map per page
 * deduplicates loads per URL reference so every root shares one table. */
export interface SnapshotTablesState {
  loadedTables: Map<string, Promise<unknown>>;
  resolvedTables: Map<string, unknown>;
}

/** Snapshot adoption per-element caches (S5-tail). WeakMaps keyed by
 * paragraph elements; no lifecycle, pure page-wide caches. */
export interface SnapshotAdoptionState {
  snapshotFontReplayProofs: WeakMap<HTMLElement, unknown>;
  states: WeakMap<HTMLElement, unknown>;
  directServerArtifacts: WeakMap<HTMLElement, unknown>;
}

/** Viewport gesture and scroll-anchoring state (S5-tail). Document-wide
 * singletons shared across all roots. */
export interface ViewportAnchorState {
  gestureTrackerInstalled: boolean;
  lastGestureAt: number;
  heldOwnerByRoot: WeakMap<HTMLElement, HTMLElement>;
  ownerHolds: WeakMap<HTMLElement, unknown>;
}

/** Stylesheet loader handles (S5-tail). One stylesheet per document, keyed
 * by document so multi-document hosts never share a link or load promise. */
export interface StylesheetLoaderState {
  stylesheetPromises: WeakMap<Document, Promise<unknown>>;
  stylesheetElements: WeakMap<Document, unknown>;
}

export interface GlobalServices {
  coordination: CoordinationService;
  // Font/measurement coordination state: page-wide singletons owned by the
  // coordination service (see the cluster modules for why each is
  // page-level single).
  fonts: FontCoordinationState;
  measurement: MeasurementCoordinationState;
  // The rootsByHost map tracks which root owns a given host paragraph
  // element; the scopeCounters map assigns monotonically increasing scope
  // IDs per document. Both are document-scoped and must survive across module
  // copies, hence they live in the page-global container rather than in
  // prepared-dom.ts module state.
  preparedStyles: PreparedStylesState;
  // Prepared pipeline state: the renderer module reference with its test
  // override and the commit validator oracle slot. Registered by
  // runtime-loader.ts at import time; the record stays in this container
  // because prepared-pipeline consumers resolve the renderer without
  // loading the runtime.
  preparedDom?: PreparedDomState;
  // Snapshot-table deduplication caches (S5-tail): page-wide maps that
  // cache loaded and resolved binary tables by URL reference.
  snapshotTables?: SnapshotTablesState;
  // Snapshot adoption per-element caches (S5-tail): WeakMaps keyed by
  // paragraph elements for font-replay proofs, adoption state, and
  // direct server artifacts.
  snapshotAdoption?: SnapshotAdoptionState;
  // Viewport gesture and scroll-anchoring state (S5-tail): document-wide
  // singletons for gesture tracking and native scroll anchoring holds.
  viewportAnchor?: ViewportAnchorState;
  // Stylesheet loader handles (S5-tail): one stylesheet link element and load
  // promise per document.
  stylesheetLoader?: StylesheetLoaderState;
}

type GlobalServicesRegistry = Record<symbol, GlobalServices | undefined>;

export type GlobalServicesRestoreFn = () => void;

const GLOBAL_SERVICES_KEY: unique symbol = Symbol.for("@tiqian/prose.global-services.v1");

function createGlobalServices(): GlobalServices {
  const coordination = new CoordinationService();
  return {
    coordination,
    fonts: coordination.fonts,
    measurement: coordination.measurement,
    preparedStyles: {
      rootsByHost: new WeakMap<Element, Element>(),
      scopeCounters: new WeakMap<Document, PreparedScopeCounter>(),
    },
    preparedDom: undefined,
    snapshotTables: {
      loadedTables: new Map(),
      resolvedTables: new Map(),
    },
    snapshotAdoption: {
      snapshotFontReplayProofs: new WeakMap(),
      states: new WeakMap(),
      directServerArtifacts: new WeakMap(),
    },
    viewportAnchor: {
      gestureTrackerInstalled: false,
      lastGestureAt: Number.NEGATIVE_INFINITY,
      heldOwnerByRoot: new WeakMap(),
      ownerHolds: new WeakMap(),
    },
    stylesheetLoader: {
      stylesheetPromises: new WeakMap(),
      stylesheetElements: new WeakMap(),
    },
  };
}

export function globalServices(): GlobalServices {
  return (globalThis as GlobalServicesRegistry)[GLOBAL_SERVICES_KEY] ??= createGlobalServices();
}

export function installGlobalServicesForTesting(container: GlobalServices): GlobalServicesRestoreFn {
  const registry = globalThis as GlobalServicesRegistry;
  const previous = registry[GLOBAL_SERVICES_KEY];
  registry[GLOBAL_SERVICES_KEY] = container;
  return () => {
    if (previous === undefined) delete registry[GLOBAL_SERVICES_KEY];
    else registry[GLOBAL_SERVICES_KEY] = previous;
  };
}