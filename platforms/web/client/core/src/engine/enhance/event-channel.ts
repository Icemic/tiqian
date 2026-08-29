// EventChannel — completion event notification for one enhanced element
// (core-neutral parts ruling). The channel is the single event funnel: the
// layout drivers and the snapshot adoption paths report completions through
// notify(); the per-mount internal handler (installed by the mount
// lifecycle) classifies each completion before the callback subscribers are
// notified. The DOM CustomEvents tiqian:ready / tiqian:relayout-ready stay
// the single observable event surface on the root; in the web component
// path their synthesis is owned by the element shell through the
// dispatcher slot (event single-truth ruling). Hosts without a dispatcher
// keep the baseline core synthesis so framework-less drivers stay
// byte-identical.
//
// Externally dispatched completion events (test drives and host frameworks
// dispatch tiqian:relayout-ready directly on the root) still reach the
// funnel: while the listeners are attached the channel routes DOM events
// into the same internal handler, skipping events it dispatched itself.

export type EnhancementEvent = "ready" | "relayout-ready";

export interface EnhancementDiagnostics {
  readonly enhanceMs?: number;
  readonly loadMs?: number;
  readonly relayoutMs?: number;
  readonly maxSliceMs?: number;
  readonly snapshotCount?: number;
  readonly enhancedCount?: number;
  readonly snapshot?: boolean;
}

export type EnhancementEventCallback = (diagnostics: EnhancementDiagnostics) => void;

/** Unsubscribe handle returned by the event subscription surface. */
export type EnhancementEventUnsubscribe = () => void;

// Event view handed to the internal funnel: the DOM listener passes the real
// CustomEvent, notify() passes the synthetic equivalent.
export interface CompletionEventView {
  readonly type: string;
  detail: Record<string, unknown> | null | undefined;
}

export type CompletionEventHandler = (event: CompletionEventView) => void;

/** Root DOM listener installed while the completion listeners are attached. */
export type DomCompletionListener = (event: Event) => void;

// Synthesizes and dispatches one completion CustomEvent on the root. The
// web component shell installs the synthesis; the returned value is unused.
export type CompletionEventDispatcher = (
  kind: string,
  detail: Record<string, unknown>,
) => void;

export interface EventChannel {
  readonly lastDiagnostics: EnhancementDiagnostics;
  on(event: EnhancementEvent, callback: EnhancementEventCallback): EnhancementEventUnsubscribe;
  onReady(callback: EnhancementEventCallback): EnhancementEventUnsubscribe;
  onRelayoutReady(callback: EnhancementEventCallback): EnhancementEventUnsubscribe;
  /** Notifies the callback subscribers and stores the latest diagnostics. */
  emit(event: EnhancementEvent, diagnostics: EnhancementDiagnostics): void;
  /** Sets the per-mount internal funnel handler; null detaches it. */
  setInternalHandler(handler: CompletionEventHandler | null): void;
  /** Installs the shell synthesis; null restores the core synthesis. */
  setDispatcher(dispatcher: CompletionEventDispatcher | null): void;
  /** Attaches the root DOM listeners that route external dispatches. */
  attachCompletionListeners(): void;
  detachCompletionListeners(): void;
  /**
   * Driver completion report: runs the funnel, then dispatches the DOM
   * event so external listeners observe the funnel-adjusted detail.
   */
  notify(kind: "tiqian:ready" | "tiqian:relayout-ready", detail: Record<string, unknown>): void;
  /** Dispatches a non-funnel event kind (the progressive error events). */
  dispatch(kind: string, detail: Record<string, unknown>): void;
  beginCompletion(): Promise<void>;
  finishCompletion(): void;
  /** Drops subscribers and listeners; the channel becomes inert. */
  dispose(): void;
}

// Marker the channel sets on events it dispatched itself, so the DOM
// listener never funnels its own dispatch a second time. Exported for the
// web component shell's dispatcher slot, which performs the same synthesis.
export const INTERNAL_DISPATCH_MARKER = "__tiqianCompletionDispatched";

// A completion CustomEvent carrying the self-dispatch marker. The channel
// and the element shell both write the marker; the DOM listeners read it to
// skip the events this runtime dispatched itself. A single assertion widens
// a CustomEvent to this view, so the marker stays a named contract instead
// of a double cast through Record.
export type MarkedCompletionEvent = CustomEvent<Record<string, unknown>> & {
  [INTERNAL_DISPATCH_MARKER]?: boolean;
};

type CompletionResolver = () => void;

function createEventChannel(root: HTMLElement): EventChannel {
  let listeners: Map<EnhancementEvent, EnhancementEventCallback[]> | null = new Map();
  let lastDiagnostics: EnhancementDiagnostics = {};
  let internalHandler: CompletionEventHandler | null = null;
  let dispatcher: CompletionEventDispatcher | null = null;
  let domListener: DomCompletionListener | null = null;
  let completionPromise: Promise<void> = Promise.resolve();
  let completionResolve: CompletionResolver | null = null;

  function emit(event: EnhancementEvent, diagnostics: EnhancementDiagnostics): void {
    lastDiagnostics = diagnostics;
    const table = listeners;
    if (!table) return;
    const list = table.get(event);
    if (!list) return;
    for (const callback of [...list]) callback(diagnostics);
  }

  function runFunnel(event: CompletionEventView): void {
    if (internalHandler) internalHandler(event);
  }

  function synthesizeAndDispatch(kind: string, detail: Record<string, unknown>): void {
    if (dispatcher) {
      dispatcher(kind, detail);
      return;
    }
    // Baseline core synthesis for hosts without a shell dispatcher.
    if (!root || typeof root.dispatchEvent !== "function") return;
    const event = new CustomEvent(kind, { bubbles: true, composed: true, detail: detail });
    (event as MarkedCompletionEvent)[INTERNAL_DISPATCH_MARKER] = true;
    root.dispatchEvent(event);
  }

  function notify(kind: "tiqian:ready" | "tiqian:relayout-ready", detail: Record<string, unknown>): void {
    runFunnel({ type: kind, detail: detail });
    synthesizeAndDispatch(kind, detail);
  }

  function dispatch(kind: string, detail: Record<string, unknown>): void {
    synthesizeAndDispatch(kind, detail);
  }

  function attachCompletionListeners(): void {
    if (domListener) return;
    domListener = (event: Event) => {
      const completion = event as MarkedCompletionEvent;
      if (completion[INTERNAL_DISPATCH_MARKER]) return;
      runFunnel(completion);
    };
    root.addEventListener("tiqian:ready", domListener);
    root.addEventListener("tiqian:relayout-ready", domListener);
  }

  function detachCompletionListeners(): void {
    if (!domListener) return;
    root.removeEventListener("tiqian:ready", domListener);
    root.removeEventListener("tiqian:relayout-ready", domListener);
    domListener = null;
  }

  function on(event: EnhancementEvent, callback: EnhancementEventCallback): EnhancementEventUnsubscribe {
    const table = listeners ?? (listeners = new Map());
    let list = table.get(event);
    if (!list) {
      list = [];
      table.set(event, list);
    }
    const subscribers = list;
    subscribers.push(callback);
    return () => {
      const index = subscribers.indexOf(callback);
      if (index >= 0) subscribers.splice(index, 1);
    };
  }

  function beginCompletion(): Promise<void> {
    // Supersede any lifecycle whose completion never reported: a restart
    // between dispatch and ready abandons the earlier promise.
    finishCompletion();
    let resolver: CompletionResolver | null = null;
    completionPromise = new Promise<void>((resolve) => {
      resolver = resolve;
    });
    completionResolve = resolver;
    return completionPromise;
  }

  function finishCompletion(): void {
    const resolver = completionResolve;
    completionResolve = null;
    if (resolver) resolver();
  }

  function dispose(): void {
    detachCompletionListeners();
    internalHandler = null;
    listeners = null;
    finishCompletion();
  }

  return {
    get lastDiagnostics() {
      return lastDiagnostics;
    },
    on,
    onReady(callback: EnhancementEventCallback) {
      return on("ready", callback);
    },
    onRelayoutReady(callback: EnhancementEventCallback) {
      return on("relayout-ready", callback);
    },
    emit,
    setInternalHandler(handler: CompletionEventHandler | null) {
      internalHandler = handler;
    },
    setDispatcher(next: CompletionEventDispatcher | null) {
      dispatcher = next;
    },
    attachCompletionListeners,
    detachCompletionListeners,
    notify,
    dispatch,
    beginCompletion,
    finishCompletion,
    dispose,
  };
}

export { createEventChannel };
