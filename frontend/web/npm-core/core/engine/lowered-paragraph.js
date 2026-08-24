/**
 * LoweredParagraph wire model (TsHost runtime port, Slice 1). One module owns
 * the exact plain-object shape produced by `LoweringBuilder.prototype.build()`
 * in markdown-lowering.js and the two paragraph predicates that the Kotlin
 * decode layer in MarkdownParagraphLowering.kt currently implements as
 * extensions. Field names match the wire object character for character; the
 * Kotlin `LoweredParagraph` data class remains the decode target for now.
 */

/**
 * @typedef {Object} TextStyle
 * @property {string[]} fontFamilies
 * @property {number} fontSize
 * @property {number} fontWeight
 * @property {boolean} italic
 * @property {number} baselineShift
 * @property {string} locale
 */

/**
 * @typedef {Object} TextSpan
 * @property {number} start
 * @property {number} end
 * @property {TextStyle} style
 */

/**
 * @typedef {Object} DecorationSpan
 * @property {number} start
 * @property {number} end
 * @property {string} kind Currently always "Emphasis".
 */

/**
 * @typedef {Object} InlineBoxSpan
 * @property {number} start
 * @property {number} end
 * @property {number} inlineStart
 * @property {number} inlineEnd
 */

/**
 * @typedef {Object} InlineObjectSpan
 * @property {number} start
 * @property {number} end
 * @property {number} advance
 * @property {number} ascent
 * @property {number} descent
 */

/**
 * @typedef {Object} DomInlineObject
 * @property {number} start
 * @property {number} end
 * @property {Element} element
 * @property {number} marginRight
 */

/**
 * @typedef {Object} DomInlineBoxStyle
 * @property {number} inlineStart
 * @property {number} inlineEnd
 * @property {number} marginRight
 * @property {number} letterSpacing
 * @property {string} boxDecorationBreak
 */

/**
 * @typedef {Object} DomSourceSpan
 * @property {number} start
 * @property {number} end
 * @property {Element} element
 * @property {number} depth
 * @property {(number|null)} cjkStrongBaseWeight
 * @property {(string|null)} computedColor
 * @property {DomInlineBoxStyle} inlineBoxStyle
 */

/**
 * @typedef {Object} LineBreakSpan
 * @property {number} start
 * @property {number} end
 * @property {string} policy Currently always "ProgressiveTechnical".
 */

/**
 * @typedef {Object} LoweredParagraph
 * @property {string} text
 * @property {TextStyle} textStyle
 * @property {number} lineHeight
 * @property {TextSpan[]} spans
 * @property {DecorationSpan[]} decorations
 * @property {InlineBoxSpan[]} inlineBoxes
 * @property {InlineObjectSpan[]} inlineObjects
 * @property {DomInlineObject[]} domInlineObjects
 * @property {DomSourceSpan[]} sourceSpans
 * @property {number[]} sourceBoundaries
 * @property {LineBreakSpan[]} lineBreakSpans
 */

/**
 * CanonicalPlainParagraph: classifies the shape the prepared plain host path
 * and the re-lowerer promise treat as canonical plain (PreparedPlainHostPromise
 * in WebEnhancerParagraphPipeline.kt): every styled collection on the wire is
 * empty.
 *
 * @param {LoweredParagraph} lowered
 * @returns {boolean}
 */
export function isCanonicalPlainParagraph(lowered) {
  return lowered.spans.length === 0 &&
    lowered.decorations.length === 0 &&
    lowered.inlineBoxes.length === 0 &&
    lowered.inlineObjects.length === 0 &&
    lowered.domInlineObjects.length === 0 &&
    lowered.sourceSpans.length === 0;
}

/**
 * RuntimeExactPreparedDomScope: the runtime prepared-DOM bridge replays styled
 * spans through plan evidence, and the plan wire carries one paragraph locale,
 * so the bridge cannot replay a span shaped under a different one.
 * Locale-mismatching spans fail closed with SpanLocaleMismatchUnsupported.
 *
 * @param {LoweredParagraph} lowered
 * @returns {boolean}
 */
export function isRuntimeExactPreparedDomEligible(lowered) {
  return lowered.spans.every((span) => span.style.locale === lowered.textStyle.locale);
}

/**
 * Escape a string into JSON format matching worker JSON string serialization.
 *
 * @param {string} value
 * @returns {string}
 */
function escapeJson(value) {
  let result = '"';
  for (let i = 0; i < value.length; i += 1) {
    const ch = value[i];
    const code = value.charCodeAt(i);
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
          result += `\\u${code.toString(16).padStart(4, "0")}`;
        } else {
          result += ch;
        }
        break;
    }
  }
  result += '"';
  return result;
}

/**
 * RuntimeRichPreparedDomOptions (ADR 0053 B8.1): source spans become
 * live-source semantics whose sourceIndex addresses the same-order element
 * array and whose order carries the nesting depth as the tie-break.
 *
 * @param {LoweredParagraph} lowered
 * @returns {string}
 */
export function preparedSemanticReplayJson(lowered) {
  let result = "[";
  for (let i = 0; i < lowered.sourceSpans.length; i += 1) {
    if (i > 0) {
      result += ",";
    }
    const span = lowered.sourceSpans[i];
    result += `{"start":${String(span.start)},"end":${String(span.end)},"tagName":${escapeJson(span.element.tagName.toLowerCase())},"sourceIndex":${String(i)},"order":${String(span.depth)}}`;
  }
  result += "]";
  return result;
}

/**
 * Prepared DOM inline objects ride as {start, end, marginRight} metadata
 * paired with an element array. marginRight prints through plain number
 * toString: the compiled Kotlin/JS Float append is n.toString() with no
 * 32-bit rounding, so the wire value passes through unchanged.
 *
 * @param {LoweredParagraph} lowered
 * @returns {string}
 */
export function preparedInlineObjectMetaJson(lowered) {
  let result = "[";
  for (let i = 0; i < lowered.domInlineObjects.length; i += 1) {
    if (i > 0) {
      result += ",";
    }
    const objectSpan = lowered.domInlineObjects[i];
    result += `{"start":${String(objectSpan.start)},"end":${String(objectSpan.end)},"marginRight":${String(objectSpan.marginRight)}}`;
  }
  result += "]";
  return result;
}

/**
 * PreparedCjkStrongSemantics: strong-as-emphasis lowering records the
 * inherited base weight on each weighted source span; the prepared lowerer
 * replays the same marks from this metadata, matched by range equality.
 * Empty unless strong-as-emphasis lowering produced weighted spans.
 *
 * @param {LoweredParagraph} lowered
 * @returns {string}
 */
export function preparedCjkStrongSemanticsJson(lowered) {
  let result = "[";
  let first = true;
  for (let i = 0; i < lowered.sourceSpans.length; i += 1) {
    const span = lowered.sourceSpans[i];
    const weight = span.cjkStrongBaseWeight;
    if (weight == null) {
      continue;
    }
    if (!first) {
      result += ",";
    }
    first = false;
    result += `{"start":${String(span.start)},"end":${String(span.end)},"weight":${String(weight)}}`;
  }
  result += "]";
  return result;
}