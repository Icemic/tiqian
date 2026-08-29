// Reference React binding tests: the four lifecycle paths (mount, options
// update, unmount, relayout-ready) driven through react-dom/client over the
// runtime-host fake DOM, plus a deep-geometry parity comparison against the
// web-component translation layer over the same demo corpus.

import assert from "node:assert/strict";
import test from "node:test";
import React from "react";
import { TiqianProse, useTiqianProse } from "../binding.js";
import {
  cleanupMounted,
  drainMicrotasks,
  flushAllTestAnimationFrames,
  installTestAnimationFrames,
  loadHostRuntime,
  mount,
  probe,
  setElementRect,
} from "../../web-component/tests/runtime-host.js";
import {
  createReactHarness,
  deepGeometry,
  settleEnhanced,
} from "./react-dom-fake-host.js";
import type { FakeElement } from "../../web-component/tests/snapshot-dom-fixtures.js";
import type { TiqianProseElement } from "../../web-component/element.js";

// Demo corpus excerpt: the node-stack counterpart of demo/web's paragraphs,
// shared verbatim between the two enhancement paths under test.
const DEMO_CORPUS = [
  "在现代 Web 渲染架构中，从 HTML 字符串到屏幕像素的转化经历了一条高度复杂的流水线。",
  "中文排版具有独特的网格化特征，汉字原则上严格基于全角正方形的字身框进行排列。",
  "在行首与行尾的禁则处理中，排版引擎还需严格遵循避头尾规则。",
];

function corpusHtml(): string {
  return DEMO_CORPUS.map((text) => `<p>${text}</p>`).join("");
}

interface ContextHolder {
  current: unknown;
}

interface CapturedHolder {
  contextRef: ContextHolder;
}

interface HarnessOptionsProps {
  options: Record<string, unknown>;
}

test("mount: the hook creates and mounts the EnhancedElementContext directly", async (t) => {
  t.after(cleanupMounted);
  installTestAnimationFrames();
  await loadHostRuntime();
  const root = mount(`<div data-tiqian-root="true">${corpusHtml()}</div>`);
  setElementRect(root, 0, 360);

  const harness = createReactHarness();
  t.after(() => harness.dispose());
  const captured: CapturedHolder = { contextRef: { current: null } };
  function Harness() {
    captured.contextRef = useTiqianProse(probe<Element>(root)).contextRef;
    return null;
  }

  await harness.render(React.createElement(Harness));
  const context = captured.contextRef.current;
  assert.ok(context, "the mount effect created the context");
  assert.strictEqual((context as Record<string, unknown>).element, root, "the context is bound to the host element");
  assert.equal(typeof (context as Record<string, unknown>).mount, "function");
  assert.equal(typeof (context as Record<string, unknown>).updateOptions, "function");
  assert.equal(typeof (context as Record<string, unknown>).destroy, "function");

  await settleEnhanced(root);
  assert.equal((context as Record<string, unknown>).isConnected, true, "the mounted context reports connected");
  assert.equal(root.querySelector("p")?.getAttribute("data-tq-rendered"), "true");
});

test("options update: new option objects flow through updateOptions", async (t) => {
  t.after(cleanupMounted);
  installTestAnimationFrames();
  await loadHostRuntime();
  const root = mount(`<div data-tiqian-root="true">${corpusHtml()}</div>`);
  setElementRect(root, 0, 360);

  const harness = createReactHarness();
  t.after(() => harness.dispose());
  const captured: CapturedHolder = { contextRef: { current: null } };
  function Harness({ options }: HarnessOptionsProps) {
    captured.contextRef = useTiqianProse(probe<Element>(root), options).contextRef;
    return null;
  }

  await harness.render(React.createElement(Harness, { options: {} }));
  await settleEnhanced(root);
  const context = captured.contextRef.current;
  assert.equal(((context as Record<string, unknown>).optionsLedger as Record<string, unknown>)?.strongAsEmphasisMarks, false);

  await harness.render(
    React.createElement(Harness, { options: { strongAsEmphasisMarks: true } }),
  );
  assert.equal(
    ((context as Record<string, unknown>).optionsLedger as Record<string, unknown>)?.strongAsEmphasisMarks,
    true,
    "the re-render pushed the new options through updateOptions",
  );
  assert.equal((context as Record<string, unknown>).isConnected, true, "the options reaction keeps the root mounted");
  await settleEnhanced(root);
});

test("unmount: the effect cleanup settles the lifecycle and releases the context", async (t) => {
  t.after(cleanupMounted);
  installTestAnimationFrames();
  await loadHostRuntime();
  const root = mount(`<div data-tiqian-root="true">${corpusHtml()}</div>`);
  setElementRect(root, 0, 360);

  const harness = createReactHarness();
  t.after(() => harness.dispose());
  const captured: CapturedHolder = { contextRef: { current: null } };
  function Harness() {
    captured.contextRef = useTiqianProse(probe<Element>(root)).contextRef;
    return null;
  }

  await harness.render(React.createElement(Harness));
  await settleEnhanced(root);
  const context = captured.contextRef.current;
  assert.equal(((context as Record<string, unknown>).stateMachine as Record<string, unknown>)?.connected, true);

  await harness.unmount();
  flushAllTestAnimationFrames();
  await drainMicrotasks(6);
  assert.equal(captured.contextRef.current, null, "the cleanup released the context ref");
  // DetachedNavigationDisposal: the settle is a detach, not a destroy. The
  // rawDom backups and the weak runtime state stay for reconciler-move
  // re-adoption, so the rendered state survives the settle; React's own
  // node removal carries the DOM.
  assert.equal(((context as Record<string, unknown>).stateMachine as Record<string, unknown>)?.connected, false, "the settle disconnected the state machine");
  assert.equal(root.querySelector("p")?.getAttribute("data-tq-rendered"), "true");

  // Component face: React removes the host element together with the tree.
  const componentHarness = createReactHarness();
  t.after(() => componentHarness.dispose());
  await componentHarness.render(
    React.createElement(TiqianProse, { options: { disabled: true } }, React.createElement("p", null, DEMO_CORPUS[0])),
  );
  const host = componentHarness.container.querySelector("tiqian-prose");
  assert.ok(host, "the component rendered the tiqian-prose host element");
  probe<HTMLElement>(host).style.setProperty("--tq-styles-ready", "1");
  setElementRect(probe<FakeElement>(host), 0, 360);
  await componentHarness.render(
    React.createElement(TiqianProse, { options: { disabled: false } }, React.createElement("p", null, DEMO_CORPUS[0])),
  );
  await settleEnhanced(host);
  await componentHarness.unmount();
  assert.equal(
    componentHarness.container.querySelector("tiqian-prose"),
    null,
    "the React teardown removed the host element",
  );
});

test("relayout-ready: the subscription prop surfaces the notification", async (t) => {
  t.after(cleanupMounted);
  installTestAnimationFrames();
  await loadHostRuntime();
  const root = mount(`<div data-tiqian-root="true">${corpusHtml()}</div>`);
  setElementRect(root, 0, 360);

  const harness = createReactHarness();
  t.after(() => harness.dispose());
  const notifications: unknown[] = [];
  const captured: CapturedHolder = { contextRef: { current: null } };
  function Harness() {
    captured.contextRef = useTiqianProse(probe<Element>(root), {}, {
      onRelayoutReady: (detail: unknown) => notifications.push(detail),
    }).contextRef;
    return null;
  }

  await harness.render(React.createElement(Harness));
  await settleEnhanced(root);
  const baseline = notifications.length;

  ((captured.contextRef.current as Record<string, unknown>).relayout as (() => void) | undefined)?.();
  await settleEnhanced(root);
  assert.ok(
    notifications.length > baseline,
    "relayout() produced a relayout-ready notification through the prop",
  );
});

test("component face: TiqianProse renders and enhances its own host element", async (t) => {
  t.after(cleanupMounted);
  installTestAnimationFrames();
  await loadHostRuntime();

  const harness = createReactHarness();
  t.after(() => harness.dispose());
  // Disabled-first mount mirrors a host whose shared stylesheet has not
  // loaded yet: the initial mount performs no enhance, the marker arrives,
  // and the enable reaction restarts the lifecycle past the styles gate.
  await harness.render(
    React.createElement(
      TiqianProse,
      { options: { disabled: true } },
      ...DEMO_CORPUS.map((text) => React.createElement("p", null, text)),
    ),
  );
  const host = harness.container.querySelector("tiqian-prose");
  assert.ok(host, "the component rendered the tiqian-prose host element");
  probe<HTMLElement>(host).style.setProperty("--tq-styles-ready", "1");
  setElementRect(probe<FakeElement>(host), 0, 360);
  await harness.render(
    React.createElement(
      TiqianProse,
      { options: { disabled: false } },
      ...DEMO_CORPUS.map((text) => React.createElement("p", null, text)),
    ),
  );
  await settleEnhanced(host);
  assert.equal(host.querySelectorAll("p").length, DEMO_CORPUS.length);
  for (const paragraph of host.querySelectorAll("p")) {
    assert.equal(paragraph.getAttribute("data-tq-rendered"), "true");
  }
});

test("parity: binding geometry matches the web-component path on the demo corpus", async (t) => {
  t.after(cleanupMounted);
  installTestAnimationFrames();
  const TiqianWeb = await loadHostRuntime();

  // Web-component translation layer path: the shell's programmatic enhance
  // entry over the same root markup.
  const wcRoot = mount(`<div data-tiqian-root="true">${corpusHtml()}</div>`);
  setElementRect(wcRoot, 0, 360);
  assert.equal(TiqianWeb.enhance(probe<Element>(wcRoot), null), DEMO_CORPUS.length);
  await settleEnhanced(wcRoot);

  // Binding path: the component face over the identical corpus, mounted
  // disabled first so the shared-styles marker can land before the enable
  // reaction runs the first enhance.
  const harness = createReactHarness();
  t.after(() => harness.dispose());
  const corpusChildren = () => DEMO_CORPUS.map((text) => React.createElement("p", null, text));
  await harness.render(
    React.createElement(TiqianProse, { options: { disabled: true } }, ...corpusChildren()),
  );
  const reactRoot = harness.container.querySelector("tiqian-prose");
  assert.ok(reactRoot, "react root element found");
  probe<HTMLElement>(reactRoot).style.setProperty("--tq-styles-ready", "1");
  setElementRect(probe<FakeElement>(reactRoot), 0, 360);
  await harness.render(
    React.createElement(TiqianProse, { options: { disabled: false } }, ...corpusChildren()),
  );
  await settleEnhanced(reactRoot);

  const wcParagraphs = wcRoot.querySelectorAll("p");
  const reactParagraphs = reactRoot.querySelectorAll("p");
  assert.equal(reactParagraphs.length, wcParagraphs.length);
  for (let index = 0; index < wcParagraphs.length; index += 1) {
    assert.deepEqual(
      deepGeometry(reactParagraphs[index]),
      deepGeometry(probe<Node>(wcParagraphs[index])),
      `paragraph ${index} geometry diverges between the binding and the web-component path`,
    );
  }
});