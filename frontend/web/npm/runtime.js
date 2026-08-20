let runtimePromise;

export function loadTiqianRuntime() {
  runtimePromise ??= import("./runtime/tiqian-web.js").then((module) => {
    // WorkerPolledScheduling: the runtime exports its polled worker facade
    // (an IR object singleton behind getInstance; the UMD branch exposes it
    // as globalThis.web). Mount the methods on the existing TiqianWeb bridge
    // so the coordinator grants slices by passing one plain controller
    // object per grant; no live coordinator state crosses the boundary.
    const facade = module.TiqianWebWorkers ??
      module.default?.TiqianWebWorkers ??
      globalThis.web?.TiqianWebWorkers;
    const workers = facade?.getInstance?.();
    const bridge = globalThis.TiqianWeb;
    if (workers && bridge) {
      bridge.workerAttach = workers.attach.bind(workers);
      bridge.workerDetach = workers.detach.bind(workers);
      bridge.workerHasJob = workers.hasJob.bind(workers);
      bridge.workerJobGeneration = workers.jobGeneration.bind(workers);
      bridge.workerRunSlice = workers.runSlice.bind(workers);
      bridge.workerPendingInTier = workers.pendingInTier.bind(workers);
      bridge.workerParagraphCount = workers.paragraphCount.bind(workers);
      bridge.workerParagraphAt = workers.paragraphAt.bind(workers);
      bridge.workerSetParagraphTier = workers.setParagraphTier.bind(workers);
    }
    return module;
  });
  return runtimePromise;
}

export function currentTiqianRuntime() {
  return runtimePromise;
}

export async function withTiqianRuntime(action) {
  await loadTiqianRuntime();
  return action(globalThis.TiqianWeb);
}
