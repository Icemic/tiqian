// commitPreparedParagraph (TsHost runtime port, Slice 4c). Ports the two
// paragraph layout COMMIT functions of the web host from
// WebEnhancerParagraphPipeline.kt (commitWorkerPreparedParagraph, lines 266-313,
// and commitPreparedParagraph, lines 500-583). The module mounts the rendered
// prepared DOM, sets canonical-source and canonical-plain attributes, checks
// validator verdicts, manages rawDom engine write suspensions, and handles
// exact-session distrust retries.
//
// Stateless module: commitWorkerPreparedParagraph(rawDom, argument) and
// commitPreparedParagraph(rawDom, argument) are named functions that receive
// the raw-DOM collaborator as an explicit first parameter. Consumers import
// the functions directly; tests call the functions with a fake rawDom.
//
// Embedding constraint: the generator wraps this file in a Kotlin raw string,
// so the source must contain no dollar sign and no triple double-quote
// sequence. Use string concatenation, never template literals.

// Ambient global declarations pulled in via import type from owner modules.
import type { LoweredParagraph } from "./lowered-paragraph.js";
import type { PrepareReadyResult } from "./prepare-paragraph-layout.js";
import type { PreparedDomRendererApi } from "../sampler/snapshot/prepared-dom.js";
import type { PreparedDomValidatorInterface } from "../sampler/snapshot/precomputed.js";
import type { RawDomApi } from "./raw-dom.js";
import { effectiveLineMeasure, sourceParagraphWidth } from "./responsive-measure.js";
import { prepareParagraphLayout } from "./prepare-paragraph-layout.js";

// The commit verdict is a discriminated union on kind. Every original
// construction site sets the fields of its own member only.
interface CommitSuccessResult {
  kind: "success";
  measure: number;
  [key: string]: unknown;
}

interface CommitUnsupportedResult {
  kind: "unsupported";
  name: string;
  detail?: string;
  element?: Element;
  [key: string]: unknown;
}

export type CommitResult = CommitSuccessResult | CommitUnsupportedResult;

interface CommitParagraphTarget {
  source: Element;
  lowered: LoweredParagraph;
  lastMeasure: number | null;
}

type CommitExactPreparedDomFallbackCallback = (issue: unknown) => void;

interface CommitWorkerPreparedParagraphArgument {
  paragraph: CommitParagraphTarget;
  workerPlan: string;
  onExactPreparedDomFallback: CommitExactPreparedDomFallbackCallback;
  inlineObjectMetaJson: string;
  cjkStrongSemanticsJson: string;
}

interface CommitPreparedParagraphArgument {
  paragraph: CommitParagraphTarget;
  preparation: PrepareReadyResult;
  options: Record<string, unknown>;
  browserFallback: Record<string, unknown> | null;
  onExactPreparedDomFallback: CommitExactPreparedDomFallbackCallback;
  semanticReplayJson: string;
  inlineObjectMetaJson: string;
  cjkStrongSemanticsJson: string;
}

// Both commit functions receive the raw-DOM collaborator as an explicit first
// parameter. Consumers import the functions directly; the engine bootstrap
// passes the single shared raw-DOM instance.

const CANONICAL_SOURCE_ATTRIBUTE = 'data-tq-canonical-source';
const EXACT_PREPARED_DOM_ATTRIBUTE = 'data-tq-exact-prepared-dom';

// CanonicalPlainParagraph: inline twin of isCanonicalPlainParagraph in
// lowered-paragraph.js (line 110). True when all six styled collections are
// empty. The module cannot import the ESM function, so it carries an inline
// copy.
function isCanonicalPlain(lowered: LoweredParagraph): boolean {
  return lowered.spans.length === 0 &&
    lowered.decorations.length === 0 &&
    lowered.inlineBoxes.length === 0 &&
    lowered.inlineObjects.length === 0 &&
    lowered.domInlineObjects.length === 0 &&
    lowered.sourceSpans.length === 0;
}

// PreparedDomValidatorIsTestOnly: inline twin of validatePreparedParagraphDom
// in WebEnhancerSupport.kt. The validator global exists only in test worlds;
// an absent validator reports null and never throws.
function rendererIssue(host: Element, width: number): string | null {
  const validator = globalThis.__TiqianPreparedDomValidator;
  return (validator && typeof validator.issue === 'function')
    ? validator.issue(host, width)
    : null;
}

// ReleasePreparedParagraphDomStyles: inline twin of
// releasePreparedParagraphDomStyles in WebEnhancerSupport.kt. Gated on the
// installed renderer. Callers ignore the return value.
function releasePreparedDomStyles(host: Element): boolean {
  const renderer = globalThis.__TiqianPreparedDomRenderer;
  return !!(renderer && typeof renderer.release === 'function' && renderer.release(host) === true);
}

// RenderPreparedWorkerParagraphDom: direct port of the
// renderPreparedWorkerParagraphDom @JsFun body in WebEnhancerSupport.kt.
function renderWorkerPrepared(
  rawDom: RawDomApi,
  host: Element,
  recordJson: string,
  locale: string,
  sourceText: string,
  semanticElements: Element[],
  inlineObjectElements: Element[],
  inlineObjectMetaJson: string,
  cjkStrongSemanticsJson: string
): unknown {
  const record = JSON.parse(recordJson);
  const inlineObjects = Array.from(inlineObjectElements || []);
  const meta = JSON.parse(inlineObjectMetaJson || '[]');
  const inlineObjectsMetaPaired = meta.map(function (entry: Record<string, unknown>, index: number) {
    return {
      start: entry.start,
      end: entry.end,
      marginRight: entry.marginRight,
      element: inlineObjects[index],
    };
  });
  return rawDom.suspendEngineWrites(host, function () {
    return globalThis.__TiqianPreparedDomRenderer!.render(
      host,
      record.plan,
      locale,
      {
        sourceText: sourceText,
        semanticReplay: record.semanticReplay || 'snapshot-safe',
        semantics: record.semantics || [],
        inlineBoxes: record.inlineBoxes || [],
        liveSemanticElements: Array.from(semanticElements || []),
        inlineObjects: inlineObjectsMetaPaired,
        cjkStrongSemantics: JSON.parse(cjkStrongSemanticsJson || '[]'),
      }
    );
  });
}

// RenderPreparedParagraphDom: direct port of the renderPreparedParagraphDom
// @JsFun body in WebEnhancerSupport.kt.
function renderPrepared(
  rawDom: RawDomApi,
  host: Element,
  planJson: string,
  locale: string,
  sourceText: string,
  semanticElements: Element[],
  semanticsJson: string,
  inlineObjectElements: Element[],
  inlineObjectMetaJson: string,
  cjkStrongSemanticsJson: string
): unknown {
  const semantics = Array.from(semanticElements || []);
  const inlineObjects = Array.from(inlineObjectElements || []);
  const hasLiveSources = semantics.length > 0 || inlineObjects.length > 0;
  return rawDom.suspendEngineWrites(host, function () {
    const meta = JSON.parse(inlineObjectMetaJson || '[]');
    const inlineObjectsMetaPaired = meta.map(function (entry: Record<string, unknown>, index: number) {
      return {
        start: entry.start,
        end: entry.end,
        marginRight: entry.marginRight,
        element: inlineObjects[index],
      };
    });
    const options = hasLiveSources ? {
      sourceText: sourceText,
      semanticReplay: 'live-source',
      semantics: JSON.parse(semanticsJson || '[]'),
      liveSemanticElements: semantics,
      inlineObjects: inlineObjectsMetaPaired,
      cjkStrongSemantics: JSON.parse(cjkStrongSemanticsJson || '[]'),
    } : undefined;
    return globalThis.__TiqianPreparedDomRenderer!.render(
      host,
      planJson,
      locale,
      options
    );
  });
}

/**
 * Commit a worker-prepared paragraph to the DOM.
 *
 * @param {Object} rawDom
 * @param {Object} argument
 * @returns {Object|null}
 */
export function commitWorkerPreparedParagraph(rawDom: RawDomApi, argument: CommitWorkerPreparedParagraphArgument): CommitResult | null {
  const paragraph = argument.paragraph;
  const source = paragraph.source;
  const lowered = paragraph.lowered;
  const width = sourceParagraphWidth(source);

  source.setAttribute(EXACT_PREPARED_DOM_ATTRIBUTE, 'true');
  source.setAttribute(CANONICAL_SOURCE_ATTRIBUTE, 'true');

  // CanonicalPlainMatchesRuntimeScope: the re-lowering treats a paragraph as
  // a prepared plain host only when the paragraph shape really is plain.
  // Inline-object paragraphs carry replacement characters the re-lowering
  // must re-measure, so sourceSpans alone must not mark them plain.
  if (isCanonicalPlain(lowered)) {
    source.setAttribute('data-tq-canonical-plain', 'true');
  } else {
    source.removeAttribute('data-tq-canonical-plain');
  }

  source.setAttribute('lang', lowered.textStyle.locale);

  const sourceSpansElements = lowered.sourceSpans.map(function (s) {
    return s.element;
  });
  const domInlineObjectsElements = lowered.domInlineObjects.map(function (s) {
    return s.element;
  });

  renderWorkerPrepared(
    rawDom,
    source,
    argument.workerPlan,
    lowered.textStyle.locale,
    lowered.text,
    sourceSpansElements,
    domInlineObjectsElements,
    argument.inlineObjectMetaJson,
    argument.cjkStrongSemanticsJson
  );

  const preparedDomIssue = rendererIssue(source, width);
  if (preparedDomIssue != null) {
    if (typeof argument.onExactPreparedDomFallback === 'function') {
      argument.onExactPreparedDomFallback(preparedDomIssue);
    }
    releasePreparedDomStyles(source);
    source.removeAttribute(EXACT_PREPARED_DOM_ATTRIBUTE);
    source.removeAttribute('data-tq-canonical-plain');
    source.removeAttribute(CANONICAL_SOURCE_ATTRIBUTE);
    source.removeAttribute('lang');
    return {
      kind: 'unsupported',
      name: 'WorkerPreparedDomContractMismatch',
      detail: preparedDomIssue,
      element: source,
    };
  }

  // WorkerCommitRecordsMeasure: cache the effective line measure computed from
  // the measured width and the paragraph font size.
  paragraph.lastMeasure = effectiveLineMeasure(
    width,
    lowered.textStyle.fontSize
  );
  rawDom.stampRendered(source);
  return null;
}

/**
 * Commit a direct prepared paragraph layout result to the DOM.
 *
 * @param {Object} rawDom
 * @param {Object} argument
 * @returns {Object}
 */
export function commitPreparedParagraph(rawDom: RawDomApi, argument: CommitPreparedParagraphArgument): CommitResult {
  const paragraph = argument.paragraph;
  const preparation = argument.preparation;
  const source = paragraph.source;
  const lowered = paragraph.lowered;

  // PreparedPlainHostPromise: canonical-plain promises the re-lowerer a
  // prepared plain host, so a rich prepared paragraph only carries
  // canonical-source and re-lowers through its live clones.
  if (isCanonicalPlain(lowered)) {
    source.setAttribute('data-tq-canonical-plain', 'true');
  }
  source.setAttribute(CANONICAL_SOURCE_ATTRIBUTE, 'true');
  source.setAttribute('lang', lowered.textStyle.locale);

  const sourceSpansElements = lowered.sourceSpans.map(function (s) {
    return s.element;
  });
  const domInlineObjectsElements = lowered.domInlineObjects.map(function (s) {
    return s.element;
  });

  // Kotlin re-serializes its LayoutResult via toPreparedParagraphJson with
  // renderEvidence = !isCanonicalPlainParagraph(); the TS prepare step passes
  // the same six-collection verdict as the render-evidence override, so
  // preparation.planJson is already the byte-equivalent wire form and is used
  // directly.
  renderPrepared(
    rawDom,
    source,
    preparation.planJson!,
    lowered.textStyle.locale,
    lowered.text,
    sourceSpansElements,
    argument.semanticReplayJson,
    domInlineObjectsElements,
    argument.inlineObjectMetaJson,
    argument.cjkStrongSemanticsJson
  );

  const preparedDomIssue = rendererIssue(source, preparation.width!);
  if (preparedDomIssue == null) {
    rawDom.stampRendered(source);
    return {
      kind: 'success',
      measure: preparation.measure,
    };
  }

  if (typeof argument.onExactPreparedDomFallback === 'function') {
    argument.onExactPreparedDomFallback(preparedDomIssue);
  }
  releasePreparedDomStyles(source);
  source.removeAttribute('data-tq-canonical-plain');
  source.removeAttribute(CANONICAL_SOURCE_ATTRIBUTE);
  source.removeAttribute('lang');

  if (preparation.exactFontSessionUsed && argument.browserFallback != null) {
    // ExactSessionMetricDistrust: the replay failed geometry validation
    // against a result shaped by the exact session, so re-lay the paragraph
    // out with browser metrics and replay it through the prepared bridge once
    // more; the per-paragraph validator still guards that second render.
    const fallbackOptions: Record<string, unknown> = {};
    for (const key in argument.options) {
      if (Object.prototype.hasOwnProperty.call(argument.options, key)) {
        fallbackOptions[key] = argument.options[key];
      }
    }
    fallbackOptions.exactFontSession = null;

    const fallbackPreparation = prepareParagraphLayout(
      {
        paragraph: paragraph,
        options: fallbackOptions,
        exactSession: null,
        browserFallback: argument.browserFallback,
        widthOverride: preparation.width,
        ignoreUnchangedMeasure: true,
      }
    );

    switch (fallbackPreparation.kind) {
      case 'unchanged':
        throw new Error('Exact prepared DOM fallback unexpectedly skipped relayout');
      case 'unsupported':
        return {
          kind: 'unsupported',
          name: fallbackPreparation.name,
          detail: fallbackPreparation.detail,
          element: fallbackPreparation.element,
        };
      case 'ready':
        return commitPreparedParagraph(rawDom, {
          paragraph: paragraph,
          preparation: fallbackPreparation,
          options: fallbackOptions,
          browserFallback: null,
          onExactPreparedDomFallback: argument.onExactPreparedDomFallback,
          semanticReplayJson: argument.semanticReplayJson,
          inlineObjectMetaJson: argument.inlineObjectMetaJson,
          cjkStrongSemanticsJson: argument.cjkStrongSemanticsJson,
        });
    }
  }

  // PreparedDomRenderMismatch: the bridge disagreed with a result the browser
  // itself measured, so no re-layout can repair the replay. There is no second
  // renderer to fall back to; the paragraph fails closed and the caller
  // restores its source.
  return {
    kind: 'unsupported',
    name: 'PreparedDomRenderMismatch',
    detail: preparedDomIssue,
    element: source,
  };
}