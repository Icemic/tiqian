import assert from "node:assert/strict";
import test from "node:test";

import {
  fontLoadingAffectsTypography,
  parseCssFontFamilies,
  waitForTypographyFonts,
} from "../core/engine/loaders/font-loader.js";
import type { FontLoadingEventLike, TypographyFontWaitResult } from "../core/engine/loaders/font-loader.js";

interface FakeStyleLike {
  getPropertyValue(property: string): string;
}

type FakeGetStyleFn = (element?: Element) => FakeStyleLike;

interface FakeFontSetLike {
  ready?: Promise<unknown>;
  load(descriptor: string, sample: string): Promise<unknown>;
}

interface FakeElementLike {
  textContent: string;
}

interface FakeFontFaceEntry {
  family: string;
  weight: string;
  style: string;
}

interface FakeFontLoadingEventLike {
  fontfaces: FakeFontFaceEntry[];
}

function coerceToFontLoadingEvent<T extends FakeFontLoadingEventLike>(fake: T): FontLoadingEventLike & T {
  return fake as FontLoadingEventLike & T;
}

interface FakeCSSStyleDeclarationLike extends FakeStyleLike {}

type FakeElementForLoader = FakeElementLike & Omit<Element, keyof FakeElementLike>;

type FakeGetStyleForLoader = (element?: Element) => CSSStyleDeclaration;

type VoidFn = () => void;

type FontFaceResolver = (value: FontFace[]) => void;

test("font loading invalidation filters unrelated family and face variants", () => {
  assert.deepEqual(
    parseCssFontFamilies('"IBM Plex Sans SC", system-ui, \'Noto Sans\''),
    ["ibm plex sans sc", "system-ui", "noto sans"],
  );
  const elements: FakeElementLike[] = [{ textContent: "" }];
  const getStyle: FakeGetStyleFn = (): FakeStyleLike => {
    interface StyleMap {
      [key: string]: string;
    }

    const map: StyleMap = {
      "font-family": '"IBM Plex Sans SC", sans-serif',
      "font-weight": "500",
      "font-style": "normal",
    };
    return {
      getPropertyValue(property: string): string {
        return map[property] ?? "";
      },
    };
  };

  const entry1: FakeFontFaceEntry = { family: "Unrelated", weight: "400", style: "normal" };
  const event1: FakeFontLoadingEventLike = { fontfaces: [entry1] };
  assert.equal(fontLoadingAffectsTypography(
    coerceToFontLoadingEvent(event1),
    elements as Iterable<FakeElementForLoader>,
    getStyle as FakeGetStyleForLoader,
  ), false);

  const entry2: FakeFontFaceEntry = { family: "IBM Plex Sans SC", weight: "400", style: "normal" };
  const event2: FakeFontLoadingEventLike = { fontfaces: [entry2] };
  assert.equal(fontLoadingAffectsTypography(
    coerceToFontLoadingEvent(event2),
    elements as Iterable<FakeElementForLoader>,
    getStyle as FakeGetStyleForLoader,
  ), false);

  const entry3: FakeFontFaceEntry = { family: "IBM Plex Sans SC", weight: "100 900", style: "normal" };
  const event3: FakeFontLoadingEventLike = { fontfaces: [entry3] };
  assert.equal(fontLoadingAffectsTypography(
    coerceToFontLoadingEvent(event3),
    elements as Iterable<FakeElementForLoader>,
    getStyle as FakeGetStyleForLoader,
  ), true);

  const event4: FakeFontLoadingEventLike = { fontfaces: [] };
  assert.equal(fontLoadingAffectsTypography(
    coerceToFontLoadingEvent(event4),
    elements as Iterable<FakeElementForLoader>,
    getStyle as FakeGetStyleForLoader,
  ), true);
});

interface FontLoadCall {
  descriptor: string;
  sample: string;
}

interface TimeoutOptions {
  timeoutMs: number;
}

test("initial font readiness waits only for prose font descriptors and subsets", async () => {
  const calls: FontLoadCall[] = [];
  let releaseBodyFont: VoidFn | undefined;
  const bodyFont: Promise<void> = new Promise((resolve) => { releaseBodyFont = resolve; });

  const fonts: FakeFontSetLike = {
    ready: bodyFont,
    load(descriptor: string, sample: string): Promise<FontFace[]> {
      calls.push({ descriptor, sample });
      return Promise.resolve([]);
    },
  };

  const elements: FakeElementLike[] = [
    { textContent: "\u7532\u4e59\u7532" },
    { textContent: "\u4e59\u4e19" },
    { textContent: "code" },
  ];

  interface StyleMap {
    [key: string]: string;
  }

  const getStyle: FakeGetStyleFn = (element?: Element): FakeStyleLike => {
    const mono: boolean = element === (elements[2] as FakeElementForLoader);
    const map: StyleMap = {
      "font-family": mono ? '"JetBrains Mono", monospace' : '"IBM Plex Sans SC", sans-serif',
      "font-size": mono ? "14px" : "16px",
      "font-style": "normal",
      "font-weight": mono ? "400" : "500",
      "font-stretch": "100%",
    };
    return {
      getPropertyValue(property: string): string {
        return map[property] ?? "";
      },
    };
  };

  const outcome: TypographyFontWaitResult = await waitForTypographyFonts(
    fonts as FontFaceSet,
    elements as Iterable<FakeElementForLoader>,
    getStyle as FakeGetStyleForLoader,
  );

  assert.deepEqual(calls, [
    {
      descriptor: 'normal 500 100% 16px "IBM Plex Sans SC", sans-serif',
      sample: "\u7532\u4e59\u4e19",
    },
    {
      descriptor: 'normal 400 100% 14px "JetBrains Mono", monospace',
      sample: "code",
    },
  ]);
  assert.equal(outcome.status, "settled");
  assert.equal(await Promise.race([bodyFont.then((): string => "ready"), Promise.resolve("not-awaited")]), "not-awaited");
  releaseBodyFont!();
});

test("initial font readiness times out without abandoning eventual completion", async () => {
  let releaseFont: FontFaceResolver | undefined;
  const font: Promise<FontFace[]> = new Promise((resolve) => { releaseFont = resolve; });
  const fonts: FakeFontSetLike = { load: (): Promise<FontFace[]> => font };
  const element: FakeElementLike = { textContent: "\u6b63\u6587" };

  interface StyleMap {
    [key: string]: string;
  }

  const getStyle: FakeGetStyleFn = (): FakeStyleLike => {
    const map: StyleMap = {
      "font-family": '"Example CJK", sans-serif',
      "font-size": "16px",
      "font-style": "normal",
      "font-weight": "400",
      "font-stretch": "100%",
    };
    return {
      getPropertyValue(property: string): string {
        return map[property] ?? "";
      },
    };
  };

  const timeoutOpts: TimeoutOptions = { timeoutMs: 0 };
  const outcome: TypographyFontWaitResult = await waitForTypographyFonts(
    fonts as FontFaceSet,
    [element as FakeElementForLoader],
    getStyle as FakeGetStyleForLoader,
    timeoutOpts,
  );

  assert.equal(outcome.status, "timeout");
  releaseFont!([]);
  await outcome.completion;
});

test("a rejected face settles on the browser fallback instead of timing out", async () => {
  const fonts: FakeFontSetLike = { load: (): Promise<FontFace[]> => Promise.reject(new Error("font unavailable")) };
  const element: FakeElementLike = { textContent: "\u6b63\u6587" };

  interface StyleMap {
    [key: string]: string;
  }

  const getStyle: FakeGetStyleFn = (): FakeStyleLike => {
    const map: StyleMap = {
      "font-family": "sans-serif",
      "font-size": "16px",
    };
    return {
      getPropertyValue(property: string): string {
        return map[property] ?? "";
      },
    };
  };

  const timeoutOpts: TimeoutOptions = { timeoutMs: 0 };
  const outcome: TypographyFontWaitResult = await waitForTypographyFonts(
    fonts as FontFaceSet,
    [element as FakeElementForLoader],
    getStyle as FakeGetStyleForLoader,
    timeoutOpts,
  );

  assert.equal(outcome.status, "settled");
});
