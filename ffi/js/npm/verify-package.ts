#!/usr/bin/env node

import { readFile, readdir, stat } from "node:fs/promises";
import type { Stats } from "node:fs";
import { resolve } from "node:path";
import { pathToFileURL } from "node:url";

export interface VerifiedArtifact {
  readonly path: string;
  readonly size: number;
}

interface RuntimeSpec {
  readonly directory: string;
  readonly path: string;
  readonly marker: string;
  readonly forbiddenMarkers?: readonly string[];
}

interface PackageManifest {
  readonly name: string;
  readonly license?: string;
  readonly files: readonly string[];
}

interface SourceMapManifest {
  readonly sources?: readonly string[];
  readonly sourcesContent?: readonly string[];
}

const EXPECTED_NAME: string = "@tiqian/ffi";
const RUNTIMES: readonly RuntimeSpec[] = [
  {
    directory: "runtime/",
    path: "runtime/Tiqian-tiqian-ffi-js.mjs",
    marker: "precomputePlainParagraph",
  },
];

function fail(message: string): never {
  throw new Error(`PackageVerificationFailed: ${message}`);
}

export async function verifyPackage(packageRoot: URL = new URL("./", import.meta.url)): Promise<VerifiedArtifact[]> {
  const manifest: PackageManifest = JSON.parse(await readFile(new URL("package.json", packageRoot), "utf8")) as PackageManifest;
  if (manifest.name !== EXPECTED_NAME) fail(`expected ${EXPECTED_NAME}, found ${manifest.name}`);
  if (manifest.license !== "MPL-2.0") fail("manifest must declare MPL-2.0");

  for (const required of [
    "LICENSE",
    "README.md",
  ]) {
    const metadata: Stats = await stat(new URL(required, packageRoot));
    if (!metadata.isFile() || metadata.size === 0) fail(`${required} is missing or empty`);
    if (!manifest.files.includes(required)) fail(`${required} is absent from files`);
  }
  const [license, readme]: readonly [string, string] = await Promise.all([
    readFile(new URL("LICENSE", packageRoot), "utf8"),
    readFile(new URL("README.md", packageRoot), "utf8"),
  ]);
  if (!license.startsWith("Mozilla Public License Version 2.0")) {
    fail("LICENSE is not the MPL-2.0 text");
  }
  if (!readme.includes(EXPECTED_NAME)) fail(`README.md does not name ${EXPECTED_NAME}`);

  const verified: VerifiedArtifact[] = [];
  for (const runtime of RUNTIMES) {
    if (!manifest.files.includes(runtime.directory)) {
      fail(`${runtime.directory} is absent from files`);
    }
    const source: string = await readFile(new URL(runtime.path, packageRoot), "utf8");
    if (source.length <= 100 || !source.includes(runtime.marker)) {
      fail(`${runtime.path} is not a non-empty Kotlin/JS runtime`);
    }
    for (const marker of runtime.forbiddenMarkers ?? []) {
      if (source.includes(marker)) fail(`${runtime.path} contains forbidden browser marker ${marker}`);
    }
    const directoryEntries: readonly string[] = await readdir(new URL(runtime.directory, packageRoot));
    const wasmEntry: string | undefined = directoryEntries.find((entry: string): boolean => entry.endsWith(".wasm"));
    if (wasmEntry) {
      fail(`${runtime.directory}${wasmEntry} must not be published`);
    }
    verified.push({ path: runtime.path, size: Buffer.byteLength(source) });
  }

  const declarations: string = await readFile(
    new URL("runtime/Tiqian-tiqian-ffi-js.d.mts", packageRoot),
    "utf8",
  );
  for (const name of [
    "bopomofoParse",
    "numberSymbolCohesionUnbreakableRanges",
    "fontMetricsResolve",
    "fontFallbackResolve",
    "liangHyphenate",
    "unicodePunctuationLineBreakClassOf",
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

  const runtimeEntries: readonly string[] = await readdir(new URL("runtime/", packageRoot));
  const modules: readonly string[] = runtimeEntries.filter((entry: string): boolean => entry.endsWith(".mjs"));
  const mapsWithoutSources: ReadonlySet<string> = new Set([
    "kotlin_org_jetbrains_kotlin_kotlin_dom_api_compat.mjs.map",
  ]);
  for (const module of modules) {
    const map: string = `${module}.map`;
    if (!runtimeEntries.includes(map)) {
      fail(`runtime/${module} has no source map`);
    }
    if (mapsWithoutSources.has(map)) continue;
    const parsed: SourceMapManifest = JSON.parse(await readFile(new URL(`runtime/${map}`, packageRoot), "utf8")) as SourceMapManifest;
    const sources: readonly string[] = parsed.sources ?? [];
    const contents: readonly string[] = parsed.sourcesContent ?? [];
    if (sources.length === 0 || contents.length < sources.length) {
      fail(`runtime/${map} does not embed its sources`);
    }
  }

  return verified;
}

const invokedPath: string | null = process.argv[1] ? pathToFileURL(resolve(process.argv[1])).href : null;
if (invokedPath === import.meta.url) {
  const artifacts: readonly VerifiedArtifact[] = await verifyPackage();
  for (const artifact of artifacts) {
    console.log(`verified ${artifact.path} (${artifact.size} bytes)`);
  }
}
