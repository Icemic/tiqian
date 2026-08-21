// Build-font stylesheet parity oracle (ADR 0050). Runs the case matrix of the
// Rust integration test through `parseBuildFontStylesheet` from
// `frontend/web/npm/precompute-node-fonts.js` and emits one `<name>\t<dump>`
// line per case to stdout; the Rust test runs the same matrix through
// tiqian-precompute and byte-compares the dumps.
//
//   node build_fonts_oracle.mjs <cases.json> <repo-root>
//
// Every case passes its stylesheet source as a `file:` URL string, so the js
// helper and the Rust port receive the same base URL without Node path
// resolution involved.

import { readFileSync } from "node:fs";
import { join } from "node:path";
import { pathToFileURL } from "node:url";

const casesPath = process.argv[2];
const repoRoot = process.argv[3];

const { parseBuildFontStylesheet } = await import(
  pathToFileURL(join(repoRoot, "frontend/web/npm/precompute-node-fonts.js"))
);
const { stableStringify } = await import(
  pathToFileURL(join(repoRoot, "frontend/web/npm/snapshot-schema.js"))
);

const cases = JSON.parse(readFileSync(casesPath, "utf8"));
const lines = [];
for (const entry of cases) {
  let dump;
  try {
    const faces = parseBuildFontStylesheet(entry.css, {
      source: entry.source,
      publicUrl: entry.publicUrl,
    });
    dump = stableStringify(faces);
  } catch (error) {
    dump = `ERROR:${error instanceof Error ? error.message : String(error)}`;
  }
  lines.push(`${entry.name}\t${dump}`);
}
console.log(lines.join("\n"));
