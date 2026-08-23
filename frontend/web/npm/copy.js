// Compatibility entry; the implementation moved to core/utils/copy.js in
// ADR 0053 batch 1. The module is a plain script installing
// globalThis.__TiqianInstallCopyHandler; the named export below keeps the
// historical import surface working.
import "./core/utils/copy.js";

export const installTiqianCopyHandler = globalThis.__TiqianInstallCopyHandler;
