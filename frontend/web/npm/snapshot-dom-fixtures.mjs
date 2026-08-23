// Shared fake-DOM fixtures for snapshot adoption tests: a minimal selector-
//matching node tree, the computed-style fixture, and the canonical node form
//used by manifest artifact digests. Pure move from precomputed.test.mjs so
// timing-golden journeys can drive the same adoption transport.

import { createHash } from "node:crypto";

function matchesSelector(element, selector) {
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
  constructor(nodeType) {
    this.nodeType = nodeType;
    this.childNodes = [];
    this.parentNode = null;
    this.parentElement = null;
  }

  get firstChild() {
    return this.childNodes[0] ?? null;
  }

  append(...nodes) {
    for (const node of nodes) this.appendChild(node);
  }

  appendChild(node) {
    if (node.nodeType === 11) {
      while (node.firstChild) this.appendChild(node.firstChild);
      return node;
    }
    if (node.parentNode) node.parentNode.removeChild(node);
    this.childNodes.push(node);
    node.parentNode = this;
    node.parentElement = this.nodeType === 1 ? this : null;
    return node;
  }

  removeChild(node) {
    const index = this.childNodes.indexOf(node);
    if (index < 0) throw new Error("NotAChild");
    this.childNodes.splice(index, 1);
    node.parentNode = null;
    node.parentElement = null;
    return node;
  }

  remove() {
    this.parentNode?.removeChild(this);
  }

  get textContent() {
    return this.childNodes.map((node) => node.textContent).join("");
  }

  set textContent(value) {
    while (this.firstChild) this.removeChild(this.firstChild);
    if (value) this.appendChild(new FakeText(String(value)));
  }

  querySelectorAll(selector) {
    const result = [];
    const visit = (node) => {
      for (const child of node.childNodes) {
        if (child.nodeType === 1 && matchesSelector(child, selector)) result.push(child);
        visit(child);
      }
    };
    visit(this);
    return result;
  }

  querySelector(selector) {
    return this.querySelectorAll(selector)[0] ?? null;
  }
}

class FakeText extends FakeNode {
  constructor(value) {
    super(3);
    this.value = value;
  }

  get textContent() {
    return this.value;
  }

  set textContent(value) {
    this.value = String(value);
  }

  cloneNode() {
    return new FakeText(this.value);
  }
}

function computeNormalInnerText(root) {
  let result = "";
  let atLineStart = true;
  let pendingSpace = false;

  function visit(node) {
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
      if (node.tagName === "BR") {
        result += "\n";
        atLineStart = true;
        pendingSpace = false;
      } else if (node._innerText != null) {
        const tokens = node._innerText.split(/(\s+)/);
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
        for (const child of node.childNodes) {
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

class FakeElement extends FakeNode {
  constructor(tagName) {
    super(1);
    this.tagName = tagName.toUpperCase();
    this.attributes = new Map();
    this.dataset = {};
    this.style = { cssText: "" };
    this.ownerDocument = null;
    this.width = 0;
    this.height = 0;
    this.left = 0;
    this.top = 0;
    this._innerText = null;
  }

  setAttribute(name, value) {
    this.attributes.set(name, String(value));
  }

  getAttribute(name) {
    return this.attributes.get(name) ?? null;
  }

  hasAttribute(name) {
    return this.attributes.has(name);
  }

  removeAttribute(name) {
    this.attributes.delete(name);
  }

  get innerText() {
    if (this._innerText != null) return this._innerText;
    return computeNormalInnerText(this);
  }

  set innerText(value) {
    this._innerText = String(value);
  }

  getBoundingClientRect() {
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

  getClientRects() {
    return [this.getBoundingClientRect()];
  }

  closest(selector) {
    for (let node = this; node; node = node.parentElement) {
      if (node.nodeType === 1 && matchesSelector(node, selector)) return node;
    }
    return null;
  }

  cloneNode(deep = false) {
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

  cloneNode(deep = false) {
    const clone = new FakeFragment();
    if (deep) for (const child of this.childNodes) clone.appendChild(child.cloneNode(true));
    return clone;
  }
}

function styleDeclaration(values) {
  return {
    getPropertyValue(name) {
      return values[name] ?? "";
    },
  };
}

function sha256(value) {
  return createHash("sha256").update(value).digest("hex");
}

function canonicalFixtureNode(node) {
  if (node.nodeType === 3) return ["#", node.textContent];
  return [
    node.tagName.toLocaleLowerCase(),
    Array.from(node.attributes, ([name, value]) => [name, value])
      .sort(([left], [right]) => left < right ? -1 : left > right ? 1 : 0),
    node.childNodes.map(canonicalFixtureNode),
  ];
}

function fixtureComputedStyle(element, _pseudo, overrides = {}) {
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
export {
  FakeElement,
  FakeFragment,
  FakeNode,
  FakeText,
  canonicalFixtureNode,
  fixtureComputedStyle,
  matchesSelector,
  sha256,
  styleDeclaration,
};
