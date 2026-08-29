// Shared test types for the core package.
// Consolidates test-side shapes used across multiple test files.

import type { LayoutJobPool } from "../core/engine/layout-job-pool.js";

export interface GlobalEntry {
  readonly name: string;
  readonly own: boolean;
  readonly value: unknown;
}

export interface FakeClock {
  pendingTimerCount(): number;
  advance(ms: number): void;
}

export interface PreserveGlobalsFn {
  (names: readonly string[]): GlobalEntry[];
}

export interface RestoreGlobalsFn {
  (entries: readonly GlobalEntry[]): void;
}

export interface InstallFakeClockFn {
  (): FakeClock;
}

// Writable view over the coordination service's readonly layoutJobPool slot;
// tests swap a fake pool in for one test and restore it afterwards.
export interface CoordinationPoolSlot {
  layoutJobPool: LayoutJobPool;
}

export interface Thunk<T> {
  (): T;
}

// Partial ready verdict: tests feed the session a verdict carrying only the
// fields the session reads, then assert it to the product PrepareReadyResult.
export interface PartialReadyVerdict {
  kind: "ready";
  planJson: string;
  measure: number;
  width: number;
}
