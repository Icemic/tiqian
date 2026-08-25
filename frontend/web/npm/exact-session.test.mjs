// Exact font session tests ported from TiqianWebExactSessionTest.kt and the
// canonical-fallback / dash-evidence cases in TiqianWebEnhancerTest.kt and
// TiqianWebSourceFidelityTest.kt. Verifies the shared font backend, the
// prepared DOM bridge, per-run browser fallback, worker plan replay and the
// quote feature locks.

import assert from "node:assert/strict";
import test from "node:test";
import {
  assertEnginePunctuationFeatureLock,
  cleanupMounted,
  clearExactFontSessionFixture,
  copySelection,
  cssPx,
  computedStyleValue,
  enginePunctuationFeatureStyle,
  exactFontFallbackCount,
  exactFontShapeCount,
  exactPreparedPlan,
  exactPreparedRenderCount,
  exactTestOptions,
  failExactPreparedDomValidation,
  failNextExactPreparedDomValidation,
  flushAllTestAnimationFrames,
  dispatchRelayout,
  installExactFontSessionFixture,
  installPreparedWorkerIssue,
  installPreparedWorkerLivePlan,
  installTestAnimationFrames,
  loadHostRuntime,
  mount,
  testOptions,
} from "./runtime-host.mjs";

function curlyQuoteCount(text) {
  let count = 0;
  for (const ch of text) {
    if (ch === "‘" || ch === "’" || ch === "“" || ch === "”") count += 1;
  }
  return count;
}

function quoteRangeCount(text) {
  let count = 0;
  for (const ch of text) {
    if (ch >= "‘" && ch <= "”") count += 1;
  }
  return count;
}

test("exactSession_canonicalPreparedParagraphFallsBackIntoRuntimeCleanly", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const source = "甲’乙\n丙";
  const root = mount(`
    <div data-tiqian-root="true" style="width: 180px; font-size: 18px; line-height: 30px">
      <p data-tq-rendered="true" data-tq-canonical-plain="true" data-tq-canonical-source="true"><span data-tq-geometry="true">甲</span><span data-tq-src="’" data-tq-geometry="true">＇</span><br data-tq-engine-break="AutoWrap"><span data-tq-geometry="true">乙</span><span data-tq-src="&#10;" data-tq-hard-break="true"></span><br data-tq-engine-break="MandatoryBreak"><span data-tq-geometry="true">丙</span></p>
    </div>
  `);

  assert.equal(TiqianWeb.enhance(root, testOptions()), 1);

  const paragraph = root.querySelector("p");
  assert.equal(paragraph.getAttribute("data-tiqian-capability-issue"), null);
  assert.equal(copySelection(paragraph), source);
});

test("exactSession_canonicalFallbackSamplesHostLineHeightBeforeLowering", async (t) => {
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

  assert.equal(TiqianWeb.enhance(root), 1);

  const paragraph = root.querySelector("#prepared-fallback");
  const line = paragraph.querySelector(":scope > .tq-line");
  assert.equal(cssPx(line.style.getPropertyValue("--tq-line-height")), 30);
  assert.equal(copySelection(paragraph), "第一行正文第二行正文");
});

test("exactSession_conformingSessionShapesViaSharedBackendAndPreparedDomBridge", async (t) => {
  t.after(cleanupMounted);
  t.after(() => clearExactFontSessionFixture());
  const TiqianWeb = await loadHostRuntime();
  installExactFontSessionFixture({ failShaping: false });
  const root = mount(`
    <div data-tiqian-root="true" style="width: 220px">
      ${enginePunctuationFeatureStyle}
      <p data-tq-snapshot-key="plain" style="font-family: 'Fixture CJK'; font-size: 18px; line-height: 30px">中文正文。</p>
    </div>
  `);

  const count = TiqianWeb.enhance(root, exactTestOptions());

  assert.equal(count, 1);
  const paragraph = root.querySelector("p");
  assert.equal(paragraph.getAttribute("data-tq-canonical-plain"), "true");
  assert.equal(paragraph.getAttribute("data-tq-canonical-source"), "true");
  assert.equal(paragraph.getAttribute("data-tq-runtime-render-font"), "true");
  assert.equal(paragraph.getAttribute("lang"), "zh-Hans");
  assert.ok(paragraph.querySelector("[data-tq-exact-rendered]"));
  assertEnginePunctuationFeatureLock(paragraph);
  assert.ok(exactPreparedPlan().includes('"layoutRevision":"tiqian-layout-v2"'));
  assert.ok(exactPreparedPlan().includes('"height":'));
});

test("exactSession_semanticParagraphShapedBeforeRuntimeDomReplay", async (t) => {
  t.after(cleanupMounted);
  t.after(() => clearExactFontSessionFixture());
  const TiqianWeb = await loadHostRuntime();
  installExactFontSessionFixture({ failShaping: false });
  const root = mount(`
    <div data-tiqian-root="true" style="width: 220px">
      <p data-tq-snapshot-key="rich" style="font-family: 'Fixture CJK'; font-size: 18px; line-height: 30px">中文<a href="/more">链接</a>正文。</p>
    </div>
  `);

  const count = TiqianWeb.enhance(root, exactTestOptions());

  assert.equal(count, 1);
  const paragraph = root.querySelector("p");
  assert.ok(exactFontShapeCount() > 0);
  assert.equal(paragraph.getAttribute("data-tq-canonical-plain"), null);
  assert.ok(paragraph.querySelector("a[href='/more']"));
  assert.ok(paragraph.querySelector(".tq-line"));
  assert.ok(paragraph.querySelector("[data-tq-exact-rendered]"));
  assert.equal(copySelection(paragraph), "中文链接正文。");
});

test("exactSession_faceEvidenceDoesNotFragmentOrdinaryDomText", async (t) => {
  t.after(cleanupMounted);
  t.after(() => clearExactFontSessionFixture());
  const TiqianWeb = await loadHostRuntime();
  installExactFontSessionFixture({ failShaping: false, varyFaceByText: true });
  const root = mount(`
    <div data-tiqian-root="true" style="width: 700px">
      <p style="font-family: 'Fixture CJK'; font-size: 18px; line-height: 30px">中文正文</p>
    </div>
  `);
  const options = { ...exactTestOptions(), paragraphSelector: "p" };

  assert.equal(TiqianWeb.enhance(root, options), 1);

  const paragraph = root.querySelector("p");
  assert.equal(
    paragraph.querySelectorAll(
      ":scope > span[data-tq-geometry]:not(.tq-line):not([data-tq-line-end-sentinel])",
    ).length,
    0,
    `font replay evidence must not create a visible shaping boundary: ${paragraph.innerHTML}`,
  );
  assert.equal(copySelection(paragraph), "中文正文");
});

test("exactSession_unsupportedFontRunFallsBackPerRunNotPerParagraph", async (t) => {
  t.after(cleanupMounted);
  t.after(() => clearExactFontSessionFixture());
  const TiqianWeb = await loadHostRuntime();
  installExactFontSessionFixture({ failShaping: false, failFamily: "Fixture Mono" });
  const root = mount(`
    <div data-tiqian-root="true" style="width: 260px">
      <p data-tq-snapshot-key="rich" style="font-family: 'Fixture CJK'; font-size: 18px; line-height: 30px">中文<code style="font-family: 'Fixture Mono'">code42</code>正文。</p>
    </div>
  `);

  assert.equal(TiqianWeb.enhance(root, exactTestOptions()), 1);

  const paragraph = root.querySelector("p");
  assert.ok(exactFontShapeCount() > 0);
  assert.ok(exactFontFallbackCount() > 0);
  assert.equal(paragraph.getAttribute("data-tq-canonical-plain"), null);
  assert.equal(paragraph.getAttribute("data-tiqian-capability-issue"), null);
  assert.ok(paragraph.querySelector("code"));
  assert.equal(copySelection(paragraph), "中文code42正文。");
});

test("exactSession_workerReplayMissFallsBackOnlyForRichRun", async (t) => {
  t.after(cleanupMounted);
  t.after(() => clearExactFontSessionFixture());
  const TiqianWeb = await loadHostRuntime();
  installExactFontSessionFixture({ failShaping: false, failFamily: "Fixture Mono" });
  installPreparedWorkerIssue("MissingServerShapingReplay:test");
  const root = mount(`
    <div data-tiqian-root="true" style="width: 260px">
      <p data-tq-snapshot-key="rich" style="font-family: 'Fixture CJK'; font-size: 18px; line-height: 30px">中文<code style="font-family: 'Fixture Mono'">code42</code>正文。</p>
    </div>
  `);

  assert.equal(
    TiqianWeb.enhance(root, { ...exactTestOptions(), requireExactLayoutWorker: true }),
    1,
  );

  const paragraph = root.querySelector("p");
  assert.ok(exactFontFallbackCount() > 0);
  assert.equal(paragraph.getAttribute("data-tq-rendered"), "true");
  assert.equal(paragraph.getAttribute("data-tiqian-capability-issue"), null);
  assert.ok(paragraph.querySelector("code"));
  assert.equal(copySelection(paragraph), "中文code42正文。");
});

test("exactSession_workerPlanReplaysLiveSemanticsFromSourceElements", async (t) => {
  t.after(cleanupMounted);
  t.after(() => clearExactFontSessionFixture());
  const TiqianWeb = await loadHostRuntime();
  installExactFontSessionFixture({ failShaping: false });
  installPreparedWorkerLivePlan();
  const root = mount(`
    <div data-tiqian-root="true" style="width: 260px">
      <p style="font-family: 'Fixture CJK'; font-size: 18px; line-height: 30px">正文<spoiler style="box-decoration-break: slice; padding-left: 4px; padding-right: 4px"><em>秘密</em></spoiler>继续。</p>
    </div>
  `);
  const paragraph = root.querySelector("p");
  const enhanced = TiqianWeb.enhance(
    root,
    {
      ...exactTestOptions(),
      paragraphSelector: "p:not([data-tq-snapshot-key])",
      requireExactLayoutWorker: true,
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
  assert.equal(exactFontShapeCount(), 0, "live semantics must not relayout on the main thread");
  assert.equal(exactPreparedRenderCount(), 1);
  assert.equal(copySelection(paragraph), "正文秘密继续。");
});

test("exactSession_unkeyedCompletionFailsClosedWhenDashNonConforming", async (t) => {
  t.after(cleanupMounted);
  t.after(() => clearExactFontSessionFixture());
  const TiqianWeb = await loadHostRuntime();
  installExactFontSessionFixture({ failShaping: false, failText: "坏" });
  const root = mount(`
    <div data-tiqian-root="true" style="width: 260px">
      <p style="font-family: 'Fixture CJK'; font-size: 18px; line-height: 30px">坏——正文。</p>
    </div>
  `);
  const options = {
    ...exactTestOptions(),
    paragraphSelector: "p:not([data-tq-snapshot-key])",
    cjkDashCapability: {
      status: "unavailable",
      detail: "ServerShapingReplayRequired",
    },
  };

  // Since the Slice 4a/4d-2b decisions (ADR 0053) an unkeyed rich paragraph
  // whose exact session fails one run retries the whole paragraph with
  // browser metrics; the dash run then fails closed when the dash capability
  // is non-conforming, and the paragraph stays native.
  assert.equal(TiqianWeb.enhance(root, options), 0);

  const paragraph = root.querySelector("p");
  assert.ok(exactFontFallbackCount() > 0);
  assert.equal(paragraph.getAttribute("data-tq-canonical-plain"), null);
  assert.equal(paragraph.getAttribute("data-tq-rendered"), null);
  assert.equal(paragraph.querySelector(".tq-line"), null);
  assert.equal(
    paragraph.getAttribute("data-tiqian-capability-issue"),
    "NoConformingCjkDashGlyph",
  );
  assert.equal(paragraph.innerHTML, "坏——正文。");
});

test("exactSession_fallbackParagraphUsesBrowserLineMetrics", async (t) => {
  t.after(cleanupMounted);
  t.after(() => clearExactFontSessionFixture());
  const TiqianWeb = await loadHostRuntime();
  installExactFontSessionFixture({ failShaping: false, failText: "a" });
  const root = mount(`
    <div data-tiqian-root="true" style="width: 300px">
      <p data-tq-snapshot-key="exact" style="font-family: 'Fixture CJK'; font-size: 18px; line-height: 30px">中文<a href="/more">链接</a>正文。</p>
      <p data-tq-snapshot-key="fallback" style="font-family: 'Fixture CJK'; font-size: 18px; line-height: 30px">… and <a href="/more">more</a>.</p>
    </div>
  `);

  assert.equal(TiqianWeb.enhance(root, exactTestOptions()), 2);

  const paragraphs = root.querySelectorAll("p");
  const exactParagraph = paragraphs[0];
  const fallbackParagraph = paragraphs[1];
  const exactLine = exactParagraph.querySelector(".tq-line");
  const fallbackLine = fallbackParagraph.querySelector(".tq-line");
  assert.ok(exactFontFallbackCount() > 0);
  // The declared line height stays shared; since the Slice 4a whole-paragraph
  // browser retry (ADR 0053) the fallback paragraph's baseline metrics come
  // from the browser lane, so they no longer claim the exact session's.
  assert.equal(
    exactLine.style.getPropertyValue("--tq-line-height"),
    fallbackLine.style.getPropertyValue("--tq-line-height"),
  );
  assert.notEqual(
    exactLine.style.getPropertyValue("--tq-line-baseline-offset"),
    fallbackLine.style.getPropertyValue("--tq-line-baseline-offset"),
  );
  assert.ok(
    fallbackLine.style.getPropertyValue("--tq-line-baseline-offset").length > 0,
  );
});

test("exactSession_browserFallbackCarriesLatinQuoteFeaturesIntoPlan", async (t) => {
  t.after(cleanupMounted);
  t.after(() => clearExactFontSessionFixture());
  const TiqianWeb = await loadHostRuntime();
  installExactFontSessionFixture({ failShaping: false });
  const root = mount(`
    <div data-tiqian-root="true" style="width: 220px">
      <p data-tq-snapshot-key="plain" style="font-family: 'Fixture CJK'; font-size: 18px; line-height: 30px">that’s James’ ’90s</p>
    </div>
  `);

  const count = TiqianWeb.enhance(root, exactTestOptions());

  assert.equal(count, 1);
  assert.ok(
    exactPreparedPlan().includes('"openTypeFeatures":["pwid","palt"]'),
    exactPreparedPlan(),
  );
});

test("exactSession_browserFallbackMeasuresAndReplaysLatinCurlyQuoteFeatures", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const source = "that’s；（如 ‘O’, ‘Q’）";
  const root = mount(`
    <div data-tiqian-root="true" style="width: 500px">
      ${enginePunctuationFeatureStyle}
      <p>${source}</p>
    </div>
  `);

  assert.equal(TiqianWeb.enhance(root, testOptions()), 1);

  const paragraph = root.querySelector("p");
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

test("exactSession_quoteContextMatrixReplaysOnlyLatinQuoteFeatures", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const cases = [
    { source: "中“文”中", proportionalQuoteCount: 0 },
    { source: "便延伸出了“乃子”“大波”“大灯”“大雷”“大扎”“对A”“波霸”这些词", proportionalQuoteCount: 0 },
    { source: "这些太直白了是吧， “欧派”“double”“double may”呢", proportionalQuoteCount: 0 },
    { source: "“Hello”", proportionalQuoteCount: 2 },
    { source: "that’s James’ ’90s", proportionalQuoteCount: 3 },
    { source: "中文 ‘don’t’", proportionalQuoteCount: 3 },
    { source: "他说：“She said ‘hello’.”", proportionalQuoteCount: 2 },
    { source: "中文 ‘don’t’", html: "中文 <strong>‘don’t’</strong>", proportionalQuoteCount: 3 },
  ];
  const root = mount(
    "<div data-tiqian-root='true' style='width: 520px'>" +
      cases.map((testCase) => `<p>${testCase.html ?? testCase.source}</p>`).join("") +
      "</div>",
  );

  TiqianWeb.install();
  assert.equal(TiqianWeb.enhance(root, testOptions()), cases.length);

  const assertCases = () => {
    const paragraphs = root.querySelectorAll("p");
    for (const [index, testCase] of cases.entries()) {
      const paragraph = paragraphs[index];
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
  root.style.width = "180px";
  dispatchRelayout(root);
  flushAllTestAnimationFrames();
  assertCases();
});

test("exactSession_unavailableFaceFallsBackToBrowserPipeline", async (t) => {
  t.after(cleanupMounted);
  t.after(() => clearExactFontSessionFixture());
  const TiqianWeb = await loadHostRuntime();
  installExactFontSessionFixture({ failShaping: true });
  const root = mount(`
    <div data-tiqian-root="true" style="width: 220px">
      <p data-tq-snapshot-key="plain" style="font-size: 18px; line-height: 30px">中文正文。</p>
    </div>
  `);

  const count = TiqianWeb.enhance(root, exactTestOptions());

  assert.equal(count, 1);
  const paragraph = root.querySelector("p");
  assert.equal(paragraph.getAttribute("data-tq-canonical-plain"), "true");
  assert.equal(paragraph.getAttribute("data-tq-canonical-source"), "true");
  assert.ok(paragraph.querySelector(".tq-line"));
  assert.ok(paragraph.querySelector("[data-tq-exact-rendered]"));
});

test("exactSession_standingPreparedDomValidationFailureFailsEveryParagraphClosed", async (t) => {
  t.after(cleanupMounted);
  t.after(() => clearExactFontSessionFixture());
  const TiqianWeb = await loadHostRuntime();
  installExactFontSessionFixture({ failShaping: false });
  failExactPreparedDomValidation("fixture-line-drift");
  const root = mount(`
    <div data-tiqian-root="true" style="width: 220px">
      <p data-tq-snapshot-key="plain" style="font-family: 'Fixture CJK'; font-size: 18px; line-height: 30px">中文正文。</p>
      <p data-tq-snapshot-key="second" style="font-family: 'Fixture CJK'; font-size: 18px; line-height: 30px">第二段正文。</p>
    </div>
  `);
  const paragraph = root.querySelector("p");
  const second = root.querySelector("p[data-tq-snapshot-key='second']");

  const count = TiqianWeb.enhance(root, exactTestOptions());

  // PreparedDomRenderMismatch: the bridge disagrees even with browser-metric
  // output, so both paragraphs fail closed and the raw-DOM backup restores their source.
  assert.equal(count, 0);
  assert.equal(root.getAttribute("data-tiqian-enhanced-count"), "0");
  assert.equal(root.getAttribute("data-tiqian-issue-count"), "2");
  assert.equal(paragraph.getAttribute("data-tq-rendered"), null);
  assert.equal(paragraph.getAttribute("data-tq-canonical-plain"), null);
  assert.equal(paragraph.querySelector(".tq-line"), null);
  assert.equal(paragraph.getAttribute("data-tiqian-capability-issue"), "PreparedDomRenderMismatch");
  assert.equal(second.getAttribute("data-tq-rendered"), null);
  assert.equal(second.querySelector(".tq-line"), null);
  assert.equal(second.getAttribute("data-tiqian-capability-issue"), "PreparedDomRenderMismatch");
  // The first paragraph renders twice (exact session, then the browser-metric
  // retry); the second fails on its only render.
  assert.equal(exactPreparedRenderCount(), 3);
  assert.equal(
    root.getAttribute("data-tiqian-exact-layout-fallback"),
    "fixture-line-drift",
  );
});

test("exactSession_preparedDomMismatchRetriesWithBrowserMetricsThroughThePreparedBridge", async (t) => {
  t.after(cleanupMounted);
  t.after(() => clearExactFontSessionFixture());
  const TiqianWeb = await loadHostRuntime();
  installExactFontSessionFixture({ failShaping: false });
  failNextExactPreparedDomValidation("fixture-line-drift");
  const root = mount(`
    <div data-tiqian-root="true" style="width: 220px">
      <p data-tq-snapshot-key="plain" style="font-family: 'Fixture CJK'; font-size: 18px; line-height: 30px">中文正文。</p>
      <p data-tq-snapshot-key="second" style="font-family: 'Fixture CJK'; font-size: 18px; line-height: 30px">第二段正文。</p>
    </div>
  `);
  const paragraph = root.querySelector("p");
  const second = root.querySelector("p[data-tq-snapshot-key='second']");

  const count = TiqianWeb.enhance(root, exactTestOptions());

  // ExactSessionMetricDistrust: the first replay failed geometry validation
  // against exact-session metrics, so the paragraph re-lays out with browser
  // metrics and replays through the prepared bridge; that render validates.
  assert.equal(count, 2);
  assert.equal(root.getAttribute("data-tiqian-enhanced-count"), "2");
  assert.equal(paragraph.getAttribute("data-tq-rendered"), "true");
  assert.equal(paragraph.getAttribute("data-tq-canonical-plain"), "true");
  assert.ok(paragraph.querySelector(".tq-line"));
  assert.equal(paragraph.getAttribute("data-tiqian-capability-issue"), null);
  assert.equal(second.getAttribute("data-tq-rendered"), "true");
  assert.ok(second.querySelector(".tq-line"));
  assert.equal(second.getAttribute("data-tiqian-capability-issue"), null);
  assert.equal(exactPreparedRenderCount(), 3);
  assert.equal(
    root.getAttribute("data-tiqian-exact-layout-fallback"),
    "fixture-line-drift",
  );
});

test("exactSession_layoutOptionOverrideCannotReuseSnapshotSession", async (t) => {
  t.after(cleanupMounted);
  t.after(() => clearExactFontSessionFixture());
  const TiqianWeb = await loadHostRuntime();
  installExactFontSessionFixture({ failShaping: false });
  const root = mount(`
    <div data-tiqian-root="true" style="width: 220px">
      <p data-tq-snapshot-key="plain" style="font-family: 'Fixture CJK'; font-size: 18px; line-height: 30px">中文正文。</p>
    </div>
  `);

  const count = TiqianWeb.enhance(root, { ...exactTestOptions(), fontSize: 24 });

  assert.equal(count, 1);
  const paragraph = root.querySelector("p");
  assert.equal(paragraph.getAttribute("data-tq-canonical-plain"), "true");
  assert.ok(paragraph.querySelector("[data-tq-exact-rendered]"));
  assert.ok(paragraph.querySelector(".tq-line"));
});

test("exactSession_dashParagraphNativeWithoutVerifiableFontSource", async (t) => {
  t.after(cleanupMounted);
  const TiqianWeb = await loadHostRuntime();
  const root = mount(`
    <div data-tiqian-root="true" style="width: 420px">
      <p style="font-family: Arial, sans-serif">中文——中文。</p>
    </div>
  `);

  assert.equal(TiqianWeb.enhance(root, testOptions()), 0);

  const paragraph = root.querySelector("p");
  assert.ok(paragraph.textContent.includes("中文——中文。"));
  assert.ok(!paragraph.textContent.includes("⸺"));
  assert.equal(
    paragraph.getAttribute("data-tiqian-capability-issue"),
    "NoConformingCjkDashGlyph",
  );
  assert.equal(paragraph.getAttribute("data-tq-rendered"), null);
  assert.equal(copySelection(paragraph), "中文——中文。");
});

test("exactSession_conformingDashEvidenceWithoutExactSessionReportsMissingCapability", async (t) => {
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

  assert.equal(TiqianWeb.enhance(root, options), 0);

  const paragraph = root.querySelector("p");
  assert.equal(
    paragraph.getAttribute("data-tiqian-capability-issue"),
    "ConformingCjkDashRequiresExactFontSession",
  );
  assert.ok(
    (paragraph.getAttribute("data-tiqian-capability-detail") || "").includes(
      "status=conforming",
    ),
  );
  assert.equal(paragraph.getAttribute("data-tq-rendered"), null);
});
