// EnhancedElementContext — the per-root general typography context. One
// context per enhanced element; every member is general layout lifecycle
// state that dies with the root. Web-specific observation state
// (ResizeObserver/IntersectionObserver instances, frame handles, viewport
// flags) lives on the web host element, not here.
//
// Construction surface (ADR 0053 batch R3, plan A): the context is a plain
// value object. constructEnhanceContext(element) is the open construction
// entry — hosts and tests build a context explicitly and inject it into the
// engine paths. createEnhanceContext(element) remains the compatibility
// factory: it constructs through the same surface and additionally registers
// the context in the per-document element registry consumed by
// getOrCreateEnhanceContext / getContextForElement (both lookup signatures
// stay unchanged).
//
// Lifecycle verbs follow the 2026-08-25 naming ruling: update() is the single
// refresh verb (it bumps the generation to supersede in-flight work started
// under an earlier generation; readers compare context.generation against the
// value they captured before their first await) and destroy() clears the
// paragraph records, releases the prepared-style state, and drops the context
// from the registry.

import type { SnapshotFontSessionEntry } from "../snapshot-font.js";
import type { PreparedStyleState } from "../../sampler/snapshot/prepared-dom.js";
import { releasePreparedStyleState } from "../../sampler/snapshot/prepared-dom.js";
import { getElementContexts } from "../../services/element-contexts.js";
import { createDiagnosisManager } from "./diagnosis-manager.js";
import type { DiagnosisDatasetRecord, DiagnosisManager } from "./diagnosis-manager.js";

export interface RawDomParagraphRecord {
  fragment: DocumentFragment | null;  // detached original children
  engineWriteDepth: number;           // host engine-write suspension counter
  forwarding: boolean;                // commit-forwarding installed flag
  originalContent: DocumentFragment | null;
  renderedNodes: Node[];
  rawDomNodes: Node[];
  originalRenderedAttribute: string | null;
  originalPreparedFlowAttribute: string | null;
  originalCanonicalSourceAttribute: string | null;
  originalSnapshotPreparedDomAttribute: string | null;
  originalLangAttribute: string | null;
  originalStyleAttribute: string | null;
  originalPosition: string;
  originalPositionPriority: string;
  originalInlineSize: string;
  originalInlineSizePriority: string;
  originalFontSize: string;
  originalFontSizePriority: string;
  originalHostInlineSizeAttribute: string | null;
  containingBlockApplied: boolean;
  hostInlineSizeApplied: string | null;
  hostFontSizeApplied: string | null;
}

interface SnapshotFontSessionState {
  entry: SnapshotFontSessionEntry | null;
}

interface EnhancedElementContext {
  readonly element: Element;
  readonly generation: number;
  readonly snapshotFontSession: SnapshotFontSessionState;
  readonly rawDomParagraphs: Map<Element, RawDomParagraphRecord>;
  readonly diagnosis: DiagnosisManager;
  preparedStyle: PreparedStyleState | null;
  update(): number;
  destroy(): void;
}

/** Returns the live context for the given element, or undefined. */
function getContextForElement(element: Element): EnhancedElementContext | undefined {
  return getElementContexts().get(element);
}

// Only HTMLElement hosts carry a dataset surface; the context types its
// element as Element, so the diagnosis host resolves it live and cast-free.
function isDatasetRecord(value: unknown): value is DiagnosisDatasetRecord {
  return typeof value === "object" && value !== null;
}

// Open construction surface (plan A): builds the plain context value without
// touching the element registry, so hosts and tests can construct a context
// explicitly before injecting it into the engine paths.
function constructEnhanceContext(element: Element): EnhancedElementContext {
  let generation = 0;
  const snapshotFontSession: SnapshotFontSessionState = { entry: null };
  const rawDomParagraphs = new Map<Element, RawDomParagraphRecord>();
  let preparedStyle: PreparedStyleState | null = null;
  const diagnosis = createDiagnosisManager({
    get dataset() {
      const candidate = Reflect.get(element, "dataset");
      return isDatasetRecord(candidate) ? candidate : undefined;
    },
  });

  return {
    element,
    get generation() {
      return generation;
    },
    snapshotFontSession,
    rawDomParagraphs,
    diagnosis,
    get preparedStyle() {
      return preparedStyle;
    },
    set preparedStyle(value: PreparedStyleState | null) {
      preparedStyle = value;
    },
    update() {
      generation += 1;
      return generation;
    },
    destroy() {
      rawDomParagraphs.clear();
      if (preparedStyle) {
        releasePreparedStyleState(preparedStyle);
        preparedStyle = null;
      }
      diagnosis.dispose();
      getElementContexts().delete(element);
    },
  };
}

// Compatibility factory: constructs through the open surface and registers
// the result in the per-document element registry.
function createEnhanceContext(element: Element): EnhancedElementContext {
  const context = constructEnhanceContext(element);
  getElementContexts().set(element, context);
  return context;
}

function getOrCreateEnhanceContext(element: Element): EnhancedElementContext {
  let context = getElementContexts().get(element);
  if (!context) {
    context = createEnhanceContext(element);
  }
  return context;
}

export { constructEnhanceContext, createEnhanceContext, getContextForElement, getOrCreateEnhanceContext };
export type { EnhancedElementContext, SnapshotFontSessionState };
