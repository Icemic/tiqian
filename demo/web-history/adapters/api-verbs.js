// Era adapter family F2: the api.js verb era after the Kotlin document
// event channel was dropped (876612ef) and before the api entry itself was
// dissolved (acdce952). The package "." entry still exports the enhance
// verbs; enhanceProgressively is the coordinated verb and enhance is the
// atomic one-shot verb.
const api = await import("@tiqian/prose");

const HISTORY_OPTIONS = { firstLineIndentIc: 0 };

const rootsOf = () => Array.from(document.querySelectorAll("tiqian-prose, [data-tiqian-root]"));

globalThis.__historyOptions = HISTORY_OPTIONS;

globalThis.__historyEnhance = async () => {
  for (const root of rootsOf()) {
    await api.enhanceProgressively(root, HISTORY_OPTIONS);
  }
};

globalThis.__historyOneShot = async () => {
  for (const root of rootsOf()) {
    await api.enhance(root, HISTORY_OPTIONS);
  }
};

const PARAS_SELECTOR = "tiqian-prose p, tiqian-prose li, [data-tiqian-root] p, [data-tiqian-root] li";

globalThis.__historyTerminal = () =>
  Array.from(document.querySelectorAll(PARAS_SELECTOR)).every((p) =>
    p.getAttribute("data-tq-rendered") === "true" ||
    p.hasAttribute("data-tiqian-capability-issue"));

globalThis.__historyReady = true;
