import assert from "node:assert/strict";
import test from "node:test";

import {
  createFontFamilies,
  cssFamilyToken,
  DEFAULT_LATIN_MONOSPACE_FONT_FAMILY,
  DEFAULT_CJK_SERIF_FONT_FAMILY,
  DEFAULT_LATIN_SERIF_FONT_FAMILY,
  DEFAULT_BOPOMOFO_FONT_FAMILY,
} from "../src/engine/canvas-fonts.js";
import type { WebFontFamiliesInstance } from "../src/engine/canvas-fonts.js";

const EXPECTED_LATIN_MONOSPACE: string =
  '"SFMono-Regular", Menlo, Consolas, "Liberation Mono", monospace';
const EXPECTED_CJK_SERIF: string =
  '"Songti SC", "Noto Serif CJK SC", serif';
const EXPECTED_LATIN_SERIF: string =
  'Georgia, "Times New Roman", serif';
const EXPECTED_BOPOMOFO: string =
  '"PingFang TC", "Hiragino Sans CNS", "Heiti TC", "Microsoft JhengHei UI", "Microsoft JhengHei", "Noto Sans CJK TC", "Source Han Sans TC", "Noto Sans Bopomofo", "Noto Serif Bopomofo", "BpmfGenYoGothic", "BpmfGenSenRounded", "Apple LiGothic", "Apple LiSung", "PMingLiU", "MingLiU", "Noto Serif CJK TC", "Source Han Serif TC", sans-serif';

test("canvas-fonts module exports defaults and helper", () => {
  assert.equal(typeof createFontFamilies, "function");
  assert.equal(typeof cssFamilyToken, "function");
  assert.equal(DEFAULT_LATIN_MONOSPACE_FONT_FAMILY, EXPECTED_LATIN_MONOSPACE);
  assert.equal(DEFAULT_CJK_SERIF_FONT_FAMILY, EXPECTED_CJK_SERIF);
  assert.equal(DEFAULT_LATIN_SERIF_FONT_FAMILY, EXPECTED_LATIN_SERIF);
  assert.equal(DEFAULT_BOPOMOFO_FONT_FAMILY, EXPECTED_BOPOMOFO);
});

test("createFontFamilies applies default stack strings verbatim", () => {
  const fonts: WebFontFamiliesInstance = createFontFamilies({
    cjk: '"PingFang SC", sans-serif',
    latin: '"Inter", sans-serif',
  });

  assert.equal(fonts.cjk, '"PingFang SC", sans-serif');
  assert.equal(fonts.latin, '"Inter", sans-serif');
  assert.equal(fonts.latinMonospace, EXPECTED_LATIN_MONOSPACE);
  assert.equal(fonts.cjkSerif, EXPECTED_CJK_SERIF);
  assert.equal(fonts.latinSerif, EXPECTED_LATIN_SERIF);
  assert.equal(fonts.bopomofo, EXPECTED_BOPOMOFO);
});

test("createFontFamilies allows overriding optional default stacks", () => {
  const fonts: WebFontFamiliesInstance = createFontFamilies({
    cjk: '"PingFang SC", sans-serif',
    latin: '"Inter", sans-serif',
    latinMonospace: "CustomMono, monospace",
    cjkSerif: "CustomCjkSerif, serif",
    latinSerif: "CustomLatinSerif, serif",
    bopomofo: "CustomBopomofo, sans-serif",
  });

  assert.equal(fonts.latinMonospace, "CustomMono, monospace");
  assert.equal(fonts.cjkSerif, "CustomCjkSerif, serif");
  assert.equal(fonts.latinSerif, "CustomLatinSerif, serif");
  assert.equal(fonts.bopomofo, "CustomBopomofo, sans-serif");
});

type CssFamilyTokenFn = (family: string) => string;

test("cssFamilyToken quotes bare names and preserves generic keywords / quoted strings", () => {
  const token: CssFamilyTokenFn = cssFamilyToken;

  assert.equal(token("serif"), "serif");
  assert.equal(token("sans-serif"), "sans-serif");
  assert.equal(token("sansserif"), "sansserif");
  assert.equal(token("monospace"), "monospace");
  assert.equal(token("cursive"), "cursive");
  assert.equal(token("fantasy"), "fantasy");
  assert.equal(token("system-ui"), "system-ui");
  assert.equal(token("Sans-Serif"), "Sans-Serif");
  assert.equal(token("MONOSPACE"), "MONOSPACE");

  assert.equal(token('"SFMono-Regular"'), '"SFMono-Regular"');
  assert.equal(token("'Times New Roman'"), "'Times New Roman'");

  assert.equal(token("PingFang SC"), '"PingFang SC"');
  assert.equal(token("Noto Sans CJK SC"), '"Noto Sans CJK SC"');
  assert.equal(token("Inter"), '"Inter"');
});

test("forRole selects role defaults when preferredFamilies is empty", () => {
  const fonts: WebFontFamiliesInstance = createFontFamilies({
    cjk: '"DefaultCJK", sans-serif',
    latin: '"DefaultLatin", sans-serif',
  });

  assert.equal(fonts.forRole("LatinText"), '"DefaultLatin", sans-serif');
  assert.equal(fonts.forRole("LatinText", []), '"DefaultLatin", sans-serif');
  assert.equal(fonts.forRole("CjkText"), '"DefaultCJK", sans-serif');
  assert.equal(fonts.forRole("CjkText", []), '"DefaultCJK", sans-serif');
  assert.equal(fonts.forRole("CjkPunctuation"), '"DefaultCJK", sans-serif');
  assert.equal(fonts.forRole("Symbol"), '"DefaultCJK", sans-serif');
  assert.equal(fonts.forRole("Emoji"), '"DefaultCJK", sans-serif');
  assert.equal(fonts.forRole("Unknown"), '"DefaultCJK", sans-serif');
});

test("forRole resolves single-keyword generic aliases per role", () => {
  const fonts: WebFontFamiliesInstance = createFontFamilies({
    cjk: '"DefaultCJK", sans-serif',
    latin: '"DefaultLatin", sans-serif',
  });

  assert.equal(fonts.forRole("LatinText", ["monospace"]), EXPECTED_LATIN_MONOSPACE);
  assert.equal(fonts.forRole("LatinText", ["MonoSpace"]), EXPECTED_LATIN_MONOSPACE);
  assert.equal(fonts.forRole("CjkText", ["monospace"]), '"DefaultCJK", sans-serif');
  assert.equal(fonts.forRole("CjkPunctuation", ["monospace"]), '"DefaultCJK", sans-serif');

  assert.equal(fonts.forRole("LatinText", ["serif"]), EXPECTED_LATIN_SERIF);
  assert.equal(fonts.forRole("LatinText", ["SERIF"]), EXPECTED_LATIN_SERIF);
  assert.equal(fonts.forRole("CjkText", ["serif"]), EXPECTED_CJK_SERIF);
  assert.equal(fonts.forRole("CjkPunctuation", ["serif"]), EXPECTED_CJK_SERIF);

  assert.equal(fonts.forRole("LatinText", ["sans-serif"]), '"DefaultLatin", sans-serif');
  assert.equal(fonts.forRole("LatinText", ["sansserif"]), '"DefaultLatin", sans-serif');
  assert.equal(fonts.forRole("CjkText", ["sans-serif"]), '"DefaultCJK", sans-serif');
  assert.equal(fonts.forRole("CjkText", ["sansserif"]), '"DefaultCJK", sans-serif');

  assert.equal(fonts.forRole("LatinText", ["CustomFont"]), '"CustomFont"');
  assert.equal(fonts.forRole("CjkText", ['"AlreadyQuoted"']), '"AlreadyQuoted"');
});

test("forRole formats multi-family preference with cssFamilyToken", () => {
  const fonts: WebFontFamiliesInstance = createFontFamilies({
    cjk: '"DefaultCJK", sans-serif',
    latin: '"DefaultLatin", sans-serif',
  });

  const resolved: string = fonts.forRole("CjkText", [
    "PingFang SC",
    "sans-serif",
    '"CustomQuoted"',
    "'SingleQuoted'",
  ]);
  assert.equal(resolved, '"PingFang SC", sans-serif, "CustomQuoted", \'SingleQuoted\'');
});

test("fallbackStacks generates single stack for 0 or 1 preferred family", () => {
  const fonts: WebFontFamiliesInstance = createFontFamilies({
    cjk: '"DefaultCJK", sans-serif',
    latin: '"DefaultLatin", sans-serif',
  });

  assert.deepEqual(fonts.fallbackStacks("LatinText"), ['"DefaultLatin", sans-serif']);
  assert.deepEqual(fonts.fallbackStacks("LatinText", []), ['"DefaultLatin", sans-serif']);
  assert.deepEqual(fonts.fallbackStacks("LatinText", ["monospace"]), [EXPECTED_LATIN_MONOSPACE]);
  assert.deepEqual(fonts.fallbackStacks("CjkText", ["CustomFont"]), ['"CustomFont"']);
});

test("fallbackStacks generates suffix sublists and collapses duplicate suffix stacks", () => {
  const fonts: WebFontFamiliesInstance = createFontFamilies({
    cjk: '"DefaultCJK", sans-serif',
    latin: '"DefaultLatin", sans-serif',
  });

  const stacks: string[] = fonts.fallbackStacks("CjkText", ["FontA", "FontB", "sans-serif"]);
  assert.deepEqual(stacks, [
    '"FontA", "FontB", sans-serif',
    '"FontB", sans-serif',
    "sans-serif",
  ]);

  const duplicateInput: string[] = ["FontA", "FontB", "FontA", "FontB"];
  const deduplicatedStacks: string[] = fonts.fallbackStacks("CjkText", duplicateInput);
  assert.deepEqual(deduplicatedStacks, [
    '"FontA", "FontB", "FontA", "FontB"',
    '"FontB", "FontA", "FontB"',
    '"FontA", "FontB"',
    '"FontB"',
  ]);
});

test("forRuby delegates to forRole with LatinText", () => {
  const fonts: WebFontFamiliesInstance = createFontFamilies({
    cjk: '"DefaultCJK", sans-serif',
    latin: '"DefaultLatin", sans-serif',
  });

  assert.equal(fonts.forRuby(), '"DefaultLatin", sans-serif');
  assert.equal(fonts.forRuby(["serif"]), EXPECTED_LATIN_SERIF);
  assert.equal(fonts.forRuby(["CustomRuby"]), '"CustomRuby"');
});

test("forBopomofo returns default stack or prepends explicit families", () => {
  const fonts: WebFontFamiliesInstance = createFontFamilies({
    cjk: '"DefaultCJK", sans-serif',
    latin: '"DefaultLatin", sans-serif',
  });

  assert.equal(fonts.forBopomofo(), EXPECTED_BOPOMOFO);
  assert.equal(fonts.forBopomofo([]), EXPECTED_BOPOMOFO);

  const custom: string = fonts.forBopomofo(["RubySpanFont", "sans-serif"]);
  assert.equal(custom, '"RubySpanFont", sans-serif, ' + EXPECTED_BOPOMOFO);
});

test("forRoleName maps LatinText to LatinText and anything else to CjkText", () => {
  const fonts: WebFontFamiliesInstance = createFontFamilies({
    cjk: '"DefaultCJK", sans-serif',
    latin: '"DefaultLatin", sans-serif',
  });

  assert.equal(fonts.forRoleName("LatinText"), '"DefaultLatin", sans-serif');
  assert.equal(fonts.forRoleName("LatinText", ["serif"]), EXPECTED_LATIN_SERIF);

  assert.equal(fonts.forRoleName("CjkText"), '"DefaultCJK", sans-serif');
  assert.equal(fonts.forRoleName("CjkText", ["serif"]), EXPECTED_CJK_SERIF);
  assert.equal(fonts.forRoleName("Unknown"), '"DefaultCJK", sans-serif');
  assert.equal(fonts.forRoleName(null), '"DefaultCJK", sans-serif');
  assert.equal(fonts.forRoleName(undefined), '"DefaultCJK", sans-serif');
});

test("roleFamilyCache returns the identical string instance on repeated queries", () => {
  const fonts: WebFontFamiliesInstance = createFontFamilies({
    cjk: '"DefaultCJK", sans-serif',
    latin: '"DefaultLatin", sans-serif',
  });

  const query1: string = fonts.forRole("CjkText", ["CustomFamily"]);
  const query2: string = fonts.forRole("CjkText", ["CustomFamily"]);
  assert.equal(query1, query2);
  assert.strictEqual(query1, query2);
});
