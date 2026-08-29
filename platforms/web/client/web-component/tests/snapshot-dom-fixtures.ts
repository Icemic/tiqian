// Shared fake-DOM fixtures for snapshot adoption tests: a minimal selector-
// matching node tree, the computed-style fixture, and the canonical node form
// used by manifest artifact digests. Pure move from precomputed.test.mjs so
// timing-golden journeys can drive the same adoption transport.

import { createHash } from "node:crypto";

type FixtureProbeMeasureFn = (cssText: string) => void;
type FontCheckFn = (descriptor: unknown, text: string) => boolean;
type FakeFontLoadFn = (descriptor: unknown, text: string) => Promise<unknown[]>;
type FakeFontEventListenerFn = () => void;

interface FakeClientRect {
  width: number;
  left: number;
  right: number;
  top: number;
  bottom: number;
  height: number;
}

interface FakeRangeRect {
  width: number;
}

interface FakeRange {
  selectNodeContents(node: FakeNode): void;
  getBoundingClientRect(): FakeRangeRect;
}

interface FakeDocumentFonts {
  load: FakeFontLoadFn;
  check: FontCheckFn;
  addEventListener: FakeFontEventListenerFn;
  removeEventListener: FakeFontEventListenerFn;
  ready?: Promise<void>;
}

interface FakeStyleDeclaration {
  getPropertyValue(name: string): string;
}

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
  nodeType: number;
  childNodes: FakeNode[];
  parentNode: FakeNode | null;
  parentElement: FakeElement | null;

  constructor(nodeType: number) {
    this.nodeType = nodeType;
    this.childNodes = [];
    this.parentNode = null;
    this.parentElement = null;
  }

  get firstChild(): FakeNode | null {
    return this.childNodes[0] ?? null;
  }

  _isConnected?: boolean;

  get isConnected(): boolean {
    let curr: FakeNode | null = this;
    const doc = (globalThis as Record<string, unknown>).document as FakeNode | undefined;
    while (curr) {
      if (curr._isConnected !== undefined) return curr._isConnected;
      if (curr.nodeType === 9 || (doc && curr === doc)) return true;
      curr = curr.parentNode;
    }
    return false;
  }

  set isConnected(value: boolean) {
    this._isConnected = value;
  }

  get nextSibling(): FakeNode | null {
    const parent = this.parentNode;
    if (!parent) return null;
    const index = parent.childNodes.indexOf(this);
    return parent.childNodes[index + 1] ?? null;
  }

  append(...nodes: FakeNode[]): void {
    for (const node of nodes) this.appendChild(node);
  }

  appendChild(node: FakeNode): FakeNode {
    if (node.nodeType === 11) {
      // The browser's native fragment append is atomic and bypasses any
      // wrapper installed on the element (e.g. the raw-dom commit
      // forwarding), so expand through the prototype method directly.
      while (node.firstChild) {
        FakeNode.prototype.appendChild.call(this, node.firstChild);
      }
      return node;
    }
    if (node.parentNode) node.parentNode.removeChild(node);
    this.childNodes.push(node);
    node.parentNode = this;
    node.parentElement = this.nodeType === 1 ? asElementParent(this) : null;
    return node;
  }

  insertBefore(node: FakeNode, reference: FakeNode | null): FakeNode {
    if (reference == null) return this.appendChild(node);
    if (node.nodeType === 11) {
      while (node.firstChild) {
        FakeNode.prototype.insertBefore.call(this, node.firstChild, reference);
      }
      return node;
    }
    const index = this.childNodes.indexOf(reference);
    if (index < 0) throw new Error("NotFound");
    if (node.parentNode) node.parentNode.removeChild(node);
    this.childNodes.splice(index, 0, node);
    node.parentNode = this;
    node.parentElement = this.nodeType === 1 ? asElementParent(this) : null;
    return node;
  }

  replaceChild(next: FakeNode, previous: FakeNode): FakeNode {
    const index = this.childNodes.indexOf(previous);
    if (index < 0) throw new Error("NotFound");
    if (next.nodeType === 11) {
      this.removeChild(previous);
      FakeNode.prototype.insertBefore.call(this, next, this.childNodes[index] ?? null);
      return previous;
    }
    if (next.parentNode) next.parentNode.removeChild(next);
    this.childNodes[index] = next;
    previous.parentNode = null;
    previous.parentElement = null;
    next.parentNode = this;
    next.parentElement = this.nodeType === 1 ? asElementParent(this) : null;
    return previous;
  }

  removeChild(node: FakeNode): FakeNode {
    const index = this.childNodes.indexOf(node);
    if (index < 0) throw new Error("NotAChild");
    this.childNodes.splice(index, 1);
    node.parentNode = null;
    node.parentElement = null;
    return node;
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
}

// Merged into the FakeNode class type: subclasses provide the real
// implementations; the merged signature types the recursive calls in their
// deep-clone loops against the shared base type.
interface FakeNode {
  cloneNode(deep?: boolean): FakeNode;
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

  cloneNode(): FakeText {
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

// Minimal inline-style declaration mirroring the CSSStyleDeclaration surface
// the engine uses: getPropertyValue/getPropertyPriority/setProperty/
// removeProperty, with cssText derived from the stored properties so probe
// checks that sniff the serialized style keep working.
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

export interface FakeElementClassList {
  contains(name: string): boolean;
  add(...cls: string[]): void;
  remove(...cls: string[]): void;
}

class FakeElement extends FakeNode {
  tagName: string;
  attributes: Map<string, string>;
  style: FakeInlineStyle;
  ownerDocument: FakeDocument | null;
  width: number;
  height: number;
  left: number;
  top: number;
  // dataset and innerHTML stay accessor pairs so the runtime host's
  // HostElement subclass can override them with its own accessors. In the
  // bare fixture world dataset is a plain per-instance record and innerHTML
  // reads undefined, exactly as the pre-conversion fixtures behaved.
  protected declare _datasetRecord: Record<string, string | undefined> | undefined;
  protected declare _innerHTMLValue: string | undefined;
  _innerText: string | null = null;
  _fixtureProbeWidth?: number;
  _onFixtureProbeMeasure?: FixtureProbeMeasureFn;

  constructor(tagName: string) {
    super(1);
    this.tagName = tagName.toUpperCase();
    this.attributes = new Map();
    this.dataset = {};
    this.style = new FakeInlineStyle();
    this.ownerDocument = null;
    this.width = 0;
    this.height = 0;
    this.left = 0;
    this.top = 0;
  }

  get children(): FakeElement[] {
    return this.childNodes.filter((n): n is FakeElement => n.nodeType === 1);
  }

  get classList(): FakeElementClassList {
    const getClasses = () => (this.getAttribute("class") || "").trim().split(/\s+/).filter(Boolean);
    return {
      contains: (cls: string) => getClasses().includes(cls),
      add: (...cls: string[]) => {
        const set = new Set(getClasses());
        for (const c of cls) set.add(c);
        this.setAttribute("class", Array.from(set).join(" "));
      },
      remove: (...cls: string[]) => {
        const set = new Set(getClasses());
        for (const c of cls) set.delete(c);
        this.setAttribute("class", Array.from(set).join(" "));
      },
    };
  }

  get outerHTML(): string {
    return this.innerHTML ?? "";
  }

  get clientWidth(): number {
    return this.width;
  }

  set clientWidth(value: number) {
    this.width = Number(value);
  }

  get clientHeight(): number {
    return this.height;
  }

  set clientHeight(value: number) {
    this.height = Number(value);
  }

  get scrollWidth(): number {
    return this.width;
  }

  set scrollWidth(value: number) {
    this.width = Number(value);
  }

  get scrollHeight(): number {
    return this.height;
  }

  set scrollHeight(value: number) {
    this.height = Number(value);
  }

  get dataset(): Record<string, string | undefined> {
    this._datasetRecord ??= {};
    return this._datasetRecord;
  }

  set dataset(value: Record<string, string | undefined>) {
    this._datasetRecord = value;
  }

  get innerHTML(): string | undefined {
    return this._innerHTMLValue;
  }

  set innerHTML(value: string | undefined) {
    this._innerHTMLValue = value;
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

  getBoundingClientRect(): FakeClientRect {
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

  getClientRects(): FakeClientRect[] {
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
    clone.dataset = { ...this.dataset };
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
  fonts: FakeDocumentFonts;
  createDocumentFragment(): FakeFragment;
  createElement(tagName: string): FakeElement;
  createElementNS(ns: string | null, tagName: string): FakeElement;
  createTextNode(data: string): FakeText;
  getSelection(): unknown;
  contains(node: FakeNode): boolean;
  createRange(): FakeRange;
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

function styleDeclaration(values: Record<string, string>): FakeStyleDeclaration {
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
  overrides: Record<string, string> = {},
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
  FakeEvent,
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
