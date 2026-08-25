// FontCoordinationState: the page-level, once-per-document fonts and
// declared-face coordination record. Per the service-directory rule (see
// core/services/global-services.ts), font readiness, declared @font-face
// registration, the browser font session loader and the replay registry are
// all page-wide singletons by definition: one document owns one exact-font
// fallback gate, one refcounted declared-face registry and one browser font
// backend regardless of how many roots attach or how many times a duplicated
// bundle chunk evaluates this module.
//
// This cluster module holds NO state of its own: it exports the state record
// type plus factory-free helpers that operate on a passed-in record, and the
// CoordinationService holds the single instance. The individual loaders and
// registries keep their public call surface; they consult this service-owned
// record instead of module-level mutable state.
//
// The replay registry is keyed on globalThis via Symbol.for so that client
// routers, dev HMR and duplicated package chunks share one registry, mirroring
// the worker-channel coordinator precedent. Because the globalServices
// container is itself Symbol.for-keyed, the service-owned record is shared
// across bundle copies too; the globalThis slot is the ffi backend PROTOCOL
// and stays byte-identical.
import { FONT_REPLAY_REVISION } from "../../../snapshot-schema.js";
import type { ExactFontFallbackLoader } from "../loaders/font-loader.js";
import type { PreparedDomRendererApi } from "../../sampler/snapshot/prepared-dom.js";
import type { BrowserFontSessionLoader } from "../../measurement/browser-fonts.js";
import type { ReplayRegistry } from "../../../browser-font-replay.js";
import type {
  DeclaredFaceEntry,
  DeclaredFaceVoidCallbackFn,
} from "../../sampler/snapshot/declared-faces.js";

const REPLAY_REGISTRY_KEY: unique symbol = Symbol.for(`org.tiqian.web.font-replay.${FONT_REPLAY_REVISION}`);

export interface FontCoordinationState {
  exactFontFallbackPromise: Promise<ExactFontFallbackLoader> | undefined;
  preparedBridgePromise: Promise<PreparedDomRendererApi | undefined> | undefined;
  declaredFacesEntries: Map<string, DeclaredFaceEntry>;
  declaredFacesChangeListeners: Set<DeclaredFaceVoidCallbackFn>;
  // Constructed lazily on first use (see browser-fonts.ts): constructing it
  // here would statically pull the browser-fonts module graph — which leads
  // to precomputed.ts and prepared-dom.ts, whose module body installs the
  // read-only prepared renderer bridge — into every module that imports the
  // service accessor, ahead of test fixtures that pre-seed that slot.
  browserFontLoader: BrowserFontSessionLoader | undefined;
  replayRegistry: ReplayRegistry;
}

// The replay registry survives across bundle copies through its Symbol.for
// key on globalThis. The service creates it once on first construction; later
// copies (and test-injected fresh services) reuse the same page-global slot.
export function createReplayRegistry(): ReplayRegistry {
  return (globalThis as Record<symbol, ReplayRegistry | undefined>)[REPLAY_REGISTRY_KEY] ??= {
    sessions: new Map(),
    shapeResults: new Map(),
    metricResults: new Map(),
    nextSessionId: 1,
    nextResultId: 1,
  };
}
