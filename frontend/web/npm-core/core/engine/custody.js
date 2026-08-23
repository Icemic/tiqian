// Source custody for enhanced paragraphs.
//
// Plain script, no exports: running it installs globalThis.__TiqianCustody.
// Two consumers share this file as the single source of truth: the npm host
// (importing it for the side effect) and the Kotlin runtime bundle, into
// which the generateCustodyBridge gradle task embeds this source verbatim.
// Double installation is guarded.
//
// Embedding constraint: the generator wraps this file in a Kotlin raw string,
// so the source must contain no dollar sign and no triple double-quote
// sequence. Use string concatenation, never template literals.

(function () {
  if (globalThis.__TiqianCustody) return;

  var CANONICAL_SOURCE_ATTRIBUTE = "data-tq-canonical-source";
  var EXACT_PREPARED_DOM_ATTRIBUTE = "data-tq-exact-prepared-dom";
  var RUNTIME_RENDER_FONT_ATTRIBUTE = "data-tq-runtime-render-font";
  var HOST_INLINE_SIZE_ATTRIBUTE = "data-tq-host-inline-size";

  // Per-paragraph custody state, keyed weakly so a discarded paragraph can
  // be collected together with its state.
  var states = new WeakMap();

  function stateOf(source) {
    var state = states.get(source);
    if (!state) {
      throw new Error("custody state missing for paragraph");
    }
    return state;
  }

  function liveChildNodes(element) {
    var nodes = [];
    var child = element.firstChild;
    while (child) {
      nodes.push(child);
      child = child.nextSibling;
    }
    return nodes;
  }

  function restoreAttribute(element, name, value) {
    if (value === null || value === undefined) {
      element.removeAttribute(name);
    } else {
      element.setAttribute(name, value);
    }
  }

  function stampCustodyContent(state, source) {
    state.custodyNodes = liveChildNodes(state.originalContent);
    source.__tqCustodyFragment = state.originalContent;
    installCustodyCommitForwarding(source);
  }

  function stampRenderedContent(state, source) {
    state.renderedNodes = liveChildNodes(source);
  }

  // CustodyAnchoredCommitForwarding: host frameworks keep node references
  // and commit edits through the paragraph's own mutation methods while the
  // semantic source lives in custody. Redirect those calls into the published
  // fragment unless the engine itself is writing (__tqCustodyEngineWrites
  // above zero). The overrides read the published fragment at call time, so
  // a re-take with a fresh fragment needs no re-install. An empty fragment
  // means the paragraph is not under custody and every branch falls through
  // to native.
  function installCustodyCommitForwarding(paragraph) {
    if (paragraph.__tqCustodyForwarding) {
      return;
    }
    var nativeRemoveChild = Node.prototype.removeChild;
    var nativeInsertBefore = Node.prototype.insertBefore;
    var nativeReplaceChild = Node.prototype.replaceChild;
    var nativeAppendChild = Node.prototype.appendChild;
    var activeCustody = function () {
      var fragment = paragraph.__tqCustodyFragment;
      return fragment && fragment.childNodes.length > 0 ? fragment : null;
    };
    var heldInCustody = function (node) {
      var fragment = paragraph.__tqCustodyFragment;
      return !!fragment && !!node && node.parentNode === fragment;
    };
    var engineWriting = function () {
      return paragraph.__tqCustodyEngineWrites > 0;
    };
    paragraph.removeChild = function (child) {
      if (engineWriting()) return nativeRemoveChild.call(paragraph, child);
      if (heldInCustody(child)) return paragraph.__tqCustodyFragment.removeChild(child);
      return nativeRemoveChild.call(paragraph, child);
    };
    paragraph.insertBefore = function (node, ref) {
      if (engineWriting()) return nativeInsertBefore.call(paragraph, node, ref);
      if (heldInCustody(ref)) return paragraph.__tqCustodyFragment.insertBefore(node, ref);
      if (!ref && node && node.nodeType !== 11) {
        var fragment = activeCustody();
        if (fragment) return fragment.appendChild(node);
      }
      return nativeInsertBefore.call(paragraph, node, ref);
    };
    paragraph.replaceChild = function (next, prev) {
      if (engineWriting()) return nativeReplaceChild.call(paragraph, next, prev);
      if (heldInCustody(prev)) return paragraph.__tqCustodyFragment.replaceChild(next, prev);
      return nativeReplaceChild.call(paragraph, next, prev);
    };
    paragraph.appendChild = function (node) {
      if (engineWriting()) return nativeAppendChild.call(paragraph, node);
      if (node && node.nodeType !== 11) {
        var fragment = activeCustody();
        if (fragment) return fragment.appendChild(node);
      }
      return nativeAppendChild.call(paragraph, node);
    };
    paragraph.__tqCustodyForwarding = true;
  }

  // Captures every host-owned attribute and inline style entry the engine may
  // overwrite during takeover. Must run before the first engine write of the
  // current takeover (applyConfiguredHostFontSize in the pipeline).
  function begin(
    source,
    renderedAttribute,
    preparedFlowAttribute,
    canonicalSourceAttribute,
    exactPreparedDomAttribute,
    langAttribute,
    styleAttribute,
    position,
    positionPriority,
    inlineSize,
    inlineSizePriority,
    fontSize,
    fontSizePriority,
    hostInlineSizeAttribute
  ) {
    states.set(source, {
      originalContent: null,
      renderedNodes: [],
      custodyNodes: [],
      originalRenderedAttribute: renderedAttribute,
      originalPreparedFlowAttribute: preparedFlowAttribute,
      originalCanonicalSourceAttribute: canonicalSourceAttribute,
      originalExactPreparedDomAttribute: exactPreparedDomAttribute,
      originalLangAttribute: langAttribute,
      originalStyleAttribute: styleAttribute,
      originalPosition: position,
      originalPositionPriority: positionPriority,
      originalInlineSize: inlineSize,
      originalInlineSizePriority: inlineSizePriority,
      originalFontSize: fontSize,
      originalFontSizePriority: fontSizePriority,
      originalHostInlineSizeAttribute: hostInlineSizeAttribute,
      containingBlockApplied: false,
      hostInlineSizeApplied: null,
      hostFontSizeApplied: null,
    });
  }

  // Moves the semantic source children into a detached custody fragment.
  function take(source, hostFontSizeApplied) {
    var state = stateOf(source);
    state.hostFontSizeApplied = hostFontSizeApplied;
    var fragment = globalThis.document.createDocumentFragment();
    while (source.firstChild) {
      fragment.appendChild(source.firstChild);
    }
    state.originalContent = fragment;
  }

  // Publishes the custody fragment on the paragraph and installs commit
  // forwarding. Runs after the pipeline stabilized the source inline size.
  function commit(source, hostInlineSizeApplied) {
    var state = stateOf(source);
    state.hostInlineSizeApplied = hostInlineSizeApplied;
    stampCustodyContent(state, source);
  }

  function stampRendered(source) {
    stampRenderedContent(stateOf(source), source);
  }

  function renderedMatches(source) {
    var state = stateOf(source);
    var recorded = state.renderedNodes;
    var child = source.firstChild;
    var index = 0;
    while (child) {
      if (index >= recorded.length || recorded[index] !== child) return false;
      index += 1;
      child = child.nextSibling;
    }
    return index === recorded.length;
  }

  function custodyMatches(source) {
    var state = stateOf(source);
    var recorded = state.custodyNodes;
    var child = state.originalContent.firstChild;
    var index = 0;
    while (child) {
      if (index >= recorded.length || recorded[index] !== child) return false;
      index += 1;
      child = child.nextSibling;
    }
    return index === recorded.length;
  }

  // Snapshots the current rendered output of a paragraph at a slice boundary
  // so a later rollback can replay it. Drains the live children into the
  // snapshot fragment. Reads snapshot attributes before draining, matching
  // the previous Kotlin ordering.
  function captureLive(source, lastMeasure) {
    var state = stateOf(source);
    var content = globalThis.document.createDocumentFragment();
    var snapshot = {
      source: source,
      content: content,
      renderedAttribute: source.getAttribute("data-tq-rendered"),
      preparedFlowAttribute: source.getAttribute("data-tq-canonical-plain"),
      canonicalSourceAttribute: source.getAttribute(CANONICAL_SOURCE_ATTRIBUTE),
      exactPreparedDomAttribute: source.getAttribute(EXACT_PREPARED_DOM_ATTRIBUTE),
      langAttribute: source.getAttribute("lang"),
      styleAttribute: source.getAttribute("style"),
      capabilityNameAttribute: source.getAttribute("data-tiqian-capability-issue"),
      capabilityDetailAttribute: source.getAttribute("data-tiqian-capability-detail"),
      lastMeasure: lastMeasure,
      containingBlockApplied: state.containingBlockApplied,
      hostInlineSizeApplied: state.hostInlineSizeApplied,
      hostInlineSizeAttribute: source.getAttribute(HOST_INLINE_SIZE_ATTRIBUTE),
      originalContentHadChildren: state.originalContent.firstChild !== null,
    };
    while (source.firstChild) {
      content.appendChild(source.firstChild);
    }
    stampRenderedContent(state, source);
    return snapshot;
  }

  // Replays snapshots in reverse order. Each replayed paragraph gets its
  // snapshot content, attributes and flags back; the caller receives the
  // lastMeasure per source element.
  function rollback(snapshots) {
    var results = [];
    for (var i = snapshots.length - 1; i >= 0; i--) {
      var snapshot = snapshots[i];
      var source = snapshot.source;
      var state = stateOf(source);
      if (snapshot.originalContentHadChildren && state.originalContent.firstChild === null) {
        // restoreParagraph() handed the semantic source fragment back to the
        // live DOM; move those exact nodes into source custody again before
        // replaying the previous rendered fragment.
        while (source.firstChild) {
          state.originalContent.appendChild(source.firstChild);
        }
        stampCustodyContent(state, source);
      } else {
        while (source.firstChild) {
          source.removeChild(source.firstChild);
        }
      }
      source.appendChild(snapshot.content);
      restoreAttribute(source, "data-tq-rendered", snapshot.renderedAttribute);
      restoreAttribute(source, "data-tq-canonical-plain", snapshot.preparedFlowAttribute);
      restoreAttribute(source, CANONICAL_SOURCE_ATTRIBUTE, snapshot.canonicalSourceAttribute);
      restoreAttribute(source, EXACT_PREPARED_DOM_ATTRIBUTE, snapshot.exactPreparedDomAttribute);
      restoreAttribute(source, "lang", snapshot.langAttribute);
      restoreAttribute(source, "style", snapshot.styleAttribute);
      restoreAttribute(source, "data-tiqian-capability-issue", snapshot.capabilityNameAttribute);
      restoreAttribute(source, "data-tiqian-capability-detail", snapshot.capabilityDetailAttribute);
      state.containingBlockApplied = snapshot.containingBlockApplied;
      state.hostInlineSizeApplied = snapshot.hostInlineSizeApplied;
      restoreAttribute(source, HOST_INLINE_SIZE_ATTRIBUTE, snapshot.hostInlineSizeAttribute);
      stampRenderedContent(state, source);
      results.push({ source: source, lastMeasure: snapshot.lastMeasure });
    }
    return results;
  }

  // Hands the semantic source back to the live DOM and restores the shell
  // the engine overwrote. Used by destroy and by unsupported relayouts.
  function restoreParagraph(source) {
    var state = stateOf(source);
    var renderer = globalThis.__TiqianPreparedDomRenderer;
    if (renderer) {
      renderer.release(source);
    }
    while (source.firstChild) {
      source.removeChild(source.firstChild);
    }
    source.appendChild(state.originalContent);
    // The drain empties custody. Restamp so a paragraph that stays tracked
    // through the relayout-unsupported window does not read as host drift.
    stampCustodyContent(state, source);
    restoreShell(source);
    stampRenderedContent(state, source);
  }

  // Restores the paragraph element attributes and inline style entries the
  // engine overwrote during takeover. Shared by the custody restore path and
  // the content-reconcile path that keeps host-mutated live children.
  function restoreShell(source) {
    var state = stateOf(source);
    var style = source.style;
    restoreAttribute(source, "data-tq-rendered", state.originalRenderedAttribute);
    restoreAttribute(source, "data-tq-canonical-plain", state.originalPreparedFlowAttribute);
    restoreAttribute(source, CANONICAL_SOURCE_ATTRIBUTE, state.originalCanonicalSourceAttribute);
    restoreAttribute(source, EXACT_PREPARED_DOM_ATTRIBUTE, state.originalExactPreparedDomAttribute);
    source.removeAttribute(RUNTIME_RENDER_FONT_ATTRIBUTE);
    restoreAttribute(source, "lang", state.originalLangAttribute);
    if (
      state.containingBlockApplied &&
      style.getPropertyValue("position") === "relative" &&
      style.getPropertyPriority("position") === "important"
    ) {
      if (state.originalPosition === "") {
        style.removeProperty("position");
      } else {
        style.setProperty("position", state.originalPosition, state.originalPositionPriority);
      }
    }
    var appliedInlineSize = state.hostInlineSizeApplied;
    if (
      appliedInlineSize !== null &&
      source.getAttribute(HOST_INLINE_SIZE_ATTRIBUTE) === "true" &&
      style.getPropertyValue("inline-size") === appliedInlineSize &&
      style.getPropertyPriority("inline-size") === "important"
    ) {
      if (state.originalInlineSize === "") {
        style.removeProperty("inline-size");
      } else {
        style.setProperty("inline-size", state.originalInlineSize, state.originalInlineSizePriority);
      }
    }
    var appliedFontSize = state.hostFontSizeApplied;
    if (
      appliedFontSize !== null &&
      style.getPropertyValue("font-size") === appliedFontSize &&
      style.getPropertyPriority("font-size") === "important"
    ) {
      if (state.originalFontSize === "") {
        style.removeProperty("font-size");
      } else {
        style.setProperty("font-size", state.originalFontSize, state.originalFontSizePriority);
      }
    }
    restoreAttribute(source, HOST_INLINE_SIZE_ATTRIBUTE, state.originalHostInlineSizeAttribute);
    if (state.originalStyleAttribute === null) {
      var currentStyle = source.getAttribute("style");
      if (currentStyle === null || currentStyle.trim() === "") {
        source.removeAttribute("style");
      }
    }
    state.containingBlockApplied = false;
    state.hostInlineSizeApplied = null;
  }

  // The engine positions line markers absolutely against the paragraph, so a
  // statically positioned paragraph must become its containing block first.
  function ensureContainingBlock(source) {
    var state = stateOf(source);
    if (state.containingBlockApplied) return;
    var position = globalThis.getComputedStyle(source).getPropertyValue("position");
    if (position.trim().toLowerCase() !== "static") return;
    source.style.setProperty("position", "relative", "important");
    state.containingBlockApplied = true;
  }

  globalThis.__TiqianCustody = {
    begin: begin,
    take: take,
    commit: commit,
    stampRendered: stampRendered,
    renderedMatches: renderedMatches,
    custodyMatches: custodyMatches,
    captureLive: captureLive,
    rollback: rollback,
    restoreParagraph: restoreParagraph,
    restoreShell: restoreShell,
    ensureContainingBlock: ensureContainingBlock,
  };
})();
