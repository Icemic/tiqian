// Era adapter family F4: the enhanced element context era (672f14bc onward).
// Coordinated flow: the <tiqian-prose> elements mount through the public
// registration surface (importing the element entry registers the custom
// element; the host calls registerTiqianProse()). One-shot flow: a fresh
// createEnhanceContext per root replayed with the options the coordinated
// run resolved and published on the root dataset, mirroring the HEAD
// demo test's __tiqianOneShot replay path (demo/web/main.js).
const element = await import("@tiqian/prose/element");
const core = await import("@tiqian/core/core/engine/context/enhance-context.js");

if (typeof element.registerTiqianProse === "function") {
  element.registerTiqianProse();
}

const rootsOf = () => Array.from(document.querySelectorAll("tiqian-prose, [data-tiqian-root]"));

globalThis.__historyOptions = { firstLineIndentIc: 0 };

// Elements already in the DOM upgrade on registration; nothing else to
// trigger. The terminal gate in the harness waits for all paragraphs.
globalThis.__historyEnhance = () => {};

globalThis.__historyOneShot = () => {
  for (const root of rootsOf()) {
    const raw = root.dataset?.tiqianEnhanceOptions;
    const options = raw ? JSON.parse(raw) : { firstLineIndentIc: 0 };
    core.createEnhanceContext(root, options).mount();
  }
};

const PARAS_SELECTOR = "tiqian-prose p, tiqian-prose li, [data-tiqian-root] p, [data-tiqian-root] li";

globalThis.__historyTerminal = () =>
  Array.from(document.querySelectorAll(PARAS_SELECTOR)).every((p) =>
    p.getAttribute("data-tq-rendered") === "true" ||
    p.hasAttribute("data-tiqian-capability-issue"));

globalThis.__historyReady = true;
