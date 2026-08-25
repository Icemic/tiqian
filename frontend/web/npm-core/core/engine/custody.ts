// Source custody for enhanced paragraphs.
//
// ES module exporting the custody factory. The composition root
// (loaders/ts-runtime.ts) constructs one instance per engine bootstrap and
// passes it to process-paragraph, commit-prepared-paragraph,
// content-reconcile, and progressive-relayout-session through their deps.

// The prepared DOM renderer owns release; its global type is declared in
// prepare-paragraph-layout.ts.
import type {} from "./prepare-paragraph-layout.js";

// Per-paragraph custody state, keyed weakly so a discarded paragraph can be
// collected together with its state. The original-attribute snapshots mirror
// what begin captured before the engine overwrote host-owned markup.
interface CustodyState {
  originalContent: DocumentFragment | null;
  renderedNodes: Node[];
  custodyNodes: Node[];
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

// A paragraph under custody: the host's semantic children live in the
// published fragment, and the four mutation methods redirect host commits
// into it unless the engine write counter is above zero. The counter field is
// read as a number (not a boolean) so engineWriteSuspension in the prepared
// DOM bridge can increment and decrement it around engine writes.
type CustodyRemoveChildFn = (child: Node) => Node;
type CustodyInsertBeforeFn = (node: Node, ref: Node | null) => Node;
type CustodyReplaceChildFn = (next: Node, prev: Node) => Node;
type CustodyAppendChildFn = (node: Node) => Node;

type CustodyParagraphElement = Omit<
  Element,
  "removeChild" | "insertBefore" | "replaceChild" | "appendChild"
> & {
  __tqCustodyFragment: DocumentFragment;
  __tqCustodyForwarding: boolean;
  __tqCustodyEngineWrites: number;
  removeChild: CustodyRemoveChildFn;
  insertBefore: CustodyInsertBeforeFn;
  replaceChild: CustodyReplaceChildFn;
  appendChild: CustodyAppendChildFn;
};

// Live rendered-output snapshot taken by captureLive at a slice boundary and
// replayed by rollback in reverse order.
export type CustodySnapshot = {
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

export type CustodyRollbackResult = {
  source: Element;
  lastMeasure: number | null;
};

type CustodyBeginFn = (
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
type CustodyTakeFn = (source: Element, hostFontSizeApplied: string | null) => void;
type CustodyCommitFn = (source: Element, hostInlineSizeApplied: string | null) => void;
type CustodyStampRenderedFn = (source: Element) => void;
type CustodyRenderedMatchesFn = (source: Element) => boolean;
type CustodyMatchesFn = (source: Element) => boolean;
type CustodyCaptureLiveFn = (source: Element, lastMeasure: number | null) => CustodySnapshot;
type CustodyRollbackFn = (snapshots: CustodySnapshot[]) => CustodyRollbackResult[];
type CustodyRestoreParagraphFn = (source: Element) => void;
type CustodyRestoreShellFn = (source: HTMLElement) => void;
type CustodyEnsureContainingBlockFn = (source: HTMLElement) => void;

export type CustodyApi = {
  begin: CustodyBeginFn;
  take: CustodyTakeFn;
  commit: CustodyCommitFn;
  stampRendered: CustodyStampRenderedFn;
  renderedMatches: CustodyRenderedMatchesFn;
  custodyMatches: CustodyMatchesFn;
  captureLive: CustodyCaptureLiveFn;
  rollback: CustodyRollbackFn;
  restoreParagraph: CustodyRestoreParagraphFn;
  restoreShell: CustodyRestoreShellFn;
  ensureContainingBlock: CustodyEnsureContainingBlockFn;
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

function stampCustodyContent(state: CustodyState, source: Element): void {
  state.custodyNodes = liveChildNodes(state.originalContent as DocumentFragment);
  (source as CustodyParagraphElement).__tqCustodyFragment = state.originalContent as DocumentFragment;
  installCustodyCommitForwarding(source as CustodyParagraphElement);
}

function stampRenderedContent(state: CustodyState, source: Element): void {
  state.renderedNodes = liveChildNodes(source);
}

// CustodyAnchoredCommitForwarding: host frameworks keep node references
// and commit edits through the paragraph's own mutation methods while the
// semantic source lives in custody. Redirect those calls into the published
// fragment unless the engine itself is writing (__tqCustodyEngineWrites
// above zero). The overrides read the published fragment at call time, so
// a re-take with a fresh fragment needs no re-install. An empty fragment
// means the paragraph is not under custody and every branch falls through
// to native.
function installCustodyCommitForwarding(paragraph: CustodyParagraphElement): void {
  if (paragraph.__tqCustodyForwarding) {
    return;
  }
  const nativeRemoveChild = Node.prototype.removeChild;
  const nativeInsertBefore = Node.prototype.insertBefore;
  const nativeReplaceChild = Node.prototype.replaceChild;
  const nativeAppendChild = Node.prototype.appendChild;
  const activeCustody = function (): DocumentFragment | null {
    const fragment = paragraph.__tqCustodyFragment;
    return fragment && fragment.childNodes.length > 0 ? fragment : null;
  };
  const heldInCustody = function (node: Node | null): boolean {
    const fragment = paragraph.__tqCustodyFragment;
    return !!fragment && !!node && node.parentNode === fragment;
  };
  const engineWriting = function (): boolean {
    return paragraph.__tqCustodyEngineWrites > 0;
  };
  paragraph.removeChild = function (child: Node): Node {
    if (engineWriting()) return nativeRemoveChild.call(paragraph, child);
    if (heldInCustody(child)) return paragraph.__tqCustodyFragment.removeChild(child);
    return nativeRemoveChild.call(paragraph, child);
  };
  paragraph.insertBefore = function (node: Node, ref: Node | null): Node {
    if (engineWriting()) return nativeInsertBefore.call(paragraph, node, ref);
    if (heldInCustody(ref)) return paragraph.__tqCustodyFragment.insertBefore(node, ref);
    if (!ref && node && node.nodeType !== 11) {
      const fragment = activeCustody();
      if (fragment) return fragment.appendChild(node);
    }
    return nativeInsertBefore.call(paragraph, node, ref);
  };
  paragraph.replaceChild = function (next: Node, prev: Node): Node {
    if (engineWriting()) return nativeReplaceChild.call(paragraph, next, prev);
    if (heldInCustody(prev)) return paragraph.__tqCustodyFragment.replaceChild(next, prev);
    return nativeReplaceChild.call(paragraph, next, prev);
  };
  paragraph.appendChild = function (node: Node): Node {
    if (engineWriting()) return nativeAppendChild.call(paragraph, node);
    if (node && node.nodeType !== 11) {
      const fragment = activeCustody();
      if (fragment) return fragment.appendChild(node);
    }
    return nativeAppendChild.call(paragraph, node);
  };
  paragraph.__tqCustodyForwarding = true;
}

export function createCustody(): CustodyApi {
  // Per-paragraph custody state, keyed weakly so a discarded paragraph can be
  // collected together with its state.
  const states = new WeakMap<Element, CustodyState>();

  function stateOf(source: Element): CustodyState {
    const state = states.get(source);
    if (!state) {
      throw new Error("custody state missing for paragraph");
    }
    return state;
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
      custodyNodes: [],
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

  // Moves the semantic source children into a detached custody fragment.
  function take(source: Element, hostFontSizeApplied: string | null): void {
    const state = stateOf(source);
    state.hostFontSizeApplied = hostFontSizeApplied;
    const fragment = globalThis.document.createDocumentFragment();
    while (source.firstChild) {
      fragment.appendChild(source.firstChild as ChildNode);
    }
    state.originalContent = fragment;
  }

  // Publishes the custody fragment on the paragraph and installs commit
  // forwarding. Runs after the pipeline stabilized the source inline size.
  function commit(source: Element, hostInlineSizeApplied: string | null): void {
    const state = stateOf(source);
    state.hostInlineSizeApplied = hostInlineSizeApplied;
    stampCustodyContent(state, source);
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

  function custodyMatches(source: Element): boolean {
    const state = stateOf(source);
    const recorded = state.custodyNodes;
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
  function captureLive(source: Element, lastMeasure: number | null): CustodySnapshot {
    const state = stateOf(source);
    const content = globalThis.document.createDocumentFragment();
    const snapshot: CustodySnapshot = {
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
  function rollback(snapshots: CustodySnapshot[]): CustodyRollbackResult[] {
    const results: CustodyRollbackResult[] = [];
    for (let i = snapshots.length - 1; i >= 0; i--) {
      const snapshot = snapshots[i];
      const source = snapshot.source;
      const state = stateOf(source);
      if (snapshot.originalContentHadChildren && (state.originalContent as DocumentFragment).firstChild === null) {
        // restoreParagraph() handed the semantic source fragment back to the
        // live DOM; move those exact nodes into source custody again before
        // replaying the previous rendered fragment.
        while (source.firstChild) {
          (state.originalContent as DocumentFragment).appendChild(source.firstChild as ChildNode);
        }
        stampCustodyContent(state, source);
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
    // The drain empties custody. Restamp so a paragraph that stays tracked
    // through the relayout-unsupported window does not read as host drift.
    stampCustodyContent(state, source);
    restoreShell(source as HTMLElement);
    stampRenderedContent(state, source);
  }

  // Restores the paragraph element attributes and inline style entries the
  // engine overwrote during takeover. Shared by the custody restore path and
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

  return {
    begin: begin,
    take: take,
    commit: commit,
    stampRendered: stampRendered,
    renderedMatches: renderedMatches,
    custodyMatches: custodyMatches,
    captureLive: captureLive,
    rollback: rollback,
    restoreParagraph: restoreParagraph,
    restoreShell: restoreShell,
    ensureContainingBlock: ensureContainingBlock,
  };
}