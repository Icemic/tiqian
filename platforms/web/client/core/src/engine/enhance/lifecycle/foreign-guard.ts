// ForeignGuard — the ForeignEnhancedRootMountNoOp detection for one
// enhanced element (core-neutral parts ruling). A fresh context with no
// runtime state of its own mounts over a root whose paragraph candidates
// all sit in a terminal state (rendered or capability-marked) only when a
// foreign context already owns the root. Terminal candidates here count
// everything shouldTryParagraph accepts, so an unenhanced root never
// matches. Fake-DOM test worlds without the eligibility DOM surface fall
// through to the ordinary mount path.
//
// Registry dissolution: the baseline consulted the per-session root-state
// registry to prove the mounting session held no state of its own; the
// context's runtimeEstablished flag answers the same question (one context
// per element, so the element identity is the key).

import type { EnhancementStateMachine } from "../state-machine.js";
import type { ContextState } from "../context-state.js";

export interface ForeignGuard {
  rootIsForeignEnhanced(): boolean;
}

function createForeignGuard(
  root: HTMLElement,
  stateMachine: EnhancementStateMachine,
  contextState: ContextState,
): ForeignGuard {
  return {
    rootIsForeignEnhanced(): boolean {
      if (stateMachine.connected || stateMachine.runtimeActive) return false;
      if (stateMachine.snapshotAdopted) return false;
      if (contextState.runtimeEstablished) return false;
      try {
        const candidates = contextState.paragraphCandidates(root, "p, li");
        if (candidates.length === 0) return false;
        for (let i = 0; i < candidates.length; i += 1) {
          const candidate = candidates[i];
          if (candidate.getAttribute("data-tq-rendered") !== "true" &&
              !candidate.hasAttribute("data-tiqian-capability-issue")) {
            return false;
          }
        }
        return true;
      } catch {
        return false;
      }
    },
  };
}

export { createForeignGuard };
