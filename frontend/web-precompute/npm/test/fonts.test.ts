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

function readLocalFont(fileName: string): Buffer | null {
  try {
    return readFileSync(`${process.env.HOME}/.local/share/fonts/${fileName}`);
  } catch {
    return null; // no font available; the named-error path needs a real face list
  }
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
  const bytes = readLocalFont("EBGaramond-Regular.ttf");
  if (bytes === null) return;
  await assert.rejects(
    () =>
      fonts.createFontSession(
        [{ family: "Garamond", publicUrl: "/fonts/garamond.ttf", source: bytes }],
        { baseFeatures: ["kern"] },
      ),
    /UnsupportedFontSessionBaseFeatures/,
  );
});

test("styled spans reach source boundaries", { skip: fonts === null }, async () => {
  assert.ok(fonts);
  const bytes = readLocalFont("EBGaramond-Regular.ttf");
  const italicBytes = readLocalFont("EBGaramond-Italic.ttf");
  if (bytes === null || italicBytes === null) return; // boundaries need real faces
  const session = await fonts.createFontSession([
    { family: "Garamond", publicUrl: "/fonts/garamond.ttf", source: bytes },
    { family: "Garamond", publicUrl: "/fonts/garamond-italic.ttf", source: italicBytes, style: "italic" },
  ]);
  const style = { fontFamilies: ["Garamond"], fontSizePx: 20, fontWeight: 400, italic: false };
  const boundaries = session.sourceBoundaries("typeset", style, [
    { start: 0, end: 4, style },
    { start: 4, end: 7, style: { ...style, italic: true } },
  ]);
  assert.ok(Array.isArray(boundaries));
  session.close();
});
