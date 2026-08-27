// Unit tests for the content-reconcile engine module behind ts-runtime.
// core/core/engine/content-reconcile.js exports the four named functions;
// these tests call them with raw-dom named exports and drive the
// raw-DOM backup graph directly, so no full runtime boot is required.

import assert from "node:assert/strict";
import test from "node:test";
import { cleanupMounted, mount } from "./runtime-host.mjs";
import {
  classifyReconcile,
  prepareTrackedParagraphForRelowering,
  probeContentDrift,
  stripEngineMarkupFromStrandedParagraph,
} from "@tiqian/core/core/engine/content-reconcile.js";
import {
  rawDomBegin,
  rawDomTake,
  rawDomCommit,
  rawDomStampRendered,
  rawDomRenderedMatches,
  rawDomSuspendEngineWrites,
} from "@tiqian/core/core/engine/raw-dom.js";
import { getOrCreateEnhanceContext } from "@tiqian/core/core/engine/context/enhance-context.js";

// Move a mounted paragraph into the enhanced state: take the host children
// into the raw-DOM backup, publish the fragment, write one engine-owned rendered child
// (through the engine-write suspension), and stamp the rendered output.
function enhanceParagraph(paragraph, t) {
  t.after(cleanupMounted);
  const context = getOrCreateEnhanceContext(paragraph);
  rawDomBegin(
    context,
    paragraph,
    null,
    null,
    null,
    null,
    null,
    null,
    "",
    "",
    "",
    "",
    "",
    "",
    null,
  );
  rawDomTake(context, paragraph, null);
  rawDomCommit(context, paragraph, null);
  let rendered;
  rawDomSuspendEngineWrites(context, paragraph, () => {
    rendered = globalThis.document.createElement("span");
    rendered.textContent = "rendered";
    paragraph.appendChild(rendered);
  });
  rawDomStampRendered(context, paragraph);
  return rendered;
}

test("contentReconcileBridge_constructsFullApiSurface", () => {
  for (const fn of [
    probeContentDrift,
    classifyReconcile,
    prepareTrackedParagraphForRelowering,
    stripEngineMarkupFromStrandedParagraph,
  ]) {
    assert.equal(typeof fn, "function");
  }
});

test("contentReconcileProbe_countsDeadDriftAndRawDom", (t) => {
  const root = mount(`
    <div data-tiqian-root="true" style="width: 320px">
      <p style="font-size: 18px; line-height: 30px">enhanced drift probe</p>
    </div>
  `);
  const context = getOrCreateEnhanceContext(root.querySelector("p") || globalThis.document.createElement("p"));
  assert.deepEqual(
    probeContentDrift(context, []),
    { unknown: 0, drifted: 0, dead: 0, rawDom: 0 },
  );

  const paragraph = root.querySelector("p");
  const rendered = enhanceParagraph(paragraph, t);
  assert.ok(rendered);
  paragraph.removeChild(rendered);

  assert.deepEqual(probeContentDrift(context, [paragraph]), {
    unknown: 0,
    drifted: 1,
    dead: 0,
    rawDom: 0,
  });

  // A detached tracked source counts as dead.
  const detached = mount(`
    <div data-tiqian-root="true"><p>detached paragraph</p></div>
  `).querySelector("p");
  detached.remove();
  assert.deepEqual(probeContentDrift(context, [detached]), {
    unknown: 0,
    drifted: 0,
    dead: 1,
    rawDom: 0,
  });
});

test("contentReconcileProbe_staysReadOnly", (t) => {
  const root = mount(`
    <div data-tiqian-root="true" style="width: 320px">
      <p style="font-size: 18px; line-height: 30px">read-only probe paragraph</p>
    </div>
  `);
  const paragraph = root.querySelector("p");
  enhanceParagraph(paragraph, t);
  const beforeNodes = Array.from(paragraph.childNodes);

  const context = getOrCreateEnhanceContext(paragraph);
  probeContentDrift(context, [paragraph]);

  const afterNodes = Array.from(paragraph.childNodes);
  assert.equal(afterNodes.length, beforeNodes.length);
  for (let i = 0; i < beforeNodes.length; i++) {
    assert.equal(afterNodes[i], beforeNodes[i]);
  }
});

test("contentReconcileClassify_verdicts", (t) => {
  t.after(cleanupMounted);

  const emptySpec = {
    trackedSources: [],
    tainted: [],
    strandedCandidates: [],
    rootSelector: "tiqian-prose, [data-tiqian-root]",
  };
  const context = getOrCreateEnhanceContext(globalThis.document.createElement("p"));
  const emptyVerdict = classifyReconcile(context, emptySpec);
  assert.equal(emptyVerdict.outcome, "idle");
  assert.deepEqual(emptyVerdict.drifted, []);
  assert.deepEqual(emptyVerdict.rawDom, []);
  assert.deepEqual(emptyVerdict.tainted, []);
  assert.deepEqual(emptyVerdict.stranded, []);
  assert.equal(emptyVerdict.dead, 0);

  const root = mount(`
    <div data-tiqian-root="true">
      <p id="p-stranded">stranded candidate</p>
      <p id="p-skipped" data-tiqian-capability-issue="true">skipped candidate</p>
    </div>
  `);
  const pStranded = root.querySelector("#p-stranded");
  const pSkipped = root.querySelector("#p-skipped");

  const spec = {
    trackedSources: [],
    tainted: [],
    strandedCandidates: [pStranded, pSkipped],
    rootSelector: "tiqian-prose, [data-tiqian-root]",
  };
  const verdict = classifyReconcile(context, spec);
  assert.equal(verdict.outcome, "work");
  assert.deepEqual(verdict.drifted, []);
  assert.deepEqual(verdict.rawDom, []);
  assert.deepEqual(verdict.tainted, []);
  assert.deepEqual(verdict.stranded, [pStranded]);
  assert.equal(verdict.dead, 0);
});

test("contentReconcilePrepare_restoresShellAndStamps", (t) => {
  const root = mount(`
    <div data-tiqian-root="true" style="width: 320px">
      <p style="font-size: 18px; line-height: 30px">prepare relowering paragraph</p>
    </div>
  `);
  const paragraph = root.querySelector("p");
  const rendered = enhanceParagraph(paragraph, t);

  assert.ok(paragraph.firstChild);
  paragraph.removeChild(rendered);
  const context = getOrCreateEnhanceContext(paragraph);
  assert.equal(rawDomRenderedMatches(context, paragraph), false);

  prepareTrackedParagraphForRelowering(context, paragraph);
  assert.equal(paragraph.getAttribute("data-tq-rendered"), null);
  assert.equal(rawDomRenderedMatches(context, paragraph), true);
});

test("contentReconcileStrip_removesEngineMarkup", (t) => {
  t.after(cleanupMounted);
  const root = mount(`
    <div data-tiqian-root="true" style="width: 320px">
      <p data-tq-rendered="true" data-tq-canonical-plain="true">
        <span data-tq-hard-break="true" data-tq-src="first">first</span>
        <br data-tq-engine-break="MandatoryBreak">
        <span data-tq-copy-ignore="true">hidden</span>
        second
      </p>
    </div>
  `);
  root.innerHTML = root.innerHTML;
  const clonedParagraph = root.querySelector("p");
  assert.ok(clonedParagraph);

  const context = getOrCreateEnhanceContext(clonedParagraph);
  stripEngineMarkupFromStrandedParagraph(context, clonedParagraph);

  assert.equal(clonedParagraph.querySelectorAll("[data-tq-hard-break]").length, 0);
  assert.ok(clonedParagraph.querySelector("br"), "bare br must exist");
  assert.equal(clonedParagraph.querySelectorAll("[data-tq-copy-ignore]").length, 0);
  assert.equal(clonedParagraph.querySelectorAll("[data-tq-engine-break]").length, 0);
  assert.equal(clonedParagraph.getAttribute("data-tq-rendered"), null);
});