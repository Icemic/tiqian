// Shared test harness: a manual rAF + timer clock plus global save/restore.
// The fake timeline drives both performance.now and Date.now because the
// coordinator mixes the two clocks by design (ClockTierDiscipline): budget
// deadlines read performance.now, coarse counters such as debounce due times and
// duration statistics read Date.now. Tests that record timing goldens depend
// on both readings moving together under advance().

import type { GlobalEntry, FakeClock } from "./types.js";

export const CLOCK_GLOBALS: readonly string[] = [
  "performance",
  "requestAnimationFrame",
  "cancelAnimationFrame",
  "setTimeout",
  "clearTimeout",
  "Date",
];

interface SavedGlobal {
  readonly name: string;
  readonly own: boolean;
  readonly value: unknown;
}

interface TimerEntry {
  readonly callback: () => void;
  readonly dueAt: number;
}

type RafCallback = (time: number) => void;

export function preserveGlobals(names: readonly string[]): SavedGlobal[] {
  return names.map((name: string) => ({
    name,
    own: Object.prototype.hasOwnProperty.call(globalThis, name),
    value: (globalThis as Record<string, unknown>)[name],
  }));
}

export function restoreGlobals(entries: readonly SavedGlobal[]): void {
  for (const { name, own, value } of entries) {
    if (own) (globalThis as Record<string, unknown>)[name] = value;
    else delete (globalThis as Record<string, unknown>)[name];
  }
}

export function installFakeClock(): FakeClock {
  let now = 0;
  let rafId = 0;
  let timerId = 0;
  const rafQueue = new Map<number, RafCallback>();
  const timers = new Map<number, TimerEntry>();
  const RealDate = Date;

  (globalThis as Record<string, unknown>).performance = { now: (): number => now };
  // Coarse counters (debounce due times, duration stats) read Date.now, so the
  // fake timeline drives them too.
  (globalThis as Record<string, unknown>).Date = class FakeDate extends RealDate {
    static now(): number {
      return now;
    }
  };
  (globalThis as Record<string, unknown>).requestAnimationFrame = (callback: RafCallback): number => {
    const id = ++rafId;
    rafQueue.set(id, callback);
    return id;
  };
  (globalThis as Record<string, unknown>).cancelAnimationFrame = (id: number): void => {
    rafQueue.delete(id);
  };
  (globalThis as Record<string, unknown>).setTimeout = (callback: () => void, delay: number = 0): number => {
    const id = ++timerId;
    timers.set(id, { callback, dueAt: now + delay });
    return id;
  };
  (globalThis as Record<string, unknown>).clearTimeout = (id: number): void => {
    timers.delete(id);
  };

  return {
    // Pending timer count: lets tests observe whether the coordination
    // service still holds a worker wake timer (see the worker-slot
    // weak-reference tests).
    pendingTimerCount(): number {
      return timers.size;
    },
    advance(ms: number): void {
      const target = now + ms;
      for (;;) {
        const dueTimer = [...timers.entries()].filter(([, t]) => t.dueAt <= target)
          .sort((a, b) => a[1].dueAt - b[1].dueAt)[0];
        const dueFrame = [...rafQueue.entries()][0];
        const timerTime = dueTimer ? dueTimer[1].dueAt : Infinity;
        const frameTime = dueFrame ? now : Infinity;
        if (timerTime === Infinity && frameTime === Infinity) break;
        if (frameTime <= timerTime) {
          now = Math.max(now, frameTime);
          const [, callback] = dueFrame;
          rafQueue.delete(dueFrame[0]);
          callback(now);
        } else {
          now = Math.max(now, timerTime);
          const [, { callback }] = dueTimer;
          timers.delete(dueTimer[0]);
          callback();
        }
        if (now > target) break;
      }
      now = target;
    },
  };
}
