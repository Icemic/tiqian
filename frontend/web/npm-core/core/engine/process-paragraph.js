// processParagraph (TsHost runtime port, Slice 4d-1). Ports the paragraph
// processing orchestration from WebEnhancerParagraphPipeline.kt
// (processParagraph, lines 89-227, and layoutParagraph, lines 229-264).
// The module coordinates paragraph eligibility, style custody, markdown
// lowering, exact layout Worker queries with rich fallback detection,
// direct prepare/commit dispatch, and capability issue reporting.
//
// Consumes __TiqianPreparedMetadata, __TiqianEligibility,
// __TiqianMarkdownLowering, __TiqianLifecycle, __TiqianCustody,
// __TiqianWorkerRequest, __TiqianLayoutWorker,
// __TiqianPrepareParagraphLayout, and __TiqianCommitPreparedParagraph.
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

(function () {
  if (globalThis.__TiqianProcessParagraph) return;

  // Constants named after the Kotlin constants in WebEnhancerSupport.kt:
  // lines 470-475 and 483-489.
  var CANONICAL_SOURCE_ATTRIBUTE = 'data-tq-canonical-source';
  var EXACT_PREPARED_DOM_ATTRIBUTE = 'data-tq-exact-prepared-dom';
  var RUNTIME_RENDER_FONT_ATTRIBUTE = 'data-tq-runtime-render-font';
  var HOST_INLINE_SIZE_ATTRIBUTE = 'data-tq-host-inline-size';
  var EXACT_FONT_SESSION_CAPABILITY_FAILURES = [
    'NoExactFontFace',
    'MissingGlyph',
    'MissingServerShapingReplay',
    'NoExactMetricFace',
    'NonUniformUnicodeRangeMetrics',
  ];

  // CanonicalPlainParagraph: inline twin of isCanonicalPlainParagraph in
  // lowered-paragraph.js (line 110). True when all six styled collections
  // are empty.
  function isCanonicalPlain(lowered) {
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
  function isCapabilityFailureDetail(detail) {
    if (detail == null) return false;
    var str = String(detail);
    for (var i = 0; i < EXACT_FONT_SESSION_CAPABILITY_FAILURES.length; i += 1) {
      if (str.indexOf(EXACT_FONT_SESSION_CAPABILITY_FAILURES[i]) !== -1) {
        return true;
      }
    }
    return false;
  }

  // LoweringHelpers: inline twin of the helpers builder in worker-request.js
  // (lines 258-269).
  function loweringHelpers(ffi) {
    return {
      classifyRole: ffi.classifyFontRole,
      inlineShapingDecision: function (tag, elementValues, paragraphValues) {
        var property = ffi.firstDivergentInlineShapingProperty(elementValues, paragraphValues);
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
  function processParagraph(argument) {
    var ffi = argument.ffi;
    var paragraph = argument.paragraph;
    var state = argument.state;
    // Prepared metadata builders shared across orchestrators.
    var metadata = globalThis.__TiqianPreparedMetadata;

    if (!globalThis.__TiqianEligibility.shouldTryParagraph(paragraph)) return;

    // Capture host-owned inline typography before any computed-style probe.
    // CSSStyleDeclaration can leave an empty style attribute after a
    // temporary property is removed even when the source had no attribute.
    var originalStyleAttribute = paragraph.getAttribute('style');

    var lowered = null;
    try {
      var loweringResult = globalThis.__TiqianMarkdownLowering.lower(
        paragraph,
        state.options,
        loweringHelpers(ffi)
      );
      if (loweringResult && loweringResult.ok === true) {
        lowered = loweringResult.lowered;
      } else {
        var issue = (loweringResult && loweringResult.issue) || {
          name: 'UnsupportedParagraph',
          detail: 'paragraph could not be lowered',
          element: paragraph,
          reportToConsole: true,
        };
        if (issue.element == null) issue.element = paragraph;
        if (issue.reportToConsole == null) issue.reportToConsole = true;
        globalThis.__TiqianLifecycle.reportIssue(issue);
        state.onIssue(issue);
        return;
      }
    } catch (error) {
      var loweringIssue = {
        name: 'DomLoweringFailure',
        detail: (error && error.message) || 'unexpected DOM lowering failure',
        element: paragraph,
        reportToConsole: true,
      };
      globalThis.__TiqianLifecycle.reportIssue(loweringIssue);
      state.onIssue(loweringIssue);
      return;
    }

    var paragraphStyle = paragraph.style;
    globalThis.__TiqianCustody.begin(
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

    var hostFontSizeApplied = globalThis.__TiqianLifecycle.applyConfiguredHostFontSize(
      paragraph,
      state.options ? state.options.fontSize : undefined
    );
    var sourceInlineSize = globalThis.__TiqianLifecycle.captureSourceInlineSize(paragraph);

    var activeOptions = state.preparedDomEnabled
      ? state.options
      : globalThis.__TiqianLifecycle.withoutExactFontSession(state.options);

    var workerRequest = globalThis.__TiqianWorkerRequest.workerLayoutRequest(
      paragraph,
      lowered,
      activeOptions
    );
    var sessionKey = globalThis.__TiqianLifecycle.conformingExactFontSessionId(activeOptions);
    // The layout Worker channel is installed by the host page bundle and by
    // test worlds per test; an absent channel reads as no reusable plan, the
    // same tolerance the former Kotlin shims applied.
    var layoutWorker = globalThis.__TiqianLayoutWorker;
    var workerPlan = workerRequest != null && sessionKey != null && layoutWorker != null
      ? layoutWorker.take(paragraph, sessionKey, workerRequest)
      : null;
    var workerIssue = workerRequest != null && workerPlan == null && sessionKey != null && layoutWorker != null
      ? layoutWorker.issue(paragraph, sessionKey, workerRequest)
      : null;

    // WorkerIneligibleRichRunBrowserFallback: SSR and the exact Worker
    // still fail closed when a semantic run has no replayable font
    // evidence. In the live browser, a rich paragraph can shape just that
    // unsupported run through its resolved host font while covered runs
    // remain on the exact session. The progressive scheduler bounds this
    // main-thread fallback to the individual paragraph slice.
    var canUseRichBrowserFallback = !isCanonicalPlain(lowered) && isCapabilityFailureDetail(workerIssue);

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
      var exactWorkerIssue = {
        name: 'ExactLayoutWorkerPlanUnavailable',
        detail: workerIssue || 'the exact layout Worker produced no reusable plan',
        element: paragraph,
        reportToConsole: true,
      };
      globalThis.__TiqianLifecycle.reportIssue(exactWorkerIssue);
      state.onIssue(exactWorkerIssue);
      return;
    }

    globalThis.__TiqianCustody.take(paragraph, hostFontSizeApplied);
    var hostInlineSizeApplied = globalThis.__TiqianLifecycle.stabilizeContentSizedItemInlineSize(
      paragraph,
      sourceInlineSize
    );

    paragraph.setAttribute('data-tq-rendered', 'true');
    paragraph.setAttribute(RUNTIME_RENDER_FONT_ATTRIBUTE, 'true');

    var item = {
      source: paragraph,
      lowered: lowered,
      lastMeasure: null,
    };

    globalThis.__TiqianCustody.commit(paragraph, hostInlineSizeApplied);

    var layoutIssue = null;
    try {
      if (workerPlan != null) {
        layoutIssue = globalThis.__TiqianCommitPreparedParagraph.commitWorkerPreparedParagraph({
          paragraph: item,
          workerPlan: workerPlan,
          onExactPreparedDomFallback: state.onDisableExactPreparedDom,
          inlineObjectMetaJson: metadata.preparedInlineObjectMetaJson(lowered),
          cjkStrongSemanticsJson: metadata.preparedCjkStrongSemanticsJson(lowered),
        });
      } else {
        var preparation = globalThis.__TiqianPrepareParagraphLayout.prepareParagraphLayout(
          ffi,
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
          var commitResult = globalThis.__TiqianCommitPreparedParagraph.commitPreparedParagraph({
            ffi: ffi,
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
        detail: (error && error.message) || 'unexpected layout or DOM rendering failure',
        element: paragraph,
        reportToConsole: true,
      };
    }

    if (layoutIssue == null) {
      state.onParagraphCommitted(item);
    } else {
      globalThis.__TiqianCustody.restoreParagraph(paragraph);
      if (layoutIssue.element == null) {
        layoutIssue.element = paragraph;
      }
      if (layoutIssue.reportToConsole == null) {
        layoutIssue.reportToConsole = true;
      }
      globalThis.__TiqianLifecycle.reportIssue(layoutIssue);
      state.onIssue(layoutIssue);
    }
  }

  globalThis.__TiqianProcessParagraph = {
    processParagraph: processParagraph,
  };
})();
