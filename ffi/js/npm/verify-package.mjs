#!/usr/bin/env node

import { readFile, readdir, stat } from "node:fs/promises";
import { resolve } from "node:path";
import { pathToFileURL } from "node:url";

const EXPECTED_NAME = "@tiqian/ffi";
const RUNTIMES = [
  {
    directory: "runtime/",
    path: "runtime/Tiqian-tiqian-ffi-js.mjs",
    marker: "precomputePlainParagraph",
  },
];

function fail(message) {
  throw new Error(`PackageVerificationFailed: ${message}`);
}

export async function verifyPackage(packageRoot = new URL("./", import.meta.url)) {
  const manifest = JSON.parse(await readFile(new URL("package.json", packageRoot), "utf8"));
  if (manifest.name !== EXPECTED_NAME) fail(`expected ${EXPECTED_NAME}, found ${manifest.name}`);
  if (manifest.license !== "MPL-2.0") fail("manifest must declare MPL-2.0");

  for (const required of [
    "LICENSE",
    "README.md",
  ]) {
    const metadata = await stat(new URL(required, packageRoot));
    if (!metadata.isFile() || metadata.size === 0) fail(`${required} is missing or empty`);
    if (!manifest.files.includes(required)) fail(`${required} is absent from files`);
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
    if (!manifest.files.includes(runtime.directory)) {
      fail(`${runtime.directory} is absent from files`);
    }
    const source = await readFile(new URL(runtime.path, packageRoot), "utf8");
    if (source.length <= 100 || !source.includes(runtime.marker)) {
      fail(`${runtime.path} is not a non-empty Kotlin/JS runtime`);
    }
    for (const marker of runtime.forbiddenMarkers ?? []) {
      if (source.includes(marker)) fail(`${runtime.path} contains forbidden browser marker ${marker}`);
    }
    const directoryEntries = await readdir(new URL(runtime.directory, packageRoot));
    const wasmEntry = directoryEntries.find((entry) => entry.endsWith(".wasm"));
    if (wasmEntry) {
      fail(`${runtime.directory}${wasmEntry} must not be published`);
    }
    verified.push({ path: runtime.path, size: Buffer.byteLength(source) });
  }

  const declarations = await readFile(
    new URL("runtime/Tiqian-tiqian-ffi-js.d.mts", packageRoot),
    "utf8",
  );
  for (const name of [
    "classifyFontRole",
    "unsupportedInlineShapingProperties",
    "firstDivergentInlineShapingProperty",
    "precomputePlainParagraph",
    "precomputeParagraph",
    "precomputeParagraphWithDiagnostics",
    "precomputeParagraphWithBrowserMetrics",
  ]) {
    if (!new RegExp(`export declare function ${name}\\(`).test(declarations)) {
      fail(`runtime/Tiqian-tiqian-ffi-js.d.mts does not declare ${name}`);
    }
  }

  const runtimeEntries = await readdir(new URL("runtime/", packageRoot));
  const modules = runtimeEntries.filter((entry) => entry.endsWith(".mjs"));
  const mapsWithoutSources = new Set([
    "kotlin_org_jetbrains_kotlin_kotlin_dom_api_compat.mjs.map",
  ]);
  for (const module of modules) {
    const map = `${module}.map`;
    if (!runtimeEntries.includes(map)) {
      fail(`runtime/${module} has no source map`);
    }
    if (mapsWithoutSources.has(map)) continue;
    const parsed = JSON.parse(await readFile(new URL(`runtime/${map}`, packageRoot), "utf8"));
    const sources = parsed.sources ?? [];
    const contents = parsed.sourcesContent ?? [];
    if (sources.length === 0 || contents.length < sources.length) {
      fail(`runtime/${map} does not embed its sources`);
    }
  }

  return verified;
}

const invokedPath = process.argv[1] ? pathToFileURL(resolve(process.argv[1])).href : null;
if (invokedPath === import.meta.url) {
  const artifacts = await verifyPackage();
  for (const artifact of artifacts) {
    console.log(`verified ${artifact.path} (${artifact.size} bytes)`);
  }
}
