// Precompute-html parity oracle (ADR 0050). Runs the case matrix written by
// the Rust integration test through the production JS boundary
// (`frontend/web/npm/precompute-html.js`) and emits one `<name>\t<dump>` line
// per case to stdout. The Rust test runs the same matrix through
// tiqian-precompute and byte-compares the two dumps after aligning the one
// exempt engine-identity field.
//
//   node precompute_html_oracle.mjs <cases.json> <repo-root>
//
// The matrix sticks to inputs where linkedom and html5ever build the same
// tree. `<p>` inside `<select>`, fostered table content, and markup after
// `<plaintext>` diverge between the two parsers and stay out of parity
// scope; prose hosts do not produce them.
//
// Sequencing mirrors the Rust harness: the bad selector create runs first
// (it still consumes a session id), then the main preparer walks mainCases
// in order (the close case must be last), then a second preparer with the
// projection callback walks projectorCases. The projector is hardcoded on
// both sides: paragraphs whose source text contains "链" get empty override
// arrays, everything else declines.

import { readFileSync } from "node:fs";
import { join } from "node:path";
import { pathToFileURL } from "node:url";

const casesPath = process.argv[2];
const repoRoot = process.argv[3];

const { createHtmlPreparer } = await import(
  pathToFileURL(join(repoRoot, "frontend/web/npm/precompute-html.js"))
);
const { stableStringify } = await import(
  pathToFileURL(join(repoRoot, "frontend/web/npm/snapshot-schema.js"))
);

const plan = JSON.parse(readFileSync(casesPath, "utf8"));
const font = readFileSync(plan.fontPath);
const baseOptions = {
  typography: { fontFamilies: ["Dela Gothic One"], fontSizePx: 18, lineHeightPx: 27 },
  faces: [{
    family: "Dela Gothic One",
    source: font,
    publicUrl: "/fonts/DelaGothicOne-Regular.ttf",
    weight: 400,
    style: "normal",
  }],
};

const lines = [];
const dump = (name, value) => {
  lines.push(`${name}\t${typeof value === "string" ? value : stableStringify(value)}`);
};
const prepareOptions = (entry) => ({
  ...(entry.id === undefined ? {} : { id: entry.id }),
  ...(entry.snapshotMaxWidthPx === undefined || entry.snapshotMaxWidthPx === null
    ? {}
    : { snapshot: { maxWidthPx: entry.snapshotMaxWidthPx } }),
});
const runCase = async (preparer, entry) => {
  try {
    const result = await preparer.prepare(entry.html, prepareOptions(entry));
    dump(entry.name, result);
  } catch (error) {
    dump(entry.name, `ERROR:${error instanceof Error ? error.message : String(error)}`);
  }
};

try {
  await createHtmlPreparer({ ...baseOptions, paragraphSelector: plan.badSelector });
  dump("badSelector", "ERROR:unreachable");
} catch (error) {
  dump("badSelector", `ERROR:${error instanceof Error ? error.message : String(error)}`);
}

const main = await createHtmlPreparer(baseOptions);
for (const entry of plan.mainCases) {
  if (entry.close === true) main.close();
  await runCase(main, entry);
}

const projector = (context) => context.source.text.includes("链")
  ? { semantics: [], textSpans: [], inlineBoxes: [], sourceBoundaries: [] }
  : null;
const projecting = await createHtmlPreparer({ ...baseOptions, projectSnapshotParagraph: projector });
for (const entry of plan.projectorCases) {
  await runCase(projecting, entry);
}
projecting.close();

console.log(lines.join("\n"));
