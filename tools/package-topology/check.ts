#!/usr/bin/env node
// Package topology guard for the three Tiqian npm packages.
//
// Implements the topology half of ADR 0053 Verification 8: "the three-package
// topology has a CI check; inter-package dependency direction is
// web-component -> core -> ffi; any dependency outside that direction and any
// cross-package relative import makes CI fail." (The eslint/grep half lives in
// ci-ts-discipline.yml.)
//
// Packages and the only allowed dependency edges:
//   @tiqian/prose      (frontend/web/npm)       web-component layer
//   @tiqian/core (frontend/web/core)  core layer
//   @tiqian/ffi        (ffi/js/npm)             ffi layer
//   @tiqian/prose      -> @tiqian/core
//   @tiqian/prose      -> @tiqian/ffi
//   @tiqian/core -> @tiqian/ffi
//
// Check 1: every @tiqian/* entry in dependencies, devDependencies,
// peerDependencies and optionalDependencies of the three package.json files
// must be one of the edges above. Any other @tiqian/* edge (reverse edge,
// unknown target, self-dependency) is a violation.
//
// Check 2: import/export/require module specifiers in published sources must
// not cross a package boundary. A relative specifier whose resolved target
// leaves its own package root and lands inside another topology package is a
// violation; packages must reference each other through declared package
// names, never repo-relative paths.
//
// The scanned source set mirrors tools/ts-discipline/eslint.config.ts so both
// checks share one scope definition: **/*.js, **/*.mjs, **/*.ts and **/*.d.ts
// under the
// three package directories, ignoring node_modules/, runtime/, build/,
// .gradle/, .b2-tmp/, target/ and demo/, plus the task-level exclusions
// *.test.js and lock files (lock files are never matched anyway because only
// js/mjs/d.ts files are read).
//
// Relative escapes that leave the three-package topology entirely (for example
// a bench harness reaching into frontend/web-precompute) are printed as
// non-fatal notes so they stay visible without failing the check; set
// TIQIAN_TOPOLOGY_STRICT=1 to escalate them to violations once such imports
// are cleaned up.
//
// Zero npm dependencies; node >= 22 builtins only. Exit codes: 0 = clean,
// 1 = violation or unreadable input.

import { existsSync, readdirSync, readFileSync } from "node:fs";
import type { Dirent } from "node:fs";
import path from "node:path";
import process from "node:process";

function findRepoRoot(): string {
  let current: string = process.cwd();
  while (current !== path.dirname(current)) {
    if (existsSync(path.join(current, ".git"))) {
      return current;
    }
    current = path.dirname(current);
  }
  return process.cwd();
}

const repoRoot: string = findRepoRoot();

interface Package {
  readonly name: string;
  readonly dir: string;
}

interface SpecifierHit {
  readonly line: number;
  readonly column: number;
  readonly specifier: string;
}

const PACKAGES: readonly Package[] = [
  { name: "@tiqian/prose", dir: "frontend/web/npm" },
  { name: "@tiqian/core", dir: "frontend/web/core" },
  { name: "@tiqian/ffi", dir: "ffi/js/npm" },
];

const ALLOWED_EDGES: ReadonlySet<string> = new Set([
  "@tiqian/prose -> @tiqian/core",
  "@tiqian/prose -> @tiqian/ffi",
  "@tiqian/core -> @tiqian/ffi",
]);

const ALLOWED_DIRECTIONS: string =
  "Allowed inter-package edges (web-component -> core -> ffi): " +
  [...ALLOWED_EDGES].join("; ") +
  ".";

const DEPENDENCY_FIELDS: readonly string[] = [
  "dependencies",
  "devDependencies",
  "peerDependencies",
  "optionalDependencies",
];

// Directory names never scanned, mirroring the ignores in
// tools/ts-discipline/eslint.config.ts restricted to what can occur inside
// the three package directories.
const IGNORED_DIR_NAMES: ReadonlySet<string> = new Set([
  "node_modules",
  "runtime",
  "build",
  ".gradle",
  ".b2-tmp",
  "target",
  "demo",
]);

const STRICT_OUTSIDE_ESCAPES: boolean = process.env.TIQIAN_TOPOLOGY_STRICT === "1";

function isScannedSourceFile(fileName: string): boolean {
  if (fileName.endsWith(".test.js")) return false;
  return (
    fileName.endsWith(".d.ts") ||
    fileName.endsWith(".js") ||
    fileName.endsWith(".mjs") ||
    fileName.endsWith(".ts")
  );
}

function collectSourceFiles(packageRoot: string): string[] {
  const files: string[] = [];
  const visit = (dir: string): void => {
    const entries: Dirent[] = readdirSync(dir, { withFileTypes: true }).sort(
      (a: Dirent, b: Dirent): number => a.name.localeCompare(b.name),
    );
    for (const entry of entries) {
      const fullPath: string = path.join(dir, entry.name);
      if (entry.isDirectory()) {
        if (!IGNORED_DIR_NAMES.has(entry.name)) visit(fullPath);
      } else if (entry.isFile() && isScannedSourceFile(entry.name)) {
        files.push(fullPath);
      }
    }
  };
  visit(packageRoot);
  return files;
}

// Module specifier extraction. Line-based regexes instead of a parser because
// the script must stay dependency-free; the patterns cover static imports,
// side-effect imports, dynamic import(), re-exports and require().
const SPECIFIER_PATTERNS: readonly RegExp[] = [
  /\bfrom\s*(["'])([^"'\n]+)\1/g,
  /\bimport\s*\(?\s*(["'])([^"'\n]+)\1/g,
  /\brequire\s*\(\s*(["'])([^"'\n]+)\1/g,
];

function extractRelativeSpecifiers(text: string): SpecifierHit[] {
  const hits: SpecifierHit[] = [];
  const lines: readonly string[] = text.split("\n");
  for (const [lineIndex, line] of lines.entries()) {
    const seenOnLine: Set<string> = new Set<string>();
    for (const pattern of SPECIFIER_PATTERNS) {
      pattern.lastIndex = 0;
      let match: RegExpExecArray | null;
      while ((match = pattern.exec(line)) !== null) {
        const specifier: string = match[2];
        if (!specifier.startsWith(".")) continue;
        // Patterns overlap (import ... from "..."), dedupe per line/column.
        const key: string = `${match.index}:${specifier}`;
        if (seenOnLine.has(key)) continue;
        seenOnLine.add(key);
        hits.push({ line: lineIndex + 1, column: match.index + 1, specifier });
      }
    }
  }
  return hits;
}

function isInside(parent: string, candidate: string): boolean {
  const rel: string = path.relative(parent, candidate);
  return rel !== "" && !rel.startsWith("..") && !path.isAbsolute(rel);
}

function owningTopologyPackage(resolvedTarget: string): Package | null {
  for (const pkg of PACKAGES) {
    if (isInside(path.join(repoRoot, pkg.dir), resolvedTarget)) return pkg;
  }
  return null;
}

function checkDependencyDeclarations(errors: string[]): void {
  for (const pkg of PACKAGES) {
    const manifestPath: string = path.join(repoRoot, pkg.dir, "package.json");
    let manifest: Record<string, unknown>;
    try {
      manifest = JSON.parse(readFileSync(manifestPath, "utf8")) as Record<string, unknown>;
    } catch (error: unknown) {
      const message: string = error instanceof Error ? error.message : String(error);
      errors.push(
        `[manifest] ${pkg.dir}/package.json cannot be read or parsed: ${message}`,
      );
      continue;
    }
    for (const field of DEPENDENCY_FIELDS) {
      const deps: unknown = manifest[field];
      if (deps === undefined || deps === null) continue;
      if (typeof deps !== "object" || Array.isArray(deps)) {
        errors.push(
          `[manifest] ${pkg.dir}/package.json: "${field}" is not an object.`,
        );
        continue;
      }
      const depsRecord: Record<string, unknown> = deps as Record<string, unknown>;
      for (const depName of Object.keys(depsRecord)) {
        if (!depName.startsWith("@tiqian/")) continue;
        const edge: string = `${pkg.name} -> ${depName}`;
        if (ALLOWED_EDGES.has(edge)) continue;
        errors.push(
          `[dependency direction] ${pkg.dir}/package.json: ${pkg.name} declares ` +
            `"${depName}" in "${field}".\n` +
            `  Violating edge: ${edge}.\n  ${ALLOWED_DIRECTIONS}`,
        );
      }
    }
  }
}

function checkCrossPackageImports(errors: string[], notes: string[]): number {
  let scannedFiles: number = 0;
  for (const pkg of PACKAGES) {
    const packageRoot: string = path.join(repoRoot, pkg.dir);
    for (const file of collectSourceFiles(packageRoot)) {
      scannedFiles += 1;
      const displayPath: string = path.relative(repoRoot, file);
      let text: string;
      try {
        text = readFileSync(file, "utf8");
      } catch (error: unknown) {
        const message: string = error instanceof Error ? error.message : String(error);
        errors.push(`[read] ${displayPath}: ${message}`);
        continue;
      }
      for (const hit of extractRelativeSpecifiers(text)) {
        const resolvedTarget: string = path.resolve(
          path.dirname(file),
          hit.specifier,
        );
        const escapesOwnPackage: boolean = !isInside(packageRoot, resolvedTarget);
        if (!escapesOwnPackage) continue;
        const owner: Package | null = owningTopologyPackage(resolvedTarget);
        if (owner !== null) {
          errors.push(
            `[cross-package import] ${displayPath}:${hit.line}:${hit.column}: ` +
              `"${hit.specifier}" resolves to ${path.relative(repoRoot, resolvedTarget)}, ` +
              `which belongs to ${owner.name}.\n` +
              `  ${pkg.name} must reach ${owner.name} through a package specifier ` +
              `(declared dependency, e.g. "${owner.name}/..."), not a relative path ` +
              `across package boundaries.\n  ${ALLOWED_DIRECTIONS}`,
          );
        } else {
          const verdict: string = STRICT_OUTSIDE_ESCAPES ? "violation" : "note";
          const message: string =
            `[outside-topology escape: ${verdict}] ${displayPath}:${hit.line}:${hit.column}: ` +
            `"${hit.specifier}" resolves to ${path.relative(repoRoot, resolvedTarget)}, ` +
            `which is outside all three topology packages.` +
            (STRICT_OUTSIDE_ESCAPES
              ? ""
              : " Not a Verification 8 failure today; set TIQIAN_TOPOLOGY_STRICT=1 to escalate.");
          if (STRICT_OUTSIDE_ESCAPES) errors.push(message);
          else notes.push(message);
        }
      }
    }
  }
  return scannedFiles;
}

function main(): void {
  const errors: string[] = [];
  const notes: string[] = [];
  checkDependencyDeclarations(errors);
  const scannedFiles: number = checkCrossPackageImports(errors, notes);

  for (const note of notes) console.error(note);
  if (errors.length > 0) {
    for (const error of errors) console.error(error);
    console.error(
      `package topology check FAILED: ${errors.length} violation(s), ` +
        `${scannedFiles} source files scanned`,
    );
    process.exit(1);
  }
  console.log(
    `package topology OK: dependency direction and imports conform to ` +
      `ADR 0053 Verification 8 (${scannedFiles} source files scanned, ` +
      `${notes.length} note(s))`,
  );
}

main();
