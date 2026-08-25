import assert from "node:assert/strict";
import { readFile, readdir } from "node:fs/promises";
import test from "node:test";

interface PackageManifest {
  name: string;
  license: string;
  engines: { node: string };
  publishConfig: { access: string; tag: string };
  files: string[];
  exports: {
    ".": {
      types: string;
      default: string;
    };
  };
  dependencies?: undefined;
  bin?: undefined;
  scripts: {
    prepack: string;
  };
}

interface SourceMap {
  sources: string[];
  sourcesContent?: string[];
}

const MAPS_WITHOUT_SOURCES: ReadonlySet<string> = new Set([
  "kotlin_org_jetbrains_kotlin_kotlin_dom_api_compat.mjs.map",
]);

interface FfiExports {
  bopomofoParse: (reading: string) => string;
  numberSymbolCohesionUnbreakableRanges: (text: string) => string;
  fontMetricsResolve: (requestJson: string) => string;
  fontFallbackResolve: (text: string, start: number, end: number, requestJson: string) => string;
  liangHyphenate: (word: string, patternsJson: string, exceptionsJson: string, leftMin?: number, rightMin?: number) => string;
  unicodePunctuationLineBreakClassOf: (codePoint: number) => string;
  classifyFontRole: (text: string, start: number, end: number, locale: string) => string;
  unsupportedInlineShapingProperties: () => string[];
  firstDivergentInlineShapingProperty: (elementValues: string[], paragraphValues: string[]) => string | null;
  precomputePlainParagraph: (
    fontSessionId: string,
    text: string,
    maxWidthPx: number,
    fontFamilies: string,
    fontSizePx: number,
    lineHeightPx: number,
    locale: string,
    fontWeight: number,
    italic: boolean,
    firstLineIndentIc: number,
    lineLengthGridEnabled: boolean
  ) => string;
  precomputeParagraph: (
    fontSessionId: string,
    text: string,
    maxWidthPx: number,
    fontFamilies: string,
    fontSizePx: number,
    lineHeightPx: number,
    locale: string,
    fontWeight: number,
    italic: boolean,
    firstLineIndentIc: number,
    lineLengthGridEnabled: boolean,
    sourceBoundaries: string,
    textSpans: string,
    inlineBoxes: string,
    lineBreakSpans: string,
    inlineObjects: string | null,
    renderEvidenceOverride?: boolean | null
  ) => string;
  precomputeParagraphWithDiagnostics: (
    fontSessionId: string,
    text: string,
    maxWidthPx: number,
    fontFamilies: string,
    fontSizePx: number,
    lineHeightPx: number,
    locale: string,
    fontWeight: number,
    italic: boolean,
    firstLineIndentIc: number,
    lineLengthGridEnabled: boolean,
    sourceBoundaries: string,
    textSpans: string,
    inlineBoxes: string,
    lineBreakSpans: string,
    inlineObjects: string | null,
    zeroAdvanceEpsilonPx: number,
    decorations?: string | null,
    emphasisDotGapEm?: number | null,
    renderEvidenceOverride?: boolean | null
  ) => string;
  precomputeParagraphWithBrowserMetrics: (
    text: string,
    maxWidthPx: number,
    fontFamilies: string,
    fontSizePx: number,
    lineHeightPx: number,
    locale: string,
    fontWeight: number,
    italic: boolean,
    firstLineIndentIc: number,
    lineLengthGridEnabled: boolean,
    sourceBoundaries: string,
    textSpans: string,
    inlineBoxes: string,
    lineBreakSpans: string,
    inlineObjects: string | null,
    zeroAdvanceEpsilonPx: number,
    shapeJson: (p0: string) => string,
    metricsJson: (p0: string) => string,
    decorations?: string | null,
    emphasisDotGapEm?: number | null,
    renderEvidenceOverride?: boolean | null
  ) => string;
}

test("the manifest ships the generated engine runtime and nothing else", async () => {
  const manifest = JSON.parse(
    await readFile(new URL("./package.json", import.meta.url), "utf8"),
  ) as PackageManifest;

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
  assert.deepEqual(exported, [
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
  ]);
});

test("every engine module ships a source map with embedded sources", async () => {
  const entries = await readdir(new URL("./runtime/", import.meta.url));
  const modules = entries.filter((entry) => entry.endsWith(".mjs"));

  assert.ok(
    modules.length >= 4,
    "the runtime keeps the full module set (engine is a single published module)",
  );
  for (const module of modules) {
    const map = `${module}.map`;
    assert.ok(entries.includes(map), `runtime/${module} has no source map`);
    if (MAPS_WITHOUT_SOURCES.has(map)) continue;
    const parsed = JSON.parse(
      await readFile(new URL(`./runtime/${map}`, import.meta.url), "utf8"),
    ) as SourceMap;
    assert.ok(parsed.sources.length > 0, `runtime/${map} has no sources`);
    assert.ok(
      (parsed.sourcesContent ?? []).length >= parsed.sources.length,
      `runtime/${map} does not embed its sources`,
    );
  }
});

test("the engine entry loads from the package exports surface", async () => {
  const ffi = (await import("@tiqian/ffi")) as FfiExports;

  assert.equal(typeof ffi.bopomofoParse, "function");
  assert.equal(typeof ffi.numberSymbolCohesionUnbreakableRanges, "function");
  assert.equal(typeof ffi.fontMetricsResolve, "function");
  assert.equal(typeof ffi.fontFallbackResolve, "function");
  assert.equal(typeof ffi.liangHyphenate, "function");
  assert.equal(typeof ffi.unicodePunctuationLineBreakClassOf, "function");
  assert.equal(typeof ffi.classifyFontRole, "function");
  assert.equal(typeof ffi.unsupportedInlineShapingProperties, "function");
  assert.equal(typeof ffi.firstDivergentInlineShapingProperty, "function");
  assert.equal(typeof ffi.precomputePlainParagraph, "function");
  assert.equal(typeof ffi.precomputeParagraph, "function");
  assert.equal(typeof ffi.precomputeParagraphWithDiagnostics, "function");
  assert.equal(typeof ffi.precomputeParagraphWithBrowserMetrics, "function");
  assert.match(import.meta.resolve("@tiqian/ffi"), /Tiqian-tiqian-ffi-js\.mjs$/u);
});

test("classifyFontRole maps classifier roles to lowering role strings", async () => {
  const ffi = (await import("@tiqian/ffi")) as FfiExports;

  assert.equal(ffi.classifyFontRole("汉字", 0, 2, "zh-Hans"), "cjk-text");
  assert.equal(ffi.classifyFontRole("，", 0, 1, "zh-Hans"), "cjk-punctuation");
  assert.equal(ffi.classifyFontRole("Hello", 0, 5, "en"), "other");
});

test("unsupportedInlineShapingProperties returns fresh ordered property array", async () => {
  const ffi = (await import("@tiqian/ffi")) as FfiExports;

  const properties1 = ffi.unsupportedInlineShapingProperties();
  const properties2 = ffi.unsupportedInlineShapingProperties();

  assert.equal(properties1.length, 16);
  assert.equal(properties1[0], "font-feature-settings");
  assert.equal(properties1[1], "font-variation-settings");
  assert.equal(properties1[2], "font-stretch");
  assert.deepEqual(properties1, properties2);
  assert.notEqual(properties1, properties2, "consecutive calls return distinct array instances");
});

test("firstDivergentInlineShapingProperty detects divergence and clamps common prefix", async () => {
  const ffi = (await import("@tiqian/ffi")) as FfiExports;

  assert.equal(
    ffi.firstDivergentInlineShapingProperty(
      ["normal", "normal", "normal"],
      ["normal", "normal", "normal"],
    ) ?? null,
    null,
  );

  assert.equal(
    ffi.firstDivergentInlineShapingProperty(
      ["normal", "normal", "expanded"],
      ["normal", "normal", "condensed"],
    ),
    "font-stretch",
  );

  assert.equal(
    ffi.firstDivergentInlineShapingProperty(
      ["normal", "normal", "normal", "none"],
      ["normal", "normal", "normal", "auto"],
    ),
    "font-kerning",
  );

  assert.equal(
    ffi.firstDivergentInlineShapingProperty(
      ["normal", "normal"],
      ["normal", "normal", "expanded"],
    ) ?? null,
    null,
  );
  assert.equal(
    ffi.firstDivergentInlineShapingProperty(
      ["normal", "normal", "expanded"],
      ["normal", "normal"],
    ) ?? null,
    null,
  );
});