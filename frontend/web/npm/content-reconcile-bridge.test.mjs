// Unit tests for the content-reconcile engine module behind ts-runtime.
// npm-core/core/engine/content-reconcile.js exports the four named functions;
// these tests call them with a real deriveRawDom() instance and drive the
// raw-DOM backup graph directly, so no full runtime boot is required.

import assert from "node:assert/strict";
import test from "node:test";
import { cleanupMounted, mount } from "./runtime-host.mjs";
import {
  classifyReconcile,
  prepareTrackedParagraphForRelowering,
  probeContentDrift,
  stripEngineMarkupFromStrandedParagraph,
} from "@tiqian/prose-core/core/engine/content-reconcile.js";
import { deriveRawDom } from "@tiqian/prose-core/core/engine/raw-dom.js";

const rawDom = deriveRawDom();

// Move a mounted paragraph into the enhanced state: take the host children
// into the raw-DOM backup, publish the fragment, write one engine-owned rendered child
// (through the engine-write suspension), and stamp the rendered output.
function enhanceParagraph(paragraph, t) {
  t.after(cleanupMounted);
  rawDom.begin(
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
  rawDom.take(paragraph, null);
  rawDom.commit(paragraph, null);
  let rendered;
  rawDom.suspendEngineWrites(paragraph, () => {
    rendered = globalThis.document.createElement("span");
    rendered.textContent = "rendered";
    paragraph.appendChild(rendered);
  });
  rawDom.stampRendered(paragraph);
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
  assert.equal(
    probeContentDrift(rawDom,[]),
    '{"unknown":0,"drifted":0,"dead":0,"rawDom":0}',
  );

  const paragraph = root.querySelector("p");
  const rendered = enhanceParagraph(paragraph, t);
  assert.ok(rendered);
  paragraph.removeChild(rendered);

  const result = JSON.parse(probeContentDrift(rawDom,[paragraph]));
  assert.deepEqual(result, {
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
  const deadResult = JSON.parse(probeContentDrift(rawDom,[detached]));
  assert.deepEqual(deadResult, {
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

  probeContentDrift(rawDom,[paragraph]);

  const afterNodes = Array.from(paragraph.childNodes);
  assert.equal(afterNodes.length, beforeNodes.length);
  for (let i = 0; i < beforeNodes.length; i++) {
    assert.equal(afterNodes[i], beforeNodes[i]);
  }
});

test("contentReconcileClassify_jsonVerdicts", (t) => {
  t.after(cleanupMounted);

  const emptySpec = {
    trackedSources: [],
    tainted: [],
    strandedCandidates: [],
    rootSelector: "tiqian-prose, [data-tiqian-root]",
  };
  const emptyVerdict = classifyReconcile(rawDom,emptySpec);
  assert.equal(emptyVerdict.outcome, "idle");
  assert.deepEqual(emptyVerdict.drifted, []);
  assert.deepEqual(emptyVerdict.rawDom, []);
  assert.deepEqual(emptyVerdict.tainted, []);
  assert.deepEqual(emptyVerdict.stranded, []);
  assert.equal(emptyVerdict.dead, 0);
  assert.equal(
    emptyVerdict.json,
    '{"outcome":"idle","drifted":0,"rawDom":0,"tainted":0,"stranded":0,"dead":0}',
  );
  assert.deepEqual(JSON.parse(emptyVerdict.json), {
    outcome: "idle",
    drifted: 0,
    rawDom: 0,
    tainted: 0,
    stranded: 0,
    dead: 0,
  });

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
  const verdict = classifyReconcile(rawDom,spec);
  assert.equal(verdict.outcome, "work");
  assert.deepEqual(verdict.drifted, []);
  assert.deepEqual(verdict.rawDom, []);
  assert.deepEqual(verdict.tainted, []);
  assert.deepEqual(verdict.stranded, [pStranded]);
  assert.equal(verdict.dead, 0);
  assert.equal(
    verdict.json,
    '{"outcome":"work","drifted":0,"rawDom":0,"tainted":0,"stranded":1,"dead":0}',
  );
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
  assert.equal(rawDom.renderedMatches(paragraph), false);

  prepareTrackedParagraphForRelowering(rawDom,paragraph);
  assert.equal(paragraph.getAttribute("data-tq-rendered"), null);
  assert.equal(rawDom.renderedMatches(paragraph), true);
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

  stripEngineMarkupFromStrandedParagraph(rawDom,clonedParagraph);

  assert.equal(clonedParagraph.querySelectorAll("[data-tq-hard-break]").length, 0);
  assert.ok(clonedParagraph.querySelector("br"), "bare br must exist");
  assert.equal(clonedParagraph.querySelectorAll("[data-tq-copy-ignore]").length, 0);
  assert.equal(clonedParagraph.querySelectorAll("[data-tq-engine-break]").length, 0);
  assert.equal(clonedParagraph.getAttribute("data-tq-rendered"), null);
});