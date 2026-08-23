import assert from "node:assert/strict";
import { readFile, readdir } from "node:fs/promises";
import test from "node:test";

const MAPS_WITHOUT_SOURCES = new Set([
  // The DOM API compatibility shim compiles from sources the JS compiler does
  // not carry; its map exists but has nothing to embed.
  "kotlin_org_jetbrains_kotlin_kotlin_dom_api_compat.mjs.map",
]);

test("the manifest ships the generated engine runtime and nothing else", async () => {
  const manifest = JSON.parse(await readFile(new URL("./package.json", import.meta.url), "utf8"));

  assert.equal(manifest.name, "@tiqian/ffi");
  assert.equal(manifest.license, "MPL-2.0");
  assert.equal(manifest.engines.node, ">=22");
  assert.deepEqual(manifest.publishConfig, { access: "public", tag: "alpha" });
  assert.deepEqual(manifest.files, ["LICENSE", "README.md", "runtime/"]);
  assert.deepEqual(manifest.exports, {
    ".": {
      types: "./runtime/Tiqian-tiqian-ffi-js.d.mts",
      default: "./runtime/Tiqian-tiqian-ffi-js.mjs",
    },
  });
  assert.equal(manifest.dependencies, undefined);
  assert.equal(manifest.bin, undefined);
  assert.equal(
    manifest.scripts.prepack,
    "npm run build:runtime && npm test && npm run verify:package",
  );
});

test("the generated declarations name the whole export surface", async () => {
  const declarations = await readFile(
    new URL("./runtime/Tiqian-tiqian-ffi-js.d.mts", import.meta.url),
    "utf8",
  );

  const exported = [...declarations.matchAll(/export declare function (\w+)\(/gu)].map(
    (match) => match[1],
  );
  assert.deepEqual(exported, ["precomputePlainParagraph", "precomputeParagraph"]);
});

test("every engine module ships a source map with embedded sources", async () => {
  const entries = await readdir(new URL("./runtime/", import.meta.url));
  const modules = entries.filter((entry) => entry.endsWith(".mjs"));

  assert.ok(modules.length >= 9, "the runtime keeps the full module set");
  for (const module of modules) {
    const map = `${module}.map`;
    assert.ok(entries.includes(map), `runtime/${module} has no source map`);
    if (MAPS_WITHOUT_SOURCES.has(map)) continue;
    const parsed = JSON.parse(await readFile(new URL(`./runtime/${map}`, import.meta.url), "utf8"));
    assert.ok(parsed.sources.length > 0, `runtime/${map} has no sources`);
    assert.ok(
      (parsed.sourcesContent ?? []).length >= parsed.sources.length,
      `runtime/${map} does not embed its sources`,
    );
  }
});

test("the engine entry loads from the package exports surface", async () => {
  const ffi = await import("@tiqian/ffi");

  assert.equal(typeof ffi.precomputePlainParagraph, "function");
  assert.equal(typeof ffi.precomputeParagraph, "function");
  assert.match(import.meta.resolve("@tiqian/ffi"), /Tiqian-tiqian-ffi-js\.mjs$/u);
});
