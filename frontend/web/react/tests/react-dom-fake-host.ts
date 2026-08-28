// Drives react-dom/client over the repo's fake DOM (the runtime-host world),
// so the reference React binding runs its four lifecycle paths on the same
// DOM stub shape the web-component tests use. react-dom needs a handful of
// browser globals the fake world does not model; the shims below are the
// minimum that keeps createRoot/render/act running without touching the
// engine's fixture surface.

import { FakeElement, FakeText } from "../../npm/tests/snapshot-dom-fixtures.js";
import {
  drainMicrotasks,
  flushAllTestAnimationFrames,
} from "../../npm/tests/runtime-host.js";
import { act } from "react";
import { createRoot } from "react-dom/client";

(globalThis as Record<string, unknown>).IS_REACT_ACT_ENVIRONMENT = true;
// react-dom's focus restore walks `element instanceof window.HTMLIFrameElement`;
// the fake world has no frames, so a dummy constructor makes the check false.
(globalThis as Record<string, unknown>).HTMLIFrameElement ??= class HTMLIFrameElementShim {};

// react-dom commits text updates through nodeValue; the fixture text node
// stores its payload in `value`.
if (!Object.getOwnPropertyDescriptor(FakeText.prototype, "nodeValue")) {
  Object.defineProperty(FakeText.prototype, "nodeValue", {
    get() {
      return this.value;
    },
    set(next) {
      this.value = String(next);
    },
    configurable: true,
  });
}

export function createReactHarness(): {
  container: Element;
  render: (element: React.ReactElement) => Promise<void>;
  unmount: () => Promise<void>;
  dispose: () => void;
} {
  const container = globalThis.document.createElement("div");
  globalThis.document.body.appendChild(container);
  const root = createRoot(container);
  return {
    container,
    async render(element: React.ReactElement): Promise<void> {
      await act(async () => {
        root.render(element);
      });
    },
    async unmount(): Promise<void> {
      await act(async () => {
        root.unmount();
      });
    },
    dispose(): void {
      container.parentNode?.removeChild?.(container);
    },
  };
}

// Settles the enhancement lifecycle on the fake clock: flushes test animation
// frames and drains microtasks until every paragraph under the root reports
// data-tq-rendered, mirroring how the journey hosts settle a mount.
export async function settleEnhanced(
  rootElement: Element | FakeElement,
  paragraphSelector = "p"
): Promise<void> {
  for (let attempt = 0; attempt < 240; attempt += 1) {
    flushAllTestAnimationFrames();
    await drainMicrotasks(6);
    const selection = rootElement.querySelectorAll(paragraphSelector);
    // The fixture world returns a plain array; the DOM lib returns a
    // NodeList. instanceof keeps both branches truthful without casting
    // the union through Array.from.
    const paragraphs: Array<Element | FakeElement> =
      selection instanceof Array ? selection : Array.from(selection);
    if (
      paragraphs.length > 0 &&
      paragraphs.every((paragraph) => paragraph.getAttribute("data-tq-rendered") === "true")
    ) {
      return;
    }
  }
  throw new Error("settleEnhanced: paragraphs never reached data-tq-rendered");
}

// Canonical deep-geometry serialization for the parity comparison: tag,
// sorted attribute pairs, the serialized inline style, and the child
// geometry. Both enhancement paths render through the same engine, so equal
// serializations prove the binding path reproduces the web-component
// geometry exactly.
export function deepGeometry(node: Node): unknown {
  if (node.nodeType === 3) return ["#text", node.textContent];
  if (node.nodeType !== 1) return null;
  const element = node as Element;
  const attributes: Array<[string, string]> = [];
  for (const raw of element.attributes ?? []) {
    // Real DOM iterates Attr objects; the fixture world stores Map entries,
    // so each item is both shapes at once and the ?? fallbacks pick the
    // populated side.
    const entry = raw as Attr & [string, string];
    attributes.push([entry.name ?? entry[0], entry.value ?? entry[1]]);
  }
  attributes.sort(([left], [right]) => (left < right ? -1 : left > right ? 1 : 0));
  const style = (element as HTMLElement).style?.cssText ?? "";
  const children = Array.from(element.childNodes)
    .map(deepGeometry)
    .filter((child) => child !== null);
  return [element.tagName, attributes, style, children];
}