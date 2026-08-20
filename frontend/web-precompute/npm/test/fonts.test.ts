// Boundary smoke over the native addon, run against the built lib/ output.
// Runs wherever an addon build exists (local `npm run debug:native`, or the
// CI build lane); on a checkout without a build the loader cannot resolve a
// platform package and the suite skips.

import { readFileSync } from "node:fs";
import { test } from "node:test";
import assert from "node:assert/strict";

type FontsModule = typeof import("../src/fonts.js");

let fonts: FontsModule | null = null;
try {
  fonts = (await import("../lib/fonts.js")) as FontsModule;
} catch {
  fonts = null;
}

test("addon loads and reports engine identity", { skip: fonts === null }, () => {
  assert.ok(fonts);
  assert.equal(fonts.backendRevision, "tiqian-shared-harfbuzz-v5");
  assert.match(fonts.harfbuzzVersion, /^harfrust-/);
});

test("empty face list reports MissingExplicitFontFaces", { skip: fonts === null }, async () => {
  assert.ok(fonts);
  await assert.rejects(() => fonts.createFontSession([]), /MissingExplicitFontFaces/);
});

test("unsupported base features report by name", { skip: fonts === null }, async () => {
  assert.ok(fonts);
  let bytes: Buffer | null = null;
  try {
    bytes = readFileSync(`${process.env.HOME}/.local/share/fonts/EBGaramond-Regular.ttf`);
  } catch {
    return; // no font available; the named-error path needs a real face list
  }
  await assert.rejects(
    () =>
      fonts.createFontSession(
        [{ family: "Garamond", publicUrl: "/fonts/garamond.ttf", source: bytes as Buffer }],
        { baseFeatures: ["kern"] },
      ),
    /UnsupportedFontSessionBaseFeatures/,
  );
});
