// processParagraph (TsHost runtime port, Slice 4d-1). Ports the paragraph
// processing orchestration from WebEnhancerParagraphPipeline.kt
// (processParagraph, lines 89-227, and layoutParagraph, lines 229-264).
// The module coordinates paragraph eligibility, style custody, markdown
// lowering, exact layout Worker queries with rich fallback detection,
// direct prepare/commit dispatch, and capability issue reporting.
//
// Consumes __TiqianPreparedMetadata, __TiqianEligibility,
// __TiqianLifecycle, __TiqianCustody, __TiqianWorkerRequest,
// __TiqianLayoutWorker, __TiqianPrepareParagraphLayout, and
// __TiqianCommitPreparedParagraph, plus the lower function imported from
// markdown-lowering.js.
//
// Plain script, no exports: running it installs
// globalThis.__TiqianProcessParagraph. Two consumers share this file as
// the single source of truth: the npm host (importing it for the side effect)
// and the Kotlin runtime bundle, into which a future gradle bridge task will
// embed this source verbatim. Double installation is guarded.
//
// Embedding constraint: the generator wraps this file in a Kotlin raw string,
// so the source must contain no dollar sign and no triple double-quote
// sequence. Use string concatenation, never template literals.

// Ambient global declarations pulled in via import type from owner modules.
import type { LoweredParagraph } from "./lowered-paragraph.js";
import type { EligibilityGlobal } from "./eligibility.js";
import type { PreparedMetadataGlobal } from "./prepared-metadata.js";
import type { CapabilityIssueRecord, EnhanceOptions, LifecycleApi } from "./lifecycle.js";
import type { TiqianLayoutWorkerInstance } from "./web-worker/worker-channel.js";
import type { CustodyApi } from "./custody.js";
import type { ResponsiveMeasureGlobal } from "./responsive-measure.js";
import type { TiqianWorkerRequestGlobal } from "./worker-request.js";
import type { TiqianPrepareParagraphLayoutGlobal } from "./prepare-paragraph-layout.js";
import type { TiqianCommitPreparedParagraphGlobal } from "./commit-prepared-paragraph.js";
import type { EngineFfiFacade } from "./ffi-face.js";
import { lower } from "./markdown-lowering.js";

interface ProcessParagraphTarget {
  source: Element;
  lowered: LoweredParagraph;
  lastMeasure: number | null;
}

interface ProcessExactSessionDescriptor {
  sessionId: string;
}

type ProcessIssueHandler = (issue: Record<string, unknown>) => void;
type ProcessParagraphCommittedHandler = (item: ProcessParagraphTarget) => void;
type ProcessDisableExactPreparedDomHandler = (issue: unknown) => void;

interface ProcessParagraphState {
  onIssue: ProcessIssueHandler;
  onParagraphCommitted: ProcessParagraphCommittedHandler;
  preparedDomEnabled: boolean;
  options: EnhanceOptions;
  exactSession: ProcessExactSessionDescriptor | null;
  browserFallback: Record<string, unknown> | null;
  onDisableExactPreparedDom: ProcessDisableExactPreparedDomHandler;
}

interface ProcessParagraphInvocation {
  ffi: EngineFfiFacade | null;
  paragraph: Element;
  state: ProcessParagraphState;
}

type ProcessParagraphFn = (argument: ProcessParagraphInvocation) => void;

export interface TiqianProcessParagraphGlobal {
  processParagraph: ProcessParagraphFn;
}

interface ProcessInlineShapingDecisionResult {
  name: string;
  detail: string;
}

declare global {
  var __TiqianProcessParagraph: TiqianProcessParagraphGlobal | undefined;
}

(function () {
  if (globalThis.__TiqianProcessParagraph) return;

  // Constants named after the Kotlin constants in WebEnhancerSupport.kt:
  // lines 470-475 and 483-489.
  const CANONICAL_SOURCE_ATTRIBUTE = 'data-tq-canonical-source';
  const EXACT_PREPARED_DOM_ATTRIBUTE = 'data-tq-exact-prepared-dom';
  const RUNTIME_RENDER_FONT_ATTRIBUTE = 'data-tq-runtime-render-font';
  const HOST_INLINE_SIZE_ATTRIBUTE = 'data-tq-host-inline-size';
  const EXACT_FONT_SESSION_CAPABILITY_FAILURES = [
    'NoExactFontFace',
    'MissingGlyph',
    'MissingServerShapingReplay',
    'NoExactMetricFace',
    'NonUniformUnicodeRangeMetrics',
  ];

  // CanonicalPlainParagraph: inline twin of isCanonicalPlainParagraph in
  // lowered-paragraph.js (line 110). True when all six styled collections
  // are empty.
  function isCanonicalPlain(lowered: LoweredParagraph): boolean {
    return lowered.spans.length === 0 &&
      lowered.decorations.length === 0 &&
      lowered.inlineBoxes.length === 0 &&
      lowered.inlineObjects.length === 0 &&
      lowered.domInlineObjects.length === 0 &&
      lowered.sourceSpans.length === 0;
  }

  // CapabilityFailureDetail: inline twin of
  // isExactFontSessionCapabilityFailureDetail in WebEnhancerSupport.kt
  // (line 140). Returns false when detail is null.
  function isCapabilityFailureDetail(detail: unknown): boolean {
    if (detail == null) return false;
    const str = String(detail);
    for (let i = 0; i < EXACT_FONT_SESSION_CAPABILITY_FAILURES.length; i += 1) {
      if (str.indexOf(EXACT_FONT_SESSION_CAPABILITY_FAILURES[i]) !== -1) {
        return true;
      }
    }
    return false;
  }

  // LoweringHelpers: inline twin of the helpers builder in worker-request.js
  // (lines 258-269).
  function loweringHelpers(ffi: EngineFfiFacade): Record<string, unknown> {
    return {
      classifyRole: ffi.classifyFontRole,
      inlineShapingDecision: function (tag: string, elementValues: string[], paragraphValues: string[]): ProcessInlineShapingDecisionResult | null {
        const property = ffi.firstDivergentInlineShapingProperty(elementValues, paragraphValues);
        return property == null ? null : { name: 'UnsupportedInlineShapingStyle', detail: tag + ':' + property };
      },
      inlineShapingProperties: ffi.unsupportedInlineShapingProperties(),
    };
  }

  /**
   * Process a single paragraph element through markdown lowering, custody
   * takeover, layout preparation, and commit.
   *
   * @param {Object} argument
   */
  function processParagraph(argument: ProcessParagraphInvocation): void {
    const ffi = argument.ffi;
    const paragraph = argument.paragraph;
    const state = argument.state;
    // Prepared metadata builders shared across orchestrators.
    const metadata = globalThis.__TiqianPreparedMetadata!;

    if (!globalThis.__TiqianEligibility!.shouldTryParagraph(paragraph)) return;

    // Capture host-owned inline typography before any computed-style probe.
    // CSSStyleDeclaration can leave an empty style attribute after a
    // temporary property is removed even when the source had no attribute.
    const originalStyleAttribute = paragraph.getAttribute('style');

    let lowered: LoweredParagraph | null = null;
    try {
      const loweringResult = lower(
        paragraph,
        state.options,
        loweringHelpers(ffi!)
      );
      if (loweringResult && loweringResult.ok === true) {
        lowered = loweringResult.lowered;
      } else {
        const issue = (loweringResult && loweringResult.issue) || {
          name: 'UnsupportedParagraph',
          detail: 'paragraph could not be lowered',
          element: paragraph,
          reportToConsole: true,
        };
        if (issue.element == null) issue.element = paragraph;
        if (issue.reportToConsole == null) issue.reportToConsole = true;
        globalThis.__TiqianLifecycle!.reportIssue(issue);
        state.onIssue(issue);
        return;
      }
    } catch (error) {
      const loweringIssue: CapabilityIssueRecord = {
        name: 'DomLoweringFailure',
        detail: ((error && (error as { message?: string }).message) as string) || 'unexpected DOM lowering failure',
        element: paragraph,
        reportToConsole: true,
      };
      globalThis.__TiqianLifecycle!.reportIssue(loweringIssue);
      state.onIssue(loweringIssue);
      return;
    }

    const paragraphStyle = (paragraph as HTMLElement).style;
    globalThis.__TiqianCustody!.begin(
      paragraph,
      paragraph.getAttribute('data-tq-rendered'),
      paragraph.getAttribute('data-tq-canonical-plain'),
      paragraph.getAttribute(CANONICAL_SOURCE_ATTRIBUTE),
      paragraph.getAttribute(EXACT_PREPARED_DOM_ATTRIBUTE),
      paragraph.getAttribute('lang'),
      originalStyleAttribute,
      paragraphStyle ? paragraphStyle.getPropertyValue('position') : '',
      paragraphStyle ? paragraphStyle.getPropertyPriority('position') : '',
      paragraphStyle ? paragraphStyle.getPropertyValue('inline-size') : '',
      paragraphStyle ? paragraphStyle.getPropertyPriority('inline-size') : '',
      paragraphStyle ? paragraphStyle.getPropertyValue('font-size') : '',
      paragraphStyle ? paragraphStyle.getPropertyPriority('font-size') : '',
      paragraph.getAttribute(HOST_INLINE_SIZE_ATTRIBUTE)
    );

    const hostFontSizeApplied = globalThis.__TiqianLifecycle!.applyConfiguredHostFontSize(
      paragraph as HTMLElement,
      state.options ? (state.options.fontSize as number | undefined) : undefined
    );
    const sourceInlineSize = globalThis.__TiqianLifecycle!.captureSourceInlineSize(paragraph);

    const activeOptions = state.preparedDomEnabled
      ? state.options
      : globalThis.__TiqianLifecycle!.withoutExactFontSession(state.options);

    const workerRequest = globalThis.__TiqianWorkerRequest!.workerLayoutRequest(
      paragraph,
      lowered,
      activeOptions
    );
    const sessionKey = globalThis.__TiqianLifecycle!.conformingExactFontSessionId(activeOptions);
    // The layout Worker channel is installed by the host page bundle and by
    // test worlds per test; an absent channel reads as no reusable plan, the
    // same tolerance the former Kotlin shims applied.
    const layoutWorker = globalThis.__TiqianLayoutWorker;
    const workerPlan = workerRequest != null && sessionKey != null && layoutWorker != null
      ? layoutWorker.take(paragraph, sessionKey, workerRequest)
      : null;
    const workerIssue = workerRequest != null && workerPlan == null && sessionKey != null && layoutWorker != null
      ? layoutWorker.issue(paragraph, sessionKey, workerRequest)
      : null;

    // WorkerIneligibleRichRunBrowserFallback: SSR and the exact Worker
    // still fail closed when a semantic run has no replayable font
    // evidence. In the live browser, a rich paragraph can shape just that
    // unsupported run through its resolved host font while covered runs
    // remain on the exact session. The progressive scheduler bounds this
    // main-thread fallback to the individual paragraph slice.
    const canUseRichBrowserFallback = !isCanonicalPlain(lowered) && isCapabilityFailureDetail(workerIssue);

    if (
      activeOptions &&
      activeOptions.requireExactLayoutWorker &&
      workerRequest != null &&
      workerPlan == null &&
      !canUseRichBrowserFallback
    ) {
      if (originalStyleAttribute == null) {
        paragraph.removeAttribute('style');
      } else {
        paragraph.setAttribute('style', originalStyleAttribute);
      }
      const exactWorkerIssue = {
        name: 'ExactLayoutWorkerPlanUnavailable',
        detail: workerIssue || 'the exact layout Worker produced no reusable plan',
        element: paragraph,
        reportToConsole: true,
      };
      globalThis.__TiqianLifecycle!.reportIssue(exactWorkerIssue);
      state.onIssue(exactWorkerIssue);
      return;
    }

    globalThis.__TiqianCustody!.take(paragraph, hostFontSizeApplied);
    const hostInlineSizeApplied = globalThis.__TiqianLifecycle!.stabilizeContentSizedItemInlineSize(
      paragraph as HTMLElement,
      sourceInlineSize
    );

    paragraph.setAttribute('data-tq-rendered', 'true');
    paragraph.setAttribute(RUNTIME_RENDER_FONT_ATTRIBUTE, 'true');

    const item: ProcessParagraphTarget = {
      source: paragraph,
      lowered: lowered,
      lastMeasure: null,
    };

    globalThis.__TiqianCustody!.commit(paragraph, hostInlineSizeApplied);

    let layoutIssue = null;
    try {
      if (workerPlan != null) {
        layoutIssue = globalThis.__TiqianCommitPreparedParagraph!.commitWorkerPreparedParagraph({
          paragraph: item,
          workerPlan: workerPlan,
          onExactPreparedDomFallback: state.onDisableExactPreparedDom,
          inlineObjectMetaJson: metadata.preparedInlineObjectMetaJson(lowered),
          cjkStrongSemanticsJson: metadata.preparedCjkStrongSemanticsJson(lowered),
        });
      } else {
        const preparation = globalThis.__TiqianPrepareParagraphLayout!.prepareParagraphLayout(
          ffi!,
          {
            paragraph: item,
            options: activeOptions,
            exactSession: state.exactSession,
            browserFallback: state.browserFallback,
          }
        );
        if (preparation.kind === 'unchanged') {
          layoutIssue = null;
        } else if (preparation.kind === 'unsupported') {
          layoutIssue = preparation;
        } else if (preparation.kind === 'ready') {
          const commitResult = globalThis.__TiqianCommitPreparedParagraph!.commitPreparedParagraph({
            ffi: ffi!,
            paragraph: item,
            preparation: preparation,
            options: activeOptions,
            browserFallback: state.browserFallback,
            onExactPreparedDomFallback: state.onDisableExactPreparedDom,
            semanticReplayJson: metadata.preparedSemanticReplayJson(lowered),
            inlineObjectMetaJson: metadata.preparedInlineObjectMetaJson(lowered),
            cjkStrongSemanticsJson: metadata.preparedCjkStrongSemanticsJson(lowered),
          });
          if (commitResult.kind === 'success') {
            item.lastMeasure = commitResult.measure;
            layoutIssue = null;
          } else {
            layoutIssue = commitResult;
          }
        }
      }
    } catch (error) {
      layoutIssue = {
        name: 'WebEnhancementFailure',
        detail: (error && (error as { message?: string }).message) || 'unexpected layout or DOM rendering failure',
        element: paragraph,
        reportToConsole: true,
      };
    }

    if (layoutIssue == null) {
      state.onParagraphCommitted(item);
    } else {
      globalThis.__TiqianCustody!.restoreParagraph(paragraph);
      if (layoutIssue.element == null) {
        layoutIssue.element = paragraph;
      }
      if (layoutIssue.reportToConsole == null) {
        layoutIssue.reportToConsole = true;
      }
      globalThis.__TiqianLifecycle!.reportIssue(layoutIssue as CapabilityIssueRecord);
      state.onIssue(layoutIssue);
    }
  }

  globalThis.__TiqianProcessParagraph = {
    processParagraph: processParagraph,
  };
})();

export {};
