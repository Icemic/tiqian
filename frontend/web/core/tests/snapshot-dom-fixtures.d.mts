// Type declarations for snapshot-dom-fixtures.mjs

export class FakeNode {
  nodeType: number;
  childNodes: FakeNode[];
  parentNode: FakeNode | null;
  parentElement: FakeElement | null;

  readonly firstChild: FakeNode | null;
  readonly nextSibling: FakeNode | null;
  textContent: string;

  append(...nodes: FakeNode[]): void;
  appendChild(node: FakeNode): FakeNode;
  removeChild(node: FakeNode): FakeNode;
  replaceWith(node: FakeNode): void;
  insertBefore(node: FakeNode, reference: FakeNode | null): FakeNode;
  replaceChild(next: FakeNode, prev: FakeNode): FakeNode;
  remove(): void;
  querySelectorAll(selector: string): FakeElement[];
  querySelector(selector: string): FakeElement | null;
  cloneNode(deep?: boolean): FakeNode;
}

export class FakeElement extends FakeNode {
  tagName: string;
  attributes: Map<string, string>;
  dataset: Record<string, string>;
  style: FakeInlineStyle;
  ownerDocument: any;
  width: number;
  height: number;
  left: number;
  top: number;
  content: FakeNode | null;

  _innerText: string | null;
  _fixtureProbeWidth?: number;
  _onFixtureProbeMeasure?: (cssText: string) => void;

  innerText: string;
  innerHTML: string;

  constructor(tagName: string);

  setAttribute(name: string, value: string): void;
  getAttribute(name: string): string | null;
  hasAttribute(name: string): boolean;
  removeAttribute(name: string): void;
  getBoundingClientRect(): {
    width: number;
    left: number;
    right: number;
    top: number;
    bottom: number;
    height: number;
  };
  getClientRects(): Array<{
    width: number;
    left: number;
    right: number;
    top: number;
    bottom: number;
    height: number;
  }>;
  closest(selector: string): FakeElement | null;
  cloneNode(deep?: boolean): FakeElement;
}

export class FakeText extends FakeNode {
  value: string;

  constructor(value: string);

  textContent: string;
  cloneNode(deep?: boolean): FakeText;
}

export class FakeFragment extends FakeNode {
  constructor();

  cloneNode(deep?: boolean): FakeFragment;
}

export class FakeInlineStyle {
  cssText: string;

  getPropertyValue(name: string): string;
  getPropertyPriority(name: string): string;
  setProperty(name: string, value: string | null, priority?: string): void;
  removeProperty(name: string): string;
}

export interface FixtureComputedStyleOverrides {
  [key: string]: string;
}

export function fixtureComputedStyle(
  element: FakeElement | null | undefined,
  pseudo?: string | null,
  overrides?: FixtureComputedStyleOverrides
): CSSStyleDeclaration;

export function matchesSelector(element: FakeElement, selector: string): boolean;

export function sha256(value: string | Uint8Array): string;

export interface StyleDeclarationValues {
  [key: string]: string;
}

export function styleDeclaration(values: StyleDeclarationValues): {
  getPropertyValue(name: string): string;
};

export type CanonicalFixtureNode =
  | ["#", string]
  | [string, Array<[string, string]>, CanonicalFixtureNode[]];

export function canonicalFixtureNode(node: FakeNode): CanonicalFixtureNode;
