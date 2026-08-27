#!/usr/bin/env node

// Package integrity check for @tiqian/core. Verifies the manifest name,
// license, dependency surface, and that every required published file exists
// and is non-empty. The engine runtime installs through
// core/engine/loaders/cjk-dash.js at import time; no bundled Kotlin/JS
// runtime artifact is scanned here anymore.

import { readFile, stat } from "node:fs/promises";

const EXPECTED_NAME = "@tiqian/core";
const REQUIRED_FILES = [
  "LICENSE",
  "README.md",
  "core/",
  "core/sampler/snapshot/snapshot-schema.js",
  "core/sampler/snapshot/snapshot-tables.js",
  "core/engine/layout-worker.js",
  "core/engine/web-worker/worker-channel.js",
  "core/sampler/snapshot/table-binary-writer.mjs",
];

function fail(message) {
  throw new Error(`PackageVerificationFailed: ${message}`);
}

export async function verifyPackage(packageRoot = new URL("../", import.meta.url)) {
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
}

const isEntryPoint = process.argv[1] && import.meta.url === new URL(`file://${process.argv[1]}`).href;
if (isEntryPoint) {
  await verifyPackage();
  console.log(`verified ${EXPECTED_NAME}`);
}
