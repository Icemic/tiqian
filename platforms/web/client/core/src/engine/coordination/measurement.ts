// MeasurementCoordinationState: the page-level, once-per-document text
// measurement record. Per the service-directory rule (see
// core/services/global-services.ts), the shared bounded canvas measurement
// cache and its per-font probe verdicts are page-wide by definition: every
// shaper instance (across every attached root) must share one cache so
// cross-root resizes stay warm (ADR 0039), and one webfont-arrival
// invalidation must drop the cache and verdicts for the whole page.
//
// This cluster module holds NO state of its own: it exports the state record
// type plus factory-free helpers that operate on a passed-in record, and the
// CoordinationService holds the single instance. canvas-shaping.ts keeps its
// shaper logic and exported test helpers; they consult this service-owned
// record instead of module-level mutable state.
import type { MeasuredTextLike } from "../canvas-shaping.js";

export interface MeasurementCoordinationState {
  measurementCache: Map<string, MeasuredTextLike>;
  degenerateInkBoundsByFont: Record<string, boolean>;
  canvasAdvanceParityByFont: Record<string, boolean>;
  fontLoadInvalidationInstalled: boolean;
}

// Drop the shared measurement cache and both per-font probe verdict caches.
// The cache map is cleared in place (its LRU ordering and identity must
// survive) while the verdict records are replaced, matching the historic
// module-level behavior exactly.
export function clearMeasurementState(state: MeasurementCoordinationState): void {
  state.measurementCache.clear();
  state.degenerateInkBoundsByFont = {};
  state.canvasAdvanceParityByFont = {};
}

export function measurementCacheEntryCount(state: MeasurementCoordinationState): number {
  return state.measurementCache.size;
}
