// Fake host environment for driving Kotlin/JS runtime under Node.js test runner.
// Node does not provide rAF or DOM; the fake clock and DOM doubles below provide
// stable and synchronous execution for custody relayout and destruction tests.

import {
  FakeElement,
  FakeFragment,
  FakeNode,
  FakeText,
  fixtureComputedStyle,
} from "./snapshot-dom-fixtures.mjs";

export class FakeDOMRect {
  constructor(x = 0, y = 0, width = 0, height = 0) {
    this.x = x;
    this.y = y;
    this.top = y;
    this.left = x;
    this.right = x + width;
    this.bottom = y + height;
    this.width = width;
    this.height = height;
  }

  toJSON() {
    return {
      x: this.x,
      y: this.y,
      top: this.top,
      left: this.left,
      right: this.right,
      bottom: this.bottom,
      width: this.width,
      height: this.height,
    };
  }
}

export class FakeEvent {
  constructor(type, init = {}) {
    this.type = type;
    this.bubbles = init.bubbles ?? false;
    this.cancelable = init.cancelable ?? false;
    this.defaultPrevented = false;
    this.detail = init.detail ?? null;
    this.target = null;
    this.currentTarget = null;
  }

  preventDefault() {
    if (this.cancelable) this.defaultPrevented = true;
  }
}

export class FakeCustomEvent extends FakeEvent {
  constructor(type, init = {}) {
    super(type, init);
    this.detail = init.detail ?? null;
  }
}

function matchesHostSelector(element, selector) {
  if (selector === "*") return element.nodeType === 1;
  const parts = selector.split(",").map((s) => s.trim());
  if (parts.length > 1) {
    return parts.some((p) => matchesHostSelector(element, p));
  }

  const spaceTokens = selector.split(/\s+/).filter(Boolean);
  if (spaceTokens.length > 1) {
    const targetSelector = spaceTokens[spaceTokens.length - 1];
    if (!matchesCompound(element, targetSelector)) return false;
    let ancestor = element.parentElement;
    let tokenIndex = spaceTokens.length - 2;
    while (ancestor && tokenIndex >= 0) {
      if (matchesCompound(ancestor, spaceTokens[tokenIndex])) {
        tokenIndex -= 1;
      }
      ancestor = ancestor.parentElement;
    }
    return tokenIndex < 0;
  }

  return matchesCompound(element, selector);
}

function matchesCompound(element, selector) {
  if (element.nodeType !== 1) return false;
  if (selector === "*") return true;

  if (selector.startsWith(":is(") && selector.endsWith(")")) {
    const inside = selector.slice(4, -1);
    const subSelectors = inside.split(",").map((s) => s.trim());
    return subSelectors.some((sub) => matchesHostSelector(element, sub));
  }

  const isMatch = /^([a-zA-Z0-9_-]+)?(?:\.([a-zA-Z0-9_-]+))?(?:\[([a-zA-Z0-9_-]+)(?:([~|^$*]?=)(?:"([^"]*)"|'([^']*)'|([^\]]+)))?\])?$/u.exec(selector);
  if (!isMatch) {
    return false;
  }

  const [, tagName, className, attrName, , dblVal, sglVal, rawVal] = isMatch;
  if (tagName && element.tagName !== tagName.toUpperCase()) return false;
  if (className) {
    const classes = (element.getAttribute("class") || "").trim().split(/\s+/);
    if (!classes.includes(className)) return false;
  }
  if (attrName) {
    if (!element.hasAttribute(attrName)) return false;
    const expectedVal = dblVal ?? sglVal ?? rawVal;
    if (expectedVal != null && element.getAttribute(attrName) !== expectedVal) return false;
  }
  return true;
}

function createStyleObject(element) {
  const map = new Map();
  const priorities = new Map();
  return new Proxy({}, {
    get(target, prop) {
      if (prop === "setProperty") {
        return (name, value, priority = "") => {
          if (value == null || value === "") {
            map.delete(name);
            priorities.delete(name);
          } else {
            map.set(name, String(value));
            if (priority) priorities.set(name, priority);
            else priorities.delete(name);
          }
          updateStyleAttr();
        };
      }
      if (prop === "getPropertyValue") {
        return (name) => map.get(name) ?? "";
      }
      if (prop === "getPropertyPriority") {
        return (name) => priorities.get(name) ?? "";
      }
      if (prop === "removeProperty") {
        return (name) => {
          const old = map.get(name) ?? "";
          map.delete(name);
          priorities.delete(name);
          updateStyleAttr();
          return old;
        };
      }
      if (prop === "cssText") {
        return Array.from(map.entries()).map(([k, v]) => `${k}: ${v}`).join("; ");
      }
      if (typeof prop === "string") {
        const kebab = prop.replace(/[A-Z]/g, (m) => `-${m.toLowerCase()}`);
        return map.get(kebab) ?? map.get(prop) ?? "";
      }
      return Reflect.get(target, prop);
    },
    set(target, prop, value) {
      if (prop === "cssText") {
        map.clear();
        if (value) {
          const decls = String(value).split(";");
          for (const decl of decls) {
            const idx = decl.indexOf(":");
            if (idx > 0) {
              const k = decl.slice(0, idx).trim();
              const v = decl.slice(idx + 1).trim();
              if (k) map.set(k, v);
            }
          }
        }
        return true;
      }
      if (typeof prop === "string") {
        const kebab = prop.replace(/[A-Z]/g, (m) => `-${m.toLowerCase()}`);
        if (value == null || value === "") {
          map.delete(kebab);
          map.delete(prop);
        } else {
          map.set(kebab, String(value));
        }
        updateStyleAttr();
        return true;
      }
      return Reflect.set(target, prop, value);
    },
    has(target, prop) {
      if (typeof prop === "string") {
        const kebab = prop.replace(/[A-Z]/g, (m) => `-${m.toLowerCase()}`);
        return map.has(kebab) || map.has(prop) || prop in target;
      }
      return Reflect.has(target, prop);
    },
  });

  function updateStyleAttr() {
    if (map.size === 0) {
      element.attributes.delete("style");
    } else {
      const text = Array.from(map.entries()).map(([k, v]) => `${k}: ${v}`).join("; ");
      element.attributes.set("style", text);
    }
  }
}

export class HostElement extends FakeElement {
  constructor(tagName) {
    super(tagName);
    this.ownerDocument = globalThis.document ?? null;
    this.style = createStyleObject(this);
    this.listeners = new Map();
  }

  get firstElementChild() {
    return this.childNodes.find((n) => n.nodeType === 1) ?? null;
  }

  get lastElementChild() {
    return this.childNodes.filter((n) => n.nodeType === 1).pop() ?? null;
  }

  get children() {
    return this.childNodes.filter((n) => n.nodeType === 1);
  }

  get dataset() {
    return new Proxy(this, {
      get(target, prop) {
        if (typeof prop !== "string") return undefined;
        const attrName = "data-" + prop.replace(/([A-Z])/g, "-$1").toLowerCase();
        return target.getAttribute(attrName) ?? undefined;
      },
      set(target, prop, value) {
        if (typeof prop !== "string") return false;
        const attrName = "data-" + prop.replace(/([A-Z])/g, "-$1").toLowerCase();
        target.setAttribute(attrName, String(value));
        return true;
      },
      deleteProperty(target, prop) {
        if (typeof prop !== "string") return false;
        const attrName = "data-" + prop.replace(/([A-Z])/g, "-$1").toLowerCase();
        target.removeAttribute(attrName);
        return true;
      },
      has(target, prop) {
        if (typeof prop !== "string") return false;
        const attrName = "data-" + prop.replace(/([A-Z])/g, "-$1").toLowerCase();
        return target.hasAttribute(attrName);
      },
    });
  }

  set dataset(value) {
    if (value && typeof value === "object") {
      for (const [k, v] of Object.entries(value)) {
        this.dataset[k] = v;
      }
    }
  }

  get classList() {
    const getClasses = () => (this.getAttribute("class") || "").trim().split(/\s+/).filter(Boolean);
    return {
      contains: (cls) => getClasses().includes(cls),
      add: (...cls) => {
        const set = new Set(getClasses());
        for (const c of cls) set.add(c);
        this.setAttribute("class", Array.from(set).join(" "));
      },
      remove: (...cls) => {
        const set = new Set(getClasses());
        for (const c of cls) set.delete(c);
        this.setAttribute("class", Array.from(set).join(" "));
      },
    };
  }

  setAttribute(name, value) {
    super.setAttribute(name, value);
    if (name === "style") {
      this.style.cssText = value;
    }
  }

  removeAttribute(name) {
    super.removeAttribute(name);
    if (name === "style") {
      this.style.cssText = "";
    }
  }

  addEventListener(type, listener) {
    if (!this.listeners.has(type)) {
      this.listeners.set(type, new Set());
    }
    this.listeners.get(type).add(listener);
  }

  removeEventListener(type, listener) {
    this.listeners.get(type)?.delete(listener);
  }

  dispatchEvent(event) {
    event.target = this;
    event.currentTarget = this;
    const set = this.listeners.get(event.type);
    if (set) {
      for (const listener of Array.from(set)) {
        listener(event);
      }
    }
    return !event.defaultPrevented;
  }

  replaceChildren(...nodes) {
    while (this.firstChild) this.removeChild(this.firstChild);
    for (const node of nodes) this.appendChild(node);
  }

  insertBefore(newNode, referenceNode) {
    if (!referenceNode) return this.appendChild(newNode);
    if (newNode.nodeType === 11) {
      while (newNode.firstChild) this.insertBefore(newNode.firstChild, referenceNode);
      return newNode;
    }
    if (newNode.parentNode) newNode.parentNode.removeChild(newNode);
    const index = this.childNodes.indexOf(referenceNode);
    if (index < 0) throw new Error("NotFoundError");
    this.childNodes.splice(index, 0, newNode);
    newNode.parentNode = this;
    newNode.parentElement = this.nodeType === 1 ? this : null;
    return newNode;
  }

  replaceChild(newChild, oldChild) {
    this.insertBefore(newChild, oldChild);
    return this.removeChild(oldChild);
  }

  getBoundingClientRect() {
    let w = this.width;
    if (!w) {
      for (let curr = this; curr; curr = curr.parentElement) {
        const styleWidth = curr.style?.getPropertyValue?.("width") || curr.style?.width;
        if (styleWidth) {
          const parsed = Number.parseFloat(styleWidth);
          if (!Number.isNaN(parsed) && parsed > 0) {
            w = parsed;
            break;
          }
        }
        if (curr.width) {
          w = curr.width;
          break;
        }
      }
    }
    if (!w) w = 360;
    return new FakeDOMRect(this.left ?? 0, this.top ?? 0, w, this.height || 30);
  }

  getClientRects() {
    return [this.getBoundingClientRect()];
  }

  get innerHTML() {
    return this.childNodes.map((child) => serializeNode(child)).join("");
  }

  set innerHTML(value) {
    while (this.firstChild) this.removeChild(this.firstChild);
    if (value) {
      for (const node of parseHtmlNodes(value, this.ownerDocument)) {
        this.appendChild(node);
      }
    }
  }

  querySelectorAll(selector) {
    const result = [];
    const visit = (node) => {
      for (const child of node.childNodes) {
        if (child.nodeType === 1 && matchesHostSelector(child, selector)) {
          result.push(child);
        }
        visit(child);
      }
    };
    visit(this);
    return result;
  }

  querySelector(selector) {
    return this.querySelectorAll(selector)[0] ?? null;
  }

  cloneNode(deep = false) {
    const clone = new HostElement(this.tagName);
    clone.ownerDocument = this.ownerDocument;
    clone.attributes = new Map(this.attributes);
    clone.style.cssText = this.style.cssText;
    if (deep) {
      for (const child of this.childNodes) {
        clone.appendChild(child.cloneNode(true));
      }
    }
    return clone;
  }
}

// Extend FakeText with data property.
Object.defineProperty(FakeText.prototype, "data", {
  get() { return this.value; },
  set(v) { this.value = String(v); },
  configurable: true,
});

function serializeNode(node) {
  if (node.nodeType === 3) {
    return node.value ?? node.textContent ?? "";
  }
  if (node.nodeType === 1) {
    const tag = node.tagName.toLowerCase();
    let attrs = "";
    if (node.attributes) {
      for (const [k, v] of node.attributes) {
        attrs += ` ${k}="${v}"`;
      }
    }
    const inner = (node.childNodes || []).map(serializeNode).join("");
    return `<${tag}${attrs}>${inner}</${tag}>`;
  }
  if (node.nodeType === 11) {
    return (node.childNodes || []).map(serializeNode).join("");
  }
  return "";
}

export function parseHtmlFragment(html, doc = globalThis.document) {
  const nodes = parseHtmlNodes(html, doc);
  if (nodes.length === 1 && nodes[0].nodeType === 1) {
    return nodes[0];
  }
  const root = new HostElement("div");
  if (doc) root.ownerDocument = doc;
  for (const node of nodes) {
    root.appendChild(node);
  }
  return root;
}

function parseHtmlNodes(html, doc = globalThis.document) {
  const tagRegex = /<\/?([a-zA-Z0-9-]+)((?:\s+[a-zA-Z0-9_-]+(?:\s*=\s*(?:"[^"]*"|'[^']*'|[^\s>]+))?)*)\s*\/?>|([^<]+)/gs;
  const attrRegex = /([a-zA-Z0-9_-]+)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+)))?/g;

  const root = new HostElement("div");
  if (doc) root.ownerDocument = doc;
  const stack = [root];

  let match;
  while ((match = tagRegex.exec(html)) !== null) {
    const [full, tagName, attrStr, text] = match;
    if (text !== undefined) {
      if (text.length > 0) {
        const textNode = new FakeText(text);
        stack[stack.length - 1].appendChild(textNode);
      }
    } else if (full.startsWith("</")) {
      if (stack.length > 1) {
        stack.pop();
      }
    } else {
      const el = new HostElement(tagName);
      if (doc) el.ownerDocument = doc;
      if (attrStr) {
        let attrMatch;
        while ((attrMatch = attrRegex.exec(attrStr)) !== null) {
          const attrName = attrMatch[1];
          const attrVal = attrMatch[2] ?? attrMatch[3] ?? attrMatch[4] ?? "";
          el.setAttribute(attrName, attrVal);
          if (attrName === "style") {
            el.style.cssText = attrVal;
          }
        }
      }
      stack[stack.length - 1].appendChild(el);
      const isSelfClosing = full.endsWith("/>") || ["br", "hr", "img", "input"].includes(tagName.toLowerCase());
      if (!isSelfClosing) {
        stack.push(el);
      }
    }
  }

  return Array.from(root.childNodes);
}

export class FakeCanvasRenderingContext2D {
  constructor() {
    this.font = "";
    this.direction = "ltr";
    this.textAlign = "start";
    this.textBaseline = "alphabetic";
  }

  setTransform() {}
  resetTransform() {}
  save() {}
  restore() {}
  scale() {}
  translate() {}
  rotate() {}
  transform() {}
  fillText() {}
  strokeText() {}
  beginPath() {}
  closePath() {}
  moveTo() {}
  lineTo() {}
  arc() {}
  fill() {}
  stroke() {}
  clearRect() {}
  fillRect() {}
  strokeRect() {}
  createImageData() { return {}; }
  getImageData() { return { data: new Uint8ClampedArray(4) }; }
  putImageData() {}

  measureText(text) {
    const len = String(text ?? "").length;
    return {
      width: len * 18,
      actualBoundingBoxLeft: 0,
      actualBoundingBoxRight: len * 18,
      actualBoundingBoxAscent: 16,
      actualBoundingBoxDescent: 4,
      fontBoundingBoxAscent: 16,
      fontBoundingBoxDescent: 4,
      ideographicBaseline: -16,
      alphabeticBaseline: -16,
    };
  }
}

export class FakeHTMLCanvasElement extends HostElement {
  constructor() {
    super("canvas");
    this._ctx2d = new FakeCanvasRenderingContext2D();
  }

  getContext(type) {
    return type === "2d" ? this._ctx2d : null;
  }
}

function createFakeCanvas() {
  return new FakeHTMLCanvasElement();
}

let originalGlobals = null;

export function installTestAnimationFrames() {
  if (globalThis.__TiqianTestAnimationFrames) return;
  const tqInstallFrameState = {
    originalRequest: globalThis.requestAnimationFrame,
    originalCancel: globalThis.cancelAnimationFrame,
    originalRequestIdle: globalThis.requestIdleCallback,
    originalCancelIdle: globalThis.cancelIdleCallback,
    originalSetTimeout: globalThis.setTimeout,
    originalClearTimeout: globalThis.clearTimeout,
    callbacks: new Map(),
    nextId: 1,
    cancelled: 0,
    idleScheduled: 0,
    idleBudget: 50,
  };
  globalThis.__TiqianTestAnimationFrames = tqInstallFrameState;
  globalThis.requestAnimationFrame = (callback) => {
    const tqFrameId = tqInstallFrameState.nextId++;
    tqInstallFrameState.callbacks.set(tqFrameId, callback);
    return tqFrameId;
  };
  globalThis.cancelAnimationFrame = (tqFrameId) => {
    if (tqInstallFrameState.callbacks.delete(tqFrameId)) tqInstallFrameState.cancelled += 1;
  };
  globalThis.requestIdleCallback = (callback) => {
    const tqIdleId = tqInstallFrameState.nextId++;
    tqInstallFrameState.idleScheduled += 1;
    tqInstallFrameState.callbacks.set(tqIdleId, () => callback({
      didTimeout: false,
      timeRemaining: () => tqInstallFrameState.idleBudget,
    }));
    return tqIdleId;
  };
  globalThis.cancelIdleCallback = (tqIdleId) => {
    if (tqInstallFrameState.callbacks.delete(tqIdleId)) tqInstallFrameState.cancelled += 1;
  };
  globalThis.setTimeout = (callback) => {
    const tqTimerId = tqInstallFrameState.nextId++;
    tqInstallFrameState.callbacks.set(tqTimerId, callback);
    return tqTimerId;
  };
  globalThis.clearTimeout = (tqTimerId) => {
    if (tqInstallFrameState.callbacks.delete(tqTimerId)) tqInstallFrameState.cancelled += 1;
  };
  if (globalThis.window) {
    globalThis.window.requestAnimationFrame = globalThis.requestAnimationFrame;
    globalThis.window.cancelAnimationFrame = globalThis.cancelAnimationFrame;
    globalThis.window.requestIdleCallback = globalThis.requestIdleCallback;
    globalThis.window.cancelIdleCallback = globalThis.cancelIdleCallback;
    globalThis.window.setTimeout = globalThis.setTimeout;
    globalThis.window.clearTimeout = globalThis.clearTimeout;
  }
}

export function flushOneTestAnimationFrame() {
  const tqFlushOneState = globalThis.__TiqianTestAnimationFrames;
  if (!tqFlushOneState) return 0;
  const tqFlushOneCallbacks = Array.from(tqFlushOneState.callbacks.values());
  tqFlushOneState.callbacks.clear();
  for (const tqFlushOneCallback of tqFlushOneCallbacks) tqFlushOneCallback(performance.now());
  return tqFlushOneCallbacks.length;
}

export function flushAllTestAnimationFrames() {
  const tqFlushAllState = globalThis.__TiqianTestAnimationFrames;
  if (!tqFlushAllState) return 0;
  let tqFlushAllSlices = 0;
  while (tqFlushAllState.callbacks.size > 0) {
    if (tqFlushAllSlices++ > 1000) throw new Error("animation frame test queue did not settle");
    const tqFlushAllCallbacks = Array.from(tqFlushAllState.callbacks.values());
    tqFlushAllState.callbacks.clear();
    for (const tqFlushAllCallback of tqFlushAllCallbacks) tqFlushAllCallback(performance.now());
  }
  return tqFlushAllSlices;
}

export function pendingTestAnimationFrameCount() {
  return globalThis.__TiqianTestAnimationFrames ? globalThis.__TiqianTestAnimationFrames.callbacks.size : 0;
}

export function cancelledTestAnimationFrameCount() {
  return globalThis.__TiqianTestAnimationFrames ? globalThis.__TiqianTestAnimationFrames.cancelled : 0;
}

export function restoreTestAnimationFrames() {
  const tqRestoreFrameState = globalThis.__TiqianTestAnimationFrames;
  if (!tqRestoreFrameState) return;
  globalThis.requestAnimationFrame = tqRestoreFrameState.originalRequest;
  globalThis.cancelAnimationFrame = tqRestoreFrameState.originalCancel;
  if (tqRestoreFrameState.originalRequestIdle === undefined) {
    delete globalThis.requestIdleCallback;
  } else {
    globalThis.requestIdleCallback = tqRestoreFrameState.originalRequestIdle;
  }
  if (tqRestoreFrameState.originalCancelIdle === undefined) {
    delete globalThis.cancelIdleCallback;
  } else {
    globalThis.cancelIdleCallback = tqRestoreFrameState.originalCancelIdle;
  }
  globalThis.setTimeout = tqRestoreFrameState.originalSetTimeout;
  globalThis.clearTimeout = tqRestoreFrameState.originalClearTimeout;
  if (globalThis.window) {
    globalThis.window.requestAnimationFrame = globalThis.requestAnimationFrame;
    globalThis.window.cancelAnimationFrame = globalThis.cancelAnimationFrame;
    globalThis.window.requestIdleCallback = globalThis.requestIdleCallback;
    globalThis.window.cancelIdleCallback = globalThis.cancelIdleCallback;
    globalThis.window.setTimeout = globalThis.setTimeout;
    globalThis.window.clearTimeout = globalThis.clearTimeout;
  }
  delete globalThis.__TiqianTestAnimationFrames;
}

// Ensure Array.prototype.item exists for NodeList compatibility with Kotlin/JS DOM.
if (!Array.prototype.item) {
  Object.defineProperty(Array.prototype, "item", {
    value: function (index) {
      return this[index] ?? null;
    },
    configurable: true,
    writable: true,
  });
}

function toNodeList(array) {
  array.item = function (index) {
    return this[index] ?? null;
  };
  return array;
}

// Node global definition for Kotlin/JS DOM checks.
export class HostNode extends FakeNode {
  contains(other) {
    for (let node = other; node; node = node.parentNode) {
      if (node === this) return true;
    }
    return false;
  }
}
HostNode.TEXT_NODE = 3;
HostNode.ELEMENT_NODE = 1;
HostNode.DOCUMENT_FRAGMENT_NODE = 11;

FakeNode.TEXT_NODE = 3;
FakeNode.ELEMENT_NODE = 1;
FakeNode.DOCUMENT_FRAGMENT_NODE = 11;

Object.defineProperty(FakeNode.prototype, "nextSibling", {
  get() {
    if (!this.parentNode) return null;
    const siblings = this.parentNode.childNodes;
    const index = siblings.indexOf(this);
    if (index < 0 || index + 1 >= siblings.length) return null;
    return siblings[index + 1];
  },
  configurable: true,
});

Object.defineProperty(FakeNode.prototype, "previousSibling", {
  get() {
    if (!this.parentNode) return null;
    const siblings = this.parentNode.childNodes;
    const index = siblings.indexOf(this);
    if (index <= 0) return null;
    return siblings[index - 1];
  },
  configurable: true,
});

Object.defineProperty(FakeNode.prototype, "lastChild", {
  get() {
    return this.childNodes[this.childNodes.length - 1] ?? null;
  },
  configurable: true,
});

FakeNode.prototype.appendChild = function (node) {
  if (node.nodeType === 11) {
    while (node.firstChild) {
      const child = node.firstChild;
      node.childNodes.shift();
      this.childNodes.push(child);
      child.parentNode = this;
      child.parentElement = this.nodeType === 1 ? this : null;
    }
    return node;
  }
  if (node.parentNode) node.parentNode.removeChild(node);
  this.childNodes.push(node);
  node.parentNode = this;
  node.parentElement = this.nodeType === 1 ? this : null;
  return node;
};

FakeNode.prototype.removeChild = function (node) {
  const index = this.childNodes.indexOf(node);
  if (index < 0) throw new Error("NotFoundError");
  this.childNodes.splice(index, 1);
  node.parentNode = null;
  node.parentElement = null;
  return node;
};

FakeNode.prototype.insertBefore = function (newNode, referenceNode) {
  if (!referenceNode) return this.appendChild(newNode);
  if (newNode.nodeType === 11) {
    while (newNode.firstChild) this.insertBefore(newNode.firstChild, referenceNode);
    return newNode;
  }
  if (newNode.parentNode) newNode.parentNode.removeChild(newNode);
  const index = this.childNodes.indexOf(referenceNode);
  if (index < 0) throw new Error("NotFoundError");
  this.childNodes.splice(index, 0, newNode);
  newNode.parentNode = this;
  newNode.parentElement = this.nodeType === 1 ? this : null;
  return newNode;
};

FakeNode.prototype.replaceChild = function (newChild, oldChild) {
  this.insertBefore(newChild, oldChild);
  return this.removeChild(oldChild);
};

FakeNode.prototype.replaceChildren = function (...nodes) {
  while (this.firstChild) {
    const child = this.firstChild;
    this.childNodes.shift();
    child.parentNode = null;
    child.parentElement = null;
  }
  for (const node of nodes) {
    if (node.nodeType === 11) {
      while (node.firstChild) {
        const child = node.firstChild;
        node.childNodes.shift();
        this.childNodes.push(child);
        child.parentNode = this;
        child.parentElement = this.nodeType === 1 ? this : null;
      }
    } else {
      if (node.parentNode) node.parentNode.removeChild(node);
      this.childNodes.push(node);
      node.parentNode = this;
      node.parentElement = this.nodeType === 1 ? this : null;
    }
  }
};

let cachedDocument = null;

export class FakeRange {
  constructor() {
    this.startContainer = null;
    this.startOffset = 0;
    this.endContainer = null;
    this.endOffset = 0;
  }

  setStart(node, offset) {
    this.startContainer = node;
    this.startOffset = offset;
  }

  setEnd(node, offset) {
    this.endContainer = node;
    this.endOffset = offset;
  }

  selectNodeContents(node) {
    this.startContainer = node;
    this.startOffset = 0;
  }

  getBoundingClientRect() {
    return new FakeDOMRect(0, 0, 36, 30);
  }

  getClientRects() {
    return [this.getBoundingClientRect()];
  }
}

function createDocumentDouble() {
  if (cachedDocument) return cachedDocument;
  const listeners = new Map();
  const doc = {
    baseURI: "https://example.test/post/",
    styleSheets: [],
    listeners,
    fonts: {
      load: async () => [{}],
      check: () => true,
      addEventListener() {},
      removeEventListener() {},
      ready: Promise.resolve(),
    },
    createElement(tagName) {
      if (String(tagName).toLowerCase() === "canvas") {
        const canvas = createFakeCanvas();
        canvas.ownerDocument = doc;
        return canvas;
      }
      const el = new HostElement(tagName);
      el.ownerDocument = doc;
      return el;
    },
    createTextNode(data) {
      return new FakeText(data);
    },
    createDocumentFragment() {
      return new FakeFragment();
    },
    createRange() {
      return new FakeRange();
    },
    getElementById(id) {
      return doc.body?.querySelector(`#${id}`) ?? null;
    },
    querySelector(selector) {
      return doc.body?.querySelector(selector) ?? null;
    },
    querySelectorAll(selector) {
      return doc.body?.querySelectorAll(selector) ?? [];
    },
    addEventListener(type, listener) {
      if (!listeners.has(type)) {
        listeners.set(type, new Set());
      }
      listeners.get(type).add(listener);
    },
    removeEventListener(type, listener) {
      listeners.get(type)?.delete(listener);
    },
    dispatchEvent(event) {
      event.target = doc;
      event.currentTarget = doc;
      const set = listeners.get(event.type);
      if (set) {
        for (const listener of Array.from(set)) {
          listener(event);
        }
      }
      return !event.defaultPrevented;
    },
    getSelection() {
      return {
        removeAllRanges() {},
        addRange() {},
        toString: () => "",
      };
    },
  };
  doc.body = doc.createElement("body");
  doc.head = doc.createElement("head");
  doc.documentElement = doc.createElement("html");
  doc.documentElement.appendChild(doc.head);
  doc.documentElement.appendChild(doc.body);
  cachedDocument = doc;
  return doc;
}

export function buildWorld() {
  if (!originalGlobals) {
    originalGlobals = {
      document: globalThis.document,
      Element: globalThis.Element,
      HTMLElement: globalThis.HTMLElement,
      HTMLCanvasElement: globalThis.HTMLCanvasElement,
      CanvasRenderingContext2D: globalThis.CanvasRenderingContext2D,
      Node: globalThis.Node,
      Range: globalThis.Range,
      DocumentFragment: globalThis.DocumentFragment,
      Text: globalThis.Text,
      getComputedStyle: globalThis.getComputedStyle,
      MutationObserver: globalThis.MutationObserver,
      ResizeObserver: globalThis.ResizeObserver,
      IntersectionObserver: globalThis.IntersectionObserver,
      CustomEvent: globalThis.CustomEvent,
      Event: globalThis.Event,
      DOMRect: globalThis.DOMRect,
      window: globalThis.window,
      fetch: globalThis.fetch,
      requestAnimationFrame: globalThis.requestAnimationFrame,
      cancelAnimationFrame: globalThis.cancelAnimationFrame,
      requestIdleCallback: globalThis.requestIdleCallback,
      cancelIdleCallback: globalThis.cancelIdleCallback,
      setTimeout: globalThis.setTimeout,
      clearTimeout: globalThis.clearTimeout,
    };
  }

  globalThis.Node = FakeNode;
  globalThis.Element = HostElement;
  globalThis.HTMLElement = HostElement;
  globalThis.HTMLCanvasElement = FakeHTMLCanvasElement;
  globalThis.CanvasRenderingContext2D = FakeCanvasRenderingContext2D;
  globalThis.Range = FakeRange;
  globalThis.DocumentFragment = FakeFragment;
  globalThis.Text = FakeText;
  globalThis.CustomEvent = FakeCustomEvent;
  globalThis.Event = FakeEvent;
  globalThis.DOMRect = FakeDOMRect;
  globalThis.document = createDocumentDouble();
  globalThis.window = globalThis;

  globalThis.getComputedStyle = (element, pseudo) => {
    const isInlineTag = [
      "STRONG", "SPAN", "EM", "A", "B", "I", "U", "MARK", "SMALL",
      "SUB", "SUP", "CODE", "KBD", "SAMP", "VAR", "TIME", "DATA",
      "RUBY", "RT", "RP", "BDI", "BDO", "ABBR", "Q", "CITE",
    ].includes(element?.tagName);
    const overrides = isInlineTag ? { display: "inline" } : {};
    const base = fixtureComputedStyle(element, pseudo, overrides);
    return {
      ...base,
      getPropertyValue(name) {
        const inline = element?.style?.getPropertyValue?.(name);
        if (inline !== undefined && inline !== "") return inline;
        const camel = name.replace(/-([a-z])/g, (_, c) => c.toUpperCase());
        return base[name] ?? base[camel] ?? "";
      },
    };
  };

  globalThis.MutationObserver = class {
    constructor(callback) { this.callback = callback; }
    observe() {}
    disconnect() {}
    takeRecords() { return []; }
  };

  globalThis.ResizeObserver = class {
    constructor(callback) { this.callback = callback; }
    observe() {}
    unobserve() {}
    disconnect() {}
  };

  globalThis.IntersectionObserver = class {
    constructor(callback) { this.callback = callback; }
    observe() {}
    unobserve() {}
    disconnect() {}
  };

  globalThis.fetch = async () => ({ ok: false });

  installTestAnimationFrames();
}

export function cleanupWorld() {
  if (globalThis.document?.body) {
    while (globalThis.document.body.firstChild) {
      globalThis.document.body.removeChild(globalThis.document.body.firstChild);
    }
  }
}

export function setElementRect(element, top, width) {
  element.getBoundingClientRect = () => new FakeDOMRect(0, top, width, 30);
}

export function relayoutEventIsStale(event) {
  return Boolean(event?.detail && event.detail.stale === true);
}

export function eventDetailInt(event, name) {
  return Number(event?.detail && event.detail[name]);
}

export function dispatchRelayout(root) {
  globalThis.document.dispatchEvent(new FakeCustomEvent("tiqian:relayout", { detail: { root } }));
}

export function detachViaChannel(root) {
  globalThis.document.dispatchEvent(new FakeCustomEvent("tiqian:detach", { detail: { root } }));
}

export function testGrantController(root, generation, deadlineMs, quota) {
  return {
    root,
    generation,
    deadline: deadlineMs,
    quota,
    shouldStop(processed) {
      return processed >= quota || Date.now() >= deadlineMs;
    },
  };
}

export function failExactPreparedDomRender(detail) {
  globalThis.__TiqianPreparedDomValidator = { issue: () => null };
  globalThis.__TiqianPreparedDomRenderer = {
    render() {
      throw new Error(detail);
    },
  };
}

export function installExactFontSessionFixture({
  failShaping = false,
  failFamily = null,
  failText = null,
  varyFaceByText = false,
} = {}) {
  const shapes = new Map();
  const metrics = new Map();
  let nextHandle = 1;
  globalThis.__TiqianExactPreparedPlan = "";
  globalThis.__TiqianExactPreparedRenderCount = 0;
  globalThis.__TiqianExactFontShapeCount = 0;
  globalThis.__TiqianExactFontFallbackCount = 0;
  globalThis.__TiqianFontBackend = {
    shape(_session, displayText, families, fontSize, _fontWeight, _italic, _locale, role) {
      if (
        failShaping ||
        (failFamily && String(families).includes(failFamily)) ||
        (failText && String(displayText).includes(failText))
      ) {
        globalThis.__TiqianExactFontFallbackCount += 1;
        throw new Error("NoExactFontFace:test");
      }
      globalThis.__TiqianExactFontShapeCount += 1;
      const handle = nextHandle++;
      const glyphs = [];
      let glyphIndex = 0;
      for (const _point of displayText) {
        glyphs.push({
          id: 100 + glyphIndex,
          advance: fontSize,
          x: glyphIndex * fontSize,
          y: 0,
          bounds: [0, -fontSize * 0.88, fontSize, fontSize * 0.12],
        });
        glyphIndex++;
      }
      const features = role === "LatinText" && /[‘’“”]/u.test(displayText)
        ? ["pwid", "palt"]
        : [];
      shapes.set(handle, {
        glyphs,
        advance: glyphs.length * fontSize,
        features,
        faceId: varyFaceByText ? `Fixture CJK:${displayText}` : "Fixture CJK",
      });
      return handle;
    },
    shapeGlyphCount: (handle) => shapes.get(handle).glyphs.length,
    shapeGlyphId: (handle, index) => shapes.get(handle).glyphs[index].id,
    shapeGlyphAdvance: (handle, index) => shapes.get(handle).glyphs[index].advance,
    shapeGlyphX: (handle, index) => shapes.get(handle).glyphs[index].x,
    shapeGlyphY: (handle, index) => shapes.get(handle).glyphs[index].y,
    shapeGlyphBound: (handle, index, edge) => shapes.get(handle).glyphs[index].bounds[edge],
    shapeAdvance: (handle) => shapes.get(handle).advance,
    shapeFaceId: (handle) => shapes.get(handle).faceId,
    shapeFontInstanceId: () => "fixture:0:default",
    shapeScript: () => "Hani",
    shapeFeatureCount: (handle) => shapes.get(handle).features.length,
    shapeFeature: (handle, index) => shapes.get(handle).features[index],
    shapeUnsafeBreakCount: () => 0,
    releaseShape: (handle) => shapes.delete(handle),
    metrics(_session, families, fontSize) {
      if (failShaping || (failFamily && String(families).includes(failFamily))) {
        globalThis.__TiqianExactFontFallbackCount += 1;
        throw new Error("NoExactFontFace:test");
      }
      const handle = nextHandle++;
      metrics.set(handle, [fontSize, fontSize * 0.25, 0, fontSize * 0.88, fontSize * 0.12]);
      return handle;
    },
    metricValue: (handle, index) => metrics.get(handle)[index],
    releaseMetrics: (handle) => metrics.delete(handle),
  };
  globalThis.__TiqianPreparedDomRenderer = {
    render(host, planJson, locale, options = {}) {
      if (failShaping) throw new Error("Exact renderer must not run after shaping failure");
      globalThis.__TiqianExactPreparedRenderCount += 1;
      globalThis.__TiqianExactPreparedPlan = planJson;
      if (options.semanticReplay === "live-source") {
        const sourceText = String(options.sourceText || "");
        const semantics = Array.from(options.semantics || []);
        const sourceElements = Array.from(options.liveSemanticElements || []);
        host.replaceChildren();
        const roots = [];
        const stack = [];
        for (const semantic of semantics) {
          while (stack.length > 0 && semantic.start >= stack.at(-1).end) stack.pop();
          const node = { ...semantic, children: [] };
          const parent = stack.at(-1);
          if (parent) {
            if (semantic.end > parent.end) throw new Error("CrossingLiveSemanticRanges");
            parent.children.push(node);
          } else {
            roots.push(node);
          }
          stack.push(node);
        }
        const appendRange = (container, start, end, children) => {
          let offset = start;
          for (const semantic of children) {
            if (semantic.start > offset) {
              container.appendChild(globalThis.document.createTextNode(sourceText.slice(offset, semantic.start)));
            }
            const source = sourceElements[semantic.sourceIndex];
            if (!source) throw new Error(`MissingLiveSemanticSource:${semantic.sourceIndex}`);
            const clone = source.cloneNode(false);
            clone.setAttribute("data-tq-source-semantic", "true");
            appendRange(clone, semantic.start, semantic.end, semantic.children);
            container.appendChild(clone);
            offset = semantic.end;
          }
          if (offset < end) {
            container.appendChild(globalThis.document.createTextNode(sourceText.slice(offset, end)));
          }
        };
        appendRange(host, 0, sourceText.length, roots);
        return {};
      }
      host.innerHTML = `<span data-tq-exact-rendered="${locale}"></span>`;
      return {};
    },
  };
  globalThis.__TiqianPreparedDomValidator = { issue: () => null };
}

export function clearExactFontSessionFixture() {
  delete globalThis.__TiqianFontBackend;
  delete globalThis.__TiqianPreparedDomRenderer;
  delete globalThis.__TiqianPreparedDomValidator;
  delete globalThis.__TiqianLayoutWorker;
  delete globalThis.__TiqianExactPreparedPlan;
  delete globalThis.__TiqianExactPreparedRenderCount;
  delete globalThis.__TiqianExactFontShapeCount;
  delete globalThis.__TiqianExactFontFallbackCount;
}

export const PROGRESSIVE_TIER_COUNT = 3;

export function attachWorker(root) {
  globalThis.TiqianWeb.workerAttach(root);
}

export function grantWorkerSlice(root, deadlineMs = 0) {
  const controller = testGrantController(
    root,
    globalThis.TiqianWeb.workerJobGeneration(root),
    deadlineMs,
    Number.MAX_SAFE_INTEGER,
  );
  return globalThis.TiqianWeb.workerRunSlice(controller, PROGRESSIVE_TIER_COUNT);
}

export function runWorkerJobToCompletion(root, deadlineMs = 0) {
  let slices = 0;
  while (globalThis.TiqianWeb.workerHasJob(root)) {
    grantWorkerSlice(root, deadlineMs);
    slices += 1;
    if (slices > 1000) throw new Error("attached worker job did not settle");
  }
  return slices;
}

export function testOptions() {
  return { fontSize: 18, lineHeight: 30 };
}

export function exactTestOptions() {
  return {
    paragraphSelector: "p[data-tq-snapshot-key]",
    exactFontSession: {
      status: "conforming",
      sessionId: "fixture-exact-session",
      detail: "test",
    },
  };
}

let runtimePromise;

export function loadHostRuntime() {
  buildWorld();
  runtimePromise ??= import("./runtime/tiqian-web.js").then((module) => {
    const facade = module.TiqianWebWorkers ??
      module.default?.TiqianWebWorkers ??
      globalThis.web?.TiqianWebWorkers;
    const workers = facade?.getInstance?.();
    const bridge = globalThis.TiqianWeb;
    if (workers && bridge) {
      bridge.workerAttach = workers.attach.bind(workers);
      bridge.workerDetach = workers.detach.bind(workers);
      bridge.workerHasJob = workers.hasJob.bind(workers);
      bridge.workerJobGeneration = workers.jobGeneration.bind(workers);
      bridge.workerRunSlice = workers.runSlice.bind(workers);
      bridge.workerPendingInTier = workers.pendingInTier.bind(workers);
      bridge.workerParagraphCount = workers.paragraphCount.bind(workers);
      bridge.workerParagraphAt = workers.paragraphAt.bind(workers);
      bridge.workerSetParagraphTier = workers.setParagraphTier.bind(workers);
    }
    if (bridge) {
      bridge.install ??= () => {};
      const rawEnhance = bridge.enhance.bind(bridge);
      bridge.enhance = (root, options) => {
        rawEnhance(root, options);
        const count = root.getAttribute("data-tiqian-enhanced-count");
        return count != null ? Number(count) : 0;
      };
    }
    return bridge;
  });
  return runtimePromise;
}

const mounted = [];

export function mount(html) {
  buildWorld();
  const wrapper = new HostElement("div");
  wrapper.ownerDocument = globalThis.document;
  wrapper.innerHTML = html;
  const root = wrapper.firstElementChild;
  if (!root) throw new Error("mount: markup has no root element");
  root.style.setProperty("--tq-styles-ready", "1");
  globalThis.document.body.appendChild(root);
  mounted.push(root);
  return root;
}

export async function cleanupMounted() {
  const bridge = globalThis.TiqianWeb;
  for (const root of mounted) {
    try {
      bridge?.destroy?.(root);
    } catch {}
    try {
      bridge?.workerDetach?.(root);
    } catch {}
    root.parentNode?.removeChild?.(root);
  }
  mounted.length = 0;
  clearExactFontSessionFixture();
  restoreTestAnimationFrames();
  cleanupWorld();
}

export async function drainMicrotasks(times = 6) {
  for (let i = 0; i < times; i += 1) {
    await new Promise((resolve) => setImmediate(resolve));
  }
}
