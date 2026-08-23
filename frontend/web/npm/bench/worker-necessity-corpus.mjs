// Worker-necessity corpus producer (ADR 0053 batch 0, section 5).
//
// Builds the reproducible paragraph corpus for the worker-necessity bench from
// a real built page, running it through the same native snapshot producer the
// site uses at build time. The produced fixtures under fixtures/corpus/ are the
// exact inputs worker-necessity.mjs replays:
//
//   manifest.txt   - the snapshot manifest text, exactly as a host serves it
//   tables.tiqtbl  - the finalized binary snapshot tables the manifest pins
//   requests.json  - one layout-worker request object per corpus paragraph
//   meta.json      - font, counts, bucket sizes and file sizes
//
// Run from the npm package root (frontend/web/npm):  node bench/worker-necessity-corpus.mjs
//
// Only files under bench/ are written; the source page and the font directory
// are read-only inputs.

import { mkdirSync, readFileSync, readdirSync, statSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

import { createPrecomputer } from "../../../web-precompute/npm/lib/precompute.js";
import { createHtmlPreparer } from "../../../web-precompute/npm/lib/precompute-html.js";
import { snapshotTablesFromBytes } from "@tiqian/prose-core/snapshot-tables.js";
import { parseSnapshotManifest } from "@tiqian/prose-core/snapshot-manifest.js";

const BENCH_DIR = dirname(fileURLToPath(import.meta.url));
const FIXTURE_DIR = join(BENCH_DIR, "fixtures", "corpus");

// The built page the corpus mirrors. Read-only; kept as a literal so the bench
// stays reproducible across machines.
const SOURCE_PAGE_PATH = "/sveltekit 站点/build/2019/12/17/oh-my-2019.html";

// Font selection order (from the task spec): IBM Plex Sans SC first, then
// Noto Sans SC / Source Han Sans SC / WenQuanYi, then any CJK-covering face.
const IBM_PLEX_SC_PATTERN = /ibmplexsanssc/iu;
const ALTERNATE_CJK_PATTERNS = [/notosanssc/iu, /sourcehansanssc/iu, /wenquanyi/iu, /wqy/iu];
const GENERIC_CJK_PATTERNS = [
  /cjk/iu,
  /zh_hans/iu,
  /hans/iu,
  /chinese/iu,
  /simhei/iu,
  /simsun/iu,
  /msyh/iu,
  /yahei/iu,
  /\.sc\./iu,
  /roundedcn/iu,
];
const REGULAR_WEIGHT_PATTERN = /regular/iu;
const FONT_EXTENSIONS = /\.(?:otf|ttf)$/iu;

// Typography of the real site (the oh-my-2019 article and its to-witter
// sidebar). fontSizePx 20, lineHeightPx 30, zh-Hans, weight 400, 2ic first-line
// indent and a 720px measure are the values the blog's live layout uses.
const SITE_TYPOGRAPHY = Object.freeze({
  fontSizePx: 20,
  lineHeightPx: 30,
  locale: "zh-Hans",
  fontWeight: 400,
  italic: false,
  firstLineIndentIc: 2,
  lineLengthGridEnabled: true,
});
const SITE_MAX_WIDTH_PX = 720;
const FONT_FAMILY_SEPARATOR = "\u001f";

// The snapshot producer lane currently freezes firstLineIndentIc to 0
// (normalize.rs rejects any other value as UnsupportedSnapshotFirstLineIndent);
// the request objects below still carry the site's 2ic indent.
const PRODUCER_FIRST_LINE_INDENT_IC = 0;

const MIN_PARAGRAPH_LENGTH = 20;
const SHORT_BUCKET_MAX = 80;
const MEDIUM_BUCKET_MAX = 200;

const PROSE_ELEMENT_PATTERN = /<tiqian-prose(?:\s[^>]*)?>([\s\S]*?)<\/tiqian-prose>/gu;
const PARAGRAPH_ELEMENT_PATTERN = /<(p|li)(?:\s[^>]*)?>([\s\S]*?)<\/\1>/gu;
const HTML_TAG_PATTERN = /<[^>]+>/gu;
const ZERO_WIDTH_PATTERN = /[\u200B-\u200F\u2028\u2029\u2060-\u2064\uFEFF]/gu;
const ASCII_CONTROL_PATTERN = /[\u0000-\u001F\u007F]/gu;
const WHITESPACE_RUN_PATTERN = /\s+/gu;
const MANIFEST_SCRIPT_PATTERN =
  /<script[^>]*data-tq-snapshot-manifest[^>]*>([\s\S]*?)<\/script>/u;

const NAMED_ENTITIES = Object.freeze({
  amp: "&",
  lt: "<",
  gt: ">",
  quot: '"',
  apos: "'",
  nbsp: " ",
});

function decodeEntities(text) {
  return text.replace(
    /&(#x?[0-9a-fA-F]+|[a-zA-Z][a-zA-Z0-9]*);/gu,
    (match, body) => {
      if (body[0] === "#") {
        const code = body[1] === "x" || body[1] === "X"
          ? Number.parseInt(body.slice(2), 16)
          : Number.parseInt(body.slice(1), 10);
        return Number.isFinite(code) && code >= 0 ? String.fromCodePoint(code) : match;
      }
      return NAMED_ENTITIES[body] ?? match;
    },
  );
}

function stripEngineArtifacts(text) {
  return text
    .replace(ZERO_WIDTH_PATTERN, "")
    .replace(ASCII_CONTROL_PATTERN, " ")
    .replace(WHITESPACE_RUN_PATTERN, " ")
    .trim();
}

// The engine lays out the p/li descendants of a prose root
// (DEFAULT_RUNTIME_PARAGRAPH_SELECTOR in the worker channel); the corpus uses the
// same paragraph granularity so the bench measures realistic per-paragraph work.
function extractParagraphs(sourceHtml) {
  const paragraphs = [];
  let proseMatch;
  while ((proseMatch = PROSE_ELEMENT_PATTERN.exec(sourceHtml)) !== null) {
    const proseInnerHtml = proseMatch[1];
    PARAGRAPH_ELEMENT_PATTERN.lastIndex = 0;
    let paragraphMatch;
    while ((paragraphMatch = PARAGRAPH_ELEMENT_PATTERN.exec(proseInnerHtml)) !== null) {
      const rawText = decodeEntities(paragraphMatch[2].replace(HTML_TAG_PATTERN, ""));
      const clean = stripEngineArtifacts(rawText);
      if (clean.length >= MIN_PARAGRAPH_LENGTH) paragraphs.push(clean);
    }
  }
  return paragraphs;
}

function bucketOf(length) {
  if (length < SHORT_BUCKET_MAX) return "short";
  if (length < MEDIUM_BUCKET_MAX) return "medium";
  return "long";
}

function fontCandidates(directory) {
  try {
    return readdirSync(directory)
      .filter((name) => FONT_EXTENSIONS.test(name))
      .sort();
  } catch {
    return [];
  }
}

function preferRegular(names) {
  const regular = names.filter((name) => REGULAR_WEIGHT_PATTERN.test(name));
  return (regular.length > 0 ? regular : names)[0] ?? null;
}

// Picks the font for the native precomputer following the tiered order. The
// chosen face family is derived from the matched tier so the request font
// stack agrees with the face the producer measures.
function selectFont(directory) {
  const candidates = fontCandidates(directory);
  if (candidates.length === 0) {
    throw new Error(
      `BenchFontDirectoryEmpty: no .otf/.ttf files under ${directory}`,
    );
  }
  const plex = candidates.filter((name) => IBM_PLEX_SC_PATTERN.test(name));
  if (plex.length > 0) {
    const chosen = preferRegular(plex);
    return { fileName: chosen, family: "IBM Plex Sans SC" };
  }
  const alternates = candidates.filter((name) =>
    ALTERNATE_CJK_PATTERNS.some((pattern) => pattern.test(name)));
  if (alternates.length > 0) {
    const chosen = preferRegular(alternates);
    const lower = chosen.toLowerCase();
    if (/notosanssc/iu.test(lower)) return { fileName: chosen, family: "Noto Sans SC" };
    if (/sourcehansanssc/iu.test(lower)) return { fileName: chosen, family: "Source Han Sans SC" };
    return { fileName: chosen, family: "WenQuanYi" };
  }
  const generic = candidates.filter((name) =>
    GENERIC_CJK_PATTERNS.some((pattern) => pattern.test(name)));
  if (generic.length > 0) {
    const chosen = preferRegular(generic);
    return { fileName: chosen, family: chosen.replace(FONT_EXTENSIONS, "") };
  }
  throw new Error(
    `BenchCjkFontMissing: no CJK-covering font under ${directory} ` +
      `(looked for IBM Plex Sans SC, Noto/Source Han Sans SC, WenQuanYi, then any CJK-named face)`,
  );
}

function escapeHtml(text) {
  return text
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;");
}

function wrapParagraphs(paragraphs) {
  const body = paragraphs.map((text) => `<p>${escapeHtml(text)}</p>`).join("\n");
  return `<!doctype html>\n<html><head><meta charset="utf-8">` +
    `<title>tiqian worker-necessity corpus</title></head>` +
    `<body>\n${body}\n</body></html>`;
}

// Mirrors the per-request wire shape the Kotlin worker serializer emits
// (WebEnhancerSupport.kt workerLayoutRequestJson): font families are joined
// with U+001F and the span sets use the record/field wire encoding; an empty
// span set is an empty string, not a JSON array. The bench calls the engine
// with exactly these field values. `prepared` here is the precomputer, whose
// renderFontFamilies and typography are the frozen producer values.
function requestForParagraph(text, prepared, requestIndex) {
  return {
    text,
    maxWidthPx: SITE_MAX_WIDTH_PX,
    fontFamilies: prepared.renderFontFamilies.join(FONT_FAMILY_SEPARATOR),
    fontSizePx: prepared.typography.fontSizePx,
    lineHeightPx: prepared.typography.lineHeightPx,
    locale: prepared.typography.locale,
    fontWeight: prepared.typography.fontWeight,
    italic: prepared.typography.italic,
    firstLineIndentIc: SITE_TYPOGRAPHY.firstLineIndentIc,
    sourceBoundaries: "",
    textSpans: "",
    inlineBoxes: "",
    lineBreakSpans: "",
    index: requestIndex,
  };
}

async function createProducer(fontDirectory, font, typography) {
  const bytes = readFileSync(join(fontDirectory, font.fileName));
  let created;
  try {
    created = await createPrecomputer({
      faces: [{
        family: font.family,
        publicUrl: `/fonts/${font.fileName}`,
        source: bytes,
      }],
      typography,
    });
  } catch (error) {
    throw new Error(
      `BenchProducerCreateFailed: ${error instanceof Error ? error.message : String(error)}`,
    );
  }
  return created;
}

function report(...parts) {
  console.log(`[corpus] ${parts.join(" ")}`);
}

async function main() {
  const sourceHtml = readFileSync(SOURCE_PAGE_PATH, "utf8");
  const paragraphs = extractParagraphs(sourceHtml);
  const totalCharacters = paragraphs.reduce((sum, text) => sum + text.length, 0);
  const buckets = { short: 0, medium: 0, long: 0 };
  for (const text of paragraphs) buckets[bucketOf(text.length)] += 1;
  report(`source page: ${SOURCE_PAGE_PATH}`);
  report(`paragraphs >= ${MIN_PARAGRAPH_LENGTH} chars: ${paragraphs.length}`);
  report(`total characters: ${totalCharacters}`);
  report(`buckets short(<${SHORT_BUCKET_MAX}) medium(<${MEDIUM_BUCKET_MAX}) long:`,
    JSON.stringify(buckets));

  const fontDirectory = join(process.env.HOME, ".local/share/fonts");
  const font = selectFont(fontDirectory);
  report(`font used: ${font.fileName} (family ${font.family})`);

  // The producer's frozen typography mirrors the site except for the first-line
  // indent, which the snapshot lane pins to 0 (see PRODUCER_FIRST_LINE_INDENT_IC),
  // and the family list, which names the locally available face.
  const producerTypography = {
    ...SITE_TYPOGRAPHY,
    fontFamilies: [font.family],
    firstLineIndentIc: PRODUCER_FIRST_LINE_INDENT_IC,
  };
  const precomputer = await createProducer(fontDirectory, font, producerTypography);
  const preparer = await createHtmlPreparer({ precomputer });
  let prepared;
  try {
    prepared = await preparer.prepare(wrapParagraphs(paragraphs), {
      id: "tq-bench-corpus",
      snapshot: { maxWidthPx: SITE_MAX_WIDTH_PX },
    });
  } catch (error) {
    preparer.close();
    precomputer.close();
    throw new Error(
      `BenchPrepareFailed: ${error instanceof Error ? error.message : String(error)}`,
    );
  }
  report(`prepared paragraphs: ${prepared.issues.length === 0 ? "all ok" : JSON.stringify(prepared.issues)}`);

  // Paragraphs the snapshot lane could not prepare (emoji, kaomoji and other
  // glyphs the selected face cannot shape) stay native on the real site and
  // never reach the layout Worker; the corpus mirrors that by dropping them.
  const snapshotIssues = prepared.issues.filter((issue) => issue.stage === "snapshot");
  const failedIndexes = new Set(snapshotIssues.map((issue) => issue.index));
  const corpusParagraphs = paragraphs.filter((_text, index) => !failedIndexes.has(index));
  const corpusTotalCharacters = corpusParagraphs.reduce((sum, text) => sum + text.length, 0);
  const corpusBuckets = { short: 0, medium: 0, long: 0 };
  for (const text of corpusParagraphs) corpusBuckets[bucketOf(text.length)] += 1;
  if (corpusParagraphs.length !== paragraphs.length) {
    report(`dropped ${paragraphs.length - corpusParagraphs.length} paragraphs with snapshot issues;`,
      `corpus now ${corpusParagraphs.length} paragraphs / ${corpusTotalCharacters} chars`);
  }

  const clientTemplate = prepared.clientBundle?.clientTemplate ?? "";
  const manifestMatch = MANIFEST_SCRIPT_PATTERN.exec(clientTemplate);
  if (!manifestMatch) {
    preparer.close();
    precomputer.close();
    throw new Error("BenchManifestMissing: the client template has no snapshot manifest script");
  }
  const manifestText = manifestMatch[1];
  const tablesBytes = new Uint8Array(prepared.tables?.bytes ?? []);
  if (tablesBytes.length === 0) {
    preparer.close();
    precomputer.close();
    throw new Error("BenchTablesMissing: the prepared html has no snapshot tables");
  }

  // Verify the fixtures decode through the host-side readers the worker uses.
  snapshotTablesFromBytes(tablesBytes);
  parseSnapshotManifest(manifestText, snapshotTablesFromBytes(tablesBytes));
  report("manifest decodes via parseSnapshotManifest, tables via snapshotTablesFromBytes");

  const requests = corpusParagraphs.map((text, index) =>
    requestForParagraph(text, precomputer, index));
  const requestsBytes = Buffer.byteLength(JSON.stringify(requests), "utf8");
  const manifestBytes = Buffer.byteLength(manifestText, "utf8");
  const tablesBytesLength = tablesBytes.length;

  mkdirSync(FIXTURE_DIR, { recursive: true });
  writeFileSync(join(FIXTURE_DIR, "manifest.txt"), manifestText, "utf8");
  writeFileSync(join(FIXTURE_DIR, "tables.tiqtbl"), tablesBytes);
  writeFileSync(join(FIXTURE_DIR, "requests.json"), JSON.stringify(requests));
  const meta = {
    sourcePagePath: SOURCE_PAGE_PATH,
    fontDirectory,
    fontFile: font.fileName,
    fontFamily: font.family,
    extractedParagraphCount: paragraphs.length,
    extractedTotalCharacters: totalCharacters,
    paragraphCount: corpusParagraphs.length,
    totalCharacters: corpusTotalCharacters,
    buckets: corpusBuckets,
    droppedParagraphIndexes: [...failedIndexes],
    producerTypography,
    requestTypography: SITE_TYPOGRAPHY,
    maxWidthPx: SITE_MAX_WIDTH_PX,
    manifestBytes,
    tablesBytes: tablesBytesLength,
    requestsBytes,
    anomalyNotes: [
      "UnsupportedSnapshotFirstLineIndent: the snapshot producer lane freezes " +
        "firstLineIndentIc to 0; the request objects below carry the site's 2ic value.",
      ...(snapshotIssues.length > 0
        ? [`Snapshot lane dropped ${snapshotIssues.length} emoji/kaomoji paragraphs: ` +
           `${snapshotIssues.map((issue) => `${issue.key}=${issue.issue}`).join(", ")}`]
        : []),
    ],
  };
  writeFileSync(join(FIXTURE_DIR, "meta.json"), `${JSON.stringify(meta, null, 2)}\n`);
  const metaBytes = statSync(join(FIXTURE_DIR, "meta.json")).size;

  preparer.close();
  precomputer.close();

  report(`fixtures written to ${FIXTURE_DIR}`);
  report("sizes:",
    `manifest.txt=${manifestBytes}B`,
    `tables.tiqtbl=${tablesBytesLength}B`,
    `requests.json=${requestsBytes}B`,
    `meta.json=${metaBytes}B`);
}

main().catch((error) => {
  console.error(`[corpus] ${error instanceof Error ? error.stack : String(error)}`);
  process.exitCode = 1;
});