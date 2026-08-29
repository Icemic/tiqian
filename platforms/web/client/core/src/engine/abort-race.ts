// EnhancePipelineAbortRace: the standard-shell race that lets the prose
// enhance pipeline stop waiting the moment its lifecycle AbortSignal fires.
// The generational/revision cancellation inside the state machine and the
// worker channel remains the semantic kernel; this race only decides how
// long a pipeline await point keeps waiting after that kernel cancelled.
//
// The race never rejects on account of the abort: an aborted outcome is a
// resolved tagged value, so aborting a lifecycle cannot create an uncaught
// rejection. The underlying promise keeps running (the loaders it awaits are
// shared memoized page resources) and its settlement is always consumed
// here, so a late rejection after an abort cannot become unhandled either.

export interface AbortRaceAborted {
  readonly aborted: true;
}

export interface AbortRaceValue<T> {
  readonly aborted: false;
  readonly value: T;
}

export type AbortRaceOutcome<T> = AbortRaceAborted | AbortRaceValue<T>;

export function raceAbort<T>(
  signal: AbortSignal | null | undefined,
  promise: Promise<T>,
): Promise<AbortRaceOutcome<T>> {
  if (!signal) return promise.then((value) => ({ aborted: false as const, value }));
  if (signal.aborted) return Promise.resolve({ aborted: true as const });
  return new Promise<AbortRaceOutcome<T>>((resolve, reject) => {
    const onAbort = () => resolve({ aborted: true as const });
    signal.addEventListener("abort", onAbort, { once: true });
    promise.then(
      (value) => {
        signal.removeEventListener("abort", onAbort);
        resolve({ aborted: false as const, value });
      },
      (error) => {
        signal.removeEventListener("abort", onAbort);
        reject(error);
      },
    );
  });
}
