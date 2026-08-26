// ElementContextsRegistry: page-level WeakMap that maps enhanced elements to
// their EnhancedElementContext instances. Must be page-level (one per document)
// because multiple module copies (client HMR, chunk duplication) can evaluate
// the enhance-context module independently, and each copy must share one
// registry to avoid creating duplicate contexts for the same element. Parameter
// passing cannot cover this because the registry is accessed from async
// callbacks (ResizeObserver, IntersectionObserver) that never see the assembly
// scope.
//
// The per-element context type and its create/get functions remain in
// engine/context/enhance-context.ts; only the WeakMap registry lives here.

import type { EnhancedElementContext } from "../engine/context/enhance-context.js";

export type ElementContextsMap = WeakMap<Element, EnhancedElementContext>;

const elementContexts: ElementContextsMap = new WeakMap();

/** Returns the page-level element-contexts registry. */
export function getElementContexts(): ElementContextsMap {
  return elementContexts;
}
