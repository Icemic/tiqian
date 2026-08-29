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

interface HistoryOptions {
  firstLineIndentIc: number;
}

interface EventChannelApiModule {
  loadTiqianRuntime?: () => Promise<void>;
}

interface EventDetailPayload {
  root: Element;
  options: HistoryOptions;
}

type RootGetter = () => Element[];
type Dispatcher = (type: string, detail: EventDetailPayload) => void;
type HistoryVerb = () => Promise<void> | void;
type TerminalCheck = () => boolean;

declare global {
  var __historyOptions: HistoryOptions | undefined;
  var __historyEnhance: HistoryVerb | undefined;
  var __historyOneShot: HistoryVerb | undefined;
  var __historyTerminal: TerminalCheck | undefined;
  var __historyReady: boolean | undefined;
}

const api: EventChannelApiModule = (await import("@tiqian/prose")) as EventChannelApiModule;
if (typeof api.loadTiqianRuntime === "function") {
  await api.loadTiqianRuntime();
}

const HISTORY_OPTIONS: HistoryOptions = { firstLineIndentIc: 0 };

const rootsOf: RootGetter = (): Element[] =>
  Array.from(document.querySelectorAll("tiqian-prose, [data-tiqian-root]"));

const dispatch: Dispatcher = (type: string, detail: EventDetailPayload): void => {
  document.dispatchEvent(new CustomEvent(type, { detail }));
};

globalThis.__historyOptions = HISTORY_OPTIONS;

globalThis.__historyEnhance = (): void => {
  for (const root of rootsOf()) {
    dispatch("tiqian:enhance-progressively", { root, options: HISTORY_OPTIONS });
  }
};

globalThis.__historyOneShot = (): void => {
  for (const root of rootsOf()) {
    dispatch("tiqian:enhance", { root, options: HISTORY_OPTIONS });
  }
};

const PARAS_SELECTOR: string = "tiqian-prose p, tiqian-prose li, [data-tiqian-root] p, [data-tiqian-root] li";

globalThis.__historyTerminal = (): boolean =>
  Array.from(document.querySelectorAll(PARAS_SELECTOR)).every((p: Element): boolean =>
    p.getAttribute("data-tq-rendered") === "true" ||
    p.hasAttribute("data-tiqian-capability-issue"));

globalThis.__historyReady = true;

export {};
