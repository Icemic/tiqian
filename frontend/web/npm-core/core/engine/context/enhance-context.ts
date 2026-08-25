// EnhancedElementContext — the per-root assembly home for layout lifecycle
// state. One context per enhanced element, constructed by
// createEnhanceContext(element) and held by the caller (the public api.ts
// entries today; the custom element joins in the S3-b wave).
//
// Why per-element: every field below is scoped to one enhanced root and dies
// with it. A module-level WeakMap per field would work mechanically, but a
// single context object makes the ownership explicit and gives later waves
// (paragraph table, prepared-style registries, element lifecycle forwarding)
// one place to land without new module globals.
//
// Scope of this wave (S3-a first half): the invalidation generation (one
// counting mechanism replacing api.ts's rootGenerations WeakMap) and the
// exact font session entry (replacing api.ts's rootFontSessions WeakMap).
// beginEnhanceCycle() supersedes in-flight work started under an earlier
// generation; readers compare context.generation against the value they
// captured before their first await.

import type { ExactFontSessionEntry } from "../exact-font.js";

export interface RawDomParagraphRecord {
  fragment: DocumentFragment | null;  // detached original children
  engineWriteDepth: number;           // host engine-write suspension counter
  forwarding: boolean;                // commit-forwarding installed flag
}

interface ExactFontSessionState {
  entry: ExactFontSessionEntry | null;
}

interface EnhancedElementContext {
  readonly element: Element;
  readonly generation: number;
  readonly exactFontSession: ExactFontSessionState;
  readonly rawDomParagraphs: Map<Element, RawDomParagraphRecord>;
  beginEnhanceCycle(): number;
}

const elementContexts = new WeakMap<Element, EnhancedElementContext>();

/** Returns the live context for the given element, or undefined. */
function getContextForElement(element: Element): EnhancedElementContext | undefined {
  return elementContexts.get(element);
}

function createEnhanceContext(element: Element): EnhancedElementContext {
  let generation = 0;
  const exactFontSession: ExactFontSessionState = { entry: null };
  const rawDomParagraphs = new Map<Element, RawDomParagraphRecord>();

  const context: EnhancedElementContext = {
    element,
    get generation() {
      return generation;
    },
    exactFontSession,
    rawDomParagraphs,
    beginEnhanceCycle() {
      generation += 1;
      return generation;
    },
  };

  elementContexts.set(element, context);
  return context;
}

function getOrCreateEnhanceContext(element: Element): EnhancedElementContext {
  let context = elementContexts.get(element);
  if (!context) {
    context = createEnhanceContext(element);
  }
  return context;
}

export { createEnhanceContext, getContextForElement, getOrCreateEnhanceContext };
export type { EnhancedElementContext, ExactFontSessionState };
