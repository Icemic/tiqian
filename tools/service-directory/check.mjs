#!/usr/bin/env node
// Mechanical singleton gate for ADR 0053 `ServiceDirectoryRule`
// (docs/adr/0053-web-prose-host-consolidation.md, "全页构造一次的运行时单例
// 集中放在 core/services/ 目录"). The 2026-08-25 S-wave audit found adjudicated
// registry moves that were never executed and singletons that escaped every
// census because no gate made them visible. This script keeps every
// module-scope mutable value outside the sanctioned homes mechanically
// visible in CI: each occurrence must live in core/services/, in the worker
// world, or carry an exemption entry naming the disposition that will delete
// both the code and the entry.
//
// Rules:
//   S1  Module-scope mutable state in frontend/web runtime sources: top-level
//       `let`, top-level `const ... = new Map/WeakMap`, top-level closure
//       singletons (`const x[: T] = createX()`), and globalThis registry
//       writes. Allowed without exemption only in:
//         - frontend/web/core/core/services/ (the ServiceDirectoryRule home)
//         - frontend/web/core/core/engine/layout-worker.ts (worker world:
//           runs in its own JS global scope, not the document's)
//   S2  Every exemption entry lists the exact declaration names it covers.
//       A name that disappears without its exemption entry being deleted
//       fails the gate as a stale exemption, so waves that sweep a file
//       must shrink the table in the same commit.
//
// Exemption reasons use two fixed classes:
//   pure-memo   Stateless derivation cache or stateless utility instance
//               (keyed by its own source object or created once with no
//               lifecycle). Permanent unless the ruling changes.
//   pending     Adjudicated move that has not landed; the entry names the
//               owning task and the destination.

import { readdirSync, readFileSync, statSync } from "node:fs";
import { join, relative } from "node:path";

const REPO_ROOT = join(import.meta.dirname, "..", "..");
const SCAN_ROOTS = ["frontend/web/core/core", "frontend/web/npm"];
const SANCTIONED_PREFIXES = [
  "frontend/web/core/core/services/",
  "frontend/web/core/core/engine/layout-worker.ts",
];

// file (repo-relative) -> { names: string[], reason: string, task?: string }
const EXEMPTIONS = new Map([
  ["frontend/web/core/core/sampler/snapshot/snapshot-tables.ts", {
    names: ["loadedTables", "resolvedTables"],
    reason: "pending",
    task: "#97 S5: snapshot table registries -> container decision",
  }],
  ["frontend/web/core/core/sampler/snapshot/snapshot-manifest.ts", {
    names: ["replayMetricsByView"],
    reason: "pure-memo: derivation cache keyed by the immutable table view",
  }],
  ["frontend/web/core/core/sampler/snapshot/prepared-dom.ts", {
    names: ["preparedStyleStates", "preparedStyleRootsByHost", "preparedScopeCounters"],
    reason: "pending",
    task: "s5-ctx lane (adjudicated sweep: per-root -> context, per-document -> globalServices)",
  }],
  ["frontend/web/core/core/sampler/snapshot/precomputed.ts", {
    names: ["snapshotFontReplayProofs", "states", "directServerArtifacts", "unicodeRangeCache"],
    reason: "pending",
    task: "#97 S5: adoption registries -> context/container; unicodeRangeCache pure-memo stays",
  }],
  ["frontend/web/core/core/engine/loaders/styles.ts", {
    names: ["stylesheetPromise", "stylesheetElement"],
    reason: "pending",
    task: "#97 S5: per-document style handles -> container",
  }],
  ["frontend/web/core/core/engine/coordination/viewport-anchor.ts", {
    names: ["gestureTrackerInstalled", "lastGestureAt", "heldOwnerByRoot", "ownerHolds"],
    reason: "pending",
    task: "#97 S5: viewport anchor state -> container/context decision",
  }],
  ["frontend/web/core/core/engine/context/enhance-context.ts", {
    names: ["elementContexts"],
    reason: "pending",
    task: "s5-ctx lane: caller-held contexts shrink this to the paragraph-slot registry",
  }],
  ["frontend/web/core/core/engine/markdown-lowering.ts", {
    names: ["graphemeSegmenter"],
    reason: "pure-memo: one stateless Intl.Segmenter instance, no lifecycle",
  }],
  ["frontend/web/core/core/engine/web-worker/worker-channel.ts", {
    names: ["coordinator"],
    reason: "pending",
    task: "#97 s5-bc: Kotlin-era page bridge registry -> globalServices",
  }],
  ["frontend/web/core/core/engine/loaders/runtime-loader.ts", {
    names: ["loaderState"],
    reason: "pending",
    task: "#97 s5-bc: dissolve into globalServices with install-time test seams",
  }],
]);

const DETECTORS = [
  {
    kind: "let",
    pattern: /^let\s+([A-Za-z_$][\w$]*)/,
  },
  {
    kind: "collection",
    pattern: /^const\s+([A-Za-z_$][\w$]*)(?:\s*:\s*[^=]+)?\s*=\s*new\s+(?:Weak)?Map\s*[<(]/,
  },
  {
    kind: "closure-singleton",
    pattern: /^const\s+([A-Za-z_$][\w$]*)(?:\s*:\s*[^=]+)?\s*=\s*create[A-Z][\w$]*\s*\(/,
  },
  {
    kind: "globalThis-write",
    pattern: (line) =>
      line.includes("globalThis") &&
      (/\]\s*\?\?=/.test(line) || /\]\s*=(?!=)/.test(line) ||
       /globalThis\s*\.\s*[\w$]+\s*=(?!=)/.test(line)),
    name: (line) => {
      const decl = line.match(/^const\s+([A-Za-z_$][\w$]*)/);
      if (decl) return decl[1];
      const member = line.match(/(?:\]|\.)\s*([A-Za-z_$][\w$]*)\s*(?:\?\?=|=(?!=))/);
      return member ? member[1] : "globalThis";
    },
  },
];

/** @returns {string[]} */
function walk(dir) {
  const out = [];
  for (const entry of readdirSync(dir)) {
    const path = join(dir, entry);
    const st = statSync(path);
    if (st.isDirectory()) out.push(...walk(path));
    else if (entry.endsWith(".ts") && !entry.endsWith(".d.ts") && !entry.includes(".test.")) {
      out.push(path);
    }
  }
  return out;
}

const violations = [];
const matchedNames = new Map([...EXEMPTIONS].map(([file, entry]) => [file, new Set(entry.names)]));

for (const root of SCAN_ROOTS) {
  const files = walk(join(REPO_ROOT, root));
  for (const file of files) {
    const rel = relative(REPO_ROOT, file);
    const sanctioned = SANCTIONED_PREFIXES.some(
      (prefix) => rel === prefix || rel.startsWith(prefix),
    );
    if (sanctioned) continue;
    const lines = readFileSync(file, "utf8").split("\n");
    for (let i = 0; i < lines.length; i += 1) {
      const line = lines[i];
      if (!line || line.startsWith("//") || line.startsWith("*") || line.startsWith("/*")) continue;
      for (const detector of DETECTORS) {
        let name = null;
        if (detector.pattern instanceof RegExp) {
          const m = line.match(detector.pattern);
          if (m) name = m[1];
        } else if (detector.pattern(line)) {
          name = detector.name(line);
        }
        if (name === null) continue;
        const entry = EXEMPTIONS.get(rel);
        if (entry && entry.names.includes(name)) {
          matchedNames.get(rel)?.add(`seen:${name}`);
          continue;
        }
        violations.push(`${rel}:${i + 1} [${detector.kind}] ${name}`);
      }
    }
  }
}

const stale = [];
for (const [file, entry] of EXEMPTIONS) {
  for (const name of entry.names) {
    if (!matchedNames.get(file)?.has(`seen:${name}`)) {
      stale.push(`${file}: exemption name "${name}" no longer matches (delete it with the wave)`);
    }
  }
}

if (violations.length > 0 || stale.length > 0) {
  console.error("service-directory gate: FAIL");
  for (const v of violations) console.error(`  unexempted: ${v}`);
  for (const s of stale) console.error(`  stale: ${s}`);
  process.exit(1);
}

console.log(
  `service-directory gate: PASS (${EXEMPTIONS.size} exempted files, ` +
    `${[...EXEMPTIONS.values()].reduce((n, e) => n + e.names.length, 0)} exempted names)`,
);
