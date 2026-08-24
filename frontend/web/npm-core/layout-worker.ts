import {
  mergeSerializedSourceBoundaries,
  workerExactSubsetSourceBoundaries,
} from "./core/sampler/font-face-boundaries.js";
import {
  createManifestFontSession,
  createProbeBootstrapFontSession,
} from "./core/engine/web-worker/session-bootstrap.js";
import type { ServerReplayFontSession } from "./browser-font-replay.js";
import type {
  WorkerLayoutRequestBody,
  WorkerRequestEnvelope,
  WorkerResponseEnvelope,
} from "./core/engine/web-worker/worker-channel.js";
import { precomputeParagraph } from "@tiqian/ffi";

type WorkerMessageEventListener = (event: MessageEvent<WorkerRequestEnvelope>) => void | Promise<void>;

interface LayoutWorkerMessageScope {
  addEventListener(type: "message", listener: WorkerMessageEventListener): void;
  postMessage(message: WorkerResponseEnvelope): void;
}

const sessions: Map<string, ServerReplayFontSession> = new Map();

function errorDetail(error: unknown): string {
  return String(error instanceof Error ? error.message : error).slice(0, 1_000);
}

(globalThis as LayoutWorkerMessageScope).addEventListener("message", async (event: MessageEvent<WorkerRequestEnvelope>) => {
  const message = event.data;
  if (!message || typeof message !== "object") return;
  const { id, type, sessionKey } = message;
  try {
    if (type === "init") {
      let session = sessions.get(sessionKey);
      if (!session) {
        session = message.probeBootstrap === true
          ? await createProbeBootstrapFontSession(sessionKey, message)
          : await createManifestFontSession(message.manifestText!, message.tablesBytes, sessionKey);
        sessions.set(sessionKey, session);
      }
      (globalThis as LayoutWorkerMessageScope).postMessage({ id, ok: true });
      return;
    }
    if (type === "release") {
      sessions.get(sessionKey)?.close?.();
      sessions.delete(sessionKey);
      (globalThis as LayoutWorkerMessageScope).postMessage({ id, ok: true });
      return;
    }
    if (type !== "layout") return;
    const session = sessions.get(sessionKey);
    if (!session) throw new Error("LayoutWorkerFontSessionMissing");
    const request: WorkerLayoutRequestBody = message.request;
    const sourceBoundaries = mergeSerializedSourceBoundaries(
      request.sourceBoundaries,
      workerExactSubsetSourceBoundaries(session.faces, request),
    );
    // WorkerRenderEvidencePassthrough: the field passes through verbatim; an
    // old sender omits it, undefined reaches the nullable ffi parameter as
    // null, and the wire-derived verdict applies, so package version skew
    // keeps both directions working.
    const plan = precomputeParagraph(
      session.id,
      request.text,
      request.maxWidthPx,
      request.fontFamilies,
      request.fontSizePx,
      request.lineHeightPx,
      request.locale,
      request.fontWeight,
      request.italic,
      request.firstLineIndentIc,
      true,
      sourceBoundaries,
      request.textSpans,
      request.inlineBoxes,
      request.lineBreakSpans,
      request.inlineObjects,
      request.renderEvidence,
    );
    (globalThis as LayoutWorkerMessageScope).postMessage({ id, ok: true, plan });
  } catch (error) {
    (globalThis as LayoutWorkerMessageScope).postMessage({ id, ok: false, error: errorDetail(error) });
  }
});


