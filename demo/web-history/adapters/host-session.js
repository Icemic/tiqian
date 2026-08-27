// Era adapter family F3: the prose host session era (bed4c791 onward).
// Coordinated flow: the <tiqian-prose> elements mount through the public
// registration surface of the era (importing the element entry registers the
// custom element; from 9561c747 on the host calls registerTiqianProse()).
// One-shot flow: a fresh createProseHostSession per root replayed with the
// options the coordinated run resolved and published on the root dataset
// (4370925f), mirroring the HEAD demo test's replay path.
const element = await import("@tiqian/prose/element");
const core = await import("@tiqian/core/core/engine/prose-host-session.js");

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
    core.createProseHostSession(root, options).mount();
  }
};

const PARAS_SELECTOR = "tiqian-prose p, tiqian-prose li, [data-tiqian-root] p, [data-tiqian-root] li";

globalThis.__historyTerminal = () =>
  Array.from(document.querySelectorAll(PARAS_SELECTOR)).every((p) =>
    p.getAttribute("data-tq-rendered") === "true" ||
    p.hasAttribute("data-tiqian-capability-issue"));

globalThis.__historyReady = true;
