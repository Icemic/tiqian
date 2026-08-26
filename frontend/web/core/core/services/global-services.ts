// GlobalServices: the page-wide, once-per-document container for services
// that page-level policy owns (user ruling 2026-08-25). The coordination
// service holds the page frame clock, admission ordering and worker grants,
// all per-page by definition: one document gets one timeline and one grant
// ledger no matter how many roots attach.
//
// The container must be one per document rather than one per module copy
// because client routers, dev HMR and duplicated package chunks can evaluate
// this module more than once in the same document. The Symbol.for key shares
// one container across those copies, mirroring the worker-channel coordinator
// precedent (core/engine/web-worker/worker-channel.ts). Members are resolved
// through the globalServices() accessor rather than passed as parameters
// because hosts reach the services from async callbacks that never see an
// assembly scope: ResizeObserver, IntersectionObserver and requestAnimationFrame
// entry points all invoke the library without a construction context.
//
// Library-internal, test-only injection: installGlobalServicesForTesting
// swaps the container for an explicit replacement and returns a restore
// function. Hosts must not touch it.
import { CoordinationService } from "../engine/coordination/coordination-service.js";
import type { FontCoordinationState } from "../engine/coordination/fonts.js";
import type { MeasurementCoordinationState } from "../engine/coordination/measurement.js";

/** Per-document scope counter for prepared-style CSS scoping. */
export interface PreparedScopeCounter {
  next: number;
}

/** Per-document prepared-style lookups (S3-a Part 2). */
export interface PreparedStylesState {
  rootsByHost: WeakMap<Element, Element>;
  scopeCounters: WeakMap<Document, PreparedScopeCounter>;
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