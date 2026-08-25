#!/usr/bin/env node

import { spawnSync } from "node:child_process";
import { copyFile, mkdir, mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";

const packageRoot = fileURLToPath(new URL("../", import.meta.url));
const consumerRoot = await mkdtemp(resolve(tmpdir(), "tiqian-prose-release-"));
let tarballPath = null;

function runNpm(arguments_, options = {}) {
  const npmCli = process.env.npm_execpath;
  const command = npmCli ? process.execPath : process.platform === "win32" ? "npm.cmd" : "npm";
  const args = npmCli ? [npmCli, ...arguments_] : arguments_;
  const result = spawnSync(command, args, {
    cwd: options.cwd ?? packageRoot,
    encoding: "utf8",
    stdio: options.capture ? "pipe" : "inherit",
  });
  if (result.error) throw result.error;
  if (result.status !== 0) {
    const detail = [result.stdout, result.stderr].filter(Boolean).join("\n").trim();
    throw new Error(`ReleaseConsumerCommandFailed: npm ${arguments_.join(" ")}\n${detail}`);
  }
  return result.stdout ?? "";
}

try {
  // prepack already rebuilt and verified the working tree. Pack without scripts
  // here so this consumer check cannot recursively invoke verify:release.
  const packed = JSON.parse(runNpm([
    "pack",
    "--ignore-scripts",
    "--json",
    "--pack-destination",
    consumerRoot,
  ], { capture: true }));
  const filename = packed?.[0]?.filename;
  if (!filename) throw new Error("ReleaseConsumerPackFailed: npm pack returned no filename");
  tarballPath = resolve(consumerRoot, filename);

  // The ffi and core packages publish in lockstep (ADR 0053 A4/F2).
  // Pack both from the working tree and install them so the consumer resolves
  // @tiqian/core and @tiqian/ffi the way the lockstep release provides them.
  const packedFfi = JSON.parse(runNpm([
    "pack",
    "--ignore-scripts",
    "--json",
    "--pack-destination",
    consumerRoot,
  ], { cwd: resolve(packageRoot, "../../../ffi/js/npm"), capture: true }));
  const ffiFilename = packedFfi?.[0]?.filename;
  if (!ffiFilename) throw new Error("ReleaseConsumerPackFailed: npm pack returned no ffi filename");

  const packedCore = JSON.parse(runNpm([
    "pack",
    "--ignore-scripts",
    "--json",
    "--pack-destination",
    consumerRoot,
  ], { cwd: resolve(packageRoot, "../core"), capture: true }));
  const coreFilename = packedCore?.[0]?.filename;
  if (!coreFilename) throw new Error("ReleaseConsumerPackFailed: npm pack returned no core filename");

  await writeFile(
    resolve(consumerRoot, "package.json"),
    `${JSON.stringify({ private: true, type: "module" }, null, 2)}\n`,
  );
  runNpm([
    "install",
    "--ignore-scripts",
    "--package-lock=false",
    "--no-audit",
    "--no-fund",
    tarballPath,
    resolve(consumerRoot, coreFilename),
    resolve(consumerRoot, ffiFilename),
  ], { cwd: consumerRoot });

  // Stylesheet single-source-of-truth: prose tarball must not carry styles.css,
  // core tarball must. This replaces the previous byte-identity pin.
  function listTarball(tarball) {
    const listed = spawnSync("tar", ["-tzf", tarball], { encoding: "utf8", stdio: "pipe" });
    if (listed.error) throw listed.error;
    if (listed.status !== 0) {
      const detail = [listed.stdout, listed.stderr].filter(Boolean).join("\n").trim();
      throw new Error(`ReleaseTarballListFailed: tar -tzf ${tarball}\n${detail}`);
    }
    return String(listed.stdout ?? "");
  }
  const proseTarballList = listTarball(tarballPath);
  if (proseTarballList.includes("styles.css")) {
    throw new Error("ReleaseTarballVerificationFailed: @tiqian/prose tarball must not contain styles.css (stylesheet ships from @tiqian/core)");
  }
  const coreTarballList = listTarball(resolve(consumerRoot, coreFilename));
  if (!coreTarballList.includes("styles.css")) {
    throw new Error("ReleaseTarballVerificationFailed: @tiqian/core tarball must contain styles.css");
  }

  await writeFile(
    resolve(consumerRoot, "verify.mjs"),
    `import assert from "node:assert/strict";
import * as api from "@tiqian/prose";
import { TiqianProseElement } from "@tiqian/prose/element";
import { readFile, stat } from "node:fs/promises";

assert.equal(typeof api.enhance, "function");
assert.equal(typeof api.destroy, "function");
assert.equal(typeof TiqianProseElement, "function");
// Single source of truth: the stylesheet ships from @tiqian/core only;
// prose neither ships nor exports it.
const proseManifest = JSON.parse(await readFile(new URL("./node_modules/@tiqian/prose/package.json", import.meta.url), "utf8"));
assert.equal(proseManifest.exports["./styles.css"], undefined);
// The stylesheet resolves through core's exports map and exists on disk.
const coreStylesResolution = import.meta.resolve("@tiqian/core/styles.css");
assert.match(coreStylesResolution, /@tiqian\\/core\\/styles\\.css$/u);
const coreStylesMeta = await stat(new URL(coreStylesResolution));
assert.ok(coreStylesMeta.isFile() && coreStylesMeta.size > 0);
`,
  );
  const result = spawnSync(process.execPath, ["verify.mjs"], {
    cwd: consumerRoot,
    encoding: "utf8",
    stdio: "pipe",
  });
  if (result.error) throw result.error;
  if (result.status !== 0) {
    const detail = [result.stdout, result.stderr].filter(Boolean).join("\n").trim();
    throw new Error(`ReleaseConsumerImportFailed:\n${detail}`);
  }
  console.log("verified packed @tiqian/prose exports in an isolated consumer");
  const artifactDirectory = String(process.env.TIQIAN_RELEASE_ARTIFACT_DIR ?? "").trim();
  if (artifactDirectory) {
    const outputDirectory = resolve(artifactDirectory);
    await mkdir(outputDirectory, { recursive: true });
    const outputPath = resolve(outputDirectory, filename);
    await copyFile(tarballPath, outputPath);
    console.log(`retained verified release tarball at ${outputPath}`);
  }
} finally {
  await rm(consumerRoot, { force: true, recursive: true });
}
