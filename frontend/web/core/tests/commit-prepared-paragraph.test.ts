import assert from "node:assert/strict";
import test from "node:test";

import { commitPreparedParagraph, commitWorkerPreparedParagraph } from "../core/engine/commit-prepared-paragraph.js";
import { createEnhanceContext } from "../core/engine/context/enhance-context.js";
import { effectiveLineMeasure } from "../core/engine/responsive-measure.js";
import { LAYOUT_REVISION, SNAPSHOT_SCHEMA } from "../core/sampler/snapshot/snapshot-schema.js";
import type { EnhancedElementContext } from "../core/engine/context/enhance-context.js";
import type {
  DecorationSpan,
  InlineBoxSpan,
  InlineObjectSpan,
  LineBreakSpan,
  LoweredParagraph,
  TextSpan,
  TextStyle,
} from "../core/engine/lowered-paragraph.js";

// The commit functions run for real, including the real prepared-DOM
// renderer. The former injected validator and its mismatch/fallback/retry
// machinery were dissolved; renderer contract failures now surface as thrown
// errors (UnsupportedPreparedLayoutRevision, InvalidPreparedParagraphHost)
// that the commit functions propagate to the caller.

interface SavedGlobal {
  name: string;
  own: boolean;
  value: unknown;
}

interface FakeStyle {
  setProperty(): void;
}

interface FakeElementOptions {
  tagName?: string;
  width?: number;
}

interface ComputedStyleValues {
  [key: string]: string;
}

interface AttributeRecord {
  name: string;
  value: string;
}

interface FakeBoundingRect {
  width: number;
}

interface FakeElement {
  tagName: string;
  innerHTML: string;
  style: FakeStyle;
  getAttribute(name: string): string | null;
  setAttribute(name: string, value: string): void;
  removeAttribute(name: string): void;
  attributes: Map<string, string>;
  setAttributes: AttributeRecord[];
  removedAttributes: string[];
  getBoundingClientRect(): FakeBoundingRect;
  getClientRects(): [];
  parentElement: null;
  closest(): null;
  querySelectorAll(): [];
  cloneNode(): FakeElement;
  _computedValues?: ComputedStyleValues;
}

type FakeElementWithDom = FakeElement & Element;

type TextStyleOverrides = Partial<TextStyle>;

// The live-DOM collections ride element-only holders: the runtime objects
// the tests build carry only the element field. The full LoweredParagraph
// assigns onto the holder shape, which keeps makeParagraph's single boundary
// cast legal.
interface ElementHolder {
  element: Element;
}

interface LoweredParagraphOverrides {
  text?: string;
  textStyle?: TextStyle;
  lineHeight?: number;
  spans?: TextSpan[];
  decorations?: DecorationSpan[];
  inlineBoxes?: InlineBoxSpan[];
  inlineObjects?: InlineObjectSpan[];
  domInlineObjects?: ElementHolder[];
  sourceSpans?: ElementHolder[];
  sourceBoundaries?: number[];
  lineBreakSpans?: LineBreakSpan[];
}

interface TestLoweredHolders {
  domInlineObjects: ElementHolder[];
  sourceSpans: ElementHolder[];
}

type TestLoweredParagraph = Pick<
  LoweredParagraph,
  | "text"
  | "textStyle"
  | "lineHeight"
  | "spans"
  | "decorations"
  | "inlineBoxes"
  | "inlineObjects"
  | "sourceBoundaries"
  | "lineBreakSpans"
> &
  TestLoweredHolders;

interface ParagraphOverrides {
  source?: FakeElement;
  lowered?: LoweredParagraphOverrides;
  lastMeasure?: number | null;
}

interface CommitParagraphTarget {
  source: FakeElementWithDom;
  lowered: LoweredParagraph;
  lastMeasure: number | null;
}

function saveGlobals(names: string[]): SavedGlobal[] {
  return names.map((name) => ({
    name,
    own: Object.prototype.hasOwnProperty.call(globalThis, name),
    value: globalThis[name as keyof typeof globalThis],
  }));
}

function restoreGlobals(entries: SavedGlobal[]): void {
  for (const { name, own, value } of entries) {
    if (own) (globalThis as Record<string, unknown>)[name] = value;
    else delete (globalThis as Record<string, unknown>)[name];
  }
}

interface PropertyReader {
  getPropertyValue(name: string): string;
}

type ComputedStyleStub = Record<string, unknown> & PropertyReader;

function computedStyle(values: ComputedStyleValues = {}): ComputedStyleStub {
  const props: ComputedStyleValues = {
    paddingLeft: "0px",
    paddingRight: "0px",
    borderLeftWidth: "0px",
    borderRightWidth: "0px",
    ...values,
  };
  const style: ComputedStyleStub = {
    getPropertyValue: (name: string): string => {
      const key = String(name).toLowerCase();
      return Object.prototype.hasOwnProperty.call(props, key)
        ? String(props[key])
        : "";
    },
  };
  for (const key of Object.keys(props)) style[key] = props[key];
  return style;
}

type EnvScopedRun<T> = () => T;

function withEnv<T>(fn: EnvScopedRun<T>): T {
  const saved = saveGlobals([
    "getComputedStyle",
  ]);
  try {
    (globalThis as Record<string, unknown>).getComputedStyle = (target: Element | null, pseudo?: string | null): ComputedStyleStub =>
      target && (target as FakeElement & Element)._computedValues
        ? computedStyle((target as FakeElement & Element)._computedValues)
        : computedStyle();
    return fn();
  } finally {
    restoreGlobals(saved);
  }
}

function makeElement(initialAttributes: Record<string, string> = {}, options: FakeElementOptions = {}): FakeElementWithDom {
  const attributes = new Map(Object.entries(initialAttributes));
  const removedAttributes: string[] = [];
  const setAttributes: AttributeRecord[] = [];
  const element: FakeElement = {
    tagName: options.tagName ?? "P",
    innerHTML: "",
    style: { setProperty: () => {} },
    getAttribute: (name: string): string | null => attributes.get(name) ?? null,
    setAttribute: (name: string, value: string): void => {
      const strVal = String(value);
      attributes.set(name, strVal);
      setAttributes.push({ name, value: strVal });
    },
    removeAttribute: (name: string): void => {
      attributes.delete(name);
      removedAttributes.push(name);
    },
    attributes,
    setAttributes,
    removedAttributes,
    getBoundingClientRect: (): FakeBoundingRect => ({ width: options.width ?? 320 }),
    getClientRects: (): [] => [],
    parentElement: null,
    closest: (): null => null,
    // An empty-lines plan renders no markup, so the renderer's marker and
    // placeholder queries all answer empty.
    querySelectorAll: (): [] => [],
    cloneNode: (): FakeElement => makeElement({}, options),
  };
  return element as FakeElementWithDom;
}

function textStyle(overrides: TextStyleOverrides = {}): TextStyle {
  return {
    fontFamilies: ["Noto Serif CJK SC"],
    fontSize: 19,
    fontWeight: 400,
    italic: false,
    baselineShift: 0,
    locale: "zh-Hans",
    ...overrides,
  };
}

function makeParagraph(overrides: ParagraphOverrides = {}): CommitParagraphTarget {
  const source = (overrides.source ?? makeElement()) as FakeElementWithDom;
  const lowered: TestLoweredParagraph = {
    text: "hello",
    textStyle: textStyle(),
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
    lowered: lowered as LoweredParagraph,
    lastMeasure: overrides.lastMeasure ?? null,
  };
}

function createTestContext(source: FakeElementWithDom): EnhancedElementContext {
  return createEnhanceContext(source);
}

// The minimal schema-conforming plan: the renderer accepts it and renders no
// lines, so the commit contract is exercised without a shaping fixture.
const EMPTY_PLAN_JSON = JSON.stringify({
  schema: SNAPSHOT_SCHEMA,
  layoutRevision: LAYOUT_REVISION,
  height: 0,
  lines: [],
});

test("worker happy path: sets four attributes, renders the plan, sets lastMeasure, stamps rawDom, returns null", () => {
  withEnv(() => {
    const source = makeElement({}, { width: 300 });
    const context = createTestContext(source);

    const domObjElement = makeElement();
    const paragraph = makeParagraph({
      source,
      lowered: {
        text: "hello world",
        textStyle: textStyle({ locale: "zh-Hans", fontSize: 19 }),
        domInlineObjects: [{ element: domObjElement }],
      },
    });

    const record = {
      plan: EMPTY_PLAN_JSON,
      semantics: [{ start: 0, end: 2, tagName: "em" }],
      inlineBoxes: [{ start: 0, end: 1 }],
    };
    const workerPlan = JSON.stringify(record);
    const inlineObjectMetaJson = JSON.stringify([{ start: 2, end: 3, marginRight: 6 }]);
    const cjkStrongSemanticsJson = JSON.stringify([{ start: 0, end: 1 }]);

    const result = commitWorkerPreparedParagraph(context, {
      paragraph,
      workerPlan,
      inlineObjectMetaJson,
      cjkStrongSemanticsJson,
    });

    assert.equal(result, null);
    assert.equal(paragraph.source.getAttribute("lang"), "zh-Hans");
    assert.equal(paragraph.source.getAttribute("data-tq-canonical-plain"), null); // not plain due to domInlineObjects
    assert.equal(paragraph.source.getAttribute("data-tq-snapshot-prepared-dom"), "true");
    assert.equal(paragraph.source.getAttribute("data-tq-canonical-source"), "true");
    assert.equal(paragraph.lastMeasure, effectiveLineMeasure(300, 19));

    // The real renderer ran against the host: the empty plan lowered to empty
    // markup, and the rawDom stamp registered the paragraph with the engine
    // write suspension closed.
    assert.equal(paragraph.source.innerHTML, "");
    assert.ok(context.rawDomParagraphs.has(paragraph.source));
    assert.equal(context.rawDomParagraphs.get(paragraph.source)?.engineWriteDepth, 0);
  });
});

test("worker commit propagates the renderer layout-revision rejection", () => {
  withEnv(() => {
    const paragraph = makeParagraph();
    const context = createTestContext(paragraph.source);

    // A plan without the schema/revision envelope is rejected by the real
    // renderer; the commit layer no longer swallows or re-verdicts it.
    assert.throws(
      () => commitWorkerPreparedParagraph(context, {
        paragraph,
        workerPlan: JSON.stringify({ plan: "{}" }),
        inlineObjectMetaJson: "[]",
        cjkStrongSemanticsJson: "[]",
      }),
      { message: "UnsupportedPreparedLayoutRevision" },
    );
  });
});

test("worker rich lowered: removes canonical-plain attribute for rich lowered", () => {
  withEnv(() => {
    const source = makeElement({ "data-tq-canonical-plain": "true" });
    const context = createTestContext(source);

    const paragraph = makeParagraph({
      source,
      lowered: {
        spans: [{ start: 0, end: 2, style: textStyle() }],
      },
    });

    const result = commitWorkerPreparedParagraph(context, {
      paragraph,
      workerPlan: JSON.stringify({ plan: EMPTY_PLAN_JSON }),
      inlineObjectMetaJson: "[]",
      cjkStrongSemanticsJson: "[]",
    });

    assert.equal(result, null);
    assert.equal(paragraph.source.getAttribute("data-tq-canonical-plain"), null);
    assert.ok(paragraph.source.removedAttributes.includes("data-tq-canonical-plain"));
  });
});

test("direct happy path, no live sources: sets canonical-plain and canonical-source, stamps rawDom, returns success", () => {
  withEnv(() => {
    const paragraph = makeParagraph();
    const context = createTestContext(paragraph.source);

    const preparation = {
      kind: "ready" as const,
      rawEnvelope: EMPTY_PLAN_JSON,
      planJson: EMPTY_PLAN_JSON,
      plan: { lines: [] },
      diagnostics: { capabilityIssues: [], advanceSuspects: [] },
      width: 320,
      measure: 339,
      snapshotFontSessionUsed: false,
    };

    const result = commitPreparedParagraph(context, {
      paragraph,
      preparation,
      options: {},
      browserFallback: null,
      semanticReplayJson: "[]",
      inlineObjectMetaJson: "[]",
      cjkStrongSemanticsJson: "[]",
    });

    assert.deepEqual(result, { kind: "success", measure: 339 });
    assert.equal(paragraph.source.getAttribute("data-tq-canonical-plain"), "true");
    assert.equal(paragraph.source.getAttribute("data-tq-canonical-source"), "true");
    assert.equal(paragraph.source.getAttribute("lang"), "zh-Hans");

    // Plain paragraphs render through the real renderer with no live-source
    // options; the empty plan lowered to empty markup on the host.
    assert.equal(paragraph.source.innerHTML, "");
    assert.ok(context.rawDomParagraphs.has(paragraph.source));
  });
});

test("direct rich path with sourceSpans elements: renders without canonical-plain and returns success", () => {
  withEnv(() => {
    const spanElement = makeElement({}, { tagName: "EM" });
    const objElement = makeElement();
    const paragraph = makeParagraph({
      lowered: {
        text: "hello world",
        sourceSpans: [{ element: spanElement }],
        domInlineObjects: [{ element: objElement }],
      },
    });
    const context = createTestContext(paragraph.source);

    const preparation = {
      kind: "ready" as const,
      rawEnvelope: EMPTY_PLAN_JSON,
      planJson: EMPTY_PLAN_JSON,
      plan: { lines: [] },
      diagnostics: { capabilityIssues: [], advanceSuspects: [] },
      width: 320,
      measure: 339,
      snapshotFontSessionUsed: false,
    };

    // The rich lowered (sourceSpans + domInlineObjects) drives the live-source
    // branch with an empty semantic replay: no semantic placeholders render in
    // the empty plan, but the commit still takes the hasLiveSources path.
    const semanticReplayJson = "[]";
    const inlineObjectMetaJson = JSON.stringify([{ start: 1, end: 2, marginRight: 5 }]);
    const cjkStrongSemanticsJson = JSON.stringify([{ start: 0, end: 1 }]);

    const result = commitPreparedParagraph(context, {
      paragraph,
      preparation,
      options: {},
      browserFallback: null,
      semanticReplayJson,
      inlineObjectMetaJson,
      cjkStrongSemanticsJson,
    });

    assert.deepEqual(result, { kind: "success", measure: 339 });
    assert.equal(paragraph.source.getAttribute("data-tq-canonical-plain"), null);
    assert.equal(paragraph.source.getAttribute("data-tq-canonical-source"), "true");
    assert.equal(paragraph.source.getAttribute("lang"), "zh-Hans");
  });
});

test("direct commit propagates the renderer layout-revision rejection", () => {
  withEnv(() => {
    const paragraph = makeParagraph();
    const context = createTestContext(paragraph.source);

    const preparation = {
      kind: "ready" as const,
      rawEnvelope: '{"lines":[]}',
      planJson: '{"lines":[]}',
      plan: { lines: [] },
      diagnostics: { capabilityIssues: [], advanceSuspects: [] },
      width: 320,
      measure: 339,
      snapshotFontSessionUsed: false,
    };

    // The former GeometryMismatch fallback verdict is dissolved; a plan the
    // renderer rejects surfaces as the thrown renderer error.
    assert.throws(
      () => commitPreparedParagraph(context, {
        paragraph,
        preparation,
        options: {},
        browserFallback: { bridge: {} },
        semanticReplayJson: "[]",
        inlineObjectMetaJson: "[]",
        cjkStrongSemanticsJson: "[]",
      }),
      { message: "UnsupportedPreparedLayoutRevision" },
    );

    assert.equal(
      paragraph.source.setAttributes.some((a) => a.name === "data-tq-snapshot-prepared-dom"),
      false,
    );
  });
});
