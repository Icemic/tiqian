// Isolated Node coverage for the pure DOM helpers lowered from the prose
// element. The Node test environment carries no DOM globals, so every
// fragment is a constructed fake and the whole file doubles as proof that the
// lowered modules import without globalThis.document.
import assert from "node:assert/strict";
import test from "node:test";

import { belongsToRootScope, rootScopedParagraphs } from "../core/sampler/observers.js";
import { hasStrongEmphasis, isPureBlockImageParagraph } from "../core/engine/eligibility.js";
import {
  renderedRawDomParagraphs,
  rendererOwnedProgressiveStyleMutation,
} from "../core/engine/raw-dom.js";
import {
  isRuntimeCompletionCandidate,
  snapshotCompletionSelector,
} from "../core/sampler/snapshot/snapshot-completion.js";
import { snapshotFontAttemptSignature } from "../core/sampler/signatures.js";
import { lineLengthGridMeasure } from "../core/sampler/grid-metrics.js";
import { hasHostInlineSizeParagraph } from "../core/engine/responsive-measure.js";
import { getOrCreateEnhanceContext } from "../core/engine/context/enhance-context.js";
import { ensureTiqianStyles } from "../core/engine/loaders/styles.js";
import { createTiqianClipboardPayload } from "../core/utils/copy.js";

interface FakeNode {
  nodeType: number;
  tagName: string;
  attributes: Map<string, string>;
  children: FakeNode[];
  parentNode: FakeNode | null;
  ownerDocument: FakeNode | null;
  data: string;
  fakeWidth: number;
  fakeDisplay: string;
}

function fakeOf(members: Record<string, unknown>) {
  return Object.assign(Object.create(null), members);
}

function makeFakeText(data: string) {
  return fakeOf({
    nodeType: 3,
    tagName: "",
    attributes: new Map(),
    children: [],
    parentNode: null,
    ownerDocument: null,
    data: data,
  });
}

interface StyleDeclaration {
  name: string;
  value: string;
  priority: string;
}

function makeFakeStyle() {
  const declarations: StyleDeclaration[] = [];
  const style = fakeOf({});
  function find(name: string) {
    return declarations.find((declaration) => declaration.name === name);
  }
  style.getPropertyValue = (name: string) => find(name)?.value ?? "";
  style.getPropertyPriority = (name: string) => find(name)?.priority ?? "";
  style.removeProperty = (name: string) => {
    const index = declarations.findIndex((declaration) => declaration.name === name);
    if (index !== -1) declarations.splice(index, 1);
  };
  style.setProperty = (name: string, value: string, priority = "") => {
    const existing = find(name);
    if (existing) {
      existing.value = value;
      existing.priority = priority;
      return;
    }
    declarations.push({ name: name, value: value, priority: priority });
  };
  style.parseCssText = (text: string) => {
    declarations.length = 0;
    for (const rawDeclaration of text.split(";")) {
      const trimmed = rawDeclaration.trim();
      if (!trimmed) continue;
      const colonIndex = trimmed.indexOf(":");
      if (colonIndex === -1) continue;
      const name = trimmed.slice(0, colonIndex).trim();
      let value = trimmed.slice(colonIndex + 1).trim();
      let priority = "";
      if (value.endsWith("!important")) {
        priority = "important";
        value = value.slice(0, -"!important".length).trim();
      }
      declarations.push({ name: name, value: value, priority: priority });
    }
  };
  Object.defineProperty(style, "cssText", {
    get() {
      return declarations
        .map((declaration) =>
          declaration.priority
            ? `${declaration.name}: ${declaration.value} !${declaration.priority};`
            : `${declaration.name}: ${declaration.value};`,
        )
        .join(" ");
    },
  });
  return style;
}

function makeFakeDocument() {
  const document = fakeOf({ nodeType: 9 });
  document.createElement = (tagName: string) => makeFakeElement(tagName, { ownerDocument: document });
  document.createTextNode = (data: string) => makeFakeText(data);
  document.head = fakeOf({ append() {} });
  document.querySelector = () => null;
  return document;
}

function textOf(node: FakeNode): string {
  if (node.nodeType === 3) return node.data;
  let text = "";
  for (const child of node.children) text += textOf(child);
  return text;
}

function selectorParts(selector: string): string[] {
  const parts: string[] = [];
  let depth = 0;
  let current = "";
  for (const character of selector) {
    if (character === "(") depth += 1;
    if (character === ")") depth -= 1;
    if (character === "," && depth === 0) {
      parts.push(current.trim());
      current = "";
      continue;
    }
    current += character;
  }
  if (current.trim()) parts.push(current.trim());
  return parts;
}

function attributeCondition(node: FakeNode, body: string): boolean {
  const equalsIndex = body.indexOf("=");
  if (equalsIndex === -1) return node.attributes.has(body);
  const name = body.slice(0, equalsIndex);
  const value = body.slice(equalsIndex + 1).replace(/^["']|["']$/gu, "");
  return node.attributes.get(name) === value;
}

function compoundMatches(node: FakeNode, compound: string): boolean {
  let rest = compound;
  let baseTag = "";
  let baseChecked = false;
  while (rest.length > 0) {
    if (rest.startsWith(":is(")) {
      const closeIndex = rest.indexOf(")");
      const inner = rest.slice(4, closeIndex);
      if (!selectorParts(inner).some((part) => compoundMatches(node, part))) return false;
      rest = rest.slice(closeIndex + 1);
      continue;
    }
    if (rest.startsWith(":not(")) {
      const closeIndex = rest.indexOf(")");
      const inner = rest.slice(5, closeIndex);
      if (selectorParts(inner).some((part) => compoundMatches(node, part))) return false;
      rest = rest.slice(closeIndex + 1);
      continue;
    }
    if (rest.startsWith("[")) {
      const closeIndex = rest.indexOf("]");
      if (!attributeCondition(node, rest.slice(1, closeIndex))) return false;
      rest = rest.slice(closeIndex + 1);
      continue;
    }
    if (rest.startsWith(".")) {
      let endIndex = rest.length;
      for (let index = 1; index < rest.length; index += 1) {
        const character = rest[index];
        if (character === "[" || character === ":" || character === ".") {
          endIndex = index;
          break;
        }
      }
      const classValue = node.attributes.get("class") ?? "";
      if (!classValue.split(/\s+/u).includes(rest.slice(1, endIndex))) return false;
      rest = rest.slice(endIndex);
      continue;
    }
    if (!baseChecked) {
      let endIndex = rest.length;
      for (let index = 0; index < rest.length; index += 1) {
        const character = rest[index];
        if (character === "[" || character === ":" || character === ".") {
          endIndex = index;
          break;
        }
      }
      baseTag = rest.slice(0, endIndex);
      rest = rest.slice(endIndex);
      baseChecked = true;
      continue;
    }
    return false;
  }
  if (baseTag === "" || baseTag === "*") return true;
  return node.tagName === baseTag.toUpperCase();
}

function selectorMatches(node: FakeNode, selector: string): boolean {
  return selectorParts(selector).some((part) => compoundMatches(node, part));
}

function descendantsOf(node: FakeNode): FakeNode[] {
  const collected: FakeNode[] = [];
  for (const child of node.children) {
    collected.push(child);
    collected.push(...descendantsOf(child));
  }
  return collected;
}

function collectMatches(root: FakeNode, selector: string, firstOnly: boolean): FakeNode[] {
  const matches: FakeNode[] = [];
  for (const part of selectorParts(selector)) {
    let scoped = false;
    let remainder = part;
    if (remainder.startsWith(":scope > ")) {
      scoped = true;
      remainder = remainder.slice(":scope > ".length);
    }
    const candidates = scoped
      ? root.children.filter((child) => child.nodeType === 1)
      : descendantsOf(root).filter((child) => child.nodeType === 1);
    for (const candidate of candidates) {
      if (!selectorMatches(candidate, remainder)) continue;
      if (!matches.includes(candidate)) matches.push(candidate);
      if (firstOnly) return matches;
    }
  }
  return matches;
}

function makeFakeElement(tagName: string, extras: Record<string, unknown> = {}) {
  const element = fakeOf({
    nodeType: 1,
    tagName: tagName.toUpperCase(),
    attributes: new Map(),
    children: [],
    parentNode: null,
    ownerDocument: null,
    style: makeFakeStyle(),
  });
  element.getAttribute = (name: string) => element.attributes.get(name) ?? null;
  element.setAttribute = (name: string, value: string) => {
    element.attributes.set(name, value);
    if (name === "style") element.style.parseCssText(value);
  };
  element.removeAttribute = (name: string) => {
    element.attributes.delete(name);
  };
  element.hasAttribute = (name: string) => element.attributes.has(name);
  element.appendChild = (child: FakeNode) => {
    element.children.push(child);
    child.parentNode = element;
    return child;
  };
  element.matches = (selector: string) => selectorMatches(element, selector);
  element.closest = (selector: string) => {
    let node: FakeNode | null = element;
    while (node) {
      if (node.nodeType === 1 && selectorMatches(node, selector)) return node;
      node = node.parentNode;
    }
    return null;
  };
  element.querySelectorAll = (selector: string) => collectMatches(element, selector, false);
  element.querySelector = (selector: string) => collectMatches(element, selector, true)[0] ?? null;
  element.getBoundingClientRect = () => fakeOf({ width: element.fakeWidth ?? 0 });
  Object.defineProperty(element, "textContent", {
    get() {
      return textOf(element);
    },
  });
  Object.assign(element, extras);
  return element;
}

function styleMutationRecord(target: FakeNode, oldValue: string | null) {
  return fakeOf({
    type: "attributes",
    attributeName: "style",
    attributeNamespace: null,
    target: target,
    oldValue: oldValue,
    addedNodes: null,
    removedNodes: null,
    previousSibling: null,
    nextSibling: null,
  }) as MutationRecord;
}

function makeViewDocument() {
  const document = makeFakeDocument();
  document.defaultView = fakeOf({
    getComputedStyle: (target: FakeNode) =>
      fakeOf({
        getPropertyValue: (property: string) =>
          property === "display" ? target.fakeDisplay ?? "block" : "",
      }),
  });
  return document;
}

test("lowered helpers import while globalThis.document stays absent", () => {
  assert.equal("document" in globalThis, false);
  assert.equal(typeof belongsToRootScope, "function");
  assert.equal(typeof rendererOwnedProgressiveStyleMutation, "function");
  assert.equal(typeof snapshotCompletionSelector, "function");
});

test("belongsToRootScope and rootScopedParagraphs follow the nearest root", () => {
  const root = makeFakeElement("div");
  root.setAttribute("data-tiqian-root", "true");
  const ownParagraph = makeFakeElement("p");
  root.appendChild(ownParagraph);
  const listItem = makeFakeElement("li");
  root.appendChild(listItem);
  const nestedRoot = makeFakeElement("div");
  nestedRoot.setAttribute("data-tiqian-root", "true");
  root.appendChild(nestedRoot);
  const nestedParagraph = makeFakeElement("p");
  nestedRoot.appendChild(nestedParagraph);

  assert.equal(belongsToRootScope(ownParagraph as Element, root as Element), true);
  assert.equal(belongsToRootScope(nestedParagraph as Element, root as Element), false);
  assert.equal(belongsToRootScope(nestedParagraph as Element, nestedRoot as Element), true);

  const scoped = rootScopedParagraphs(root as Element);
  assert.equal(scoped.length, 2);
  assert.equal(scoped[0], ownParagraph);
  assert.equal(scoped[1], listItem);
});

test("hasStrongEmphasis and hasHostInlineSizeParagraph query root descendants", () => {
  const root = makeFakeElement("div");
  assert.equal(hasStrongEmphasis(root as Element), false);
  assert.equal(hasHostInlineSizeParagraph(root as Element), false);
  const paragraph = makeFakeElement("p");
  root.appendChild(paragraph);
  const strong = makeFakeElement("strong");
  paragraph.appendChild(strong);
  assert.equal(hasStrongEmphasis(root as Element), true);
  paragraph.setAttribute("data-tq-host-inline-size", "true");
  assert.equal(hasHostInlineSizeParagraph(root as Element), true);
});

test("isPureBlockImageParagraph answers the Kotlin contract", () => {
  const document = makeViewDocument();
  const paragraph = makeFakeElement("p", { ownerDocument: document });
  const image = makeFakeElement("img", { ownerDocument: document, fakeDisplay: "block" });
  paragraph.appendChild(image);
  assert.equal(isPureBlockImageParagraph(paragraph as Element), true);

  const inlineImage = makeFakeElement("p", { ownerDocument: document });
  inlineImage.appendChild(makeFakeElement("img", { ownerDocument: document, fakeDisplay: "inline" }));
  assert.equal(isPureBlockImageParagraph(inlineImage as Element), false);

  const withText = makeFakeElement("p", { ownerDocument: document });
  withText.appendChild(makeFakeElement("img", { ownerDocument: document, fakeDisplay: "block" }));
  withText.appendChild(makeFakeText("图注"));
  assert.equal(isPureBlockImageParagraph(withText as Element), false);

  const itemParagraph = makeFakeElement("li", { ownerDocument: document });
  itemParagraph.appendChild(makeFakeElement("img", { ownerDocument: document, fakeDisplay: "block" }));
  assert.equal(isPureBlockImageParagraph(itemParagraph as Element), false);

  const emptyParagraph = makeFakeElement("p", { ownerDocument: document });
  assert.equal(isPureBlockImageParagraph(emptyParagraph as Element), false);

  // No owning view means an empty computed display, so the block check fails.
  const viewless = makeFakeElement("p");
  viewless.appendChild(makeFakeElement("img"));
  assert.equal(isPureBlockImageParagraph(viewless as Element), false);

  assert.equal(isPureBlockImageParagraph(null), false);
});

test("renderedRawDomParagraphs keeps document order and drops untracked paragraphs", () => {
  const root = makeFakeElement("tiqian-prose");
  const first = makeFakeElement("p");
  first.setAttribute("data-tq-rendered", "true");
  root.appendChild(first);
  const second = makeFakeElement("li");
  second.setAttribute("data-tq-rendered", "true");
  root.appendChild(second);
  const untracked = makeFakeElement("p");
  untracked.setAttribute("data-tq-rendered", "true");
  root.appendChild(untracked);
  const notRendered = makeFakeElement("p");
  root.appendChild(notRendered);

  const firstRecord = fakeOf({ fragment: null, engineWriteDepth: 0, forwarding: false });
  const secondRecord = fakeOf({ fragment: null, engineWriteDepth: 1, forwarding: true });
  getOrCreateEnhanceContext(first as Element).rawDomParagraphs.set(first as Element, firstRecord);
  getOrCreateEnhanceContext(second as Element).rawDomParagraphs.set(second as Element, secondRecord);

  const pairs = renderedRawDomParagraphs(root as Element);
  assert.equal(pairs.length, 2);
  assert.equal(pairs[0][0], first);
  assert.equal(pairs[0][1], firstRecord);
  assert.equal(pairs[1][0], second);
  assert.equal(pairs[1][1], secondRecord);
});

test("rendererOwnedProgressiveStyleMutation unwinds only renderer-owned deltas", () => {
  const document = makeFakeDocument();
  const root = makeFakeElement("div", { ownerDocument: document });
  root.setAttribute("data-tiqian-root", "true");
  const paragraph = makeFakeElement("p", { ownerDocument: document });
  paragraph.setAttribute("data-tq-rendered", "true");
  root.appendChild(paragraph);

  paragraph.setAttribute("style", "position: relative !important");
  assert.equal(
    rendererOwnedProgressiveStyleMutation(styleMutationRecord(paragraph, null), root as Element),
    true,
  );

  paragraph.setAttribute("style", "position: relative !important; color: red");
  assert.equal(
    rendererOwnedProgressiveStyleMutation(styleMutationRecord(paragraph, "color: red"), root as Element),
    true,
  );

  paragraph.setAttribute("style", "position: relative !important; color: blue");
  assert.equal(
    rendererOwnedProgressiveStyleMutation(styleMutationRecord(paragraph, "color: red"), root as Element),
    false,
  );

  const hostAttribute = fakeOf({
    type: "attributes",
    attributeName: "class",
    attributeNamespace: null,
    target: paragraph,
    oldValue: null,
    addedNodes: null,
    removedNodes: null,
    previousSibling: null,
    nextSibling: null,
  }) as MutationRecord;
  assert.equal(rendererOwnedProgressiveStyleMutation(hostAttribute, root as Element), false);

  paragraph.setAttribute("style", "position: relative !important");
  const foreignRoot = makeFakeElement("div", { ownerDocument: document });
  foreignRoot.setAttribute("data-tiqian-root", "true");
  assert.equal(
    rendererOwnedProgressiveStyleMutation(styleMutationRecord(paragraph, null), foreignRoot as Element),
    false,
  );

  const plainParagraph = makeFakeElement("p", { ownerDocument: document });
  root.appendChild(plainParagraph);
  plainParagraph.setAttribute("style", "position: relative !important");
  assert.equal(
    rendererOwnedProgressiveStyleMutation(styleMutationRecord(plainParagraph, null), root as Element),
    false,
  );

  const textTarget = fakeOf({
    type: "attributes",
    attributeName: "style",
    attributeNamespace: null,
    target: makeFakeText("文本"),
    oldValue: null,
    addedNodes: null,
    removedNodes: null,
    previousSibling: null,
    nextSibling: null,
  }) as MutationRecord;
  assert.equal(rendererOwnedProgressiveStyleMutation(textTarget, root as Element), false);
});

test("rendererOwnedProgressiveStyleMutation unwinds the captured inline size", () => {
  const document = makeFakeDocument();
  const root = makeFakeElement("div", { ownerDocument: document });
  root.setAttribute("data-tiqian-root", "true");
  const paragraph = makeFakeElement("p", { ownerDocument: document });
  paragraph.setAttribute("data-tq-rendered", "true");
  paragraph.setAttribute("data-tq-host-inline-size", "true");
  root.appendChild(paragraph);

  paragraph.setAttribute("style", "position: relative !important; inline-size: 512px !important");
  assert.equal(
    rendererOwnedProgressiveStyleMutation(
      styleMutationRecord(paragraph, "inline-size: 100px"),
      root as Element,
    ),
    true,
  );
  assert.equal(
    rendererOwnedProgressiveStyleMutation(
      styleMutationRecord(paragraph, "inline-size: 100px; color: red"),
      root as Element,
    ),
    false,
  );
});

test("snapshotCompletionSelector finds only root-scoped runtime candidates", () => {
  const document = makeViewDocument();
  const root = makeFakeElement("div");
  root.setAttribute("data-tiqian-root", "true");

  assert.equal(snapshotCompletionSelector(root as Element), "");

  const pending = makeFakeElement("p", { ownerDocument: document });
  pending.appendChild(makeFakeText("正文"));
  root.appendChild(pending);
  assert.equal(snapshotCompletionSelector(root as Element), ":is(p, li):not([data-tq-snapshot-key])");
  assert.equal(isRuntimeCompletionCandidate(pending as Element, root as Element), true);

  pending.setAttribute("data-tq-snapshot-key", "k1");
  assert.equal(snapshotCompletionSelector(root as Element), "");

  const skipped = makeFakeElement("p", { ownerDocument: document });
  skipped.appendChild(makeFakeText("代码"));
  const skippedHost = makeFakeElement("div", { ownerDocument: document });
  skippedHost.setAttribute("class", "not-prose");
  skippedHost.appendChild(skipped);
  root.appendChild(skippedHost);
  assert.equal(isRuntimeCompletionCandidate(skipped as Element, root as Element), false);
  assert.equal(snapshotCompletionSelector(root as Element), "");

  const imageParagraph = makeFakeElement("p", { ownerDocument: document });
  imageParagraph.appendChild(makeFakeElement("img", { ownerDocument: document, fakeDisplay: "block" }));
  root.appendChild(imageParagraph);
  assert.equal(isRuntimeCompletionCandidate(imageParagraph as Element, root as Element), false);

  const containerItem = makeFakeElement("li", { ownerDocument: document });
  containerItem.appendChild(makeFakeText("条目"));
  containerItem.appendChild(makeFakeElement("ul", { ownerDocument: document }));
  root.appendChild(containerItem);
  assert.equal(isRuntimeCompletionCandidate(containerItem as Element, root as Element), false);

  const nestedRoot = makeFakeElement("div", { ownerDocument: document });
  nestedRoot.setAttribute("data-tiqian-root", "true");
  root.appendChild(nestedRoot);
  const nestedPending = makeFakeElement("p", { ownerDocument: document });
  nestedPending.appendChild(makeFakeText("嵌套"));
  nestedRoot.appendChild(nestedPending);
  assert.equal(isRuntimeCompletionCandidate(nestedPending as Element, root as Element), false);

  const leafItem = makeFakeElement("li", { ownerDocument: document });
  leafItem.appendChild(makeFakeText("叶子"));
  root.appendChild(leafItem);
  assert.equal(isRuntimeCompletionCandidate(leafItem as Element, root as Element), true);
  assert.equal(snapshotCompletionSelector(root as Element), ":is(p, li):not([data-tq-snapshot-key])");
});

test("snapshotFontAttemptSignature encodes reference, font size and grid measure", () => {
  const root = makeFakeElement("div");
  assert.equal(snapshotFontAttemptSignature(root as Element, null), "");

  const emptyRoot = makeFakeElement("div");
  assert.equal(snapshotFontAttemptSignature(emptyRoot as Element, "ref-1"), "ref-1\u0000missing");

  const previousGetComputedStyle = globalThis.getComputedStyle;
  globalThis.getComputedStyle = () => fakeOf({ fontSize: "16px" });
  try {
    const paragraph = makeFakeElement("p", { fakeWidth: 512 });
    root.appendChild(paragraph);
    const expectedMeasure = lineLengthGridMeasure(512, 16);
    assert.equal(
      snapshotFontAttemptSignature(root as Element, "ref-2"),
      `ref-2\u0000${Math.fround(16)}\u0000${expectedMeasure}`,
    );
  } finally {
    globalThis.getComputedStyle = previousGetComputedStyle;
  }
});

test("document-dependent entries degrade without any document", async () => {
  assert.equal(await ensureTiqianStyles(null), null);
  assert.deepEqual(createTiqianClipboardPayload(null, null), { text: "", html: "" });
});
