// Worker-necessity corpus producer (ADR 0053 batch 0, section 5).
//
// Builds the reproducible paragraph corpus for the worker-necessity bench from
// a real built page, running it through the same native snapshot producer the
// site uses at build time. The produced fixtures under fixtures/corpus/ are the
// exact inputs worker-necessity.ts replays:
//
//   manifest.txt   - the snapshot manifest text, exactly as a host serves it
//   tables.tiqtbl  - the finalized binary snapshot tables the manifest pins
//   requests.json  - one layout-worker request object per corpus paragraph
//   meta.json      - font, counts, bucket sizes and file sizes
//
// Run from the npm package root (frontend/web/npm):  node bench/worker-necessity-corpus.ts
//
// Only files under bench/ are written; the source page and the font directory
// are read-only inputs.

import { mkdirSync, readFileSync, readdirSync, statSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

import { snapshotTablesFromBytes } from "@tiqian/core/snapshot-tables.js";
import { parseSnapshotManifest } from "@tiqian/core/snapshot-manifest.js";

const BENCH_DIR: string = dirname(fileURLToPath(import.meta.url));
const FIXTURE_DIR: string = join(BENCH_DIR, "fixtures", "corpus");

// The built page the corpus mirrors. It lives outside this repository, so its
// location comes from the environment; read-only.
const SOURCE_PAGE_PATH: string | undefined = process.env.TIQIAN_BENCH_SOURCE_PAGE;

// Font selection order (from the task spec): IBM Plex Sans SC first, then
// Noto Sans SC / Source Han Sans SC / WenQuanYi, then any CJK-covering face.
const IBM_PLEX_SC_PATTERN: RegExp = /ibmplexsanssc/iu;
const ALTERNATE_CJK_PATTERNS: readonly RegExp[] = [/notosanssc/iu, /sourcehansanssc/iu, /wenquanyi/iu, /wqy/iu];
const GENERIC_CJK_PATTERNS: readonly RegExp[] = [
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
const REGULAR_WEIGHT_PATTERN: RegExp = /regular/iu;
const FONT_EXTENSIONS: RegExp = /\.(?:otf|ttf)$/iu;

interface SiteTypography {
  readonly fontSizePx: number;
  readonly lineHeightPx: number;
  readonly locale: string;
  readonly fontWeight: number;
  readonly italic: boolean;
  readonly firstLineIndentIc: number;
  readonly lineLengthGridEnabled: boolean;
}

interface ProducerTypography extends SiteTypography {
  readonly fontFamilies: readonly string[];
}

interface SelectedFont {
  readonly fileName: string;
  readonly family: string;
}

type LengthBucket = "short" | "medium" | "long";

interface CorpusBuckets {
  short: number;
  medium: number;
  long: number;
}

// Typography of the real site (the oh-my-2019 article and its to-witter
// sidebar). fontSizePx 20, lineHeightPx 30, zh-Hans, weight 400, 2ic first-line
// indent and a 720px measure are the values the blog's live layout uses.
const SITE_TYPOGRAPHY: SiteTypography = Object.freeze({
  fontSizePx: 20,
  lineHeightPx: 30,
  locale: "zh-Hans",
  fontWeight: 400,
  italic: false,
  firstLineIndentIc: 2,
  lineLengthGridEnabled: true,
});
const SITE_MAX_WIDTH_PX: number = 720;
const FONT_FAMILY_SEPARATOR: string = String.fromCharCode(0x1f);

// The snapshot producer currently freezes firstLineIndentIc to 0
// (normalize.rs rejects any other value as UnsupportedSnapshotFirstLineIndent);
// the request objects below still carry the site's 2ic indent.
const PRODUCER_FIRST_LINE_INDENT_IC: number = 0;

const MIN_PARAGRAPH_LENGTH: number = 20;
const SHORT_BUCKET_MAX: number = 80;
const MEDIUM_BUCKET_MAX: number = 200;

const PROSE_ELEMENT_PATTERN: RegExp = /<tiqian-prose(?:\s[^>]*)?>([\s\S]*?)<\/tiqian-prose>/gu;
const PARAGRAPH_ELEMENT_PATTERN: RegExp = /<(p|li)(?:\s[^>]*)?>([\s\S]*?)<\/\1>/gu;
const HTML_TAG_PATTERN: RegExp = /<[^>]+>/gu;
const ZERO_WIDTH_PATTERN: RegExp = /[\u200B-\u200F\u2028\u2029\u2060-\u2064\uFEFF]/gu;
const ASCII_CONTROL_PATTERN: RegExp = new RegExp("[\\u0000-" + String.fromCharCode(0x1f) + "\\u007f]", "gu");
const WHITESPACE_RUN_PATTERN: RegExp = /\s+/gu;
const MANIFEST_SCRIPT_PATTERN: RegExp =
  /<script[^>]*data-tq-snapshot-manifest[^>]*>([\s\S]*?)<\/script>/u;

const NAMED_ENTITIES: Readonly<Record<string, string>> = Object.freeze({
  amp: "&",
  lt: "<",
  gt: ">",
  quot: '"',
  apos: "'",
  nbsp: " ",
});

function decodeEntities(text: string): string {
  return text.replace(
    /&(#x?[0-9a-fA-F]+|[a-zA-Z][a-zA-Z0-9]*);/gu,
    (match: string, body: string): string => {
      if (body[0] === "#") {
        const code: number = body[1] === "x" || body[1] === "X"
          ? Number.parseInt(body.slice(2), 16)
          : Number.parseInt(body.slice(1), 10);
        return Number.isFinite(code) && code >= 0 ? String.fromCodePoint(code) : match;
      }
      return NAMED_ENTITIES[body] ?? match;
    },
  );
}

function stripEngineArtifacts(text: string): string {
  return text
    .replace(ZERO_WIDTH_PATTERN, "")
    .replace(ASCII_CONTROL_PATTERN, " ")
    .replace(WHITESPACE_RUN_PATTERN, " ")
    .trim();
}

// The engine lays out the p/li descendants of a prose root
// (DEFAULT_RUNTIME_PARAGRAPH_SELECTOR in the worker channel); the corpus uses the
// same paragraph granularity so the bench measures realistic per-paragraph work.
function extractParagraphs(sourceHtml: string): string[] {
  const paragraphs: string[] = [];
  let proseMatch: RegExpExecArray | null;
  while ((proseMatch = PROSE_ELEMENT_PATTERN.exec(sourceHtml)) !== null) {
    const proseInnerHtml: string = proseMatch[1];
    PARAGRAPH_ELEMENT_PATTERN.lastIndex = 0;
    let paragraphMatch: RegExpExecArray | null;
    while ((paragraphMatch = PARAGRAPH_ELEMENT_PATTERN.exec(proseInnerHtml)) !== null) {
      const rawText: string = decodeEntities(paragraphMatch[2].replace(HTML_TAG_PATTERN, ""));
      const clean: string = stripEngineArtifacts(rawText);
      if (clean.length >= MIN_PARAGRAPH_LENGTH) paragraphs.push(clean);
    }
  }
  return paragraphs;
}

function bucketOf(length: number): LengthBucket {
  if (length < SHORT_BUCKET_MAX) return "short";
  if (length < MEDIUM_BUCKET_MAX) return "medium";
  return "long";
}

function fontCandidates(directory: string): string[] {
  try {
    return readdirSync(directory)
      .filter((name: string): boolean => FONT_EXTENSIONS.test(name))
      .sort();
  } catch {
    return [];
  }
}

function preferRegular(names: readonly string[]): string | null {
  const regular: readonly string[] = names.filter((name: string): boolean => REGULAR_WEIGHT_PATTERN.test(name));
  return (regular.length > 0 ? regular : names)[0] ?? null;
}

// Picks the font for the native precomputer following the tiered order. The
// chosen face family is derived from the matched tier so the request font
// stack agrees with the face the producer measures.
function selectFont(directory: string): SelectedFont {
  const candidates: readonly string[] = fontCandidates(directory);
  if (candidates.length === 0) {
    throw new Error(
      `BenchFontDirectoryEmpty: no .otf/.ttf files under ${directory}`,
    );
  }
  const plex: readonly string[] = candidates.filter((name: string): boolean => IBM_PLEX_SC_PATTERN.test(name));
  if (plex.length > 0) {
    const chosen: string | null = preferRegular(plex);
    if (chosen !== null) return { fileName: chosen, family: "IBM Plex Sans SC" };
  }
  const alternates: readonly string[] = candidates.filter((name: string): boolean =>
    ALTERNATE_CJK_PATTERNS.some((pattern: RegExp): boolean => pattern.test(name)));
  if (alternates.length > 0) {
    const chosen: string | null = preferRegular(alternates);
    if (chosen !== null) {
      const lower: string = chosen.toLowerCase();
      if (/notosanssc/iu.test(lower)) return { fileName: chosen, family: "Noto Sans SC" };
      if (/sourcehansanssc/iu.test(lower)) return { fileName: chosen, family: "Source Han Sans SC" };
      return { fileName: chosen, family: "WenQuanYi" };
    }
  }
  const generic: readonly string[] = candidates.filter((name: string): boolean =>
    GENERIC_CJK_PATTERNS.some((pattern: RegExp): boolean => pattern.test(name)));
  if (generic.length > 0) {
    const chosen: string | null = preferRegular(generic);
    if (chosen !== null) {
      return { fileName: chosen, family: chosen.replace(FONT_EXTENSIONS, "") };
    }
  }
  throw new Error(
    `BenchCjkFontMissing: no CJK-covering font under ${directory} ` +
      `(looked for IBM Plex Sans SC, Noto/Source Han Sans SC, WenQuanYi, then any CJK-named face)`,
  );
}

function escapeHtml(text: string): string {
  return text
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;");
}

function wrapParagraphs(paragraphs: readonly string[]): string {
  const body: string = paragraphs.map((text: string): string => `<p>${escapeHtml(text)}</p>`).join("\n");
  return `<!doctype html>\n<html><head><meta charset="utf-8">` +
    `<title>tiqian worker-necessity corpus</title></head>` +
    `<body>\n${body}\n</body></html>`;
}

interface ParagraphRequest {
  readonly text: string;
  readonly maxWidthPx: number;
  readonly fontFamilies: string;
  readonly fontSizePx: number;
  readonly lineHeightPx: number;
  readonly locale: string;
  readonly fontWeight: number;
  readonly italic: boolean;
  readonly firstLineIndentIc: number;
  readonly sourceBoundaries: string;
  readonly textSpans: string;
  readonly inlineBoxes: string;
  readonly lineBreakSpans: string;
  readonly index: number;
}

interface PrecomputerFace {
  readonly family: string;
  readonly publicUrl: string;
  readonly source: Uint8Array;
}

interface PrecomputerOptions {
  readonly faces: readonly PrecomputerFace[];
  readonly typography: ProducerTypography;
}

interface PrecomputerInstance {
  readonly renderFontFamilies: readonly string[];
  readonly typography: ProducerTypography;
  close(): void;
}

type CreatePrecomputerFn = (options: PrecomputerOptions) => Promise<PrecomputerInstance>;

interface PrecomputerModule {
  readonly createPrecomputer: CreatePrecomputerFn;
}

interface HtmlPreparerOptions {
  readonly precomputer: PrecomputerInstance;
}

interface SnapshotIssue {
  readonly stage: string;
  readonly index: number;
  readonly key?: string;
  readonly issue?: string;
}

interface PreparedClientBundle {
  readonly clientTemplate?: string;
}

interface PreparedTables {
  readonly bytes?: readonly number[];
}

interface PreparedHtml {
  readonly issues: readonly SnapshotIssue[];
  readonly clientTemplate?: string;
  readonly clientBundle?: PreparedClientBundle;
  readonly tables?: PreparedTables;
}

interface SnapshotPrepareOptions {
  readonly maxWidthPx: number;
}

interface PrepareRequestOptions {
  readonly id: string;
  readonly snapshot: SnapshotPrepareOptions;
}

interface HtmlPreparerInstance {
  prepare(html: string, options: PrepareRequestOptions): Promise<PreparedHtml>;
  close(): void;
}

type CreateHtmlPreparerFn = (options: HtmlPreparerOptions) => Promise<HtmlPreparerInstance>;

interface HtmlPreparerModule {
  readonly createHtmlPreparer: CreateHtmlPreparerFn;
}

// Mirrors the per-request wire shape the Kotlin worker serializer emits
// (WebEnhancerSupport.kt workerLayoutRequestJson): font families are joined
// with U+001F and the span sets use the record/field wire encoding; an empty
// span set is an empty string, not a JSON array. The bench calls the engine
// with exactly these field values. `prepared` here is the precomputer, whose
// renderFontFamilies and typography are the frozen producer values.
function requestForParagraph(text: string, prepared: PrecomputerInstance, requestIndex: number): ParagraphRequest {
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

async function createProducer(fontDirectory: string, font: SelectedFont, typography: ProducerTypography): Promise<PrecomputerInstance> {
  const bytes: Uint8Array = new Uint8Array(readFileSync(join(fontDirectory, font.fileName)));
  const precomputePath: string = new URL("../../../web-precompute/npm/lib/precompute.js", import.meta.url).href;
  const precomputeModule: PrecomputerModule = (await import(precomputePath)) as PrecomputerModule;
  let created: PrecomputerInstance;
  try {
    created = await precomputeModule.createPrecomputer({
      faces: [{
        family: font.family,
        publicUrl: `/fonts/${font.fileName}`,
        source: bytes,
      }],
      typography,
    });
  } catch (error: unknown) {
    throw new Error(
      `BenchProducerCreateFailed: ${error instanceof Error ? error.message : String(error)}`,
    );
  }
  return created;
}

function report(...parts: readonly unknown[]): void {
  console.log(`[corpus] ${parts.join(" ")}`);
}

interface BenchMeta {
  readonly sourcePagePath: string | undefined;
  readonly fontDirectory: string;
  readonly fontFile: string;
  readonly fontFamily: string;
  readonly extractedParagraphCount: number;
  readonly extractedTotalCharacters: number;
  readonly paragraphCount: number;
  readonly totalCharacters: number;
  readonly buckets: CorpusBuckets;
  readonly droppedParagraphIndexes: readonly number[];
  readonly producerTypography: ProducerTypography;
  readonly requestTypography: SiteTypography;
  readonly maxWidthPx: number;
  readonly manifestBytes: number;
  readonly tablesBytes: number;
  readonly requestsBytes: number;
  readonly anomalyNotes: readonly string[];
}

async function main(): Promise<void> {
  if (!SOURCE_PAGE_PATH) {
    throw new Error("TIQIAN_BENCH_SOURCE_PAGE environment variable is not set");
  }
  const sourceHtml: string = readFileSync(SOURCE_PAGE_PATH, "utf8");
  const paragraphs: readonly string[] = extractParagraphs(sourceHtml);
  const totalCharacters: number = paragraphs.reduce((sum: number, text: string): number => sum + text.length, 0);
  const buckets: CorpusBuckets = { short: 0, medium: 0, long: 0 };
  for (const text of paragraphs) buckets[bucketOf(text.length)] += 1;
  report(`source page: ${SOURCE_PAGE_PATH}`);
  report(`paragraphs >= ${MIN_PARAGRAPH_LENGTH} chars: ${paragraphs.length}`);
  report(`total characters: ${totalCharacters}`);
  report(`buckets short(<${SHORT_BUCKET_MAX}) medium(<${MEDIUM_BUCKET_MAX}) long:`,
    JSON.stringify(buckets));

  const homeDir: string = process.env.HOME ?? "";
  const fontDirectory: string = join(homeDir, ".local/share/fonts");
  const font: SelectedFont = selectFont(fontDirectory);
  report(`font used: ${font.fileName} (family ${font.family})`);

  // The producer's frozen typography mirrors the site except for the first-line
  // indent, which the snapshot producer pins to 0 (see PRODUCER_FIRST_LINE_INDENT_IC),
  // and the family list, which names the locally available face.
  const producerTypography: ProducerTypography = {
    ...SITE_TYPOGRAPHY,
    fontFamilies: [font.family],
    firstLineIndentIc: PRODUCER_FIRST_LINE_INDENT_IC,
  };
  const precomputer: PrecomputerInstance = await createProducer(fontDirectory, font, producerTypography);
  const htmlPreparerPath: string = new URL("../../../web-precompute/npm/lib/precompute-html.js", import.meta.url).href;
  const htmlPreparerModule: HtmlPreparerModule = (await import(htmlPreparerPath)) as HtmlPreparerModule;
  const preparer: HtmlPreparerInstance = await htmlPreparerModule.createHtmlPreparer({ precomputer });
  let prepared: PreparedHtml;
  try {
    prepared = await preparer.prepare(wrapParagraphs(paragraphs), {
      id: "tq-bench-corpus",
      snapshot: { maxWidthPx: SITE_MAX_WIDTH_PX },
    });
  } catch (error: unknown) {
    preparer.close();
    precomputer.close();
    throw new Error(
      `BenchPrepareFailed: ${error instanceof Error ? error.message : String(error)}`,
    );
  }
  report(`prepared paragraphs: ${prepared.issues.length === 0 ? "all ok" : JSON.stringify(prepared.issues)}`);

  // Paragraphs the snapshot producer could not prepare (emoji, kaomoji and other
  // glyphs the selected face cannot shape) stay native on the real site and
  // never reach the layout Worker; the corpus mirrors that by dropping them.
  const snapshotIssues: readonly SnapshotIssue[] = prepared.issues.filter((issue: SnapshotIssue): boolean => issue.stage === "snapshot");
  const failedIndexes: Set<number> = new Set<number>(snapshotIssues.map((issue: SnapshotIssue): number => issue.index));
  const corpusParagraphs: readonly string[] = paragraphs.filter((_text: string, index: number): boolean => !failedIndexes.has(index));
  const corpusTotalCharacters: number = corpusParagraphs.reduce((sum: number, text: string): number => sum + text.length, 0);
  const corpusBuckets: CorpusBuckets = { short: 0, medium: 0, long: 0 };
  for (const text of corpusParagraphs) corpusBuckets[bucketOf(text.length)] += 1;
  if (corpusParagraphs.length !== paragraphs.length) {
    report(`dropped ${paragraphs.length - corpusParagraphs.length} paragraphs with snapshot issues;`,
      `corpus now ${corpusParagraphs.length} paragraphs / ${corpusTotalCharacters} chars`);
  }

  const clientTemplate: string = prepared.clientBundle?.clientTemplate ?? "";
  const manifestMatch: RegExpExecArray | null = MANIFEST_SCRIPT_PATTERN.exec(clientTemplate);
  if (!manifestMatch) {
    preparer.close();
    precomputer.close();
    throw new Error("BenchManifestMissing: the client template has no snapshot manifest script");
  }
  const manifestText: string = manifestMatch[1];
  const tablesBytes: Uint8Array = new Uint8Array(prepared.tables?.bytes ?? []);
  if (tablesBytes.length === 0) {
    preparer.close();
    precomputer.close();
    throw new Error("BenchTablesMissing: the prepared html has no snapshot tables");
  }

  // Verify the fixtures decode through the host-side readers the worker uses.
  snapshotTablesFromBytes(tablesBytes);
  parseSnapshotManifest(manifestText, snapshotTablesFromBytes(tablesBytes));
  report("manifest decodes via parseSnapshotManifest, tables via snapshotTablesFromBytes");

  const requests: readonly ParagraphRequest[] = corpusParagraphs.map((text: string, index: number): ParagraphRequest =>
    requestForParagraph(text, precomputer, index));
  const requestsBytes: number = Buffer.byteLength(JSON.stringify(requests), "utf8");
  const manifestBytes: number = Buffer.byteLength(manifestText, "utf8");
  const tablesBytesLength: number = tablesBytes.length;

  mkdirSync(FIXTURE_DIR, { recursive: true });
  writeFileSync(join(FIXTURE_DIR, "manifest.txt"), manifestText, "utf8");
  writeFileSync(join(FIXTURE_DIR, "tables.tiqtbl"), tablesBytes);
  writeFileSync(join(FIXTURE_DIR, "requests.json"), JSON.stringify(requests));
  const meta: BenchMeta = {
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
      "UnsupportedSnapshotFirstLineIndent: the snapshot producer freezes " +
        "firstLineIndentIc to 0; the request objects below carry the site's 2ic value.",
      ...(snapshotIssues.length > 0
        ? [`Snapshot producer dropped ${snapshotIssues.length} emoji/kaomoji paragraphs: ` +
           `${snapshotIssues.map((issue: SnapshotIssue): string => `${issue.key ?? ""}=${issue.issue ?? ""}`).join(", ")}`]
        : []),
    ],
  };
  writeFileSync(join(FIXTURE_DIR, "meta.json"), `${JSON.stringify(meta, null, 2)}\n`);
  const metaBytes: number = statSync(join(FIXTURE_DIR, "meta.json")).size;

  preparer.close();
  precomputer.close();

  report(`fixtures written to ${FIXTURE_DIR}`);
  report("sizes:",
    `manifest.txt=${manifestBytes}B`,
    `tables.tiqtbl=${tablesBytesLength}B`,
    `requests.json=${requestsBytes}B`,
    `meta.json=${metaBytes}B`);
}

main().catch((error: unknown): void => {
  console.error(`[corpus] ${error instanceof Error ? error.stack : String(error)}`);
  process.exitCode = 1;
});
