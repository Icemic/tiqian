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
//   @tiqian/core (frontend/web/npm-core)  core layer
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
// The scanned source set mirrors tools/ts-discipline/eslint.config.mjs so both
// checks share one scope definition: **/*.js, **/*.mjs, **/*.ts and **/*.d.ts
// under the
// three package directories, ignoring node_modules/, runtime/, build/,
// .gradle/, .b2-tmp/, target/ and demo/, plus the task-level exclusions
// *.test.mjs and lock files (lock files are never matched anyway because only
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

import { readdirSync, readFileSync } from "node:fs";
import path from "node:path";
import process from "node:process";
import { fileURLToPath } from "node:url";

const repoRoot = fileURLToPath(new URL("../../", import.meta.url));

const PACKAGES = [
  { name: "@tiqian/prose", dir: "frontend/web/npm" },
  { name: "@tiqian/core", dir: "frontend/web/npm-core" },
  { name: "@tiqian/ffi", dir: "ffi/js/npm" },
];

const ALLOWED_EDGES = new Set([
  "@tiqian/prose -> @tiqian/core",
  "@tiqian/prose -> @tiqian/ffi",
  "@tiqian/core -> @tiqian/ffi",
]);

const ALLOWED_DIRECTIONS =
  "Allowed inter-package edges (web-component -> core -> ffi): " +
  [...ALLOWED_EDGES].join("; ") +
  ".";

const DEPENDENCY_FIELDS = [
  "dependencies",
  "devDependencies",
  "peerDependencies",
  "optionalDependencies",
];

// Directory names never scanned, mirroring the ignores in
// tools/ts-discipline/eslint.config.mjs restricted to what can occur inside
// the three package directories.
const IGNORED_DIR_NAMES = new Set([
  "node_modules",
  "runtime",
  "build",
  ".gradle",
  ".b2-tmp",
  "target",
  "demo",
]);

const STRICT_OUTSIDE_ESCAPES = process.env.TIQIAN_TOPOLOGY_STRICT === "1";

function isScannedSourceFile(fileName) {
  if (fileName.endsWith(".test.mjs")) return false;
  return (
    fileName.endsWith(".d.ts") ||
    fileName.endsWith(".js") ||
    fileName.endsWith(".mjs") ||
    fileName.endsWith(".ts")
  );
}

function collectSourceFiles(packageRoot) {
  const files = [];
  const visit = (dir) => {
    const entries = readdirSync(dir, { withFileTypes: true }).sort((a, b) =>
      a.name.localeCompare(b.name),
    );
    for (const entry of entries) {
      const fullPath = path.join(dir, entry.name);
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
const SPECIFIER_PATTERNS = [
  /\bfrom\s*(["'])([^"'\n]+)\1/g,
  /\bimport\s*\(?\s*(["'])([^"'\n]+)\1/g,
  /\brequire\s*\(\s*(["'])([^"'\n]+)\1/g,
];

function extractRelativeSpecifiers(text) {
  const hits = [];
  const lines = text.split("\n");
  for (const [lineIndex, line] of lines.entries()) {
    const seenOnLine = new Set();
    for (const pattern of SPECIFIER_PATTERNS) {
      pattern.lastIndex = 0;
      let match;
      while ((match = pattern.exec(line)) !== null) {
        const specifier = match[2];
        if (!specifier.startsWith(".")) continue;
        // Patterns overlap (import ... from "..."), dedupe per line/column.
        const key = `${match.index}:${specifier}`;
        if (seenOnLine.has(key)) continue;
        seenOnLine.add(key);
        hits.push({ line: lineIndex + 1, column: match.index + 1, specifier });
      }
    }
  }
  return hits;
}

function isInside(parent, candidate) {
  const rel = path.relative(parent, candidate);
  return rel !== "" && !rel.startsWith("..") && !path.isAbsolute(rel);
}

function owningTopologyPackage(resolvedTarget) {
  for (const pkg of PACKAGES) {
    if (isInside(path.join(repoRoot, pkg.dir), resolvedTarget)) return pkg;
  }
  return null;
}

function checkDependencyDeclarations(errors) {
  for (const pkg of PACKAGES) {
    const manifestPath = path.join(repoRoot, pkg.dir, "package.json");
    let manifest;
    try {
      manifest = JSON.parse(readFileSync(manifestPath, "utf8"));
    } catch (error) {
      errors.push(
        `[manifest] ${pkg.dir}/package.json cannot be read or parsed: ${error.message}`,
      );
      continue;
    }
    for (const field of DEPENDENCY_FIELDS) {
      const deps = manifest[field];
      if (deps === undefined || deps === null) continue;
      if (typeof deps !== "object" || Array.isArray(deps)) {
        errors.push(
          `[manifest] ${pkg.dir}/package.json: "${field}" is not an object.`,
        );
        continue;
      }
      for (const depName of Object.keys(deps)) {
        if (!depName.startsWith("@tiqian/")) continue;
        const edge = `${pkg.name} -> ${depName}`;
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

function checkCrossPackageImports(errors, notes) {
  let scannedFiles = 0;
  for (const pkg of PACKAGES) {
    const packageRoot = path.join(repoRoot, pkg.dir);
    for (const file of collectSourceFiles(packageRoot)) {
      scannedFiles += 1;
      const displayPath = path.relative(repoRoot, file);
      let text;
      try {
        text = readFileSync(file, "utf8");
      } catch (error) {
        errors.push(`[read] ${displayPath}: ${error.message}`);
        continue;
      }
      for (const hit of extractRelativeSpecifiers(text)) {
        const resolvedTarget = path.resolve(
          path.dirname(file),
          hit.specifier,
        );
        const escapesOwnPackage = !isInside(packageRoot, resolvedTarget);
        if (!escapesOwnPackage) continue;
        const owner = owningTopologyPackage(resolvedTarget);
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
          const verdict = STRICT_OUTSIDE_ESCAPES ? "violation" : "note";
          const message =
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

function main() {
  const errors = [];
  const notes = [];
  checkDependencyDeclarations(errors);
  const scannedFiles = checkCrossPackageImports(errors, notes);

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
