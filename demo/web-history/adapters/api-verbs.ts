// Era adapter family F2: the api.js verb era after the Kotlin document
// event channel was dropped (876612ef) and before the api entry itself was
// dissolved (acdce952). The package "." entry still exports the enhance
// verbs; enhanceProgressively is the coordinated verb and enhance is the
// atomic one-shot verb.

interface HistoryOptions {
  firstLineIndentIc: number;
}

interface ApiVerbsModule {
  enhanceProgressively?: (root: Element, options: HistoryOptions) => Promise<void>;
  enhance?: (root: Element, options: HistoryOptions) => Promise<void>;
}

type RootGetter = () => Element[];
type TerminalCheck = () => boolean;
type HistoryVerb = () => Promise<void> | void;

declare global {
  var __historyOptions: HistoryOptions | undefined;
  var __historyEnhance: HistoryVerb | undefined;
  var __historyOneShot: HistoryVerb | undefined;
  var __historyTerminal: TerminalCheck | undefined;
  var __historyReady: boolean | undefined;
}

const api: ApiVerbsModule = (await import("@tiqian/prose")) as ApiVerbsModule;

const HISTORY_OPTIONS: HistoryOptions = { firstLineIndentIc: 0 };

const rootsOf: RootGetter = (): Element[] =>
  Array.from(document.querySelectorAll("tiqian-prose, [data-tiqian-root]"));

globalThis.__historyOptions = HISTORY_OPTIONS;

globalThis.__historyEnhance = async (): Promise<void> => {
  for (const root of rootsOf()) {
    await api.enhanceProgressively?.(root, HISTORY_OPTIONS);
  }
};

globalThis.__historyOneShot = async (): Promise<void> => {
  for (const root of rootsOf()) {
    await api.enhance?.(root, HISTORY_OPTIONS);
  }
};

const PARAS_SELECTOR: string = "tiqian-prose p, tiqian-prose li, [data-tiqian-root] p, [data-tiqian-root] li";

globalThis.__historyTerminal = (): boolean =>
  Array.from(document.querySelectorAll(PARAS_SELECTOR)).every((p: Element): boolean =>
    p.getAttribute("data-tq-rendered") === "true" ||
    p.hasAttribute("data-tiqian-capability-issue"));

globalThis.__historyReady = true;

export {};
