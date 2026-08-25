// Compatibility entry; the implementation moved to core/utils/copy.js in
// ADR 0053 batch 1. The re-export below keeps the historical import surface
// working under the package's historical name. The shared copy installer is
// owned by the runtime loader, so the page-level element layer and the engine
// graph both reach the same per-document handler instance.
export { copyInstaller as installTiqianCopyHandler } from "@tiqian/core/core/engine/loaders/runtime-loader.js";