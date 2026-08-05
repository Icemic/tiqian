import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import test from "node:test";

import {
  createHtmlPreparer,
  findHtmlOpeningTags,
  renderSnapshotServerAssets,
} from "./precompute-html.js";

const typography = Object.freeze({
  fontFamilies: ["Fixture CJK"],
  fontSizePx: 18,
  lineHeightPx: 27,
  locale: "zh-Hans",
  fontWeight: 400,
  italic: false,
  firstLineIndentIc: 0,
  lineLengthGridEnabled: true,
  letterSpacingPx: 0,
  fontFeatureSettings: "normal",
  fontVariationSettings: "normal",
  fontVariantNumeric: "normal",
});

function sha256(value) {
  return createHash("sha256").update(value).digest("hex");
}

function prepared(input) {
  return {
    status: "prepared",
    schema: 1,
    layoutRevision: "tiqian-layout-v2",
    renderRevision: "prebroken-dom-v15",
    key: input.key,
    sourceText: input.text,
    sourceSha256: sha256(input.text),
    sourceArtifactSha256: sha256(JSON.stringify({ text: input.text, semantics: input.semantics ?? [] })),
    semantics: input.semantics ?? [],
    inlineBoxes: input.inlineBoxes ?? [],
    renderTextSpans: [],
    typography,
    typographySha256: sha256(JSON.stringify(typography)),
    maxWidthPx: input.maxWidthPx ?? 1,
    renderFontFamilies: ["Fixture CJK"],
    fontEvidence: {
      backendRevision: "tiqian-shared-harfbuzz-v5",
      harfbuzzVersion: "fixture",
      faces: [{
        faceId: "fixture-face",
        sourceOrder: 0,
        family: "Fixture CJK",
        publicUrl: "/fonts/fixture.woff2",
        coverageText: input.text,
        probe: { text: input.text[0] },
      }],
      replay: {
        revision: "tiqian-server-shaping-replay-v1",
        shapes: [],
        metrics: [],
      },
    },
    plan: {
      schema: 1,
      layoutRevision: "tiqian-layout-v2",
      height: 27,
      lines: [{
        rangeStart: 0,
        rangeEnd: input.text.length,
        top: 0,
        bottom: 27,
        baseline: 20,
        indent: 0,
        visualWidth: 36,
        hyphenAdvance: 0,
        endReason: "ParagraphEnd",
        cells: [{
          rangeStart: 0,
          rangeEnd: input.text.length,
          source: input.text,
          display: input.text,
          drawX: 0,
          naturalWidth: 36,
          leadingLayoutAdvance: 0,
        }],
      }],
    },
    html: "",
    renderArtifactSha256: "c".repeat(64),
  };
}

function fakePrecomputer({ snapshots = true, contracts = true } = {}) {
  const snapshotInputs = [];
  const contractInputs = [];
  return {
    typography,
    renderFontFamilies: ["Fixture CJK"],
    snapshotInputs,
    contractInputs,
    async prepareParagraph(input) {
      snapshotInputs.push(input);
      return snapshots ? prepared(input) : { status: "unsupported", key: input.key, issue: "fixture" };
    },
    async prepareFontContract(input) {
      contractInputs.push(input);
      return contracts ? prepared(input) : { status: "unsupported", key: input.key, issue: "fixture" };
    },
    close() {},
  };
}

test("opening-tag scan ignores literal paragraphs in comments, raw text and templates", () => {
  const html = '<!-- <p>comment</p> --><p>正文</p><script>"<p>script</p>"</script>' +
    '<template><p>inert</p></template><li>条目</li>';
  assert.deepEqual(
    findHtmlOpeningTags(html).map(({ tagName, source }) => [tagName, source]),
    [["p", "<p>"], ["li", "<li>"]],
  );
});

test("width-free HTML preparation never asks the host for a maximum measure", async () => {
  const precomputer = fakePrecomputer({ contracts: false });
  const preparer = await createHtmlPreparer({ precomputer });
  const result = await preparer.prepare("<p>无需声明版心。</p>");

  assert.equal(precomputer.snapshotInputs.length, 0);
  assert.equal(precomputer.contractInputs.length, 1);
  assert.equal("maxWidthPx" in precomputer.contractInputs[0], false);
  assert.equal(result.html, "<p>无需声明版心。</p>");
  assert.equal(result.bundle, null);
  preparer.close();
});

test("fixed-measure snapshots key only conservative plain paragraphs", async () => {
  const precomputer = fakePrecomputer();
  const preparer = await createHtmlPreparer({ precomputer });
  const result = await preparer.prepare(
    '<p>纯文本。</p><p><a href="/next">链接正文</a></p>',
    { id: "tq-article", snapshot: { maxWidthPx: 720 } },
  );

  assert.equal(precomputer.snapshotInputs.length, 1);
  assert.equal(precomputer.snapshotInputs[0].maxWidthPx, 720);
  assert.equal(precomputer.contractInputs.length, 1);
  assert.equal("maxWidthPx" in precomputer.contractInputs[0], false);
  assert.equal(
    result.html,
    '<p data-tq-snapshot-key="p-0">纯文本。</p><p><a href="/next">链接正文</a></p>',
  );
  assert.deepEqual(result.rootAttributes, {
    "snapshot-ref": "tq-article",
    "data-tiqian-exact-render-font": "true",
  });
  assert.match(result.serverAssets.inertTemplate, /data-tq-entry="p-0"/u);
  assert.match(result.clientBundle.clientTemplate, /font-contract-v1/u);
  assert.match(renderSnapshotServerAssets(result.serverAssets), /data-tq-initial-snapshot="tq-article"/u);
  preparer.close();
});

