#!/usr/bin/env node

// Package verification for @tiqian/prose-core. The Kotlin/JS runtime guards
// moved here from the @tiqian/prose verifier when the runtime artifact lane
// moved (ADR 0053 F2); the checks keep their original strength.

import { readFile, readdir, stat } from "node:fs/promises";

const EXPECTED_NAME = "@tiqian/prose-core";
const RUNTIMES = [
  {
    directory: "runtime/",
    path: "runtime/tiqian-web.js",
    marker: "TiqianEngine",
    forbiddenMarkers: ["__TiqianWebFontShaping", "WebAssembly"],
  },
];
const REQUIRED_FILES = [
  "LICENSE",
  "README.md",
  "core/",
  "runtime/",
  "snapshot-schema.js",
  "snapshot-tables.js",
  "layout-worker.js",
  "worker-layout.js",
];

function fail(message) {
  throw new Error(`PackageVerificationFailed: ${message}`);
}

export async function verifyPackage(packageRoot = new URL("./", import.meta.url)) {
  const manifest = JSON.parse(await readFile(new URL("package.json", packageRoot), "utf8"));
  if (manifest.name !== EXPECTED_NAME) fail(`expected ${EXPECTED_NAME}, found ${manifest.name}`);
  if (manifest.license !== "MPL-2.0") fail("manifest must declare MPL-2.0");
  if (manifest.dependencies?.["@tiqian/prose"]) {
    fail("core package must not depend on the web-component package");
  }
  if (!manifest.dependencies?.["@tiqian/ffi"]) {
    fail("core package must declare its @tiqian/ffi dependency");
  }

  for (const required of REQUIRED_FILES) {
    if (!manifest.files.includes(required)) fail(`${required} is absent from files`);
    const metadata = await stat(new URL(required, packageRoot));
    const isFile = metadata.isFile() && metadata.size > 0;
    const isDir = metadata.isDirectory();
    if (!isFile && !isDir) fail(`${required} is missing or empty`);
  }
  const [license, readme] = await Promise.all([
    readFile(new URL("LICENSE", packageRoot), "utf8"),
    readFile(new URL("README.md", packageRoot), "utf8"),
  ]);
  if (!license.startsWith("Mozilla Public License Version 2.0")) {
    fail("LICENSE is not the MPL-2.0 text");
  }
  if (!readme.includes(EXPECTED_NAME)) fail(`README.md does not name ${EXPECTED_NAME}`);

  const verified = [];
  for (const runtime of RUNTIMES) {
    const source = await readFile(new URL(runtime.path, packageRoot), "utf8");
    if (source.length <= 100 || !source.includes(runtime.marker)) {
      fail(`${runtime.path} is not a non-empty Kotlin/JS runtime`);
    }
    for (const marker of runtime.forbiddenMarkers ?? []) {
      if (source.includes(marker)) fail(`${runtime.path} contains forbidden browser marker ${marker}`);
    }
    const runtimeEntries = await readdir(new URL(runtime.directory, packageRoot));
    const wasmEntry = runtimeEntries.find((entry) => entry.endsWith(".wasm"));
    if (wasmEntry) {
      fail(`${runtime.directory}${wasmEntry} must not be published`);
    }
    verified.push({ path: runtime.path, size: Buffer.byteLength(source) });
  }
  return verified;
}

const isEntryPoint = process.argv[1] && import.meta.url === new URL(`file://${process.argv[1]}`).href;
if (isEntryPoint) {
  const verified = await verifyPackage();
  for (const entry of verified) {
    console.log(`verified ${entry.path} (${entry.size} bytes)`);
  }
}
