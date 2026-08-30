import type { EnhancedElementContext, RawDomParagraphRecord } from "./context/enhance-context.js";
import { releasePreparedParagraphStyles } from "../sampler/snapshot/prepared-dom.js";
import { DEFAULT_PARAGRAPH_SELECTOR } from "../sampler/signatures.js";

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

export type RawDomSnapshot = {
  source: Element;
  content: DocumentFragment;
  renderedAttribute: string | null;
  preparedFlowAttribute: string | null;
  canonicalSourceAttribute: string | null;
  snapshotPreparedDomAttribute: string | null;
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

const CANONICAL_SOURCE_ATTRIBUTE: string = "data-tq-canonical-source";
const SNAPSHOT_PREPARED_DOM_ATTRIBUTE: string = "data-tq-snapshot-prepared-dom";
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

function stampRenderedContent(record: RawDomParagraphRecord, source: Element): void {
  record.renderedNodes = liveChildNodes(source);
}

function stampRawDomContent(record: RawDomParagraphRecord, source: Element): void {
  record.rawDomNodes = liveChildNodes(record.originalContent as DocumentFragment);
  record.fragment = record.originalContent as DocumentFragment;
  installRawDomCommitForwarding(source, record);
}

function installRawDomCommitForwarding(paragraph: Element, record: RawDomParagraphRecord): void {
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

export function rawDomRecordOf(context: EnhancedElementContext, source: Element): RawDomParagraphRecord {
  let record = context.rawDomParagraphs.get(source);
  if (!record) {
    record = {
      fragment: null,
      engineWriteDepth: 0,
      forwarding: false,
      originalContent: null,
      renderedNodes: [],
      rawDomNodes: [],
      originalRenderedAttribute: null,
      originalPreparedFlowAttribute: null,
      originalCanonicalSourceAttribute: null,
      originalSnapshotPreparedDomAttribute: null,
      originalLangAttribute: null,
      originalStyleAttribute: null,
      originalPosition: "",
      originalPositionPriority: "",
      originalInlineSize: "",
      originalInlineSizePriority: "",
      originalFontSize: "",
      originalFontSizePriority: "",
      originalHostInlineSizeAttribute: null,
      containingBlockApplied: false,
      hostInlineSizeApplied: null,
      hostFontSizeApplied: null,
    };
    context.rawDomParagraphs.set(source, record);
  }
  return record;
}

function recordOfOrThrow(context: EnhancedElementContext, source: Element): RawDomParagraphRecord {
  const record = context.rawDomParagraphs.get(source);
  if (!record) {
    throw new Error("rawDom state missing for paragraph");
  }
  return record;
}

export function rawDomBegin(
  context: EnhancedElementContext,
  source: Element,
  renderedAttribute: string | null,
  preparedFlowAttribute: string | null,
  canonicalSourceAttribute: string | null,
  snapshotPreparedDomAttribute: string | null,
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
  const record = rawDomRecordOf(context, source);
  record.originalContent = null;
  record.renderedNodes = [];
  record.rawDomNodes = [];
  record.originalRenderedAttribute = renderedAttribute;
  record.originalPreparedFlowAttribute = preparedFlowAttribute;
  record.originalCanonicalSourceAttribute = canonicalSourceAttribute;
  record.originalSnapshotPreparedDomAttribute = snapshotPreparedDomAttribute;
  record.originalLangAttribute = langAttribute;
  record.originalStyleAttribute = styleAttribute;
  record.originalPosition = position;
  record.originalPositionPriority = positionPriority;
  record.originalInlineSize = inlineSize;
  record.originalInlineSizePriority = inlineSizePriority;
  record.originalFontSize = fontSize;
  record.originalFontSizePriority = fontSizePriority;
  record.originalHostInlineSizeAttribute = hostInlineSizeAttribute;
  record.containingBlockApplied = false;
  record.hostInlineSizeApplied = null;
  record.hostFontSizeApplied = null;
}

export function rawDomTake(context: EnhancedElementContext, source: Element, hostFontSizeApplied: string | null): void {
  const record = recordOfOrThrow(context, source);
  record.hostFontSizeApplied = hostFontSizeApplied;
  const fragment = globalThis.document.createDocumentFragment();
  while (source.firstChild) {
    fragment.appendChild(source.firstChild as ChildNode);
  }
  record.originalContent = fragment;
}

export function rawDomCommit(context: EnhancedElementContext, source: Element, hostInlineSizeApplied: string | null): void {
  const record = recordOfOrThrow(context, source);
  record.hostInlineSizeApplied = hostInlineSizeApplied;
  stampRawDomContent(record, source);
}

export function rawDomStampRendered(context: EnhancedElementContext, source: Element): void {
  stampRenderedContent(recordOfOrThrow(context, source), source);
}

export function rawDomRenderedMatches(context: EnhancedElementContext, source: Element): boolean {
  const record = recordOfOrThrow(context, source);
  const recorded = record.renderedNodes;
  let child: ChildNode | null = source.firstChild;
  let index = 0;
  while (child) {
    if (index >= recorded.length || recorded[index] !== child) return false;
    index += 1;
    child = child.nextSibling;
  }
  return index === recorded.length;
}

export function rawDomMatches(context: EnhancedElementContext, source: Element): boolean {
  const record = recordOfOrThrow(context, source);
  const recorded = record.rawDomNodes;
  let child: ChildNode | null = (record.originalContent as DocumentFragment).firstChild;
  let index = 0;
  while (child) {
    if (index >= recorded.length || recorded[index] !== child) return false;
    index += 1;
    child = child.nextSibling;
  }
  return index === recorded.length;
}

export function rawDomCaptureLive(context: EnhancedElementContext, source: Element, lastMeasure: number | null): RawDomSnapshot {
  const record = recordOfOrThrow(context, source);
  const content = globalThis.document.createDocumentFragment();
  const snapshot: RawDomSnapshot = {
    source: source,
    content: content,
    renderedAttribute: source.getAttribute("data-tq-rendered"),
    preparedFlowAttribute: source.getAttribute("data-tq-canonical-plain"),
    canonicalSourceAttribute: source.getAttribute(CANONICAL_SOURCE_ATTRIBUTE),
    snapshotPreparedDomAttribute: source.getAttribute(SNAPSHOT_PREPARED_DOM_ATTRIBUTE),
    langAttribute: source.getAttribute("lang"),
    styleAttribute: source.getAttribute("style"),
    capabilityNameAttribute: source.getAttribute("data-tiqian-capability-issue"),
    capabilityDetailAttribute: source.getAttribute("data-tiqian-capability-detail"),
    lastMeasure: lastMeasure,
    containingBlockApplied: record.containingBlockApplied,
    hostInlineSizeApplied: record.hostInlineSizeApplied,
    hostInlineSizeAttribute: source.getAttribute(HOST_INLINE_SIZE_ATTRIBUTE),
    originalContentHadChildren: (record.originalContent as DocumentFragment).firstChild !== null,
  };
  while (source.firstChild) {
    content.appendChild(source.firstChild as ChildNode);
  }
  stampRenderedContent(record, source);
  return snapshot;
}

export function rawDomRollback(context: EnhancedElementContext, snapshots: RawDomSnapshot[]): RawDomRollbackResult[] {
  const results: RawDomRollbackResult[] = [];
  for (let i = snapshots.length - 1; i >= 0; i--) {
    const snapshot = snapshots[i];
    const source = snapshot.source;
    const record = recordOfOrThrow(context, source);
    if (snapshot.originalContentHadChildren && (record.originalContent as DocumentFragment).firstChild === null) {
      while (source.firstChild) {
        (record.originalContent as DocumentFragment).appendChild(source.firstChild as ChildNode);
      }
      stampRawDomContent(record, source);
    } else {
      while (source.firstChild) {
        source.removeChild(source.firstChild as ChildNode);
      }
    }
    source.appendChild(snapshot.content);
    restoreAttribute(source, "data-tq-rendered", snapshot.renderedAttribute);
    restoreAttribute(source, "data-tq-canonical-plain", snapshot.preparedFlowAttribute);
    restoreAttribute(source, CANONICAL_SOURCE_ATTRIBUTE, snapshot.canonicalSourceAttribute);
    restoreAttribute(source, SNAPSHOT_PREPARED_DOM_ATTRIBUTE, snapshot.snapshotPreparedDomAttribute);
    restoreAttribute(source, "lang", snapshot.langAttribute);
    restoreAttribute(source, "style", snapshot.styleAttribute);
    restoreAttribute(source, "data-tiqian-capability-issue", snapshot.capabilityNameAttribute);
    restoreAttribute(source, "data-tiqian-capability-detail", snapshot.capabilityDetailAttribute);
    record.containingBlockApplied = snapshot.containingBlockApplied;
    record.hostInlineSizeApplied = snapshot.hostInlineSizeApplied;
    restoreAttribute(source, HOST_INLINE_SIZE_ATTRIBUTE, snapshot.hostInlineSizeAttribute);
    stampRenderedContent(record, source);
    results.push({ source: source, lastMeasure: snapshot.lastMeasure });
  }
  return results;
}

export function rawDomRestoreParagraph(context: EnhancedElementContext, source: Element): void {
  const record = recordOfOrThrow(context, source);
  releasePreparedParagraphStyles(source, context);
  while (source.firstChild) {
    source.removeChild(source.firstChild as ChildNode);
  }
  source.appendChild(record.originalContent as DocumentFragment);
  stampRawDomContent(record, source);
  rawDomRestoreShell(context, source as HTMLElement);
  stampRenderedContent(record, source);
}

export function rawDomRestoreShell(context: EnhancedElementContext, source: HTMLElement): void {
  const record = recordOfOrThrow(context, source);
  const style = source.style;
  restoreAttribute(source, "data-tq-rendered", record.originalRenderedAttribute);
  restoreAttribute(source, "data-tq-canonical-plain", record.originalPreparedFlowAttribute);
  restoreAttribute(source, CANONICAL_SOURCE_ATTRIBUTE, record.originalCanonicalSourceAttribute);
  restoreAttribute(source, SNAPSHOT_PREPARED_DOM_ATTRIBUTE, record.originalSnapshotPreparedDomAttribute);
  source.removeAttribute(RUNTIME_RENDER_FONT_ATTRIBUTE);
  restoreAttribute(source, "lang", record.originalLangAttribute);
  if (
    record.containingBlockApplied &&
    style.getPropertyValue("position") === "relative" &&
    style.getPropertyPriority("position") === "important"
  ) {
    if (record.originalPosition === "") {
      style.removeProperty("position");
    } else {
      style.setProperty("position", record.originalPosition, record.originalPositionPriority);
    }
  }
  const appliedInlineSize = record.hostInlineSizeApplied;
  if (
    appliedInlineSize !== null &&
    source.getAttribute(HOST_INLINE_SIZE_ATTRIBUTE) === "true" &&
    style.getPropertyValue("inline-size") === appliedInlineSize &&
    style.getPropertyPriority("inline-size") === "important"
  ) {
    if (record.originalInlineSize === "") {
      style.removeProperty("inline-size");
    } else {
      style.setProperty("inline-size", record.originalInlineSize, record.originalInlineSizePriority);
    }
  }
  const appliedFontSize = record.hostFontSizeApplied;
  if (
    appliedFontSize !== null &&
    style.getPropertyValue("font-size") === appliedFontSize &&
    style.getPropertyPriority("font-size") === "important"
  ) {
    if (record.originalFontSize === "") {
      style.removeProperty("font-size");
    } else {
      style.setProperty("font-size", record.originalFontSize, record.originalFontSizePriority);
    }
  }
  restoreAttribute(source, HOST_INLINE_SIZE_ATTRIBUTE, record.originalHostInlineSizeAttribute);
  if (record.originalStyleAttribute === null) {
    const currentStyle = source.getAttribute("style");
    if (currentStyle === null || currentStyle.trim() === "") {
      source.removeAttribute("style");
    }
  }
  record.containingBlockApplied = false;
  record.hostInlineSizeApplied = null;
}

export function rawDomEnsureContainingBlock(context: EnhancedElementContext, source: HTMLElement): void {
  const record = recordOfOrThrow(context, source);
  if (record.containingBlockApplied) return;
  const position = globalThis.getComputedStyle(source).getPropertyValue("position");
  if (position.trim().toLowerCase() !== "static") return;
  source.style.setProperty("position", "relative", "important");
  record.containingBlockApplied = true;
}

// Named per G1 code standard rule 5: the suspended action runs with engine
// writes held off; its return value passes through untouched.
type RawDomSuspendedAction<T> = () => T;

export function rawDomSuspendEngineWrites<T>(context: EnhancedElementContext, source: Element, action: RawDomSuspendedAction<T>): T {
  const record = rawDomRecordOf(context, source);
  record.engineWriteDepth = (record.engineWriteDepth || 0) + 1;
  try {
    return action();
  } finally {
    record.engineWriteDepth -= 1;
  }
}

const ROOT_SELECTOR = "tiqian-prose, [data-tiqian-root]";
const RENDERED_PARAGRAPH_SELECTOR = "p[data-tq-rendered=true], li[data-tq-rendered=true]";
const RENDERED_RAW_DOM_SELECTOR = `:is(${DEFAULT_PARAGRAPH_SELECTOR})[data-tq-rendered="true"]`;

export function renderedRawDomParagraphs(context: EnhancedElementContext, root: Element): [Element, RawDomParagraphRecord][] {
  const pairs: [Element, RawDomParagraphRecord][] = [];
  const paragraphs = root.querySelectorAll(RENDERED_RAW_DOM_SELECTOR);
  for (let i = 0; i < paragraphs.length; i += 1) {
    const paragraph = paragraphs[i];
    const record = context.rawDomParagraphs.get(paragraph);
    if (record) pairs.push([paragraph, record]);
  }
  return pairs;
}

export function rendererOwnedProgressiveStyleMutation(record: MutationRecord, root: Element): boolean {
  if (record.attributeName !== "style") return false;
  const targetNode = record.target;
  if (!targetNode || targetNode.nodeType !== 1) return false;
  const target = targetNode as Element;
  if (!target.matches(RENDERED_PARAGRAPH_SELECTOR)) return false;
  if (target.closest(ROOT_SELECTOR) !== root) return false;
  const targetDocument = target.ownerDocument;
  if (!targetDocument) return false;

  const previous = targetDocument.createElement(target.tagName);
  if (record.oldValue != null) previous.setAttribute("style", record.oldValue);
  const projected = targetDocument.createElement(target.tagName);
  const current = target.getAttribute("style");
  if (current != null) projected.setAttribute("style", current);
  let rendererPropertyFound = false;
  if (
    projected.style.getPropertyValue("position") === "relative" &&
    projected.style.getPropertyPriority("position") === "important"
  ) {
    rendererPropertyFound = true;
    const value = previous.style.getPropertyValue("position");
    if (value) {
      projected.style.setProperty("position", value, previous.style.getPropertyPriority("position"));
    } else {
      projected.style.removeProperty("position");
    }
  }
  if (
    target.getAttribute(HOST_INLINE_SIZE_ATTRIBUTE) === "true" &&
    projected.style.getPropertyPriority("inline-size") === "important"
  ) {
    rendererPropertyFound = true;
    const value = previous.style.getPropertyValue("inline-size");
    if (value) {
      projected.style.setProperty(
        "inline-size",
        value,
        previous.style.getPropertyPriority("inline-size"),
      );
    } else {
      projected.style.removeProperty("inline-size");
    }
  }
  return rendererPropertyFound && projected.style.cssText === previous.style.cssText;
}
