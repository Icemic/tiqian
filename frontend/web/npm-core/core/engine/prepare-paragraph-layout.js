// prepareParagraphLayout (TsHost runtime port, Slice 4b). Ports the paragraph
// layout PREPARATION step of the web host from
// WebEnhancerParagraphPipeline.kt (prepareParagraphLayout, lines 319-498).
// The module computes the responsive measure, gates the prepared-DOM bridge,
// checks prepared-DOM eligibility, serializes the lowered paragraph onto the
// shared ffi wire, runs the exact-session or browser-metric layout call, and
// re-checks the plan envelope facts into three named capability verdicts.
//
// Plain script, no exports: running it installs
// globalThis.__TiqianPrepareParagraphLayout. Two consumers share this file as
// the single source of truth: the npm host (importing it for the side effect)
// and the Kotlin runtime bundle, into which a future gradle bridge task will
// embed this source verbatim. Double installation is guarded.
//
// Embedding constraint: the generator wraps this file in a Kotlin raw string,
// so the source must contain no dollar sign and no triple double-quote
// sequence. Use string concatenation, never template literals. The module is
// self-contained: ffi and the measure/renderer globals are injected by the
// caller or read from globalThis.

(function () {
  if (globalThis.__TiqianPrepareParagraphLayout) return;

  // Wire separators named after the Kotlin constants in WebEnhancerSupport.kt:
  // records join by U+001E, fields by U+001D, families by U+001F. Twin of the
  // worker-request.js serializers, which use the same values.
  var PREPARE_RECORD_SEPARATOR = '\u001e';
  var PREPARE_FIELD_SEPARATOR = '\u001d';
  var PREPARE_FAMILY_SEPARATOR = '\u001f';
  // WebEnhancerSupport.kt INLINE_EDGE_EPSILON: a clone box whose edges stay
  // below this epsilon remains eligible for prepared-DOM preparation.
  var INLINE_EDGE_EPSILON = 0.01;
  // WebEnhancerSupport.kt ZERO_ADVANCE_EPSILON: the host threshold passed to
  // the ffi diagnostics export, which pre-filters advance suspects.
  var ZERO_ADVANCE_EPSILON = 0.01;
  // PreparedParagraph.kt PREPARED_PARAGRAPH_LAYOUT_REVISION: the plan wire
  // revision the installed prepared-DOM renderer must report.
  var PREPARED_LAYOUT_REVISION = 'tiqian-layout-v2';
  // WebEnhancerSupport.kt EXACT_FONT_SESSION_CAPABILITY_FAILURES: substrings
  // that mark an exact-session layout failure as a font capability issue,
  // after which the whole paragraph retries through the browser bridge.
  var EXACT_FONT_SESSION_CAPABILITY_FAILURES = [
    'NoExactFontFace',
    'MissingGlyph',
    'MissingServerShapingReplay',
    'NoExactMetricFace',
    'NonUniformUnicodeRangeMetrics',
  ];

  // Serialize the lowered paragraph onto the shared ffi wire. Twins of the
  // worker-request.js serializer functions (lines 96-153), copied locally so
  // both files stay embeddable and import-free.
  function wireArguments(lowered) {
    var textSpans = lowered.spans.map(function (span) {
      return [
        String(span.start),
        String(span.end),
        span.style.fontFamilies.join(PREPARE_FAMILY_SEPARATOR),
        String(span.style.fontSize),
        String(span.style.fontWeight),
        String(span.style.italic),
        String(span.style.baselineShift),
      ].join(PREPARE_FIELD_SEPARATOR);
    }).join(PREPARE_RECORD_SEPARATOR);

    // InlineBoxOuterSpacing default chain: the wire never carries outer
    // spacing, so every inlineBoxes join field emits the string Narrow.
    var inlineBoxes = lowered.inlineBoxes.map(function (box) {
      return [
        String(box.start),
        String(box.end),
        String(box.inlineStart),
        String(box.inlineEnd),
        'Narrow',
      ].join(PREPARE_FIELD_SEPARATOR);
    }).join(PREPARE_RECORD_SEPARATOR);

    // LineBreakPolicy decode maps every wire policy string to the same
    // member, so the join always emits ProgressiveTechnical.
    var lineBreakSpans = lowered.lineBreakSpans.map(function (span) {
      return [
        String(span.start),
        String(span.end),
        'ProgressiveTechnical',
      ].join(PREPARE_FIELD_SEPARATOR);
    }).join(PREPARE_RECORD_SEPARATOR);

    var inlineObjects = lowered.inlineObjects.map(function (span) {
      return [
        String(span.start),
        String(span.end),
        String(span.advance),
        String(span.ascent),
        String(span.descent),
      ].join(PREPARE_FIELD_SEPARATOR);
    }).join(PREPARE_RECORD_SEPARATOR);

    // Decorations (mirrors the Kotlin parseDecorations landed in 33c5106):
    // each entry carries start, end, and the member-name kind string.
    var decorations = lowered.decorations.map(function (decoration) {
      return [
        String(decoration.start),
        String(decoration.end),
        decoration.kind,
      ].join(PREPARE_FIELD_SEPARATOR);
    }).join(PREPARE_RECORD_SEPARATOR);

    // SourceBoundary wire: dedupe into a Set, sort ascending, join by comma.
    var sourceBoundaries = Array.from(new Set(lowered.sourceBoundaries))
      .sort(function (a, b) { return a - b; })
      .join(',');

    return {
      text: lowered.text,
      fontFamilies: lowered.textStyle.fontFamilies.join(PREPARE_FAMILY_SEPARATOR),
      fontSize: lowered.textStyle.fontSize,
      lineHeight: lowered.lineHeight,
      locale: lowered.textStyle.locale,
      fontWeight: lowered.textStyle.fontWeight,
      italic: lowered.textStyle.italic,
      sourceBoundaries: sourceBoundaries,
      textSpans: textSpans,
      inlineBoxes: inlineBoxes,
      lineBreakSpans: lineBreakSpans,
      inlineObjects: inlineObjects,
      decorations: decorations,
    };
  }

  // RuntimeExactPreparedDomScope: inline the lowered-paragraph.js predicate
  // isRuntimeExactPreparedDomEligible, which requires every span to share the
  // paragraph locale. The plan wire carries one paragraph locale.
  function isPreparedDomEligible(lowered) {
    return lowered.spans.every(function (span) {
      return span.style.locale === lowered.textStyle.locale;
    });
  }

  // CanonicalPlainParagraphEvidence: twin of isCanonicalPlainParagraph in
  // lowered-paragraph.js (six collections). The wire predicate inside the
  // layout module cannot see sourceSpans or domInlineObjects because they
  // never travel the wire, so the host passes this full-model verdict as the
  // render-evidence override on both layout calls.
  function hasRenderEvidence(lowered) {
    return lowered.spans.length > 0 ||
      lowered.decorations.length > 0 ||
      lowered.inlineBoxes.length > 0 ||
      lowered.inlineObjects.length > 0 ||
      lowered.domInlineObjects.length > 0 ||
      lowered.sourceSpans.length > 0;
  }

  // PreparedDomUnifiedEligibility: inline the WebEnhancerSupport.kt
  // isPreparedDomBridgeAvailable @JsFun body, gating on the installed renderer
  // shape, schema, and matching layout revision.
  function isPreparedDomBridgeAvailable() {
    var renderer = globalThis.__TiqianPreparedDomRenderer;
    return !!(renderer &&
      typeof renderer.render === 'function' &&
      typeof renderer.release === 'function' &&
      typeof renderer.releaseRoot === 'function' &&
      renderer.schema === 1 &&
      renderer.layoutRevision === PREPARED_LAYOUT_REVISION);
  }

  function isExactSessionCapabilityFailure(error) {
    var message = String(error && error.message);
    for (var i = 0; i < EXACT_FONT_SESSION_CAPABILITY_FAILURES.length; i += 1) {
      if (message.indexOf(EXACT_FONT_SESSION_CAPABILITY_FAILURES[i]) !== -1) {
        return true;
      }
    }
    return false;
  }

  // BrowserMetricsCallArguments: the browser-metric export is the diagnostics
  // list without the leading sessionId, plus the shape and metrics callbacks
  // inserted before the trailing decorations and emphasis dot gap.
  function browserMetricsArguments(browserFallback, paragraphArguments, wire, emphasisDotGapEm, renderEvidenceOverride) {
    return paragraphArguments.concat([
      ZERO_ADVANCE_EPSILON,
      browserFallback.bridge.shapeJson,
      browserFallback.bridge.metricsJson,
      wire.decorations,
      emphasisDotGapEm,
      renderEvidenceOverride,
    ]);
  }

  /**
   * Prepare a paragraph for layout. See the slice header for the verdict
   * shapes and the Kotlin order this follows.
   *
   * @param {Object} ffi
   * @param {Object} argument
   * @returns {Object}
   */
  function prepareParagraphLayout(ffi, argument) {
    var paragraph = argument.paragraph;
    var options = argument.options;
    var exactSession = argument.exactSession;
    var browserFallback = argument.browserFallback;
    var widthOverride = argument.widthOverride;
    var ignoreUnchangedMeasure = argument.ignoreUnchangedMeasure;
    var lowered = paragraph.lowered;
    var element = paragraph.source;

    var responsive = globalThis.__TiqianResponsiveMeasure;
    var width = widthOverride != null
      ? widthOverride
      : responsive.sourceParagraphWidth(paragraph.source);
    var fontSize = lowered.textStyle.fontSize;
    var measure = responsive.effectiveLineMeasure(width, fontSize);

    if (!ignoreUnchangedMeasure && paragraph.lastMeasure === measure) {
      return { kind: 'unchanged' };
    }

    if (!isPreparedDomBridgeAvailable()) {
      return {
        kind: 'unsupported',
        name: 'PreparedDomBridgeUnavailable',
        detail: 'expectedLayoutRevision=' + PREPARED_LAYOUT_REVISION,
        element: element,
      };
    }

    if (!isPreparedDomEligible(lowered)) {
      var firstMismatch = null;
      for (var i = 0; i < lowered.spans.length; i += 1) {
        if (lowered.spans[i].style.locale !== lowered.textStyle.locale) {
          firstMismatch = lowered.spans[i];
          break;
        }
      }
      return {
        kind: 'unsupported',
        name: 'SpanLocaleMismatchUnsupported',
        detail: 'spanRange=' + (firstMismatch ? firstMismatch.start : 0) +
          '..' + (firstMismatch ? firstMismatch.end : 0) +
          '; spanLocale=' + (firstMismatch ? firstMismatch.style.locale : 'unknown') +
          '; paragraphLocale=' + lowered.textStyle.locale,
        element: element,
      };
    }

    var wire = wireArguments(lowered);
    var firstLineIndentIc = element.tagName.toUpperCase() === 'LI'
      ? 0
      : options.firstLineIndentIc;
    // The Kotlin direct path builds ParagraphStyle without lineLengthGrid,
    // whose data-class default is LineLengthGrid(enabled = true).
    var lineLengthGridEnabled = true;
    var emphasisDotGapEm = options.emphasisDotGapEm == null
      ? null
      : options.emphasisDotGapEm;
    var renderEvidenceOverride = hasRenderEvidence(lowered);

    // EngineLineMeasureMatchesResponsiveGrid: feed the quantized measure, not
    // the raw width, as maxWidthPx to every layout path.
    var paragraphArguments = [
      wire.text,
      measure,
      wire.fontFamilies,
      wire.fontSize,
      wire.lineHeight,
      wire.locale,
      wire.fontWeight,
      wire.italic,
      firstLineIndentIc,
      lineLengthGridEnabled,
      wire.sourceBoundaries,
      wire.textSpans,
      wire.inlineBoxes,
      wire.lineBreakSpans,
      wire.inlineObjects,
    ];

    var exactFontSessionUsed = browserFallback != null;

    var rawEnvelope;
    if (exactSession != null) {
      try {
        // ExactSessionSemanticLayout: one font session serves the canonical
        // plain paragraph and the semantic DOM, so no engine pair exists.
        rawEnvelope = ffi.precomputeParagraphWithDiagnostics(
          exactSession.sessionId,
          paragraphArguments[0],
          paragraphArguments[1],
          paragraphArguments[2],
          paragraphArguments[3],
          paragraphArguments[4],
          paragraphArguments[5],
          paragraphArguments[6],
          paragraphArguments[7],
          paragraphArguments[8],
          paragraphArguments[9],
          paragraphArguments[10],
          paragraphArguments[11],
          paragraphArguments[12],
          paragraphArguments[13],
          paragraphArguments[14],
          ZERO_ADVANCE_EPSILON,
          wire.decorations,
          emphasisDotGapEm,
          renderEvidenceOverride,
        );
      } catch (error) {
        if (!isExactSessionCapabilityFailure(error)) throw error;
        // PreparedDomAfterSessionFailure: a capability failure retries the
        // whole paragraph through the browser bridge. The per-run
        // ExactSessionBrowserFallback* wrappers are deliberately not ported
        // (Slice 4a note); the whole paragraph re-runs instead.
        exactFontSessionUsed = false;
        rawEnvelope = ffi.precomputeParagraphWithBrowserMetrics.apply(
          null,
          browserMetricsArguments(browserFallback, paragraphArguments, wire, emphasisDotGapEm, renderEvidenceOverride),
        );
      }
    } else {
      if (browserFallback == null) {
        throw new Error('missing browserFallback descriptor for browser-metric layout');
      }
      exactFontSessionUsed = false;
      rawEnvelope = ffi.precomputeParagraphWithBrowserMetrics.apply(
        null,
        browserMetricsArguments(browserFallback, paragraphArguments, wire, emphasisDotGapEm, renderEvidenceOverride),
      );
    }

    var envelope = JSON.parse(rawEnvelope);
    var planJson = envelope.plan;
    var plan = JSON.parse(planJson);
    var diagnostics = envelope.diagnostics;

    var capabilityIssue = diagnostics.capabilityIssues[0];
    if (capabilityIssue != null) {
      return {
        kind: 'unsupported',
        name: capabilityIssue.name,
        detail: capabilityIssue.reason,
        element: element,
      };
    }

    var invalidShaping = null;
    for (var s = 0; s < diagnostics.advanceSuspects.length; s += 1) {
      var suspect = diagnostics.advanceSuspects[s];
      if (suspect.displayText.length > 0 &&
          suspect.displayText.indexOf('\n') === -1 &&
          suspect.displayText.indexOf('\r') === -1) {
        invalidShaping = suspect;
        break;
      }
    }
    if (invalidShaping != null) {
      return {
        kind: 'unsupported',
        name: 'InvalidWebShapingAdvance',
        detail: 'text=' + invalidShaping.displayText +
          '; advance=' + invalidShaping.advance +
          '; ' + invalidShaping.reason,
        element: element,
      };
    }

    var clonedDecoration = null;
    for (var d = 0; d < lowered.sourceSpans.length; d += 1) {
      var sourceSpan = lowered.sourceSpans[d];
      var crossing = 0;
      for (var l = 0; l < plan.lines.length; l += 1) {
        var line = plan.lines[l];
        if (line.rangeStart < sourceSpan.end && line.rangeEnd > sourceSpan.start) {
          crossing += 1;
        }
      }
      if (sourceSpan.inlineBoxStyle.boxDecorationBreak === 'clone' &&
          (Math.abs(sourceSpan.inlineBoxStyle.inlineStart) >= INLINE_EDGE_EPSILON ||
            Math.abs(sourceSpan.inlineBoxStyle.inlineEnd) >= INLINE_EDGE_EPSILON) &&
          crossing > 1) {
        clonedDecoration = sourceSpan;
        break;
      }
    }
    if (clonedDecoration != null) {
      return {
        kind: 'unsupported',
        name: 'InlineCloneDecorationBreakUnsupported',
        detail: clonedDecoration.element.tagName.toLowerCase(),
        element: element,
      };
    }

    return {
      kind: 'ready',
      rawEnvelope: rawEnvelope,
      planJson: planJson,
      plan: plan,
      diagnostics: diagnostics,
      width: width,
      measure: measure,
      exactFontSessionUsed: exactFontSessionUsed,
    };
  }

  globalThis.__TiqianPrepareParagraphLayout = {
    prepareParagraphLayout: prepareParagraphLayout,
  };
})();
