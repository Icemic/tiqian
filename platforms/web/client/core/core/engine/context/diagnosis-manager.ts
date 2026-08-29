// DiagnosisManager — the per-root diagnosis channel owned by the
// EnhancedElementContext (one manager per enhanced element; the context
// constructs it and drops its listeners on destroy). The manager is the
// single write path for the host telemetry dataset keys: set() and clear()
// always write the root dataset so the observable attribute contract stays
// byte-identical.
//
// Event delivery is demand-driven (2026-08-26 ruling): a DiagnosisEvent
// object is constructed and broadcast only while at least one listener is
// subscribed. With no listeners the write path allocates nothing beyond the
// caller-supplied value string, so an unobserved page pays no GC cost for
// the channel. eventBroadcastCount counts constructed event objects and
// stays 0 on the unlistened path.
//
// The event type and the subscription surface are internal in this wave; the
// public export lands with the wc-s5 event union.

// Telemetry keys owned by the host diagnosis channel. The frozen
// snapshot-adoption contract keys (tiqianSnapshot, tiqianSnapshotFontPolicy,
// and the core-side tiqianSnapshotMiss writes) and the stylesheet marker are
// not listed here: those writes stay direct per the contract rulings.
export type DiagnosisKey =
  | "tiqianCapabilityIssue"
  | "tiqianEnhanceMs"
  | "tiqianEnhanceOptions"
  | "tiqianExactLayoutIssue"
  | "tiqianFontWait"
  | "tiqianLoadMs"
  | "tiqianMaxSliceMs"
  | "tiqianRelayoutMs"
  | "tiqianRelayoutMaxSliceMs"
  | "tiqianSnapshotCount"
  | "tiqianSnapshotFontMiss"
  | "tiqianSnapshotLiveIssue"
  | "tiqianSnapshotMiss";

export interface DiagnosisEvent {
  readonly kind: "set" | "clear";
  readonly key: DiagnosisKey;
  readonly value: string | null;
}

export type DiagnosisListener = (event: DiagnosisEvent) => void;

export type DiagnosisUnsubscribe = () => void;

/** String map shaped like an HTMLElement dataset entry set. */
export type DiagnosisDatasetRecord = { [key: string]: string | undefined };

// One capability issue record reported through the lifecycle
// reportIssue/clearIssue markers and accumulated per root. Absorbed from the
// dissolved root-state.ts issues array in the core-neutral wave (renamed
// from RootStateIssueRecord; the RootState prefix object no longer exists).
// The lifecycle marker functions mutate the markerCaptured bookkeeping
// fields onto the same record.
export type DiagnosisIssueRecord = {
  kind?: string;
  name?: string;
  detail?: string;
  element?: Element;
  measure?: number;
  reportToConsole?: boolean;
  markerCaptured?: boolean;
  originalNameAttribute?: string | null;
  originalDetailAttribute?: string | null;
};

// Minimal dataset surface: the real host is an HTMLElement whose dataset is a
// DOMStringMap, and the Node test shims supply a plain object. Read live on
// every write because test drives may replace element.dataset after the
// context is constructed. Hosts without a dataset surface (the SSR element
// shell, plain engine fakes) carry undefined; those hosts never run the
// telemetry lifecycle, so the write is skipped rather than guessed.
interface DiagnosisDatasetHost {
  readonly dataset: DiagnosisDatasetRecord | undefined;
}

export interface DiagnosisManager {
  /** Number of DiagnosisEvent objects constructed since creation. */
  readonly eventBroadcastCount: number;
  /** Live per-root capability issue records; drivers push by reference. */
  readonly issues: DiagnosisIssueRecord[];
  set(key: DiagnosisKey, value: string): void;
  clear(key: DiagnosisKey): void;
  /** Event-only broadcast for demoted internal signals; no dataset write. */
  signal(key: DiagnosisKey, value: string): void;
  /** Subscribes a listener; the returned function unsubscribes it. */
  subscribe(listener: DiagnosisListener): DiagnosisUnsubscribe;
  /** Drops every listener; the dataset write path stays functional. */
  dispose(): void;
  /** Empties the issue records after their markers were cleared. */
  clearIssues(): void;
}

function createDiagnosisManager(host: DiagnosisDatasetHost): DiagnosisManager {
  let listeners: DiagnosisListener[] | null = null;
  let eventBroadcastCount = 0;
  const issues: DiagnosisIssueRecord[] = [];

  function broadcast(kind: "set" | "clear", key: DiagnosisKey, value: string | null): void {
    if (!listeners) return;
    eventBroadcastCount += 1;
    const event: DiagnosisEvent = { kind, key, value };
    for (let index = 0; index < listeners.length; index += 1) {
      listeners[index](event);
    }
  }

  function set(key: DiagnosisKey, value: string): void {
    const dataset = host.dataset;
    if (dataset) dataset[key] = value;
    broadcast("set", key, value);
  }

  function clear(key: DiagnosisKey): void {
    const dataset = host.dataset;
    if (dataset) delete dataset[key];
    broadcast("clear", key, null);
  }

  function signal(key: DiagnosisKey, value: string): void {
    broadcast("set", key, value);
  }

  function subscribe(listener: DiagnosisListener): DiagnosisUnsubscribe {
    listeners ??= [];
    listeners.push(listener);
    return () => {
      if (!listeners) return;
      const index = listeners.indexOf(listener);
      if (index >= 0) listeners.splice(index, 1);
      if (listeners.length === 0) listeners = null;
    };
  }

  function dispose(): void {
    listeners = null;
  }

  return {
    get eventBroadcastCount() {
      return eventBroadcastCount;
    },
    issues,
    set,
    clear,
    signal,
    subscribe,
    dispose,
    clearIssues() {
      issues.length = 0;
    },
  };
}

export { createDiagnosisManager };
export type { DiagnosisDatasetHost };
