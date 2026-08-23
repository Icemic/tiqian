import assert from "node:assert/strict";
import test from "node:test";

import { createTypographyInvalidationSource } from "./core/sampler/observers.js";
import {
  declaredFaceSheets,
  declaredFacesDiagnostics,
  declareTiqianFontFaces,
  onDeclaredFacesChanged,
} from "./core/sampler/snapshot/declared-faces.js";
import { collectFontFaces } from "./core/sampler/snapshot/precomputed.js";

function installStyleSheetStub(t, rulesFor) {
  const previous = globalThis.CSSStyleSheet;
  globalThis.CSSStyleSheet = class {
    constructor() { this.cssRules = []; }
    replaceSync(text) { this.cssRules = rulesFor(text); }
  };
  t.after(() => {
    if (previous === undefined) delete globalThis.CSSStyleSheet;
    else globalThis.CSSStyleSheet = previous;
  });
}

function fakeFontFaceRule(properties, parentStyleSheet = null) {
  const map = new Map(Object.entries(properties));
  return {
    type: 5,
    parentStyleSheet,
    style: {
      getPropertyValue(prop) {
        return map.get(prop) ?? "";
      },
    },
  };
}

test("declaredFaces_blankTextIsNoop", () => {
  const unregisterEmpty = declareTiqianFontFaces("");
  const unregisterBlank = declareTiqianFontFaces("   \n\t  ");

  assert.equal(declaredFaceSheets().length, 0);
  assert.equal(declaredFacesDiagnostics().length, 0);

  // Unregister function should be safely callable multiple times
  unregisterEmpty();
  unregisterEmpty();
  unregisterBlank();
  unregisterBlank();

  assert.equal(declaredFaceSheets().length, 0);
  assert.equal(declaredFacesDiagnostics().length, 0);
});

test("declaredFaces_refcountKeepsSharedDeclaration", (t) => {
  installStyleSheetStub(t, () => [
    fakeFontFaceRule({
      "font-family": "TestFont",
      src: "url('test.woff2')",
    }),
  ]);

  let changes = 0;
  const unsubscribe = onDeclaredFacesChanged(() => {
    changes += 1;
  });
  t.after(unsubscribe);

  const css = "@font-face { font-family: TestFont; src: url('test.woff2'); }";
  const baseUrl = "https://example.com/fonts.css";

  const unregister1 = declareTiqianFontFaces(css, { baseUrl });
  assert.equal(changes, 1);
  assert.equal(declaredFaceSheets().length, 1);

  // Second declaration of the exact same (cssText, baseUrl)
  const unregister2 = declareTiqianFontFaces(css, { baseUrl });
  assert.equal(changes, 1, "Duplicate declaration should not trigger change notification");
  assert.equal(declaredFaceSheets().length, 1);

  // Unregister once: refCount decrements to 1, still kept
  unregister1();
  assert.equal(changes, 1, "Partial unregister should not trigger change notification");
  assert.equal(declaredFaceSheets().length, 1);

  // Unregister second time: refCount drops to 0, removed and notified
  unregister2();
  assert.equal(changes, 2);
  assert.equal(declaredFaceSheets().length, 0);
});

test("declaredFaces_parseOrderDeclaredBeforeCssom", (t) => {
  installStyleSheetStub(t, (text) => [
    fakeFontFaceRule({
      "font-family": "DeclaredFont",
      src: 'url("b.woff2")',
    }),
  ]);

  const unregister = declareTiqianFontFaces(
    "@font-face { font-family: DeclaredFont; src: url('b.woff2'); }",
    { baseUrl: "https://cdn.test/fonts.css" },
  );
  t.after(unregister);

  const documentObject = {
    baseURI: "https://page.test/",
    styleSheets: [
      {
        href: null,
        cssRules: [
          fakeFontFaceRule({
            "font-family": "CssomFont",
            src: 'url("/a.woff2")',
          }),
        ],
      },
    ],
  };

  const { faces } = collectFontFaces(documentObject);
  assert.equal(faces.length, 2);
  assert.equal(faces[0].family, "DeclaredFont");
  assert.deepEqual(faces[0].urls, ["https://cdn.test/b.woff2"]);
  assert.equal(faces[1].family, "CssomFont");
  assert.deepEqual(faces[1].urls, ["https://page.test/a.woff2"]);
});

test("declaredFaces_cssomWinsByFindLastSemantics", (t) => {
  installStyleSheetStub(t, () => [
    fakeFontFaceRule({
      "font-family": "SharedFamily",
      src: 'url("declared.woff2")',
    }),
  ]);

  const unregister = declareTiqianFontFaces(
    "@font-face { font-family: SharedFamily; src: url('declared.woff2'); }",
    { baseUrl: "https://cdn.test/fonts.css" },
  );
  t.after(unregister);

  const documentObject = {
    baseURI: "https://page.test/",
    styleSheets: [
      {
        href: "https://page.test/styles.css",
        cssRules: [
          fakeFontFaceRule({
            "font-family": "SharedFamily",
            src: 'url("cssom.woff2")',
          }),
        ],
      },
    ],
  };

  const { faces } = collectFontFaces(documentObject);
  assert.equal(faces.length, 2);
  assert.equal(faces[0].family, "SharedFamily");
  assert.deepEqual(faces[0].urls, ["https://cdn.test/declared.woff2"]);
  assert.equal(faces[1].family, "SharedFamily");
  assert.deepEqual(faces[1].urls, ["https://page.test/cssom.woff2"]);

  const lastMatch = faces.findLast((f) => f.family.toLowerCase() === "sharedfamily");
  assert.deepEqual(lastMatch?.urls, ["https://page.test/cssom.woff2"]);
});

test("declaredFaces_invalidTextRecordsDiagnostic", (t) => {
  installStyleSheetStub(t, () => {
    const error = new Error("bad");
    error.name = "SyntaxError";
    throw error;
  });

  const unregister = declareTiqianFontFaces("invalid css text", {
    baseUrl: "https://example.com/invalid.css",
  });
  t.after(unregister);

  assert.deepEqual(declaredFacesDiagnostics(), [
    { kind: "DeclaredTextInvalid", error: "SyntaxError" },
  ]);
  assert.equal(declaredFaceSheets().length, 0);
});

test("declaredFaces_noRulesRecordsAbsence", (t) => {
  // Ensure neither CSSStyleSheet nor document exists
  const previousCSSStyleSheet = globalThis.CSSStyleSheet;
  const previousDocument = globalThis.document;
  delete globalThis.CSSStyleSheet;
  delete globalThis.document;
  t.after(() => {
    if (previousCSSStyleSheet !== undefined) globalThis.CSSStyleSheet = previousCSSStyleSheet;
    if (previousDocument !== undefined) globalThis.document = previousDocument;
  });

  const unregister = declareTiqianFontFaces(
    "@font-face { font-family: NoRules; }",
    { baseUrl: "https://example.com/no-rules.css" },
  );
  t.after(unregister);

  assert.deepEqual(declaredFacesDiagnostics(), [
    { kind: "DeclaredRulesUnavailable", error: "" },
  ]);
  assert.equal(declaredFaceSheets().length, 0);
});

test("declaredFaces_changeNotifications", (t) => {
  installStyleSheetStub(t, () => [
    fakeFontFaceRule({
      "font-family": "NotifyFont",
      src: "url('notify.woff2')",
    }),
  ]);

  let count = 0;
  const unsubscribe = onDeclaredFacesChanged(() => {
    count += 1;
  });

  const unregister1 = declareTiqianFontFaces(
    "@font-face { font-family: NotifyFont; src: url('notify.woff2'); }",
    { baseUrl: "https://notify.test/1.css" },
  );
  assert.equal(count, 1);

  unregister1();
  assert.equal(count, 2);

  unsubscribe();

  const unregister2 = declareTiqianFontFaces(
    "@font-face { font-family: NotifyFont; src: url('notify.woff2'); }",
    { baseUrl: "https://notify.test/2.css" },
  );
  assert.equal(count, 2, "Listener should not receive notification after unsubscribe");
  unregister2();
});

test("typographySource subscribes declared wakes and stops cleanly", (t) => {
  const globals = ["MutationObserver", "document"].map((name) => ({
    name,
    own: Object.prototype.hasOwnProperty.call(globalThis, name),
    value: globalThis[name],
  }));
  const fontListeners = new Map();
  class FakeMutationObserver {
    observe() {}
    disconnect() {}
  }
  const fakeDocument = {
    fonts: {
      addEventListener(name, listener) {
        fontListeners.set(name, listener);
      },
      removeEventListener(name, listener) {
        if (fontListeners.get(name) === listener) fontListeners.delete(name);
      },
    },
  };
  globalThis.MutationObserver = FakeMutationObserver;
  globalThis.document = fakeDocument;
  t.after(() => {
    for (const entry of globals) {
      if (entry.own) globalThis[entry.name] = entry.value;
      else delete globalThis[entry.name];
    }
  });

  const wakes = [];
  const root = { parentElement: null };
  const source = createTypographyInvalidationSource(root, {
    onMutation: () => {},
    onFontEvent: () => {},
    onDeclaredFacesChanged: () => wakes.push(1),
  });

  source.start();
  assert.equal(fontListeners.has("loadingdone"), true);
  assert.equal(wakes.length, 0);

  const unregister = declareTiqianFontFaces(
    "@font-face { font-family: WakeFont; src: url('wake.woff2'); }",
    { baseUrl: "https://wake.test/fonts.css" },
  );
  assert.equal(wakes.length, 1, "registration wakes the root synchronously");

  unregister();
  assert.equal(wakes.length, 2, "unregistration wakes the root too");

  source.stop();
  const unregisterAfterStop = declareTiqianFontFaces(
    "@font-face { font-family: WakeFont2; src: url('wake2.woff2'); }",
    { baseUrl: "https://wake.test/2.css" },
  );
  assert.equal(wakes.length, 2, "no wake after the source stops");
  unregisterAfterStop();
  assert.equal(fontListeners.has("loadingdone"), false);
});
