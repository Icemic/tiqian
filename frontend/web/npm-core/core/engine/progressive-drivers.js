// progressive-drivers (TsHost runtime port, Slice 5). Ports the
// enhanceProgressively / relayout progressive job drivers and the finishing
// reporting layer from WebEnhancer.kt and WebEnhancerProgressiveJob.kt into
// a pure TS module.
//
// Consumes __TiqianRootState, __TiqianProgressiveJob,
// __TiqianProgressiveRelayoutSession, __TiqianPrepareParagraphLayout,
// __TiqianProcessParagraph, __TiqianLifecycle, __TiqianResponsiveMeasure.
//
// Plain script, no exports: running it installs
// globalThis.__TiqianProgressiveDrivers. Three consumers share this file as
// the single source of truth: the npm host (importing it for the side effect),
// the Kotlin runtime bundle, into which a future gradle bridge task will
// embed this source verbatim, and engine-entry.js which reads the public
// surface for style gates, job dispatch, and canonical re-entry. Double
// installation is guarded.
//
// Embedding constraint: the generator wraps this file in a Kotlin raw string,
// so the source must contain no dollar sign and no triple double-quote
// sequence. Use string concatenation, never template literals.

(function () {
  if (globalThis.__TiqianProgressiveDrivers) return;

  var CAPABILITY_DETAIL_LIMIT = 512;
  var WIDTH_DEPENDENT_CAPABILITY_ISSUES = ["InlineCloneDecorationBreakUnsupported"];

  // CssFragmentedBlockInlineMeasure: plain getBoundingClientRect().width -- for
  // a block fragmented by CSS columns this is the union of every fragment, not
  // a per-fragment measure. Every caller uses it only for coarse >=0.5px drift
  // detection, where the union error is dwarfed by the tolerance (see the ADR
  // 0039 fractional fragment-aware amendment). A caller that needs the widest
  // live fragment must use elementContentWidth from
  // npm/core/engine/responsive-measure.js (installed as the responsive measure
  // bridge) instead.
  function elementFragmentBorderBoxInlineSize(element) {
    if (!element) return 0;
    return element.getBoundingClientRect ? element.getBoundingClientRect().width : 0;
  }

  // paragraphViewportDistance: returns 0 when the element is visible in the
  // viewport, or a positive pixel distance otherwise (negative of bottom for
  // above-viewport, top minus viewportHeight for below-viewport).
  function paragraphViewportDistance(element) {
    if (!element || !element.getBoundingClientRect) return 0;
    var rect = element.getBoundingClientRect();
    var viewportHeight = window.innerHeight || document.documentElement.clientHeight || 0;
    if (rect.bottom >= 0 && rect.top <= viewportHeight) return 0;
    return rect.bottom < 0 ? -rect.bottom : rect.top - viewportHeight;
  }

  // observableSnapshotCount: reads data-tiqian-snapshot-count attribute; safe
  // integer and > 0, else 0.
  function observableSnapshotCount(root) {
    var value = Number(root.getAttribute("data-tiqian-snapshot-count"));
    return Number.isSafeInteger(value) && value > 0 ? value : 0;
  }

  // computedStyle: reads a CSS property value from the element's computed
  // style.
  function computedStyle(element, property) {
    return window.getComputedStyle(element).getPropertyValue(property);
  }

  // SharedRuntimeStylesCapabilityGate: renderer-owned geometry depends on the
  // package stylesheet for its line strut, reset, and nowrap invariants. The
  // public ESM entry waits for that stylesheet; direct callers must do the
  // same instead of silently painting a second browser-owned layout.
  function rejectMissingSharedRuntimeStyles(state, candidates) {
    var ready = computedStyle(state.root, "--tq-styles-ready").trim();
    if (ready === "1") return false;
    for (var i = 0; i < candidates.length; i += 1) {
      var issue = {
        name: "MissingSharedRuntimeStyles",
        detail: "Load @tiqian/prose/styles.css before TiqianWeb.enhance",
        element: candidates[i],
        reportToConsole: true,
        markerCaptured: false,
      };
      state.issues.push(issue);
      globalThis.__TiqianLifecycle.reportIssue(issue);
    }
    globalThis.__TiqianRootState.publishState(state);
    return true;
  }

  // startProgressiveJob: mirrors WebEnhancerProgressiveJob.kt
  // startProgressiveJob. Builds the spec and hands it to the progressive job
  // module.
  function startProgressiveJob(state, kind, itemCount, processItem, onItemsFinished, onFailure, stale, itemTierIndex, paragraphsByDoc) {
    state.root.removeAttribute("data-tiqian-relayout-error");
    var spec = {
      root: state.root,
      kind: kind,
      itemCount: itemCount,
      processItem: processItem,
      onItemsFinished: onItemsFinished || null,
      onFailure: onFailure || null,
      isStale: stale || null,
      onProgress: function () {
        globalThis.__TiqianRootState.publishState(state, true);
      },
      onFinished: function (report) {
        finishProgressiveJob(state, report);
      },
      onFailed: function (failure) {
        failProgressiveJob(state, failure);
      },
      startedAt: Date.now(),
      itemTierIndex: itemTierIndex || null,
      paragraphsByDoc: paragraphsByDoc || null,
      coordinated: globalThis.__TiqianProgressiveJob.isAttached(state.root),
    };
    globalThis.__TiqianProgressiveJob.startJob(spec);
  }

  // finishProgressiveJob: mirrors WebEnhancerProgressiveJob.kt
  // finishProgressiveJob.
  function finishProgressiveJob(state, report) {
    globalThis.__TiqianRootState.publishState(state);
    dispatchProgressiveSummary(
      state,
      report.kind,
      Date.now() - report.startedAt,
      report.maxSliceMs,
      false,
      null,
      report.stale
    );
  }

  // failProgressiveJob: mirrors WebEnhancerProgressiveJob.kt
  // failProgressiveJob. Truncates detail, sets error attribute, dispatches
  // error and summary events.
  function failProgressiveJob(state, failure) {
    var detail = String(failure.detail).slice(0, CAPABILITY_DETAIL_LIMIT);
    state.root.setAttribute("data-tiqian-relayout-error", detail);
    globalThis.__TiqianRootState.publishState(state, true);
    dispatchTiqianProgressiveError(
      state.root,
      failure.kind,
      detail,
      Date.now() - failure.startedAt,
      failure.maxSliceMs
    );
    dispatchProgressiveSummary(
      state,
      failure.kind,
      Date.now() - failure.startedAt,
      failure.maxSliceMs,
      true,
      detail,
      false
    );
  }

  // dispatchProgressiveSummary: mirrors WebEnhancerProgressiveJob.kt
  // dispatchProgressiveSummary. Emits tiqian:ready or tiqian:relayout-ready
  // with the full detail shape.
  function dispatchProgressiveSummary(state, kind, durationMs, maxSliceMs, failed, error, stale) {
    var runtimeEnhancedCount = state.paragraphs.length;
    var snapshotCount = observableSnapshotCount(state.root);
    var enhancedCount = runtimeEnhancedCount + snapshotCount;
    var issueCount = state.issues.length;
    var detail;
    if (kind === "Relayout") {
      detail = {
        enhancedCount: enhancedCount,
        runtimeEnhancedCount: runtimeEnhancedCount,
        snapshotCount: snapshotCount,
        issueCount: issueCount,
        durationMs: durationMs,
        maxSliceMs: maxSliceMs,
        relayout: true,
        failed: failed,
        error: error,
        stale: stale,
      };
      dispatchCustomEvent(state.root, "tiqian:relayout-ready", detail);
    } else {
      detail = {
        enhancedCount: enhancedCount,
        runtimeEnhancedCount: runtimeEnhancedCount,
        snapshotCount: snapshotCount,
        issueCount: issueCount,
        durationMs: durationMs,
        maxSliceMs: maxSliceMs,
        stale: stale,
      };
      dispatchCustomEvent(state.root, "tiqian:ready", detail);
    }
  }

  // dispatchCustomEvent: defensive CustomEvent dispatch; skips if
  // root.dispatchEvent is missing.
  function dispatchCustomEvent(root, kind, detail) {
    if (!root || typeof root.dispatchEvent !== "function") return;
    root.dispatchEvent(new CustomEvent(kind, { bubbles: true, composed: true, detail: detail }));
  }

  // dispatchTiqianProgressiveError: mirrors WebEnhancerSupport.kt
  // dispatchTiqianProgressiveError. Emits tiqian:relayout-error or
  // tiqian:error depending on kind.
  function dispatchTiqianProgressiveError(root, kind, detail, durationMs, maxSliceMs) {
    var eventName = kind === "Relayout" ? "tiqian:relayout-error" : "tiqian:error";
    var eventDetail = {
      kind: kind,
      error: detail,
      durationMs: durationMs,
      maxSliceMs: maxSliceMs,
    };
    dispatchCustomEvent(root, eventName, eventDetail);
  }

  // ---------------------------------------------------------------------------
  // enhanceProgressively internal
  // ---------------------------------------------------------------------------

  // optionsFromJs consumes the public options bag, not the canonical options
  // this module stores in state.options. Relayout restarts arrive with the
  // canonical shape, so fromCanonical routes them through
  // createRootStateFromCanonical instead of re-resolving the bag.
  function enhanceProgressively(root, optionsBag, kind, fromCanonical) {
    var RS = globalThis.__TiqianRootState;

    // Kotlin's private enhanceProgressively installs the copy handler and
    // destroys the root before rebuilding state, and the relayout restarts
    // (branches 1 and 3) enter this function directly. Hosted worlds carry
    // __TiqianEngine, whose destroy cancels the job, restores every committed
    // paragraph, and clears the root attributes; the standalone unit-test
    // world drives this module without an engine entry and keeps the bare
    // job cancel.
    var copyInstaller = globalThis.__TiqianInstallCopyHandler;
    if (copyInstaller && globalThis.document) copyInstaller(globalThis.document);
    if (globalThis.__TiqianEngine) {
      globalThis.__TiqianEngine.destroy(root);
    } else {
      globalThis.__TiqianProgressiveJob.cancelJob(root);
    }
    var state = fromCanonical
      ? RS.createRootStateFromCanonical(root, optionsBag)
      : RS.createRootState(root, optionsBag);

    var sourceCandidates = RS.paragraphCandidates(root, state.options.paragraphSelector);

    // SharedRuntimeStylesCapabilityGate.
    if (rejectMissingSharedRuntimeStyles(state, sourceCandidates)) return;

    // Work order sorts by viewport distance; itemTierIndex keeps the
    // document-order index of each work item, so a coordinator tier flip
    // arriving in document order gates its item in work order in O(1).
    var distances = new Array(sourceCandidates.length);
    for (var d = 0; d < sourceCandidates.length; d += 1) {
      distances[d] = paragraphViewportDistance(sourceCandidates[d]);
    }
    var itemTierIndex = new Array(sourceCandidates.length);
    for (var t = 0; t < sourceCandidates.length; t += 1) {
      itemTierIndex[t] = t;
    }
    // Explicit dual-key sort: (distance, index) ascending; does not rely on
    // Array.sort stability.
    itemTierIndex.sort(function (a, b) {
      if (distances[a] < distances[b]) return -1;
      if (distances[a] > distances[b]) return 1;
      return a < b ? -1 : a > b ? 1 : 0;
    });
    var candidates = new Array(itemTierIndex.length);
    for (var c = 0; c < itemTierIndex.length; c += 1) {
      candidates[c] = sourceCandidates[itemTierIndex[c]];
    }

    // Capture responsive measures for staleness detection.
    var capturedMeasures = new Array(candidates.length);
    for (var m = 0; m < candidates.length; m += 1) {
      capturedMeasures[m] = globalThis.__TiqianLifecycle.responsiveSourceMeasure(
        candidates[m],
        state.options.fontSize
      );
    }
    var stale = false;

    function liveMeasure(index) {
      return globalThis.__TiqianLifecycle.responsiveSourceMeasure(
        candidates[index],
        state.options.fontSize
      );
    }

    RS.setState(root, state);
    globalThis.__TiqianRootState.publishState(state, true);

    startProgressiveJob(
      state,
      kind,
      candidates.length,
      function (index) {
        // Per-item measure guard: refuse to commit a paragraph whose measure
        // drifted since capture.
        if (liveMeasure(index) !== capturedMeasures[index]) {
          stale = true;
        } else {
          globalThis.__TiqianProcessParagraph.processParagraph(
            RS.processParagraphArgument(state, candidates[index])
          );
        }
      },
      function () {
        // StaleFinishKeepsCommittedParagraphs: the per-item guard already
        // refuses to commit a paragraph whose measure drifted, so the
        // committed ones were current when they landed. Rolling them back
        // here would tear the root to native source whenever a coordinated
        // job spans frames across a width change; the stale report
        // hands the follow-up to element.js, which dispatches one
        // latest-width relayout.
        for (var i = 0; i < candidates.length; i += 1) {
          if (liveMeasure(i) !== capturedMeasures[i]) {
            stale = true;
            break;
          }
        }
      },
      null,
      function () { return stale; },
      itemTierIndex,
      sourceCandidates
    );
  }

  // ---------------------------------------------------------------------------
  // relayout
  // ---------------------------------------------------------------------------

  function relayout(root) {
    var RS = globalThis.__TiqianRootState;
    var PJ = globalThis.__TiqianProgressiveJob;

    // Branch 1: Enhance is running. Kotlin restarts the interrupted enhance
    // through the two-arg overload, so the kind stays Enhance and the finish
    // event stays tiqian:ready. Running.options is already canonical; route
    // it through the canonical state builder so the resolved options are
    // reused, not re-resolved.
    if (PJ.jobKind(root) === "Enhance") {
      var running = RS.getState(root);
      if (running != null) {
        enhanceProgressively(root, running.options, "Enhance", true);
        return;
      }
    }

    // Branch 2: no state at all -- cold-start a Relayout with bag null.
    var state = RS.getState(root);
    if (state == null) {
      enhanceProgressively(root, null, "Relayout");
      return;
    }

    // Branch 3: cancel current job; check for width-dependent capability
    // issues that require a full enhance restart.
    PJ.cancelJob(root);
    var hasWidthDependentIssue = false;
    for (var i = 0; i < state.issues.length; i += 1) {
      var issueName = ((state.issues[i] && state.issues[i].name) || "");
      if (WIDTH_DEPENDENT_CAPABILITY_ISSUES.indexOf(issueName) !== -1) {
        hasWidthDependentIssue = true;
        break;
      }
    }
    if (hasWidthDependentIssue) {
      // WidthDependentCapabilityTransitionRetry: only named capabilities
      // whose eligibility depends on line count need to be lowered again at
      // the new width. Restore semantic source once, then let viewport-near
      // paragraphs take over atomically in bounded slices just like any other
      // source refresh. state.options is canonical.
      enhanceProgressively(root, state.options, "Relayout", true);
      return;
    }

    // Main relayout path.
    var rendered = state.paragraphs;
    // StrandedEnhanceResume: a stale enhance finish leaves the paragraphs
    // it skipped in semantic source, and this follow-up relayout is the
    // only job that will reach them. Fold them into the work set at the
    // live width; the rendered ones keep the snapshot path below.
    var stranded = RS.strandedSourceParagraphs(root, state);
    var renderedCount = rendered.length;
    var count = renderedCount + stranded.length;

    // Work order: if root is in viewport process in document order; otherwise
    // sort by viewport distance.
    var workOrder;
    if (paragraphViewportDistance(root) <= 0) {
      workOrder = new Array(count);
      for (var w = 0; w < count; w += 1) {
        workOrder[w] = w;
      }
    } else {
      var relayoutDistances = new Array(count);
      for (var r = 0; r < count; r += 1) {
        if (r < renderedCount) {
          relayoutDistances[r] = paragraphViewportDistance(rendered[r].source);
        } else {
          relayoutDistances[r] = paragraphViewportDistance(stranded[r - renderedCount]);
        }
      }
      workOrder = new Array(count);
      for (var wi = 0; wi < count; wi += 1) {
        workOrder[wi] = wi;
      }
      workOrder.sort(function (a, b) {
        if (relayoutDistances[a] < relayoutDistances[b]) return -1;
        if (relayoutDistances[a] > relayoutDistances[b]) return 1;
        return a < b ? -1 : a > b ? 1 : 0;
      });
    }

    // WidthSnapshotPerRelayoutJob: every paragraph is prepared against the
    // geometry seen when the job starts. If the host changes again while
    // slices are running, element.js schedules one latest-width follow-up
    // instead of allowing a queue of obsolete widths to replay.
    var widths = new Array(renderedCount);
    for (var p = 0; p < renderedCount; p += 1) {
      widths[p] = globalThis.__TiqianResponsiveMeasure.sourceParagraphWidth(rendered[p].source);
    }

    var commitSession = globalThis.__TiqianProgressiveRelayoutSession.createProgressiveRelayoutSession(
      RS.sessionArgument(state)
    );
    var rootWidth = elementFragmentBorderBoxInlineSize(root);

    // Build paragraphsByDoc: rendered sources in order, then stranded.
    var paragraphsByDoc = new Array(count);
    for (var pb = 0; pb < renderedCount; pb += 1) {
      paragraphsByDoc[pb] = rendered[pb].source;
    }
    for (var ps = 0; ps < stranded.length; ps += 1) {
      paragraphsByDoc[renderedCount + ps] = stranded[ps];
    }

    startProgressiveJob(
      state,
      "Relayout",
      count,
      function (index) {
        // Stale guard: once the session is stale, skip remaining items.
        if (commitSession.stale) return;
        var mixIndex = workOrder[index];
        if (mixIndex >= renderedCount) {
          // Stranded paragraph: process through the enhance path.
          globalThis.__TiqianProcessParagraph.processParagraph(
            RS.processParagraphArgument(state, stranded[mixIndex - renderedCount])
          );
          return;
        }
        // Rendered paragraph: prepare and commit through the relayout session.
        var paragraph = rendered[mixIndex];
        var preparation = globalThis.__TiqianPrepareParagraphLayout.prepareParagraphLayout(
          globalThis.__TiqianRootState.currentFfi(),
          RS.prepareArgument(state, paragraph, widths[mixIndex])
        );
        commitSession.processItem(mixIndex, preparation);
      },
      function () {
        commitSession.finish();
      },
      function () {
        commitSession.rollback();
      },
      function () {
        // WidthSnapshotPerRelayoutJob: drift detection -- if root width has
        // changed since the snapshot, the session is stale.
        return commitSession.stale || Math.abs(elementFragmentBorderBoxInlineSize(root) - rootWidth) >= 0.5;
      },
      workOrder,
      paragraphsByDoc
    );
  }

  // ---------------------------------------------------------------------------
  // public surface
  // ---------------------------------------------------------------------------

  globalThis.__TiqianProgressiveDrivers = {
    enhanceProgressively: function (root, optionsBag) {
      enhanceProgressively(root, optionsBag, "Enhance");
    },
    enhanceProgressivelyFromCanonical: function (root, canonicalOptions) {
      enhanceProgressively(root, canonicalOptions, "Enhance", true);
    },
    relayout: relayout,
    rejectMissingSharedRuntimeStyles: rejectMissingSharedRuntimeStyles,
    startProgressiveJob: startProgressiveJob,
  };
})();
