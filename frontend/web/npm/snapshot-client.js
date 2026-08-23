// Compatibility re-export (ADR 0053 batch 6). The snapshot client module
// moved to core/sampler/snapshot/snapshot-client.js; this root path
// remains for published-file and bundler deep-import compatibility.
export * from "./core/sampler/snapshot/snapshot-client.js";
