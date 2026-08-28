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

import { existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import type { Stats } from "node:fs";
import { join, relative } from "node:path";
import path from "node:path";
import process from "node:process";

function findRepoRoot(): string {
  let current: string = process.cwd();
  while (current !== path.dirname(current)) {
    if (existsSync(join(current, ".git"))) {
      return current;
    }
    current = path.dirname(current);
  }
  return process.cwd();
}

const REPO_ROOT: string = findRepoRoot();
const SCAN_ROOTS: readonly string[] = ["frontend/web/core/core", "frontend/web/npm"];
const SANCTIONED_PREFIXES: readonly string[] = [
  "frontend/web/core/core/services/",
  "frontend/web/core/core/engine/layout-worker.ts",
];

interface Exemption {
  readonly names: readonly string[];
  readonly reason: string;
  readonly task?: string;
}

// file (repo-relative) -> { names: string[], reason: string, task?: string }
const EXEMPTIONS: ReadonlyMap<string, Exemption> = new Map([
  ["frontend/web/core/core/sampler/snapshot/snapshot-manifest.ts", {
    names: ["replayMetricsByView"],
    reason: "pure-memo: WeakMap keyed by immutable table view; input-deterministic, no teardown, derivation cache",
  }],
  ["frontend/web/core/core/sampler/snapshot/precomputed.ts", {
    names: ["unicodeRangeCache"],
    reason: "pure-memo: bounded Map cache of parsed CSS unicode-range descriptors; input-deterministic, no lifecycle",
  }],
  ["frontend/web/core/core/engine/markdown-lowering.ts", {
    names: ["graphemeSegmenter"],
    reason: "pure-memo: one stateless Intl.Segmenter instance; input-deterministic, no teardown",
  }],
]);

interface Detector {
  readonly kind: string;
  readonly pattern: RegExp | ((line: string) => boolean);
  readonly name?: (line: string) => string;
}

const DETECTORS: readonly Detector[] = [
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
    pattern: (line: string): boolean =>
      line.includes("globalThis") &&
      (/\]\s*\?\?=/.test(line) || /\]\s*=(?!=)/.test(line) ||
       /globalThis\s*\.\s*[\w$]+\s*=(?!=)/.test(line)),
    name: (line: string): string => {
      const decl: RegExpMatchArray | null = line.match(/^const\s+([A-Za-z_$][\w$]*)/);
      if (decl) return decl[1];
      const member: RegExpMatchArray | null = line.match(/(?:\]|\.)\s*([A-Za-z_$][\w$]*)\s*(?:\?\?=|=(?!=))/);
      return member ? member[1] : "globalThis";
    },
  },
];

function walk(dir: string): string[] {
  const out: string[] = [];
  if (!existsSync(dir)) return out;
  for (const entry of readdirSync(dir)) {
    const filePath: string = join(dir, entry);
    const st: Stats = statSync(filePath);
    if (st.isDirectory()) {
      // ServiceDirectoryRule governs runtime sources; test batteries and their
      // support files sit in tests/ directories outside the rule's scope.
      if (entry === "tests") continue;
      out.push(...walk(filePath));
    } else if (entry.endsWith(".ts") && !entry.endsWith(".d.ts") && !entry.includes(".test.")) {
      out.push(filePath);
    }
  }
  return out;
}

function main(): void {
  const violations: string[] = [];
  const matchedNames: Map<string, Set<string>> = new Map(
    [...EXEMPTIONS].map(([file, entry]: [string, Exemption]): [string, Set<string>] => [
      file,
      new Set<string>(entry.names),
    ]),
  );

  for (const root of SCAN_ROOTS) {
    const files: string[] = walk(join(REPO_ROOT, root));
    for (const file of files) {
      const rel: string = relative(REPO_ROOT, file);
      const sanctioned: boolean = SANCTIONED_PREFIXES.some(
        (prefix: string): boolean => rel === prefix || rel.startsWith(prefix),
      );
      if (sanctioned) continue;
      const lines: string[] = readFileSync(file, "utf8").split("\n");
      for (let i: number = 0; i < lines.length; i += 1) {
        const line: string = lines[i];
        if (!line || line.startsWith("//") || line.startsWith("*") || line.startsWith("/*")) continue;
        for (const detector of DETECTORS) {
          let name: string | null = null;
          if (detector.pattern instanceof RegExp) {
            const m: RegExpMatchArray | null = line.match(detector.pattern);
            if (m) name = m[1];
          } else if (detector.pattern(line)) {
            name = detector.name ? detector.name(line) : null;
          }
          if (name === null) continue;
          const entry: Exemption | undefined = EXEMPTIONS.get(rel);
          if (entry && entry.names.includes(name)) {
            matchedNames.get(rel)?.add(`seen:${name}`);
            continue;
          }
          violations.push(`${rel}:${i + 1} [${detector.kind}] ${name}`);
        }
      }
    }
  }

  const stale: string[] = [];
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
      `${[...EXEMPTIONS.values()].reduce((n: number, e: Exemption): number => n + e.names.length, 0)} exempted names)`,
  );
}

main();
