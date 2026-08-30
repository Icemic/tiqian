// Prepared metadata JSON builders for semantic replay, inline object meta,
// and CJK strong semantics. Pure serializers over a LoweredParagraph; no
// instance state.

import type { LoweredParagraph } from "./lowered-paragraph.js";

// Escape a string into valid JSON string characters.
function escapeJson(value: string): string {
  let result = '"';
  for (let i = 0; i < value.length; i += 1) {
    const ch = value.charAt(i);
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
export function preparedSemanticReplayJson(lowered: LoweredParagraph): string {
  let result = '[';
  for (let i = 0; i < lowered.sourceSpans.length; i += 1) {
    if (i > 0) {
      result += ',';
    }
    const span = lowered.sourceSpans[i];
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
export function preparedInlineObjectMetaJson(lowered: LoweredParagraph): string {
  let result = '[';
  for (let i = 0; i < lowered.domInlineObjects.length; i += 1) {
    if (i > 0) {
      result += ',';
    }
    const objectSpan = lowered.domInlineObjects[i];
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
export function preparedCjkStrongSemanticsJson(lowered: LoweredParagraph): string {
  let result = '[';
  let first = true;
  for (let i = 0; i < lowered.sourceSpans.length; i += 1) {
    const span = lowered.sourceSpans[i];
    const weight = span.cjkStrongBaseWeight;
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