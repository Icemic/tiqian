// Worker layout request serialization (TsHost runtime port, Slice 3b). Ports
// workerLayoutRequestJson from WebEnhancerSupport.kt and the lowered-paragraph
// workerLayoutRequest overload from WebEnhancerParagraphPipeline.kt into one
// module. The root+paragraph overload that feeds Kotlin-core classifier hooks
// through lower() ports with the pipeline in Slice 4 and is intentionally not
// here.
//
// Plain script, no exports: running it installs globalThis.__TiqianWorkerRequest.
// Two consumers share this file as the single source of truth: the npm host
// (importing it for the side effect) and the Kotlin runtime bundle, into
// which a future gradle bridge task will embed this source verbatim. Double
// installation is guarded.
//
// Embedding constraint: the generator wraps this file in a Kotlin raw string,
// so the source must contain no dollar sign and no triple double-quote
// sequence. Use string concatenation, never template literals.

(function () {
  if (globalThis.__TiqianWorkerRequest) return;

  // Wire separators named after the Kotlin constants in WebEnhancerSupport.kt:
  // records join by U+001E, fields by U+001D, families by U+001F.
  var WORKER_RECORD_SEPARATOR = '\u001e';
  var WORKER_FIELD_SEPARATOR = '\u001d';
  var WORKER_FAMILY_SEPARATOR = '\u001f';
  // WebEnhancerSupport.kt INLINE_EDGE_EPSILON: a clone box whose edges stay
  // below this epsilon remains eligible for Worker preparation.
  var INLINE_EDGE_EPSILON = 0.01;

  /**
   * Escape a string into the Worker JSON string format.
   *
   * @param {string} value
   * @returns {string}
   */
  function escapeJson(value) {
    var result = '"';
    for (var i = 0; i < value.length; i += 1) {
      var ch = value.charAt(i);
      var code = value.charCodeAt(i);
      switch (ch) {
        case '"':
          result += '\\"';
          break;
        case '\\':
          result += '\\\\';
          break;
        case '\b':
          result += '\\b';
          break;
        case '\f':
          result += '\\f';
          break;
        case '\n':
          result += '\\n';
          break;
        case '\r':
          result += '\\r';
          break;
        case '\t':
          result += '\\t';
          break;
        default:
          if (code < 0x20) {
            result += '\\u' + code.toString(16).padStart(4, '0');
          } else {
            result += ch;
          }
          break;
      }
    }
    result += '"';
    return result;
  }

  // elementAttributesJson in WebEnhancerSupport.kt is a @JsFun around
  // JSON.stringify of the [name, value] pairs; the result is already JSON and
  // is appended verbatim, never re-escaped.
  function elementAttributesJson(element) {
    return JSON.stringify(Array.from(element.attributes || [], function (attribute) {
      return [attribute.name, attribute.value];
    }));
  }

  /**
   * Serialize a lowered paragraph into the Worker layout request text, matching
   * the Kotlin builder field for field.
   *
   * @param {Element} paragraph
   * @param {LoweredParagraph} lowered
   * @param {number} width
   * @param {number} firstLineIndentIc
   * @returns {string}
   */
  function workerLayoutRequestJson(paragraph, lowered, width, firstLineIndentIc) {
    var textSpans = lowered.spans.map(function (span) {
      return [
        String(span.start),
        String(span.end),
        span.style.fontFamilies.join(WORKER_FAMILY_SEPARATOR),
        String(span.style.fontSize),
        String(span.style.fontWeight),
        String(span.style.italic),
        String(span.style.baselineShift),
      ].join(WORKER_FIELD_SEPARATOR);
    }).join(WORKER_RECORD_SEPARATOR);

    // InlineBoxOuterSpacing default chain: the wire never carries outer
    // spacing. The Kotlin decode (MarkdownParagraphLowering.kt
    // decodeInlineBoxes) constructs InlineBoxSpan with the constructor default
    // InlineBoxOuterSpacing.Narrow (core TextModel.kt), so every inlineBoxes
    // join field and renderInlineBoxes entry emits the string Narrow.
    var inlineBoxes = lowered.inlineBoxes.map(function (box) {
      return [
        String(box.start),
        String(box.end),
        String(box.inlineStart),
        String(box.inlineEnd),
        'Narrow',
      ].join(WORKER_FIELD_SEPARATOR);
    }).join(WORKER_RECORD_SEPARATOR);

    // LineBreakPolicy decode: the Kotlin decode maps every wire policy string
    // to the same member, so the join always emits ProgressiveTechnical
    // regardless of the source span's policy value.
    var lineBreakSpans = lowered.lineBreakSpans.map(function (span) {
      return [
        String(span.start),
        String(span.end),
        'ProgressiveTechnical',
      ].join(WORKER_FIELD_SEPARATOR);
    }).join(WORKER_RECORD_SEPARATOR);

    // WorkerInlineObjectWire: the same measured geometry the runtime lowering
    // feeds its engine (advance, ascent, descent) so the Worker lays the
    // replacement character out identically; the live element stays on the
    // main thread and enters at commit time.
    var inlineObjects = lowered.inlineObjects.map(function (span) {
      return [
        String(span.start),
        String(span.end),
        String(span.advance),
        String(span.ascent),
        String(span.descent),
      ].join(WORKER_FIELD_SEPARATOR);
    }).join(WORKER_RECORD_SEPARATOR);

    // SourceBoundary wire: the Kotlin decode builds a deduped Set, then the
    // builder emits it sorted ascending joined by ",". Array.from(new Set(...))
    // dedupes; the numeric sort keeps the ascending order.
    var sourceBoundaries = Array.from(new Set(lowered.sourceBoundaries))
      .sort(function (a, b) { return a - b; })
      .join(',');

    // WorkerSemanticHierarchyOrder: sourceSpans are collected after their
    // children, so the list index identifies the live element but cannot also
    // describe outer-to-inner replay order.
    var semantics = '[';
    for (var i = 0; i < lowered.sourceSpans.length; i += 1) {
      if (i > 0) semantics += ',';
      var sourceSpan = lowered.sourceSpans[i];
      semantics += '{"start":' + String(sourceSpan.start) +
        ',"end":' + String(sourceSpan.end) +
        ',"tagName":' + escapeJson(sourceSpan.element.tagName.toLowerCase()) +
        ',"attributes":' + elementAttributesJson(sourceSpan.element) +
        ',"sourceIndex":' + String(i) +
        ',"order":' + String(sourceSpan.depth) + '}';
    }

    var renderInlineBoxes = '[';
    for (var j = 0; j < lowered.inlineBoxes.length; j += 1) {
      if (j > 0) renderInlineBoxes += ',';
      var inlineBox = lowered.inlineBoxes[j];
      renderInlineBoxes += '{"start":' + String(inlineBox.start) +
        ',"end":' + String(inlineBox.end) +
        ',"inlineStartPx":' + String(inlineBox.inlineStart) +
        ',"inlineEndPx":' + String(inlineBox.inlineEnd) +
        ',"outerSpacing":' + escapeJson('Narrow') + '}';
    }

    return '{' +
      '"text":' + escapeJson(lowered.text) + ',' +
      '"maxWidthPx":' + String(width) + ',' +
      '"fontFamilies":' + escapeJson(lowered.textStyle.fontFamilies.join(WORKER_FAMILY_SEPARATOR)) + ',' +
      '"fontSizePx":' + String(lowered.textStyle.fontSize) + ',' +
      '"lineHeightPx":' + String(lowered.lineHeight) + ',' +
      '"locale":' + escapeJson(lowered.textStyle.locale) + ',' +
      '"fontWeight":' + String(lowered.textStyle.fontWeight) + ',' +
      '"italic":' + String(lowered.textStyle.italic) + ',' +
      '"firstLineIndentIc":' + String(firstLineIndentIc) + ',' +
      '"sourceBoundaries":' + escapeJson(sourceBoundaries) + ',' +
      '"textSpans":' + escapeJson(textSpans) + ',' +
      '"inlineBoxes":' + escapeJson(inlineBoxes) + ',' +
      '"lineBreakSpans":' + escapeJson(lineBreakSpans) + ',' +
      '"inlineObjects":' + escapeJson(inlineObjects) + ',' +
      '"semantics":' + semantics + '],' +
      '"renderInlineBoxes":' + renderInlineBoxes + '],' +
      '"sourceTag":' + escapeJson(paragraph.tagName.toLowerCase()) +
      '}';
  }

  /**
   * Gate the lowered paragraph against Worker preparation eligibility, compute
   * the responsive line measure, and serialize the request. Returns null when
   * ineligible.
   *
   * @param {Element} paragraph
   * @param {LoweredParagraph} lowered
   * @param {Record<string, unknown>} options
   * @returns {(string|null)}
   */
  function workerLayoutRequest(paragraph, lowered, options) {
    if (globalThis.__TiqianLifecycle.conformingExactFontSessionId(options) == null) return null;
    // WorkerRequestMatchesRuntimeEligibility: inline objects no longer exclude
    // a paragraph from Worker preparation; their measured geometry travels on
    // the request wire and the live elements enter at commit time, the same
    // split the runtime exact path uses. Decorated paragraphs stay excluded
    // because the request wire carries no decoration input; they lower on the
    // main thread, whose LayoutInput carries the decorations, and commit
    // through the same prepared bridge. Every other exclusion mirrors
    // isRuntimeExactPreparedDomEligible so both exact paths adopt one shape.
    if (lowered.decorations.length > 0 ||
        lowered.sourceSpans.some(function (span) {
          return span.inlineBoxStyle.boxDecorationBreak === 'clone' &&
            (Math.abs(span.inlineBoxStyle.inlineStart) >= INLINE_EDGE_EPSILON ||
              Math.abs(span.inlineBoxStyle.inlineEnd) >= INLINE_EDGE_EPSILON);
        }) ||
        lowered.spans.some(function (span) {
          return span.style.locale !== lowered.textStyle.locale;
        })) {
      return null;
    }
    var rawWidth = globalThis.__TiqianResponsiveMeasure.sourceParagraphWidth(paragraph);
    if (!Number.isFinite(rawWidth) || rawWidth <= 0) return null;
    // WorkerLineMeasureMatchesResponsiveGrid: the responsive coordinator
    // intentionally treats widths within the same floor(width / fontSize) cell
    // count as one layout input. Serialize that effective measure, not the
    // transient CSS width observed while a window is being dragged, so
    // preparation and commit use the same Worker plan inside the grid.
    var measure = globalThis.__TiqianResponsiveMeasure.effectiveLineMeasure(
      rawWidth,
      lowered.textStyle.fontSize,
    );
    var firstLineIndentIc = paragraph.tagName.toUpperCase() === 'LI'
      ? 0
      : options.firstLineIndentIc;
    return workerLayoutRequestJson(paragraph, lowered, measure, firstLineIndentIc);
  }

  globalThis.__TiqianWorkerRequest = {
    workerLayoutRequest: workerLayoutRequest,
    workerLayoutRequestJson: workerLayoutRequestJson,
  };
})();