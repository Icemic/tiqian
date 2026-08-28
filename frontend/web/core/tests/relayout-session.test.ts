import assert from "node:assert/strict";
import test from "node:test";

import { openRelayoutSession } from "../core/engine/relayout-session.js";
import type { RelayoutSession } from "../core/engine/relayout-session.js";
import type { PrepareLayoutResult } from "../core/engine/prepare-paragraph-layout.js";
import { createEnhanceContext } from "../core/engine/context/enhance-context.js";
import type { EnhancedElementContext } from "../core/engine/context/enhance-context.js";
import {
  rawDomBegin,
  rawDomCommit,
  rawDomTake,
} from "../core/engine/raw-dom.js";
import { LAYOUT_REVISION, SNAPSHOT_SCHEMA } from "../core/sampler/snapshot/snapshot-schema.js";
import { FakeElement, FakeFragment, FakeNode, FakeText } from "./snapshot-dom-fixtures.mjs";
import { initializeGlobalServices } from "../core/services/global-services.js";
import type { TrackedParagraph } from "../core/engine/enhance/context-state.js";
import type { DiagnosisIssueRecord } from "../core/engine/context/diagnosis-manager.js";
import type { TextSpan, DecorationSpan } from "../core/engine/lowered-paragraph.js";
initializeGlobalServices();

// The relayout session runs for real against the real raw-DOM lifecycle and
// the real prepared-DOM renderer. The former injectable rawDom collaborator
// and renderer/validator globals are dissolved; every observation now reads
// the live element, record, and the context's live paragraph and issue
// arrays.

interface SavedGlobals {
  documentOwn: boolean;
  documentValue: unknown;
  nodeOwn: boolean;
  nodeValue: unknown;
  computedOwn: boolean;
  computedValue: unknown;
}

interface ParagraphLowered {
  text: string;
  textStyle: {
    fontFamilies: string[];
    fontSize: number;
    fontWeight: number;
    italic: boolean;
    baselineShift: number;
    locale: string;
  };
  lineHeight: number;
  spans: TextSpan[];
  decorations: DecorationSpan[];
  inlineBoxes: unknown[];
  inlineObjects: unknown[];
  domInlineObjects: unknown[];
  sourceSpans: unknown[];
  sourceBoundaries: unknown[];
  lineBreakSpans: unknown[];
}

interface TrackedParagraphWithMeasure {
  source: FakeElement & Element;
  lowered: ParagraphLowered;
  lastMeasure: number | null;
}

interface SeedContextOverrides {
  options?: Record<string, unknown>;
  paragraphs?: TrackedParagraphWithMeasure[];
  issues?: DiagnosisIssueRecord[];
}

function withDocument<T>(fn: () => T): T {
  const saved: SavedGlobals = {
    documentOwn: Object.prototype.hasOwnProperty.call(globalThis, "document"),
    documentValue: globalThis.document,
    nodeOwn: Object.prototype.hasOwnProperty.call(globalThis, "Node"),
    nodeValue: globalThis.Node,
    computedOwn: Object.prototype.hasOwnProperty.call(globalThis, "getComputedStyle"),
    computedValue: globalThis.getComputedStyle,
  };
  (globalThis as Record<string, unknown>).document = {
    createDocumentFragment: (): FakeFragment => new FakeFragment(),
  };
  // The rawDom commit forwarding captures Node.prototype's mutation methods
  // as its native layer; the fake node class plays that role.
  (globalThis as Record<string, unknown>).Node = FakeNode;
  // The options ledger's withRootDefaults resolves the inherited font-family
  // through getComputedStyle while seeding the runtime options; answer an
  // empty declaration so resolution falls back to the family defaults.
  (globalThis as Record<string, unknown>).getComputedStyle = (): CSSStyleDeclaration => ({ getPropertyValue: (): string => "" }) as unknown as CSSStyleDeclaration;
  try {
    return fn();
  } finally {
    if (saved.documentOwn) (globalThis as Record<string, unknown>).document = saved.documentValue;
    else delete (globalThis as Record<string, unknown>).document;
    if (saved.nodeOwn) (globalThis as Record<string, unknown>).Node = saved.nodeValue;
    else delete (globalThis as Record<string, unknown>).Node;
    if (saved.computedOwn) (globalThis as Record<string, unknown>).getComputedStyle = saved.computedValue;
    else delete (globalThis as Record<string, unknown>).getComputedStyle;
  }
}

function makeParagraph(overrides: {
  source?: FakeElement & Element;
  lowered?: Partial<ParagraphLowered>;
  lastMeasure?: number | null;
} = {}): TrackedParagraphWithMeasure {
  const source = overrides.source ?? (new FakeElement("p") as FakeElement & Element);
  if (source.childNodes.length === 0) source.appendChild(new FakeText("hello world"));
  const lowered: ParagraphLowered = {
    text: "hello world",
    textStyle: {
      fontFamilies: ["Noto Serif CJK SC"],
      fontSize: 19,
      fontWeight: 400,
      italic: false,
      baselineShift: 0,
      locale: "zh-Hans",
    },
    lineHeight: 28,
    spans: [],
    decorations: [],
    inlineBoxes: [],
    inlineObjects: [],
    domInlineObjects: [],
    sourceSpans: [],
    sourceBoundaries: [],
    lineBreakSpans: [],
    ...(overrides.lowered ?? {}),
  };
  return {
    source,
    lowered,
    lastMeasure: overrides.lastMeasure ?? null,
  };
}

// Registers the paragraph with the context's raw-DOM bookkeeping exactly the
// way process-paragraph does before layout: begin, take the live content into
// the backup fragment, then commit. captureLive/restoreParagraph require this
// record to exist.
function registerParagraph(context: EnhancedElementContext, source: FakeElement & Element): void {
  rawDomBegin(context, source, null, null, null, null, null, null, "", "", "", "", "", "", null);
  rawDomTake(context, source, null);
  rawDomCommit(context, source, null);
}

// Simulates the enhanced steady state: the renderer wrote the prepared DOM
// through innerHTML (bypassing the commit forwarding) and the pipeline
// stamped the rendered marker.
function simulateRendered(source: FakeElement & Element, text: string): void {
  source.innerHTML = text;
  source.setAttribute("data-tq-rendered", "true");
}

// Standard core-neutral seeding: one EnhancedElementContext for the element,
// the resolved engine options on the context state (the relayout session
// reads them there), and an established typography runtime, exactly the
// driver order. The seeded paragraphs and issues are the context's own live
// arrays the session splices and pushes by reference.
function seedContext(element: FakeElement & Element, overrides: SeedContextOverrides = {}): EnhancedElementContext {
  const context = createEnhanceContext(element);
  const resolved = context.optionsLedger.resolveEngineOptions(element, overrides.options ?? { fontSize: 19 });
  context.contextState.setRuntimeOptions(resolved);
  context.typography.establishRuntime(element, resolved);
  for (const paragraph of overrides.paragraphs ?? []) {
    context.contextState.paragraphs.push(paragraph as unknown as TrackedParagraph);
  }
  for (const issue of overrides.issues ?? []) {
    context.diagnosis.issues.push(issue);
  }
  return context;
}

// The minimal schema-conforming plan: the real renderer accepts it and
// renders no lines, keeping the session contract under test.
const EMPTY_PLAN_JSON = JSON.stringify({
  schema: SNAPSHOT_SCHEMA,
  layoutRevision: LAYOUT_REVISION,
  height: 0,
  lines: [],
});

test("1. unchanged verdict: no rawDom consequence, no state change", () => {
  withDocument(() => {
    const p1 = makeParagraph({ lastMeasure: 100 });
    const context = seedContext(p1.source, { paragraphs: [p1] });
    registerParagraph(context, p1.source);
    // The unchanged verdict must leave the element exactly as registration
    // left it; capture the baseline after take/commit.
    const originalText = p1.source.textContent;
    const active = openRelayoutSession(context);

    active.processItem(0, { kind: "unchanged" });
    active.finish();

    assert.equal(p1.source.textContent, originalText);
    assert.equal(p1.source.getAttribute("data-tq-rendered"), null);
    assert.equal(context.contextState.paragraphs.length, 1);
    assert.equal(context.contextState.paragraphs[0], p1);
    assert.equal(context.diagnosis.issues.length, 0);
    assert.equal(p1.lastMeasure, 100);
  });
});

test("2. unsupported verdict: live content captured and restored, finish() removes the paragraph from state.paragraphs, pushes the issue, reports it", () => {
  withDocument(() => {
    const p1 = makeParagraph({ lastMeasure: 120 });
    const originalText = p1.source.textContent;
    const context = seedContext(p1.source, { paragraphs: [p1] });
    registerParagraph(context, p1.source);

    const active = openRelayoutSession(context);

    const unsupportedVerdict = {
      kind: "unsupported" as const,
      name: "UnsupportedStyle",
      detail: "font size too large",
    };

    active.processItem(0, unsupportedVerdict as PrepareLayoutResult);

    // captureLive moved the live content into the backup snapshot and
    // restoreParagraph immediately put the original content back.
    assert.equal(p1.source.textContent, originalText);

    active.finish();

    assert.equal(context.contextState.paragraphs.length, 0);
    assert.equal(context.diagnosis.issues.length, 1);
    const issue = context.diagnosis.issues[0];
    assert.equal(issue.name, "UnsupportedStyle");
    assert.equal(issue.detail, "font size too large");
    assert.equal(issue.element, p1.source);
    assert.equal(issue.reportToConsole, true);
    // The lifecycle marker was written onto the source element.
    assert.equal(p1.source.getAttribute("data-tiqian-capability-issue"), "UnsupportedStyle");
    assert.equal(p1.source.getAttribute("data-tiqian-capability-detail"), "font size too large");
  });
});

test("3. ready + commit success: lastMeasure copies preparation.measure, the paragraph is stamped rendered, item stays in state.paragraphs", () => {
  withDocument(() => {
    const p1 = makeParagraph({ lastMeasure: 100 });
    const context = seedContext(p1.source, { paragraphs: [p1] });
    registerParagraph(context, p1.source);

    const active = openRelayoutSession(context);

    const preparation = { kind: "ready" as const, planJson: EMPTY_PLAN_JSON, measure: 250, width: 300 };
    active.processItem(0, preparation as PrepareLayoutResult);

    assert.equal(p1.lastMeasure, preparation.measure);
    // The real commit rendered the empty plan and stamped the paragraph. The
    // rendered attribute itself is written by the enhance pipeline before the
    // session runs; the commit stamps the rawDom record only.
    assert.equal(p1.source.getAttribute("data-tq-canonical-source"), "true");
    assert.equal(p1.source.getAttribute("data-tq-canonical-plain"), "true");
    assert.equal(p1.source.getAttribute("lang"), "zh-Hans");
    assert.equal(context.rawDomParagraphs.get(p1.source)?.engineWriteDepth, 0);

    active.finish();

    assert.equal(p1.lastMeasure, preparation.measure);
    assert.equal(context.contextState.paragraphs.length, 1);
    assert.equal(context.contextState.paragraphs[0], p1);
    assert.equal(context.diagnosis.issues.length, 0);
  });
});

test("4. ready + commit failure: capture precedes the render, the failure propagates, rollback restores the captured live state", () => {
  withDocument(() => {
    const p1 = makeParagraph({ lastMeasure: 110 });
    const context = seedContext(p1.source, { paragraphs: [p1] });
    registerParagraph(context, p1.source);
    simulateRendered(p1.source, "rendered v1");

    const active = openRelayoutSession(context);

    // A plan without the schema envelope makes the real renderer throw; the
    // session catches nothing, so the driver-level failure contract applies.
    assert.throws(
      () => active.processItem(0, { kind: "ready" as const, planJson: "{}", measure: 250, width: 300 } as PrepareLayoutResult),
      { message: "UnsupportedPreparedLayoutRevision" },
    );

    // captureLive moved the live rendered content into the backup snapshot
    // before the failed render attempt.
    assert.ok(context.rawDomParagraphs.get(p1.source));
    assert.equal(p1.source.textContent, "");

    active.rollback();

    // Rollback restored the live content captured at session time.
    assert.equal(p1.source.textContent, "rendered v1");
    assert.equal(p1.source.getAttribute("data-tq-rendered"), "true");
    assert.equal(context.contextState.paragraphs.length, 1);
    assert.equal(context.diagnosis.issues.length, 0);
  });
});

test("6. rollback(): state lists restored to before, captured snapshots rolled back in insertion order, lastMeasure patched from the snapshots", () => {
  withDocument(() => {
    const p1 = makeParagraph({ lastMeasure: 100 });
    const p2 = makeParagraph({ lastMeasure: 200 });
    const initialIssue: DiagnosisIssueRecord = { name: "InitialIssue", element: undefined, reportToConsole: false };
    const context = seedContext(p1.source, {
      paragraphs: [p1, p2],
      issues: [initialIssue],
    });
    registerParagraph(context, p1.source);
    registerParagraph(context, p2.source);
    simulateRendered(p1.source, "rendered v1");
    simulateRendered(p2.source, "rendered v2");

    const active = openRelayoutSession(context);

    active.processItem(0, { kind: "unsupported" as const, name: "Unsupported", detail: "no" } as PrepareLayoutResult);
    active.processItem(1, { kind: "ready" as const, planJson: EMPTY_PLAN_JSON, measure: 250, width: 300 } as PrepareLayoutResult);

    // Mutate the context's live lists to simulate mid-session modifications.
    context.contextState.paragraphs.length = 0;
    context.diagnosis.issues.push({ name: "AnotherIssue" });

    active.rollback();

    assert.equal(context.contextState.paragraphs.length, 2);
    assert.equal(context.contextState.paragraphs[0], p1);
    assert.equal(context.contextState.paragraphs[1], p2);
    assert.equal(context.diagnosis.issues.length, 1);
    assert.equal(context.diagnosis.issues[0], initialIssue);

    // Both paragraphs carry the live content captured at session start; the
    // unsupported path's restoreParagraph and the commit's fresh render are
    // both undone. Attributes return to their captured values.
    assert.equal(p1.source.textContent, "rendered v1");
    assert.equal(p2.source.textContent, "rendered v2");
    assert.equal(p1.source.getAttribute("data-tq-rendered"), "true");
    assert.equal(p2.source.getAttribute("data-tq-rendered"), "true");
    assert.equal(p2.source.getAttribute("data-tq-canonical-source"), null);
    assert.equal(p1.source.getAttribute("data-tiqian-capability-issue"), null);

    // lastMeasure is patched from the captured snapshot values.
    assert.equal(p1.lastMeasure, 100);
    assert.equal(p2.lastMeasure, 200);
  });
});

test("8. stale starts false and is assignable", () => {
  withDocument(() => {
    const p1 = makeParagraph();
    const context = seedContext(p1.source, { paragraphs: [p1] });
    const active = openRelayoutSession(context);

    assert.equal(active.stale, false);
    active.stale = true;
    assert.equal(active.stale, true);
  });
});
