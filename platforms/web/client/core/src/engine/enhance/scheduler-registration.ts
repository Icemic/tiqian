// SchedulerRegistration — the root's scheduling registration in the
// page-global CoordinationService (core-neutral parts ruling). Owns the
// RootSessionId lifecycle (register on first mount, unregister on settle)
// and the identity surface every frame/grant/fact call needs: update,
// grantImmediate, requestFrame, cancelFrame, runPrepare and the worker
// registration verbs. Zero out-edges: the part is purely held by the
// composition root and the parts that schedule through it.

import { globalServices } from "../../services/global-services.js";
import type { CoordinationService, FrameTaskCallback, PrepareJob, RootSessionId } from "../coordination/coordination-service.js";

export interface SchedulerRegistration {
  readonly id: RootSessionId;
  /** Registers on first use; a live registration is reused. */
  register(): void;
  unregister(): void;
  update(facts: Record<string, unknown>): void;
  grantImmediate(): void;
  requestFrame(callback: FrameTaskCallback): void;
  cancelFrame(callback: FrameTaskCallback): void;
  runPrepare(prepareJob: PrepareJob): Promise<number>;
  registerWorker(root: HTMLElement): void;
  setWorkerActive(active: boolean): void;
  refreshWorkerDeferred(): void;
  clearWorkerDeferred(): void;
  requestWorkerFrame(): void;
}

function coordinationService(): CoordinationService {
  return globalServices().coordination;
}

function createSchedulerRegistration(): SchedulerRegistration {
  let sessionId: RootSessionId = 0;

  return {
    get id() {
      return sessionId;
    },
    register() {
      if (!sessionId) sessionId = coordinationService().register();
    },
    unregister() {
      coordinationService().unregister(sessionId);
      sessionId = 0;
    },
    update(facts: Record<string, unknown>) {
      coordinationService().update(sessionId, facts);
    },
    grantImmediate() {
      coordinationService().grantImmediate(sessionId);
    },
    requestFrame(callback: FrameTaskCallback) {
      coordinationService().requestFrame(callback, sessionId);
    },
    cancelFrame(callback: FrameTaskCallback) {
      coordinationService().cancelFrame(callback);
    },
    runPrepare(prepareJob: PrepareJob) {
      return coordinationService().runPrepare(sessionId, prepareJob);
    },
    registerWorker(root: HTMLElement) {
      coordinationService().registerWorker(sessionId, root);
    },
    setWorkerActive(active: boolean) {
      coordinationService().setWorkerActive(sessionId, active);
    },
    refreshWorkerDeferred() {
      coordinationService().refreshWorkerDeferred(sessionId);
    },
    clearWorkerDeferred() {
      coordinationService().clearWorkerDeferred(sessionId);
    },
    requestWorkerFrame() {
      coordinationService().requestWorkerFrame(sessionId);
    },
  };
}

export { createSchedulerRegistration };
