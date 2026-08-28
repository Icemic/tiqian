import assert from "node:assert/strict";
import test from "node:test";
import {
  preserveGlobals,
  restoreGlobals,
  installFakeClock,
  CLOCK_GLOBALS,
} from "./test-clock.js";
import {
  driveDeclaredFaceWakeTimeline,
  driveElementTimeline,
  ELEMENT_DRIVE_GLOBALS,
} from "./timing-golden-host.js";
import { initializeGlobalServices } from "@tiqian/core/core/services/global-services.js";
initializeGlobalServices();


test("element entry imports without browser globals during SSR", async () => {
  const globals = preserveGlobals(["document", "HTMLElement", "customElements"]);
  try {
    delete (globalThis as Record<string, unknown>)["document"];
    delete (globalThis as Record<string, unknown>)["HTMLElement"];
    delete (globalThis as Record<string, unknown>)["customElements"];

    const module = await import(`../element.js?ssr=${Date.now()}`);

    assert.equal(typeof module.TiqianProseElement, "function");
  } finally {
    restoreGlobals(globals);
  }
});

test("element entry registers the browser custom element through registerTiqianProse", async () => {
  const globals = preserveGlobals(["document", "HTMLElement", "customElements"]);
  const elements = new Map();
  class FakeHTMLElement {}
  try {
    delete (globalThis as Record<string, unknown>)["document"];
    (globalThis as Record<string, unknown>)["HTMLElement"] = FakeHTMLElement;
    (globalThis as Record<string, unknown>)["customElements"] = {
      define(name: string, constructor: CustomElementConstructor) {
        assert.equal(elements.has(name), false);
        elements.set(name, constructor);
      },
      get(name: string) {
        return elements.get(name);
      },
    };

    const module = await import(`../element.js?browser=${Date.now()}`);

    // The /element import itself stays side-effect free; registration is the
    // explicit, idempotent registerTiqianProse() entry (wc-s6 scope item 2).
    assert.equal(customElements.get("tiqian-prose"), undefined);
    module.registerTiqianProse();
    assert.strictEqual(elements.get("tiqian-prose"), module.TiqianProseElement);
    assert.ok(module.TiqianProseElement.prototype instanceof FakeHTMLElement);
    // A repeat registration must not reach define again for the same tag.
    module.registerTiqianProse();
    assert.strictEqual(elements.get("tiqian-prose"), module.TiqianProseElement);
  } finally {
    restoreGlobals(globals);
  }
});

test("disabled is reversible and cancels stale initial font work", async () => {
  const globalNames = [
    "document",
    "HTMLElement",
    "customElements",
    "getComputedStyle",
    "MutationObserver",
    "requestAnimationFrame",
    "cancelAnimationFrame",
    "setTimeout",
    "clearTimeout",
    "window",
    "TiqianWeb",
  ];
  const globals = preserveGlobals(globalNames);
  const documentListeners = new Map();
  const fontListeners = new Map();
  const fontLoads: Array<(value: unknown) => void> = [];
  const timers = new Set();
  let nextTimer = 1;

  class FakeMutationObserver {
    callback: (...args: unknown[]) => void;
    constructor(callback: (...args: unknown[]) => void) {
      this.callback = callback;
    }
    observe() {}
    disconnect() {}
  }

  class FakeHTMLElement {
    attributes: Map<string, string>;
    dataset: Record<string, string | undefined>;
    isConnected: boolean;
    parentElement: null;
    listeners: Map<string, (...args: unknown[]) => void>;
    paragraph: {
      textContent: string;
      hasAttribute(): boolean;
      getAttribute(): null;
      querySelectorAll(): never[];
    };
    constructor() {
      this.attributes = new Map();
      this.dataset = {};
      this.isConnected = true;
      this.parentElement = null;
      this.listeners = new Map();
      this.paragraph = {
        textContent: "正文",
        hasAttribute() {
          return false;
        },
        getAttribute() {
          return null;
        },
        querySelectorAll() {
          return [];
        },
      };
    }
    addEventListener(name: string, listener: (...args: unknown[]) => void) {
      this.listeners.set(name, listener);
    }
    removeEventListener(name: string, listener: (...args: unknown[]) => void) {
      if (this.listeners.get(name) === listener) this.listeners.delete(name);
    }
    getAttribute(name: string) {
      return this.attributes.get(name) ?? null;
    }
    hasAttribute(name: string) {
      return this.attributes.has(name);
    }
    setAttribute(name: string, value: string | number) {
      this.attributes.set(name, String(value));
    }
    removeAttribute(name: string) {
      this.attributes.delete(name);
    }
    toggleAttribute(name: string, force: boolean) {
      if (force) this.setAttribute(name, "");
      else this.removeAttribute(name);
    }
    querySelector() {
      return null;
    }
    querySelectorAll(selector: string) {
      return selector === "p, li" ? [this.paragraph] : [];
    }
    getBoundingClientRect() {
      return { width: 640 };
    }
  }

  const fonts = {
    status: "loading",
    load() {
      return new Promise((resolve) => fontLoads.push(resolve));
    },
    addEventListener(name: string, listener: (...args: unknown[]) => void) {
      fontListeners.set(name, listener);
    },
    removeEventListener(name: string, listener: (...args: unknown[]) => void) {
      if (fontListeners.get(name) === listener) fontListeners.delete(name);
    },
  };
  const documentObject = {
    fonts,
    addEventListener(name: string, listener: (...args: unknown[]) => void) {
      documentListeners.set(name, listener);
    },
    removeEventListener(name: string, listener: (...args: unknown[]) => void) {
      if (documentListeners.get(name) === listener) documentListeners.delete(name);
    },
    dispatchEvent() {},
    getElementById() {
      return null;
    },
    querySelectorAll() {
      return [];
    },
  };
  const elements = new Map();

  try {
    (globalThis as Record<string, unknown>)["document"] = documentObject;
    (globalThis as Record<string, unknown>)["HTMLElement"] = FakeHTMLElement;
    (globalThis as Record<string, unknown>)["customElements"] = {
      define(name: string, constructor: CustomElementConstructor) {
        elements.set(name, constructor);
      },
      get(name: string) {
        return elements.get(name);
      },
    };
    (globalThis as Record<string, unknown>)["getComputedStyle"] = (element: FakeHTMLElement) => ({
      getPropertyValue(property: string) {
        if (element instanceof FakeHTMLElement && property === "--tq-styles-ready") return "1";
        return {
          "font-family": '"Example CJK", sans-serif',
          "font-size": "16px",
          "font-style": "normal",
          "font-weight": "400",
          "font-stretch": "100%",
        }[property] ?? "";
      },
    });
    (globalThis as Record<string, unknown>)["MutationObserver"] = FakeMutationObserver;
    (globalThis as Record<string, unknown>)["requestAnimationFrame"] = (callback: (time: number) => void) => {
      queueMicrotask(() => callback(0));
      return 1;
    };
    (globalThis as Record<string, unknown>)["cancelAnimationFrame"] = () => {};
    (globalThis as Record<string, unknown>)["setTimeout"] = (callback: () => void) => {
      const id = nextTimer++;
      queueMicrotask(() => {
        if (!timers.has(id)) callback();
      });
      return id;
    };
    (globalThis as Record<string, unknown>)["clearTimeout"] = (id: number) => timers.add(id);
    (globalThis as Record<string, unknown>)["window"] = {
      addEventListener() {},
      removeEventListener() {},
    };

    const module = await import(`../element.js?font-lifecycle=${Date.now()}`);
    const element = new module.TiqianProseElement();
    element.ownerDocument = documentObject;
    element.disabled = true;
    element.connectedCallback();
    await new Promise((resolve) => setImmediate(resolve));

    assert.equal(element.disabled, true);
    assert.equal(fontLoads.length, 0);
    assert.equal(fontListeners.has("loadingdone"), false);
    assert.equal(fontListeners.has("loadingerror"), false);

    element.disabled = false;
    element.attributeChangedCallback("disabled", "", null);
    await new Promise((resolve) => setImmediate(resolve));

    assert.equal(element.disabled, false);
    assert.equal(element.dataset.tiqianFontWait, "timeout");
    assert.equal(element.hasAttribute("data-tiqian-enhanced"), false);
    assert.equal(fontLoads.length, 1);
    assert.equal(fontListeners.has("loadingdone"), true);
    assert.equal(fontListeners.has("loadingerror"), true);

    fontListeners.get("loadingdone")({
      fontfaces: [{ family: "Example CJK", weight: "400", style: "normal" }],
    });
    await new Promise((resolve) => setImmediate(resolve));

    assert.equal(fontLoads.length, 2);
    assert.equal(element.dataset.tiqianFontWait, "timeout");

    element.setAttribute("emphasis-dot-gap-em", "0.2");
    element.attributeChangedCallback("emphasis-dot-gap-em", null, "0.2");
    await new Promise((resolve) => setImmediate(resolve));

    assert.equal(fontLoads.length, 3);
    assert.equal(element.dataset.tiqianFontWait, "timeout");

    element.disabled = true;
    element.attributeChangedCallback("disabled", null, "");
    assert.equal(element.dataset.tiqianFontWait, undefined);
    assert.equal(fontListeners.has("loadingdone"), false);
    assert.equal(fontListeners.has("loadingerror"), false);

    element.isConnected = false;
    element.disconnectedCallback();
    fontLoads.forEach((resolve) => resolve([]));
    await new Promise((resolve) => setImmediate(resolve));

    assert.equal(fontLoads.length, 3);
    assert.equal(fontListeners.has("loadingdone"), false);
    assert.equal(fontListeners.has("loadingerror"), false);
    assert.equal(element.dataset.tiqianFontWait, undefined);
  } finally {
    restoreGlobals(globals);
  }
});

test("declared face change wakes revalidate and merge per root", async () => {
  const globals = preserveGlobals([...CLOCK_GLOBALS, ...ELEMENT_DRIVE_GLOBALS]);
  const clock = installFakeClock();
  try {
    const record = await driveDeclaredFaceWakeTimeline(clock, "declared-wake");
    const wake = record.declaredWake as { paragraphQueries?: Record<string, number> } | undefined;
    const dispatchesIn = (phase: string) =>
      record.datasetWrites.filter(
        (write) => write.phase === phase && write.op === "set" && write.key === "tiqianEnhanceOptions",
      ).length;

    assert.equal(dispatchesIn("w1-declared-merge"), 1,
      "two same-frame declarations merge into one revalidate cycle");
    assert.ok((wake?.paragraphQueries?.["w1-declared-merge"] ?? 0) >= 1,
      "the merged wake reaches a scheduled typography check");

    assert.equal(dispatchesIn("w2-declared-later"), 1,
      "a later declaration revalidates again");
    assert.ok((wake?.paragraphQueries?.["w2-declared-later"] ?? 0) >= 1,
      "the later wake reaches a scheduled typography check");

    assert.equal(dispatchesIn("w3-disabled"), 0,
      "a disabled element no longer wakes");
    assert.equal(wake?.paragraphQueries?.["w3-disabled"] ?? 0, 0,
      "no typography check executes after the element stops observing");
  } finally {
    restoreGlobals(globals);
  }
});

test("element exact font contract mismatch writes structured detail to tiqianSnapshotFontMiss dataset", async () => {
  const globals = preserveGlobals([...CLOCK_GLOBALS, ...ELEMENT_DRIVE_GLOBALS]);
  const clock = installFakeClock();
  try {
    const record = await driveElementTimeline(clock, "element-snapshot-font-miss-shape", {
      fontFaceSrc: "url(\"/assets/mismatch-deadbeef.woff2\")",
    });
    const missWrite = record.datasetWrites.find((w) => w.key === "tiqianSnapshotFontMiss");
    assert.ok(missWrite, "tiqianSnapshotFontMiss dataset write was recorded");
    assert.match(
      missWrite.value!,
      /^SnapshotFontContractMismatch\|FieldMismatch\|expectedFaces=\d+\|actualFaces=\d+\|firstField=\w+$/u,
    );
    assert.equal(
      missWrite.value,
      "SnapshotFontContractMismatch|FieldMismatch|expectedFaces=1|actualFaces=1|firstField=src",
    );
  } finally {
    restoreGlobals(globals);
  }
});
