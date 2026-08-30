// Explicit registration and zero-config /auto entry contract (wc-s5 scope
// item 2). The tests drive registerTiqianProse against fake document,
// HTMLElement, and customElements globals: the explicit path proves the
// parameterized, idempotent registration and the copy-interception toggle,
// and the /auto path proves the one-import zero-config registration.
// Globals are swapped with Reflect so the tests carry no type assertions.

import assert from "node:assert/strict";
import test from "node:test";

const GLOBAL_NAMES = ["document", "HTMLElement", "customElements"] as const;

function preserveGlobals(): Map<string, unknown> {
  const saved = new Map<string, unknown>();
  for (const name of GLOBAL_NAMES) saved.set(name, Reflect.get(globalThis, name));
  return saved;
}

function restoreGlobals(saved: Map<string, unknown>): void {
  for (const [name, value] of saved) {
    if (value === undefined) Reflect.deleteProperty(globalThis, name);
    else Reflect.set(globalThis, name, value);
  }
}

function clearBrowserGlobals(): void {
  for (const name of GLOBAL_NAMES) Reflect.deleteProperty(globalThis, name);
}

// A registry that rejects a second define of the same name, so an accidental
// double registration fails the test instead of silently overwriting.
class FakeCustomElementRegistry {
  readonly definitions = new Map<string, unknown>();
  define(name: string, constructor: unknown): void {
    if (this.definitions.has(name)) throw new Error(`element already defined: ${name}`);
    this.definitions.set(name, constructor);
  }
  get(name: string): unknown {
    return this.definitions.get(name);
  }
}

interface FakeDocument {
  listeners: Map<string, unknown>;
  addEventListener(name: string, listener: unknown): void;
  removeEventListener(name: string, listener: unknown): void;
  querySelectorAll(): [];
}

function fakeDocument(): FakeDocument {
  const listeners = new Map<string, unknown>();
  return {
    listeners,
    addEventListener: (name, listener) => {
      listeners.set(name, listener);
    },
    removeEventListener: (name, listener) => {
      if (listeners.get(name) === listener) listeners.delete(name);
    },
    querySelectorAll: () => [],
  };
}

class FakeHTMLElement {}

test("registerTiqianProse registers the default tag and installs the copy interceptor", async () => {
  const saved = preserveGlobals();
  const registry = new FakeCustomElementRegistry();
  const documentObject = fakeDocument();
  try {
    clearBrowserGlobals();
    Reflect.set(globalThis, "HTMLElement", FakeHTMLElement);
    Reflect.set(globalThis, "customElements", registry);
    Reflect.set(globalThis, "document", documentObject);

    const module = await import(`../element.js?register-default=${Date.now()}`);

    // The /element import stays side-effect free; registration is explicit.
    assert.equal(registry.get("tiqian-prose"), undefined);
    module.registerTiqianProse();

    assert.equal(registry.get("tiqian-prose"), module.TiqianProseElement);
    // The default registration installs the source-faithful copy interceptor.
    assert.equal(documentObject.listeners.has("copy"), true);
    assert.equal(typeof module.registerTiqianProse, "function");
  } finally {
    restoreGlobals(saved);
  }
});

test("registerTiqianProse defines a custom tag and stays idempotent", async () => {
  const saved = preserveGlobals();
  const registry = new FakeCustomElementRegistry();
  try {
    clearBrowserGlobals();
    Reflect.set(globalThis, "HTMLElement", FakeHTMLElement);
    Reflect.set(globalThis, "customElements", registry);

    const module = await import(`../element.js?register-custom=${Date.now()}`);

    module.registerTiqianProse({ tagName: "custom-prose" });
    assert.equal(registry.get("custom-prose"), module.TiqianProseElement);

    // A repeat call for the same tag must not reach define again; the fake
    // registry throws on any second define of a name, so this would fail if
    // the idempotency guard regressed.
    module.registerTiqianProse({ tagName: "custom-prose" });
    assert.equal(registry.get("custom-prose"), module.TiqianProseElement);
  } finally {
    restoreGlobals(saved);
  }
});

test("interceptCopy=false skips the copy interceptor for the target document", async () => {
  const saved = preserveGlobals();
  const registry = new FakeCustomElementRegistry();
  try {
    // No document at import time: the import registers nothing, and the
    // explicit default registration defines the tag but installs no copy
    // interceptor without a document, leaving a clean baseline.
    clearBrowserGlobals();
    Reflect.set(globalThis, "HTMLElement", FakeHTMLElement);
    Reflect.set(globalThis, "customElements", registry);

    const module = await import(`../element.js?register-copy=${Date.now()}`);
    assert.equal(registry.get("tiqian-prose"), undefined);
    module.registerTiqianProse();
    assert.equal(registry.get("tiqian-prose"), module.TiqianProseElement);

    const copyless = fakeDocument();
    module.registerTiqianProse({ targetDocument: copyless, interceptCopy: false });
    assert.equal(copyless.listeners.has("copy"), false);

    const intercepted = fakeDocument();
    module.registerTiqianProse({ targetDocument: intercepted, interceptCopy: true });
    assert.equal(intercepted.listeners.has("copy"), true);
  } finally {
    restoreGlobals(saved);
  }
});

test("registerTiqianProse is a silent no-op without a browser environment", async () => {
  const saved = preserveGlobals();
  try {
    clearBrowserGlobals();

    const module = await import(`../element.js?register-ssr=${Date.now()}`);

    assert.equal(typeof module.TiqianProseElement, "function");
    assert.equal(typeof module.registerTiqianProse, "function");
    // No document, no HTMLElement, no registry: the call must not throw.
    module.registerTiqianProse();
  } finally {
    restoreGlobals(saved);
  }
});

test("the /auto entry registers the default tag on import", async () => {
  const saved = preserveGlobals();
  const registry = new FakeCustomElementRegistry();
  try {
    clearBrowserGlobals();
    Reflect.set(globalThis, "HTMLElement", FakeHTMLElement);
    Reflect.set(globalThis, "customElements", registry);

    await import(`../auto.js?register-auto=${Date.now()}`);

    assert.equal(typeof registry.get("tiqian-prose"), "function");
  } finally {
    restoreGlobals(saved);
  }
});
