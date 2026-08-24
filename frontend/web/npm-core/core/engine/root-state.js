// RootState maintenance for the enhance pipeline (TsHost runtime port,
// Slice 5). Ports the Kotlin RootState data class and its state methods from
// WebEnhancer.kt (lines 206-272, 454-489) together with the engine-state and
// argument descriptors from WebEnhancerTsHost.kt (lines 225-270). The TS
// engine entry (Slice 6) binds the ffi facade once at startup.
//
// Consumes __TiqianLifecycle, __TiqianEligibility, __TiqianCanvasFonts, and
// __TiqianBrowserMetricsBridge.
//
// Plain script, no exports: running it installs globalThis.__TiqianRootState.
// Two consumers share this file as the single source of truth: the npm host
// (importing it for the side effect) and the Kotlin runtime bundle, into
// which a future gradle bridge task will embed this source verbatim. Double
// installation is guarded.
//
// Embedding constraint: the generator wraps this file in a Kotlin raw string,
// so the source must contain no dollar sign and no triple double-quote
// sequence. Use string concatenation, never template literals. Use var
// declarations.

(function () {
  if (globalThis.__TiqianRootState) return;

  var EXACT_PREPARED_FALLBACK_ATTRIBUTE = "data-tiqian-exact-layout-fallback";
  var ROOT_SELECTOR = "tiqian-prose, [data-tiqian-root]";
  var CAPABILITY_DETAIL_LIMIT = 512;

  // DetachedRootWeakOwnership: navigation can discard a rendered article
  // without reconstructing its semantic DOM. Weak ownership retains the
  // source fragments only if a host later reconnects that exact element.
  var states = new WeakMap();

  // The ffi facade the TS orchestrators consume. The Kotlin side owns it as
  // the module-level tsFfiFacade val; here the TS engine entry binds it once
  // at startup and tests bind a fake.
  var ffi = null;

  function bindFfi(bound) {
    ffi = bound;
  }

  function currentFfi() {
    return ffi;
  }

  // belongsToRootScope: a candidate belongs to this root when its nearest
  // scope-owning ancestor is absent, is the root itself, or lives outside the
  // root. Mirror of the belongsToRootScope @JsFun in WebEnhancerSupport.kt;
  // the closest guard keeps fake elements honest.
  function belongsToRootScope(paragraph, root, selector) {
    if (!paragraph.closest) return true;
    var owner = paragraph.closest(selector);
    return !owner || owner === root || !root.contains(owner);
  }

  // RuntimeEligibleMeasureSet: progressive staleness compares the
  // same leaf paragraphs that can actually enter the pipeline.
  // Measuring a host-owned outer <li> and later rendering its
  // child <p> changes the container's live width/measure, which
  // used to roll back every valid child as a false stale job.
  function paragraphCandidates(root, selector) {
    var nodes = root.querySelectorAll(selector);
    var eligibility = globalThis.__TiqianEligibility;
    var result = [];
    for (var i = 0; i < nodes.length; i += 1) {
      var paragraph = nodes[i];
      if (belongsToRootScope(paragraph, root, ROOT_SELECTOR) &&
          eligibility.shouldTryParagraph(paragraph)) {
        result.push(paragraph);
      }
    }
    return result;
  }

  // The canvas modules own their probe nodes; attachProbe keeps the probe in
  // the document without duplicating it across measures.
  function browserMetricsEnv() {
    return {
      createCanvasContext: function () {
        return document.createElement("canvas").getContext("2d");
      },
      createProbeElement: function () {
        return document.createElement("span");
      },
      attachProbe: function (node) {
        if (!node.parentNode) document.body.appendChild(node);
      },
    };
  }

  // The {bridge} descriptor every TS layout lane consumes. The inner bridge
  // adapts the canvas shaper and metrics resolver to the two JSON callbacks
  // of precomputeParagraphWithBrowserMetrics. Built once per root.
  function buildBrowserFallbackDescriptor(resolved) {
    var fontFamilies = resolved.fontFamilies;
    // buildFontFamiliesConfigJs renames the resolved monospace family to the
    // latinMonospace key that canvas-fonts.js reads for the LatinText role.
    var fonts = globalThis.__TiqianCanvasFonts.createFontFamilies({
      cjk: fontFamilies.cjk,
      latin: fontFamilies.latin,
      latinMonospace: fontFamilies.monospace,
      cjkSerif: fontFamilies.cjkSerif,
      latinSerif: fontFamilies.latinSerif,
    });
    var bridge = globalThis.__TiqianBrowserMetricsBridge.createBrowserMetricsBridge({
      fonts: fonts,
      cjkDashCapability: resolved.cjkDashCapability,
      env: browserMetricsEnv(),
    });
    return { bridge: bridge };
  }

  function createRootState(root, optionsBag) {
    root.removeAttribute(EXACT_PREPARED_FALLBACK_ATTRIBUTE);
    var lifecycle = globalThis.__TiqianLifecycle;
    var canonical = lifecycle.optionsFromJs(optionsBag);
    // allowsSnapshotExactLayout ? options : options.copy(exactFontSession =
    // null): an exact snapshot only reproduces the host with root defaults,
    // so configured typography lowers the exact font session.
    var exactEligible = lifecycle.allowsSnapshotExactLayout(canonical)
      ? canonical
      : lifecycle.withoutExactFontSession(canonical);
    var resolved = lifecycle.withRootDefaults(exactEligible, root);
    return newRootState(root, resolved);
  }

  function createRootStateFromCanonical(root, canonicalOptions) {
    // Re-entry path for relayout/refresh: the canonical options already came
    // from optionsFromJs output shape, so the snapshot gate is skipped.
    root.removeAttribute(EXACT_PREPARED_FALLBACK_ATTRIBUTE);
    var resolved = globalThis.__TiqianLifecycle.withRootDefaults(canonicalOptions, root);
    return newRootState(root, resolved);
  }

  // Canonical TS options and the browser fallback descriptor are built
  // once per root and consumed by every embedded TS orchestrator. Live JS
  // arrays: the TS session module splices and pushes these by reference,
  // so the host mutates the same storage.
  // PreparedDomLane: every paragraph renders through the prepared DOM,
  // including roots that never configured an exact font session. After
  // a replay fails geometry validation the flag distrusts the exact
  // session metrics for the whole root; paragraphs keep rendering
  // through the prepared bridge with browser metrics, and the
  // per-paragraph validator still guards every render.
  function newRootState(root, resolved) {
    return {
      root: root,
      options: resolved,
      browserFallback: buildBrowserFallbackDescriptor(resolved),
      paragraphs: [],
      issues: [],
      preparedDomEnabled: true,
      preparedDomFallback: null,
    };
  }

  function getState(root) {
    return states.get(root);
  }

  function setState(root, state) {
    states.set(root, state);
  }

  function deleteState(root) {
    states.delete(root);
  }

  function activeTsOptions(state) {
    if (state.preparedDomEnabled) return state.options;
    return globalThis.__TiqianLifecycle.withoutExactFontSession(state.options);
  }

  // Kotlin resolves the descriptor off activeOptions().conformingExactFont
  // SessionId(), so once prepared DOM is disabled the session is always null;
  // the TS options lane reads the same active options here.
  function activeExactSessionDescriptor(state) {
    var sessionId = globalThis.__TiqianLifecycle.conformingExactFontSessionId(activeTsOptions(state));
    if (sessionId == null) return null;
    return { sessionId: sessionId };
  }

  function disableExactPreparedDom(state, detail) {
    if (!state.preparedDomEnabled) return;
    state.preparedDomEnabled = false;
    state.preparedDomFallback = String(detail).slice(0, CAPABILITY_DETAIL_LIMIT);
    state.root.setAttribute(EXACT_PREPARED_FALLBACK_ATTRIBUTE, state.preparedDomFallback);
  }

  function engineState(state) {
    return {
      ffi: currentFfi(),
      options: state.options,
      preparedDomEnabled: state.preparedDomEnabled,
      exactSession: activeExactSessionDescriptor(state),
      browserFallback: state.browserFallback,
      onIssue: function (issue) { state.issues.push(issue); },
      onParagraphCommitted: function (item) { state.paragraphs.push(item); },
      onDisableExactPreparedDom: function (detail) { disableExactPreparedDom(state, detail); },
      paragraphs: state.paragraphs,
      issues: state.issues,
    };
  }

  function processParagraphArgument(state, paragraph) {
    return { ffi: currentFfi(), paragraph: paragraph, state: engineState(state) };
  }

  function sessionArgument(state) {
    return { paragraphs: state.paragraphs, state: engineState(state) };
  }

  function prepareArgument(state, paragraph, widthOverride) {
    return {
      paragraph: paragraph,
      options: activeTsOptions(state),
      exactSession: activeExactSessionDescriptor(state),
      browserFallback: state.browserFallback,
      widthOverride: widthOverride == null ? null : widthOverride,
    };
  }

  function strandedSourceParagraphs(root, state) {
    var candidates = paragraphCandidates(root, state.options.paragraphSelector);
    if (state.paragraphs.length === 0) return candidates;
    var renderedSources = new Set();
    for (var i = 0; i < state.paragraphs.length; i += 1) {
      renderedSources.add(state.paragraphs[i].source);
    }
    var result = [];
    for (var j = 0; j < candidates.length; j += 1) {
      if (!renderedSources.has(candidates[j])) result.push(candidates[j]);
    }
    return result;
  }

  function observableSnapshotCount(root) {
    var value = Number(root.getAttribute("data-tiqian-snapshot-count"));
    return Number.isSafeInteger(value) && value > 0 ? value : 0;
  }

  function publishState(state, keepEmpty) {
    var hasWork = state.paragraphs.length > 0 || state.issues.length > 0;
    if (!hasWork && !keepEmpty) {
      deleteState(state.root);
      state.root.removeAttribute("data-tiqian-enhanced");
      state.root.removeAttribute("data-tiqian-enhanced-count");
      state.root.removeAttribute("data-tiqian-issue-count");
      return;
    }
    setState(state.root, state);
    state.root.setAttribute("data-tiqian-enhanced", "true");
    state.root.setAttribute(
      "data-tiqian-enhanced-count",
      String(state.paragraphs.length + observableSnapshotCount(state.root)),
    );
    if (state.issues.length === 0) {
      state.root.removeAttribute("data-tiqian-issue-count");
    } else {
      state.root.setAttribute("data-tiqian-issue-count", String(state.issues.length));
    }
  }

  globalThis.__TiqianRootState = {
    bindFfi: bindFfi,
    currentFfi: currentFfi,
    createRootState: createRootState,
    createRootStateFromCanonical: createRootStateFromCanonical,
    activeTsOptions: activeTsOptions,
    activeExactSessionDescriptor: activeExactSessionDescriptor,
    disableExactPreparedDom: disableExactPreparedDom,
    engineState: engineState,
    processParagraphArgument: processParagraphArgument,
    sessionArgument: sessionArgument,
    prepareArgument: prepareArgument,
    getState: getState,
    setState: setState,
    deleteState: deleteState,
    paragraphCandidates: paragraphCandidates,
    strandedSourceParagraphs: strandedSourceParagraphs,
    publishState: publishState,
  };
})();