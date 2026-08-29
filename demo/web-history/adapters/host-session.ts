// Era adapter family F3: the prose host session era (bed4c791 onward).
// Coordinated flow: the <tiqian-prose> elements mount through the public
// registration surface of the era (importing the element entry registers the
// custom element; from 9561c747 on the host calls registerTiqianProse()).
// One-shot flow: a fresh createProseHostSession per root replayed with the
// options the coordinated run resolved and published on the root dataset
// (4370925f), mirroring the HEAD demo test's replay path.

interface HistoryOptions {
  firstLineIndentIc: number;
}

interface HostSessionElementModule {
  registerTiqianProse?: () => void;
}

interface MountableSession {
  mount: () => Promise<void> | void;
}

interface CoreSessionModule {
  createProseHostSession: (root: Element, options?: Record<string, unknown>) => MountableSession;
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

const element: HostSessionElementModule = (await import("@tiqian/prose/element")) as HostSessionElementModule;
const core: CoreSessionModule = (await import("@tiqian/core/src/engine/prose-host-session.js" as string)) as CoreSessionModule;

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
    core.createProseHostSession(root, options).mount();
  }
};

const PARAS_SELECTOR: string = "tiqian-prose p, tiqian-prose li, [data-tiqian-root] p, [data-tiqian-root] li";

globalThis.__historyTerminal = (): boolean =>
  Array.from(document.querySelectorAll(PARAS_SELECTOR)).every((p: Element): boolean =>
    p.getAttribute("data-tq-rendered") === "true" ||
    p.hasAttribute("data-tiqian-capability-issue"));

globalThis.__historyReady = true;

export {};
