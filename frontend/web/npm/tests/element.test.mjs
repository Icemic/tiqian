import assert from "node:assert/strict";
import test from "node:test";
import {
  preserveGlobals,
  restoreGlobals,
  installFakeClock,
  CLOCK_GLOBALS,
} from "./test-clock.mjs";
import {
  driveDeclaredFaceWakeTimeline,
  driveElementTimeline,
  ELEMENT_DRIVE_GLOBALS,
} from "./timing-golden-host.mjs";

test("element entry imports without browser globals during SSR", async () => {
  const globals = preserveGlobals(["document", "HTMLElement", "customElements"]);
  try {
    delete globalThis.document;
    delete globalThis.HTMLElement;
    delete globalThis.customElements;

    const module = await import(`../element.js?ssr=${Date.now()}`);

    assert.equal(typeof module.TiqianProseElement, "function");
  } finally {
    restoreGlobals(globals);
  }
});

test("element entry still registers the browser custom element", async () => {
  const globals = preserveGlobals(["document", "HTMLElement", "customElements"]);
  const elements = new Map();
  class FakeHTMLElement {}
  try {
    delete globalThis.document;
    globalThis.HTMLElement = FakeHTMLElement;
    globalThis.customElements = {
      define(name, constructor) {
        assert.equal(elements.has(name), false);
        elements.set(name, constructor);
      },
      get(name) {
        return elements.get(name);
      },
    };

    const module = await import(`../element.js?browser=${Date.now()}`);

    assert.strictEqual(elements.get("tiqian-prose"), module.TiqianProseElement);
    assert.ok(module.TiqianProseElement.prototype instanceof FakeHTMLElement);
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
  const fontLoads = [];
  const timers = new Set();
  let nextTimer = 1;

  class FakeMutationObserver {
    constructor(callback) {
      this.callback = callback;
    }
    observe() {}
    disconnect() {}
  }

  class FakeHTMLElement {
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
    addEventListener(name, listener) {
      this.listeners.set(name, listener);
    }
    removeEventListener(name, listener) {
      if (this.listeners.get(name) === listener) this.listeners.delete(name);
    }
    getAttribute(name) {
      return this.attributes.get(name) ?? null;
    }
    hasAttribute(name) {
      return this.attributes.has(name);
    }
    setAttribute(name, value) {
      this.attributes.set(name, String(value));
    }
    removeAttribute(name) {
      this.attributes.delete(name);
    }
    toggleAttribute(name, force) {
      if (force) this.setAttribute(name, "");
      else this.removeAttribute(name);
    }
    querySelector() {
      return null;
    }
    querySelectorAll(selector) {
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
    addEventListener(name, listener) {
      fontListeners.set(name, listener);
    },
    removeEventListener(name, listener) {
      if (fontListeners.get(name) === listener) fontListeners.delete(name);
    },
  };
  const documentObject = {
    fonts,
    addEventListener(name, listener) {
      documentListeners.set(name, listener);
    },
    removeEventListener(name, listener) {
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
    globalThis.document = documentObject;
    globalThis.HTMLElement = FakeHTMLElement;
    globalThis.customElements = {
      define(name, constructor) {
        elements.set(name, constructor);
      },
      get(name) {
        return elements.get(name);
      },
    };
    globalThis.getComputedStyle = (element) => ({
      getPropertyValue(property) {
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
    globalThis.MutationObserver = FakeMutationObserver;
    globalThis.requestAnimationFrame = (callback) => {
      queueMicrotask(() => callback(0));
      return 1;
    };
    globalThis.cancelAnimationFrame = () => {};
    globalThis.setTimeout = (callback) => {
      const id = nextTimer++;
      queueMicrotask(() => {
        if (!timers.has(id)) callback();
      });
      return id;
    };
    globalThis.clearTimeout = (id) => timers.add(id);
    globalThis.window = {
      addEventListener() {},
      removeEventListener() {},
    };

    const module = await import(`../element.js?font-lifecycle=${Date.now()}`);
    const element = new module.TiqianProseElement();
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
  // Real-element drive: the timing-golden host grafts the element onto its
  // fixture world and settles it through S1, then registers declared faces
  // while the recording engine stub answers every engine call. The
  // assertions read the element's own forced-check path end to end: registry
  // notify, source subscription, scheduleTypographyCheck(true) through rAF
  // dedup, snapshot invalidation, and the enhance dispatch that follows.
  // Declared sheets never enter the CSSOM the typography signature reads, so
  // an unforced check would dedup and produce no engine call at all.
  const globals = preserveGlobals([...CLOCK_GLOBALS, ...ELEMENT_DRIVE_GLOBALS]);
  const clock = installFakeClock();
  try {
    const record = await driveDeclaredFaceWakeTimeline(clock, "declared-wake");
    const wake = record.declaredWake;

    // Two same-frame declarations merge into exactly one revalidate cycle:
    // one forced check, one snapshot invalidation, one progressive dispatch.
    // A wake without the rAF dedup would dispatch twice.
    assert.equal(wake.w1RevalidateCalls, 1,
      "two same-frame declarations merge into one revalidate cycle");
    assert.ok((wake.paragraphQueries["w1-declared-merge"] ?? 0) >= 1,
      "the merged wake reaches a scheduled typography check");

    // After the job the first wake opened completes, the root observes
    // again; a later declaration forces one fresh refresh cycle: the source
    // refresh destroys the prior runtime state, then dispatches.
    assert.equal(wake.w2RevalidateCalls, 2,
      "a later declaration revalidates again");
    assert.ok((wake.paragraphQueries["w2-declared-later"] ?? 0) >= 1,
      "the later wake reaches a scheduled typography check");

    // A disabled element unsubscribed from the declared-face registry: the
    // declaration produces neither a scheduled check nor an engine call.
    assert.equal(wake.w3RevalidateCalls, 0,
      "a disabled element no longer wakes");
    assert.equal(wake.paragraphQueries["w3-disabled"] ?? 0, 0,
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
      missWrite.value,
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
