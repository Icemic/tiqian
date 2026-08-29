// Snapshot font session tests ported from TiqianWebSnapshotSessionTest.kt and the
// canonical-fallback / dash-evidence cases in TiqianWebEnhancerTest.kt and
// TiqianWebSourceFidelityTest.kt. Verifies the shared font backend, the
// prepared DOM bridge, per-run browser fallback, worker plan replay and the
// quote feature locks.

import * as assert from "node:assert/strict";
import test from "node:test";
import {
  assertEnginePunctuationFeatureLock,
  cleanupMounted,
  clearSnapshotFontSessionFixture,
  copySelection,
  cssPx,
  computedStyleValue,
  enginePunctuationFeatureStyle,
  snapshotFontFallbackCount,
  snapshotFontShapeCount,
  snapshotTestOptions,
  flushAllTestAnimationFrames,
  dispatchRelayout,
  installSnapshotFontSessionFixture,
  installPreparedWorkerIssue,
  installPreparedWorkerLivePlan,
  installTestAnimationFrames,
  loadHostRuntime,
  mount,
  preparedValueStyleProperty,
  probe,
  testOptions,
} from "./runtime-host.js";

function curlyQuoteCount(text: string): number {
  let count = 0;
  for (const ch of text) {
    if (ch === '\u2018' || ch === '\u2019' || ch === '\u201C' || ch === '\u201D') count += 1;
  }
  return count;
}

function quoteRangeCount(text: string): number {
  let count = 0;
  for (const ch of text) {
    if (ch >= '\u2018' && ch <= '\u201D') count += 1;
  }
  return count;
}

test("snapshotSession_canonicalPreparedParagraphFallsBackIntoRuntimeCleanly", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const source = "甲\u2019乙\n丙";
  const root = mount(`
    <div data-tiqian-root="true" style="width: 180px; font-size: 18px; line-height: 30px">
      <p data-tq-rendered="true" data-tq-canonical-plain="true" data-tq-canonical-source="true"><span data-tq-geometry="true">甲</span><span data-tq-src="\u2019" data-tq-geometry="true">\uFF07</span><br data-tq-engine-break="AutoWrap"><span data-tq-geometry="true">乙</span><span data-tq-src="&#10;" data-tq-hard-break="true"></span><br data-tq-engine-break="MandatoryBreak"><span data-tq-geometry="true">丙</span></p>
    </div>
  `);

  assert.equal(TiqianWeb.enhance(probe<Element>(root), testOptions()), 1);

  const paragraph = root.querySelector("p")!;
  assert.equal(paragraph.getAttribute("data-tiqian-capability-issue"), null);
  assert.equal(copySelection(paragraph), source);
});

test("snapshotSession_canonicalFallbackSamplesHostLineHeightBeforeLowering", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const root = mount(`
    <div data-tiqian-root="true" style="width: 180px">
      <style>
        #prepared-fallback { font-size: 18px; line-height: 30px; white-space: normal; }
        #prepared-fallback[data-tq-rendered="true"][data-tq-canonical-plain="true"] {
          line-height: 0 !important;
          white-space: pre !important;
        }
      </style>
      <p id="prepared-fallback" data-tq-rendered="true" data-tq-canonical-plain="true" data-tq-canonical-source="true"><span data-tq-geometry="true">第一行正文</span><br data-tq-engine-break="AutoWrap"><span data-tq-geometry="true">第二行正文</span></p>
    </div>
  `);

  assert.equal(TiqianWeb.enhance(probe<Element>(root)), 1);

  const paragraph = root.querySelector("#prepared-fallback")!;
  const line = paragraph.querySelector(":scope > .tq-line")!;
  assert.equal(cssPx(preparedValueStyleProperty(line, "--tq-line-height")), 30);
  assert.equal(copySelection(paragraph), "第一行正文第二行正文");
});

test("snapshotSession_conformingSessionShapesViaSharedBackendAndPreparedDomBridge", async (t) => {
  t.after(cleanupMounted);
  t.after(() => clearSnapshotFontSessionFixture());
  const TiqianWeb = await loadHostRuntime();
  installSnapshotFontSessionFixture({ failShaping: false });
  const root = mount(`
    <div data-tiqian-root="true" style="width: 220px">
      ${enginePunctuationFeatureStyle}
      <p data-tq-snapshot-key="plain" style="font-family: 'Fixture CJK'; font-size: 18px; line-height: 30px">中文正文。</p>
    </div>
  `);

  const count = TiqianWeb.enhance(probe<Element>(root), snapshotTestOptions());

  assert.equal(count, 1);
  const paragraph = root.querySelector("p")!;
  assert.equal(paragraph.getAttribute("data-tq-canonical-plain"), "true");
  assert.equal(paragraph.getAttribute("data-tq-canonical-source"), "true");
  assert.equal(paragraph.getAttribute("data-tq-runtime-render-font"), "true");
  assert.equal(paragraph.getAttribute("lang"), "zh-Hans");
  assert.ok(paragraph.querySelector(".tq-line[data-tq-line-flow-width]"));
  assertEnginePunctuationFeatureLock(paragraph);
  const line = paragraph.querySelector(".tq-line")!;
  assert.ok(line, paragraph.innerHTML);
  assert.ok(cssPx(preparedValueStyleProperty(line, "--tq-line-height")) > 0, paragraph.innerHTML);
});

test("snapshotSession_semanticParagraphShapedBeforeRuntimeDomReplay", async (t) => {
  t.after(cleanupMounted);
  t.after(() => clearSnapshotFontSessionFixture());
  const TiqianWeb = await loadHostRuntime();
  installSnapshotFontSessionFixture({ failShaping: false });
  const root = mount(`
    <div data-tiqian-root="true" style="width: 220px">
      <p data-tq-snapshot-key="rich" style="font-family: 'Fixture CJK'; font-size: 18px; line-height: 30px">中文<a href="/more">链接</a>正文。</p>
    </div>
  `);

  const count = TiqianWeb.enhance(probe<Element>(root), snapshotTestOptions());

  assert.equal(count, 1);
  const paragraph = root.querySelector("p")!;
  assert.ok(snapshotFontShapeCount() > 0);
  assert.equal(paragraph.getAttribute("data-tq-canonical-plain"), null);
  assert.ok(paragraph.querySelector("a[href='/more']"));
  assert.ok(paragraph.querySelector(".tq-line"));
  assert.ok(paragraph.querySelector(".tq-line[data-tq-line-flow-width]"));
  assert.equal(copySelection(paragraph), "中文链接正文。");
});

test("snapshotSession_faceEvidenceDoesNotFragmentOrdinaryDomText", async (t) => {
  t.after(cleanupMounted);
  t.after(() => clearSnapshotFontSessionFixture());
  const TiqianWeb = await loadHostRuntime();
  installSnapshotFontSessionFixture({ failShaping: false, varyFaceByText: true });
  const root = mount(`
    <div data-tiqian-root="true" style="width: 700px">
      <p style="font-family: 'Fixture CJK'; font-size: 18px; line-height: 30px">中文正文</p>
    </div>
  `);
  const options = { ...snapshotTestOptions(), paragraphSelector: "p" };

  assert.equal(TiqianWeb.enhance(probe<Element>(root), options), 1);

  const paragraph = root.querySelector("p")!;
  assert.equal(
    paragraph.querySelectorAll(
      ":scope > span[data-tq-geometry]:not(.tq-line):not([data-tq-line-end-sentinel])",
    ).length,
    0,
    `font replay evidence must not create a visible shaping boundary: ${paragraph.innerHTML}`,
  );
  assert.equal(copySelection(paragraph), "中文正文");
});

test("snapshotSession_unsupportedFontRunFallsBackPerRunNotPerParagraph", async (t) => {
  t.after(cleanupMounted);
  t.after(() => clearSnapshotFontSessionFixture());
  const TiqianWeb = await loadHostRuntime();
  installSnapshotFontSessionFixture({ failShaping: false, failFamily: "Fixture Mono" });
  const root = mount(`
    <div data-tiqian-root="true" style="width: 260px">
      <p data-tq-snapshot-key="rich" style="font-family: 'Fixture CJK'; font-size: 18px; line-height: 30px">中文<code style="font-family: 'Fixture Mono'">code42</code>正文。</p>
    </div>
  `);

  assert.equal(TiqianWeb.enhance(probe<Element>(root), snapshotTestOptions()), 1);

  const paragraph = root.querySelector("p")!;
  assert.ok(snapshotFontShapeCount() > 0);
  assert.ok(snapshotFontFallbackCount() > 0);
  assert.equal(paragraph.getAttribute("data-tq-canonical-plain"), null);
  assert.equal(paragraph.getAttribute("data-tiqian-capability-issue"), null);
  assert.ok(paragraph.querySelector("code"));
  assert.equal(copySelection(paragraph), "中文code42正文。");
});

test("snapshotSession_workerReplayMissFallsBackOnlyForRichRun", async (t) => {
  t.after(cleanupMounted);
  t.after(() => clearSnapshotFontSessionFixture());
  const TiqianWeb = await loadHostRuntime();
  installSnapshotFontSessionFixture({ failShaping: false, failFamily: "Fixture Mono" });
  installPreparedWorkerIssue("MissingServerShapingReplay:test");
  const root = mount(`
    <div data-tiqian-root="true" style="width: 260px">
      <p data-tq-snapshot-key="rich" style="font-family: 'Fixture CJK'; font-size: 18px; line-height: 30px">中文<code style="font-family: 'Fixture Mono'">code42</code>正文。</p>
    </div>
  `);

  assert.equal(
    TiqianWeb.enhance(probe<Element>(root), { ...snapshotTestOptions(), requireSnapshotLayoutWorker: true }),
    1,
  );

  const paragraph = root.querySelector("p")!;
  assert.ok(snapshotFontFallbackCount() > 0);
  assert.equal(paragraph.getAttribute("data-tq-rendered"), "true");
  assert.equal(paragraph.getAttribute("data-tiqian-capability-issue"), null);
  assert.ok(paragraph.querySelector("code"));
  assert.equal(copySelection(paragraph), "中文code42正文。");
});

test("snapshotSession_workerPlanReplaysLiveSemanticsFromSourceElements", async (t) => {
  t.after(cleanupMounted);
  t.after(() => clearSnapshotFontSessionFixture());
  const TiqianWeb = await loadHostRuntime();
  installSnapshotFontSessionFixture({ failShaping: false });
  installPreparedWorkerLivePlan();
  const root = mount(`
    <div data-tiqian-root="true" style="width: 260px">
      <p style="font-family: 'Fixture CJK'; font-size: 18px; line-height: 30px">正文<spoiler style="box-decoration-break: slice; padding-left: 4px; padding-right: 4px"><em>秘密</em></spoiler>继续。</p>
    </div>
  `);
  const paragraph = root.querySelector("p")!;
  const enhanced = TiqianWeb.enhance(
    probe<Element>(root),
    {
      ...snapshotTestOptions(),
      paragraphSelector: "p:not([data-tq-snapshot-key])",
      requireSnapshotLayoutWorker: true,
    },
  );
  assert.equal(
    enhanced,
    1,
    `issue=${paragraph.getAttribute("data-tiqian-capability-issue")}; ` +
      `detail=${paragraph.getAttribute("data-tiqian-capability-detail")}; ` +
      `html=${paragraph.innerHTML}`,
  );

  assert.equal(paragraph.getAttribute("data-tq-rendered"), "true");
  assert.equal(paragraph.getAttribute("data-tiqian-capability-issue"), null);
  assert.ok(
    paragraph.querySelector("spoiler[data-tq-source-semantic] > em[data-tq-source-semantic]"),
  );
  assert.equal(snapshotFontShapeCount(), 0, "live semantics must not relayout on the main thread");
  assert.equal(paragraph.querySelectorAll("spoiler[data-tq-source-semantic]").length, 1, paragraph.innerHTML);
  assert.equal(paragraph.querySelectorAll("em[data-tq-source-semantic]").length, 1, paragraph.innerHTML);
  assert.equal(copySelection(paragraph), "正文秘密继续。");
});

test("snapshotSession_unkeyedCompletionFailsClosedWhenDashNonConforming", async (t) => {
  t.after(cleanupMounted);
  t.after(() => clearSnapshotFontSessionFixture());
  const TiqianWeb = await loadHostRuntime();
  installSnapshotFontSessionFixture({ failShaping: false, failText: "坏" });
  const root = mount(`
    <div data-tiqian-root="true" style="width: 260px">
      <p style="font-family: 'Fixture CJK'; font-size: 18px; line-height: 30px">坏——正文。</p>
    </div>
  `);
  const options = {
    ...snapshotTestOptions(),
    paragraphSelector: "p:not([data-tq-snapshot-key])",
    cjkDashCapability: {
      status: "unavailable",
      detail: "ServerShapingReplayRequired",
    },
  };

  // Since the Slice 4a/4d-2b decisions (ADR 0053) an unkeyed rich paragraph
  // whose snapshot session fails one run retries the whole paragraph with
  // browser metrics; the dash run then fails closed when the dash capability
  // is non-conforming, and the paragraph stays native.
  assert.equal(TiqianWeb.enhance(probe<Element>(root), options), 0);

  const paragraph = root.querySelector("p")!;
  assert.ok(snapshotFontFallbackCount() > 0);
  assert.equal(paragraph.getAttribute("data-tq-canonical-plain"), null);
  assert.equal(paragraph.getAttribute("data-tq-rendered"), null);
  assert.equal(paragraph.querySelector(".tq-line"), null);
  assert.equal(
    paragraph.getAttribute("data-tiqian-capability-issue"),
    "NoConformingCjkDashGlyph",
  );
  assert.equal(paragraph.innerHTML, "坏——正文。");
});

test("snapshotSession_fallbackParagraphUsesBrowserLineMetrics", async (t) => {
  t.after(cleanupMounted);
  t.after(() => clearSnapshotFontSessionFixture());
  const TiqianWeb = await loadHostRuntime();
  installSnapshotFontSessionFixture({ failShaping: false, failText: "a" });
  const root = mount(`
    <div data-tiqian-root="true" style="width: 300px">
      <p data-tq-snapshot-key="exact" style="font-family: 'Fixture CJK'; font-size: 18px; line-height: 30px">中文<a href="/more">链接</a>正文。</p>
      <p data-tq-snapshot-key="fallback" style="font-family: 'Fixture CJK'; font-size: 18px; line-height: 30px">… and <a href="/more">more</a>.</p>
    </div>
  `);

  assert.equal(TiqianWeb.enhance(probe<Element>(root), snapshotTestOptions()), 2);

  const paragraphs = root.querySelectorAll("p");
  const snapshotParagraph = paragraphs[0];
  const fallbackParagraph = paragraphs[1];
  const snapshotLine = snapshotParagraph.querySelector(".tq-line")!;
  const fallbackLine = fallbackParagraph.querySelector(".tq-line")!;
  assert.ok(snapshotFontFallbackCount() > 0);
  // The declared line height stays shared; since the Slice 4a whole-paragraph
  // browser retry (ADR 0053) the fallback paragraph's baseline metrics come
  // from the browser side, so they no longer claim the snapshot session's.
  assert.equal(
    preparedValueStyleProperty(snapshotLine, "--tq-line-height"),
    preparedValueStyleProperty(fallbackLine, "--tq-line-height"),
  );
  assert.notEqual(
    preparedValueStyleProperty(snapshotLine, "--tq-line-baseline-offset"),
    preparedValueStyleProperty(fallbackLine, "--tq-line-baseline-offset"),
  );
  assert.ok(
    preparedValueStyleProperty(fallbackLine, "--tq-line-baseline-offset").length > 0,
  );
});

test("snapshotSession_browserFallbackCarriesLatinQuoteFeaturesIntoPlan", async (t) => {
  t.after(cleanupMounted);
  t.after(() => clearSnapshotFontSessionFixture());
  const TiqianWeb = await loadHostRuntime();
  installSnapshotFontSessionFixture({ failShaping: false });
  const root = mount(`
    <div data-tiqian-root="true" style="width: 220px">
      <p data-tq-snapshot-key="plain" style="font-family: 'Fixture CJK'; font-size: 18px; line-height: 30px">that\u2019s James\u2019 \u201990s</p>
    </div>
  `);

  const count = TiqianWeb.enhance(probe<Element>(root), snapshotTestOptions());

  assert.equal(count, 1);
  const paragraph = root.querySelector("p")!;
  const featureRuns = paragraph.querySelectorAll(
    "span[data-tq-open-type-features='pwid,palt']",
  );
  assert.ok(featureRuns.length > 0, paragraph.innerHTML);
  assert.ok(
    Array.from(featureRuns).some((run) => run.textContent.includes('\u2019')),
    paragraph.innerHTML,
  );
});

test("snapshotSession_browserFallbackMeasuresAndReplaysLatinCurlyQuoteFeatures", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const source = "that\u2019s\uFF1B\uFF08如 \u2018O\u2019, \u2018Q\u2019\uFF09";
  const root = mount(`
    <div data-tiqian-root="true" style="width: 500px">
      ${enginePunctuationFeatureStyle}
      <p>${source}</p>
    </div>
  `);

  assert.equal(TiqianWeb.enhance(probe<Element>(root), testOptions()), 1);

  const paragraph = root.querySelector("p")!;
  const featureRuns = paragraph.querySelectorAll(
    "span[data-tq-open-type-features='pwid,palt']",
  );
  assert.equal(featureRuns.length, 3, paragraph.innerHTML);
  let quotedCodePoints = 0;
  for (const run of featureRuns) {
    assertEnginePunctuationFeatureLock(run, true);
    quotedCodePoints += quoteRangeCount(run.textContent);
  }
  assert.equal(quotedCodePoints, 5, paragraph.innerHTML);
  assert.equal(copySelection(paragraph), source);
});

test("snapshotSession_quoteContextMatrixReplaysOnlyLatinQuoteFeatures", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const cases = [
    { source: "中\u201C文\u201D中", proportionalQuoteCount: 0 },
    { source: "便延伸出了\u201C乃子\u201D\u201C大波\u201D\u201C大灯\u201D\u201C大雷\u201D\u201C大扎\u201D\u201C对A\u201D\u201C波霸\u201D这些词", proportionalQuoteCount: 0 },
    { source: "这些太直白了是吧， \u201C欧派\u201D\u201Cdouble\u201D\u201Cdouble may\u201D呢", proportionalQuoteCount: 0 },
    { source: "\u201CHello\u201D", proportionalQuoteCount: 2 },
    { source: "that\u2019s James\u2019 \u201990s", proportionalQuoteCount: 3 },
    { source: "中文 \u2018don\u2019t\u2019", proportionalQuoteCount: 3 },
    { source: "他说：\u201CShe said \u2018hello\u2019.\u201D", proportionalQuoteCount: 2 },
    { source: "中文 \u2018don\u2019t\u2019", html: "中文 <strong>\u2018don\u2019t\u2019</strong>", proportionalQuoteCount: 3 },
  ];
  const root = mount(
    "<div data-tiqian-root='true' style='width: 520px'>" +
      cases.map((testCase) => `<p>${testCase.html ?? testCase.source}</p>`).join("") +
      "</div>",
  );

  TiqianWeb.install();
  assert.equal(TiqianWeb.enhance(probe<Element>(root), testOptions()), cases.length);

  const assertCases = () => {
    const paragraphs = root.querySelectorAll("p");
    for (const [index, testCase] of Array.from(cases.entries())) {
      const paragraph = paragraphs[index]!;
      const featureRuns = paragraph.querySelectorAll(
        "span[data-tq-open-type-features='pwid,palt']",
      );
      let actualQuoteCount = 0;
      for (const run of featureRuns) {
        actualQuoteCount += curlyQuoteCount(run.textContent);
      }
      assert.equal(testCase.proportionalQuoteCount, actualQuoteCount, testCase.source);
      assert.equal(copySelection(paragraph), testCase.source, testCase.source);
    }
  };
  assertCases();

  installTestAnimationFrames();
  root.style.setProperty("width", "180px");
  dispatchRelayout(root);
  flushAllTestAnimationFrames();
  assertCases();
});

test("snapshotSession_unavailableFaceFallsBackToBrowserPipeline", async (t) => {
  t.after(cleanupMounted);
  t.after(() => clearSnapshotFontSessionFixture());
  const TiqianWeb = await loadHostRuntime();
  installSnapshotFontSessionFixture({ failShaping: true });
  const root = mount(`
    <div data-tiqian-root="true" style="width: 220px">
      <p data-tq-snapshot-key="plain" style="font-size: 18px; line-height: 30px">中文正文。</p>
    </div>
  `);

  const count = TiqianWeb.enhance(probe<Element>(root), snapshotTestOptions());

  assert.equal(count, 1);
  const paragraph = root.querySelector("p")!;
  assert.equal(paragraph.getAttribute("data-tq-canonical-plain"), "true");
  assert.equal(paragraph.getAttribute("data-tq-canonical-source"), "true");
  assert.ok(paragraph.querySelector(".tq-line"));
  assert.ok(paragraph.querySelector(".tq-line[data-tq-line-flow-width]"));
});

test("snapshotSession_layoutOptionOverrideCannotReuseSnapshotSession", async (t) => {
  t.after(cleanupMounted);
  t.after(() => clearSnapshotFontSessionFixture());
  const TiqianWeb = await loadHostRuntime();
  installSnapshotFontSessionFixture({ failShaping: false });
  const root = mount(`
    <div data-tiqian-root="true" style="width: 220px">
      <p data-tq-snapshot-key="plain" style="font-family: 'Fixture CJK'; font-size: 18px; line-height: 30px">中文正文。</p>
    </div>
  `);

  const count = TiqianWeb.enhance(probe<Element>(root), { ...snapshotTestOptions(), fontSize: 24 });

  assert.equal(count, 1);
  const paragraph = root.querySelector("p")!;
  assert.equal(paragraph.getAttribute("data-tq-canonical-plain"), "true");
  assert.ok(paragraph.querySelector(".tq-line[data-tq-line-flow-width]"));
  assert.ok(paragraph.querySelector(".tq-line"));
});

test("snapshotSession_dashParagraphNativeWithoutVerifiableFontSource", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const root = mount(`
    <div data-tiqian-root="true" style="width: 420px">
      <p style="font-family: Arial, sans-serif">中文——中文。</p>
    </div>
  `);

  assert.equal(TiqianWeb.enhance(probe<Element>(root), testOptions()), 0);

  const paragraph = root.querySelector("p")!;
  assert.ok(paragraph.textContent.includes("中文——中文。"));
  assert.ok(!paragraph.textContent.includes('\u2E3A'));
  assert.equal(
    paragraph.getAttribute("data-tiqian-capability-issue"),
    "NoConformingCjkDashGlyph",
  );
  assert.equal(paragraph.getAttribute("data-tq-rendered"), null);
  assert.equal(copySelection(paragraph), "中文——中文。");
});

test("snapshotSession_conformingDashEvidenceWithoutSnapshotSessionReportsMissingCapability", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const root = mount(
    "<div data-tiqian-root='true'><p>中文——中文。</p></div>",
  );
  const options = {
    ...testOptions(),
    cjkDashCapability: {
      status: "conforming",
      detail: "FixtureDashFace",
    },
  };

  assert.equal(TiqianWeb.enhance(probe<Element>(root), options), 0);

  const paragraph = root.querySelector("p")!;
  assert.equal(
    paragraph.getAttribute("data-tiqian-capability-issue"),
    "ConformingCjkDashRequiresSnapshotFontSession",
  );
  assert.ok(
    (paragraph.getAttribute("data-tiqian-capability-detail") || "").includes(
      "status=conforming",
    ),
  );
  assert.equal(paragraph.getAttribute("data-tq-rendered"), null);
});
