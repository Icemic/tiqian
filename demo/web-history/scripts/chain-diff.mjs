// chain-diff.mjs — phase 2 ledger helper. Compares two chain-capture
// evidence files (chain-<label>.json) with the frozen diffDeepGeometry
// exported by the harness, so the comparison semantics are exactly the
// spec's. Usage:
//   node demo/web-history/scripts/chain-diff.mjs <a.json> <b.json>
import { readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const repoRoot = fileURLToPath(new URL("../../..", import.meta.url));
const { diffDeepGeometry, deepGeometryCounts } = await import(
  path.join(repoRoot, "demo/web-history/oneshot-history-harness.diag.mjs")
);

const [aPath, bPath] = process.argv.slice(2);
if (!aPath || !bPath) {
  console.error("usage: chain-diff.mjs <a.json> <b.json>");
  process.exit(2);
}
const a = JSON.parse(readFileSync(path.resolve(repoRoot, aPath), "utf8"));
const b = JSON.parse(readFileSync(path.resolve(repoRoot, bPath), "utf8"));
if (!a.valid || !b.valid) {
  console.error("one side is invalid:", a.valid ? bPath : aPath);
  process.exit(3);
}
const countsA = deepGeometryCounts(a.geometry);
const countsB = deepGeometryCounts(b.geometry);
const diff = diffDeepGeometry(a.geometry, b.geometry);
console.log(JSON.stringify({
  a: { file: aPath, commit: a.commit, era: a.era, counts: countsA, pageHeight: a.pageHeight, selfEqual: a.selfEqual },
  b: { file: bPath, commit: b.commit, era: b.era, counts: countsB, pageHeight: b.pageHeight, selfEqual: b.selfEqual },
  equal: diff.equal,
  boxesCompared: diff.boxesCompared,
  divergentBoxes: diff.divergentBoxes,
  examples: diff.examples,
}, null, 2));
