// Compatibility re-export (ADR 0053 batch 6). The precomputed snapshot
// module moved to core/sampler/snapshot/precomputed.js; this root path
// remains for published-file and bundler deep-import compatibility.
export * from "./core/sampler/snapshot/precomputed.js";
