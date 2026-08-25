// processParagraph (TsHost runtime port, Slice 4d-1). Ports the paragraph
// processing orchestration from WebEnhancerParagraphPipeline.kt
// (processParagraph, lines 89-227, and layoutParagraph, lines 229-264).
// The module coordinates paragraph eligibility, style detached-fragment backup, markdown
// lowering, exact layout Worker queries with rich fallback detection,
// direct prepare/commit dispatch, and capability issue reporting.
//
// Stateless module: processParagraph(detached-fragment backup, argument) is a named function
// that receives the raw-DOM collaborator as an explicit first parameter. The
// engine bootstrap passes the shared raw-DOM instance; tests pass a fake.
// The stateless worker-request, prepare-paragraph-layout, lifecycle,
// eligibility, markdown-lowering, prepared-metadata and
// commit-prepared-paragraph helpers are imported directly.
//
// Embedding constraint: the generator wraps this file in a Kotlin raw string,
// so the source must contain no dollar sign and no triple double-quote
// sequence. Use string concatenation, never template literals.

// Ambient global declarations pulled in via import type from owner modules.
import type { LoweredParagraph } from "./lowered-paragraph.js";
import type { CapabilityIssueRecord, EnhanceOptions } from "./lifecycle.js";
import {
  applyConfiguredHostFontSize,
  captureSourceInlineSize,
  conformingExactFontSessionId,
  reportIssue,
  stabilizeContentSizedItemInlineSize,
  withoutExactFontSession,
} from "./lifecycle.js";
import {
  classifyFontRole,
  firstDivergentInlineShapingProperty,
  unsupportedInlineShapingProperties,
} from "@tiqian/ffi";
import type { RawDomApi } from "./raw-dom.js";
import { shouldTryParagraph } from "./eligibility.js";
import { lowerMarkdown } from "./markdown-lowering.js";
import {
  preparedCjkStrongSemanticsJson,
  preparedInlineObjectMetaJson,
  preparedSemanticReplayJson,
} from "./prepared-metadata.js";
import { workerLayoutRequest } from "./worker-request.js";
import { prepareParagraphLayout } from "./prepare-paragraph-layout.js";
import {
  commitPreparedParagraph,
  commitWorkerPreparedParagraph,
} from "./commit-prepared-paragraph.js";

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
  paragraph: Element;
  state: ProcessParagraphState;
}

interface ProcessInlineShapingDecisionResult {
  name: string;
  detail: string;
}

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
  function loweringHelpers(): Record<string, unknown> {
    return {
      classifyRole: classifyFontRole,
      inlineShapingDecision: function (tag: string, elementValues: string[], paragraphValues: string[]): ProcessInlineShapingDecisionResult | null {
        const property = firstDivergentInlineShapingProperty(elementValues, paragraphValues);
        return property == null ? null : { name: 'UnsupportedInlineShapingStyle', detail: tag + ':' + property };
      },
      inlineShapingProperties: unsupportedInlineShapingProperties(),
    };
  }

  /**
   * Process a single paragraph element through markdown lowering, detached-fragment backup
   * takeover, layout preparation, and commit.
   *
   * @param {Object} detached-fragment backup
   * @param {Object} argument
   */
  export function processParagraph(rawDom: RawDomApi, argument: ProcessParagraphInvocation): void {
    const paragraph = argument.paragraph;
    const state = argument.state;
    // Prepared metadata builders shared across orchestrators.

    if (!shouldTryParagraph(paragraph)) return;

    // Capture host-owned inline typography before any computed-style probe.
    // CSSStyleDeclaration can leave an empty style attribute after a
    // temporary property is removed even when the source had no attribute.
    const originalStyleAttribute = paragraph.getAttribute('style');

    let lowered: LoweredParagraph | null = null;
    try {
      const loweringResult = lowerMarkdown(
        paragraph,
        state.options,
        loweringHelpers()
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
        reportIssue(issue);
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
      reportIssue(loweringIssue);
      state.onIssue(loweringIssue);
      return;
    }

    const paragraphStyle = (paragraph as HTMLElement).style;
    rawDom.begin(
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

    const hostFontSizeApplied = applyConfiguredHostFontSize(
      paragraph as HTMLElement,
      state.options ? (state.options.fontSize as number | undefined) : undefined
    );
    const sourceInlineSize = captureSourceInlineSize(paragraph);

    const activeOptions = state.preparedDomEnabled
      ? state.options
      : withoutExactFontSession(state.options);

    const workerRequest = workerLayoutRequest(
      paragraph,
      lowered,
      activeOptions
    );
    const sessionKey = conformingExactFontSessionId(activeOptions);
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
      reportIssue(exactWorkerIssue);
      state.onIssue(exactWorkerIssue);
      return;
    }

    rawDom.take(paragraph, hostFontSizeApplied);
    const hostInlineSizeApplied = stabilizeContentSizedItemInlineSize(
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

    rawDom.commit(paragraph, hostInlineSizeApplied);

    let layoutIssue = null;
    try {
      if (workerPlan != null) {
        layoutIssue = commitWorkerPreparedParagraph(
          rawDom,
          {
            paragraph: item,
            workerPlan: workerPlan,
            onExactPreparedDomFallback: state.onDisableExactPreparedDom,
            inlineObjectMetaJson: preparedInlineObjectMetaJson(lowered),
            cjkStrongSemanticsJson: preparedCjkStrongSemanticsJson(lowered),
          }
        );
      } else {
        const preparation = prepareParagraphLayout(
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
          const commitResult = commitPreparedParagraph(
            rawDom,
            {
              paragraph: item,
              preparation: preparation,
              options: activeOptions,
              browserFallback: state.browserFallback,
              onExactPreparedDomFallback: state.onDisableExactPreparedDom,
              semanticReplayJson: preparedSemanticReplayJson(lowered),
              inlineObjectMetaJson: preparedInlineObjectMetaJson(lowered),
              cjkStrongSemanticsJson: preparedCjkStrongSemanticsJson(lowered),
            }
          );
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
      rawDom.restoreParagraph(paragraph);
      if (layoutIssue.element == null) {
        layoutIssue.element = paragraph;
      }
      if (layoutIssue.reportToConsole == null) {
        layoutIssue.reportToConsole = true;
      }
      reportIssue(layoutIssue as CapabilityIssueRecord);
      state.onIssue(layoutIssue);
    }
  }
