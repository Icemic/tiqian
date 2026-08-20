// Plain-text issue oracle (ADR 0050 amendment `PrecomputeInRust`).
//
// Runs the exported `snapshotPlainTextIssue` of the js implementation over
// every Unicode code point as a single-character text and writes a
// run-length-encoded dump to build/plain-text-issue/oracle.json. The Rust
// integration test `rust/tiqian-precompute/tests/plain_text_issue_parity.rs`
// builds the same dump from `snapshot_plain_text_issue` and compares the
// ranges, so the generated Unicode tables stay on the js data.
//
// node scripts/plain-text-issue-oracle.mjs (from frontend/web-precompute)

import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

import { snapshotPlainTextIssue } from "../../web/npm/precompute.js";

const here = dirname(fileURLToPath(import.meta.url));
const MAX_CODE_POINT = 0x10ffff;

const ranges = [];
let start = 0;
let current = snapshotPlainTextIssue(String.fromCodePoint(0));
for (let point = 1; point <= MAX_CODE_POINT; point += 1) {
  const issue = snapshotPlainTextIssue(String.fromCodePoint(point));
  if (issue !== current) {
    ranges.push({ start, end: point - 1, issue: current });
    start = point;
    current = issue;
  }
}
ranges.push({ start, end: MAX_CODE_POINT, issue: current });

const outPath = resolve(here, "../build/plain-text-issue/oracle.json");
mkdirSync(dirname(outPath), { recursive: true });
writeFileSync(outPath, `${JSON.stringify(ranges)}\n`);
process.stdout.write(`oracle dump: ${outPath} (${ranges.length} ranges)\n`);
