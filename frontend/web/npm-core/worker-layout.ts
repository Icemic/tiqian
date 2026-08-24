// Compatibility re-export (ADR 0053 batch 5). The main-thread worker
// channel moved to core/engine/web-worker/worker-channel.js; this root
// path remains for published-file and bundler deep-import compatibility.
export * from "./core/engine/web-worker/worker-channel.js";
