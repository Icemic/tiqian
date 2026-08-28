// Shared test types for the core package.
// Consolidates test-side shapes used across multiple test files.

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
