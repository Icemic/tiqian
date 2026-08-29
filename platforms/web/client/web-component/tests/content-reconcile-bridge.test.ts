// Unit tests for the content-reconcile engine module behind ts-runtime.
// core/src/engine/content-reconcile.js exports the four named functions;
// these tests call them with raw-dom named exports and drive the
// raw-DOM backup graph directly, so no full runtime boot is required.

import assert from "node:assert/strict";
import test from "node:test";
import { cleanupMounted, mount, probe } from "./runtime-host.js";
import type { FakeElement, FakeNode } from "./snapshot-dom-fixtures.js";
import {
  classifyReconcile,
  prepareTrackedParagraphForRelowering,
  probeContentDrift,
  stripEngineMarkupFromStrandedParagraph,
} from "@tiqian/core/src/engine/content-reconcile.js";
import {
  rawDomBegin,
  rawDomTake,
  rawDomCommit,
  rawDomStampRendered,
  rawDomRenderedMatches,
  rawDomSuspendEngineWrites,
} from "@tiqian/core/src/engine/raw-dom.js";
import { createEnhanceContext } from "@tiqian/core/src/engine/context/enhance-context.js";
import { initializeGlobalServices } from "@tiqian/core/src/services/global-services.js";
initializeGlobalServices();


type AfterHookFn = () => void;
interface TestContextLike {
  after(fn: AfterHookFn): void;
}

// Move a mounted paragraph into the enhanced state on the given context: take
// the host children into the raw-DOM backup, publish the fragment, write one
// engine-owned rendered child (through the engine-write suspension), and
// stamp the rendered output. The rawDom records live on the context, so the
// caller must reuse this same context for probes and preparation.
function enhanceParagraph(context: ReturnType<typeof createEnhanceContext>, paragraph: FakeElement, t: TestContextLike) {
  t.after(cleanupMounted);
  const source = probe<Element>(paragraph);
  rawDomBegin(
    context,
    source,
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
  rawDomTake(context, source, null);
  rawDomCommit(context, source, null);
  let rendered;
  rawDomSuspendEngineWrites(context, source, () => {
    rendered = globalThis.document.createElement("span");
    rendered.textContent = "rendered";
    paragraph.appendChild(probe<FakeNode>(rendered));
  });
  rawDomStampRendered(context, source);
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
  const context = createEnhanceContext(probe<Element>(root.querySelector("p") || globalThis.document.createElement("p")));
  assert.deepEqual(
    probeContentDrift(context, []),
    { unknown: 0, drifted: 0, dead: 0, rawDom: 0 },
  );

  const paragraph = root.querySelector("p")!;
  const rendered = enhanceParagraph(context, paragraph, t);
  assert.ok(rendered);
  paragraph.removeChild(rendered);

  assert.deepEqual(probeContentDrift(context, [probe<Element>(paragraph)]), {
    unknown: 0,
    drifted: 1,
    dead: 0,
    rawDom: 0,
  });

  // A detached tracked source counts as dead.
  const detached = mount(`
    <div data-tiqian-root="true"><p>detached paragraph</p></div>
  `).querySelector("p")!;
  detached.remove();
  assert.deepEqual(probeContentDrift(context, [probe<Element>(detached)]), {
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
  const paragraph = root.querySelector("p")!;
  const context = createEnhanceContext(probe<Element>(paragraph));
  enhanceParagraph(context, paragraph, t);
  const beforeNodes = Array.from(paragraph.childNodes);

  probeContentDrift(context, [probe<Element>(paragraph)]);

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
  const context = createEnhanceContext(globalThis.document.createElement("p"));
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
  const pStranded = root.querySelector("#p-stranded")!;
  const pSkipped = root.querySelector("#p-skipped")!;

  const spec = {
    trackedSources: [],
    tainted: [],
    strandedCandidates: [probe<Element>(pStranded), probe<Element>(pSkipped)],
    rootSelector: "tiqian-prose, [data-tiqian-root]",
  };
  const verdict = classifyReconcile(context, spec);
  assert.equal(verdict.outcome, "work");
  assert.deepEqual(verdict.drifted, []);
  assert.deepEqual(verdict.rawDom, []);
  assert.deepEqual(verdict.tainted, []);
  assert.deepEqual(verdict.stranded, [probe<Element>(pStranded)]);
  assert.equal(verdict.dead, 0);
});

test("contentReconcilePrepare_restoresShellAndStamps", (t) => {
  const root = mount(`
    <div data-tiqian-root="true" style="width: 320px">
      <p style="font-size: 18px; line-height: 30px">prepare relowering paragraph</p>
    </div>
  `);
  const paragraph = root.querySelector("p")!;
  const context = createEnhanceContext(probe<Element>(paragraph));
  const rendered = enhanceParagraph(context, paragraph, t);

  assert.ok(paragraph.firstChild);
  paragraph.removeChild(probe<FakeNode>(rendered));
  assert.equal(rawDomRenderedMatches(context, probe<Element>(paragraph)), false);

  prepareTrackedParagraphForRelowering(context, probe<HTMLElement>(paragraph));
  assert.equal(paragraph.getAttribute("data-tq-rendered"), null);
  assert.equal(rawDomRenderedMatches(context, probe<Element>(paragraph)), true);
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
  const clonedParagraph = root.querySelector("p")!;
  assert.ok(clonedParagraph);

  const context = createEnhanceContext(probe<Element>(clonedParagraph));
  stripEngineMarkupFromStrandedParagraph(context, probe<HTMLElement>(clonedParagraph));

  assert.equal(clonedParagraph.querySelectorAll("[data-tq-hard-break]").length, 0);
  assert.ok(clonedParagraph.querySelector("br"), "bare br must exist");
  assert.equal(clonedParagraph.querySelectorAll("[data-tq-copy-ignore]").length, 0);
  assert.equal(clonedParagraph.querySelectorAll("[data-tq-engine-break]").length, 0);
  assert.equal(clonedParagraph.getAttribute("data-tq-rendered"), null);
});