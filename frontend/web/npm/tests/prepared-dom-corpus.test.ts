// Prepared DOM lowering golden corpus (ADR 0050 Verification).
//
// The committed corpus is shared with the Rust side
// `frontend/web-precompute/rust/tiqian-precompute/tests/prepared_dom_corpus.rs`;
// both sides assert the same bytes, so this module cannot drift from the Rust
// port. Regenerate the fixture with
// `node scripts/build-prepared-dom-corpus.mjs` from frontend/web-precompute.

import { readFileSync } from "node:fs";
import test from "node:test";
import assert from "node:assert/strict";

import { renderPreparedParagraphArtifact } from "@tiqian/core/core/sampler/snapshot/prepared-dom.js";

const fixture = JSON.parse(
  readFileSync(new URL("./prepared-dom-corpus.fixture.json", import.meta.url), "utf8"),
);

const STYLE_CLASS_MODES: Record<string, (declaration: string) => string> = {
  "declaration-length": (declaration: string) => `tqc-${declaration.length}`,
};

const EMPHASIS_DOT_COLOR_MODES: Record<string, () => string> = {
  "fixed-color": () => "rgb(17, 34, 51)",
};

test("prepared DOM lowering matches the shared golden corpus", () => {
  assert.ok(fixture.cases.length >= 20, "the corpus keeps covering the lowering paths");
  for (const { name, plan, locale, options = {}, expect } of fixture.cases) {
    assert.equal(typeof plan, "string", `${name}: plan stays wire JSON`);
    const callOptions = { ...options };
    const styleMode = callOptions.styleClassFor as string | undefined;
    const dotColorMode = callOptions.emphasisDotColor as string | undefined;
    delete callOptions.styleClassFor;
    delete callOptions.emphasisDotColor;
    if (styleMode) callOptions.styleClassFor = STYLE_CLASS_MODES[styleMode];
    if (dotColorMode) callOptions.emphasisDotColor = EMPHASIS_DOT_COLOR_MODES[dotColorMode];
    if (expect.kind === "ok") {
      const lowered = renderPreparedParagraphArtifact(plan, locale, callOptions);
      assert.equal(lowered.html, expect.html, `${name}: html`);
      assert.equal(JSON.stringify(lowered.artifact), expect.artifact, `${name}: artifact`);
      assert.equal(lowered.liveSemanticCount, expect.liveSemanticCount, `${name}: live count`);
      assert.equal(lowered.markerCount, expect.markerCount, `${name}: marker count`);
    } else {
      assert.throws(
        () => renderPreparedParagraphArtifact(plan, locale, callOptions),
        { message: expect.error },
        `${name}: error`,
      );
    }
  }
});
