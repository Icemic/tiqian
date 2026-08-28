// Shared fake-DOM fixtures for snapshot adoption tests: a minimal selector-
// matching node tree, the computed-style fixture, and the canonical node form
// used by manifest artifact digests. Pure move from precomputed.test.mjs so
// timing-golden journeys can drive the same adoption transport.

import { createHash } from "node:crypto";

function matchesSelector(element: FakeElement, selector: string): boolean {
  if (selector === "*") return element.nodeType === 1;
  if (selector === ":is(p, li)[data-tq-snapshot-key]") {
    return ["P", "LI"].includes(element.tagName) && element.hasAttribute("data-tq-snapshot-key");
  }
  if (selector === "tiqian-prose, [data-tiqian-root]") {
    return element.tagName === "TIQIAN-PROSE" || element.hasAttribute("data-tiqian-root");
  }
  const tagMatch = /^([a-z0-9-]+)?(?:\[([^=\]]+)(?:="([^"]*)")?\])?$/iu.exec(selector);
  if (!tagMatch) return false;
  if (tagMatch[1] && element.tagName !== tagMatch[1].toUpperCase()) return false;
  if (!tagMatch[2]) return true;
  if (!element.hasAttribute(tagMatch[2])) return false;
  return tagMatch[3] == null || element.getAttribute(tagMatch[2]) === tagMatch[3];
}

class FakeNode {
  static TEXT_NODE = 3;
  static ELEMENT_NODE = 1;
  static DOCUMENT_FRAGMENT_NODE = 11;

  nodeType: number;
  childNodes: FakeNode[];
  parentNode: FakeNode | null;
  parentElement: FakeElement | null;
  ownerDocument: FakeDocument | null = null;

  constructor(nodeType: number) {
    this.nodeType = nodeType;
    this.childNodes = [];
    this.parentNode = null;
    this.parentElement = null;
  }

  get firstChild(): FakeNode | null {
    return this.childNodes[0] ?? null;
  }

  get nextSibling(): FakeNode | null {
    const parent = this.parentNode;
    if (!parent) return null;
    const siblings = parent.childNodes;
    return siblings[siblings.indexOf(this) + 1] ?? null;
  }

  get previousSibling(): FakeNode | null {
    const parent = this.parentNode;
    if (!parent) return null;
    const siblings = parent.childNodes;
    const index = siblings.indexOf(this);
    if (index <= 0) return null;
    return siblings[index - 1];
  }

  contains(other: FakeNode | null): boolean {
    for (let node: FakeNode | null = other; node; node = node.parentNode) {
      if (node === this) return true;
    }
    return false;
  }

  matches(_selector: string): boolean {
    return false;
  }

  append(...nodes: FakeNode[]): void {
    for (const node of nodes) this.appendChild(node);
  }

  appendChild(node: FakeNode): FakeNode {
    if (node.nodeType === 11) {
      // Expand through the prototype method, never through this.appendChild:
      // the browser's native fragment append is atomic and bypasses any
      // wrapper installed on the element.
      while (node.firstChild) FakeNode.prototype.appendChild.call(this, node.firstChild);
      return node;
    }
    if (node.parentNode) node.parentNode.removeChild(node);
    this.childNodes.push(node);
    node.parentNode = this;
    node.parentElement = this.nodeType === 1 ? asElementParent(this) : null;
    return node;
  }

  removeChild(node: FakeNode): FakeNode {
    const index = this.childNodes.indexOf(node);
    if (index < 0) throw new Error("NotAChild");
    this.childNodes.splice(index, 1);
    node.parentNode = null;
    node.parentElement = null;
    return node;
  }

  replaceWith(node: FakeNode): void {
    const parent = this.parentNode;
    if (!parent) return;
    const following = this.nextSibling;
    parent.removeChild(this);
    if (following) parent.insertBefore(node, following);
    else parent.appendChild(node);
  }

  insertBefore(node: FakeNode, reference: FakeNode | null): FakeNode {
    if (node.nodeType === 11) {
      while (node.firstChild) FakeNode.prototype.insertBefore.call(this, node.firstChild, reference);
      return node;
    }
    const index = reference == null ? this.childNodes.length : this.childNodes.indexOf(reference);
    if (reference != null && index < 0) throw new Error("NotAChild");
    if (node.parentNode) node.parentNode.removeChild(node);
    this.childNodes.splice(index, 0, node);
    node.parentNode = this;
    node.parentElement = this.nodeType === 1 ? asElementParent(this) : null;
    return node;
  }

  replaceChild(next: FakeNode, prev: FakeNode): FakeNode {
    const index = this.childNodes.indexOf(prev);
    if (index < 0) throw new Error("NotAChild");
    if (next.nodeType === 11) {
      let child = next.firstChild;
      while (child) {
        const following = child.nextSibling;
        FakeNode.prototype.insertBefore.call(this, child, prev);
        child = following;
      }
      return this.removeChild(prev);
    }
    if (next.parentNode) next.parentNode.removeChild(next);
    this.childNodes[index] = next;
    prev.parentNode = null;
    prev.parentElement = null;
    next.parentNode = this;
    next.parentElement = this.nodeType === 1 ? asElementParent(this) : null;
    return prev;
  }

  remove(): void {
    this.parentNode?.removeChild(this);
  }

  get textContent(): string {
    return this.childNodes.map((node) => node.textContent).join("");
  }

  set textContent(value: string) {
    while (this.firstChild) this.removeChild(this.firstChild);
    if (value) this.appendChild(new FakeText(String(value)));
  }

  querySelectorAll(selector: string): FakeElement[] {
    const result: FakeElement[] = [];
    const visit = (node: FakeNode): void => {
      for (const child of node.childNodes) {
        if (child.nodeType === 1 && matchesSelector(child as FakeElement, selector)) result.push(child as FakeElement);
        visit(child);
      }
    };
    visit(this);
    return result;
  }

  querySelector(selector: string): FakeElement | null {
    return this.querySelectorAll(selector)[0] ?? null;
  }

  cloneNode(deep?: boolean): FakeNode {
    const clone = new FakeNode(this.nodeType);
    if (deep) {
      for (const child of this.childNodes) clone.appendChild(child.cloneNode(true));
    }
    return clone;
  }
}

class FakeText extends FakeNode {
  value: string;

  constructor(value: string) {
    super(3);
    this.value = value;
  }

  get textContent(): string {
    return this.value;
  }

  set textContent(value: string) {
    this.value = String(value);
  }

  cloneNode(deep?: boolean): FakeText {
    return new FakeText(this.value);
  }
}

function computeNormalInnerText(root: FakeElement): string {
  let result = "";
  let atLineStart = true;
  let pendingSpace = false;

  function visit(node: FakeNode): void {
    if (node.nodeType === 3) {
      const text = node.textContent ?? "";
      const tokens = text.split(/(\s+)/);
      for (const token of tokens) {
        if (!token) continue;
        if (/^\s+$/.test(token)) {
          if (!atLineStart) {
            pendingSpace = true;
          }
        } else {
          if (pendingSpace && !atLineStart && !result.endsWith("\n")) {
            result += " ";
          }
          pendingSpace = false;
          atLineStart = false;
          result += token;
        }
      }
    } else if (node.nodeType === 1) {
      const element = node as FakeElement;
      if (element.tagName === "BR") {
        result += "\n";
        atLineStart = true;
        pendingSpace = false;
      } else if (element._innerText != null) {
        const tokens = element._innerText.split(/(\s+)/);
        for (const token of tokens) {
          if (!token) continue;
          if (/^\s+$/.test(token)) {
            if (!atLineStart) pendingSpace = true;
          } else {
            if (pendingSpace && !atLineStart && !result.endsWith("\n")) {
              result += " ";
            }
            pendingSpace = false;
            atLineStart = false;
            result += token;
          }
        }
      } else {
        for (const child of element.childNodes) {
          visit(child);
        }
      }
    }
  }

  for (const child of root.childNodes) {
    visit(child);
  }
  return result;
}

const HTML_ENTITY_NAMES: Record<string, string> = {
  amp: "&",
  lt: "<",
  gt: ">",
  quot: "\"",
  apos: "'",
  nbsp: "\u00a0",
};

function decodeHtmlEntities(text: string): string {
  return text.replace(/&(#x[0-9a-f]+|#[0-9]+|[a-z]+);/gi, (_match, body) => {
    if (body[0] === "#") {
      const code = body[1] === "x" || body[1] === "X"
        ? parseInt(body.slice(2), 16)
        : parseInt(body.slice(1), 10);
      return Number.isFinite(code) ? String.fromCodePoint(code) : _match;
    }
    return HTML_ENTITY_NAMES[body.toLowerCase()] ?? _match;
  });
}

function parseHtmlAttributes(source: string, element: FakeElement): void {
  let index = 0;
  while (index < source.length) {
    while (index < source.length && /\s/.test(source[index])) index += 1;
    if (index >= source.length) break;
    let nameEnd = index;
    while (nameEnd < source.length && !/[\s=]/.test(source[nameEnd])) nameEnd += 1;
    const name = source.slice(index, nameEnd);
    index = nameEnd;
    while (index < source.length && /\s/.test(source[index])) index += 1;
    if (source[index] !== "=") {
      if (name) element.setAttribute(name.toLowerCase(), "");
      continue;
    }
    index += 1;
    while (index < source.length && /\s/.test(source[index])) index += 1;
    let value = "";
    const quote = source[index];
    if (quote === "\"" || quote === "'") {
      const end = source.indexOf(quote, index + 1);
      value = source.slice(index + 1, end < 0 ? source.length : end);
      index = end < 0 ? source.length : end + 1;
    } else {
      let end = index;
      while (end < source.length && !/\s/.test(source[end])) end += 1;
      value = source.slice(index, end);
      index = end;
    }
    if (name) element.setAttribute(name.toLowerCase(), decodeHtmlEntities(value));
  }
}

export const HTML_VOID_TAGS = ["BR", "IMG", "HR", "INPUT", "WBR"];

function parseHtmlFragment(html: string): FakeFragment {
  const root = new FakeFragment();
  const stack: (FakeFragment | FakeElement)[] = [root];
  const source = String(html);
  let index = 0;
  const appendText = (value: string): void => {
    if (value) FakeNode.prototype.appendChild.call(stack[stack.length - 1], new FakeText(value));
  };
  while (index < source.length) {
    const open = source.indexOf("<", index);
    if (open < 0) {
      appendText(decodeHtmlEntities(source.slice(index)));
      break;
    }
    if (open > index) appendText(decodeHtmlEntities(source.slice(index, open)));
    const close = source.indexOf(">", open);
    if (close < 0) {
      appendText(decodeHtmlEntities(source.slice(open)));
      break;
    }
    const raw = source.slice(open + 1, close);
    index = close + 1;
    if (raw.startsWith("!--")) continue;
    if (raw.startsWith("/")) {
      const tagName = raw.slice(1).trim().toUpperCase();
      for (let depth = stack.length - 1; depth > 0; depth -= 1) {
        if ((stack[depth] as FakeElement).tagName === tagName) {
          stack.length = depth;
          break;
        }
      }
      continue;
    }
    const selfClosing = raw.endsWith("/");
    const body = selfClosing ? raw.slice(0, -1).trim() : raw;
    const nameMatch = /^([a-zA-Z][a-zA-Z0-9-]*)/.exec(body);
    if (!nameMatch) continue;
    const element = new FakeElement(nameMatch[1]);
    parseHtmlAttributes(body.slice(nameMatch[0].length), element);
    FakeNode.prototype.appendChild.call(stack[stack.length - 1], element);
    if (!selfClosing && !HTML_VOID_TAGS.includes(element.tagName)) stack.push(element);
  }
  return root;
}

class FakeInlineStyle {
  readonly _values: Map<string, string>;
  readonly _priorities: Map<string, string>;

  constructor() {
    this._values = new Map();
    this._priorities = new Map();
  }

  getPropertyValue(name: string): string {
    return this._values.get(name) ?? "";
  }

  getPropertyPriority(name: string): string {
    return this._priorities.get(name) ?? "";
  }

  setProperty(name: string, value: string | null, priority: string = ""): void {
    if (value == null || String(value) === "") {
      this.removeProperty(name);
      return;
    }
    this._values.set(name, String(value));
    if (priority === "important") this._priorities.set(name, "important");
    else this._priorities.delete(name);
  }

  removeProperty(name: string): string {
    const value = this._values.get(name) ?? "";
    this._values.delete(name);
    this._priorities.delete(name);
    return value;
  }

  get cssText(): string {
    const parts: string[] = [];
    for (const [name, value] of this._values) {
      const priority = this._priorities.get(name);
      parts.push(priority ? `${name}:${value}!${priority}` : `${name}:${value}`);
    }
    return parts.join(";");
  }

  set cssText(value: string) {
    this._values.clear();
    this._priorities.clear();
    for (const declaration of String(value).split(";")) {
      const colon = declaration.indexOf(":");
      if (colon < 0) continue;
      const name = declaration.slice(0, colon).trim();
      let declarationValue = declaration.slice(colon + 1).trim();
      if (!name) continue;
      let priority = "";
      if (/!important$/i.test(declarationValue)) {
        priority = "important";
        declarationValue = declarationValue.slice(0, -10).trim();
      }
      this._values.set(name, declarationValue);
      if (priority) this._priorities.set(name, priority);
    }
  }
}

class FakeElement extends FakeNode {
  tagName: string;
  attributes: Map<string, string>;
  _dataset: Record<string, string | undefined>;
  get dataset(): Record<string, string | undefined> { return this._dataset; }
  set dataset(value: Record<string, string | undefined>) { this._dataset = value; }
  style: FakeInlineStyle;
  ownerDocument: FakeDocument | null;
  width: number;
  height: number;
  left: number;
  top: number;

  _innerText: string | null = null;
  _fixtureProbeWidth?: number;
  _onFixtureProbeMeasure?: (cssText: string) => void;

  constructor(tagName: string) {
    super(1);
    this.tagName = tagName.toUpperCase();
    this.attributes = new Map();
    this._dataset = {};
    this.style = new FakeInlineStyle();
    this.ownerDocument = null;
    this.width = 0;
    this.height = 0;
    this.left = 0;
    this.top = 0;
  }

  setAttribute(name: string, value: string): void {
    this.attributes.set(name, String(value));
  }

  getAttribute(name: string): string | null {
    return this.attributes.get(name) ?? null;
  }

  hasAttribute(name: string): boolean {
    return this.attributes.has(name);
  }

  removeAttribute(name: string): void {
    this.attributes.delete(name);
  }

  get innerText(): string {
    if (this._innerText != null) return this._innerText;
    return computeNormalInnerText(this);
  }

  set innerText(value: string) {
    this._innerText = String(value);
  }

  get innerHTML(): string {
    return this.textContent;
  }

  set innerHTML(value: string) {
    this._innerText = null;
    // The browser's innerHTML parser bypasses per-element mutation wrappers,
    // so install the replacement tree through the prototype method.
    while (this.firstChild) FakeNode.prototype.removeChild.call(this, this.firstChild);
    if (value) FakeNode.prototype.appendChild.call(this, parseHtmlFragment(value));
  }

  getBoundingClientRect(): {
    width: number;
    left: number;
    right: number;
    top: number;
    bottom: number;
    height: number;
  } {
    if (this._fixtureProbeWidth != null && this.style.cssText.includes("position:absolute!important")) {
      this._onFixtureProbeMeasure?.(this.style.cssText);
      return {
        width: this._fixtureProbeWidth,
        left: 0,
        right: this._fixtureProbeWidth,
        top: 0,
        bottom: 0,
        height: 0,
      };
    }
    return {
      width: this.width,
      left: this.left,
      right: this.left + this.width,
      top: this.top,
      bottom: this.top + this.height,
      height: this.height,
    };
  }

  getClientRects(): Array<{
    width: number;
    left: number;
    right: number;
    top: number;
    bottom: number;
    height: number;
  }> {
    return [this.getBoundingClientRect()];
  }

  closest(selector: string): FakeElement | null {
    for (let node: FakeElement | null = this; node; node = node.parentElement) {
      if (node.nodeType === 1 && matchesSelector(node, selector)) return node;
    }
    return null;
  }

  cloneNode(deep: boolean = false): FakeElement {
    const clone = new FakeElement(this.tagName);
    clone.ownerDocument = this.ownerDocument;
    clone.width = this.width;
    clone.height = this.height;
    clone.left = this.left;
    clone.top = this.top;
    clone._innerText = this._innerText;
    clone.attributes = new Map(this.attributes);
    clone._dataset = { ...this._dataset };
    clone.style.cssText = this.style.cssText;
    if (deep) for (const child of this.childNodes) clone.appendChild(child.cloneNode(true));
    return clone;
  }
}

class FakeFragment extends FakeNode {
  constructor() {
    super(11);
  }

  cloneNode(deep: boolean = false): FakeFragment {
    const clone = new FakeFragment();
    if (deep) for (const child of this.childNodes) clone.appendChild(child.cloneNode(true));
    return clone;
  }
}

// Polymorphic `this` cannot be asserted straight onto the FakeElement
// subclass; passing it through a FakeNode parameter restores the plain
// base-to-derived downcast.
function asElementParent(node: FakeNode): FakeElement {
  return node as FakeElement;
}

interface FakeDocument {
  nodeType: number;
  childNodes: FakeNode[];
  parentNode: FakeNode | null;
  parentElement: FakeElement | null;
  ownerDocument: FakeDocument | null;
  isConnected: boolean;
  listeners: Map<string, Set<unknown>>;
  defaultView: unknown;
  baseURI: string;
  elements: Map<string, unknown>;
  styleSheets: unknown[];
  fonts: {
    load: (descriptor: unknown, text: string) => Promise<unknown[]>;
    check: (descriptor: unknown, text: string) => boolean;
    addEventListener: () => void;
    removeEventListener: () => void;
  };
  createDocumentFragment(): FakeFragment;
  createElement(tagName: string): FakeElement;
  createElementNS(ns: string | null, tagName: string): FakeElement;
  createTextNode(data: string): FakeText;
  getSelection(): unknown;
  contains(node: FakeNode): boolean;
  createRange(): {
    selectNodeContents(node: FakeNode): void;
    getBoundingClientRect(): { width: number };
  };
  getElementById(id: string): FakeElement | null;
  querySelector(selector: string): FakeElement | null;
  querySelectorAll(selector: string): FakeElement[];
  addEventListener(name: string, listener: unknown): void;
  removeEventListener(name: string, listener: unknown): void;
  dispatchEvent(event: FakeEvent): boolean;
  body: FakeElement;
  head: FakeElement;
  documentElement: FakeElement;
}

interface FakeEvent {
  readonly type: string;
  readonly bubbles: boolean;
  readonly cancelable: boolean;
  readonly defaultPrevented: boolean;
  readonly detail: unknown;
  readonly target: FakeNode | null;
  readonly currentTarget: FakeNode | null;
  preventDefault(): void;
}

function styleDeclaration(values: Record<string, string>): { getPropertyValue(name: string): string } {
  return {
    getPropertyValue(name: string): string {
      return values[name] ?? "";
    },
  };
}

function sha256(value: string | Uint8Array): string {
  return createHash("sha256").update(value).digest("hex");
}

function canonicalFixtureNode(node: FakeNode): unknown {
  if (node.nodeType === 3) return ["#", node.textContent];
  const element = node as FakeElement;
  return [
    element.tagName.toLocaleLowerCase(),
    Array.from(element.attributes, ([name, value]) => [name, value])
      .sort(([left], [right]) => (left < right ? -1 : left > right ? 1 : 0)),
    element.childNodes.map(canonicalFixtureNode),
  ];
}

function fixtureComputedStyle(
  element: FakeElement | null | undefined,
  _pseudo: string | null,
  overrides: Record<string, string> = {}
): Record<string, string> {
  const boundary = element?.hasAttribute?.("data-tq-shaping-boundary") === true;
  const engineHyphen = element?.hasAttribute?.("data-tq-engine-hyphen") === true;
  const measuredGeometry = !boundary && element?.hasAttribute?.("data-tq-advance") === true;
  const proportionalQuote = element?.getAttribute?.("data-tq-open-type-features") === "pwid,palt";
  const canonicalPreparedFlow = element?.closest?.("[data-tq-canonical-plain]") != null ||
    element?.closest?.("[data-tq-canonical-source]") != null;
  return {
    display: boundary
      ? "inline"
      : engineHyphen
        ? "inline-block"
        : measuredGeometry
          ? "inline"
          : element?.tagName === "LI" ? "list-item" : "block",
    whiteSpace: boundary || engineHyphen ? "pre" : "normal",
    verticalAlign: "baseline",
    direction: "ltr",
    unicodeBidi: boundary || engineHyphen ? "isolate" : "normal",
    fontFamily: "\"Fixture CJK\"",
    fontSize: "18px",
    lineHeight: canonicalPreparedFlow ? "0px" : "27px",
    fontWeight: "400",
    fontStyle: "normal",
    letterSpacing: "normal",
    wordSpacing: "normal",
    fontFeatureSettings: proportionalQuote
      ? '"halt" 0, "chws" 0, "palt" 1'
      : canonicalPreparedFlow ? '"halt" 0, "chws" 0, "palt" 0' : "normal",
    fontVariationSettings: "normal",
    fontStretch: "100%",
    fontKerning: "normal",
    fontOpticalSizing: "none",
    fontVariantLigatures: "normal",
    fontVariantAlternates: "normal",
    fontVariantEastAsian: proportionalQuote ? "proportional-width" : "normal",
    fontVariantCaps: "normal",
    fontVariantNumeric: "normal",
    fontVariantPosition: "normal",
    fontLanguageOverride: "normal",
    fontSizeAdjust: "none",
    textTransform: "none",
    textRendering: "auto",
    textAlign: "start",
    textAlignLast: "auto",
    textJustify: "auto",
    writingMode: "horizontal-tb",
    whiteSpaceCollapse: "preserve",
    textWrapMode: "nowrap",
    overflowWrap: "normal",
    wordBreak: "normal",
    hyphens: "manual",
    textAutospace: "no-autospace",
    marginLeft: "0px",
    marginRight: "0px",
    transform: "none",
    scale: "none",
    content: "none",
    cssFloat: "none",
    boxDecorationBreak: "slice",
    ...overrides,
  };
}

// Identity boundary casts between the fake-DOM fixtures and the DOM lib
// types. Runtime no-ops, expressed as single assertions onto intersections
// so tests never need a double assertion to cross the fake/DOM seam.
type FakeGetComputedStyleFn = (
  element: FakeElement | null,
  pseudo?: string | null,
  overrides?: Record<string, string>,
) => Record<string, string>;

type HostGetComputedStyleFn = (
  element: Element | null,
  pseudoElement?: string | null,
) => CSSStyleDeclaration;

function asElement(fake: FakeElement): Element {
  return fake as FakeElement & Element;
}

function asHTMLElement(fake: FakeElement): HTMLElement {
  return fake as FakeElement & HTMLElement;
}

function asNode(fake: FakeNode): Node {
  return fake as FakeNode & Node;
}

function asFakeElement(element: Element): FakeElement {
  return element as Element & FakeElement;
}

function asFakeNode(node: Node): FakeNode {
  return node as Node & FakeNode;
}

function asNodeConstructor(constructor: typeof FakeNode): typeof Node {
  return constructor as typeof FakeNode & typeof Node;
}

function emptyDomRectList(): DOMRectList {
  const empty: DOMRect[] = [];
  return empty as DOMRectList & DOMRect[];
}

function asGetComputedStyle(fn: FakeGetComputedStyleFn): HostGetComputedStyleFn {
  return fn as FakeGetComputedStyleFn & HostGetComputedStyleFn;
}

function asDocument(doc: FakeDocument): Document {
  return doc as FakeDocument & Document;
}

export {
  FakeDocument,
  FakeElement,
  FakeFragment,
  FakeInlineStyle,
  FakeNode,
  FakeText,
  asDocument,
  asElement,
  asFakeElement,
  asFakeNode,
  asGetComputedStyle,
  asHTMLElement,
  asNode,
  asNodeConstructor,
  canonicalFixtureNode,
  emptyDomRectList,
  fixtureComputedStyle,
  matchesSelector,
  sha256,
  styleDeclaration,
};