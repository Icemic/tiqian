// preparedMetadata (TsHost runtime port, Slice 5a). Prepared metadata JSON
// builders for semantic replay, inline object meta, and CJK strong semantics.
//
// Plain script, no exports: running it installs
// globalThis.__TiqianPreparedMetadata. Two consumers share this file as
// the single source of truth: the npm host (importing it for the side effect)
// and the Kotlin runtime bundle, into which a future gradle bridge task will
// embed this source verbatim. Double installation is guarded.
//
// Embedding constraint: the generator wraps this file in a Kotlin raw string,
// so the source must contain no dollar sign and no triple double-quote
// sequence. Use string concatenation, never template literals.

import type { LoweredParagraph } from "./lowered-paragraph.js";

type PreparedMetadataBuilderFn = (lowered: LoweredParagraph) => string;

export interface PreparedMetadataGlobal {
  preparedSemanticReplayJson: PreparedMetadataBuilderFn;
  preparedInlineObjectMetaJson: PreparedMetadataBuilderFn;
  preparedCjkStrongSemanticsJson: PreparedMetadataBuilderFn;
}

declare global {
  var __TiqianPreparedMetadata: PreparedMetadataGlobal | undefined;
}

(function () {
  if (globalThis.__TiqianPreparedMetadata) return;

  // Escape a string into valid JSON string characters.
  function escapeJson(value: string): string {
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

  // PreparedSemanticReplayJson: inline twin of preparedSemanticReplayJson in
  // lowered-paragraph.js (line 186). The builder now has a single
  // plain-script home shared by every orchestrator.
  function preparedSemanticReplayJson(lowered: LoweredParagraph): string {
    var result = '[';
    for (var i = 0; i < lowered.sourceSpans.length; i += 1) {
      if (i > 0) {
        result += ',';
      }
      var span = lowered.sourceSpans[i];
      result += '{"start":' + String(span.start) +
        ',"end":' + String(span.end) +
        ',"tagName":' + escapeJson(span.element.tagName.toLowerCase()) +
        ',"sourceIndex":' + String(i) +
        ',"order":' + String(span.depth) + '}';
    }
    result += ']';
    return result;
  }

  // PreparedInlineObjectMetaJson: inline twin of preparedInlineObjectMetaJson
  // in lowered-paragraph.js (line 208). The builder now has a single
  // plain-script home shared by every orchestrator.
  function preparedInlineObjectMetaJson(lowered: LoweredParagraph): string {
    var result = '[';
    for (var i = 0; i < lowered.domInlineObjects.length; i += 1) {
      if (i > 0) {
        result += ',';
      }
      var objectSpan = lowered.domInlineObjects[i];
      result += '{"start":' + String(objectSpan.start) +
        ',"end":' + String(objectSpan.end) +
        ',"marginRight":' + String(objectSpan.marginRight) + '}';
    }
    result += ']';
    return result;
  }

  // PreparedCjkStrongSemanticsJson: inline twin of
  // preparedCjkStrongSemanticsJson in lowered-paragraph.js (line 230).
  // The builder now has a single plain-script home shared by every
  // orchestrator.
  function preparedCjkStrongSemanticsJson(lowered: LoweredParagraph): string {
    var result = '[';
    var first = true;
    for (var i = 0; i < lowered.sourceSpans.length; i += 1) {
      var span = lowered.sourceSpans[i];
      var weight = span.cjkStrongBaseWeight;
      if (weight == null) {
        continue;
      }
      if (!first) {
        result += ',';
      }
      first = false;
      result += '{"start":' + String(span.start) +
        ',"end":' + String(span.end) +
        ',"weight":' + String(weight) + '}';
    }
    result += ']';
    return result;
  }

  globalThis.__TiqianPreparedMetadata = {
    preparedSemanticReplayJson: preparedSemanticReplayJson,
    preparedInlineObjectMetaJson: preparedInlineObjectMetaJson,
    preparedCjkStrongSemanticsJson: preparedCjkStrongSemanticsJson,
  };
})();

export {};
