// Era adapter family F1: the Kotlin document event channel.
// Valid from b649841 up to the commit before 876612ef (which drops the
// document event channel and the globalThis bridge from Kotlin). The fixed
// invocation surface maps onto the public event verbs the Kotlin WebEnhancer
// installs at import time: tiqian:enhance-progressively is the coordinated
// verb, tiqian:enhance is the atomic one-shot verb. Typography options are
// pinned by the kit CSS; only firstLineIndentIc travels as an explicit
// option because it has no CSS form.
//
// The event listeners are installed by the Kotlin bundle's main() when it
// evaluates, so the api entry must be loaded (and with it the runtime)
// before any dispatch.
const api = await import("@tiqian/prose");
if (typeof api.loadTiqianRuntime === "function") {
  await api.loadTiqianRuntime();
}

const HISTORY_OPTIONS = { firstLineIndentIc: 0 };

const rootsOf = () => Array.from(document.querySelectorAll("tiqian-prose, [data-tiqian-root]"));
const dispatch = (type, detail) => {
  document.dispatchEvent(new CustomEvent(type, { detail }));
};

globalThis.__historyOptions = HISTORY_OPTIONS;

globalThis.__historyEnhance = () => {
  for (const root of rootsOf()) {
    dispatch("tiqian:enhance-progressively", { root, options: HISTORY_OPTIONS });
  }
};

globalThis.__historyOneShot = () => {
  for (const root of rootsOf()) {
    dispatch("tiqian:enhance", { root, options: HISTORY_OPTIONS });
  }
};

const PARAS_SELECTOR = "tiqian-prose p, tiqian-prose li, [data-tiqian-root] p, [data-tiqian-root] li";

globalThis.__historyTerminal = () =>
  Array.from(document.querySelectorAll(PARAS_SELECTOR)).every((p) =>
    p.getAttribute("data-tq-rendered") === "true" ||
    p.hasAttribute("data-tiqian-capability-issue"));

globalThis.__historyReady = true;
