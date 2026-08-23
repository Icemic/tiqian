// Worker-necessity bench (ADR 0053 batch 0, section 5).
//
// Measures, per paragraph, the cost of producing a layout plan through the
// server-replay session (Form A: table replay, Form B: repeat cache-hit
// reruns) and the fixed main-thread roundtrip overhead per paragraph
// (serialize take+issue, structuredClone, plan stringify/parse). The numbers
// feed the decomposition report's decision on whether a dedicated Worker is
// necessary for a snapshot-manifest page.
//
// Runs under three runners with plain node, no build step:
//   node bench/worker-necessity.mjs
//   bun bench/worker-necessity.mjs
//   node --jitless bench/worker-necessity.mjs          # slow; use TIQIAN_BENCH_PASSES=3
//
// TIQIAN_BENCH_PASSES overrides the measured pass count (default 30).
//
// No files are written; this script reads bench/fixtures/corpus/ only and
// prints the table plus a single BENCH_JSON line on stdout.

import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { performance } from "node:perf_hooks";

import { createServerReplayFontSession } from "@tiqian/prose-core/browser-font-replay.js";
import { snapshotTablesFromBytes } from "@tiqian/prose-core/snapshot-tables.js";
import { parseSnapshotManifest } from "@tiqian/prose-core/snapshot-manifest.js";
import {
  mergeSerializedSourceBoundaries,
  workerExactSubsetSourceBoundaries,
} from "@tiqian/prose-core/font-face-boundaries.js";
import { precomputeParagraph } from "@tiqian/ffi";
import { LAYOUT_REQUEST_FIELDS } from "@tiqian/prose-core/core/engine/web-worker/assembly-record-fields.js";

const BENCH_DIR = dirname(fileURLToPath(import.meta.url));
const FIXTURE_DIR = join(BENCH_DIR, "fixtures", "corpus");

// Pass configuration: Form A warms up three unmeasured full passes so the
// Kotlin/JS engine and JIT reach steady state before any number is taken.
const FORM_A_WARMUP_PASSES = 3;
const FORM_B_DISCARD_PASSES = 2;
const DEFAULT_MEASURED_PASSES = 30;
const ROUNDTRIP_SAMPLES = 200;

const SHORT_BUCKET_MAX = 80;
const MEDIUM_BUCKET_MAX = 200;

function layoutRequestKey(request) {
  return JSON.stringify(LAYOUT_REQUEST_FIELDS.map((field) => request?.[field] ?? null));
}

function bucketOf(textLength) {
  if (textLength < SHORT_BUCKET_MAX) return "short";
  if (textLength < MEDIUM_BUCKET_MAX) return "medium";
  return "long";
}

function percentile(sortedValues, fraction) {
  if (sortedValues.length === 0) return Number.NaN;
  const index = Math.max(0, Math.ceil(fraction * sortedValues.length) - 1);
  return sortedValues[index];
}

function statsOf(durations) {
  const sorted = [...durations].sort((left, right) => left - right);
  const count = sorted.length;
  const sum = sorted.reduce((total, value) => total + value, 0);
  return {
    count,
    p50: percentile(sorted, 0.5),
    p95: percentile(sorted, 0.95),
    mean: count > 0 ? sum / count : Number.NaN,
    min: count > 0 ? sorted[0] : Number.NaN,
    max: count > 0 ? sorted[count - 1] : Number.NaN,
  };
}

function statsByBucket(records) {
  const buckets = { short: [], medium: [], long: [] };
  for (const record of records) buckets[record.bucket].push(record.durationUs);
  return {
    short: statsOf(buckets.short),
    medium: statsOf(buckets.medium),
    long: statsOf(buckets.long),
  };
}

// Loads the fixtures and rebuilds the replay session by replicating
// layout-worker.js manifestSession (layout-worker.js:12-52): decode the tables,
// expand the manifest, dedupe evidence faces by the full face-identity key,
// sort by sourceOrder, then create the server-replay session with the pinned
// replay data and the empty face specs the worker passes.
async function replaySession() {
  const manifestText = readFileSync(join(FIXTURE_DIR, "manifest.txt"), "utf8");
  const tablesBytes = new Uint8Array(readFileSync(join(FIXTURE_DIR, "tables.tiqtbl")));
  const tables = tablesBytes.length > 0 ? snapshotTablesFromBytes(tablesBytes) : null;
  const manifest = parseSnapshotManifest(manifestText, tables);
  const entries = [...(manifest.entries ?? []), ...(manifest.fontContractEntries ?? [])];
  const evidence = entries.flatMap((entry) => entry?.fontEvidence?.faces ?? []);
  if (evidence.length === 0 || !manifest.fontReplay) {
    throw new Error("LayoutWorkerFontContractInvalid");
  }
  const faces = [];
  const seen = new Set();
  for (const face of evidence) {
    const key = JSON.stringify([
      face.sfntSha256,
      face.faceIndex,
      face.sourceOrder,
      face.family,
      face.style,
      face.weight,
      face.unicodeRange,
      face.publicUrl,
    ]);
    if (seen.has(key)) continue;
    seen.add(key);
    faces.push(face);
  }
  faces.sort((left, right) => Number(left.sourceOrder) - Number(right.sourceOrder));
  const first = entries.find((entry) => entry?.fontEvidence)?.fontEvidence;
  const session = await createServerReplayFontSession(
    faces.map(() => ({})),
    {
      sessionPrefix: "tq-worker-bench",
      replay: manifest.fontReplay,
      faceMetadata: faces,
      harfbuzzVersion: first?.harfbuzzVersion ?? "",
    },
  );
  const requests = JSON.parse(readFileSync(join(FIXTURE_DIR, "requests.json"), "utf8"));
  return { session, requests };
}

// Replicates the per-request worker body (layout-worker.js:82-102): the exact
// source boundaries merge, then precomputeParagraph with the fixed
// lineLengthGridEnabled=true argument the worker passes.
function callParagraph(session, request) {
  const sourceBoundaries = mergeSerializedSourceBoundaries(
    request.sourceBoundaries,
    workerExactSubsetSourceBoundaries(session.faces, request),
  );
  return precomputeParagraph(
    session.id,
    request.text,
    request.maxWidthPx,
    request.fontFamilies,
    request.fontSizePx,
    request.lineHeightPx,
    request.locale,
    request.fontWeight,
    request.italic,
    request.firstLineIndentIc,
    true,
    sourceBoundaries,
    request.textSpans,
    request.inlineBoxes,
    request.lineBreakSpans,
    request.inlineObjects,
  );
}

function runFullPass(session, requests) {
  for (const request of requests) callParagraph(session, request);
}

// Runs `measuredPasses` passes and returns one duration record per paragraph,
// tagged with its length bucket for the overall and per-bucket stats.
function measurePasses(session, requests, measuredPasses, onPlan) {
  const records = [];
  for (let pass = 0; pass < measuredPasses; pass += 1) {
    for (const request of requests) {
      const startedAt = performance.now();
      const plan = callParagraph(session, request);
      records.push({
        bucket: bucketOf(request.text.length),
        durationUs: (performance.now() - startedAt) * 1000,
      });
      onPlan?.(plan);
    }
  }
  return records;
}

function measureMicroseconds(operation) {
  operation();
  const startedAt = performance.now();
  for (let index = 0; index < ROUNDTRIP_SAMPLES; index += 1) operation();
  return ((performance.now() - startedAt) / ROUNDTRIP_SAMPLES) * 1000;
}

function formatUs(value) {
  return Number.isFinite(value) ? value.toFixed(1) : "n/a";
}

function formatStats(stats) {
  return `p50=${formatUs(stats.p50)} p95=${formatUs(stats.p95)} ` +
    `mean=${formatUs(stats.mean)} min=${formatUs(stats.min)} max=${formatUs(stats.max)} (n=${stats.count})`;
}

function printBucketRows(title, byBucket) {
  console.log(`  ${title} (us)`);
  for (const name of ["short", "medium", "long"]) {
    console.log(`    ${name.padEnd(6)} ${formatStats(byBucket[name])}`);
  }
}

async function main() {
  const measuredPasses = Number.parseInt(process.env.TIQIAN_BENCH_PASSES ?? "", 10) ||
    DEFAULT_MEASURED_PASSES;
  const meta = JSON.parse(readFileSync(join(FIXTURE_DIR, "meta.json"), "utf8"));
  console.log(
    `tiqian worker-necessity bench | corpus ${meta.paragraphCount} paragraphs ` +
    `(${JSON.stringify(meta.buckets)}) | ${measuredPasses} measured passes`,
  );

  const { session, requests } = await replaySession();

  // Form A: table replay through the server-replay session.
  for (let pass = 0; pass < FORM_A_WARMUP_PASSES; pass += 1) {
    runFullPass(session, requests);
  }
  let capturedPlan = null;
  const formA = measurePasses(session, requests, measuredPasses, (plan) => {
    if (capturedPlan === null) capturedPlan = plan;
  });

  // Form B: repeat the same requests in the same session. Passes 0 and 1 are
  // discarded so the reported numbers sit in the registry-hit regime where
  // every shaping/metrics read is a Map lookup against the session tables.
  for (let pass = 0; pass < FORM_B_DISCARD_PASSES; pass += 1) {
    runFullPass(session, requests);
  }
  const formB = measurePasses(session, requests, measuredPasses);

  // Per-paragraph fixed roundtrip overhead.
  const referenceRequest = requests[0];
  const serializeTakeIssueUs = measureMicroseconds(() => {
    // One take + one issue each serialize the layout request key and the
    // request (worker-channel.js:100-101). Each sample runs the pair twice, so
    // the reported figure covers two take+issue pairs and overstates the
    // per-paragraph cost in the conservative direction.
    for (let index = 0; index < 2; index += 1) {
      layoutRequestKey(referenceRequest);
      JSON.stringify(referenceRequest);
    }
  });
  const structuredCloneUs = measureMicroseconds(() => structuredClone(referenceRequest));
  const capturedPlanObject = JSON.parse(capturedPlan);
  const planRoundtripUs = measureMicroseconds(() => {
    JSON.parse(JSON.stringify(capturedPlanObject));
  });
  const fixedOverheadUs = serializeTakeIssueUs + structuredCloneUs + planRoundtripUs;

  const formAOverall = statsOf(formA.map((record) => record.durationUs));
  const formBOverall = statsOf(formB.map((record) => record.durationUs));

  console.log(`\nForm A (table replay, after ${FORM_A_WARMUP_PASSES} warmup passes):`);
  console.log(`  overall   ${formatStats(formAOverall)}`);
  printBucketRows("short (<80), medium (80-199), long (>=200)", statsByBucket(formA));
  console.log(`\nForm B (cache-hit rerun, passes >= ${FORM_B_DISCARD_PASSES}):`);
  console.log(`  overall   ${formatStats(formBOverall)}`);
  printBucketRows("short (<80), medium (80-199), long (>=200)", statsByBucket(formB));
  console.log(`\nPer-paragraph fixed roundtrip overhead (us):`);
  console.log(`  serialize take+issue  ${formatUs(serializeTakeIssueUs)}`);
  console.log(`  structuredClone       ${formatUs(structuredCloneUs)}`);
  console.log(`  plan stringify/parse  ${formatUs(planRoundtripUs)}`);
  console.log(`  sum (fixed overhead)  ${formatUs(fixedOverheadUs)}`);

  session.close?.();
  const benchJson = {
    corpus: {
      paragraphs: meta.paragraphCount,
      totalCharacters: meta.totalCharacters,
      buckets: meta.buckets,
    },
    passes: {
      formAWarmup: FORM_A_WARMUP_PASSES,
      formBDiscard: FORM_B_DISCARD_PASSES,
      measured: measuredPasses,
    },
    formA: {
      overall: formAOverall,
      byBucket: statsByBucket(formA),
    },
    formB: {
      overall: formBOverall,
      byBucket: statsByBucket(formB),
    },
    roundtrip: {
      serializeTakeIssueUs,
      structuredCloneUs,
      planRoundtripUs,
      fixedOverheadUs,
    },
  };
  console.log(`\nBENCH_JSON ${JSON.stringify(benchJson)}`);
}

main().catch((error) => {
  console.error(`[bench] ${error instanceof Error ? error.stack : String(error)}`);
  process.exitCode = 1;
});