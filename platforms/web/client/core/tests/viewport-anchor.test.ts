import assert from "node:assert/strict";
import test from "node:test";

import {
  captureViewportAnchor,
  compensateViewportAnchor,
  holdNativeScrollAnchoring,
  releaseNativeScrollAnchoring,
} from "../src/engine/coordination/viewport-anchor.js";
import type { ViewportAnchor } from "../src/engine/coordination/viewport-anchor.js";

interface GlobalEntry {
  name: string;
  own: boolean;
  value: unknown;
}

interface StyleProperty {
  value: string;
  priority: string;
}

type GetStringFn = (name: string) => string;
type SetStyleFn = (name: string, value: string, priority?: string) => void;
type RemoveStyleFn = (name: string) => void;

interface FakeStyle {
  getPropertyValue: GetStringFn;
  getPropertyPriority: GetStringFn;
  setProperty: SetStyleFn;
  removeProperty: RemoveStyleFn;
}

interface EventPayload {
  type: string;
}

type EventListener = (event: EventPayload) => void;
type AddEventListenerFn = (type: string, listener: EventListener) => void;
type RemoveEventListenerFn = (type: string, listener: EventListener) => void;
type ScrollByFn = (x: number, delta: number) => void;
type FireFn = (type: string) => void;

interface FakeView {
  innerHeight: number;
  scrollY: number;
  addEventListener: AddEventListenerFn;
  removeEventListener: RemoveEventListenerFn;
  scrollBy: ScrollByFn;
  fire: FireFn;
}

type AdvanceFn = (ms: number) => void;

interface FakeClock {
  advance: AdvanceFn;
}

interface Rect {
  top: number;
  bottom: number;
}

type ClosestFn = (selector: string) => unknown;
type GetRectFn = () => Rect;

interface FakeParagraph {
  isConnected: boolean;
  closest: ClosestFn;
  getBoundingClientRect: GetRectFn;
}

interface FakeDocumentElement {
  style: FakeStyle;
}

interface FakeOwnerDocument {
  defaultView: FakeView;
  scrollingElement: null;
  documentElement: FakeDocumentElement;
}

interface FakeRootStyle {
  getPropertyValue: GetStringFn;
  getPropertyPriority: GetStringFn;
  setProperty: SetStyleFn;
  removeProperty: RemoveStyleFn;
}

interface FakeScroller {
  parentElement: null;
  scrollTop: number;
  scrollHeight: number;
  clientHeight: number;
}

type RootClosestFn = () => FakeRoot;
type QueryAllFn = () => FakeParagraph[];

interface FakeRoot {
  ownerDocument: FakeOwnerDocument;
  parentElement: FakeScroller | null;
  isConnected: boolean;
  style: FakeRootStyle;
  closest: RootClosestFn;
  querySelectorAll: QueryAllFn;
  getBoundingClientRect: GetRectFn;
}

interface FakeBrowser {
  root: HTMLElement;
  paragraph: FakeParagraph;
  layoutShift: number;
}

interface WritableGlobals {
  performance: Performance;
  getComputedStyle: typeof globalThis.getComputedStyle;
  window: Window & typeof globalThis;
}

function preserveGlobals(names: string[]): GlobalEntry[] {
  return names.map((name) => ({
    name,
    own: Object.prototype.hasOwnProperty.call(globalThis, name),
    value: globalThis[name as keyof typeof globalThis],
  }));
}

function restoreGlobals(entries: GlobalEntry[]): void {
  const globals = globalThis as Record<string, unknown>;
  for (const { name, own, value } of entries) {
    if (own) globals[name] = value;
    else delete globals[name];
  }
}

const listeners = new Map<string, Set<EventListener>>();
const view: FakeView = {
  innerHeight: 844,
  scrollY: 100,
  addEventListener(type, listener) {
    const bucket = listeners.get(type) ?? new Set();
    bucket.add(listener);
    listeners.set(type, bucket);
  },
  removeEventListener(type, listener) {
    listeners.get(type)?.delete(listener);
  },
  scrollBy(_x, delta) {
    this.scrollY += delta;
  },
  fire(type) {
    for (const listener of listeners.get(type) ?? []) listener({ type });
  },
};

let now = 0;
const clock: FakeClock = {
  advance(ms) {
    now += ms;
  },
};

function makeFakeStyle(properties: Map<string, StyleProperty>): FakeStyle {
  return {
    getPropertyValue: (name) => properties.get(name)?.value ?? "",
    getPropertyPriority: (name) => properties.get(name)?.priority ?? "",
    setProperty(name, value, priority = "") {
      properties.set(name, { value, priority });
    },
    removeProperty(name) {
      properties.delete(name);
    },
  };
}

function installFakeBrowser(): FakeBrowser {
  let layoutShift = 0;
  const paragraph: FakeParagraph = {
    isConnected: true,
    closest: (selector) => (selector === "tiqian-prose, [data-tiqian-root]" ? root : null),
    getBoundingClientRect() {
      const top = 420 + layoutShift - view.scrollY;
      return { top, bottom: top + 120 };
    },
  };
  const properties = new Map<string, StyleProperty>();
  const documentElementProperties = new Map<string, StyleProperty>();
  const documentElement: FakeDocumentElement = {
    style: makeFakeStyle(documentElementProperties),
  };
  const root: FakeRoot = {
    ownerDocument: { defaultView: view, scrollingElement: null, documentElement },
    parentElement: null,
    isConnected: true,
    style: makeFakeStyle(properties),
    closest: () => root,
    querySelectorAll: () => [paragraph],
    getBoundingClientRect: () => ({ top: 0 - view.scrollY + 100, bottom: 1700 - view.scrollY + 100 }),
  };
  const globals = globalThis as WritableGlobals;
  const performanceRef = { now: () => now } as unknown;
  globals.performance = performanceRef as Performance;
  const getComputedStyleRef = (() => ({ overflowY: "visible", overflow: "visible" })) as unknown;
  globals.getComputedStyle = getComputedStyleRef as typeof globalThis.getComputedStyle;
  const windowRef = view as unknown;
  globals.window = windowRef as Window & typeof globalThis;
  const rootRef = root as unknown;
  return {
    root: rootRef as HTMLElement,
    paragraph,
    set layoutShift(value) {
      layoutShift = value;
    },
    get layoutShift() {
      return layoutShift;
    },
  };
}

const globalNames = ["performance", "getComputedStyle", "window"];

test("a capture/compensate pair around a commit keeps the anchor paragraph in place", () => {
  const globals = preserveGlobals(globalNames);
  const browser = installFakeBrowser();
  view.scrollY = 100;
  clock.advance(10_000);
  try {
    const initialTop = browser.paragraph.getBoundingClientRect().top;
    const anchor = captureViewportAnchor(browser.root);
    assert.ok(anchor);
    assert.equal(anchor.node, browser.paragraph);
    browser.layoutShift = 80;
    assert.equal(compensateViewportAnchor(browser.root, anchor), true);
    assert.equal(view.scrollY, 180);
    assert.equal(browser.paragraph.getBoundingClientRect().top, initialTop);
  } finally {
    restoreGlobals(globals);
  }
});

test("sub-pixel displacement is left alone", () => {
  const globals = preserveGlobals(globalNames);
  const browser = installFakeBrowser();
  view.scrollY = 100;
  clock.advance(10_000);
  try {
    const anchor = captureViewportAnchor(browser.root);
    browser.layoutShift = 0.25;
    assert.equal(compensateViewportAnchor(browser.root, anchor), false);
    assert.equal(view.scrollY, 100);
  } finally {
    restoreGlobals(globals);
  }
});

test("an active gesture suppresses capture and momentum scrolling extends the grace", () => {
  const globals = preserveGlobals(globalNames);
  const browser = installFakeBrowser();
  view.scrollY = 100;
  clock.advance(10_000);
  try {
    view.fire("wheel");
    assert.equal(captureViewportAnchor(browser.root), null);

    clock.advance(400);
    view.fire("scroll");
    assert.equal(captureViewportAnchor(browser.root), null);

    clock.advance(2_000);
    view.fire("scroll");
    assert.ok(captureViewportAnchor(browser.root));
  } finally {
    restoreGlobals(globals);
  }
});

test("a scroller at its top is never adjusted", () => {
  const globals = preserveGlobals(globalNames);
  const browser = installFakeBrowser();
  clock.advance(10_000);
  try {
    view.scrollY = 0;
    assert.equal(captureViewportAnchor(browser.root), null);
    view.scrollY = 1;
    assert.ok(captureViewportAnchor(browser.root));
  } finally {
    restoreGlobals(globals);
  }
});

interface RootWithRect {
  getBoundingClientRect: GetRectFn;
}

test("a root above the viewport anchors on its bottom edge; one below is left alone", () => {
  const globals = preserveGlobals(globalNames);
  const browser = installFakeBrowser();
  clock.advance(10_000);
  try {
    view.scrollY = 5_000;
    let shrink = 0;
    const rootRef = browser.root as unknown;
    const rootWithRect = rootRef as RootWithRect;
    rootWithRect.getBoundingClientRect = () => ({ top: -4_900, bottom: -3_300 - shrink });
    const anchor = captureViewportAnchor(browser.root);
    assert.equal(anchor?.edge, "bottom");
    shrink = 60;
    assert.equal(compensateViewportAnchor(browser.root, anchor), true);
    assert.equal(view.scrollY, 4_940);

    rootWithRect.getBoundingClientRect = () => ({ top: 900, bottom: 2_500 });
    assert.equal(captureViewportAnchor(browser.root), null);
  } finally {
    restoreGlobals(globals);
  }
});

test("a detached anchor node cancels the compensation", () => {
  const globals = preserveGlobals(globalNames);
  const browser = installFakeBrowser();
  view.scrollY = 100;
  clock.advance(10_000);
  try {
    const anchor = captureViewportAnchor(browser.root);
    browser.paragraph.isConnected = false;
    browser.layoutShift = 80;
    assert.equal(compensateViewportAnchor(browser.root, anchor), false);
    assert.equal(view.scrollY, 100);
  } finally {
    restoreGlobals(globals);
  }
});

interface RootWithDoc {
  ownerDocument: FakeOwnerDocument;
}

test("the scroller's native anchoring is held during a job and handed back afterwards", () => {
  const globals = preserveGlobals(globalNames);
  const browser = installFakeBrowser();
  try {
    const rootRef = browser.root as unknown;
    const rootWithDoc = rootRef as RootWithDoc;
    const owner = rootWithDoc.ownerDocument.documentElement;
    owner.style.setProperty("overflow-anchor", "auto", "important");
    holdNativeScrollAnchoring(browser.root);
    assert.equal(owner.style.getPropertyValue("overflow-anchor"), "none");
    holdNativeScrollAnchoring(browser.root);
    releaseNativeScrollAnchoring(browser.root);
    assert.equal(owner.style.getPropertyValue("overflow-anchor"), "auto");
    assert.equal(owner.style.getPropertyPriority("overflow-anchor"), "important");
    releaseNativeScrollAnchoring(browser.root);
    assert.equal(owner.style.getPropertyValue("overflow-anchor"), "auto");
  } finally {
    restoreGlobals(globals);
  }
});

interface RootWithParent {
  parentElement: FakeScroller;
}

interface ScrollerComputedStyle {
  overflowY: string;
  overflow: string;
}

type ScrollerStyleFn = (element: unknown) => ScrollerComputedStyle;

test("compensation targets the nearest scrollable ancestor before the window", () => {
  const globals = preserveGlobals(globalNames);
  const browser = installFakeBrowser();
  view.scrollY = 100;
  clock.advance(10_000);
  try {
    const scroller: FakeScroller = {
      parentElement: null,
      scrollTop: 40,
      scrollHeight: 4_000,
      clientHeight: 800,
    };
    const rootRef = browser.root as unknown;
    const rootWithParent = rootRef as RootWithParent;
    rootWithParent.parentElement = scroller;
    const writableGlobals = globalThis as { getComputedStyle: typeof globalThis.getComputedStyle };
    const scrollerStyleFn: ScrollerStyleFn = (element) => element === scroller
      ? { overflowY: "auto", overflow: "auto" }
      : { overflowY: "visible", overflow: "visible" };
    const getComputedStyleRef = scrollerStyleFn as unknown;
    writableGlobals.getComputedStyle = getComputedStyleRef as typeof globalThis.getComputedStyle;
    const anchor: ViewportAnchor | null = captureViewportAnchor(browser.root);
    browser.layoutShift = 60;
    assert.equal(compensateViewportAnchor(browser.root, anchor), true);
    assert.equal(scroller.scrollTop, 100);
    assert.equal(view.scrollY, 100);
  } finally {
    restoreGlobals(globals);
  }
});
