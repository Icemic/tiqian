// Shared test harness: a manual rAF + timer clock plus global save/restore.
// The fake timeline drives both performance.now and Date.now because the
// coordinator mixes the two clocks by design (ClockTierDiscipline): budget
// deadlines read performance.now, coarse lanes such as debounce due times and
// duration statistics read Date.now. Tests that record timing goldens depend
// on both readings moving together under advance().

export const CLOCK_GLOBALS = [
  "performance",
  "requestAnimationFrame",
  "cancelAnimationFrame",
  "setTimeout",
  "clearTimeout",
  "Date",
];

export function preserveGlobals(names) {
  return names.map((name) => ({
    name,
    own: Object.prototype.hasOwnProperty.call(globalThis, name),
    value: globalThis[name],
  }));
}

export function restoreGlobals(entries) {
  for (const { name, own, value } of entries) {
    if (own) globalThis[name] = value;
    else delete globalThis[name];
  }
}

export function installFakeClock() {
  let now = 0;
  let rafId = 0;
  let timerId = 0;
  const rafQueue = new Map();
  const timers = new Map();
  const RealDate = Date;

  globalThis.performance = { now: () => now };
  // Coarse lanes (debounce due times, duration stats) read Date.now, so the
  // fake timeline drives them too.
  globalThis.Date = class FakeDate extends RealDate {
    static now() {
      return now;
    }
  };
  globalThis.requestAnimationFrame = (callback) => {
    const id = ++rafId;
    rafQueue.set(id, callback);
    return id;
  };
  globalThis.cancelAnimationFrame = (id) => rafQueue.delete(id);
  globalThis.setTimeout = (callback, delay = 0) => {
    const id = ++timerId;
    timers.set(id, { callback, dueAt: now + delay });
    return id;
  };
  globalThis.clearTimeout = (id) => timers.delete(id);

  return {
    advance(ms) {
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
