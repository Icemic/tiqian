import assert from "node:assert/strict";
import test from "node:test";
import type { TestContext } from "node:test";

import { createTypographyInvalidationSource } from "../src/sampler/observers.js";
import type { TypographyInvalidationSource } from "../src/sampler/observers.js";
import {
  declaredFaceSheets,
  declaredFacesDiagnostics,
  declareTiqianFontFaces,
  onDeclaredFacesChanged,
} from "../src/sampler/snapshot/declared-faces.js";
import type { DeclaredFaceDiagnostic, DeclaredFaceSheet } from "../src/sampler/snapshot/declared-faces.js";
import { collectFontFaces } from "../src/sampler/snapshot/precomputed.js";
import { initializeGlobalServices } from "../src/services/global-services.js";
initializeGlobalServices();


interface FakeStyleLike {
  getPropertyValue(prop: string): string;
}

interface FakeFontFaceRuleLike {
  type: number;
  parentStyleSheet: null;
  style: FakeStyleLike;
}

type RulesForFn = (text: string) => FakeFontFaceRuleLike[];

interface FakeCSSStyleSheetInstance {
  cssRules: FakeFontFaceRuleLike[];
  replaceSync(text: string): void;
}

interface FakeCSSStyleSheetConstructor {
  new(): FakeCSSStyleSheetInstance;
}

interface FakeFontFaceRuleProperties {
  "font-family"?: string;
  src?: string;
  [key: string]: string | undefined;
}

function fakeFontFaceRule(properties: FakeFontFaceRuleProperties, parentStyleSheet: null = null): FakeFontFaceRuleLike {
  const map: Map<string, string> = new Map(Object.entries(properties).filter((entry): entry is [string, string] => entry[1] !== undefined));
  return {
    type: 5,
    parentStyleSheet,
    style: {
      getPropertyValue(prop: string): string {
        return map.get(prop) ?? "";
      },
    },
  };
}

type VoidFn = () => void;
type BooleanFn = () => boolean;

function installStyleSheetStub(t: TestContext, rulesFor: RulesForFn): void {
  const previous: typeof globalThis.CSSStyleSheet | undefined = globalThis.CSSStyleSheet;
  const fakeConstructor: FakeCSSStyleSheetConstructor = class {
    cssRules: FakeFontFaceRuleLike[];
    constructor() { this.cssRules = []; }
    replaceSync(text: string): void { this.cssRules = rulesFor(text); }
  };
  (globalThis as Record<string, unknown>).CSSStyleSheet = fakeConstructor;
  t.after((): void => {
    if (previous === undefined) delete (globalThis as Record<string, unknown>).CSSStyleSheet;
    else globalThis.CSSStyleSheet = previous;
  });
}

test("declaredFaces_blankTextIsNoop", () => {
  const unregisterEmpty: VoidFn = declareTiqianFontFaces("");
  const unregisterBlank: VoidFn = declareTiqianFontFaces("   \n\t  ");

  assert.equal(declaredFaceSheets().length, 0);
  assert.equal(declaredFacesDiagnostics().length, 0);

  unregisterEmpty();
  unregisterEmpty();
  unregisterBlank();
  unregisterBlank();

  assert.equal(declaredFaceSheets().length, 0);
  assert.equal(declaredFacesDiagnostics().length, 0);
});

test("declaredFaces_refcountKeepsSharedDeclaration", (t) => {
  installStyleSheetStub(t, (): FakeFontFaceRuleLike[] => [
    fakeFontFaceRule({
      "font-family": "TestFont",
      src: "url('test.woff2')",
    }),
  ]);

  let changes: number = 0;
  const unsubscribe: BooleanFn = onDeclaredFacesChanged((): void => {
    changes += 1;
  });
  t.after(unsubscribe);

  const css: string = "@font-face { font-family: TestFont; src: url('test.woff2'); }";
  const baseUrl: string = "https://example.com/fonts.css";

  interface DeclareOptions {
    baseUrl: string;
  }

  const options1: DeclareOptions = { baseUrl };
  const unregister1: VoidFn = declareTiqianFontFaces(css, options1);
  assert.equal(changes, 1);
  assert.equal(declaredFaceSheets().length, 1);

  const options2: DeclareOptions = { baseUrl };
  const unregister2: VoidFn = declareTiqianFontFaces(css, options2);
  assert.equal(changes, 1, "Duplicate declaration should not trigger change notification");
  assert.equal(declaredFaceSheets().length, 1);

  unregister1();
  assert.equal(changes, 1, "Partial unregister should not trigger change notification");
  assert.equal(declaredFaceSheets().length, 1);

  unregister2();
  assert.equal(changes, 2);
  assert.equal(declaredFaceSheets().length, 0);
});

interface FakeStyleSheetEntry {
  href: string | null;
  cssRules: FakeFontFaceRuleLike[];
}

interface FakeDocumentLike {
  baseURI: string;
  styleSheets: FakeStyleSheetEntry[];
}

function coerceToDocument<T extends FakeDocumentLike>(fake: T): Document & T {
  return fake as Document & T;
}

test("declaredFaces_parseOrderDeclaredBeforeCssom", (t) => {
  installStyleSheetStub(t, (): FakeFontFaceRuleLike[] => [
    fakeFontFaceRule({
      "font-family": "DeclaredFont",
      src: 'url("b.woff2")',
    }),
  ]);

  interface DeclareOptions {
    baseUrl: string;
  }

  const options: DeclareOptions = { baseUrl: "https://cdn.test/fonts.css" };
  const unregister: VoidFn = declareTiqianFontFaces(
    "@font-face { font-family: DeclaredFont; src: url('b.woff2'); }",
    options,
  );
  t.after(unregister);

  const cssomRule: FakeFontFaceRuleLike = fakeFontFaceRule({
    "font-family": "CssomFont",
    src: 'url("/a.woff2")',
  });

  const sheetEntry: FakeStyleSheetEntry = {
    href: null,
    cssRules: [cssomRule],
  };

  const documentObject: FakeDocumentLike = {
    baseURI: "https://page.test/",
    styleSheets: [sheetEntry],
  };

  const { faces } = collectFontFaces(coerceToDocument(documentObject));
  assert.equal(faces.length, 2);
  assert.equal(faces[0].family, "DeclaredFont");
  assert.deepEqual(faces[0].urls, ["https://cdn.test/b.woff2"]);
  assert.equal(faces[1].family, "CssomFont");
  assert.deepEqual(faces[1].urls, ["https://page.test/a.woff2"]);
});

test("declaredFaces_cssomWinsByFindLastSemantics", (t) => {
  installStyleSheetStub(t, (): FakeFontFaceRuleLike[] => [
    fakeFontFaceRule({
      "font-family": "SharedFamily",
      src: 'url("declared.woff2")',
    }),
  ]);

  interface DeclareOptions {
    baseUrl: string;
  }

  const options: DeclareOptions = { baseUrl: "https://cdn.test/fonts.css" };
  const unregister: VoidFn = declareTiqianFontFaces(
    "@font-face { font-family: SharedFamily; src: url('declared.woff2'); }",
    options,
  );
  t.after(unregister);

  const cssomRule: FakeFontFaceRuleLike = fakeFontFaceRule({
    "font-family": "SharedFamily",
    src: 'url("cssom.woff2")',
  });

  const sheetEntry: FakeStyleSheetEntry = {
    href: "https://page.test/styles.css",
    cssRules: [cssomRule],
  };

  const documentObject: FakeDocumentLike = {
    baseURI: "https://page.test/",
    styleSheets: [sheetEntry],
  };

  const { faces } = collectFontFaces(coerceToDocument(documentObject));
  assert.equal(faces.length, 2);
  assert.equal(faces[0].family, "SharedFamily");
  assert.deepEqual(faces[0].urls, ["https://cdn.test/declared.woff2"]);
  assert.equal(faces[1].family, "SharedFamily");
  assert.deepEqual(faces[1].urls, ["https://page.test/cssom.woff2"]);

  const lastMatch = faces.findLast((f): boolean => f.family.toLowerCase() === "sharedfamily");
  assert.deepEqual(lastMatch?.urls, ["https://page.test/cssom.woff2"]);
});

test("declaredFaces_invalidTextRecordsDiagnostic", (t) => {
  installStyleSheetStub(t, (): FakeFontFaceRuleLike[] => {
    const error: Error = new Error("bad");
    error.name = "SyntaxError";
    throw error;
  });

  interface DeclareOptions {
    baseUrl: string;
  }

  const options: DeclareOptions = { baseUrl: "https://example.com/invalid.css" };
  const unregister: VoidFn = declareTiqianFontFaces("invalid css text", options);
  t.after(unregister);

  assert.deepEqual(declaredFacesDiagnostics(), [
    { kind: "DeclaredTextInvalid", error: "SyntaxError" },
  ]);
  assert.equal(declaredFaceSheets().length, 0);
});

test("declaredFaces_noRulesRecordsAbsence", (t) => {
  const previousCSSStyleSheet: typeof globalThis.CSSStyleSheet | undefined = globalThis.CSSStyleSheet;
  const previousDocument: typeof globalThis.document | undefined = globalThis.document;
  delete (globalThis as Record<string, unknown>).CSSStyleSheet;
  delete (globalThis as Record<string, unknown>).document;
  t.after((): void => {
    if (previousCSSStyleSheet !== undefined) globalThis.CSSStyleSheet = previousCSSStyleSheet;
    if (previousDocument !== undefined) globalThis.document = previousDocument;
  });

  interface DeclareOptions {
    baseUrl: string;
  }

  const options: DeclareOptions = { baseUrl: "https://example.com/no-rules.css" };
  const unregister: VoidFn = declareTiqianFontFaces(
    "@font-face { font-family: NoRules; }",
    options,
  );
  t.after(unregister);

  assert.deepEqual(declaredFacesDiagnostics(), [
    { kind: "DeclaredRulesUnavailable", error: "" },
  ]);
  assert.equal(declaredFaceSheets().length, 0);
});

test("declaredFaces_changeNotifications", (t) => {
  installStyleSheetStub(t, (): FakeFontFaceRuleLike[] => [
    fakeFontFaceRule({
      "font-family": "NotifyFont",
      src: "url('notify.woff2')",
    }),
  ]);

  let count: number = 0;
  const unsubscribe: BooleanFn = onDeclaredFacesChanged((): void => {
    count += 1;
  });

  interface DeclareOptions {
    baseUrl: string;
  }

  const options1: DeclareOptions = { baseUrl: "https://notify.test/1.css" };
  const unregister1: VoidFn = declareTiqianFontFaces(
    "@font-face { font-family: NotifyFont; src: url('notify.woff2'); }",
    options1,
  );
  assert.equal(count, 1);

  unregister1();
  assert.equal(count, 2);

  unsubscribe();

  const options2: DeclareOptions = { baseUrl: "https://notify.test/2.css" };
  const unregister2: VoidFn = declareTiqianFontFaces(
    "@font-face { font-family: NotifyFont; src: url('notify.woff2'); }",
    options2,
  );
  assert.equal(count, 2, "Listener should not receive notification after unsubscribe");
  unregister2();
});

interface FakeFontFaceSetLike {
  addEventListener(name: string, listener: VoidFn): void;
  removeEventListener(name: string, listener: VoidFn): void;
}

interface FakeDocumentWithFonts {
  fonts: FakeFontFaceSetLike;
}

interface GlobalEntry {
  name: string;
  own: boolean;
  value: unknown;
}

type FontListenerMap = Map<string, VoidFn>;

interface TypographyCallbacks {
  onMutation: VoidFn;
  onFontEvent: VoidFn;
  onDeclaredFacesChanged: VoidFn;
}

test("typographySource subscribes declared wakes and stops cleanly", (t) => {
  const globals: GlobalEntry[] = ["MutationObserver", "document"].map((name: string): GlobalEntry => ({
    name,
    own: Object.prototype.hasOwnProperty.call(globalThis, name),
    value: (globalThis as Record<string, unknown>)[name],
  }));
  const fontListeners: FontListenerMap = new Map();
  class FakeMutationObserver {
    observe(): void {}
    disconnect(): void {}
  }

  const fakeFonts: FakeFontFaceSetLike = {
    addEventListener(name: string, listener: VoidFn): void {
      fontListeners.set(name, listener);
    },
    removeEventListener(name: string, listener: VoidFn): void {
      if (fontListeners.get(name) === listener) fontListeners.delete(name);
    },
  };

  const fakeDocument: FakeDocumentWithFonts = {
    fonts: fakeFonts,
  };
  (globalThis as Record<string, unknown>).MutationObserver = FakeMutationObserver;
  (globalThis as Record<string, unknown>).document = fakeDocument;
  t.after((): void => {
    for (const entry of globals) {
      if (entry.own) (globalThis as Record<string, unknown>)[entry.name] = entry.value;
      else delete (globalThis as Record<string, unknown>)[entry.name];
    }
  });

  interface FakeRootLike {
    parentElement: null;
  }

  type FakeElementForTypography = FakeRootLike & Omit<Element, keyof FakeRootLike>;

  const wakes: number[] = [];
  const root: FakeRootLike = { parentElement: null };

  const callbacks: TypographyCallbacks = {
    onMutation: (): void => {},
    onFontEvent: (): void => {},
    onDeclaredFacesChanged: (): void => { wakes.push(1); },
  };

  const source: TypographyInvalidationSource = createTypographyInvalidationSource(root as FakeElementForTypography, callbacks);

  source.start();
  assert.equal(fontListeners.has("loadingdone"), true);
  assert.equal(wakes.length, 0);

  interface DeclareOptions {
    baseUrl: string;
  }

  const wakeOptions: DeclareOptions = { baseUrl: "https://wake.test/fonts.css" };
  const unregister: VoidFn = declareTiqianFontFaces(
    "@font-face { font-family: WakeFont; src: url('wake.woff2'); }",
    wakeOptions,
  );
  assert.equal(wakes.length, 1, "registration wakes the root synchronously");

  unregister();
  assert.equal(wakes.length, 2, "unregistration wakes the root too");

  source.stop();

  const stopOptions: DeclareOptions = { baseUrl: "https://wake.test/2.css" };
  const unregisterAfterStop: VoidFn = declareTiqianFontFaces(
    "@font-face { font-family: WakeFont2; src: url('wake2.woff2'); }",
    stopOptions,
  );
  assert.equal(wakes.length, 2, "no wake after the source stops");
  unregisterAfterStop();
  assert.equal(fontListeners.has("loadingdone"), false);
});
