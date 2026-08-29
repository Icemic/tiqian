// EnhancePipelineAbortRace unit tests (spec wc-s4b item 2): the race is the
// standard shell around the pipeline's generational cancellation kernel. The
// abort path must resolve, never reject, and never leave the underlying
// promise's settlement unhandled.

import assert from "node:assert/strict";
import test from "node:test";
import { getEventListeners } from "node:events";

import { raceAbort } from "../src/engine/abort-race.js";

test("the abort race delivers the value when the pipeline step settles first", async () => {
  const controller = new AbortController();
  const outcome = await raceAbort(controller.signal, Promise.resolve("stored-plans"));
  assert.equal(outcome.aborted, false);
  if (!outcome.aborted) assert.equal(outcome.value, "stored-plans");
  assert.equal(getEventListeners(controller.signal, "abort").length, 0);
});

test("the abort race resolves the aborted tag when the signal fires first", async () => {
  const controller = new AbortController();
  let resolvePending = (_value: string) => {};
  const pending = new Promise<string>((resolve) => { resolvePending = resolve; });
  const raced = raceAbort(controller.signal, pending);
  controller.abort();
  const outcome = await raced;
  assert.equal(outcome.aborted, true);
  // The underlying step keeps running; a late fulfillment is dropped
  // without changing the already-delivered abort outcome.
  resolvePending("too-late");
  assert.equal((await raced).aborted, true);
});

test("the abort race answers immediately for an already-aborted signal", async () => {
  const controller = new AbortController();
  controller.abort();
  const outcome = await raceAbort(controller.signal, Promise.resolve("never"));
  assert.equal(outcome.aborted, true);
});

test("the abort race forwards a settlement rejection", async () => {
  const controller = new AbortController();
  await assert.rejects(
    raceAbort(controller.signal, Promise.reject(new Error("FontCapabilityPreparationFailed"))),
    /FontCapabilityPreparationFailed/u,
  );
  assert.equal(getEventListeners(controller.signal, "abort").length, 0);
});

test("a late rejection after an abort stays handled", async () => {
  const controller = new AbortController();
  let rejectPending = (_error: Error) => {};
  const pending = new Promise<string>((_resolve, reject) => { rejectPending = reject; });
  const raced = raceAbort(controller.signal, pending);
  controller.abort();
  assert.equal((await raced).aborted, true);
  rejectPending(new Error("late-loader-failure"));
  // Give the rejection one macrotask to surface as unhandled. The race
  // consumed it through its own handler, so the process-level unhandled-
  // rejection guard stays silent; an explicit hook makes the intent visible.
  let unhandled = false;
  const onUnhandled = () => { unhandled = true; };
  process.on("unhandledRejection", onUnhandled);
  await new Promise((resolve) => setTimeout(resolve, 0));
  process.off("unhandledRejection", onUnhandled);
  assert.equal(unhandled, false);
});

test("a missing signal passes the pipeline step through unchanged", async () => {
  const outcome = await raceAbort(null, Promise.resolve(42));
  assert.equal(outcome.aborted, false);
  if (!outcome.aborted) assert.equal(outcome.value, 42);
  await assert.rejects(raceAbort(undefined, Promise.reject(new Error("bridge"))), /bridge/u);
});
