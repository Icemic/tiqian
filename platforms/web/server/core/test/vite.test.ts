// The vite entry derives SSR externals from the package manifest, so the
// list carries the installed scope and every exports key without a
// host-side copy of entry names.

import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";
import assert from "node:assert/strict";
import { pathToFileURL } from "node:url";

type ViteModule = typeof import("../src/vite.js");
const { tiqianSsrExternals } = (await import("../lib/vite.js")) as ViteModule;

const writeManifest = (dir: string, manifest: Record<string, unknown>): URL => {
  const manifestPath = path.join(dir, "package.json");
  writeFileSync(manifestPath, JSON.stringify(manifest));
  return pathToFileURL(manifestPath);
};

test("ssr externals derive from a manifest under any scope", async () => {
  const dir = mkdtempSync(path.join(tmpdir(), "tiqian-vite-"));
  try {
    const manifestUrl = writeManifest(dir, {
      name: "@example/precompute",
      exports: {
        ".": "./lib/a.js",
        "./precompute": "./lib/b.js",
        "./vite": "./lib/vite.js",
      },
    });
    assert.deepEqual(await tiqianSsrExternals(manifestUrl), [
      "@example/precompute",
      "@example/precompute/precompute",
      "@example/precompute/vite",
    ]);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test("ssr externals reject a manifest without exports", async () => {
  const dir = mkdtempSync(path.join(tmpdir(), "tiqian-vite-"));
  try {
    const manifestUrl = writeManifest(dir, { name: "@example/precompute" });
    await assert.rejects(tiqianSsrExternals(manifestUrl), /TiqianViteManifestInvalid/u);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test("the shipped manifest covers every entry hosts must externalize", async () => {
  const manifest = JSON.parse(
    readFileSync(new URL("../package.json", import.meta.url), "utf8"),
  ) as { name: string; exports: Record<string, unknown> };
  const externals = await tiqianSsrExternals(new URL("../package.json", import.meta.url));
  assert.equal(externals.length, Object.keys(manifest.exports).length);
  // precompute-html is the entry hand-copied hosts missed; it must ride along.
  assert.ok(externals.includes(`${manifest.name}/precompute-html`));
});
