// commitPreparedParagraph (TsHost runtime port, Slice 4c). Ports the two
// paragraph layout COMMIT functions of the web host from
// WebEnhancerParagraphPipeline.kt (commitWorkerPreparedParagraph, lines 266-313,
// and commitPreparedParagraph, lines 500-583). The module mounts the rendered
// prepared DOM, sets canonical-source and canonical-plain attributes, checks
// validator verdicts, manages custody engine write suspensions, and handles
// exact-session distrust retries.
//
// Plain script, no exports: running it installs
// globalThis.__TiqianCommitPreparedParagraph. Two consumers share this file as
// the single source of truth: the npm host (importing it for the side effect)
// and the Kotlin runtime bundle, into which a future gradle bridge task will
// embed this source verbatim. Double installation is guarded.
//
// Embedding constraint: the generator wraps this file in a Kotlin raw string,
// so the source must contain no dollar sign and no triple double-quote
// sequence. Use string concatenation, never template literals. The module is
// self-contained: ffi and the measure/renderer/validator/custody globals are
// injected by the caller or read from globalThis.

// Ambient global declarations pulled in via import type from owner modules.
import type { LoweredParagraph } from "./lowered-paragraph.js";
import type { PrepareReadyResult } from "./prepare-paragraph-layout.js";
import type { PreparedDomRendererApi } from "../sampler/snapshot/prepared-dom.js";
import type { PreparedDomValidatorInterface } from "../sampler/snapshot/precomputed.js";
import type { EngineFfiFacade } from "./ffi-face.js";

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
  ffi: EngineFfiFacade;
  paragraph: CommitParagraphTarget;
  preparation: PrepareReadyResult;
  options: Record<string, unknown>;
  browserFallback: Record<string, unknown> | null;
  onExactPreparedDomFallback: CommitExactPreparedDomFallbackCallback;
  semanticReplayJson: string;
  inlineObjectMetaJson: string;
  cjkStrongSemanticsJson: string;
}

type CommitWorkerPreparedParagraphFn = (argument: CommitWorkerPreparedParagraphArgument) => CommitResult | null;
type CommitPreparedParagraphFn = (argument: CommitPreparedParagraphArgument) => CommitResult;

export interface TiqianCommitPreparedParagraphGlobal {
  commitWorkerPreparedParagraph: CommitWorkerPreparedParagraphFn;
  commitPreparedParagraph: CommitPreparedParagraphFn;
}

interface CommitCustodyEngineWritesHost {
  __tqCustodyEngineWrites?: number;
}

type CommitEngineWriteSuspensionFn = () => unknown;

declare global {
  var __TiqianCommitPreparedParagraph: TiqianCommitPreparedParagraphGlobal | undefined;
}

(function () {
  if (globalThis.__TiqianCommitPreparedParagraph) return;

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

  // CustodyEngineWriteSuspension: the prepared DOM bridge writes engine output
  // into the live paragraph with plain element and text arguments, which the
  // host custody forwarding overrides would otherwise redirect; the overrides
  // run native while the counter is positive.
  function engineWriteSuspension(host: Element & CommitCustodyEngineWritesHost, fn: CommitEngineWriteSuspensionFn): unknown {
    host.__tqCustodyEngineWrites = (host.__tqCustodyEngineWrites || 0) + 1;
    try {
      return fn();
    } finally {
      host.__tqCustodyEngineWrites -= 1;
    }
  }

  // RenderPreparedWorkerParagraphDom: direct port of the
  // renderPreparedWorkerParagraphDom @JsFun body in WebEnhancerSupport.kt.
  function renderWorkerPrepared(
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
    return engineWriteSuspension(host, function () {
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
    return engineWriteSuspension(host, function () {
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
   * @param {Object} argument
   * @returns {Object|null}
   */
  function commitWorkerPreparedParagraph(argument: CommitWorkerPreparedParagraphArgument): CommitResult | null {
    const paragraph = argument.paragraph;
    const source = paragraph.source;
    const lowered = paragraph.lowered;
    const width = globalThis.__TiqianResponsiveMeasure!.sourceParagraphWidth(source);

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
    paragraph.lastMeasure = globalThis.__TiqianResponsiveMeasure!.effectiveLineMeasure(
      width,
      lowered.textStyle.fontSize
    );
    globalThis.__TiqianCustody!.stampRendered(source);
    return null;
  }

  /**
   * Commit a direct prepared paragraph layout result to the DOM.
   *
   * @param {Object} argument
   * @returns {Object}
   */
  function commitPreparedParagraph(argument: CommitPreparedParagraphArgument): CommitResult {
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
      globalThis.__TiqianCustody!.stampRendered(source);
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

      const fallbackPreparation = globalThis.__TiqianPrepareParagraphLayout!.prepareParagraphLayout(
        argument.ffi,
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
          return commitPreparedParagraph({
            ffi: argument.ffi,
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

  globalThis.__TiqianCommitPreparedParagraph = {
    commitWorkerPreparedParagraph: commitWorkerPreparedParagraph,
    commitPreparedParagraph: commitPreparedParagraph,
  };
})();

export {};
