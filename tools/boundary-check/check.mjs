#!/usr/bin/env node
// Mechanical boundary gate for the rulings in ADR 0053, section
// "ffi 边界复审记录（2026-08-25）" (docs/adr/0053-web-prose-host-consolidation.md).
//
// The review record found four classes of boundary defects and ordered
// corrective waves (纠偏 1..5). This gate keeps each class mechanically
// visible in CI until its wave lands; every existing occurrence is either
// clean or carries an explicit exemption tagged with the corrective task
// that will delete both the code and the exemption entry.
//
// Task id mapping used across this file and the exemption list:
//   #102 = 纠偏 1  delete the HarfBuzzSession family and every related
//                  environment global (engine jsMain).
//   #103 = 纠偏 2  move the request parsers back to ffi/js; ParagraphWireFace
//                  leaves engine commonMain.
//   #104 = 纠偏 3  rename the ffi data conversion layer by function and merge
//                  files (BrowserMetricsExports codec pieces -> WireJson + JsCallbackAdapters).
//   #105 = 纠偏 4  delete buildPrecomputeBackends and the session-id exports.
//   #106 = 纠偏 5  declared DTOs across the boundary; untyped separator /
//                  JSON-string payloads disappear.
//
// Rules:
//   R1  engine/ (all source sets, *.kt): no `globalThis`, no `__Tiqian*`
//       identifiers. Ruling: the js lane reads `globalThis.__TiqianFontBackend`
//       inline through @JsFun while the native side uses an installed vtable;
//       the environment-global family is slated for deletion.
//   R2  engine/src/commonMain (*.kt): no file name or top-level declaration
//       name ending in Wire/Face, none containing "precompute". Ruling: the
//       wire format code (A3, ParagraphWireFace) was wrongly moved into the
//       engine; it belongs to the ffi/js data conversion layer.
//   R3a ffi/ (*.kt): no font-session state. Tokens banned everywhere in ffi/:
//       HarfBuzzSession, buildPrecomputeBackends. Token `fontSessionId` is
//       banned only in the JS lane (ffi/js/src/jsMain, jsTest): there it
//       resolves through the environment global; the native lane passes a
//       session id through the declared, version-checked TQLR contract, which
//       the same ruling marks compliant, so native sources stay out of scope
//       for that token.
//   R3b ffi/ export entries (@JsExport functions, @CName symbols): every
//       entry must be reviewed against an engine function counterpart
//       (REVIEWED_EXPORT_ENTRIES below) or carry an explicit exemption.
//       Ruling: "ffi 只负责把 Engine 的函数原封不动的暴露给外面，FFI 不自带
//       业务逻辑，FFI 可以做数据转换。" First version = machine-enforced
//       manual checklist; tightens with the corrective waves.
//   R4  Cross-boundary payload: the four separator escape literals
//       \u001c \u001d \u001e \u001f (also their \x1c.. and \u{..} spellings)
//       may appear only inside declared codec modules. Everything else needs
//       an exemption. Frozen payload text stays legitimate at byte-comparison
//       sites (parity oracle, golden evidence), which is why the oracle script
//       is part of the codec list.
//
// Scanned roots: engine/, ffi/, frontend/ (npm, core, web-precompute).
// Source extensions only: .kt .rs .ts .tsx .js .mjs .cjs. Checked-in dump,
// golden and fixture data files are byte-comparison artifacts sanctioned by
// the ruling and are not scanned.
//
// Style follows tools/package-topology/check.mjs: zero npm dependencies,
// node >= 22 builtins only. Exit codes: 0 = clean, 1 = violation or
// unreadable input. Stale exemptions (matching no current hit) are printed
// as loud notes so the corrective waves delete their list segments without
// blocking unrelated work.

import { execFileSync } from "node:child_process";
import { existsSync, readFileSync } from "node:fs";
import path from "node:path";
import process from "node:process";
import { fileURLToPath } from "node:url";

const repoRoot = fileURLToPath(new URL("../../", import.meta.url));

const SCANNED_ROOTS = ["engine", "ffi", "frontend", "platforms"];

const IGNORED_DIR_NAMES = new Set([
  "node_modules",
  "runtime",
  "build",
  ".gradle",
  ".b2-tmp",
  "target",
  "demo",
  "dist",
]);

const SOURCE_EXTENSIONS = new Set([
  ".kt",
  ".rs",
  ".ts",
  ".tsx",
  ".js",
  ".mjs",
  ".cjs",
]);

const KOTLIN_EXTENSIONS = new Set([".kt"]);

// The gate reviews the tracked sources, which is what CI sees on a fresh
// checkout. Enumerating with `git ls-files` keeps local runs identical even
// when in-place emit artifacts (untracked .js next to their .ts sources) or
// other build outputs sit on disk in a developer tree.
function collectFiles(rootRelPath, extensions) {
  return execFileSync("git", ["ls-files", "--", rootRelPath], {
    cwd: repoRoot,
    encoding: "utf8",
  })
    .split("\n")
    .filter(Boolean)
    .filter((relPath) => {
      const segments = relPath.split("/");
      if (segments.some((segment) => IGNORED_DIR_NAMES.has(segment))) {
        return false;
      }
      if (!extensions.has(path.extname(relPath))) return false;
      return existsSync(path.join(repoRoot, relPath));
    })
    .map((relPath) => path.join(repoRoot, relPath))
    .sort();
}

function readLines(file) {
  const text = readFileSync(file, "utf8");
  return text.split("\n");
}

// ---------------------------------------------------------------------------
// Exemption list. Every entry cites the corrective task (#10x = 纠偏 x) that
// deletes the code together with this entry. File-level unless `line` or
// `symbol` is set.
// ---------------------------------------------------------------------------

const EXEMPTIONS = [
  // R2: the wire codec sits in engine commonMain until 纠偏 2/#103 moves the
  // parsers back to ffi/js.

  // R3b: export entries awaiting their corrective wave instead of an engine
  // counterpart sign-off.

// R4: separator literals outside the declared codec modules. All die when
// payloads cross the boundary as declared DTOs (纠偏 5/#106), except the
// wire codec test which dies with the codec move (#103).
  {
    task: "#106",
    rules: ["R4-separator-literal"],
    file: "frontend/web/npm/bench/worker-necessity-corpus.mjs",
    reason: "Separator constant copy of the untyped worker payload; disappears when payloads cross as declared DTOs.",
  },
  {
    task: "#106",
    rules: ["R4-separator-literal"],
    file: "frontend/web/npm/element.ts",
    reason: "Separator join in signature building over the untyped payload; replaced by declared DTOs.",
  },
  {
    task: "#106",
    rules: ["R4-separator-literal"],
    file: "frontend/web/npm/tests/runtime-host.mjs",
    reason: "Split/join over the untyped worker payload records and fields; replaced by declared DTOs.",
  },
  {
    task: "#106",
    rules: ["R4-separator-literal"],
    file: "frontend/web/core/core/engine/canvas-fonts.ts",
    reason: "Family separator split of the untyped payload string; replaced by declared DTOs.",
  },
  {
    task: "#106",
    rules: ["R4-separator-literal"],
    file: "frontend/web/core/core/engine/canvas-metrics.ts",
    reason: "Record/field/family splits of the untyped payload strings; replaced by declared DTOs.",
  },
  {
    task: "#106",
    rules: ["R4-separator-literal"],
    file: "frontend/web/core/core/sampler/font-face-boundaries.ts",
    reason: "Separator use in untyped sampling payloads; replaced by declared DTOs.",
  },
  {
    task: "#106",
    rules: ["R4-separator-literal"],
    file: "frontend/web/core/core/sampler/grid-metrics.ts",
    reason: "Separator use in untyped sampling payloads; replaced by declared DTOs.",
  },
  {
    task: "#106",
    rules: ["R4-separator-literal"],
    file: "frontend/web/core/core/sampler/signatures.ts",
    reason: "Separator joins building cache signatures over untyped fields; replaced by declared DTOs.",
  },
  {
    task: "#106",
    rules: ["R4-separator-literal"],
    file: "frontend/web/core/core/sampler/snapshot/prepared-dom.ts",
    reason: "Separator use in untyped snapshot payloads; replaced by declared DTOs.",
  },
  {
    task: "#106",
    rules: ["R4-separator-literal"],
    file: "frontend/web/core/tests/font-face-boundaries.test.mjs",
    reason: "Separator fixture strings of the untyped payload; replaced by declared DTOs.",
  },
  {
    task: "#106",
    rules: ["R4-separator-literal"],
    file: "frontend/web/core/tests/lowered-paragraph-metadata.test.mjs",
    reason: "Separator fixture strings of the untyped payload; replaced by declared DTOs.",
  },
  {
    task: "#106",
    rules: ["R4-separator-literal"],
    file: "frontend/web/core/tests/replay-entry-codec.test.mjs",
    reason: "Separator fixture strings of the replay entry codec pending the typed-DTO wave.",
  },
  {
    task: "#106",
    rules: ["R4-separator-literal"],
    file: "frontend/web/core/tests/replay-probe.test.mjs",
    reason: "Separator fixture strings of the untyped replay probe; replaced by declared DTOs.",
  },
  {
    task: "#106",
    rules: ["R4-separator-literal"],
    file: "frontend/web/core/core/measurement/replay-probe.ts",
    reason: "Separator splits in the replay probe over untyped payloads; replaced by declared DTOs.",
  },
  {
    task: "#106",
    rules: ["R4-separator-literal"],
    file: "frontend/web/core/core/measurement/browser-font-replay.ts",
    reason: "Family separator joins building replay registry keys over the untyped callback payload (introduced with the callback lane in 纠偏 1); replaced by declared DTOs in corrective wave 5.",
  },

  {
    task: "#106",
    rules: ["R4-separator-literal"],
    file: "ffi/rust/tiqian/src/shape_buffer.rs",
    reason: "Feature list joined with a separator for the shaping callback payload; replaced by declared DTOs in corrective wave 5.",
  },
  {
    task: "#106",
    rules: ["R4-separator-literal"],
    file: "engine/src/nativeMain/kotlin/org/tiqian/shaping/NativeFontBackendShaper.kt",
    reason: "Feature string split in the shape buffer reader; part of the declared shape buffer protocol (header-defined format), not the family separator seam.",
  },
  {
    task: "#106",
    rules: ["R4-separator-literal"],
    file: "frontend/web-precompute/rust/tiqian-precompute/src/snapshot_tables.rs",
    reason: "Metric replay key restoration splits serialized families from snapshot table rows; part of the snapshot table codec.",
  },
  {
    task: "#106",
    rules: ["R4-separator-literal"],
    file: "frontend/web-precompute/rust/tiqian-precompute/src/font_record_cache.rs",
    reason: "Separator joins over untyped precompute cache keys; replaced by declared DTOs in corrective wave 5.",
  },
  {
    task: "#106",
    rules: ["R4-separator-literal"],
    file: "frontend/web-precompute/rust/tiqian-precompute/src/normalize.rs",
    reason: "Private record/field/family separator copies of the wire format; replaced by declared DTOs in corrective wave 5.",
  },

  {
    task: "#106",
    rules: ["R4-separator-literal"],
    file: "frontend/web-precompute/rust/tiqian-precompute/src/prepared_dom.rs",
    reason: "Family list joined with a separator over the untyped payload; replaced by declared DTOs in corrective wave 5.",
  },
  {
    task: "#106",
    rules: ["R4-separator-literal"],
    file: "frontend/web-precompute/rust/tiqian-precompute/src/source_boundaries.rs",
    reason: "Private record/field/family separator copies parsing the untyped request; replaced by declared DTOs in corrective wave 5.",
  },
  {
    task: "#106",
    rules: ["R4-separator-literal"],
    file: "platforms/compose/compose/src/commonMain/kotlin/org/tiqian/compose/CjkAnnotatedText.kt",
    reason: "Private ruby font separator copy of the wire format inside a renderer; replaced by declared DTOs in corrective wave 5.",
  },

  {
    task: "#106",
    rules: ["R4-separator-literal"],
    file: "frontend/web-precompute/rust/tiqian-precompute/src/json.rs",
    reason: "Separator escape literal in JSON serializer tests (string round-trip coverage); the serializer itself is a declared module.",
  },
  {
    task: "#106",
    rules: ["R4-separator-literal"],
    file: "frontend/web-precompute/rust/tiqian-precompute/src/replay.rs",
    reason: "Replay key format joins families with U+001F for backward-compatible cache keys; must stay to avoid re-keying existing caches.",
  },
];

// Top-level names ending in Wire/Face that were reviewed and found to use
// "face" as the standard typography term (font face), not as wire-transport
// naming. Anything not listed here fails rule R2 and must be judged by hand.
const REVIEWED_WIRE_FACE_NAMES = new Set([
  "fontRoleNameUsesLatinFace",
  "usesLatinFace",
]);

// Modules allowed to contain the four separator escape literals (rule R4):
// the current wire codec, and the parity oracle whose frozen-text byte
// comparison the ruling explicitly retains.
const SEPARATOR_CODEC_MODULES = [
  "ffi/js/src/jsMain/kotlin/org/tiqian/ffi/js/ParagraphWireCodec.kt",
  "frontend/web-precompute/scripts/plan-parity-oracle.mjs",
];

// Export entries already reviewed against an engine function counterpart
// (rule R3b). The comment states the engine side each entry forwards to.
const REVIEWED_EXPORT_ENTRIES = new Map([
  ["fontMetricsResolve", "org.tiqian.font.FontMetricsResolver.resolve via WireJson parse"],
  ["fontFallbackResolve", "engine font fallback policy query"],
  ["bopomofoParse", "org.tiqian.clreq.BopomofoReading parse"],
  ["numberSymbolCohesionUnbreakableRanges", "org.tiqian.clreq.NumberSymbolCohesion unbreakable ranges"],
  ["liangHyphenate", "engine English hyphenation patterns"],
  ["unicodePunctuationLineBreakClassOf", "org.tiqian.linebreak.UnicodePunctuationLineBreak class of code point"],
  ["classifyFontRole", "engine font role classification policy"],
  ["unsupportedInlineShapingProperties", "org.tiqian.font.InlineShapingStylePolicy unsupported properties"],
  ["firstDivergentInlineShapingProperty", "org.tiqian.font.InlineShapingStylePolicy divergence check"],
  ["tiqian_layout_paragraph", "packed TQLR layout request into the paragraph layout pipeline (declared, version-checked)"],
  ["tiqian_release_buffer", "native buffer release paired with tiqian_layout_paragraph"],
  [
    "tiqian_layout_paragraph_json",
    "debug-named dump entry returning the engine plan JSON for the parity oracle and golden only; production returns the packed contract (ADR 0053 disposal record, ADR 0050 amendment 2026-08-25)",
  ],
  ["tiqian_install_font_backend", "installed font-backend vtable per ADR 0050"],
  [
    "precomputeParagraphWithDiagnostics",
    "org.tiqian.ffi.js.ParagraphWireCodec.planWithDiagnostics pipeline; host callbacks enter as JsCallback shaper/resolver data conversion (post 纠偏 4/#105 review, session-id surface deleted)",
  ],
  [
    "precomputeParagraphWithBrowserMetrics",
    "org.tiqian.ffi.js.ParagraphWireCodec.planWithDiagnostics pipeline via BrowserMetricsCallbacks DTO; host callbacks enter as JsCallback shaper/resolver data conversion (corrective wave 5/#106)",
  ],
]);

// ---------------------------------------------------------------------------
// Rule implementations
// ---------------------------------------------------------------------------

const RULE_CITATIONS = {
  "R1-globalThis":
    "ADR 0053 ffi boundary review record (2026-08-25): the js lane must not depend on environment globals; corrective wave 1 (#102) deletes the family.",
  "R1-Tiqian-global":
    "ADR 0053 ffi boundary review record (2026-08-25): __Tiqian* globals are the environment-global family slated for deletion (#102).",
  "R2-filename":
    "ADR 0053 ruling on A3: wire format code does not belong in engine commonMain; corrective wave 2 (#103) moves the parsers back to ffi/js.",
  "R2-top-level-name":
    "ADR 0053 ruling on A3: wire format code does not belong in engine commonMain; corrective wave 2 (#103) moves the parsers back to ffi/js.",
  "R3-session-state":
    "ADR 0053: ffi exposes engine functions as-is and carries no business logic; font session state leaves ffi with corrective waves 1 and 4 (#102/#105).",
  "R3-export-entry-review":
    "ADR 0053: every ffi export must correspond to an engine function (data conversion allowed); review the entry against REVIEWED_EXPORT_ENTRIES or route it through a corrective wave.",
  "R4-separator-literal":
    "ADR 0053 cross-boundary payload audit: separators travel only inside declared codec modules until payloads become declared DTOs (corrective wave 5, #106).",
};

function exemptionCovers(exemption, violation) {
  if (!exemption.rules.includes(violation.rule)) return false;
  if (violation.symbol !== undefined) {
    return exemption.symbol === violation.symbol;
  }
  if (exemption.file !== violation.file) return false;
  return exemption.line === undefined || exemption.line === null || exemption.line === violation.line;
}

function makeViolation(rule, file, line, snippet) {
  return { rule, file, line, snippet };
}

// R1: globalThis and __Tiqian-prefixed identifiers anywhere in engine/.
function scanEngineGlobals(violations) {
  const globalThisPattern = /\bglobalThis\b/g;
  const tiqianGlobalPattern = /__Tiqian\w*/g;
  for (const file of collectFiles("engine", KOTLIN_EXTENSIONS)) {
    const relFile = path.relative(repoRoot, file);
    readLines(file).forEach((lineText, index) => {
      for (const [rule, pattern] of [
        ["R1-globalThis", globalThisPattern],
        ["R1-Tiqian-global", tiqianGlobalPattern],
      ]) {
        pattern.lastIndex = 0;
        if (pattern.test(lineText)) {
          violations.push(
            makeViolation(rule, relFile, index + 1, lineText.trim().slice(0, 160)),
          );
        }
      }
    });
  }
}

const TOP_LEVEL_NAME_PATTERN =
  /^(?:public|private|internal|protected|open|final|abstract|sealed|data|value|inline|suspend|operator|const|expect|actual|lateinit|external|enum|annotation)*\s*(?:class|interface|object|fun|val|var|typealias)\b/;
const DECLARATION_KEYWORD_PATTERN =
  /\b(?:class|interface|object|fun|val|var|typealias)\b\s*(?:<[^>]*>\s*)?(`[^`]+`|[A-Za-z_][A-Za-z0-9_]*)/;

function topLevelDeclarationName(lineText) {
  // Top-level only: declarations start at column 0. Indented lines are
  // members, locals, or continuation lines.
  if (/^\s/.test(lineText)) return null;
  if (!TOP_LEVEL_NAME_PATTERN.test(lineText)) return null;
  const match = DECLARATION_KEYWORD_PATTERN.exec(lineText);
  return match ? match[1].replace(/`/g, "") : null;
}

// R2: file names and top-level declaration names in engine commonMain.
function scanCommonMainNaming(violations) {
  const commonMainRoot = "engine/src/commonMain";
  for (const file of collectFiles(commonMainRoot, KOTLIN_EXTENSIONS)) {
    const relFile = path.relative(repoRoot, file);
    const baseName = path.basename(file, ".kt");
    if (/(?:Wire|Face)$/.test(baseName)) {
      violations.push(
        makeViolation("R2-filename", relFile, null, `file name "${baseName}.kt" ends in Wire/Face`),
      );
    }
    if (/precompute/i.test(baseName)) {
      violations.push(
        makeViolation("R2-filename", relFile, null, `file name "${baseName}.kt" contains precompute vocabulary`),
      );
    }
    for (const [index, lineText] of readLines(file).entries()) {
      const trimmed = lineText.trimStart();
      if (
        trimmed.length === 0 ||
        trimmed.startsWith("*") ||
        trimmed.startsWith("/") ||
        trimmed.startsWith("@")
      ) {
        continue;
      }
      const name = topLevelDeclarationName(lineText);
      if (name === null) continue;
      if (/(?:Wire|Face)$/.test(name)) {
        if (REVIEWED_WIRE_FACE_NAMES.has(name)) continue;
        violations.push(
          makeViolation(
            "R2-top-level-name",
            relFile,
            index + 1,
            `top-level symbol "${name}" ends in Wire/Face`,
          ),
        );
      }
      if (/precompute/i.test(name)) {
        violations.push(
          makeViolation(
            "R2-top-level-name",
            relFile,
            index + 1,
            `top-level symbol "${name}" contains precompute vocabulary`,
          ),
        );
      }
    }
  }
}

const SESSION_STATE_TOKENS = /\b(?:HarfBuzzSession|buildPrecomputeBackends|fontSessionId)\b/;
const NON_JS_LANE_SESSION_TOKENS = /\b(?:HarfBuzzSession|buildPrecomputeBackends)\b/;

function isFfiJsLane(relFile) {
  const segments = relFile.split(path.sep);
  return (
    segments[0] === "ffi" &&
    segments[1] === "js" &&
    (segments.includes("jsMain") || segments.includes("jsTest"))
  );
}

// R3a: font-session state markers inside ffi/.
function scanFfiSessionState(violations) {
  for (const file of collectFiles("ffi", KOTLIN_EXTENSIONS)) {
    const relFile = path.relative(repoRoot, file);
    const jsLaneOnlyLine = isFfiJsLane(relFile);
    for (const [index, lineText] of readLines(file).entries()) {
      if (!SESSION_STATE_TOKENS.test(lineText)) continue;
      if (!jsLaneOnlyLine && !NON_JS_LANE_SESSION_TOKENS.test(lineText)) {
        continue;
      }
      violations.push(
        makeViolation(
          "R3-session-state",
          relFile,
          index + 1,
          lineText.trim().slice(0, 160),
        ),
      );
    }
  }
}

// R3b: exported entries must be reviewed against engine counterparts.
const FFI_ROOTS = [["ffi", KOTLIN_EXTENSIONS]];

function extractExportEntries() {
  const entries = [];
  for (const [root, extensions] of FFI_ROOTS) {
    for (const file of collectFiles(root, extensions)) {
      const relFile = path.relative(repoRoot, file);
      const lines = readLines(file);
      for (const [index, lineText] of lines.entries()) {
        const trimmed = lineText.trim();
        const cName = /^@CName\("([^"]+)"\)/.exec(trimmed);
        if (cName !== null) {
          entries.push({ name: cName[1], file: relFile, line: index + 1 });
          continue;
        }
        if (trimmed !== "@JsExport") continue;
        for (let peek = index + 1; peek < lines.length; peek += 1) {
          const candidate = lines[peek].trim();
          if (candidate.length === 0 || candidate.startsWith("@")) {
            if (candidate.startsWith("@")) continue;
            break;
          }
          const funMatch = /^(?:internal\s+)?fun\s+(?:<[^>]*>\s*)?(`[^`]+`|[A-Za-z_][A-Za-z0-9_]*)/.exec(candidate);
          if (funMatch !== null) {
            entries.push({
              name: funMatch[1].replace(/`/g, ""),
              file: relFile,
              line: peek + 1,
            });
          }
          break;
        }
      }
    }
  }
  return entries;
}

function checkExportEntries(violations) {
  for (const entry of extractExportEntries()) {
    if (REVIEWED_EXPORT_ENTRIES.has(entry.name)) continue;
    violations.push({
      rule: "R3-export-entry-review",
      file: entry.file,
      line: entry.line,
      symbol: entry.name,
      snippet: `exported entry "${entry.name}" has no reviewed engine counterpart`,
    });
  }
}

// R4: separator escape literals outside the declared codec modules.
const SEPARATOR_PATTERNS = [
  /\\u001[a-fA-F]/,
  /\\x1[a-fA-F]/,
  /\\u\{0*1[a-fA-F]\}/,
];

function scanSeparatorLiterals(violations) {
  for (const root of SCANNED_ROOTS) {
    for (const file of collectFiles(root, SOURCE_EXTENSIONS)) {
      const relFile = path.relative(repoRoot, file);
      if (SEPARATOR_CODEC_MODULES.includes(relFile)) continue;
      for (const [index, lineText] of readLines(file).entries()) {
        if (SEPARATOR_PATTERNS.some((pattern) => pattern.test(lineText))) {
          violations.push(
            makeViolation(
              "R4-separator-literal",
              relFile,
              index + 1,
              lineText.trim().slice(0, 160),
            ),
          );
        }
      }
    }
  }
}

// ---------------------------------------------------------------------------
// Driver
// ---------------------------------------------------------------------------

function main() {
  const violations = [];
  scanEngineGlobals(violations);
  scanCommonMainNaming(violations);
  scanFfiSessionState(violations);
  checkExportEntries(violations);
  scanSeparatorLiterals(violations);

  const matchedExemptions = new Set();
  const open = [];
  for (const violation of violations) {
    const exemption = EXEMPTIONS.find((candidate) =>
      exemptionCovers(candidate, violation),
    );
    if (exemption === undefined) open.push(violation);
    else matchedExemptions.add(exemption);
  }

  for (const violation of open) {
    const where =
      violation.line === null
        ? violation.file
        : `${violation.file}:${violation.line}`;
    console.error(
      `[boundary] ${violation.rule} ${where}: ${violation.snippet}\n` +
        `  ${RULE_CITATIONS[violation.rule]}`,
    );
  }

  for (const exemption of EXEMPTIONS) {
    if (!matchedExemptions.has(exemption)) {
      console.error(
        `[boundary] STALE EXEMPTION (${exemption.task}): ` +
          `${exemption.symbol ?? exemption.file}${exemption.line ? `:${exemption.line}` : ""} matched no current hit.\n` +
          `  Delete this entry from tools/boundary-check/check.mjs.`,
      );
    }
  }

  if (open.length > 0) {
    console.error(
      `boundary check FAILED: ${open.length} violation(s) not covered by the ` +
        `exemption list (see docs/adr/0053-web-prose-host-consolidation.md, ` +
        `ffi boundary review record 2026-08-25)`,
    );
    process.exit(1);
  }
  console.log(
    `boundary check OK: engine/ffi/frontend conform to the ADR 0053 ffi ` +
      `boundary rulings (${violations.length} hit(s), all covered by ` +
      `${EXEMPTIONS.length} explicit exemption(s), ${REVIEWED_EXPORT_ENTRIES.size} reviewed export entries)`,
  );
}

main();
