// HostContentReconcile: classification and DOM preparation for live-DOM
// content changes on an enhanced root.
//
// Plain script, no exports: running it installs
// globalThis.__TiqianContentReconcile. Two consumers share this file: the
// npm host (behavior tests drive the installed global through the runtime)
// and the Kotlin runtime bundle, into which the generateContentReconcileBridge
// gradle task embeds this source verbatim.
//
// Embedding constraint: the generator wraps this file in a Kotlin raw
// string, so the source must contain no dollar sign and no triple
// double-quote sequence, and the Kotlin parser accepts only a classic JS
// subset: no optional chaining, no nullish coalescing, no spread arguments,
// no for-of loops, no bare catch. Use explicit conditionals, indexed loops
// and apply instead.

(function () {
  if (globalThis.__TiqianContentReconcile) return;

  function custodyApi() {
    return globalThis.__TiqianCustody;
  }

  function releasePreparedStyles(element) {
    var renderer = globalThis.__TiqianPreparedDomRenderer;
    if (renderer && renderer.release && renderer.release(element) === true) return true;
    return false;
  }

  // Read-only drift probe for captured in-flight jobs: answers the same
  // per-paragraph classification question as classifyReconcile without
  // touching the DOM, so element.js cancels only on real drift.
  function probeContentDrift(trackedSources) {
    var drifted = 0;
    var dead = 0;
    var custody = 0;
    for (var index = 0; index < trackedSources.length; index++) {
      var source = trackedSources[index];
      if (!source.isConnected) {
        dead += 1;
      } else if (!custodyApi().renderedMatches(source)) {
        drifted += 1;
      } else if (!custodyApi().custodyMatches(source)) {
        custody += 1;
      }
    }
    return '{"unknown":0,"drifted":' + drifted + ',"dead":' + dead +
      ',"custody":' + custody + '}';
  }

  // Per-paragraph classification, never per MutationRecord. DeadTrackedParagraphDrop
  // counts tracked sources the host detached; the RenderedContentInvariant
  // identity check flags drifted paragraphs; the custody identity check
  // flags custody drift. A tainted host survives only when connected,
  // inside a root, tracked, and not already classified as drifted. A
  // stranded candidate is skipped when it already failed lowering with a
  // capability marker and was never rendered (StrandedCapabilityNoRetry).
  function classifyReconcile(spec) {
    var trackedSources = spec.trackedSources;
    var drifted = [];
    var custodyDrifted = [];
    var dead = 0;
    var trackedSet = new Set();
    for (var trackIndex = 0; trackIndex < trackedSources.length; trackIndex++) {
      var trackedSource = trackedSources[trackIndex];
      trackedSet.add(trackedSource);
      if (!trackedSource.isConnected) {
        dead += 1;
      } else if (!custodyApi().renderedMatches(trackedSource)) {
        drifted.push(trackedSource);
      } else if (!custodyApi().custodyMatches(trackedSource)) {
        custodyDrifted.push(trackedSource);
      }
    }
    var driftedSources = new Set();
    var driftedIndex;
    for (driftedIndex = 0; driftedIndex < drifted.length; driftedIndex++) {
      driftedSources.add(drifted[driftedIndex]);
    }
    for (driftedIndex = 0; driftedIndex < custodyDrifted.length; driftedIndex++) {
      driftedSources.add(custodyDrifted[driftedIndex]);
    }
    var tainted = spec.tainted || [];
    var taintedTracked = [];
    for (var taintedIndex = 0; taintedIndex < tainted.length; taintedIndex++) {
      var host = tainted[taintedIndex];
      if (!host.isConnected) continue;
      if (!(host.closest && host.closest(spec.rootSelector))) continue;
      if (!trackedSet.has(host)) continue;
      if (driftedSources.has(host)) continue;
      taintedTracked.push(host);
    }
    var stranded = [];
    var candidates = spec.strandedCandidates || [];
    for (var candidateIndex = 0; candidateIndex < candidates.length; candidateIndex++) {
      var candidate = candidates[candidateIndex];
      if (!candidate.hasAttribute("data-tiqian-capability-issue") ||
          candidate.hasAttribute("data-tq-rendered")) {
        stranded.push(candidate);
      }
    }
    var empty = drifted.length === 0 && custodyDrifted.length === 0 &&
      taintedTracked.length === 0 && stranded.length === 0;
    return {
      outcome: empty ? "idle" : "work",
      drifted: drifted,
      custody: custodyDrifted,
      tainted: taintedTracked,
      stranded: stranded,
      dead: dead,
      json: '{"outcome":"' + (empty ? "idle" : "work") + '","drifted":' +
        drifted.length + ',"custody":' + custodyDrifted.length +
        ',"tainted":' + taintedTracked.length + ',"stranded":' +
        stranded.length + ',"dead":' + dead + '}',
    };
  }

  // HostEditRelowering: the host replaced or edited the live children of a
  // rendered paragraph. Release prepared styles, restore the engine-owned
  // shell, stamp the rendered marker, and let the caller re-lower the
  // surviving live content as the new custody source.
  function prepareTrackedParagraphForRelowering(element) {
    releasePreparedStyles(element);
    custodyApi().restoreShell(element);
    custodyApi().stampRendered(element);
  }

  // CloneDescaffoldEngineMarkup: innerHTML re-projection hands the runtime a
  // clone that still carries engine scaffolding: line markers, copy-ignore
  // spans, engine break elements, prepared value styles, and the paragraph
  // takeover attributes. Remove exactly those engine-authored artifacts so
  // the clone lowers as ordinary host content. Host elements and host
  // inline styles survive untouched.
  function stripEngineMarkupFromStrandedParagraph(paragraph) {
    releasePreparedStyles(paragraph);
    // The hidden data-tq-hard-break span is the only place a cloned hard
    // break keeps its source form. Restore a bare br before removing
    // engine elements: a newline text node would be folded into a space by
    // collapse-mode re-lowering and lose the break.
    var hardBreaks = paragraph.querySelectorAll("[data-tq-hard-break]");
    for (var breakIndex = 0; breakIndex < hardBreaks.length; breakIndex++) {
      var hardBreak = hardBreaks[breakIndex];
      if (hardBreak.parentNode) {
        hardBreak.parentNode.replaceChild(document.createElement("br"), hardBreak);
      }
    }
    var artifacts = paragraph.querySelectorAll(
      "[data-tq-copy-ignore], [data-tq-engine-break], [data-tq-src], [data-tq-prepared-value-styles]",
    );
    for (var artifactIndex = 0; artifactIndex < artifacts.length; artifactIndex++) {
      var artifact = artifacts[artifactIndex];
      if (artifact.parentNode) artifact.parentNode.removeChild(artifact);
    }
    // Engine run spans position glyphs through --tq-* custom properties.
    // Those values are meaningless on host content and would survive
    // lowering, so strip them from every remaining descendant.
    var descendants = paragraph.querySelectorAll("*");
    for (var descIndex = 0; descIndex < descendants.length; descIndex++) {
      var element = descendants[descIndex];
      var engineProperties = [];
      for (var styleIndex = 0; styleIndex < element.style.length; styleIndex++) {
        var name = element.style.item(styleIndex);
        if (name.indexOf("--tq-") === 0) engineProperties.push(name);
      }
      for (var removeIndex = 0; removeIndex < engineProperties.length; removeIndex++) {
        element.style.removeProperty(engineProperties[removeIndex]);
      }
    }
    paragraph.removeAttribute("data-tq-rendered");
    paragraph.removeAttribute("data-tq-canonical-plain");
    paragraph.removeAttribute("data-tq-canonical-source");
    paragraph.removeAttribute("data-tq-exact-prepared-dom");
    paragraph.removeAttribute("data-tq-runtime-render-font");
    paragraph.removeAttribute("data-tq-host-inline-size");
    paragraph.removeAttribute("data-tiqian-capability-issue");
    paragraph.removeAttribute("data-tiqian-capability-detail");
    // EngineInlineStyleStrippingOnClone: takeover writes position,
    // inline-size and font-size with important priority. Originals are
    // unknown on a clone, so remove exactly those engine-signed writes.
    if (paragraph.style.getPropertyPriority("position") === "important" &&
        paragraph.style.getPropertyValue("position") === "relative") {
      paragraph.style.removeProperty("position");
    }
    if (paragraph.style.getPropertyPriority("inline-size") === "important") {
      paragraph.style.removeProperty("inline-size");
    }
    if (paragraph.style.getPropertyPriority("font-size") === "important") {
      paragraph.style.removeProperty("font-size");
    }
    var styleAttribute = paragraph.getAttribute("style");
    if (styleAttribute === null || styleAttribute.trim() === "") {
      paragraph.removeAttribute("style");
    }
  }

  globalThis.__TiqianContentReconcile = {
    probeContentDrift: probeContentDrift,
    classifyReconcile: classifyReconcile,
    prepareTrackedParagraphForRelowering: prepareTrackedParagraphForRelowering,
    stripEngineMarkupFromStrandedParagraph: stripEngineMarkupFromStrandedParagraph,
  };
})();
