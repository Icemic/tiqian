import { globalServices } from "@tiqian/core/core/services/global-services.js";
// Fake host environment for driving Kotlin/JS runtime under Node.js test runner.
// Node does not provide rAF or DOM; the fake clock and DOM doubles below provide
// stable and synchronous execution for raw-DOM backup relayout and destruction tests.

import assert from "node:assert/strict";
import {
  FakeElement,
  FakeFragment,
  FakeNode,
  FakeText,
  fixtureComputedStyle,
} from "./snapshot-dom-fixtures.mjs";
import {
  enhance,
  enhanceProgressively,
  enhanceProgressivelyFromCanonical,
  relayout,
} from "@tiqian/core/core/engine/progressive-drivers.js";
import { destroyRoot, detachRoot, optionsFromJs } from "@tiqian/core/core/engine/lifecycle.js";
import { probeRootContentDrift, reconcileRoot } from "@tiqian/core/core/engine/content-reconcile.js";
import { workerLayoutRequestForRoot } from "@tiqian/core/core/engine/worker-request.js";
import { createRootState } from "@tiqian/core/core/engine/root-state.js";

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

export class FakeClipboardEvent extends FakeEvent {
  constructor(type, init = {}) {
    super(type, init);
    this.clipboardData = init.clipboardData ?? null;
  }
}

export class FakeDataTransfer {
  constructor() {
    this._data = {};
  }

  setData(type, value) {
    this._data[type] = String(value);
  }

  getData(type) {
    return this._data[type] ?? "";
  }
}

export class FakeSelection {
  constructor() {
    this._ranges = [];
  }

  get rangeCount() {
    return this._ranges.length;
  }

  get isCollapsed() {
    if (this._ranges.length === 0) return true;
    const r = this._ranges[0];
    return r.startContainer === r.endContainer && r.startOffset === r.endOffset;
  }

  getRangeAt(index) {
    return this._ranges[index] ?? null;
  }

  removeAllRanges() {
    this._ranges = [];
  }

  addRange(range) {
    this._ranges.push(range);
  }

  toString() {
    if (this._ranges.length === 0) return "";
    return this._ranges.map((r) => r.toString()).join("");
  }
}

function splitSelectorList(selector) {
  const parts = [];
  let curr = "";
  let parenDepth = 0;
  let bracketDepth = 0;
  let quote = null;
  for (let i = 0; i < selector.length; i++) {
    const ch = selector[i];
    if (quote) {
      if (ch === quote) quote = null;
      curr += ch;
    } else if (ch === '"' || ch === "'") {
      quote = ch;
      curr += ch;
    } else if (ch === "(") {
      parenDepth++;
      curr += ch;
    } else if (ch === ")") {
      if (parenDepth > 0) parenDepth--;
      curr += ch;
    } else if (ch === "[") {
      bracketDepth++;
      curr += ch;
    } else if (ch === "]") {
      if (bracketDepth > 0) bracketDepth--;
      curr += ch;
    } else if (ch === "," && parenDepth === 0 && bracketDepth === 0) {
      if (curr.trim()) parts.push(curr.trim());
      curr = "";
    } else {
      curr += ch;
    }
  }
  if (curr.trim()) parts.push(curr.trim());
  return parts;
}

function splitByWhitespace(selector) {
  const parts = [];
  let curr = "";
  let parenDepth = 0;
  let bracketDepth = 0;
  let quote = null;
  for (let i = 0; i < selector.length; i++) {
    const ch = selector[i];
    if (quote) {
      if (ch === quote) quote = null;
      curr += ch;
    } else if (ch === '"' || ch === "'") {
      quote = ch;
      curr += ch;
    } else if (ch === "(") {
      parenDepth++;
      curr += ch;
    } else if (ch === ")") {
      if (parenDepth > 0) parenDepth--;
      curr += ch;
    } else if (ch === "[") {
      bracketDepth++;
      curr += ch;
    } else if (ch === "]") {
      if (bracketDepth > 0) bracketDepth--;
      curr += ch;
    } else if (/\s/.test(ch) && parenDepth === 0 && bracketDepth === 0) {
      if (curr) parts.push(curr);
      curr = "";
    } else {
      curr += ch;
    }
  }
  if (curr) parts.push(curr);
  return parts;
}

function splitByCombinator(selector, combinator) {
  const parts = [];
  let curr = "";
  let parenDepth = 0;
  let bracketDepth = 0;
  let quote = null;
  for (let i = 0; i < selector.length; i++) {
    const ch = selector[i];
    if (quote) {
      if (ch === quote) quote = null;
      curr += ch;
    } else if (ch === '"' || ch === "'") {
      quote = ch;
      curr += ch;
    } else if (ch === "(") {
      parenDepth++;
      curr += ch;
    } else if (ch === ")") {
      if (parenDepth > 0) parenDepth--;
      curr += ch;
    } else if (ch === "[") {
      bracketDepth++;
      curr += ch;
    } else if (ch === "]") {
      if (bracketDepth > 0) bracketDepth--;
      curr += ch;
    } else if (ch === combinator && parenDepth === 0 && bracketDepth === 0) {
      if (curr.trim()) parts.push(curr.trim());
      curr = "";
    } else {
      curr += ch;
    }
  }
  if (curr.trim()) parts.push(curr.trim());
  return parts;
}

function matchesHostSelector(element, selector, scopeElement = null) {
  if (!element || element.nodeType !== 1) return false;
  if (selector === "*") return true;
  const parts = splitSelectorList(selector);
  if (parts.length > 1) {
    return parts.some((p) => matchesHostSelector(element, p, scopeElement));
  }

  if (selector === ":scope") {
    return element === scopeElement;
  }

  if (selector.includes(">")) {
    const segments = splitByCombinator(selector, ">");
    if (segments.length > 1) {
      let curr = element;
      for (let i = segments.length - 1; i >= 0; i--) {
        if (!curr || curr.nodeType !== 1) return false;
        const seg = segments[i];
        if (seg === ":scope") {
          if (curr !== scopeElement) return false;
        } else if (!matchesCompound(curr, seg)) {
          return false;
        }
        curr = curr.parentElement;
      }
      return true;
    }
  }

  const spaceTokens = splitByWhitespace(selector);
  if (spaceTokens.length > 1) {
    const targetSelector = spaceTokens[spaceTokens.length - 1];
    if (!matchesCompound(element, targetSelector)) return false;
    let ancestor = element.parentElement;
    let tokenIndex = spaceTokens.length - 2;
    while (ancestor && tokenIndex >= 0) {
      if (spaceTokens[tokenIndex] === ":scope") {
        if (ancestor !== scopeElement) return false;
        tokenIndex -= 1;
      } else if (matchesCompound(ancestor, spaceTokens[tokenIndex])) {
        tokenIndex -= 1;
      }
      ancestor = ancestor.parentElement;
    }
    return tokenIndex < 0;
  }

  return matchesCompound(element, selector);
}

function matchesCompound(element, selector) {
  if (!element || element.nodeType !== 1) return false;
  if (selector === "*") return true;

  if (selector.startsWith(":is(") && selector.endsWith(")")) {
    const inside = selector.slice(4, -1);
    const subSelectors = splitSelectorList(inside);
    return subSelectors.some((sub) => matchesHostSelector(element, sub));
  }

  let s = selector.trim();
  const tagMatch = /^([a-zA-Z0-9_-]+)/.exec(s);
  if (tagMatch) {
    if (element.tagName !== tagMatch[1].toUpperCase()) return false;
    s = s.slice(tagMatch[0].length);
  } else if (s.startsWith("*")) {
    s = s.slice(1);
  }

  while (s.length > 0) {
    if (s.startsWith(":not(")) {
      let depth = 0;
      let endIdx = -1;
      for (let i = 4; i < s.length; i++) {
        if (s[i] === "(") depth++;
        else if (s[i] === ")") {
          depth--;
          if (depth === 0) {
            endIdx = i;
            break;
          }
        }
      }
      if (endIdx < 0) return false;
      const negated = s.slice(5, endIdx).trim();
      if (matchesHostSelector(element, negated)) return false;
      s = s.slice(endIdx + 1);
    } else if (s.startsWith(":is(")) {
      let depth = 0;
      let endIdx = -1;
      for (let i = 3; i < s.length; i++) {
        if (s[i] === "(") depth++;
        else if (s[i] === ")") {
          depth--;
          if (depth === 0) {
            endIdx = i;
            break;
          }
        }
      }
      if (endIdx < 0) return false;
      const inside = s.slice(4, endIdx).trim();
      const subSelectors = splitSelectorList(inside);
      if (!subSelectors.some((sub) => matchesHostSelector(element, sub))) return false;
      s = s.slice(endIdx + 1);
    } else if (s.startsWith("#")) {
      const idMatch = /^#([a-zA-Z0-9_-]+)/.exec(s);
      if (!idMatch) return false;
      if (element.getAttribute("id") !== idMatch[1]) return false;
      s = s.slice(idMatch[0].length);
    } else if (s.startsWith(".")) {
      const classMatch = /^\.([a-zA-Z0-9_-]+)/.exec(s);
      if (!classMatch) return false;
      const classes = (element.getAttribute("class") || "").trim().split(/\s+/);
      if (!classes.includes(classMatch[1])) return false;
      s = s.slice(classMatch[0].length);
    } else if (s.startsWith("[")) {
      const attrMatch = /^\[([a-zA-Z0-9_-]+)(?:([~|^$*]?=)(?:"([^"]*)"|'([^']*)'|([^\]]+)))?\]/.exec(s);
      if (!attrMatch) return false;
      const [, attrName, op, dblVal, sglVal, rawVal] = attrMatch;
      if (!element.hasAttribute(attrName)) return false;
      const expectedVal = dblVal ?? sglVal ?? rawVal;
      if (op && expectedVal != null) {
        const actualVal = element.getAttribute(attrName);
        if (op === "=" && actualVal !== expectedVal) return false;
        if (op === "~=" && !actualVal.split(/\s+/).includes(expectedVal)) return false;
        if (op === "|=" && actualVal !== expectedVal && !actualVal.startsWith(expectedVal + "-")) return false;
        if (op === "^=" && !actualVal.startsWith(expectedVal)) return false;
        if (op === "$=" && !actualVal.endsWith(expectedVal)) return false;
        if (op === "*=" && !actualVal.includes(expectedVal)) return false;
      }
      s = s.slice(attrMatch[0].length);
    } else {
      return false;
    }
  }

  return true;
}

// Browsers expand padding/margin shorthands into longhands in the CSSOM.
// The fake host parses declarations on demand, so longhand queries must
// resolve through a present shorthand with the standard 1-4 value box
// rules; expansion happens in declaration order so a later longhand still
// overrides an earlier shorthand and vice versa, as in the cascade.
const BOX_SHORTHANDS = new Map([
  ["padding", ["padding-top", "padding-right", "padding-bottom", "padding-left"]],
  ["margin", ["margin-top", "margin-right", "margin-bottom", "margin-left"]],
]);

function expandBoxShorthand(prop, value) {
  const sides = BOX_SHORTHANDS.get(prop);
  if (!sides) return null;
  const parts = String(value).trim().split(/\s+/);
  if (parts.length < 1 || parts.length > 4 || parts.some((part) => !part)) return null;
  const top = parts[0];
  const right = parts.length > 1 ? parts[1] : top;
  const bottom = parts.length > 2 ? parts[2] : top;
  const left = parts.length > 3 ? parts[3] : right;
  return [
    [sides[0], top],
    [sides[1], right],
    [sides[2], bottom],
    [sides[3], left],
  ];
}

function parseStyleDeclarations(text, map, priorities) {
  map.clear();
  priorities.clear();
  if (!text) return;
  const cleaned = String(text).replace(/\/\*[\s\S]*?\*\//g, "");
  const decls = cleaned.split(";");
  for (const decl of decls) {
    const idx = decl.indexOf(":");
    if (idx > 0) {
      const k = decl.slice(0, idx).trim().toLowerCase();
      let v = decl.slice(idx + 1).trim();
      if (!k || !v) continue;
      let priority = "";
      const importantMatch = /\s*!\s*important\s*$/i.exec(v);
      if (importantMatch) {
        priority = "important";
        v = v.slice(0, importantMatch.index).trim();
      }
      map.set(k, v);
      if (priority) {
        priorities.set(k, priority);
      }
      const expanded = expandBoxShorthand(k, v);
      if (expanded) {
        for (const [long, longValue] of expanded) {
          map.set(long, longValue);
          if (priority) {
            priorities.set(long, priority);
          }
        }
      }
    }
  }
}

// Browsers keep the style attribute verbatim across setAttribute and mirror
// it into the declaration lazily; the attribute string is only rewritten
// (normalized) after a declaration-side mutation, which merges over the
// attribute content. The fake follows the same shape so engine-written
// style strings round-trip byte-identical until code mutates .style.
const STYLE_ATTR_STALE = Symbol("styleAttrStale");

function createStyleObject(element) {
  const map = new Map();
  const priorities = new Map();
  let syncedFromAttr = false;
  const ensureSynced = () => {
    if (syncedFromAttr) return;
    syncedFromAttr = true;
    const attr = element?.attributes?.get?.("style");
    if (typeof attr === "string" && attr) {
      parseStyleDeclarations(attr, map, priorities);
    }
  };
  return new Proxy({}, {
    get(target, prop) {
      if (prop === STYLE_ATTR_STALE) {
        return () => { syncedFromAttr = false; };
      }
      if (prop === "setProperty") {
        return (name, value, priority = "") => {
          ensureSynced();
          const kebab = String(name).trim().toLowerCase();
          if (value == null || value === "") {
            map.delete(kebab);
            priorities.delete(kebab);
          } else {
            map.set(kebab, String(value));
            if (priority && String(priority).toLowerCase() === "important") {
              priorities.set(kebab, "important");
            } else if (priority) {
              priorities.set(kebab, String(priority));
            } else {
              priorities.delete(kebab);
            }
          }
          updateStyleAttr();
        };
      }
      if (prop === "getPropertyValue") {
        return (name) => {
          ensureSynced();
          const kebab = String(name).trim().toLowerCase();
          return map.get(kebab) ?? "";
        };
      }
      if (prop === "getPropertyPriority") {
        return (name) => {
          ensureSynced();
          const kebab = String(name).trim().toLowerCase();
          return priorities.get(kebab) ?? "";
        };
      }
      if (prop === "removeProperty") {
        return (name) => {
          ensureSynced();
          const kebab = String(name).trim().toLowerCase();
          const old = map.get(kebab) ?? "";
          map.delete(kebab);
          priorities.delete(kebab);
          updateStyleAttr();
          return old;
        };
      }
      if (prop === "length") {
        ensureSynced();
        return map.size;
      }
      if (prop === "item") {
        return (index) => {
          ensureSynced();
          return Array.from(map.keys())[index] ?? "";
        };
      }
      if (prop === "cssText") {
        ensureSynced();
        return Array.from(map.entries()).map(([k, v]) => {
          const prio = priorities.get(k);
          return prio ? `${k}: ${v} !${prio}` : `${k}: ${v}`;
        }).join("; ");
      }
      if (typeof prop === "symbol" && prop === Symbol.iterator) {
        return function* () {
          ensureSynced();
          yield* map.keys();
        };
      }
      if (typeof prop === "string" && /^\d+$/.test(prop)) {
        const idx = Number(prop);
        ensureSynced();
        return Array.from(map.keys())[idx] ?? undefined;
      }
      if (typeof prop === "string") {
        const kebab = prop.replace(/[A-Z]/g, (m) => `-${m.toLowerCase()}`);
        ensureSynced();
        return map.get(kebab) ?? map.get(prop) ?? "";
      }
      return Reflect.get(target, prop);
    },
    set(target, prop, value) {
      if (prop === "cssText") {
        parseStyleDeclarations(value, map, priorities);
        updateStyleAttr();
        return true;
      }
      if (typeof prop === "string") {
        ensureSynced();
        const kebab = prop.replace(/[A-Z]/g, (m) => `-${m.toLowerCase()}`);
        if (value == null || value === "") {
          map.delete(kebab);
          map.delete(prop);
          priorities.delete(kebab);
          priorities.delete(prop);
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
    syncedFromAttr = true;
    if (map.size === 0) {
      element.attributes.delete("style");
    } else {
      const text = Array.from(map.entries()).map(([k, v]) => {
        const prio = priorities.get(k);
        return prio ? `${k}: ${v} !${prio}` : `${k}: ${v}`;
      }).join("; ");
      element.attributes.set("style", text);
    }
  }
}

const stylesheetRules = new Map();
const parsedStylesheetRules = [];
const parsedPseudoRules = [];

function parseDeclarationBlock(declBlock) {
  const map = new Map();
  const decls = declBlock.split(";");
  for (const decl of decls) {
    const colonIdx = decl.indexOf(":");
    if (colonIdx > 0) {
      const prop = decl.slice(0, colonIdx).trim().toLowerCase();
      let val = decl.slice(colonIdx + 1).trim();
      if (!prop || !val) continue;
      let important = false;
      const importantMatch = /\s*!\s*important\s*$/i.exec(val);
      if (importantMatch) {
        important = true;
        val = val.slice(0, importantMatch.index).trim();
      }
      map.set(prop, { value: val, important });
      if (prop === "padding-inline-start" && !map.has("padding-left")) {
        map.set("padding-left", { value: val, important });
      }
      if (prop === "margin-inline") {
        if (!map.has("margin-left")) map.set("margin-left", { value: val, important });
        if (!map.has("margin-right")) map.set("margin-right", { value: val, important });
      }
      const expanded = expandBoxShorthand(prop, val);
      if (expanded) {
        for (const [long, longValue] of expanded) {
          map.set(long, { value: longValue, important });
        }
      }
    }
  }
  return map;
}

function computeSpecificity(selector) {
  let a = 0;
  let b = 0;
  let c = 0;
  const tokens = splitByWhitespace(selector);
  for (const token of tokens) {
    if (!token || token === ">" || token === "+" || token === "~" || token === "*") continue;
    let s = token;
    const tagMatch = /^([a-zA-Z0-9_-]+)/.exec(s);
    if (tagMatch) {
      c++;
      s = s.slice(tagMatch[0].length);
    }
    while (s.length > 0) {
      if (s.startsWith("#")) {
        const idMatch = /^#([a-zA-Z0-9_-]+)/.exec(s);
        if (idMatch) {
          a++;
          s = s.slice(idMatch[0].length);
        } else {
          s = s.slice(1);
        }
      } else if (s.startsWith(".")) {
        const classMatch = /^\.([a-zA-Z0-9_-]+)/.exec(s);
        if (classMatch) {
          b++;
          s = s.slice(classMatch[0].length);
        } else {
          s = s.slice(1);
        }
      } else if (s.startsWith("[")) {
        const attrEnd = s.indexOf("]");
        if (attrEnd >= 0) {
          b++;
          s = s.slice(attrEnd + 1);
        } else {
          s = s.slice(1);
        }
      } else if (s.startsWith(":not(")) {
        let depth = 0;
        let endIdx = -1;
        for (let i = 4; i < s.length; i++) {
          if (s[i] === "(") depth++;
          else if (s[i] === ")") {
            depth--;
            if (depth === 0) {
              endIdx = i;
              break;
            }
          }
        }
        if (endIdx >= 0) {
          const inner = s.slice(5, endIdx).trim();
          const innerSpec = computeSpecificity(inner);
          a += innerSpec[0];
          b += innerSpec[1];
          c += innerSpec[2];
          s = s.slice(endIdx + 1);
        } else {
          s = s.slice(5);
        }
      } else if (s.startsWith(":")) {
        const pseudoMatch = /^:([a-zA-Z0-9_-]+)/.exec(s);
        if (pseudoMatch) {
          b++;
          s = s.slice(pseudoMatch[0].length);
        } else {
          s = s.slice(1);
        }
      } else {
        s = s.slice(1);
      }
    }
  }
  return [a, b, c];
}

function compareSpecificity(spec1, spec2, index1, index2) {
  for (let i = 0; i < 3; i++) {
    if (spec1[i] !== spec2[i]) return spec1[i] - spec2[i];
  }
  return index1 - index2;
}

function collectStylesheetRules(cssText) {
  if (!cssText) return;
  const cleaned = String(cssText).replace(/\/\*[\s\S]*?\*\//g, "");
  const ruleRegex = /([^{}]+)\{([^}]+)\}/g;
  let match;
  while ((match = ruleRegex.exec(cleaned)) !== null) {
    const rawSelectors = match[1].trim();
    const declBlock = match[2];
    const selectors = splitSelectorList(rawSelectors);
    const declsMap = parseDeclarationBlock(declBlock);
    for (const sel of selectors) {
      const trimmedSel = sel.trim();
      if (!trimmedSel) continue;
      const pseudoMatch = /::?(before|after)$/.exec(trimmedSel);
      if (pseudoMatch) {
        const baseSelector = trimmedSel.slice(0, pseudoMatch.index).trim();
        const pseudoKey = ":" + pseudoMatch[1];
        if (baseSelector) {
          parsedPseudoRules.push({
            selector: baseSelector,
            pseudo: pseudoKey,
            specificity: computeSpecificity(baseSelector),
            index: parsedPseudoRules.length,
            declarations: declsMap,
          });
        }
        continue;
      }
      const spec = computeSpecificity(trimmedSel);
      const ruleIndex = parsedStylesheetRules.length;
      parsedStylesheetRules.push({
        selector: trimmedSel,
        specificity: spec,
        index: ruleIndex,
        declarations: declsMap,
      });
      const tagMatch = /^[a-zA-Z0-9_-]+$/.exec(trimmedSel);
      if (tagMatch) {
        const tag = trimmedSel.toUpperCase();
        let tagRules = stylesheetRules.get(tag);
        if (!tagRules) {
          tagRules = new Map();
          stylesheetRules.set(tag, tagRules);
        }
        for (const [p, d] of declsMap) {
          tagRules.set(p, d.value);
        }
      }
    }
  }
}

function getStyleAttrProperty(element, name) {
  const styleAttr = element?.attributes?.get?.("style") ?? (typeof element?.getAttribute === "function" ? element.getAttribute("style") : null);
  if (!styleAttr || typeof styleAttr !== "string") return "";
  const kebab = name.toLowerCase();
  // Last matching declaration wins: a longhand after a shorthand must
  // override the shorthand's derived value, so every decl updates the
  // result and the scan runs to the end.
  let result = "";
  const decls = styleAttr.split(";");
  for (const decl of decls) {
    const idx = decl.indexOf(":");
    if (idx > 0) {
      const k = decl.slice(0, idx).trim().toLowerCase();
      let v = decl.slice(idx + 1).trim();
      if (!k || !v) continue;
      if (k === kebab) {
        result = v;
        continue;
      }
      if (k === "padding-inline-start" && kebab === "padding-left") {
        result = v;
        continue;
      }
      if (k === "margin-inline" && (kebab === "margin-left" || kebab === "margin-right")) {
        result = v;
        continue;
      }
      const expanded = expandBoxShorthand(k, v);
      if (expanded) {
        for (const [long, longValue] of expanded) {
          if (long === kebab) {
            result = longValue;
            break;
          }
        }
      }
    }
  }
  return result;
}

function getStylesheetProperty(element, kebab) {
  let bestImportant = null;
  let bestNormal = null;

  for (const rule of parsedStylesheetRules) {
    if (rule.declarations.has(kebab) || rule.declarations.has("all")) {
      if (matchesHostSelector(element, rule.selector)) {
        // A rule's explicit declaration for the property wins over its own
        // `all` shorthand; `all: unset` then only clears what the rule does
        // not set explicitly, mirroring declaration order inside a block.
        const decl = rule.declarations.has(kebab)
          ? rule.declarations.get(kebab)
          : allShorthandDecl(rule);
        if (!decl) continue;
        if (decl.important) {
          if (!bestImportant || compareSpecificity(rule.specificity, bestImportant.rule.specificity, rule.index, bestImportant.rule.index) >= 0) {
            bestImportant = { rule, decl };
          }
        } else {
          if (!bestNormal || compareSpecificity(rule.specificity, bestNormal.rule.specificity, rule.index, bestNormal.rule.index) >= 0) {
            bestNormal = { rule, decl };
          }
        }
      }
    }
  }

  if (bestImportant) return bestImportant.decl.value;
  if (bestNormal) return bestNormal.decl.value;
  return "";
}

// The `all` shorthand competes for every property. Only the CSS-wide reset
// keywords make sense on it in host stylesheets; other values (e.g. a font
// shorthand) do not expand per-property here and are ignored.
function allShorthandDecl(rule) {
  const decl = rule.declarations.get("all");
  if (!decl) return null;
  const value = String(decl.value).trim().toLowerCase();
  if (value !== "unset" && value !== "initial" && value !== "inherit" && value !== "revert") {
    return null;
  }
  return { ...decl, value: `__all_${value}__` };
}

function isAllResetValue(value) {
  return typeof value === "string" && /^__all_(unset|initial|inherit|revert)__$/.test(value);
}

function allResetKeyword(value) {
  const m = /^__all_(unset|initial|inherit|revert)__$/.exec(value);
  return m ? m[1] : null;
}

function getPseudoStylesheetProperty(element, kebab, pseudoKey) {
  let bestImportant = null;
  let bestNormal = null;

  for (const rule of parsedPseudoRules) {
    if (rule.pseudo === pseudoKey && rule.declarations.has(kebab)) {
      if (matchesHostSelector(element, rule.selector)) {
        const decl = rule.declarations.get(kebab);
        if (decl.important) {
          if (!bestImportant || compareSpecificity(rule.specificity, bestImportant.rule.specificity, rule.index, bestImportant.rule.index) >= 0) {
            bestImportant = { rule, decl };
          }
        } else {
          if (!bestNormal || compareSpecificity(rule.specificity, bestNormal.rule.specificity, rule.index, bestNormal.rule.index) >= 0) {
            bestNormal = { rule, decl };
          }
        }
      }
    }
  }

  if (bestImportant) return bestImportant.decl.value;
  if (bestNormal) return bestNormal.decl.value;
  return "";
}

function resolveElementPropertyRaw(element, property, base = {}) {
  if (!element || element.nodeType !== 1) return "";

  if (property.startsWith("--")) {
    for (let curr = element; curr; curr = curr.parentElement) {
      const v = curr.style?.getPropertyValue?.(property);
      if (v !== undefined && v !== "") return v;
      const attrV = getStyleAttrProperty(curr, property);
      if (attrV !== "") return attrV;
    }
    return "";
  }

  const kebab = property.toLowerCase();

  // 1. Element inline style Proxy map
  const inline = element.style?.getPropertyValue?.(kebab);
  if (inline !== undefined && inline !== "") return inline;

  // 2. Element style attribute string
  const attrVal = getStyleAttrProperty(element, kebab);
  if (attrVal !== "") return attrVal;

  // 3. Stylesheet rules (extended specificity-based rules)
  const sheetVal = getStylesheetProperty(element, kebab);
  if (isAllResetValue(sheetVal)) {
    // `all: unset` wins the cascade for this property: inherited properties
    // keep flowing from the ancestor chain, everything else resets to its
    // initial value (reported as the empty string here).
    const keyword = allResetKeyword(sheetVal);
    const inheritable = INHERITED_PROPERTIES.includes(kebab) || keyword === "inherit";
    if (inheritable && element.parentElement && element.parentElement.nodeType === 1) {
      const parentVal = resolveElementPropertyRaw(element.parentElement, kebab, base);
      if (parentVal !== "") return parentVal;
    }
    return "";
  }
  if (sheetVal !== "") return sheetVal;

  // 3b. Fallback tag rule if any
  const tagRule = stylesheetRules.get(element.tagName)?.get(kebab);
  if (tagRule !== undefined && tagRule !== "") return tagRule;

  // 4. Inherited properties
  if (INHERITED_PROPERTIES.includes(kebab) && element.parentElement && element.parentElement.nodeType === 1) {
    const parentVal = resolveElementPropertyRaw(element.parentElement, kebab, base);
    if (parentVal !== "") return parentVal;
  }

  return "";
}

const INHERITED_PROPERTIES = [
  "font-size", "font-weight", "font-family", "font-style",
  "line-height", "letter-spacing", "word-spacing", "color", "direction",
  "font-feature-settings", "white-space",
];

// Substitutes var(--custom[, fallback]) references against the element's
// custom-property chain before a computed value leaves the resolver, the
// same replacement a real engine performs at computed-value time.
function substituteCssVars(value, element, depth = 0) {
  if (typeof value !== "string" || !value.includes("var(") || depth > 4) return value;
  return value.replace(
    /var\(\s*(--[A-Za-z0-9_-]+)\s*(?:,\s*([^()]*?))?\s*\)/g,
    (_, name, fallback) => {
      const resolved = resolveElementPropertyRaw(element, name);
      if (resolved !== "") return resolved;
      return fallback !== undefined ? fallback.trim() : "";
    },
  );
}

// The currentColor keyword computes against the element's color chain; a
// browser reports the resolved used value for properties like fill/stroke.
function resolveCurrentColorKeyword(value, element, property) {
  if (typeof value !== "string" || value.trim() !== "currentColor") return value;
  if (property.toLowerCase() === "color") {
    return element?.parentElement
      ? resolveElementPropertyRaw(element.parentElement, "color")
      : value;
  }
  const color = resolveElementPropertyRaw(element, "color");
  return color !== "" ? color : value;
}

function resolveElementProperty(element, property, base = {}) {
  return resolveCurrentColorKeyword(
    substituteCssVars(resolveElementPropertyRaw(element, property, base), element),
    element,
    property,
  );
}

function getHorizontalPadding(element) {
  if (!element || element.nodeType !== 1) return { left: 0, right: 0 };
  const cs = globalThis.getComputedStyle?.(element);
  if (cs) {
    const pl = cs.getPropertyValue("padding-left") || cs.getPropertyValue("padding-inline-start");
    const pr = cs.getPropertyValue("padding-right") || cs.getPropertyValue("padding-inline-end");
    if (pl || pr) {
      return { left: Number.parseFloat(pl) || 0, right: Number.parseFloat(pr) || 0 };
    }
    const p = cs.getPropertyValue("padding");
    if (p) {
      const parts = String(p).trim().split(/\s+/);
      if (parts.length === 1) {
        const v = Number.parseFloat(parts[0]) || 0;
        return { left: v, right: v };
      }
      if (parts.length >= 2) {
        const v = Number.parseFloat(parts[1]) || 0;
        return { left: v, right: v };
      }
    }
  } else if (element.style) {
    const pl = element.style.getPropertyValue?.("padding-left") || element.style.paddingLeft;
    const pr = element.style.getPropertyValue?.("padding-right") || element.style.paddingRight;
    if (pl || pr) {
      return { left: Number.parseFloat(pl) || 0, right: Number.parseFloat(pr) || 0 };
    }
  }
  return { left: 0, right: 0 };
}

function getElementExplicitWidth(element) {
  if (!element || element.nodeType !== 1) return null;
  const inlineSize = element.style?.getPropertyValue?.("inline-size") || element.style?.inlineSize;
  if (inlineSize) {
    const parsed = Number.parseFloat(inlineSize);
    if (!Number.isNaN(parsed) && parsed > 0) return parsed;
  }
  const styleWidth = element.style?.getPropertyValue?.("width") || element.style?.width;
  if (styleWidth) {
    const parsed = Number.parseFloat(styleWidth);
    if (!Number.isNaN(parsed) && parsed > 0) return parsed;
  }
  const attrStyle = element.getAttribute?.("style");
  if (attrStyle) {
    const match = /(?:^|;)\s*width\s*:\s*(\d+(?:\.\d+)?)px/i.exec(attrStyle);
    if (match) {
      const parsed = Number.parseFloat(match[1]);
      if (!Number.isNaN(parsed) && parsed > 0) return parsed;
    }
  }
  if (element.width) return element.width;
  if (element.tagName === "IMG" || element.tagName === "SVG") {
    const attrW = element.getAttribute?.("width") ?? element.width;
    if (attrW != null) {
      const parsed = Number.parseFloat(attrW);
      if (!Number.isNaN(parsed) && parsed > 0) return parsed;
    }
  }
  return null;
}

function readExplicitHeight(element) {
  if (!element || element.nodeType !== 1) return null;
  const styleHeight = element.style?.getPropertyValue?.("height") || element.style?.height;
  if (styleHeight) {
    const parsed = Number.parseFloat(styleHeight);
    if (!Number.isNaN(parsed) && parsed > 0) return parsed;
  }
  const attrStyle = element.getAttribute?.("style");
  if (attrStyle) {
    const match = /(?:^|;)\s*height\s*:\s*(\d+(?:\.\d+)?)px/i.exec(attrStyle);
    if (match) {
      const parsed = Number.parseFloat(match[1]);
      if (!Number.isNaN(parsed) && parsed > 0) return parsed;
    }
  }
  if (element.tagName === "IMG" || element.tagName === "SVG") {
    const attrH = element.getAttribute?.("height") ?? element.height;
    if (attrH != null) {
      const parsed = Number.parseFloat(attrH);
      if (!Number.isNaN(parsed) && parsed > 0) return parsed;
    }
  }
  return null;
}

function getBlockWidth(element) {
  const selfExplicit = getElementExplicitWidth(element);
  if (selfExplicit != null) {
    const cs = globalThis.getComputedStyle?.(element);
    const boxSizing = cs?.getPropertyValue?.("box-sizing") || "content-box";
    if (boxSizing === "border-box") {
      return selfExplicit;
    }
    const selfPad = getHorizontalPadding(element);
    return selfExplicit + selfPad.left + selfPad.right;
  }

  let explicitWidth = 360;
  let widthAncestor = null;
  for (let curr = element.parentElement; curr; curr = curr.parentElement) {
    const ew = getElementExplicitWidth(curr);
    if (ew != null) {
      explicitWidth = ew;
      widthAncestor = curr;
      break;
    }
  }

  let available = explicitWidth;
  if (widthAncestor) {
    for (let curr = element.parentElement; curr; curr = curr.parentElement) {
      const pad = getHorizontalPadding(curr);
      available -= (pad.left + pad.right);
      if (curr === widthAncestor) break;
    }
  }

  return Math.max(0, available);
}

function findMultiColumnAncestor(element) {
  for (let curr = element.parentElement; curr; curr = curr.parentElement) {
    const styleAttr = curr.getAttribute?.("style") || "";
    const inlineCol = curr.style?.getPropertyValue?.("columns") || curr.style?.columns;
    const combined = `${styleAttr}; columns: ${inlineCol || ""}`;
    const colMatch = /columns\s*:\s*(\d+(?:\.\d+)?)px(?:\s+auto)?/i.exec(combined);
    if (colMatch) {
      const colWidth = Number.parseFloat(colMatch[1]);
      let colGap = 0;
      const gapMatch = /column-gap\s*:\s*(\d+(?:\.\d+)?)px/i.exec(combined) ||
        curr.style?.getPropertyValue?.("column-gap");
      if (gapMatch) {
        colGap = typeof gapMatch === "string" ? cssPx(gapMatch) : Number.parseFloat(gapMatch[1]);
      }
      let colHeight = 120;
      const heightMatch = /height\s*:\s*(\d+(?:\.\d+)?)px/i.exec(combined) ||
        curr.style?.getPropertyValue?.("height");
      if (heightMatch) {
        colHeight = typeof heightMatch === "string" ? cssPx(heightMatch) : Number.parseFloat(heightMatch[1]);
      }
      return {
        ancestor: curr,
        colWidth,
        colGap,
        colHeight,
      };
    }
  }
  return null;
}

function findContentSizedAncestor(element) {
  for (let curr = element; curr; curr = curr.parentElement) {
    const display = curr.style?.getPropertyValue?.("display") || curr.style?.display || "";
    if (["inline-block", "inline-flex", "inline-grid", "flex", "grid"].includes(display) || curr.tagName === "FIGURE") {
      return curr;
    }
  }
  return null;
}

export class FakeAttributesMap extends Map {
  * [Symbol.iterator]() {
    for (const [k, v] of super[Symbol.iterator]()) {
      const item = [k, v];
      item.name = k;
      item.value = v;
      yield item;
    }
  }

  entries() {
    return this[Symbol.iterator]();
  }
}

export class HostElement extends FakeElement {
  constructor(tagName) {
    super(tagName);
    this.attributes = new FakeAttributesMap();
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

  get clientWidth() {
    return this._clientWidth ?? this.getBoundingClientRect().width;
  }

  get scrollWidth() {
    // Overflow check for emergency-break fixtures: the widest run of inline
    // content between engine line markers versus the content box. The advance
    // model mirrors the engine's declared line widths, so a proper emergency
    // break keeps this within clientWidth.
    const pad = getHorizontalPadding(this);
    const widest = widestInlineSegment(this);
    return widest > 0 ? widest + pad.left + pad.right
      : (this._clientWidth ?? this.getBoundingClientRect().width);
  }

  set clientWidth(v) {
    this._clientWidth = Number(v);
  }

  get clientHeight() {
    return this._clientHeight ?? this.getBoundingClientRect().height;
  }

  set clientHeight(v) {
    this._clientHeight = Number(v);
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

  get className() {
    return this.getAttribute("class") || "";
  }

  set className(value) {
    if (value == null || value === "") {
      this.removeAttribute("class");
    } else {
      this.setAttribute("class", String(value));
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
      // Wholesale attribute write: keep the string verbatim and mark the
      // declaration mirror stale; the next .style access reparses it.
      this.style[STYLE_ATTR_STALE]();
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
    let curr = this;
    while (curr) {
      event.currentTarget = curr;
      const set = curr.listeners?.get(event.type);
      if (set) {
        for (const listener of Array.from(set)) {
          listener(event);
        }
      }
      if (!event.bubbles) break;
      curr = curr.parentElement;
    }
    if (event.bubbles && this.ownerDocument) {
      event.currentTarget = this.ownerDocument;
      const docSet = this.ownerDocument.listeners?.get(event.type);
      if (docSet) {
        for (const listener of Array.from(docSet)) {
          listener(event);
        }
      }
    }
    return !event.defaultPrevented;
  }

  matches(selector) {
    return matchesHostSelector(this, selector);
  }

  closest(selector) {
    for (let node = this; node; node = node.parentElement) {
      if (node.nodeType === 1 && matchesHostSelector(node, selector)) return node;
    }
    return null;
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
      const inlineSize = this.style?.getPropertyValue?.("inline-size") || this.style?.inlineSize;
      if (inlineSize) {
        const parsed = Number.parseFloat(inlineSize);
        if (!Number.isNaN(parsed) && parsed > 0) {
          w = parsed;
        }
      }
    }
    if (!w) {
      const explicit = getElementExplicitWidth(this);
      if (explicit != null) w = explicit;
    }
    if (!w) {
      const multiCol = findMultiColumnAncestor(this);
      if (multiCol) {
        w = getBlockWidth(multiCol.ancestor);
      }
    }
    if (!w) {
      const isInline = [
        "STRONG", "SPAN", "EM", "A", "B", "I", "U", "MARK", "SMALL",
        "SUB", "SUP", "CODE", "KBD", "SAMP", "VAR", "TIME", "DATA",
        "RUBY", "RT", "RP", "BDI", "BDO", "ABBR", "Q", "CITE", "DEL", "SPOILER",
      ].includes(this.tagName);
      if (isInline) {
        const pad = getHorizontalPadding(this);
        let inner = 0;
        for (const child of this.childNodes) {
          inner += inlineContentAdvance(child);
        }
        const beforeAdv = pseudoContentAdvance(this, "::before");
        const afterAdv = pseudoContentAdvance(this, "::after");
        w = pad.left + beforeAdv + inner + afterAdv + pad.right;
      } else {
        const isContentSized = Boolean(findContentSizedAncestor(this));

        if (isContentSized) {
          const pad = getHorizontalPadding(this);
          const textLen = (this.textContent || "").length;
          let availableWidth = 360;
          for (let curr = this.parentElement; curr; curr = curr.parentElement) {
            if (curr.tagName === "FIGURE") {
              for (const child of curr.childNodes) {
                if (child.nodeType === 1) {
                  const ew = getElementExplicitWidth(child);
                  if (ew != null && ew > 0) {
                    availableWidth = ew;
                    break;
                  }
                }
              }
            }
            const sw = curr.style?.getPropertyValue?.("width") || curr.style?.width;
            if (sw) {
              const p = Number.parseFloat(sw);
              if (!Number.isNaN(p) && p > 0) {
                availableWidth = p;
                break;
              }
            }
          }
          if (textLen === 0) {
            w = 0;
          } else {
            const cs = globalThis.getComputedStyle?.(this);
            const fontSize = (cs ? cssPx(cs.getPropertyValue("font-size")) : 18) || 18;
            w = Math.min(availableWidth, pad.left + textLen * fontSize + pad.right);
          }
        } else {
          w = getBlockWidth(this);
        }
      }
    }
    if (w == null || (w === 0 && (this.textContent || "").length > 0)) w = 360;
    const explicitHeight = readExplicitHeight(this);
    const cs = globalThis.getComputedStyle?.(this);
    const marginLeft = cs ? cssPx(cs.getPropertyValue("margin-left")) : 0;
    const isInline = [
      "STRONG", "SPAN", "EM", "A", "B", "I", "U", "MARK", "SMALL",
      "SUB", "SUP", "CODE", "KBD", "SAMP", "VAR", "TIME", "DATA",
      "RUBY", "RT", "RP", "BDI", "BDO", "ABBR", "Q", "CITE", "DEL", "SPOILER",
    ].includes(this.tagName);
    const left = isInline ? (inlineStartOffset(this) + marginLeft) : (this.left || 0);
    return new FakeDOMRect(left, this.top ?? 0, w, explicitHeight != null ? explicitHeight : (this.height || 30));
  }

  getClientRects() {
    const multiCol = findMultiColumnAncestor(this);
    if (multiCol) {
      const W = multiCol.colWidth;
      const G = multiCol.colGap;
      const H = multiCol.colHeight;
      const cs = globalThis.getComputedStyle?.(this);
      const fontSize = (cs ? cssPx(cs.getPropertyValue("font-size")) : 18) || 18;
      const lineHeight = (cs ? cssPx(cs.getPropertyValue("line-height")) : 30) || 30;
      const linesPerCol = Math.max(1, Math.floor(H / lineHeight));
      const charsPerLine = Math.max(1, Math.floor(W / fontSize));
      const capacityPerCol = linesPerCol * charsPerLine;
      const totalChars = (this.textContent || "").length;
      const numCols = Math.max(1, Math.ceil(totalChars / capacityPerCol));
      const rects = [];
      for (let i = 0; i < numCols; i++) {
        rects.push(new FakeDOMRect(i * (W + G), 0, W, H));
      }
      return rects;
    }
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
        if (child.nodeType === 1 && matchesHostSelector(child, selector, this)) {
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
    clone.attributes = new FakeAttributesMap(this.attributes);
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
    const isVoid = ["br", "hr", "img", "input", "link", "meta"].includes(tag);
    if (isVoid) {
      return `<${tag}${attrs}>`;
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

function decodeHtmlEntities(str) {
  if (!str || typeof str !== "string") return str;
  return str
    .replace(/&#(\d+);/g, (_, num) => String.fromCharCode(Number(num)))
    .replace(/&#x([0-9a-fA-F]+);/g, (_, hex) => String.fromCharCode(Number.parseInt(hex, 16)))
    .replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&amp;/g, "&");
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
        const parent = stack[stack.length - 1];
        if (parent?.tagName === "STYLE") {
          collectStylesheetRules(text);
        }
        const textNode = new FakeText(text);
        parent.appendChild(textNode);
      }
    } else if (full.startsWith("</")) {
      const closingTag = (tagName || "").toLowerCase();
      if (!["br", "hr", "img", "input", "link", "meta"].includes(closingTag)) {
        if (stack.length > 1) {
          stack.pop();
        }
      }
    } else {
      const el = new HostElement(tagName);
      if (doc) el.ownerDocument = doc;
      if (attrStr) {
        let attrMatch;
        while ((attrMatch = attrRegex.exec(attrStr)) !== null) {
          const attrName = attrMatch[1];
          const attrVal = decodeHtmlEntities(attrMatch[2] ?? attrMatch[3] ?? attrMatch[4] ?? "");
          el.setAttribute(attrName, attrVal);
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

// Browser-faithful per-character canvas metrics. The Kotlin source of these
// journeys runs in a real browser that resolves Noto Sans CJK SC for the
// CJK face: full-width punctuation carries a 1em box whose ink sits in the
// half the glyph connects from — opening brackets ink the RIGHT half, closing
// brackets and pause marks ink the LEFT half — and the blank half is what
// punctuation compression consumes. Curly quotes stay proportional in the
// face (the engine widens them itself in CJK context). A node fake that
// answers 1em ink-spanning advances for every character refuses compression
// everywhere and widens every quote pair, so the punctuation and quote
// journeys cannot replay. ASCII and spaces keep the uniform 1em model: the
// DOM-side advance model measures them the same way, and no ported journey
// needs their proportional widths. Classification is per character;
// multi-character clusters keep the legacy uniform model.
const PUNCT_OPENING_FULLWIDTH = new Set("（〔【《〈「『｛".split(""));
const PUNCT_CLOSING_FULLWIDTH = new Set("）〕】》〉」』".split(""));
const PUNCT_PAUSE_OR_STOP_FULLWIDTH = new Set("、。，．：；！？".split(""));
const PUNCT_HALFWIDTH_CELL = new Set("｢｣｡､".split(""));

function fakeCanvasCharMetrics(ch, fontSize) {
  const em = fontSize;
  // Ink windows mirror the measured Noto Sans CJK SC bounds so the trim
  // arithmetic picks the same body frame side the real browser layout does.
  if (PUNCT_OPENING_FULLWIDTH.has(ch)) {
    return { advance: em, inkLeft: em * 0.66, inkRight: em * 0.95 };
  }
  if (PUNCT_CLOSING_FULLWIDTH.has(ch)) {
    return { advance: em, inkLeft: em * 0.05, inkRight: em * 0.35 };
  }
  if (PUNCT_PAUSE_OR_STOP_FULLWIDTH.has(ch)) {
    return { advance: em, inkLeft: em * 0.06, inkRight: em * 0.32 };
  }
  if (PUNCT_HALFWIDTH_CELL.has(ch)) {
    // Halfwidth corner marks and halfwidth ideographic pauses shape at half
    // advance; the engine re-seats the glyph inside a synthesized full-width
    // cell (UnderwidthPunctuationFullWidthBoxPlacement).
    return { advance: em * 0.5, inkLeft: em * 0.04, inkRight: em * 0.34 };
  }
  if (ch === "·") {
    return { advance: em, inkLeft: em * 0.42, inkRight: em * 0.58 };
  }
  if (ch === "“" || ch === "”" || ch === "‘" || ch === "’") {
    return { advance: em * 0.45, inkLeft: 0, inkRight: em * 0.45 };
  }
  return null;
}

// Natural advance of one character under the same metric table, shared by
// the canvas shaper and the DOM-side advance model so the engine's
// letter-spacing residuals replay to the declared widths.
function fakeCharNaturalAdvance(ch, fontSize) {
  const m = fakeCanvasCharMetrics(ch, fontSize);
  return m ? m.advance : fontSize;
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
    const s = String(text ?? "");
    const match = /(\d+(?:\.\d+)?)px\b/.exec(this.font || "");
    const fontSize = match ? Number.parseFloat(match[1]) : 18;
    const ascent = fontSize * (16 / 18);
    const descent = fontSize * (4 / 18);
    const chars = Array.from(s);
    let width;
    let inkLeft = 0;
    let inkRight;
    if (chars.length === 1) {
      const m = fakeCanvasCharMetrics(chars[0], fontSize);
      if (m) {
        width = m.advance;
        inkLeft = -m.inkLeft;
        inkRight = m.inkRight;
      }
    }
    if (width == null) {
      width = s.length * fontSize;
      inkRight = width;
    }
    return {
      width,
      actualBoundingBoxLeft: inkLeft,
      actualBoundingBoxRight: inkRight,
      actualBoundingBoxAscent: ascent,
      actualBoundingBoxDescent: descent,
      fontBoundingBoxAscent: ascent,
      fontBoundingBoxDescent: descent,
      ideographicBaseline: -ascent,
      alphabeticBaseline: -ascent,
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
FakeNode.prototype.contains = function (other) {
  for (let node = other; node; node = node.parentNode) {
    if (node === this) return true;
  }
  return false;
};

FakeNode.prototype.querySelectorAll = function (selector) {
  const result = [];
  const visit = (node) => {
    for (const child of node.childNodes) {
      if (child.nodeType === 1 && matchesHostSelector(child, selector, this)) {
        result.push(child);
      }
      visit(child);
    }
  };
  visit(this);
  return result;
};

FakeNode.prototype.querySelector = function (selector) {
  return this.querySelectorAll(selector)[0] ?? null;
};

FakeNode.prototype.matches = function (selector) {
  return matchesHostSelector(this, selector);
};

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

Object.defineProperty(FakeNode.prototype, "nextElementSibling", {
  get() {
    if (!this.parentNode) return null;
    const siblings = this.parentNode.childNodes;
    const index = siblings.indexOf(this);
    for (let scan = index + 1; scan < siblings.length; scan += 1) {
      if (siblings[scan].nodeType === 1) return siblings[scan];
    }
    return null;
  },
  configurable: true,
});

Object.defineProperty(FakeNode.prototype, "lastChild", {
  get() {
    return this.childNodes[this.childNodes.length - 1] ?? null;
  },
  configurable: true,
});

Object.defineProperty(FakeNode.prototype, "isConnected", {
  get() {
    let curr = this;
    while (curr) {
      if (curr === globalThis.document) return true;
      curr = curr.parentNode;
    }
    return false;
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

FakeNode.prototype.replaceWith = function (...nodes) {
  if (!this.parentNode) return;
  const parent = this.parentNode;
  for (const node of nodes) {
    parent.insertBefore(node, this);
  }
  parent.removeChild(this);
};

const NON_RENDERED_TAGS = new Set(["HEAD", "STYLE", "SCRIPT", "META", "LINK", "TITLE", "NOSCRIPT"]);

// Engine-shaped source text advances at the paragraph typography's font size;
// a smaller host font on an inline wrapper (sup at 12px, say) shifts paint but
// not the engine's declared cluster advance, which the letter-spacing styles
// encode. Text measurement therefore reads the nearest block ancestor's
// font-size; pseudo content keeps the element's own font-size because the
// engine probes generated boxes through the host box.
const INLINE_FLOW_TAGS = new Set([
  "STRONG", "SPAN", "EM", "A", "B", "I", "U", "MARK", "SMALL",
  "SUB", "SUP", "CODE", "KBD", "SAMP", "VAR", "TIME", "DATA",
  "RUBY", "RT", "RP", "BDI", "BDO", "ABBR", "Q", "CITE", "DEL", "SPOILER",
]);

function blockFontSizePx(element) {
  let node = element;
  while (node && node.nodeType === 1) {
    if (!INLINE_FLOW_TAGS.has(node.tagName)) {
      return cssPx(globalThis.getComputedStyle?.(node)?.getPropertyValue("font-size")) || 18;
    }
    node = node.parentElement;
  }
  return 18;
}

function computedLetterSpacingPx(element) {
  if (!element || element.nodeType !== 1) return 0;
  const raw = globalThis.getComputedStyle?.(element)?.getPropertyValue("letter-spacing") ?? "";
  const match = /^(-?\d+(?:\.\d+)?)px/.exec(String(raw).trim());
  return match ? Number.parseFloat(match[1]) : 0;
}

function pseudoContentAdvance(element, pseudo) {
  if (!element || element.nodeType !== 1) return 0;
  const cs = globalThis.getComputedStyle?.(element, pseudo);
  if (!cs) return 0;
  const display = cs.getPropertyValue?.("display") ?? "";
  if (display === "none") return 0;
  const position = cs.getPropertyValue?.("position") ?? "";
  if (position === "absolute" || position === "fixed") return 0;
  let content = cs.getPropertyValue?.("content") ?? "";
  content = content.trim();
  if (!content || content === "none" || content === "normal") return 0;
  if ((content.startsWith('"') && content.endsWith('"')) || (content.startsWith("'") && content.endsWith("'"))) {
    content = content.slice(1, -1);
  }
  if (!content) return 0;
  const fontSize = cssPx(cs.getPropertyValue?.("font-size")) || 18;
  const letterSpacing = computedLetterSpacingPx(element);
  return content.length * (fontSize + letterSpacing);
}

// Horizontal advance of an inline-flow node: text measures chars at the
// fixture 18px plus the parent's letter-spacing (the engine distributes
// justification through per-segment letter-spacing styles, so a post-render
// re-measure must include it to agree with data-tq-line-width).
function inlineContentAdvance(node) {
  if (node.nodeType === 3) {
    const data = (node.data ?? "").replace(/[\u200B\uFEFF]/g, "");
    if (data.length === 0) return 0;
    const baseFontSize = node.parentElement ? blockFontSizePx(node.parentElement) : 18;
    const letterSpacing = computedLetterSpacingPx(node.parentElement);
    let advance = 0;
    for (const ch of data) {
      advance += fakeCharNaturalAdvance(ch, baseFontSize) + letterSpacing;
    }
    return advance;
  }
  if (node.nodeType !== 1) return 0;
  if (NON_RENDERED_TAGS.has(node.tagName)) return 0;
  const cs = globalThis.getComputedStyle?.(node);
  const margin = (side) => {
    const raw = cs?.getPropertyValue?.(side) ?? "0";
    const parsed = Number.parseFloat(raw);
    return Number.isNaN(parsed) ? 0 : parsed;
  };
  const explicit = getElementExplicitWidth(node);
  if (explicit != null) return margin("margin-left") + explicit + margin("margin-right");
  const pad = getHorizontalPadding(node);
  let inner = 0;
  for (const child of node.childNodes) inner += inlineContentAdvance(child);
  const beforeAdvance = pseudoContentAdvance(node, "::before");
  const afterAdvance = pseudoContentAdvance(node, "::after");
  return margin("margin-left") + pad.left + beforeAdvance + inner + afterAdvance + pad.right + margin("margin-right");
}

// Widest inline run between engine line markers in a block container.
// Line breaks surface both as BR (data-tq-engine-break) and as empty
// SPAN.tq-line markers, and inside sliced inline elements they sit below
// the direct children, so the walk recurses and resets the running segment
// at either marker kind while mirroring inlineContentAdvance's chrome
// model (margins, padding, pseudo advances) for the elements it crosses.
function widestInlineSegment(container) {
  let max = 0;
  let current = 0;
  const marginOf = (element, side) => {
    const cs = globalThis.getComputedStyle?.(element);
    const parsed = Number.parseFloat(cs?.getPropertyValue?.(side) ?? "0");
    return Number.isNaN(parsed) ? 0 : parsed;
  };
  const visit = (node) => {
    if (node.nodeType === 3) {
      current += inlineContentAdvance(node);
      return;
    }
    if (node.nodeType !== 1 || NON_RENDERED_TAGS.has(node.tagName)) return;
    if (node.tagName === "BR") {
      max = Math.max(max, current);
      current = 0;
      return;
    }
    const isLineMarker = typeof node.classList?.contains === "function" &&
      node.classList.contains("tq-line");
    if (isLineMarker) {
      max = Math.max(max, current);
      current = 0;
      return;
    }
    const explicit = getElementExplicitWidth(node);
    if (explicit != null) {
      current += marginOf(node, "margin-left") + explicit + marginOf(node, "margin-right");
      return;
    }
    const pad = getHorizontalPadding(node);
    current += marginOf(node, "margin-left") + pad.left + pseudoContentAdvance(node, "::before");
    for (const child of node.childNodes) visit(child);
    current += pseudoContentAdvance(node, "::after") + pad.right + marginOf(node, "margin-right");
  };
  for (const child of container.childNodes) visit(child);
  return Math.max(max, current);
}

// Left edge of a node within its block container's content box, by walking
// preceding inline siblings and ancestor paddings.
function inlineStartOffset(node) {
  const parent = node.parentNode;
  if (!parent || parent.nodeType !== 1) return 0;
  const isInline = [
    "STRONG", "SPAN", "EM", "A", "B", "I", "U", "MARK", "SMALL",
    "SUB", "SUP", "CODE", "KBD", "SAMP", "VAR", "TIME", "DATA",
    "RUBY", "RT", "RP", "BDI", "BDO", "ABBR", "Q", "CITE", "DEL", "SPOILER",
  ].includes(parent.tagName);
  const cs = globalThis.getComputedStyle?.(parent);
  const margin = (side) => {
    const raw = cs?.getPropertyValue?.(side) ?? "0";
    const parsed = Number.parseFloat(raw);
    return Number.isNaN(parsed) ? 0 : parsed;
  };
  let offset = getHorizontalPadding(parent).left + pseudoContentAdvance(parent, "::before");
  for (const sibling of parent.childNodes) {
    if (sibling === node) break;
    offset += inlineContentAdvance(sibling);
  }
  return (isInline ? inlineStartOffset(parent) + margin("margin-left") : 0) + offset;
}

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
    this.endContainer = node;
    if (node?.nodeType === 1) {
      this.endOffset = node.childNodes.length;
    } else {
      this.endOffset = (node?.data ?? node?.value ?? node?.textContent ?? "").length;
    }
  }

  selectNode(node) {
    const parent = node?.parentNode ?? node?.parentElement ?? null;
    this.startContainer = parent;
    this.endContainer = parent;
    if (parent) {
      const idx = parent.childNodes.indexOf(node);
      this.startOffset = idx >= 0 ? idx : 0;
      this.endOffset = idx >= 0 ? idx + 1 : 0;
    } else {
      this.startOffset = 0;
      this.endOffset = 0;
    }
  }

  get commonAncestorContainer() {
    return this.startContainer;
  }

  intersectsNode(node) {
    if (!node) return false;
    if (node === this.startContainer || node === this.endContainer) return true;
    if (this.startContainer?.contains?.(node) || node.contains?.(this.startContainer)) return true;
    return false;
  }

  cloneContents() {
    const fragment = new FakeFragment();
    if (!this.startContainer) return fragment;
    if (this.startContainer === this.endContainer) {
      if (this.startContainer.nodeType === 1) {
        const slice = this.startContainer.childNodes.slice(this.startOffset, this.endOffset);
        for (const child of slice) {
          fragment.appendChild(child.cloneNode(true));
        }
      } else if (this.startContainer.nodeType === 3) {
        const full = this.startContainer.data ?? this.startContainer.value ?? this.startContainer.textContent ?? "";
        const start = this.startOffset ?? 0;
        const end = this.endOffset ?? full.length;
        fragment.appendChild(new FakeText(full.slice(start, end)));
      }
    } else {
      fragment.appendChild(new FakeText(this.toString()));
    }
    return fragment;
  }

  toString() {
    if (!this.startContainer) return "";
    if (this.startContainer === this.endContainer) {
      if (this.startContainer.nodeType === 3) {
        const full = this.startContainer.data ?? this.startContainer.value ?? this.startContainer.textContent ?? "";
        const start = this.startOffset ?? 0;
        const end = this.endOffset ?? full.length;
        return full.slice(start, end);
      }
      if (this.startContainer.nodeType === 1) {
        return this.startContainer.childNodes
          .slice(this.startOffset, this.endOffset)
          .map((n) => n.textContent ?? "")
          .join("");
      }
    }
    return this.startContainer.textContent ?? "";
  }

  getBoundingClientRect() {
    let left = 0;
    let width = 0;
    if (this.startContainer) {
      if (this.startContainer.nodeType === 3) {
        const full = this.startContainer.data ?? this.startContainer.value ?? this.startContainer.textContent ?? "";
        const start = this.startOffset ?? 0;
        const end = this.endOffset ?? full.length;
        const baseFontSize = this.startContainer.parentElement
          ? blockFontSizePx(this.startContainer.parentElement)
          : 18;
        const letterSpacing = computedLetterSpacingPx(this.startContainer.parentElement);
        const charAdvance = (ch) => fakeCharNaturalAdvance(ch, baseFontSize) + letterSpacing;
        const chars = Array.from(full);
        left = inlineStartOffset(this.startContainer);
        for (let i = 0; i < Math.min(start, chars.length); i += 1) {
          left += charAdvance(chars[i]);
        }
        width = 0;
        for (let i = start; i < Math.min(end, chars.length); i += 1) {
          width += charAdvance(chars[i]);
        }
      } else {
        // Element range (selectNodeContents on an element): place the run at
        // the element's inline offset and advance it with the inline content
        // model, so geometry carriers inside the element stay within the
        // selection rect; child offsets select a sub-slice of the children.
        const container = this.startContainer;
        const children = container.childNodes ?? [];
        const start = Math.min(this.startOffset ?? 0, children.length);
        const end = Math.min(this.endOffset ?? children.length, children.length);
        left = inlineStartOffset(container);
        for (let i = 0; i < start; i += 1) {
          left += inlineContentAdvance(children[i]);
        }
        width = 0;
        for (let i = start; i < end; i += 1) {
          width += inlineContentAdvance(children[i]);
        }
      }
    }
    return new FakeDOMRect(left, 0, width, 30);
  }

  getClientRects() {
    return [this.getBoundingClientRect()];
  }
}

let cachedDocument = null;

function createDocumentDouble() {
  if (cachedDocument) return cachedDocument;
  const listeners = new Map();
  const doc = {
    isConnected: true,
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
    createElementNS(ns, tagName) {
      // The engine reaches for createElementNS only for SVG overlays; the
      // fake host models them as plain elements, so strip the svg: prefix
      // some producers add and delegate, keeping the namespace on the node.
      const el = doc.createElement(String(tagName).replace(/^svg:/i, ""));
      el.namespaceURI = ns ?? null;
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
      return globalThis.getSelection();
    },
    contains(other) {
      for (let node = other; node; node = node.parentNode) {
        if (node === doc || node === doc.documentElement || node === doc.body) return true;
      }
      return false;
    },
  };
  doc.body = doc.createElement("body");
  doc.head = doc.createElement("head");
  doc.documentElement = doc.createElement("html");
  doc.documentElement.appendChild(doc.head);
  doc.documentElement.appendChild(doc.body);
  doc.documentElement.parentNode = doc;
  cachedDocument = doc;
  return doc;
}

let currentSelection = null;

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
      ClipboardEvent: globalThis.ClipboardEvent,
      DataTransfer: globalThis.DataTransfer,
      getSelection: globalThis.getSelection,
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

  currentSelection = new FakeSelection();
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
  globalThis.ClipboardEvent = FakeClipboardEvent;
  globalThis.DataTransfer = FakeDataTransfer;
  globalThis.getSelection = () => currentSelection;
  globalThis.DOMRect = FakeDOMRect;
  globalThis.document = createDocumentDouble();
  globalThis.window = globalThis;
  globalThis.window.getSelection = () => currentSelection;

  globalThis.getComputedStyle = (element, pseudo) => {
    const isInlineTag = [
      "STRONG", "SPAN", "EM", "A", "B", "I", "U", "MARK", "SMALL",
      "SUB", "SUP", "CODE", "KBD", "SAMP", "VAR", "TIME", "DATA",
      "RUBY", "RT", "RP", "BDI", "BDO", "ABBR", "Q", "CITE", "SPOILER", "DEL",
    ].includes(element?.tagName);
    const overrides = isInlineTag ? { display: "inline" } : {};
    const base = fixtureComputedStyle(element, pseudo, overrides);

    const pseudoKey = pseudo ? ":" + String(pseudo).trim().replace(/^:+/, "") : null;
    const pseudoInheritable = [
      "font-size", "font-weight", "font-style", "font-family",
      "line-height", "letter-spacing", "color", "white-space",
    ];

    const getProp = (name) => {
      const kebab = name.replace(/[A-Z]/g, (m) => `-${m.toLowerCase()}`);
      if (pseudoKey) {
        const pseudoVal = getPseudoStylesheetProperty(element, kebab, pseudoKey);
        if (pseudoVal !== "") return pseudoVal;
        if (pseudoInheritable.includes(kebab)) {
          const inherited = resolveElementProperty(element, kebab, base);
          if (inherited !== undefined && inherited !== "") return inherited;
        }
        const camel = kebab.replace(/-([a-z])/g, (_, c) => c.toUpperCase());
        return base[kebab] ?? base[camel] ?? base[name] ?? (kebab === "float" ? (base.cssFloat ?? "none") : "");
      }
      const val = resolveElementProperty(element, kebab, base);
      if (val !== undefined && val !== "") return val;
      const camel = kebab.replace(/-([a-z])/g, (_, c) => c.toUpperCase());
      return base[kebab] ?? base[camel] ?? base[name] ?? (kebab === "float" ? (base.cssFloat ?? "none") : "");
    };

    return new Proxy(base, {
      get(target, prop) {
        if (prop === "getPropertyValue") {
          return (name) => getProp(name);
        }
        if (typeof prop === "string") {
          const val = getProp(prop);
          if (val !== "") return val;
        }
        return Reflect.get(target, prop);
      },
    });
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

  // Minimal window EventTarget: the runtime and coordinator attach scroll and
  // gesture listeners to window, compulsory for Node globalThis double.
  if (typeof globalThis.addEventListener !== "function") {
    const windowListeners = new Map();
    globalThis.addEventListener = (type, listener) => {
      if (!windowListeners.has(type)) windowListeners.set(type, new Set());
      windowListeners.get(type).add(listener);
    };
    globalThis.removeEventListener = (type, listener) => {
      windowListeners.get(type)?.delete(listener);
    };
    globalThis.dispatchEvent = (event) => {
      event.target ??= globalThis;
      event.currentTarget = globalThis;
      const set = windowListeners.get(event.type);
      if (set) {
        for (const listener of Array.from(set)) listener(event);
      }
      return !event.defaultPrevented;
    };
  }
  globalThis.innerHeight = 768;
  if (globalThis.document?.documentElement) {
    globalThis.document.documentElement.clientWidth = 1024;
    globalThis.document.documentElement.clientHeight = 768;
  }

  installTestAnimationFrames();
}

export function cleanupWorld() {
  stylesheetRules.clear();
  parsedStylesheetRules.length = 0;
  parsedPseudoRules.length = 0;
  if (currentSelection) {
    currentSelection.removeAllRanges();
  }
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

// ADR 0053 C1: the internal document event channel is retired; these host
// helpers keep their export names but drive the dissolved engine surface
// (named functions over the plain runtime graph) directly.
let runtimeGraph = null;

export function dispatchRelayout(root) {
  relayout(runtimeGraph.rootState, runtimeGraph.layoutJobPool, runtimeGraph.rawDom, root);
}

export function probeContentDrift(root) {
  return probeRootContentDrift(runtimeGraph.rawDom, runtimeGraph.rootState, root);
}

export function reconcileContent(root, paragraphs = []) {
  return reconcileRoot(runtimeGraph.rawDom, runtimeGraph.rootState, runtimeGraph.layoutJobPool, root, paragraphs);
}

export function detachViaChannel(root) {
  detachRoot(runtimeGraph.layoutJobPool, root);
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


export function clearSnapshotFontSessionFixture() {
  globalServices().coordination.fonts.replayRegistry.sessions.delete("fixture-snapshot-session");
  delete globalThis.__TiqianSnapshotFixtureActive;
  delete globalServices().coordination.layoutWorker;
  delete globalThis.__TiqianSnapshotPreparedPlan;
  delete globalThis.__TiqianSnapshotPreparedRenderCount;
  delete globalThis.__TiqianSnapshotFontShapeCount;
  delete globalThis.__TiqianSnapshotFontFallbackCount;
}

export function snapshotFontShapeCount() {
  return globalThis.__TiqianSnapshotFontShapeCount || 0;
}

export function snapshotFontFallbackCount() {
  return globalThis.__TiqianSnapshotFontFallbackCount || 0;
}

export function snapshotPreparedPlan() {
  return globalThis.__TiqianSnapshotPreparedPlan || "";
}

export function snapshotPreparedRenderCount() {
  return globalThis.__TiqianSnapshotPreparedRenderCount || 0;
}

export function installPreparedWorkerIssue(detail) {
  globalServices().coordination.layoutWorker = { take: () => null, issue: () => detail };
}

export function installPreparedWorkerLivePlan() {
  globalServices().coordination.layoutWorker = {
    take(_element, _sessionKey, requestText) {
      const request = JSON.parse(requestText);
      const semantics = Array.from(request.semantics || [], function (semantic, sourceIndex) {
        return {
          start: semantic.start,
          end: semantic.end,
          tagName: semantic.tagName,
          sourceIndex: Number.isSafeInteger(semantic.sourceIndex)
            ? semantic.sourceIndex
            : sourceIndex,
          order: Number.isSafeInteger(semantic.order) ? semantic.order : sourceIndex,
        };
      }).sort(function (left, right) {
        return left.start - right.start || right.end - left.end || left.order - right.order;
      }).map(function (semantic) {
        return {
          start: semantic.start,
          end: semantic.end,
          tagName: semantic.tagName,
          sourceIndex: semantic.sourceIndex,
        };
      });
      // WorkerLivePlanEcho: the fixture lays the request text out as one
      // line of uniform-width clusters, honoring inline-object geometry,
      // so the prepared renderer exercises real cells and ranges.
      const text = String(request.text || "");
      const charWidth = Number(request.fontSizePx) || 18;
      const lineHeight = Number(request.lineHeightPx) || 30;
      const indent = (Number(request.firstLineIndentIc) || 0) * charWidth;
      const inlineGeometry = {};
      for (const record of String(request.inlineObjects || "").split("\u001e")) {
        if (!record) continue;
        const fields = record.split("\u001d");
        inlineGeometry[fields[0] + "-" + fields[1]] = Number(fields[2]) || charWidth;
      }
      const cells = [];
      let drawX = indent;
      let index = 0;
      while (index < text.length) {
        const code = text.codePointAt(index);
        const size = code >= 0x10000 ? 2 : 1;
        const key = index + "-" + (index + size);
        const naturalWidth = inlineGeometry[key] != null ? inlineGeometry[key] : charWidth;
        cells.push({
          rangeStart: index,
          rangeEnd: index + size,
          source: text.slice(index, index + size),
          display: text.slice(index, index + size),
          drawX: drawX,
          naturalWidth: naturalWidth,
          leadingLayoutAdvance: 0,
        });
        drawX += naturalWidth;
        index += size;
      }
      const plan = {
        schema: 1,
        height: lineHeight,
        lines: cells.length
          ? [{
              rangeStart: 0,
              rangeEnd: text.length,
              endReason: "ParagraphEnd",
              indent: indent,
              visualWidth: drawX - indent,
              hyphenAdvance: 0,
              top: 0,
              bottom: lineHeight,
              baseline: lineHeight - 6,
              cells: cells,
            }]
          : [],
      };
      return JSON.stringify({
        plan: plan,
        semanticReplay: "live-source",
        semantics,
        inlineBoxes: request.renderInlineBoxes || [],
      });
    },
    issue: () => null,
  };
}

export const enginePunctuationFeatureStyle = `
        <style>
          [data-tq-rendered="true"] {
            font-feature-settings: "halt" 0, "chws" 0, "palt" 0 !important;
          }
          [data-tq-rendered="true"] span[data-tq-open-type-features="pwid,palt"] {
            font-feature-settings: "halt" 0, "chws" 0, "palt" 1 !important;
          }
        </style>
    `;

export function assertEnginePunctuationFeatureLock(element, proportionalQuote = false) {
  const features = computedStyleValue(element, "font-feature-settings");
  assert.ok(/["']halt["']\s+0/.test(features), features);
  assert.ok(/["']chws["']\s+0/.test(features), features);
  const palt = /["']palt["'](?:\s+(-?\d+))?/.exec(features);
  assert.ok(palt, features);
  const paltValue = palt[1] === undefined ? "1" : palt[1];
  assert.equal(proportionalQuote ? "1" : "0", paltValue, features);
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

export function grantUnboundedSlice(root, minTier) {
  const controller = testGrantController(
    root,
    globalThis.TiqianWeb.workerJobGeneration(root),
    Infinity,
    Number.MAX_SAFE_INTEGER,
  );
  return globalThis.TiqianWeb.workerRunSlice(controller, minTier);
}

// Mirrors the jsTest helper: reads the rendered .tq-line dataset the renderer
// stamps, joined so a single string compares across widths.
export function renderedLineSignature(paragraph) {
  return paragraph.querySelectorAll(".tq-line")
    .map((line) => [
      line.dataset.tqLineRange,
      line.dataset.tqLineWidth,
      line.dataset.tqLineEnd,
    ].join("\u001f"))
    .join("\u001e");
}

export function dispatchTestProgressiveScroll() {
  globalThis.window.dispatchEvent(new FakeEvent("scroll"));
}

export function testOptions(overrides = {}) {
  return { fontSize: 18, lineHeight: 30, ...overrides };
}

export function elementFragmentWidths(element) {
  return Array.from(element.getClientRects()).filter((rect) => rect.width > 0).map((rect) => rect.width);
}

export function snapshotWorkerRequestMaxWidth(root, paragraph) {
  const request = workerLayoutRequestForRoot(root, paragraph, optionsFromJs({
    snapshotFontSession: {
      status: "conforming",
      sessionId: "fixture-grid-session",
      detail: "test",
    },
  }));
  if (!request) throw new Error("worker layout request unavailable for snapshot fixture");
  return request.maxWidthPx;
}

export function snapshotTestOptions() {
  return {
    paragraphSelector: "p[data-tq-snapshot-key]",
    snapshotFontSession: {
      status: "conforming",
      sessionId: "fixture-snapshot-session",
      detail: "test",
    },
  };
}

let runtimePromise;

// Build the test-host TiqianWeb object from the dissolved engine surface
// (R10): named functions over the plain runtime graph replace the former
// TiqianEngine facade. enhance keeps its counting wrapper so
// tests can assert per-root paragraph counts; the worker-prefixed bridge
// names bind directly to the graph's LayoutJobPool.
export function loadHostRuntime() {
  buildWorld();
  installPreparedRendererFixture();
  runtimePromise ??= Promise.resolve().then(() => {
    // The runtime graph is now empty; the session owns its own rootState.
    // Create rootState and other services separately.
    const rootState = createRootState();
    const layoutJobPool = globalServices().coordination.layoutJobPool;
    const rawDomContext = globalServices().rawDom.context;

    const bridge = {
      // TiqianWeb.install() in the Kotlin runtime attached the clipboard
      // handler (the webpack demo main() called it eagerly, which masked the
      // missing call here while the bundle existed). With ts-runtime there is
      // no main(), so the bridge performs the install itself.
      install() {
        // The clipboard manager is now a global service; install on the test
        // document so copy interception works during hosted tests.
        if (globalThis.document) globalServices().clipboard.install(globalThis.document);
      },
      enhance(root, options) {
        enhance(rootState, layoutJobPool, rawDomContext, root, options);
        const count = root.getAttribute("data-tiqian-enhanced-count");
        return count != null ? Number(count) : 0;
      },
      enhanceProgressively(root, options) {
        enhanceProgressively(rootState, layoutJobPool, rawDomContext, root, options);
      },
      destroy(root) {
        destroyRoot(rootState, layoutJobPool, rawDomContext, root);
      },
      detach(root) {
        detachRoot(layoutJobPool, root);
      },
      relayout(root) {
        relayout(rootState, layoutJobPool, rawDomContext, root);
      },
      refresh(root, progressively = true) {
        const state = rootState.getState(root);
        if (state) {
          if (progressively) {
            enhanceProgressivelyFromCanonical(rootState, layoutJobPool, rawDomContext, root, state.options);
          } else {
            enhance(rootState, layoutJobPool, rawDomContext, root, state.options, true);
          }
        }
        return root || globalThis.document.body;
      },
      cancelLayoutWork(root) {
        layoutJobPool.cancelJob(root);
      },
      probeContentDrift(root) {
        return probeRootContentDrift(rawDomContext, rootState, root);
      },
      reconcileContent(root, paragraphs) {
        return reconcileRoot(rawDomContext, rootState, layoutJobPool, root, paragraphs);
      },
      workerLayoutRequest(root, paragraph, options) {
        const request = workerLayoutRequestForRoot(root, paragraph, optionsFromJs(options ?? {}));
        return request ? JSON.stringify(request) : null;
      },
      workerAttach: (root) => layoutJobPool.attach(root),
      workerDetach: (root) => layoutJobPool.detach(root),
      workerHasJob: (root) => layoutJobPool.hasJob(root),
      workerJobGeneration: (root) => layoutJobPool.jobGeneration(root),
      workerRunSlice: (controller, minTier) => layoutJobPool.runSlice(controller, minTier),
      workerPendingInTier: (root, tier) => layoutJobPool.pendingInTier(root, tier),
      workerParagraphCount: (root) => layoutJobPool.paragraphCount(root),
      workerParagraphAt: (root, index) => layoutJobPool.paragraphAt(root, index),
      workerSetParagraphTier: (root, index, tier) => layoutJobPool.setParagraphTier(root, index, tier),
    };
    globalThis.TiqianWeb = bridge;
    return bridge;
  });
  return runtimePromise;
}

export function computedStyleValue(element, property) {
  return globalThis.getComputedStyle(element).getPropertyValue(property);
}

export function nativeInnerText(element) {
  return element.innerText;
}

export function emptyRenderedLineCount(paragraph) {
  return Array.from(paragraph.querySelectorAll(".tq-line"))
    .filter((line) => line.dataset.tqLineEmpty === "true")
    .length;
}

export function elementWidth(element) {
  return element.getBoundingClientRect().width;
}

export function cssPx(value) {
  return Number.parseFloat(String(value).replace(/px$/, "")) || 0;
}

export function copySelection(element) {
  const selection = globalThis.getSelection();
  const range = globalThis.document.createRange();
  range.selectNodeContents(element);
  selection.removeAllRanges();
  selection.addRange(range);
  const clipboardData = new globalThis.DataTransfer();
  const event = new globalThis.ClipboardEvent("copy", {
    bubbles: true,
    cancelable: true,
    clipboardData,
  });
  element.dispatchEvent(event);
  const text = clipboardData.getData("text/plain") || selection.toString();
  selection.removeAllRanges();
  return text;
}

export function copiedData(element, type) {
  const selection = globalThis.getSelection();
  const range = globalThis.document.createRange();
  range.selectNodeContents(element);
  selection.removeAllRanges();
  selection.addRange(range);
  const clipboardData = new globalThis.DataTransfer();
  element.dispatchEvent(
    new globalThis.ClipboardEvent("copy", {
      bubbles: true,
      cancelable: true,
      clipboardData,
    }),
  );
  const value = clipboardData.getData(type);
  selection.removeAllRanges();
  return value;
}

export function copiedNodeData(node, type) {
  const selection = globalThis.getSelection();
  const range = globalThis.document.createRange();
  range.selectNode(node);
  selection.removeAllRanges();
  selection.addRange(range);
  const clipboardData = new globalThis.DataTransfer();
  node.parentElement.dispatchEvent(
    new globalThis.ClipboardEvent("copy", {
      bubbles: true,
      cancelable: true,
      clipboardData,
    }),
  );
  const value = clipboardData.getData(type);
  selection.removeAllRanges();
  return value;
}

export function copyWasIntercepted(element) {
  const selection = globalThis.getSelection();
  const range = globalThis.document.createRange();
  range.selectNodeContents(element);
  selection.removeAllRanges();
  selection.addRange(range);
  const event = new globalThis.ClipboardEvent("copy", {
    bubbles: true,
    cancelable: true,
    clipboardData: new globalThis.DataTransfer(),
  });
  element.dispatchEvent(event);
  selection.removeAllRanges();
  return event.defaultPrevented;
}

export const copySelectionWasIntercepted = copyWasIntercepted;

export function clearSelection() {
  const selection = globalThis.getSelection();
  if (selection) selection.removeAllRanges();
}

const mounted = [];

export function mount(html, { sharedStylesReady = true } = {}) {
  buildWorld();
  const wrapper = new HostElement("div");
  wrapper.ownerDocument = globalThis.document;
  wrapper.innerHTML = html;
  const root = wrapper.firstElementChild;
  if (!root) throw new Error("mount: markup has no root element");
  if (sharedStylesReady) {
    root.style.setProperty("--tq-styles-ready", "1");
  }
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
  clearSnapshotFontSessionFixture();
  restoreTestAnimationFrames();
  cleanupWorld();
}

export async function drainMicrotasks(times = 6) {
  for (let i = 0; i < times; i += 1) {
    await new Promise((resolve) => setImmediate(resolve));
  }
}

export function computedPseudoContent(element, pseudo) {
  const content = globalThis.getComputedStyle(element, pseudo).getPropertyValue("content").trim();
  if ((content.startsWith('"') && content.endsWith('"')) ||
      (content.startsWith("'") && content.endsWith("'"))) {
    return content.slice(1, -1);
  }
  return content;
}

export function directTextContent(paragraph) {
  return Array.from(paragraph.childNodes)
    .filter((node) => node.nodeType === 3)
    .map((node) => node.data)
    .join("");
}

export function selectionCoversElement(container, target) {
  const range = globalThis.document.createRange();
  range.selectNodeContents(container);
  const selected = range.getBoundingClientRect();
  const expected = target.getBoundingClientRect();
  return selected.left <= expected.left + 0.1 && selected.right >= expected.right - 0.1;
}
