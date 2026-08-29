// Era adapter family F4: the enhanced element context era (672f14bc onward).
// Coordinated flow: the <tiqian-prose> elements mount through the public
// registration surface (importing the element entry registers the custom
// element; the host calls registerTiqianProse()). One-shot flow: a fresh
// createEnhanceContext per root replayed with the options the coordinated
// run resolved and published on the root dataset, mirroring the HEAD
// demo test's __tiqianOneShot replay path (demo/web/main.js).

interface HistoryOptions {
  firstLineIndentIc: number;
}

interface EnhanceElementModule {
  registerTiqianProse?: () => void;
}

interface MountableContext {
  mount: () => Promise<void> | void;
}

interface EnhanceCoreModule {
  createEnhanceContext: (root: Element, options?: Record<string, unknown>) => MountableContext;
}

type RootGetter = () => HTMLElement[];
type TerminalCheck = () => boolean;
type HistoryVerb = () => Promise<void> | void;

declare global {
  var __historyOptions: HistoryOptions | undefined;
  var __historyEnhance: HistoryVerb | undefined;
  var __historyOneShot: HistoryVerb | undefined;
  var __historyTerminal: TerminalCheck | undefined;
  var __historyReady: boolean | undefined;
}

const element: EnhanceElementModule = (await import("@tiqian/prose/element")) as EnhanceElementModule;
const core: EnhanceCoreModule = (await import("@tiqian/core/core/engine/context/enhance-context.js")) as EnhanceCoreModule;

if (typeof element.registerTiqianProse === "function") {
  element.registerTiqianProse();
}

const rootsOf: RootGetter = (): HTMLElement[] =>
  Array.from(document.querySelectorAll<HTMLElement>("tiqian-prose, [data-tiqian-root]"));

globalThis.__historyOptions = { firstLineIndentIc: 0 };

// Elements already in the DOM upgrade on registration; nothing else to
// trigger. The terminal gate in the harness waits for all paragraphs.
globalThis.__historyEnhance = (): void => {};

globalThis.__historyOneShot = (): void => {
  for (const root of rootsOf()) {
    const raw: string | undefined = root.dataset?.tiqianEnhanceOptions;
    const options: Record<string, unknown> = raw
      ? (JSON.parse(raw) as Record<string, unknown>)
      : { firstLineIndentIc: 0 };
    core.createEnhanceContext(root, options).mount();
  }
};

const PARAS_SELECTOR: string = "tiqian-prose p, tiqian-prose li, [data-tiqian-root] p, [data-tiqian-root] li";

globalThis.__historyTerminal = (): boolean =>
  Array.from(document.querySelectorAll(PARAS_SELECTOR)).every((p: Element): boolean =>
    p.getAttribute("data-tq-rendered") === "true" ||
    p.hasAttribute("data-tiqian-capability-issue"));

globalThis.__historyReady = true;

export {};
