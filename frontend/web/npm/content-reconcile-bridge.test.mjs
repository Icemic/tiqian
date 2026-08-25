// Unit tests for the content-reconcile engine module behind ts-runtime.
// npm-core/core/engine/content-reconcile.js exports the four named functions;
// these tests call them with a real createCustody() instance and drive the
// custody graph directly, so no full runtime boot is required.

import assert from "node:assert/strict";
import test from "node:test";
import { cleanupMounted, mount } from "./runtime-host.mjs";
import {
  classifyReconcile,
  prepareTrackedParagraphForRelowering,
  probeContentDrift,
  stripEngineMarkupFromStrandedParagraph,
} from "@tiqian/prose-core/core/engine/content-reconcile.js";
import { createCustody } from "@tiqian/prose-core/core/engine/custody.js";

const custody = createCustody();
const deps = { custody };

// Move a mounted paragraph into the enhanced state: take the host children
// into custody, publish the fragment, write one engine-owned rendered child
// (through the engine-write suspension), and stamp the rendered output.
function enhanceParagraph(paragraph, t) {
  t.after(cleanupMounted);
  custody.begin(
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
  custody.take(paragraph, null);
  custody.commit(paragraph, null);
  paragraph.__tqCustodyEngineWrites = 1;
  const rendered = globalThis.document.createElement("span");
  rendered.textContent = "rendered";
  paragraph.appendChild(rendered);
  paragraph.__tqCustodyEngineWrites = 0;
  custody.stampRendered(paragraph);
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

test("contentReconcileProbe_countsDeadDriftAndCustody", (t) => {
  const root = mount(`
    <div data-tiqian-root="true" style="width: 320px">
      <p style="font-size: 18px; line-height: 30px">enhanced drift probe</p>
    </div>
  `);
  assert.equal(
    probeContentDrift(deps,[]),
    '{"unknown":0,"drifted":0,"dead":0,"custody":0}',
  );

  const paragraph = root.querySelector("p");
  const rendered = enhanceParagraph(paragraph, t);
  assert.ok(rendered);
  paragraph.removeChild(rendered);

  const result = JSON.parse(probeContentDrift(deps,[paragraph]));
  assert.deepEqual(result, {
    unknown: 0,
    drifted: 1,
    dead: 0,
    custody: 0,
  });

  // A detached tracked source counts as dead.
  const detached = mount(`
    <div data-tiqian-root="true"><p>detached paragraph</p></div>
  `).querySelector("p");
  detached.remove();
  const deadResult = JSON.parse(probeContentDrift(deps,[detached]));
  assert.deepEqual(deadResult, {
    unknown: 0,
    drifted: 0,
    dead: 1,
    custody: 0,
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

  probeContentDrift(deps,[paragraph]);

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
  const emptyVerdict = classifyReconcile(deps,emptySpec);
  assert.equal(emptyVerdict.outcome, "idle");
  assert.deepEqual(emptyVerdict.drifted, []);
  assert.deepEqual(emptyVerdict.custody, []);
  assert.deepEqual(emptyVerdict.tainted, []);
  assert.deepEqual(emptyVerdict.stranded, []);
  assert.equal(emptyVerdict.dead, 0);
  assert.equal(
    emptyVerdict.json,
    '{"outcome":"idle","drifted":0,"custody":0,"tainted":0,"stranded":0,"dead":0}',
  );
  assert.deepEqual(JSON.parse(emptyVerdict.json), {
    outcome: "idle",
    drifted: 0,
    custody: 0,
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
  const verdict = classifyReconcile(deps,spec);
  assert.equal(verdict.outcome, "work");
  assert.deepEqual(verdict.drifted, []);
  assert.deepEqual(verdict.custody, []);
  assert.deepEqual(verdict.tainted, []);
  assert.deepEqual(verdict.stranded, [pStranded]);
  assert.equal(verdict.dead, 0);
  assert.equal(
    verdict.json,
    '{"outcome":"work","drifted":0,"custody":0,"tainted":0,"stranded":1,"dead":0}',
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
  assert.equal(custody.renderedMatches(paragraph), false);

  prepareTrackedParagraphForRelowering(deps,paragraph);
  assert.equal(paragraph.getAttribute("data-tq-rendered"), null);
  assert.equal(custody.renderedMatches(paragraph), true);
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

  stripEngineMarkupFromStrandedParagraph(deps,clonedParagraph);

  assert.equal(clonedParagraph.querySelectorAll("[data-tq-hard-break]").length, 0);
  assert.ok(clonedParagraph.querySelector("br"), "bare br must exist");
  assert.equal(clonedParagraph.querySelectorAll("[data-tq-copy-ignore]").length, 0);
  assert.equal(clonedParagraph.querySelectorAll("[data-tq-engine-break]").length, 0);
  assert.equal(clonedParagraph.getAttribute("data-tq-rendered"), null);
});