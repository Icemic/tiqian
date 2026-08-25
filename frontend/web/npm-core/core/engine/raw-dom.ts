// Source raw-DOM backup for enhanced paragraphs.
//
// ES module exporting the raw-DOM backup factory. The composition root
// (loaders/ts-runtime.ts) constructs one instance per engine bootstrap and
// passes it to process-paragraph, commit-prepared-paragraph,
// content-reconcile, and relayout-session through their deps.

// The prepared DOM renderer owns release; its global type is declared in
// prepare-paragraph-layout.ts.
import type {} from "./prepare-paragraph-layout.js";

// Per-paragraph raw-DOM backup state, keyed weakly so a discarded paragraph can be
// collected together with its state. The original-attribute snapshots mirror
// what begin captured before the engine overwrote host-owned markup.
interface RawDomState {
  originalContent: DocumentFragment | null;
  renderedNodes: Node[];
  rawDomNodes: Node[];
  originalRenderedAttribute: string | null;
  originalPreparedFlowAttribute: string | null;
  originalCanonicalSourceAttribute: string | null;
  originalExactPreparedDomAttribute: string | null;
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

// A paragraph under raw-DOM backup: the host's semantic children live in the
// published fragment, and the four mutation methods redirect host commits
// into it unless the engine write counter is above zero. The counter field is
// read as a number (not a boolean) so rawDomEngineWriteSuspension in the prepared
// DOM bridge can increment and decrement it around engine writes.
type RawDomRemoveChildFn = (child: Node) => Node;
type RawDomInsertBeforeFn = (node: Node, ref: Node | null) => Node;
type RawDomReplaceChildFn = (next: Node, prev: Node) => Node;
type RawDomAppendChildFn = (node: Node) => Node;

type RawDomParagraphElement = Omit<
  Element,
  "removeChild" | "insertBefore" | "replaceChild" | "appendChild"
> & {
  removeChild: RawDomRemoveChildFn;
  insertBefore: RawDomInsertBeforeFn;
  replaceChild: RawDomReplaceChildFn;
  appendChild: RawDomAppendChildFn;
};

// Live rendered-output snapshot taken by captureLive at a slice boundary and
// replayed by rollback in reverse order.
export type RawDomSnapshot = {
  source: Element;
  content: DocumentFragment;
  renderedAttribute: string | null;
  preparedFlowAttribute: string | null;
  canonicalSourceAttribute: string | null;
  exactPreparedDomAttribute: string | null;
  langAttribute: string | null;
  styleAttribute: string | null;
  capabilityNameAttribute: string | null;
  capabilityDetailAttribute: string | null;
  lastMeasure: number | null;
  containingBlockApplied: boolean;
  hostInlineSizeApplied: string | null;
  hostInlineSizeAttribute: string | null;
  originalContentHadChildren: boolean;
};

export type RawDomRollbackResult = {
  source: Element;
  lastMeasure: number | null;
};

type RawDomBeginFn = (
  source: Element,
  renderedAttribute: string | null,
  preparedFlowAttribute: string | null,
  canonicalSourceAttribute: string | null,
  exactPreparedDomAttribute: string | null,
  langAttribute: string | null,
  styleAttribute: string | null,
  position: string,
  positionPriority: string,
  inlineSize: string,
  inlineSizePriority: string,
  fontSize: string,
  fontSizePriority: string,
  hostInlineSizeAttribute: string | null,
) => void;
type RawDomTakeFn = (source: Element, hostFontSizeApplied: string | null) => void;
type RawDomCommitFn = (source: Element, hostInlineSizeApplied: string | null) => void;
type RawDomStampRenderedFn = (source: Element) => void;
type RawDomRenderedMatchesFn = (source: Element) => boolean;
type RawDomMatchesFn = (source: Element) => boolean;
type RawDomCaptureLiveFn = (source: Element, lastMeasure: number | null) => RawDomSnapshot;
type RawDomRollbackFn = (snapshots: RawDomSnapshot[]) => RawDomRollbackResult[];
type RawDomRestoreParagraphFn = (source: Element) => void;
type RawDomRestoreShellFn = (source: HTMLElement) => void;
type RawDomEnsureContainingBlockFn = (source: HTMLElement) => void;
type RawDomSuspendEngineWritesActionFn<T> = () => T;
type RawDomSuspendEngineWritesFn = <T>(source: Element, action: RawDomSuspendEngineWritesActionFn<T>) => T;

export type RawDomApi = {
  begin: RawDomBeginFn;
  take: RawDomTakeFn;
  commit: RawDomCommitFn;
  stampRendered: RawDomStampRenderedFn;
  renderedMatches: RawDomRenderedMatchesFn;
  rawDomMatches: RawDomMatchesFn;
  captureLive: RawDomCaptureLiveFn;
  rollback: RawDomRollbackFn;
  restoreParagraph: RawDomRestoreParagraphFn;
  restoreShell: RawDomRestoreShellFn;
  ensureContainingBlock: RawDomEnsureContainingBlockFn;
  suspendEngineWrites: RawDomSuspendEngineWritesFn;
};

const CANONICAL_SOURCE_ATTRIBUTE: string = "data-tq-canonical-source";
const EXACT_PREPARED_DOM_ATTRIBUTE: string = "data-tq-exact-prepared-dom";
const RUNTIME_RENDER_FONT_ATTRIBUTE: string = "data-tq-runtime-render-font";
const HOST_INLINE_SIZE_ATTRIBUTE: string = "data-tq-host-inline-size";

function liveChildNodes(element: Node): Node[] {
  const nodes: Node[] = [];
  let child: ChildNode | null = element.firstChild;
  while (child) {
    nodes.push(child);
    child = child.nextSibling;
  }
  return nodes;
}

function restoreAttribute(element: Element, name: string, value: string | null): void {
  if (value === null || value === undefined) {
    element.removeAttribute(name);
  } else {
    element.setAttribute(name, value);
  }
}

function stampRenderedContent(state: RawDomState, source: Element): void {
  state.renderedNodes = liveChildNodes(source);
}

type GetEnhanceContextFn = (element: Element) => import("./context/enhance-context.js").EnhancedElementContext;

export interface RawDomDeps {
  getEnhanceContext: GetEnhanceContextFn;
}

export function deriveRawDom(deps: RawDomDeps): RawDomApi {
  // Per-paragraph raw-DOM backup state, keyed weakly so a discarded paragraph can be
  // collected together with its state.
  const states = new WeakMap<Element, RawDomState>();

  function stateOf(source: Element): RawDomState {
    const state = states.get(source);
    if (!state) {
      throw new Error("rawDom state missing for paragraph");
    }
    return state;
  }

  function recordOf(source: Element) {
    const table = deps.getEnhanceContext(source).rawDomParagraphs;
    let record = table.get(source);
    if (!record) {
      record = { fragment: null, engineWriteDepth: 0, forwarding: false };
      table.set(source, record);
    }
    return record;
  }

  function stampRawDomContent(state: RawDomState, source: Element): void {
    state.rawDomNodes = liveChildNodes(state.originalContent as DocumentFragment);
    const record = recordOf(source);
    record.fragment = state.originalContent as DocumentFragment;
    installRawDomCommitForwarding(source, record);
  }

  function installRawDomCommitForwarding(paragraph: Element, record: import("./context/enhance-context.js").RawDomParagraphRecord): void {
    if (record.forwarding) {
      return;
    }
    const nativeRemoveChild = Node.prototype.removeChild;
    const nativeInsertBefore = Node.prototype.insertBefore;
    const nativeReplaceChild = Node.prototype.replaceChild;
    const nativeAppendChild = Node.prototype.appendChild;
    const activeRawDom = function (): DocumentFragment | null {
      const fragment = record.fragment;
      return fragment && fragment.childNodes.length > 0 ? fragment : null;
    };
    const heldInRawDom = function (node: Node | null): boolean {
      const fragment = record.fragment;
      return !!fragment && !!node && node.parentNode === fragment;
    };
    const engineWriting = function (): boolean {
      return record.engineWriteDepth > 0;
    };
    paragraph.removeChild = function <T extends Node>(child: T): T {
      if (engineWriting()) return nativeRemoveChild.call(paragraph, child) as T;
      if (heldInRawDom(child)) return record.fragment!.removeChild(child) as T;
      return nativeRemoveChild.call(paragraph, child) as T;
    };
    paragraph.insertBefore = function <T extends Node>(node: T, ref: Node | null): T {
      if (engineWriting()) return nativeInsertBefore.call(paragraph, node, ref) as T;
      if (heldInRawDom(ref)) return record.fragment!.insertBefore(node, ref) as T;
      if (!ref && node && node.nodeType !== 11) {
        const fragment = activeRawDom();
        if (fragment) return fragment.appendChild(node) as T;
      }
      return nativeInsertBefore.call(paragraph, node, ref) as T;
    };
    paragraph.replaceChild = function <T extends Node>(next: Node, prev: T): T {
      if (engineWriting()) return nativeReplaceChild.call(paragraph, next, prev) as T;
      if (heldInRawDom(prev)) return record.fragment!.replaceChild(next, prev) as T;
      return nativeReplaceChild.call(paragraph, next, prev) as T;
    };
    paragraph.appendChild = function <T extends Node>(node: T): T {
      if (engineWriting()) return nativeAppendChild.call(paragraph, node) as T;
      if (node && node.nodeType !== 11) {
        const fragment = activeRawDom();
        if (fragment) return fragment.appendChild(node) as T;
      }
      return nativeAppendChild.call(paragraph, node) as T;
    };
    record.forwarding = true;
  }

  // Captures every host-owned attribute and inline style entry the engine may
  // overwrite during takeover. Must run before the first engine write of the
  // current takeover (applyConfiguredHostFontSize in the pipeline).
  function begin(
    source: Element,
    renderedAttribute: string | null,
    preparedFlowAttribute: string | null,
    canonicalSourceAttribute: string | null,
    exactPreparedDomAttribute: string | null,
    langAttribute: string | null,
    styleAttribute: string | null,
    position: string,
    positionPriority: string,
    inlineSize: string,
    inlineSizePriority: string,
    fontSize: string,
    fontSizePriority: string,
    hostInlineSizeAttribute: string | null
  ): void {
    states.set(source, {
      originalContent: null,
      renderedNodes: [],
      rawDomNodes: [],
      originalRenderedAttribute: renderedAttribute,
      originalPreparedFlowAttribute: preparedFlowAttribute,
      originalCanonicalSourceAttribute: canonicalSourceAttribute,
      originalExactPreparedDomAttribute: exactPreparedDomAttribute,
      originalLangAttribute: langAttribute,
      originalStyleAttribute: styleAttribute,
      originalPosition: position,
      originalPositionPriority: positionPriority,
      originalInlineSize: inlineSize,
      originalInlineSizePriority: inlineSizePriority,
      originalFontSize: fontSize,
      originalFontSizePriority: fontSizePriority,
      originalHostInlineSizeAttribute: hostInlineSizeAttribute,
      containingBlockApplied: false,
      hostInlineSizeApplied: null,
      hostFontSizeApplied: null,
    });
  }

  // Moves the semantic source children into a detached raw-DOM backup fragment.
  function take(source: Element, hostFontSizeApplied: string | null): void {
    const state = stateOf(source);
    state.hostFontSizeApplied = hostFontSizeApplied;
    const fragment = globalThis.document.createDocumentFragment();
    while (source.firstChild) {
      fragment.appendChild(source.firstChild as ChildNode);
    }
    state.originalContent = fragment;
  }

  // Publishes the raw-DOM backup fragment on the paragraph and installs commit
  // forwarding. Runs after the pipeline stabilized the source inline size.
  function commit(source: Element, hostInlineSizeApplied: string | null): void {
    const state = stateOf(source);
    state.hostInlineSizeApplied = hostInlineSizeApplied;
    stampRawDomContent(state, source);
  }

  function stampRendered(source: Element): void {
    stampRenderedContent(stateOf(source), source);
  }

  function renderedMatches(source: Element): boolean {
    const state = stateOf(source);
    const recorded = state.renderedNodes;
    let child: ChildNode | null = source.firstChild;
    let index = 0;
    while (child) {
      if (index >= recorded.length || recorded[index] !== child) return false;
      index += 1;
      child = child.nextSibling;
    }
    return index === recorded.length;
  }

  function rawDomMatches(source: Element): boolean {
    const state = stateOf(source);
    const recorded = state.rawDomNodes;
    let child: ChildNode | null = (state.originalContent as DocumentFragment).firstChild;
    let index = 0;
    while (child) {
      if (index >= recorded.length || recorded[index] !== child) return false;
      index += 1;
      child = child.nextSibling;
    }
    return index === recorded.length;
  }

  // Snapshots the current rendered output of a paragraph at a slice boundary
  // so a later rollback can replay it. Drains the live children into the
  // snapshot fragment. Reads snapshot attributes before draining, matching
  // the previous Kotlin ordering.
  function captureLive(source: Element, lastMeasure: number | null): RawDomSnapshot {
    const state = stateOf(source);
    const content = globalThis.document.createDocumentFragment();
    const snapshot: RawDomSnapshot = {
      source: source,
      content: content,
      renderedAttribute: source.getAttribute("data-tq-rendered"),
      preparedFlowAttribute: source.getAttribute("data-tq-canonical-plain"),
      canonicalSourceAttribute: source.getAttribute(CANONICAL_SOURCE_ATTRIBUTE),
      exactPreparedDomAttribute: source.getAttribute(EXACT_PREPARED_DOM_ATTRIBUTE),
      langAttribute: source.getAttribute("lang"),
      styleAttribute: source.getAttribute("style"),
      capabilityNameAttribute: source.getAttribute("data-tiqian-capability-issue"),
      capabilityDetailAttribute: source.getAttribute("data-tiqian-capability-detail"),
      lastMeasure: lastMeasure,
      containingBlockApplied: state.containingBlockApplied,
      hostInlineSizeApplied: state.hostInlineSizeApplied,
      hostInlineSizeAttribute: source.getAttribute(HOST_INLINE_SIZE_ATTRIBUTE),
      originalContentHadChildren: (state.originalContent as DocumentFragment).firstChild !== null,
    };
    while (source.firstChild) {
      content.appendChild(source.firstChild as ChildNode);
    }
    stampRenderedContent(state, source);
    return snapshot;
  }

  // Replays snapshots in reverse order. Each replayed paragraph gets its
  // snapshot content, attributes and flags back; the caller receives the
  // lastMeasure per source element.
  function rollback(snapshots: RawDomSnapshot[]): RawDomRollbackResult[] {
    const results: RawDomRollbackResult[] = [];
    for (let i = snapshots.length - 1; i >= 0; i--) {
      const snapshot = snapshots[i];
      const source = snapshot.source;
      const state = stateOf(source);
      if (snapshot.originalContentHadChildren && (state.originalContent as DocumentFragment).firstChild === null) {
        // restoreParagraph() handed the semantic source fragment back to the
        // live DOM; move those exact nodes into source raw-DOM backup again before
        // replaying the previous rendered fragment.
        while (source.firstChild) {
          (state.originalContent as DocumentFragment).appendChild(source.firstChild as ChildNode);
        }
        stampRawDomContent(state, source);
      } else {
        while (source.firstChild) {
          source.removeChild(source.firstChild as ChildNode);
        }
      }
      source.appendChild(snapshot.content);
      restoreAttribute(source, "data-tq-rendered", snapshot.renderedAttribute);
      restoreAttribute(source, "data-tq-canonical-plain", snapshot.preparedFlowAttribute);
      restoreAttribute(source, CANONICAL_SOURCE_ATTRIBUTE, snapshot.canonicalSourceAttribute);
      restoreAttribute(source, EXACT_PREPARED_DOM_ATTRIBUTE, snapshot.exactPreparedDomAttribute);
      restoreAttribute(source, "lang", snapshot.langAttribute);
      restoreAttribute(source, "style", snapshot.styleAttribute);
      restoreAttribute(source, "data-tiqian-capability-issue", snapshot.capabilityNameAttribute);
      restoreAttribute(source, "data-tiqian-capability-detail", snapshot.capabilityDetailAttribute);
      state.containingBlockApplied = snapshot.containingBlockApplied;
      state.hostInlineSizeApplied = snapshot.hostInlineSizeApplied;
      restoreAttribute(source, HOST_INLINE_SIZE_ATTRIBUTE, snapshot.hostInlineSizeAttribute);
      stampRenderedContent(state, source);
      results.push({ source: source, lastMeasure: snapshot.lastMeasure });
    }
    return results;
  }

  // Hands the semantic source back to the live DOM and restores the shell
  // the engine overwrote. Used by destroy and by unsupported relayouts.
  function restoreParagraph(source: Element): void {
    const state = stateOf(source);
    const renderer = globalThis.__TiqianPreparedDomRenderer;
    if (renderer) {
      renderer.release(source);
    }
    while (source.firstChild) {
      source.removeChild(source.firstChild as ChildNode);
    }
    source.appendChild(state.originalContent as DocumentFragment);
    // The drain empties raw-DOM backup. Restamp so a paragraph that stays tracked
    // through the relayout-unsupported window does not read as host drift.
    stampRawDomContent(state, source);
    restoreShell(source as HTMLElement);
    stampRenderedContent(state, source);
  }

  // Restores the paragraph element attributes and inline style entries the
  // engine overwrote during takeover. Shared by the raw-DOM backup restore path and
  // the content-reconcile path that keeps host-mutated live children.
  function restoreShell(source: HTMLElement): void {
    const state = stateOf(source);
    const style = source.style;
    restoreAttribute(source, "data-tq-rendered", state.originalRenderedAttribute);
    restoreAttribute(source, "data-tq-canonical-plain", state.originalPreparedFlowAttribute);
    restoreAttribute(source, CANONICAL_SOURCE_ATTRIBUTE, state.originalCanonicalSourceAttribute);
    restoreAttribute(source, EXACT_PREPARED_DOM_ATTRIBUTE, state.originalExactPreparedDomAttribute);
    source.removeAttribute(RUNTIME_RENDER_FONT_ATTRIBUTE);
    restoreAttribute(source, "lang", state.originalLangAttribute);
    if (
      state.containingBlockApplied &&
      style.getPropertyValue("position") === "relative" &&
      style.getPropertyPriority("position") === "important"
    ) {
      if (state.originalPosition === "") {
        style.removeProperty("position");
      } else {
        style.setProperty("position", state.originalPosition, state.originalPositionPriority);
      }
    }
    const appliedInlineSize = state.hostInlineSizeApplied;
    if (
      appliedInlineSize !== null &&
      source.getAttribute(HOST_INLINE_SIZE_ATTRIBUTE) === "true" &&
      style.getPropertyValue("inline-size") === appliedInlineSize &&
      style.getPropertyPriority("inline-size") === "important"
    ) {
      if (state.originalInlineSize === "") {
        style.removeProperty("inline-size");
      } else {
        style.setProperty("inline-size", state.originalInlineSize, state.originalInlineSizePriority);
      }
    }
    const appliedFontSize = state.hostFontSizeApplied;
    if (
      appliedFontSize !== null &&
      style.getPropertyValue("font-size") === appliedFontSize &&
      style.getPropertyPriority("font-size") === "important"
    ) {
      if (state.originalFontSize === "") {
        style.removeProperty("font-size");
      } else {
        style.setProperty("font-size", state.originalFontSize, state.originalFontSizePriority);
      }
    }
    restoreAttribute(source, HOST_INLINE_SIZE_ATTRIBUTE, state.originalHostInlineSizeAttribute);
    if (state.originalStyleAttribute === null) {
      const currentStyle = source.getAttribute("style");
      if (currentStyle === null || currentStyle.trim() === "") {
        source.removeAttribute("style");
      }
    }
    state.containingBlockApplied = false;
    state.hostInlineSizeApplied = null;
  }

  // The engine positions line markers absolutely against the paragraph, so a
  // statically positioned paragraph must become its containing block first.
  function ensureContainingBlock(source: HTMLElement): void {
    const state = stateOf(source);
    if (state.containingBlockApplied) return;
    const position = globalThis.getComputedStyle(source).getPropertyValue("position");
    if (position.trim().toLowerCase() !== "static") return;
    source.style.setProperty("position", "relative", "important");
    state.containingBlockApplied = true;
  }

  function suspendEngineWrites<T>(source: Element, action: RawDomSuspendEngineWritesActionFn<T>): T {
    const record = recordOf(source);
    record.engineWriteDepth = (record.engineWriteDepth || 0) + 1;
    try {
      return action();
    } finally {
      record.engineWriteDepth -= 1;
    }
  }

  return {
    begin: begin,
    take: take,
    commit: commit,
    stampRendered: stampRendered,
    renderedMatches: renderedMatches,
    rawDomMatches: rawDomMatches,
    captureLive: captureLive,
    rollback: rollback,
    restoreParagraph: restoreParagraph,
    restoreShell: restoreShell,
    ensureContainingBlock: ensureContainingBlock,
    suspendEngineWrites: suspendEngineWrites,
  };
}
