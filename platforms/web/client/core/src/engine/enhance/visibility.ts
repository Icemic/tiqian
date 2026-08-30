// VisibilityManager — the visibility signal for one enhanced element
// (core-neutral parts ruling). Owns the root intersection observation
// (inViewport facet written through the StateMachine) and the paragraph
// tier observation that feeds the layout job pool's per-paragraph
// priority tiers. Cross-part state travels through the StateMachine per
// the communication ruling; scheduling travels through the held
// SchedulerRegistration.

import { globalServices } from "../../services/global-services.js";
import type { LayoutJobPool } from "../layout-job-pool.js";
import type { RootVisibilityObservationSource } from "../../sampler/observers.js";
import { createRootVisibilityObservation } from "../../sampler/observers.js";
import { InvalidationReason } from "./state.js";
import type { EnhancementStateMachine } from "./state-machine.js";
import type { SchedulerRegistration } from "./scheduler-registration.js";

interface ParagraphTierInfo {
  index: number;
  tier: number;
}

export interface VisibilityHooks {
  scheduleResponsiveGeometryCommit(): void;
}

export interface VisibilityManager {
  observeIntersection(): void;
  stopIntersectionObservation(): void;
  observeParagraphTiers(pool: LayoutJobPool): void;
  stopParagraphTierObservation(): void;
}

// ParagraphTierGating: the observer band spans one full viewport in each
// direction via rootMargin 100%. A paragraph crossing the visible viewport
// is tier 1; inside the band but off-screen is tier 2; beyond the band is
// tier 3.
function paragraphTierFromEntry(entry: IntersectionObserverEntry): number {
  if (!entry.isIntersecting) return 3;
  const rect = entry.boundingClientRect;
  if (!rect) return 2;
  const viewportHeight = globalThis.innerHeight || 0;
  return rect.bottom >= 0 && rect.top <= viewportHeight ? 1 : 2;
}

function createVisibilityManager(
  root: HTMLElement,
  stateMachine: EnhancementStateMachine,
  scheduler: SchedulerRegistration,
  hooks: VisibilityHooks,
): VisibilityManager {
  let visibilityObservation: RootVisibilityObservationSource | null = null;
  let paragraphObserver: IntersectionObserver | null = null;
  const paragraphTierIndex = new Map<Element, ParagraphTierInfo>();

  function observeIntersection(): void {
    if (visibilityObservation || typeof IntersectionObserver === "undefined") return;
    visibilityObservation = createRootVisibilityObservation(root, {
      onRootEntry: (fact) => {
        const wasInViewport = stateMachine.inViewport;
        stateMachine.inViewport = fact.isIntersecting;
        scheduler.update({
          inViewport: stateMachine.inViewport,
          intersectionRatio: fact.intersectionRatio,
          visibleArea: fact.visibleArea,
          inlineSize: fact.inlineSize,
          area: fact.area,
        });
        if (wasInViewport && !stateMachine.inViewport) {
          // OffscreenWorkerDebounce: an off-screen root stops receiving
          // grants immediately; its pending layout work waits out the same
          // trailing window as off-screen frame tasks and replays once the
          // drag settles or the root returns. Already committed paragraphs
          // stay committed.
          scheduler.refreshWorkerDeferred();
        }
        if (!wasInViewport && stateMachine.inViewport) {
          scheduler.clearWorkerDeferred();
          if (
            stateMachine.isInvalidated(InvalidationReason.ResponsiveCommit) ||
            stateMachine.isInvalidated(InvalidationReason.ResponsiveRelayout)
          ) {
            hooks.scheduleResponsiveGeometryCommit();
          }
        }
      },
    });
    visibilityObservation.start();
  }

  function stopIntersectionObservation(): void {
    visibilityObservation?.stop();
    visibilityObservation = null;
  }

  function observeParagraphTiers(pool: LayoutJobPool): void {
    const count = pool.paragraphCount(root);
    if (count === 0) {
      stopParagraphTierObservation();
      return;
    }
    if (!paragraphObserver && typeof IntersectionObserver === "undefined") return;
    paragraphObserver ??= new IntersectionObserver((entries) => {
      // The runtime graph can be rebuilt between dispatch and intersection;
      // read the pool live so tier flips always reach the current job.
      const live = globalServices().coordination.layoutJobPool;
      for (let i = 0; i < entries.length; i++) {
        const entry = entries[i];
        const info = paragraphTierIndex.get(entry.target);
        if (!info) continue;
        const tier = paragraphTierFromEntry(entry);
        if (tier === info.tier) continue;
        info.tier = tier;
        // Tier flips go straight to the running job's pending counters, so
        // the next polled frame reorders the queue without rescanning.
        if (live && live.hasJob(root)) {
          live.setParagraphTier(root, info.index, tier);
        }
      }
    }, { rootMargin: "100% 0px" });
    // Paragraph hosts survive relayout; atomic swaps replace only their
    // children. The diff converges: a stable article adds and drops nothing
    // and the observer set stops churning.
    const live = new Set<Element>();
    for (let index = 0; index < count; index++) {
      const paragraph = pool.paragraphAt(root, index);
      if (!paragraph) continue;
      live.add(paragraph);
      const info = paragraphTierIndex.get(paragraph);
      if (!info) {
        paragraphTierIndex.set(paragraph, { index, tier: 1 });
        paragraphObserver.observe(paragraph);
      } else {
        info.index = index;
      }
    }
    for (const paragraph of paragraphTierIndex.keys()) {
      if (live.has(paragraph)) continue;
      paragraphObserver.unobserve(paragraph);
      paragraphTierIndex.delete(paragraph);
    }
  }

  function stopParagraphTierObservation(): void {
    paragraphObserver?.disconnect();
    paragraphObserver = null;
    paragraphTierIndex.clear();
  }

  return {
    observeIntersection,
    stopIntersectionObservation,
    observeParagraphTiers,
    stopParagraphTierObservation,
  };
}

export { createVisibilityManager };
